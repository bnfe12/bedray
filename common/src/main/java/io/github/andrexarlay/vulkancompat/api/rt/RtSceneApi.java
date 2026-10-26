package io.github.andrexarlay.vulkancompat.api.rt;

import io.github.andrexarlay.vulkancompat.rt.entity.CapturedVertexConsumer;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified RT Scene API for {@code Bedray Core} and external mods (Create contraptions, flywheel/instanced meshes, custom
 * block/entity renderers).
 *
 * <p>Registered meshes and active instances feed directly into the unified RT dynamic geometry stream
 * ({@link EntityCaptureManager} / {@code RtEntityGeometryManager}) so they participate in the RT TLAS (shadows, reflections,
 * GI, primary rays) and carry exact per-instance previous-frame world transforms for temporal accumulation and DLSS/XeSS/FSR
 * motion vectors.
 */
public interface RtSceneApi {

    /**
     * Returns the singleton {@link RtSceneApi} implementation backed by the unified RT dynamic geometry stream.
     */
    static RtSceneApi getInstance() {
        return DefaultRtSceneApi.INSTANCE;
    }

    /**
     * Emits all active bridge instances into the current frame's vertex, index, and entity lists.
     */
    static void emitActiveInstances(CapturedVertexConsumer out,
                                    List<EntityGeometryFrame.CapturedEntity> outEntities) {
        DefaultRtSceneApi.INSTANCE.emitInto(out, outEntities);
    }

    /**
     * Registers a reusable mesh in model/local space.
     *
     * @param vertices    byte buffer positioned at the start of vertex data (little-endian)
     * @param vertexCount number of vertices in {@code vertices}
     * @param format      vertex layout descriptor
     * @param indices     optional 32-bit int index buffer (little-endian); if {@code null}, quads (multiples of 4 vertices)
     *                    or triangles (multiples of 3 vertices) are indexed automatically
     * @param indexCount  number of triangle indices (ignored when {@code indices == null})
     * @param layer       material/pass classification ({@link RtLayer})
     * @return a valid {@link RtMeshHandle}, or {@link RtMeshHandle#INVALID} if input is empty
     */
    RtMeshHandle registerMesh(ByteBuffer vertices, int vertexCount, RtVertexFormat format,
                              @Nullable ByteBuffer indices, int indexCount, RtLayer layer);

    /**
     * Adds an active instance of a registered mesh into the RT scene with current and previous-frame transforms for motion vectors.
     *
     * @param mesh               registered mesh handle
     * @param worldTransform     model-to-camera-relative (or world) transform for the current frame
     * @param prevWorldTransform model-to-camera-relative (or world) transform for the previous frame (for motion vectors)
     * @param texture            optional Minecraft texture {@link Identifier} (packed into {@link EntityTextureAtlas}; {@code null} = white)
     * @param blockLight         block light level ({@code 0..15} or encoded {@code 0..240})
     * @param skyLight           sky light level ({@code 0..15} or encoded {@code 0..240})
     * @return a valid {@link RtInstanceHandle}, or {@link RtInstanceHandle#INVALID} if {@code mesh} is not registered
     */
    RtInstanceHandle addInstance(RtMeshHandle mesh, Matrix4f worldTransform, Matrix4f prevWorldTransform,
                                 @Nullable Identifier texture, int blockLight, int skyLight);

    /**
     * Updates the current and previous-frame transforms and light levels of an existing active instance.
     */
    void updateInstance(RtInstanceHandle instance, Matrix4f worldTransform, Matrix4f prevWorldTransform,
                        int blockLight, int skyLight);

    /**
     * Removes a registered mesh and any active instances referencing it.
     */
    void removeMesh(RtMeshHandle mesh);

    /**
     * Removes a single active instance from the RT scene.
     */
    void removeInstance(RtInstanceHandle instance);

    /**
     * Removes all registered meshes and active instances (e.g. on world unload).
     */
    void clear();

    /** Number of currently registered meshes. */
    int meshCount();

    /** Number of currently active instances. */
    int instanceCount();
}

final class DefaultRtSceneApi implements RtSceneApi {
    static final DefaultRtSceneApi INSTANCE = new DefaultRtSceneApi();

    record StoredMesh(
        RtMeshHandle handle,
        float[] posX,
        float[] posY,
        float[] posZ,
        int[] colorAbgr,
        float[] u,
        float[] v,
        int[] packedLight,
        float[] normX,
        float[] normY,
        float[] normZ,
        int[] indices
    ) {}

