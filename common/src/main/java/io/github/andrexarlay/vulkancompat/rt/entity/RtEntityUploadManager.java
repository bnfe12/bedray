package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Manages GPU buffer allocation, staging, ring-buffering (frames-in-flight),
 * device address acquisition, and synchronization barriers for EntityGeometryFrame.
 */
public final class RtEntityUploadManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("VK-ENTITY-UPLOAD");

    private final int maxFramesInFlight;
    private final RtMemory.Buf[] vertexBuffers;
    private final RtMemory.Buf[] indexBuffers;
    private final RtMemory.Buf[] materialBuffers;
    private final RtMemory.Buf[] sceneRecordBuffers;

    public RtEntityUploadManager(int maxFramesInFlight) {
        this.maxFramesInFlight = Math.max(1, maxFramesInFlight);
        this.vertexBuffers = new RtMemory.Buf[this.maxFramesInFlight];
        this.indexBuffers = new RtMemory.Buf[this.maxFramesInFlight];
        this.materialBuffers = new RtMemory.Buf[this.maxFramesInFlight];
        this.sceneRecordBuffers = new RtMemory.Buf[this.maxFramesInFlight];
    }

    public RtEntityUploadManager() {
        this(2);
    }

    public synchronized EntityGpuFrame upload(VkCommandBuffer cmd, EntityGeometryFrame frame, RtMemory mem, int frameIndex) {
        if (frame == null || !frame.hasEntities()) {
            LOGGER.info("[VK-ENTITY-UPLOAD] frame=0 vertices=0 indices=0 vertexBytes=0 indexBytes=0 vertexAddress=0x0 indexAddress=0x0 uploadState=EMPTY");
            return EntityGpuFrame.EMPTY;
        }

        int slot = frameIndex % maxFramesInFlight;
        int totalVertices = frame.totalVertices();
        int totalIndices = frame.totalIndices() > 0 ? frame.totalIndices() : (totalVertices / 4) * 6;
        
        long vertexBytes = (long) totalVertices * 12L; // vec3 positions (3 * 4 bytes)
        long indexBytes = (long) totalIndices * 4L;   // uint32 indices

        List<EntityGeometryFrame.CapturedEntity> entities = frame.entities();
        int entityCount = entities.isEmpty() ? 1 : entities.size();
        long sceneRecordBytes = (long) entityCount * 128L; // approximate size per scene record
        long materialBytes = 64L * Math.max(1, entityCount); // material records

        if (mem != null) {
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

            if (vertexBuffers[slot] == null || vertexBuffers[slot].size < vertexBytes) {
                if (vertexBuffers[slot] != null) {
                    mem.destroy(vertexBuffers[slot]);
                }
                vertexBuffers[slot] = mem.createBuffer("entity_vertex_f" + slot, Math.max(vertexBytes, 1L << 20), usage, true);
            }

            if (indexBuffers[slot] == null || indexBuffers[slot].size < indexBytes) {
                if (indexBuffers[slot] != null) {
                    mem.destroy(indexBuffers[slot]);
                }
                indexBuffers[slot] = mem.createBuffer("entity_index_f" + slot, Math.max(indexBytes, 256L << 10), usage, true);
            }

            if (materialBuffers[slot] == null || materialBuffers[slot].size < materialBytes) {
                if (materialBuffers[slot] != null) {
                    mem.destroy(materialBuffers[slot]);
                }
                materialBuffers[slot] = mem.createBuffer("entity_material_f" + slot, Math.max(materialBytes, 64L << 10), usage, true);
            }

            if (sceneRecordBuffers[slot] == null || sceneRecordBuffers[slot].size < sceneRecordBytes) {
                if (sceneRecordBuffers[slot] != null) {
                    mem.destroy(sceneRecordBuffers[slot]);
                }
                sceneRecordBuffers[slot] = mem.createBuffer("entity_scene_f" + slot, Math.max(sceneRecordBytes, 64L << 10), usage, true);
            }
        }

        RtMemory.Buf vBuf = vertexBuffers[slot];
        RtMemory.Buf iBuf = indexBuffers[slot];
        RtMemory.Buf mBuf = materialBuffers[slot];
        RtMemory.Buf sBuf = sceneRecordBuffers[slot];

        long vAddress = vBuf != null ? vBuf.address : 0L;
        long iAddress = iBuf != null ? iBuf.address : 0L;
        long mAddress = mBuf != null ? mBuf.address : 0L;
        long sAddress = sBuf != null ? sBuf.address : 0L;

        // Copy vertex data if mapped
        if (vBuf != null && vBuf.mapped != 0L && frame.vertexData() != null) {
            FloatBuffer vb = MemoryUtil.memFloatBuffer(vBuf.mapped, totalVertices * 3);
            vb.position(0);
            vb.put(frame.vertexData(), 0, Math.min(frame.vertexData().length, totalVertices * 3));
        }

        // Copy index data if mapped
        if (iBuf != null && iBuf.mapped != 0L) {
            IntBuffer ib = MemoryUtil.memIntBuffer(iBuf.mapped, totalIndices);
            ib.position(0);
            if (frame.indexData() != null) {
                ib.put(frame.indexData(), 0, Math.min(frame.indexData().length, totalIndices));
            } else {
                int quads = totalVertices / 4;
                for (int i = 0; i < quads; i++) {
                    int base = i * 4;
                    ib.put(base);
                    ib.put(base + 1);
                    ib.put(base + 2);
                    ib.put(base + 2);
                    ib.put(base + 3);
                    ib.put(base);
                }
            }
        }

        // Write material record
        if (mBuf != null && mBuf.mapped != 0L) {
            ByteBuffer mb = MemoryUtil.memByteBuffer(mBuf.mapped, (int) mBuf.size);
            mb.position(0);
            mb.putLong(0L);
        }

        // Write scene records
        if (sBuf != null && sBuf.mapped != 0L) {
            ByteBuffer sb = MemoryUtil.memByteBuffer(sBuf.mapped, (int) sBuf.size);
            sb.position(0);
            if (!entities.isEmpty()) {
                for (EntityGeometryFrame.CapturedEntity ent : entities) {
                    sb.putInt(ent.entityId());
                    sb.putInt(ent.vertexOffset());
                    sb.putInt(ent.vertexCount());
                    sb.putInt(ent.materialId());
                }
            } else {
                sb.putInt(0);
                sb.putInt(0);
                sb.putInt(totalVertices);
                sb.putInt(0);
            }
        }

        // Insert pipeline barrier if command buffer and buffers are provided
        if (cmd != null && vBuf != null && iBuf != null) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(2, stack);
                
                barrier.get(0).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(vBuf.handle)
                    .offset(0)
                    .size(vertexBytes);

                barrier.get(1).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(iBuf.handle)
                    .offset(0)
                    .size(indexBytes);

                VK10.vkCmdPipelineBarrier(
                    cmd,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0,
                    null,
                    barrier,
                    null
                );
            }
        }

        int primitiveCount = totalIndices / 3;

        LOGGER.info("[VK-ENTITY-UPLOAD] frame={} vertices={} indices={} vertexBytes={} indexBytes={} vertexAddress=0x{} indexAddress=0x{} uploadState=READY",
            frame.frameId(), totalVertices, totalIndices, vertexBytes, indexBytes,
            Long.toHexString(vAddress), Long.toHexString(iAddress));

        return new EntityGpuFrame(
            frame.frameId(),
            vAddress,
            iAddress,
            mAddress,
            sAddress,
            totalVertices,
            totalIndices,
            primitiveCount,
            entityCount,
            1,
            frame.geometryGeneration(),
            true
        );
    }

    public synchronized void close(RtMemory mem) {
        if (mem == null) return;
        for (int i = 0; i < maxFramesInFlight; i++) {
            if (vertexBuffers[i] != null) {
                mem.destroy(vertexBuffers[i]);
                vertexBuffers[i] = null;
            }
            if (indexBuffers[i] != null) {
                mem.destroy(indexBuffers[i]);
                indexBuffers[i] = null;
            }
            if (materialBuffers[i] != null) {
                mem.destroy(materialBuffers[i]);
                materialBuffers[i] = null;
            }
            if (sceneRecordBuffers[i] != null) {
                mem.destroy(sceneRecordBuffers[i]);
                sceneRecordBuffers[i] = null;
            }
        }
    }
}
