package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlFrame;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Test "mod": typical mod-style OpenGL 4.6 code running on the bridge (scenario {@code glbridge_mod}), not a triangle:
 * <ol>
 * <li>compute shader writes an animated 256x256 RGBA8 texture atlas (4 tiles: checker, gradient, orange rings, cyan stripes) with imageStore,
 *     then glMemoryBarrier;</li>
 * <li>own vertex/fragment program, VAO + VBO + EBO (DSA: glCreateBuffers/glNamedBufferStorage/glVertexArrayAttribFormat) for a cube whose faces
 *     sample the atlas tiles, depth test, perspective from the game camera FOV (JOML);</li>
 * <li>renders into its own 4x MSAA framebuffer (colour + depth renderbuffers, recreated in onResize) cleared to a translucent premultiplied blue;</li>
 * <li>MSAA resolve into the bridge target with classic glBindFramebuffer(READ/DRAW) + glBlitFramebuffer, the draw side obtained with
 *     {@code mapFramebuffer(0)} (top-right panel);</li>
 * <li>scaled glBlitNamedFramebuffer of the atlas (through an FBO on the atlas texture) 256 -> 384 px, top-left corner.</li>
 * </ol>
 * At frame 60 it reads back two pixels from the bridge target (centre of the cube panel, atlas checker corner) for the log.
 */
final class ModLikeRenderer implements GlRenderer {
    private static final int ATLAS = 256;
    private final GlBridge bridge;

    private int computeProgram, drawProgram, tLoc, mvpLoc;
    private int atlasTex, atlasFbo, vao, vbo, ebo;
    private int msaaFbo, msaaColor, msaaDepth, panelW, panelH;

    volatile int frames;
    volatile int samples;
    volatile String panelPixel = "not_read", atlasPixel = "not_read";
    volatile boolean removed;

    ModLikeRenderer(GlBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void onResize(int width, int height) {
        if (computeProgram == 0) {
            build();
        }
        if (msaaFbo != 0) {
            glDeleteFramebuffers(msaaFbo);
            glDeleteRenderbuffers(msaaColor);
            glDeleteRenderbuffers(msaaDepth);
        }
        panelW = Math.min(480, width / 3);
        panelH = Math.min(360, height / 3);
        msaaColor = glCreateRenderbuffers();
        glNamedRenderbufferStorageMultisample(msaaColor, 4, GL_RGBA8, panelW, panelH);
        msaaDepth = glCreateRenderbuffers();
        glNamedRenderbufferStorageMultisample(msaaDepth, 4, GL_DEPTH24_STENCIL8, panelW, panelH);
        msaaFbo = glCreateFramebuffers();
        glNamedFramebufferRenderbuffer(msaaFbo, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColor);
        glNamedFramebufferRenderbuffer(msaaFbo, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, msaaDepth);
        int st = glCheckNamedFramebufferStatus(msaaFbo, GL_FRAMEBUFFER);
        if (st != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("MSAA FBO incomplete: 0x" + Integer.toHexString(st));
        }
        samples = glGetNamedRenderbufferParameteri(msaaColor, GL_RENDERBUFFER_SAMPLES);
    }

    @Override
    public void onRemoved() {
        removed = true;
        glDeleteFramebuffers(new int[] {msaaFbo, atlasFbo});
        glDeleteRenderbuffers(new int[] {msaaColor, msaaDepth});
        glDeleteTextures(atlasTex);
        glDeleteBuffers(new int[] {vbo, ebo});
        glDeleteVertexArrays(vao);
        glDeleteProgram(computeProgram);
        glDeleteProgram(drawProgram);
    }

    @Override
    public void render(GlFrame f) {
        float t = f.frame() * 0.03f;
        int w = f.width(), h = f.height();

        // 1. compute: animated atlas
        glUseProgram(computeProgram);
        glUniform1f(tLoc, t);
        glBindImageTexture(0, atlasTex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glDispatchCompute(ATLAS / 16, ATLAS / 16, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // 2-3. cube into the MSAA framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
        glViewport(0, 0, panelW, panelH);
        glClearNamedFramebufferfv(msaaFbo, GL_COLOR, 0, new float[] {0.02f, 0.05f, 0.25f, 0.6f}); // premultiplied translucent blue
        glClearNamedFramebufferfi(msaaFbo, GL_DEPTH_STENCIL, 0, 1f, 0);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glEnable(GL_CULL_FACE);
        glUseProgram(drawProgram);
        float fov = f.camera() != null ? f.camera().fov() : 70f;
        Matrix4f mvp = new Matrix4f().perspective((float) Math.toRadians(fov), panelW / (float) panelH, 0.1f, 10f)
            .translate(0f, 0f, -3.2f).rotateX(0.5f).rotateY(t);
        FloatBuffer m = BufferUtils.createFloatBuffer(16);
        mvp.get(m);
        glUniformMatrix4fv(mvpLoc, false, m);
        glBindTextureUnit(0, atlasTex);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(0);

        // 4. MSAA resolve, classic binding; the draw side is "framebuffer 0" routed through the bridge
        int px = w - panelW - 16, py = h - panelH - 16;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bridge.mapFramebuffer(0));
        glBlitFramebuffer(0, 0, panelW, panelH, px, py, px + panelW, py + panelH, GL_COLOR_BUFFER_BIT, GL_NEAREST);

        // 5. scaled DSA blit of the atlas
        int target = bridge.targetFramebuffer();
        int as = Math.min(384, h / 3);
        glBlitNamedFramebuffer(atlasFbo, target, 0, 0, ATLAS, ATLAS, 16, h - 16 - as, 16 + as, h - 16, GL_COLOR_BUFFER_BIT, GL_NEAREST);

        if (f.frame() == 60) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, target);
            panelPixel = read(px + panelW / 2, py + panelH / 2);
            atlasPixel = read(16 + 2, h - 16 - as + 2);
        }
        bridge.bindTarget();
        frames++;
    }