    static final class StoredInstance {
        final RtInstanceHandle handle;
        final int entityId;
        final Matrix4f worldTransform = new Matrix4f();
        final Matrix4f prevWorldTransform = new Matrix4f();
        @Nullable final Identifier texture;
        int blockLight;
        int skyLight;

        StoredInstance(RtInstanceHandle handle, int entityId, Matrix4f worldTransform, Matrix4f prevWorldTransform,
                       @Nullable Identifier texture, int blockLight, int skyLight) {
            this.handle = handle;
            this.entityId = entityId;
            this.worldTransform.set(worldTransform);
            this.prevWorldTransform.set(prevWorldTransform != null ? prevWorldTransform : worldTransform);
            this.texture = texture;
            this.blockLight = normalizeLightByte(blockLight);
            this.skyLight = normalizeLightByte(skyLight);
        }
    }

    private final AtomicLong nextMeshId = new AtomicLong(1L);
    private final AtomicLong nextInstanceId = new AtomicLong(1L);
    private final Map<Long, StoredMesh> meshes = new LinkedHashMap<>();
    private final Map<Long, StoredInstance> instances = new LinkedHashMap<>();

    /**
     * Largest accepted vertex count of one mesh. The API is called by other mods, so the arithmetic below cannot assume the
     * caller is sane: {@code vertexCount * stride} in int overflows past 2^31/stride and would then read outside the buffer.
     * A real entity model is thousands of vertices.
     */
    private static final int MAX_MESH_VERTICES = 1 << 22;
    /** Largest accepted index count; keeps {@code triCount * 3} (the built triangle array) inside int range. */
    private static final int MAX_MESH_INDICES = 3 << 22;

    static int normalizeLightByte(int levelOrByte) {
        if (levelOrByte <= 0) {
            return 0;
        }
        if (levelOrByte <= 15) {
            return Math.min(240, levelOrByte * 16);
        }
        return Math.min(240, levelOrByte & 0xFF);
    }

