package io.github.andrexarlay.bedray.compute.voxel;

import io.github.andrexarlay.bedray.api.v1.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Render-thread residency for API 1.6. Absolute section keys survive camera movement. Payload
 * ranges become visible through the directory only after every bounded upload has been recorded.
 * Reused ranges require the recorder's compute-read -> transfer-write -> compute-read barriers. */
public final class StreamedVoxelScene {
    public static final int HEADER_BYTES = 64, ENTRY_BYTES = 48, CELL_BYTES = 32768;
    private static final int EMPTY = 1, RESIDENT = 2, TOMBSTONE = 3;
    private static final int MAX_PROBES = 64;
    private static final int MAX_QUEUED_BYTES = 8 * 1024 * 1024;
    private WorldUpdate latest;
    private long session = -1, atlasGeneration, uploadedBytes, revision;
    private int directoryCapacity, gridBytes, paletteBytes, modelBytes, dataStart;
    private int[] directory;
    private Arena cells, palettes, models;
    private final Map<SectionKey, Page> pages = new HashMap<>();
    private final LinkedHashMap<SectionKey, SectionSnapshot> pending = new LinkedHashMap<>();
    private final LinkedHashSet<Integer> dirty = new LinkedHashSet<>();
    private final Set<SectionKey> evicted = new HashSet<>();
    private final ArrayDeque<SectionKey> invalidations = new ArrayDeque<>();
    private final LinkedHashMap<SectionKey, List<Emitter>> emitterPages = new LinkedHashMap<>();
    private final ArrayList<Emitter> selectedEmitters = new ArrayList<>();
    private Iterator<List<Emitter>> emitterCursor;
    private Upload active;
    private boolean clearDirectory, memoryLimited, changed;
    private long allocationBytes;
    private int emitterFound;
    private String reason = "Waiting for streamed world sections";

    public void accept(WorldUpdate update) {
        Objects.requireNonNull(update);
        WorldCoverageBounds previousBounds=latest==null?null:latest.bounds();
        if (update.reset() || session != update.session() || atlasGeneration != update.atlasGeneration()) {
            clear();
            session = update.session(); atlasGeneration = update.atlasGeneration();
            clearDirectory = true;
        }
        latest = update;
        for (SectionKey key : update.removals()) remove(key);
        // Host capture and delivery are bounded and pause while four provider pages are queued.
        // Accepted deltas are retained; memory pressure must never silently discard an update.
        for (SectionSnapshot section : update.upserts()) {
            SectionKey key = key(section);
            if (!inside(key)) continue;
            if (section.materialGeneration() != 0 && section.materialGeneration() != atlasGeneration) continue;
            Page page = pages.get(key);
            if (page != null && page.revision == section.revision()) continue;
            pending.put(key, section);
        }
        if(previousBounds==null||!previousBounds.equals(update.bounds()))
            for (SectionKey key : new ArrayList<>(pages.keySet())) if (!inside(key)) remove(key);
        pending.keySet().removeIf(key -> !inside(key));
        evicted.removeIf(key -> !inside(key));
        if (active != null && !inside(active.key)) cancelActive();
        long queuedBytes=active==null?0:active.payload.capacity()+16384L;
        for(var section:pending.values())queuedBytes+=queuedBytes(section);
        while(queuedBytes>MAX_QUEUED_BYTES&&!pending.isEmpty()) {
            SectionKey farthest=null;double farDistance=-1;
            for(var key:pending.keySet()){double d=distance(key);if(d>farDistance){farthest=key;farDistance=d;}}
            if(invalidations.size()==64)
                throw new IllegalStateException("Host must drain provider invalidations before delivering more section payloads");
            queuedBytes-=queuedBytes(pending.remove(farthest));
            invalidations.add(farthest);evicted.add(farthest);
        }
    }

