package io.github.andrexarlay.vulkancompat.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Intercepts vertex data during entity rendering and transforms it into
 * index-buffered triangles (quad triangulation: 0,1,2 and 2,3,0).
 */
public class CapturedVertexConsumer implements VertexConsumer {
    /** Floats per recorded vertex: x,y,z, color(ABGR bits), u, v, light bits, nx, ny, nz. */
    public static final int FLOATS_PER_VERTEX = 10;

    private float[] vertices = new float[4096 * FLOATS_PER_VERTEX];
    private int vertexFloatCount;
    private int[] indices = new int[6144];
    private int indexCount;

    // Storage for the vertices of the current polygon/quad (one flat row; a quad is the longest polygon here)
    private final float[] quadStage = new float[4 * FLOATS_PER_VERTEX];
    private int quadStageCount;

    // Current vertex staging fields
    private float curX, curY, curZ;
    private int curRed = 255, curGreen = 255, curBlue = 255, curAlpha = 255;
    private float curU, curV;
    private int curLight = 15728880;
    private float curNormalX = 0f, curNormalY = 1f, curNormalZ = 0f;

    /** RT entity atlas slot of the following vertices (EntityTextureAtlas), packed into bits 8..15 of the light word. */
    private int texSlot;
    /** Optional UV remap (atlas UV -> sprite-local 0..1): u0, u1, v0, v1; null = as given. */
    private float[] uvRemap;
    /** Active entity flags (0x10 glowing, 0x20 fire, 0x40 hurt red overlay, 0x80 end portal), packed into bits 24..31 of the light word. */
    private int baseFlags;
    private int curFlags;
    private boolean allowHurt = true;
    private boolean pendingVertex = false;

    // Reused across vertices: one captured frame is tens of thousands of vertices on the render thread.
    private final Vector4f stagePos = new Vector4f();
    private final Vector3f stageNormal = new Vector3f();

    public void setFlags(int flags) {
        this.baseFlags = flags;
        this.curFlags = flags;
    }

    public int getFlags() {
        return this.baseFlags;
    }

    public void setAllowHurt(boolean allow) {
        this.allowHurt = allow;
        if (!allow) {
            this.curFlags &= ~0x40;
            this.baseFlags &= ~0x40;
        }
    }

    public void setTexture(int slot, float[] uvRemap) {
        this.texSlot = slot & 0xFF;
        this.uvRemap = uvRemap;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (this.pendingVertex) {
            flushStagedVertex();
        }
        this.pendingVertex = true;
        this.curFlags = this.baseFlags;
        this.curX = x;
        this.curY = y;
        this.curZ = z;
        return this;
    }