    @Override
    public synchronized RtMeshHandle registerMesh(ByteBuffer vertices, int vertexCount, RtVertexFormat format,
                                                  @Nullable ByteBuffer indices, int indexCount, RtLayer layer) {
        if (vertices == null || vertexCount <= 0 || format == null || layer == null) {
            return RtMeshHandle.INVALID;
        }
        if (vertexCount > MAX_MESH_VERTICES) {
            throw new IllegalArgumentException("vertexCount " + vertexCount + " exceeds the API limit " + MAX_MESH_VERTICES);
        }
        if (indexCount > MAX_MESH_INDICES) {
            throw new IllegalArgumentException("indexCount " + indexCount + " exceeds the API limit " + MAX_MESH_INDICES);
        }
        int stride = format.strideBytes();
        int basePos = vertices.position();
        long neededVertexBytes = (long) vertexCount * stride;
        if (vertices.remaining() < neededVertexBytes) {
            throw new IllegalArgumentException("vertices buffer has " + vertices.remaining()
                + " bytes remaining, need at least " + neededVertexBytes);
        }
        ByteBuffer vb = vertices.slice().order(ByteOrder.LITTLE_ENDIAN);

        int[] triIndices;
        if (indices != null && indexCount >= 3) {
            int triCount = indexCount / 3;
            long neededIndexBytes = (long) triCount * 3 * 4;
            if (indices.remaining() < neededIndexBytes) {
                throw new IllegalArgumentException("indices buffer has " + indices.remaining()
                    + " bytes remaining, need at least " + neededIndexBytes);
            }
            ByteBuffer ib = indices.slice().order(ByteOrder.LITTLE_ENDIAN);
            triIndices = new int[triCount * 3];
            for (int i = 0; i < triIndices.length; i++) {
                int idx = ib.getInt(i * 4);
                triIndices[i] = (idx >= 0 && idx < vertexCount) ? idx : 0;
            }
        } else if (vertexCount >= 4 && (vertexCount % 4) == 0) {
            int quads = vertexCount / 4;
            triIndices = new int[quads * 6];
            for (int q = 0; q < quads; q++) {
                int b = q * 4;
                int o = q * 6;
                triIndices[o] = b;
                triIndices[o + 1] = b + 1;
                triIndices[o + 2] = b + 2;
                triIndices[o + 3] = b + 2;
                triIndices[o + 4] = b + 3;
                triIndices[o + 5] = b;
            }
        } else if (vertexCount >= 3) {
            int tris = vertexCount / 3;
            triIndices = new int[tris * 3];
            for (int i = 0; i < triIndices.length; i++) {
                triIndices[i] = i;
            }
        } else {
            return RtMeshHandle.INVALID;
        }

        float[] px = new float[vertexCount];
        float[] py = new float[vertexCount];
        float[] pz = new float[vertexCount];
        int[] col = new int[vertexCount];
        float[] u = new float[vertexCount];
        float[] v = new float[vertexCount];
        int[] lt = new int[vertexCount];
        float[] nx = new float[vertexCount];
        float[] ny = new float[vertexCount];
        float[] nz = new float[vertexCount];

        for (int i = 0; i < vertexCount; i++) {
            int row = i * stride;
            px[i] = vb.getFloat(row + format.posOffset());
            py[i] = vb.getFloat(row + format.posOffset() + 4);
            pz[i] = vb.getFloat(row + format.posOffset() + 8);
            col[i] = format.colorOffset() >= 0 && format.colorOffset() + 4 <= stride
                ? vb.getInt(row + format.colorOffset())
                : 0xFFFFFFFF;
            if (format.uvOffset() >= 0 && format.uvOffset() + 8 <= stride) {
                u[i] = vb.getFloat(row + format.uvOffset());
                v[i] = vb.getFloat(row + format.uvOffset() + 4);
            }
            lt[i] = format.lightOffset() >= 0 && format.lightOffset() + 4 <= stride
                ? vb.getInt(row + format.lightOffset())
                : -1;
            if (format.normalOffset() >= 0) {
                if (format.byteNormals() && format.normalOffset() + 3 <= stride) {
                    float bx = vb.get(row + format.normalOffset()) / 127f;
                    float by = vb.get(row + format.normalOffset() + 1) / 127f;
                    float bz = vb.get(row + format.normalOffset() + 2) / 127f;
                    float len = (float) Math.sqrt(bx * bx + by * by + bz * bz);
                    if (len > 1e-4f) {
                        nx[i] = bx / len;
                        ny[i] = by / len;
                        nz[i] = bz / len;
                    } else {
                        ny[i] = 1f;
                    }
                } else if (!format.byteNormals() && format.normalOffset() + 12 <= stride) {
                    nx[i] = vb.getFloat(row + format.normalOffset());
                    ny[i] = vb.getFloat(row + format.normalOffset() + 4);
                    nz[i] = vb.getFloat(row + format.normalOffset() + 8);
                } else {
                    ny[i] = 1f;
                }
            } else {
                ny[i] = 1f;
            }
        }
        vertices.position(basePos);

        long id = nextMeshId.getAndIncrement();
        RtMeshHandle handle = new RtMeshHandle(id, vertexCount, triIndices.length, layer);
        meshes.put(id, new StoredMesh(handle, px, py, pz, col, u, v, lt, nx, ny, nz, triIndices));
        return handle;
    }

    @Override
    public synchronized RtInstanceHandle addInstance(RtMeshHandle mesh, Matrix4f worldTransform, Matrix4f prevWorldTransform,
                                                     @Nullable Identifier texture, int blockLight, int skyLight) {
        if (mesh == null || !mesh.isValid() || !meshes.containsKey(mesh.id()) || worldTransform == null) {
            return RtInstanceHandle.INVALID;
        }
        long id = nextInstanceId.getAndIncrement();
        // Negative entityId range (-1000 - id) distinguishes bridge instances from vanilla entities (-1 or >= 0)
        int entityId = -1000 - (int) (id & 0x3FFFFFFFL);
        RtInstanceHandle handle = new RtInstanceHandle(id, mesh);
        instances.put(id, new StoredInstance(handle, entityId, worldTransform, prevWorldTransform, texture, blockLight, skyLight));
        return handle;
    }

    @Override
    public synchronized void updateInstance(RtInstanceHandle instance, Matrix4f worldTransform, Matrix4f prevWorldTransform,
                                            int blockLight, int skyLight) {
        if (instance == null || !instance.isValid() || worldTransform == null) {
            return;
        }
        StoredInstance st = instances.get(instance.id());
        if (st != null) {
            st.worldTransform.set(worldTransform);
            st.prevWorldTransform.set(prevWorldTransform != null ? prevWorldTransform : worldTransform);
            st.blockLight = normalizeLightByte(blockLight);
            st.skyLight = normalizeLightByte(skyLight);
        }
    }