    /** Partition the requested scene allocation, bounded by each storage-buffer range. Images and
     * temporal targets belong to the output pipeline and are not reported as scene residency. */
    public void configure(long maxStorageRange) {
        if (latest == null) throw new IllegalStateException("No stream bounds");
        if(latest.bounds().uploadBudgetBytes()<HEADER_BYTES+LightingFrame.BYTES+112+VoxelEmitters.BYTES+ENTRY_BYTES)
            throw new IllegalArgumentException("Voxel upload budget cannot hold frame inputs and a directory entry");
        long requested = latest.bounds().budgetGpuBytes();
        long usable = Math.min(requested - 65536, Math.min(Integer.MAX_VALUE, maxStorageRange) * 2);
        if (usable < 4L * 1024 * 1024) throw new IllegalArgumentException("Voxel scene budget is too small");
        long eligible = (2L * latest.bounds().effectiveRenderDistanceChunks() + 1);
        eligible *= eligible * ((long) latest.bounds().maxSectionY() - latest.bounds().minSectionY() + 1);
        int capacity = 256;
        long directoryLimit = Math.min(eligible * 2, Math.min(262144, usable / (ENTRY_BYTES * 8L)));
        while (capacity < directoryLimit) capacity <<= 1;
        directoryCapacity = capacity;
        dataStart = HEADER_BYTES + directoryCapacity * ENTRY_BYTES;
        long range = Math.min(Integer.MAX_VALUE - 4096L, maxStorageRange);
        gridBytes = aligned((int) Math.min(range, usable * 40 / 100));
        paletteBytes = aligned((int) Math.min(range, usable * 10 / 100));
        modelBytes = aligned((int) Math.min(range, usable * 50 / 100));
        if (gridBytes <= dataStart + CELL_BYTES) throw new IllegalArgumentException("Voxel directory exceeds scene budget");
        directory = new int[directoryCapacity * 12];
        cells = new Arena(gridBytes - dataStart);
        palettes = new Arena(paletteBytes);
        models = new Arena(modelBytes);
        clearDirectory = true;
        allocationBytes = (long) gridBytes + paletteBytes + modelBytes;
    }
    private static int aligned(int bytes) { return bytes & ~4095; }
    private static long queuedBytes(SectionSnapshot section) {
        return CELL_BYTES+16384L+(section.materials()==null?0:section.materials().palette().size()*CubeSectionMaterials.MATERIAL_BYTES)
            +(section.models()==null?0:section.models().palette().size()*16+section.models().quadCount()*ModelQuad.BYTES);
    }
    /** Vulkan memory requirements may round allocations; this exact value is checked before binding. */
    public void allocationBytes(long bytes) { allocationBytes = bytes; }
    public long requestedBudget() { return latest.bounds().budgetGpuBytes(); }
    public int gridBytes() { return gridBytes; }
    public int paletteBytes() { return paletteBytes; }
    public int modelBytes() { return modelBytes; }
    public boolean consumeDirectoryClear() { boolean result = clearDirectory; clearDirectory = false; return result; }
    public int directoryBytes() { return directoryCapacity * ENTRY_BYTES; }
    public int originX() { return Math.multiplyExact(latest.bounds().centerSectionX(),16); }
    public int originY() { return 0; }
    public int originZ() { return Math.multiplyExact(latest.bounds().centerSectionZ(),16); }
    public CameraSnapshot camera() { return latest.camera(); }
    public long atlasGeneration() { return atlasGeneration; }
    public boolean ready() { return latest != null; }
    public long revision() { return revision; }

    public void writeHeader(ByteBuffer target) {
        WorldCoverageBounds b = latest.bounds();
        int radius = b.effectiveRenderDistanceChunks();
        target.putInt((radius * 2 + 1) * 16).putInt((b.maxSectionY()-b.minSectionY()+1)*16)
            .putInt((radius * 2 + 1) * 16).putInt(0);
        target.putInt(-radius*16).putInt(b.minSectionY()*16).putInt(-radius*16).putInt(0);
        target.putInt(b.centerSectionX()).putInt(0).putInt(b.centerSectionZ()).putInt(0);
        target.putInt(directoryCapacity).putInt(directoryCapacity*12).putInt(MAX_PROBES)
            .putInt((latest.renderQuality().diffuseBounce()?1:0)|(latest.renderQuality().translucentOptics()?2:0));
    }

