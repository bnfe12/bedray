package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;

/**
 * Maps captured world geometry (Sodium DecodedSection / RT entity interleaved buffers) into the Iris gbuffers vertex layout:
 * pos3, color4, uv0_2, uv1_2 (lightmap), normal3, midBlock3, mc_Entity4, mc_midTexCoord4, at_tangent4 = 29 floats. CPU-side
 * only; GPU upload lives in the forward executor. midBlock is block-center minus position (pack waving/parallax). The capture
 * has no block id, mid-texcoord or tangent, so mc_Entity is zero, mc_midTexCoord approximates uv0 and at_tangent defaults +X.
 */
public final class IrisGeometrySource {

    public static final int STRIDE_FLOATS = 29;

    public record Mesh(float[] vertices, int[] indices, int strideFloats) {}

    private IrisGeometrySource() {}

    public static Mesh buildTerrain(DecodedSection section, int pass) {
        int start = section.passQuadStart(pass);
        int count = section.quadsInPass(pass);
        return buildTerrain(section.positions, section.uvs, section.colors, section.light, section.normals,
            start, count, section.sectionX * 16f, section.sectionY * 16f, section.sectionZ * 16f);
    }

    public static Mesh buildTerrain(float[] positions, float[] uvs, int[] colors, int[] light, float[] normals,
                                    int quadStart, int quadCount, float ox, float oy, float oz) {
        float[] verts = new float[quadCount * 4 * STRIDE_FLOATS];
        int[] indices = new int[quadCount * 6];
        int vi = 0;
        int ii = 0;
        for (int q = quadStart; q < quadStart + quadCount; q++) {
            int base = (q - quadStart) * 4;
            for (int v = 0; v < 4; v++) {
                float px = positions[q * 12 + v * 3] + ox;
                float py = positions[q * 12 + v * 3 + 1] + oy;
                float pz = positions[q * 12 + v * 3 + 2] + oz;
                int cw = colors[q * 4 + v];
                int lw = light[q * 4 + v];
                verts[vi++] = px;
                verts[vi++] = py;
                verts[vi++] = pz;
                verts[vi++] = (cw & 0xFF) / 255f;
                verts[vi++] = ((cw >> 8) & 0xFF) / 255f;
                verts[vi++] = ((cw >> 16) & 0xFF) / 255f;
                verts[vi++] = ((cw >> 24) & 0xFF) / 255f;
                verts[vi++] = uvs[q * 8 + v * 2];
                verts[vi++] = uvs[q * 8 + v * 2 + 1];
                verts[vi++] = (lw & 0xFF) / 255f;
                verts[vi++] = ((lw >> 8) & 0xFF) / 255f;
                verts[vi++] = normals[q * 3];
                verts[vi++] = normals[q * 3 + 1];
                verts[vi++] = normals[q * 3 + 2];
                verts[vi++] = (float) Math.floor(px) + 0.5f - px;
                verts[vi++] = (float) Math.floor(py) + 0.5f - py;
                verts[vi++] = (float) Math.floor(pz) + 0.5f - pz;
                verts[vi++] = 0f;
                verts[vi++] = 0f;
                verts[vi++] = 0f;
                verts[vi++] = 0f;
                verts[vi++] = uvs[q * 8 + v * 2];
                verts[vi++] = uvs[q * 8 + v * 2 + 1];
                verts[vi++] = 0f;
                verts[vi++] = 0f;
                verts[vi++] = 1f;
                verts[vi++] = 0f;
                verts[vi++] = 0f;
                verts[vi++] = 1f;
            }
            indices[ii++] = base;
            indices[ii++] = base + 1;
            indices[ii++] = base + 2;
            indices[ii++] = base;
            indices[ii++] = base + 2;
            indices[ii++] = base + 3;
        }
        return new Mesh(verts, indices, STRIDE_FLOATS);
    }

    public static Mesh buildEntities(float[] interleaved10, int[] indices) {
        int count = interleaved10.length / 10;
        float[] verts = new float[count * STRIDE_FLOATS];
        int vi = 0;
        for (int i = 0; i < count; i++) {
            int o = i * 10;
            float px = interleaved10[o];
            float py = interleaved10[o + 1];
            float pz = interleaved10[o + 2];
            int cw = Float.floatToIntBits(interleaved10[o + 3]);
            int lw = Float.floatToIntBits(interleaved10[o + 6]);
            verts[vi++] = px;
            verts[vi++] = py;
            verts[vi++] = pz;
            verts[vi++] = (cw & 0xFF) / 255f;
            verts[vi++] = ((cw >> 8) & 0xFF) / 255f;
            verts[vi++] = ((cw >> 16) & 0xFF) / 255f;
            verts[vi++] = ((cw >> 24) & 0xFF) / 255f;
            verts[vi++] = interleaved10[o + 4];
            verts[vi++] = interleaved10[o + 5];
            verts[vi++] = (lw & 0xFFFF) / 65535f;
            verts[vi++] = ((lw >> 16) & 0xFFFF) / 65535f;
            verts[vi++] = interleaved10[o + 7];
            verts[vi++] = interleaved10[o + 8];
            verts[vi++] = interleaved10[o + 9];
            verts[vi++] = (float) Math.floor(px) + 0.5f - px;
            verts[vi++] = (float) Math.floor(py) + 0.5f - py;
            verts[vi++] = (float) Math.floor(pz) + 0.5f - pz;
            verts[vi++] = 0f;
            verts[vi++] = 0f;
            verts[vi++] = 0f;
            verts[vi++] = 0f;
            verts[vi++] = interleaved10[o + 4];
            verts[vi++] = interleaved10[o + 5];
            verts[vi++] = 0f;
            verts[vi++] = 0f;
            verts[vi++] = 1f;
            verts[vi++] = 0f;
            verts[vi++] = 0f;
            verts[vi++] = 1f;
        }
        return new Mesh(verts, indices.clone(), STRIDE_FLOATS);
    }
}