    @Override
    public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
        if (this.pendingVertex) {
            flushStagedVertex();
        }
        this.pendingVertex = true;
        this.curFlags = this.baseFlags;
        stagePos.set(x, y, z, 1.0f).mul(pose.pose());
        this.curX = stagePos.x;
        this.curY = stagePos.y;
        this.curZ = stagePos.z;
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        this.curRed = red & 0xFF;
        this.curGreen = green & 0xFF;
        this.curBlue = blue & 0xFF;
        this.curAlpha = alpha & 0xFF;
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        this.curRed = (argb >> 16) & 0xFF;
        this.curGreen = (argb >> 8) & 0xFF;
        this.curBlue = argb & 0xFF;
        this.curAlpha = (argb >> 24) & 0xFF;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.curU = u;
        this.curV = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        if (this.allowHurt && v == 3) {
            this.curFlags |= 0x40;
        } else {
            this.curFlags &= ~0x40;
        }
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.curLight = (v << 16) | (u & 0xFFFF);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        this.curNormalX = normalX;
        this.curNormalY = normalY;
        this.curNormalZ = normalZ;
        flushStagedVertex();
        return this;
    }

    @Override
    public VertexConsumer setNormal(PoseStack.Pose pose, float normalX, float normalY, float normalZ) {
        pose.transformNormal(normalX, normalY, normalZ, stageNormal);
        if (stageNormal.lengthSquared() > 1e-12f) {
            stageNormal.normalize();
        }
        this.curNormalX = stageNormal.x;
        this.curNormalY = stageNormal.y;
        this.curNormalZ = stageNormal.z;
        flushStagedVertex();
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    private void flushStagedVertex() {
        this.pendingVertex = false;
        float colorFloat = Float.intBitsToFloat(
            ((curAlpha & 0xFF) << 24) | ((curBlue & 0xFF) << 16) | ((curGreen & 0xFF) << 8) | (curRed & 0xFF)
        );
        // light word: block (0..240) | slot << 8 | sky (0..240) << 16 | flags << 24
        int lightWord = (curLight & 0xFF) | (texSlot << 8) | (((curLight >>> 16) & 0xFF) << 16) | ((curFlags & 0xFF) << 24);
        float u = curU;
        float v = curV;
        if (uvRemap != null) {
            u = (u - uvRemap[0]) / Math.max(1e-6f, uvRemap[1] - uvRemap[0]);
            v = (v - uvRemap[2]) / Math.max(1e-6f, uvRemap[3] - uvRemap[2]);
        }

        // Record a single vertex tuple: [x, y, z, color, u, v, light, nx, ny, nz]
        int s = quadStageCount * FLOATS_PER_VERTEX;
        quadStage[s] = curX;
        quadStage[s + 1] = curY;
        quadStage[s + 2] = curZ;
        quadStage[s + 3] = colorFloat;
        quadStage[s + 4] = u;
        quadStage[s + 5] = v;
        quadStage[s + 6] = Float.intBitsToFloat(lightWord);
        quadStage[s + 7] = curNormalX;
        quadStage[s + 8] = curNormalY;
        quadStage[s + 9] = curNormalZ;
        quadStageCount++;

        if (quadStageCount == 4) {
            emitQuadTriangles();
        }
    }

    private void emitQuadTriangles() {
        // The index base is derived from the write offset itself: the second counter this used to read was not advanced by the
        // bridge emitter (RtSceneApi.appendVertex), so a quad staged across bridge geometry rebased onto the wrong vertices.
        int at = ensureVertexSpace(4 * FLOATS_PER_VERTEX);
        int baseIndex = at / FLOATS_PER_VERTEX;
        System.arraycopy(quadStage, 0, vertices, at, 4 * FLOATS_PER_VERTEX);

        // Triangulate quad into two triangles: (0, 1, 2) and (2, 3, 0)
        int iat = ensureIndexSpace(6);
        indices[iat] = baseIndex + 0;
        indices[iat + 1] = baseIndex + 1;
        indices[iat + 2] = baseIndex + 2;
        indices[iat + 3] = baseIndex + 2;
        indices[iat + 4] = baseIndex + 3;
        indices[iat + 5] = baseIndex + 0;

        quadStageCount = 0;
    }

    /**
     * Flush any remaining incomplete polygon (e.g. standalone triangle or vertex without setNormal).
     */
    public void flushRemaining() {
        if (this.pendingVertex) {
            flushStagedVertex();
        }
        if (quadStageCount == 3) {
            int at = ensureVertexSpace(3 * FLOATS_PER_VERTEX);
            int baseIndex = at / FLOATS_PER_VERTEX;
            System.arraycopy(quadStage, 0, vertices, at, 3 * FLOATS_PER_VERTEX);
            int iat = ensureIndexSpace(3);
            indices[iat] = baseIndex + 0;
            indices[iat + 1] = baseIndex + 1;
            indices[iat + 2] = baseIndex + 2;
            quadStageCount = 0;
        } else if (quadStageCount != 0) {
            quadStageCount = 0;
        }
    }

    /** Append one already-transformed vertex tuple, used by the bridge emitter (RtSceneApi) writing into this frame. */
    public void appendVertex(float x, float y, float z, float color, float u, float v, float light,
                             float nx, float ny, float nz) {
        int i = ensureVertexSpace(FLOATS_PER_VERTEX);
        vertices[i] = x;
        vertices[i + 1] = y;
        vertices[i + 2] = z;
        vertices[i + 3] = color;
        vertices[i + 4] = u;
        vertices[i + 5] = v;
        vertices[i + 6] = light;
        vertices[i + 7] = nx;
        vertices[i + 8] = ny;
        vertices[i + 9] = nz;
    }

    /** Append one already-rebased triangle index, used by the bridge emitter writing into this frame. */
    public void appendIndex(int index) {
        indices[ensureIndexSpace(1)] = index;
    }

    private int ensureVertexSpace(int count) {
        int at = vertexFloatCount;
        int needed = at + count;
        if (needed > vertices.length) {
            float[] grown = new float[(int) Math.min(Integer.MAX_VALUE - 8L,
                Math.max((long) needed, Math.max(4096L, vertices.length) * 2L))];
            System.arraycopy(vertices, 0, grown, 0, at);
            vertices = grown;
        }
        vertexFloatCount = needed;
        return at;
    }

    private int ensureIndexSpace(int count) {
        int at = indexCount;
        int needed = at + count;
        if (needed > indices.length) {
            int[] grown = new int[(int) Math.min(Integer.MAX_VALUE - 8L,
                Math.max((long) needed, Math.max(6144L, indices.length) * 2L))];
            System.arraycopy(indices, 0, grown, 0, at);
            indices = grown;
        }
        indexCount = needed;
        return at;
    }

    /** The recorded vertex stream; only the first {@link #vertexFloatCount()} entries are meaningful. */
    public float[] vertexData() {
        return vertices;
    }

    /** Recorded floats ({@link #vertexData()} length). Does not flush staged vertices. */
    public int vertexFloatCount() {
        return vertexFloatCount;
    }

    /** The recorded index stream; only the first {@link #indexCount()} entries are meaningful. */
    public int[] indexData() {
        return indices;
    }

    /** Number of triangle indices committed to {@link #indexData()}. */
    public int indexCount() {
        return indexCount;
    }

    public int getVertexCount() {
        return vertexFloatCount / FLOATS_PER_VERTEX + quadStageCount;
    }

    public int getIndexCount() {
        return indexCount + (quadStageCount == 4 ? 6 : (quadStageCount == 3 ? 3 : 0));
    }

    public void clear() {
        vertexFloatCount = 0;
        indexCount = 0;
        quadStageCount = 0;
        curFlags = 0;
        baseFlags = 0;
        allowHurt = true;
        pendingVertex = false;
        curRed = 255;
        curGreen = 255;
        curBlue = 255;
        curAlpha = 255;
        curLight = 15728880;
    }
}