    /** Callback copies at most 64 KiB immediately into the command buffer. Buffer index: grid,
     * palette, models. Directory publication is ordered after all its payload writes. */
    @FunctionalInterface public interface Writer { void write(int buffer, long offset, ByteBuffer bytes); }
    public boolean upload(Writer writer, ByteBuffer scratch, int frameOverheadBytes) {
        if (directory == null || latest == null) return false;
        changed = false; memoryLimited = !evicted.isEmpty(); uploadedBytes=frameOverheadBytes;
        if(memoryLimited)reason="Memory-limited residency; unavailable sections retain raster";
        int budget = Math.max(0, latest.bounds().uploadBudgetBytes() - frameOverheadBytes);
        int commands = 0;
        // Invalidations/removals precede reused payload ranges in this same queue submission.
        while (!dirty.isEmpty() && budget >= ENTRY_BYTES && commands < 128) {
            int slot = dirty.iterator().next(); dirty.remove(slot);
            scratch.clear();
            for (int word=0;word<12;word++) scratch.putInt(directory[slot*12+word]);
            scratch.flip(); writer.write(0,HEADER_BYTES+(long)slot*ENTRY_BYTES,scratch);
            budget -= ENTRY_BYTES; uploadedBytes += ENTRY_BYTES; commands++; changed = true;
        }
        while (budget >= ENTRY_BYTES && commands < 128 && dirty.isEmpty()) {
            if (active == null) {
                if (pending.isEmpty()) break;
                Map.Entry<SectionKey,SectionSnapshot> entry=null;double nearest=Double.POSITIVE_INFINITY;
                for(var candidate:pending.entrySet()){double d=distance(candidate.getKey());if(d<nearest){entry=candidate;nearest=d;}}
                SectionKey key = entry.getKey(); SectionSnapshot section = entry.getValue();
                if (!inside(key)) { pending.remove(key); continue; }
                Page old = pages.get(key);
                if (old != null) { removeResident(key); continue; }
                int slot = slot(key,true);
                if (slot < 0) {
                    memoryLimited = true; reason = "Section directory is full";
                    if(invalidations.size()<64){pending.remove(key);evicted.add(key);invalidations.add(key);}
                    break;
                }
                int paletteSize = section.materials()==null ? 0 : section.materials().palette().size()*CubeSectionMaterials.MATERIAL_BYTES;
                int modelSize = section.models()==null ? 0 : section.models().palette().size()*16+section.models().quadCount()*ModelQuad.BYTES;
                int uniform = uniformEmpty(section);
                int cellOffset = uniform != -1 ? -1 : cells.allocate(CELL_BYTES);
                int paletteOffset = paletteSize==0 ? -1 : palettes.allocate(paletteSize);
                int modelOffset = modelSize==0 ? -1 : models.allocate(modelSize);
                if (uniform == -1 && cellOffset<0 || paletteSize>0 && paletteOffset<0 || modelSize>0 && modelOffset<0) {
                    if (cellOffset>=0) cells.free(cellOffset,CELL_BYTES);
                    if (paletteOffset>=0) palettes.free(paletteOffset,paletteSize);
                    if (modelOffset>=0) models.free(modelOffset,modelSize);
                    memoryLimited = true; reason = "Scene arena budget exhausted; queued sections retain raster";
                    SectionKey farthest = null; double farDistance = distance(key);
                    for(var resident : pages.entrySet()) {
                        if(resident.getValue().cells<0)continue;
                        double candidate=distance(resident.getKey());
                        if(candidate>farDistance){farthest=resident.getKey();farDistance=candidate;}
                    }
                    if(invalidations.size()<64) {
                        if(farthest!=null){evicted.add(farthest);invalidations.add(farthest);removeResident(farthest);}
                        else {pending.remove(key);evicted.add(key);invalidations.add(key);}
                    }
                    break;
                }
                // Admission precedes encoding: a full arena never repacks a large model every frame.
                ByteBuffer payload = pack(section);
                pending.remove(key);
                Page page = new Page(slot,section.revision(),cellOffset,paletteOffset,paletteSize,modelOffset,modelSize,
                    section.materials()==null?0:section.materials().palette().size(),section.models()==null?0:section.models().palette().size(),uniform);
                active = new Upload(key,section,page,payload);
                if (uniform != -1) active.position = CELL_BYTES;
            }
            Upload upload = active;
            int total = upload.payload.limit();
            if (upload.position < total) {
                int end = upload.position < CELL_BYTES ? CELL_BYTES
                    : upload.position < CELL_BYTES+upload.page.paletteSize ? CELL_BYTES+upload.page.paletteSize : total;
                int span = Math.min(Math.min(65536,budget-ENTRY_BYTES),end-upload.position) & ~3;
                if (span<=0) break;
                int buffer; long offset;
                if (upload.position<CELL_BYTES) { buffer=0; offset=dataStart+upload.page.cells+upload.position; }
                else if (upload.position<CELL_BYTES+upload.page.paletteSize) { buffer=1; offset=upload.page.palette+upload.position-CELL_BYTES; }
                else { buffer=2; offset=upload.page.models+upload.position-CELL_BYTES-upload.page.paletteSize; }
                scratch.clear(); scratch.put(upload.payload.duplicate().position(upload.position).limit(upload.position+span)); scratch.flip();
                writer.write(buffer,offset,scratch); upload.position+=span;
                uploadedBytes+=span; budget-=span; commands++; changed=true;
            }
            if (upload.position == total && budget>=ENTRY_BYTES && commands<128) {
                Page page=upload.page; int o=page.slot*12;
                directory[o]=upload.key.x(); directory[o+1]=upload.key.y(); directory[o+2]=upload.key.z();
                directory[o+3]=page.uniform!=-1?EMPTY:RESIDENT;
                directory[o+4]=page.cells<0?0:directoryCapacity*12+page.cells/4;
                directory[o+5]=page.cells<0?0:directoryCapacity*12+(page.cells+16384)/4;
                directory[o+6]=page.palette<0?0:page.palette/CubeSectionMaterials.FACE_BYTES;
                directory[o+7]=page.models<0?0:page.models/4;
                directory[o+8]=page.uniform!=-1?page.uniform:page.modelCount*4;
                directory[o+9]=page.modelCount; directory[o+10]=page.paletteCount; directory[o+11]=0;
                scratch.clear(); for(int word=0;word<12;word++)scratch.putInt(directory[o+word]);scratch.flip();
                writer.write(0,HEADER_BYTES+(long)page.slot*ENTRY_BYTES,scratch);
                uploadedBytes+=ENTRY_BYTES;budget-=ENTRY_BYTES;commands++;changed=true;
                pages.put(upload.key,page); indexEmitters(upload.key,upload.section);
                evicted.remove(upload.key);
                active=null; revision++;
            }
        }
        reason=memoryLimited?reason:pending.isEmpty()&&active==null?"Resident coverage; unavailable sections retain raster":"Uploading section deltas; queued sections retain raster";
        refreshEmitters();
        return changed;
    }

