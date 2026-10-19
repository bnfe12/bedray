package io.github.andrexarlay.vulkancompat.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts vertex data during entity rendering and transforms it into
 * index-buffered triangles (quad triangulation: 0,1,2 and 2,3,0).
 */
public class CapturedVertexConsumer implements VertexConsumer {
    private final List<Float> vertices = new ArrayList<>();
    private final List<Integer> indices = new ArrayList<>();

    // Temporary storage for vertices of the current polygon/quad
    private final List<float[]> currentQuadVertices = new ArrayList<>(4);

    // Current vertex staging fields
    private float curX, curY, curZ;
    private int curRed = 255, curGreen = 255, curBlue = 255, curAlpha = 255;
    private float curU, curV;
    private int curLight = 15728880;
    private float curNormalX = 0f, curNormalY = 1f, curNormalZ = 0f;

    private int totalVertexCount = 0;
    /** RT entity atlas slot of the following vertices (EntityTextureAtlas), packed into bits 8..15 of the light word. */
    private int texSlot;
    /** Optional UV remap (atlas UV -> sprite-local 0..1): u0, u1, v0, v1; null = as given. */
    private float[] uvRemap;
    /** Active entity flags (0x10 glowing, 0x20 fire, 0x40 hurt red overlay, 0x80 end portal), packed into bits 24..31 of the light word. */
    private int baseFlags;
    private int curFlags;
    private boolean allowHurt = true;
    private boolean pendingVertex = false;

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
        Vector4f pos = new Vector4f(x, y, z, 1.0f);
        pos.mul(pose.pose());
        this.curX = pos.x;
        this.curY = pos.y;
        this.curZ = pos.z;
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
        Vector3f norm = new Vector3f();
        pose.transformNormal(normalX, normalY, normalZ, norm);
        if (norm.lengthSquared() > 1e-12f) {
            norm.normalize();
        }
        this.curNormalX = norm.x;
        this.curNormalY = norm.y;
        this.curNormalZ = norm.z;
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
        float lightFloat = Float.intBitsToFloat(lightWord);
        float u = curU;
        float v = curV;
        if (uvRemap != null) {
            u = (u - uvRemap[0]) / Math.max(1e-6f, uvRemap[1] - uvRemap[0]);
            v = (v - uvRemap[2]) / Math.max(1e-6f, uvRemap[3] - uvRemap[2]);
        }

        // Record a single vertex tuple: [x, y, z, color, u, v, light, nx, ny, nz] (10 floats)
        float[] vData = new float[]{
            curX, curY, curZ,
            colorFloat,
            u, v,
            lightFloat,
            curNormalX, curNormalY, curNormalZ
        };

        currentQuadVertices.add(vData);

        if (currentQuadVertices.size() == 4) {
            emitQuadTriangles();
        }
    }

    private void emitQuadTriangles() {
        int baseIndex = totalVertexCount;

        // Add 4 vertices to global vertex stream
        for (float[] vData : currentQuadVertices) {
            for (float f : vData) {
                vertices.add(f);
            }
        }
        totalVertexCount += 4;

        // Triangulate quad into two triangles: (0, 1, 2) and (2, 3, 0)
        indices.add(baseIndex + 0);
        indices.add(baseIndex + 1);
        indices.add(baseIndex + 2);

        indices.add(baseIndex + 2);
        indices.add(baseIndex + 3);
        indices.add(baseIndex + 0);

        currentQuadVertices.clear();
    }

    /**
     * Flush any remaining incomplete polygon (e.g. standalone triangle or vertex without setNormal).
     */
    public void flushRemaining() {
        if (this.pendingVertex) {
            flushStagedVertex();
        }
        if (currentQuadVertices.size() == 3) {
            int baseIndex = totalVertexCount;
            for (float[] vData : currentQuadVertices) {
                for (float f : vData) {
                    vertices.add(f);
                }
            }
            totalVertexCount += 3;
            indices.add(baseIndex + 0);
            indices.add(baseIndex + 1);
            indices.add(baseIndex + 2);
            currentQuadVertices.clear();
        } else if (!currentQuadVertices.isEmpty()) {
            currentQuadVertices.clear();
        }
    }

    public List<Float> getVertices() {
        flushRemaining();
        return vertices;
    }

    public List<Integer> getIndices() {
        flushRemaining();
        return indices;
    }

    public int getVertexCount() {
        return totalVertexCount + currentQuadVertices.size();
    }

    public int getIndexCount() {
        return indices.size() + (currentQuadVertices.size() == 4 ? 6 : (currentQuadVertices.size() == 3 ? 3 : 0));
    }

    public void clear() {
        vertices.clear();
        indices.clear();
        currentQuadVertices.clear();
        totalVertexCount = 0;
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
