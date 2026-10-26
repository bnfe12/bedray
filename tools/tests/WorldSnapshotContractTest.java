import io.github.andrexarlay.bedray.api.v1.*;
import io.github.andrexarlay.bedray.compute.voxel.VoxelWorldCache;
import java.util.*;

/** Standalone CPU regression suite, executable without game, Vulkan, Gradle or native dependencies. */
public class WorldSnapshotContractTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static CameraSnapshot camera(double x) {
        float[] identity = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        return new CameraSnapshot(x,0,0,identity,identity);
    }
    static class Source implements WorldSnapshotStream.Source {
        int reads; boolean loaded = true; byte cell = SectionSnapshot.OPAQUE_CUBE;
        public boolean loaded(int x,int y,int z) { return loaded; }
        public byte[] readSection(int x,int y,int z) {
            reads++; if (!loaded) return null;
            byte[] data = new byte[4096]; Arrays.fill(data,cell); return data;
        }
    }
    public static void main(String[] args) {
        byte[] cells = new byte[4096]; cells[4095]=SectionSnapshot.UNSUPPORTED;
        SectionSnapshot immutable = new SectionSnapshot(-1,-1,-1,1,cells);
        cells[4095]=0; check(immutable.cell(15,15,15)==SectionSnapshot.UNSUPPORTED);
        var originalCamera=camera(0);
        float[] matrix=originalCamera.inverseProjection(); matrix[0]=5;
        check(originalCamera.inverseProjection()[0]==1);
        var stream=new WorldSnapshotStream(); var source=new Source(); Object world=new Object();
        WorldSnapshot snapshot=null;
        for(int i=0;i<14;i++) {
            int before=source.reads; snapshot=stream.capture(world,camera(-0.1),source);
            check(source.reads-before==2); check(snapshot.sections().size()<=27);
        }
        check(snapshot.sections().size()==27);
        check(snapshot.sections().stream().allMatch(s->s.x()>=-2 && s.x()<=0));
        var cache=new VoxelWorldCache(); cache.accept(snapshot);
        check(cache.occupancy(-1,-1,-1)==SectionSnapshot.OPAQUE_CUBE);
        check(cache.occupancy(1000,0,0)==VoxelWorldCache.UNKNOWN);
        check(cache.previousCamera()==null);
        long revision=snapshot.revision();
        for(int i=0;i<14;i++) snapshot=stream.capture(world,camera(-0.1),source);
        check(snapshot.revision()==revision); cache.accept(snapshot); check(cache.previousCamera()!=null);
        var stableSection=snapshot.sections().getFirst();
        snapshot=stream.capture(world,camera(-0.1),source);
        check(snapshot.sections().contains(stableSection));
        try { snapshot.sections().clear(); throw new AssertionError(); }
        catch(UnsupportedOperationException expected) { checks++; }
        source.cell=SectionSnapshot.UNSUPPORTED;
        for(int i=0;i<14;i++) snapshot=stream.capture(world,camera(-0.1),source);
        cache.accept(snapshot); check(snapshot.revision()>revision);
        check(cache.occupancy(-1,-1,-1)==SectionSnapshot.UNSUPPORTED);
        try { cache.accept(new WorldSnapshot(snapshot.session(),revision,camera(0),List.of())); throw new AssertionError(); }
        catch(IllegalArgumentException expected) { checks++; }
        check(cache.occupancy(-1,-1,-1)==SectionSnapshot.UNSUPPORTED);
        source.loaded=false; snapshot=stream.capture(world,camera(-0.1),source); cache.accept(snapshot);
        check(snapshot.sections().isEmpty()); check(cache.occupancy(-1,-1,-1)==VoxelWorldCache.UNKNOWN);
        source.loaded=true;
        snapshot=stream.capture(world,camera(10000),source);
        check(snapshot.sections().size()==2); check(snapshot.sections().stream().allMatch(s->s.x()>=624));
        long session=snapshot.session();
        snapshot=stream.capture(new Object(),camera(0),source); cache.accept(snapshot);
        check(snapshot.session()!=session); check(cache.previousCamera()==null);
        try { cache.accept(new WorldSnapshot(session,10000,camera(0),List.of())); throw new AssertionError(); }
        catch(IllegalArgumentException expected) { checks++; }
        stream.clear(); snapshot=stream.capture(world,camera(0),source); check(snapshot.sections().size()==2);
        cache.clear(); check(cache.camera()==null); check(cache.sectionCount()==0);
        try { new CameraSnapshot(0,Double.NaN,0,new float[16],new float[16]); throw new AssertionError(); }
        catch(IllegalArgumentException expected) { checks++; }
        try { new SectionSnapshot(0,0,0,0,new byte[1]); throw new AssertionError(); }
        catch(IllegalArgumentException expected) { checks++; }
        var duplicate = snapshot.sections().getFirst();
        try { cache.accept(new WorldSnapshot(snapshot.session(),1,camera(0),List.of(duplicate,duplicate))); throw new AssertionError(); }
        catch(IllegalArgumentException expected) { checks++; }
        System.out.println("World snapshot contract: " + checks + " checks passed");
    }
}