    private static ByteBuffer pack(SectionSnapshot section) {
        int paletteSize=section.materials()==null?0:section.materials().palette().size()*CubeSectionMaterials.MATERIAL_BYTES;
        int modelSize=section.models()==null?0:section.models().palette().size()*16+section.models().quadCount()*ModelQuad.BYTES;
        ByteBuffer output=ByteBuffer.allocate(CELL_BYTES+paletteSize+modelSize).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<4096;i++) {
            int occupancy=section.cell(i&15,i>>4&15,i>>8)&255;
            int reference=occupancy==SectionSnapshot.MODEL?section.models().reference(i):section.materials()==null?0:section.materials().reference(i);
            int light=section.lighting()==null?0:(section.lighting().packed(i)<<16)|(1<<28);
            output.putInt(light|(reference<<8)|occupancy);
        }
        for(int i=0;i<4096;i++)output.putInt(section.tints()==null?0:section.tints().argb(i));
        if(section.materials()!=null)for(var material:section.materials().palette())for(int face=0;face<6;face++) {
            var surface=material.face(face);
            output.putFloat(surface.u()).putFloat(surface.v()).putFloat(surface.duU()).putFloat(surface.duV())
                .putFloat(surface.dvU()).putFloat(surface.dvV()).putInt(surface.tintArgb()).putInt(surface.cellTint()?1:0);
        }
        if(section.models()!=null) {
            int first=0;
            for(var model:section.models().palette()){output.putInt(first).putInt(model.quads().size()).putInt(0).putInt(0);first+=model.quads().size();}
            for(var model:section.models().palette())for(ModelQuad quad:model.quads()) {
                for(int component=0;component<20;component++)output.putFloat(quad.component(component));
                output.putInt(quad.tintArgb()).putInt(quad.flags()).putInt(quad.emission()).putInt(0);
            }
        }
        return output.flip();
    }
    private static int uniformEmpty(SectionSnapshot section) {
        if(section.opaqueCount()!=0||section.modelCount()!=0||section.unsupportedCount()!=0)return -1;
        int first=section.lighting()==null?0:section.lighting().packed(0);
        if(section.lighting()!=null)for(int i=1;i<4096;i++)if(section.lighting().packed(i)!=first)return -1;
        return section.lighting()==null?0:(first<<16)|(1<<28);
    }
    private int slot(SectionKey key,boolean insert) {
        int index=hash(key)&(directoryCapacity-1),tomb=-1;
        for(int probe=0;probe<MAX_PROBES;probe++,index=(index+1)&(directoryCapacity-1)) {
            int o=index*12,state=directory[o+3];
            if(state==0)return insert?(tomb>=0?tomb:index):-1;
            if(state==TOMBSTONE){if(tomb<0)tomb=index;continue;}
            if(directory[o]==key.x()&&directory[o+1]==key.y()&&directory[o+2]==key.z())return index;
        }
        return insert?tomb:-1;
    }
    private static int hash(SectionKey key) {
        int h=key.x()*0x8da6b343^key.y()*0xd8163841^key.z()*0xcb1ab31f;
        h^=h>>>16;h*=0x7feb352d;h^=h>>>15;return h;
    }
    private boolean inside(SectionKey key) {
        var b=latest.bounds();int r=b.effectiveRenderDistanceChunks();
        return Math.abs((long)key.x()-b.centerSectionX())<=r&&Math.abs((long)key.z()-b.centerSectionZ())<=r
            &&key.y()>=b.minSectionY()&&key.y()<=b.maxSectionY();
    }
    private static SectionKey key(SectionSnapshot section){return new SectionKey(section.x(),section.y(),section.z());}
    private double distance(SectionKey key){var c=latest.camera();double x=key.x()*16.0+8-c.x(),y=key.y()*16.0+8-c.y(),z=key.z()*16.0+8-c.z();return x*x+y*y+z*z;}
    private void remove(SectionKey key){pending.remove(key);evicted.remove(key);if(active!=null&&active.key.equals(key))cancelActive();removeResident(key);}
    private void removeResident(SectionKey key) {
        Page page=pages.remove(key);if(page==null)return;
        directory[page.slot*12+3]=TOMBSTONE;dirty.add(page.slot);
        release(page); List<Emitter> removed=emitterPages.remove(key);
        if(removed!=null){emitterFound-=removed.size();selectedEmitters.removeAll(removed);emitterCursor=null;}
        revision++;
    }
    private void cancelActive(){release(active.page);active=null;}
    private void release(Page page){if(page.cells>=0)cells.free(page.cells,CELL_BYTES);if(page.palette>=0)palettes.free(page.palette,page.paletteSize);if(page.models>=0)models.free(page.models,page.modelSize);}
    public void clear() {
        latest=null;session=-1;atlasGeneration=0;pending.clear();pages.clear();dirty.clear();evicted.clear();invalidations.clear();active=null;
        if(directory!=null)Arrays.fill(directory,0);if(cells!=null)cells.reset();if(palettes!=null)palettes.reset();if(models!=null)models.reset();
        emitterPages.clear();selectedEmitters.clear();emitterCursor=null;emitterFound=0;clearDirectory=true;revision++;
    }
    public WorldStreamingStatus status() {
        if(latest==null)return WorldStreamingStatus.unavailable();
        var b=latest.bounds();return new WorldStreamingStatus(true,b.requestedRenderDistanceChunks(),b.effectiveRenderDistanceChunks(),
            pages.size(),pending.size()+(active==null?0:1),allocationBytes,b.budgetGpuBytes(),uploadedBytes,
            latest.discoveredSections(),latest.emptySections(),memoryLimited,reason);
    }
    public int emitterCount(){return selectedEmitters.size();}
    public List<SectionKey> drainInvalidations(){if(invalidations.isEmpty())return List.of();List<SectionKey> result=List.copyOf(invalidations);invalidations.clear();return result;}
    private record Emitter(double x,double y,double z,float radius,int level,int cellX,int cellY,int cellZ) {}
    private void indexEmitters(SectionKey key,SectionSnapshot section) {
        if(section.lighting()==null)return;
        ArrayList<Emitter> emitters=new ArrayList<>();
        for(int i=0;i<4096;i++) {
            int level=section.lighting().packed(i)>>8&15;if(level==0)continue;
            // Keep bounded candidates per section; remaining sources retain vanilla block-light fallback.
            if(emitters.size()==128)break;
            float[] sphere=VoxelEmitters.sphere(section,i);
            int x=key.x()*16+(i&15),y=key.y()*16+(i>>4&15),z=key.z()*16+(i>>8);
            emitters.add(new Emitter(x+sphere[0],y+sphere[1],z+sphere[2],sphere[3],level,x,y,z));
        }
        emitterPages.put(key,emitters);emitterFound+=emitters.size();emitterCursor=null;
        for(Emitter emitter:emitters)consider(emitter);
    }
    private double distance(Emitter emitter){var c=latest.camera();double x=emitter.x-c.x(),y=emitter.y-c.y(),z=emitter.z-c.z();return x*x+y*y+z*z;}
    private void consider(Emitter emitter) {
        if(selectedEmitters.contains(emitter))return;
        if(selectedEmitters.size()<128){selectedEmitters.add(emitter);return;}
        int farthest=0;double farDistance=-1;
        for(int i=0;i<selectedEmitters.size();i++){double d=distance(selectedEmitters.get(i));if(d>farDistance){farthest=i;farDistance=d;}}
        if(distance(emitter)<farDistance)selectedEmitters.set(farthest,emitter);
    }
    private void refreshEmitters() {
        // At most eight cached sections per frame, rather than rebuilding the entire scene's emitters.
        for(int i=0;i<8&&!emitterPages.isEmpty();i++) {
            if(emitterCursor==null||!emitterCursor.hasNext())emitterCursor=emitterPages.values().iterator();
            for(Emitter emitter:emitterCursor.next())consider(emitter);
        }
    }
    public void writeEmitters(ByteBuffer target) {
        for(int i=0;i<128;i++) {
            if(i<selectedEmitters.size()) {
                Emitter e=selectedEmitters.get(i);target.putFloat((float)(e.x-originX())).putFloat((float)e.y).putFloat((float)(e.z-originZ())).putFloat(e.radius)
                    .putFloat(e.level).putFloat(e.cellX-originX()).putFloat(e.cellY).putFloat(e.cellZ-originZ());
            }else for(int j=0;j<8;j++)target.putFloat(0);
        }
    }
    private record Page(int slot,long revision,int cells,int palette,int paletteSize,int models,int modelSize,int paletteCount,int modelCount,int uniform) {}
    private static final class Upload {
        final SectionKey key;final SectionSnapshot section;final Page page;final ByteBuffer payload;int position;
        Upload(SectionKey key,SectionSnapshot section,Page page,ByteBuffer payload){this.key=key;this.section=section;this.page=page;this.payload=payload;}
    }
    /** Coalescing first-fit arena. No relocation of live pages and no presumed frame completion. */
    private static final class Arena {
        final int capacity;final TreeMap<Integer,Integer> free=new TreeMap<>();
        Arena(int capacity){this.capacity=capacity;reset();}void reset(){free.clear();free.put(0,capacity);}
        int allocate(int size) {
            size=(size+15)&~15;
            for(var entry:free.entrySet())if(entry.getValue()>=size){int start=entry.getKey(),remaining=entry.getValue()-size;free.remove(start);if(remaining>0)free.put(start+size,remaining);return start;}
            return -1;
        }
        void free(int start,int size) {
            size=(size+15)&~15;var lower=free.lowerEntry(start);
            if(lower!=null&&lower.getKey()+lower.getValue()==start){start=lower.getKey();size+=lower.getValue();free.remove(lower.getKey());}
            var upper=free.ceilingEntry(start);if(upper!=null&&start+size==upper.getKey()){size+=upper.getValue();free.remove(upper.getKey());}
            free.put(start,size);
        }
    }
}