    private static String read(int x, int y) {
        ByteBuffer b = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, b);
        return String.format("%02X%02X%02X%02X", b.get(0) & 255, b.get(1) & 255, b.get(2) & 255, b.get(3) & 255);
    }

    private void build() {
        String cs = "#version 460 core\n"
            + "layout(local_size_x = 16, local_size_y = 16) in;\n"
            + "layout(rgba8, binding = 0) uniform writeonly image2D atlas;\n"
            + "uniform float t;\n"
            + "void main() {\n"
            + "  ivec2 p = ivec2(gl_GlobalInvocationID.xy); int id = (p.x / 128) + 2 * (p.y / 128); vec2 l = vec2(p % 128) / 128.0; vec4 c;\n"
            + "  if (id == 0) { c = (((p.x / 16) + (p.y / 16)) & 1) == 0 ? vec4(1.0) : vec4(0.1, 0.1, 0.1, 1.0); }\n"
            + "  else if (id == 1) { c = vec4(l.x, l.y, 0.5 + 0.5 * sin(t), 1.0); }\n"
            + "  else if (id == 2) { float r = step(0.5, fract(length(l - 0.5) * 8.0 - t)); c = vec4(r, 0.5 * r, 0.0, 1.0); }\n"
            + "  else { float s = step(0.5, fract((l.x + l.y) * 6.0 + t)); c = vec4(0.0, s, 1.0, 1.0); }\n"
            + "  imageStore(atlas, p, c);\n"
            + "}\n";
        int sh = TrianglePainter.compile(GL_COMPUTE_SHADER, cs);
        computeProgram = glCreateProgram();
        glAttachShader(computeProgram, sh);
        glLinkProgram(computeProgram);
        if (glGetProgrami(computeProgram, GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("compute link failed: " + glGetProgramInfoLog(computeProgram));
        }
        glDeleteShader(sh);
        tLoc = glGetUniformLocation(computeProgram, "t");

        String vs = "#version 460 core\n"
            + "layout(location = 0) in vec3 pos; layout(location = 1) in vec2 uv;\n"
            + "uniform mat4 mvp; out vec2 vUv;\n"
            + "void main() { vUv = uv; gl_Position = mvp * vec4(pos, 1.0); }\n";
        String fs = "#version 460 core\n"
            + "layout(binding = 0) uniform sampler2D atlas; in vec2 vUv; out vec4 o;\n"
            + "void main() { o = vec4(texture(atlas, vUv).rgb, 1.0); }\n";
        drawProgram = TrianglePainter.link(vs, fs);
        mvpLoc = glGetUniformLocation(drawProgram, "mvp");

        atlasTex = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(atlasTex, 1, GL_RGBA8, ATLAS, ATLAS);
        glTextureParameteri(atlasTex, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(atlasTex, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        atlasFbo = glCreateFramebuffers();
        glNamedFramebufferTexture(atlasFbo, GL_COLOR_ATTACHMENT0, atlasTex, 0);

        // cube: 6 faces x 4 vertices (pos3 + uv2), face k samples atlas tile k % 4
        float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        FloatBuffer v = BufferUtils.createFloatBuffer(24 * 5);
        IntBuffer idx = BufferUtils.createIntBuffer(36);
        for (int k = 0; k < 6; k++) {
            float[] nn = n[k];
            float[] u = Math.abs(nn[1]) > 0 ? new float[] {1, 0, 0} : new float[] {0, 1, 0};
            float[] w2 = {nn[1] * u[2] - nn[2] * u[1], nn[2] * u[0] - nn[0] * u[2], nn[0] * u[1] - nn[1] * u[0]};
            float tu = (k % 4 % 2) * 0.5f, tv = (k % 4 / 2) * 0.5f;
            float[][] c = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (float[] cc : c) {
                for (int a = 0; a < 3; a++) {
                    v.put(0.8f * (nn[a] + cc[0] * w2[a] + cc[1] * u[a]));
                }
                v.put(tu + (cc[0] + 1) * 0.25f).put(tv + (cc[1] + 1) * 0.25f);
            }
            int b = 4 * k;
            // (n x u) x u = -n: the corner order is clockwise seen from outside, so the indices are reversed to get CCW front faces
            idx.put(b).put(b + 2).put(b + 1).put(b).put(b + 3).put(b + 2);
        }
        v.flip();
        idx.flip();
        vbo = glCreateBuffers();
        glNamedBufferStorage(vbo, v, 0);
        ebo = glCreateBuffers();
        glNamedBufferStorage(ebo, idx, 0);
        vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(vao, 0, vbo, 0L, 5 * 4);
        glVertexArrayElementBuffer(vao, ebo);
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, 0);
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribFormat(vao, 1, 2, GL_FLOAT, false, 3 * 4);
        glVertexArrayAttribBinding(vao, 1, 0);
        SharedRing.glCheck("ModLikeRenderer.build");
    }
}