    @Override
    public synchronized void removeMesh(RtMeshHandle mesh) {
        if (mesh == null) {
            return;
        }
        meshes.remove(mesh.id());
        instances.entrySet().removeIf(e -> e.getValue().handle.mesh().id() == mesh.id());
    }

    @Override
    public synchronized void removeInstance(RtInstanceHandle instance) {
        if (instance == null) {
            return;
        }
        instances.remove(instance.id());
    }

    @Override
    public synchronized void clear() {
        meshes.clear();
        instances.clear();
    }

    @Override
    public synchronized int meshCount() {
        return meshes.size();
    }

    @Override
    public synchronized int instanceCount() {
        return instances.size();
    }

    /**
     * Appends all active bridge instances into the current {@link EntityCaptureManager} frame before {@code endFrame()}
     * finalises the interleaved vertex/index arrays.
     */
    public synchronized void emitInto(CapturedVertexConsumer out,
                                      List<EntityGeometryFrame.CapturedEntity> outEntities) {
        if (instances.isEmpty()) {
            return;
        }
        Vector4f tmpPos = new Vector4f();
        Vector3f tmpNorm = new Vector3f();
        Matrix3f normalMat = new Matrix3f();

        List<Long> stale = null;
        for (StoredInstance inst : instances.values()) {
            StoredMesh mesh = meshes.get(inst.handle.mesh().id());
            if (mesh == null) {
                if (stale == null) {
                    stale = new ArrayList<>();
                }
                stale.add(inst.handle.id());
                continue;
            }
            int texSlot = resolveTextureSlot(inst.texture);
            int startVertex = out.vertexFloatCount() / CapturedVertexConsumer.FLOATS_PER_VERTEX;
            int startIndex = out.indexCount();
            int vCount = mesh.handle().vertexCount();
            inst.worldTransform.normal(normalMat);

            for (int i = 0; i < vCount; i++) {
                tmpPos.set(mesh.posX()[i], mesh.posY()[i], mesh.posZ()[i], 1.0f).mul(inst.worldTransform);
                tmpNorm.set(mesh.normX()[i], mesh.normY()[i], mesh.normZ()[i]).mul(normalMat);
                if (tmpNorm.lengthSquared() > 1e-8f) {
                    tmpNorm.normalize();
                } else {
                    tmpNorm.set(0f, 1f, 0f);
                }

                int rawLt = mesh.packedLight()[i];
                int blk = rawLt >= 0 ? (rawLt & 0xFF) : inst.blockLight;
                int sky = rawLt >= 0 ? ((rawLt >>> 16) & 0xFF) : inst.skyLight;
                if (mesh.handle().layer() == RtLayer.EMISSIVE) {
                    blk = 240;
                }
                int lightWord = (blk & 0xFF) | ((texSlot & 0xFF) << 8) | ((sky & 0xFF) << 16);

                out.appendVertex(tmpPos.x, tmpPos.y, tmpPos.z, Float.intBitsToFloat(mesh.colorAbgr()[i]),
                    mesh.u()[i], mesh.v()[i], Float.intBitsToFloat(lightWord), tmpNorm.x, tmpNorm.y, tmpNorm.z);
            }

            int[] idx = mesh.indices();
            for (int index : idx) {
                out.appendIndex(startVertex + index);
            }

            int flags = EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY;
            boolean translucent = mesh.handle().layer() == RtLayer.TRANSLUCENT;
            if (translucent) {
                flags |= EntityGeometryFrame.CapturedEntity.FLAG_TRANSLUCENT;
            } else if (mesh.handle().layer() == RtLayer.EMISSIVE) {
                flags |= EntityGeometryFrame.CapturedEntity.FLAG_GLOWING;
            }

            outEntities.add(new EntityGeometryFrame.CapturedEntity(
                inst.entityId,
                "bridge:" + mesh.handle().layer().name().toLowerCase(java.util.Locale.ROOT),
                new Matrix4f(inst.worldTransform),
                new Matrix4f(inst.prevWorldTransform),
                startVertex,
                vCount,
                startIndex,
                idx.length,
                texSlot,
                flags,
                translucent
            ));
        }
        if (stale != null) {
            for (Long id : stale) {
                instances.remove(id);
            }
        }
    }

    private static int resolveTextureSlot(@Nullable Identifier texture) {
        if (texture == null) {
            return 0;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getResourceManager() == null) {
                return 0;
            }
            return EntityTextureAtlas.INSTANCE.slot(texture, 0, 0);
        } catch (Throwable t) {
            return 0;
        }
    }
}
