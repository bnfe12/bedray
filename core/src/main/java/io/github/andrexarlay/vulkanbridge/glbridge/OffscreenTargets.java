package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlRenderer;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * GL thread only. Private targets of renderers that do not draw straight into the shared image ({@link CompositePlan}): one RGBA8
 * texture + DEPTH32F_STENCIL8 renderbuffer + FBO per renderer (same formats as the shared slot FBO), and the pass that blends such a
 * target into the shared image: fullscreen triangle, {@code texelFetch} at {@code gl_FragCoord} (same size), {@code rgb *= a} for
 * STRAIGHT, {@code * opacity}, blend {@code ONE, ONE_MINUS_SRC_ALPHA} for colour and alpha (premultiplied "over", the same equation
 * the Vulkan composite uses). GL 4.6 core DSA calls only.
 */
final class OffscreenTargets {
    private static final String VS = "#version 460 core\n"
        + "void main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
        + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n";
    private static final String FS = "#version 460 core\n"
        + "layout(binding = 0) uniform sampler2D src;\n"
        + "uniform int straightAlpha; uniform float opacity; out vec4 o;\n"
        + "void main() { vec4 c = texelFetch(src, ivec2(gl_FragCoord.xy), 0);\n"
        + "  if (straightAlpha != 0) { c.rgb *= c.a; }\n"
        + "  o = c * opacity; }\n";

    private static final class Target {
        int tex, depth, fbo, w, h;
    }

    private final Map<GlRenderer, Target> targets = new IdentityHashMap<>();
    private int program, vao, straightLoc, opacityLoc;
    private static final float[] CLEAR = {0f, 0f, 0f, 0f};

    /** Binds (and clears) the renderer's private target of size w x h, creating or recreating it. Returns its FBO. */
    int begin(GlRenderer r, int w, int h) {
        Target t = targets.get(r);
        if (t != null && (t.w != w || t.h != h)) {
            delete(t);
            t = null;
        }
        if (t == null) {
            t = create(w, h);
            targets.put(r, t);
            GlLog.line(6, "event=offscreen_created renderer=" + r.getClass().getName() + " size=" + w + "x" + h + " fbo=" + t.fbo);
        }
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glStencilMask(0xFF);
        glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
        glViewport(0, 0, w, h);
        glClearNamedFramebufferfv(t.fbo, GL_COLOR, 0, CLEAR);
        glClearNamedFramebufferfi(t.fbo, GL_DEPTH_STENCIL, 0, 1.0f, 0);
        return t.fbo;
    }

    /** Blends the renderer's private target into {@code dstFbo} (premultiplied over) with its alpha mode and opacity. */
    void blendInto(GlRenderer r, int dstFbo, int w, int h, boolean straight, float opacity) {
        Target t = targets.get(r);
        if (t == null) {
            throw new IllegalStateException("no offscreen target for " + r.getClass().getName());
        }
        if (program == 0) {
            program = TrianglePainter.link(VS, FS);
            straightLoc = glGetUniformLocation(program, "straightAlpha");
            opacityLoc = glGetUniformLocation(program, "opacity");
            vao = glCreateVertexArrays();
        }
        glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
        glViewport(0, 0, w, h);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_CULL_FACE);
        glColorMask(true, true, true, true);
        glEnable(GL_BLEND);
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
        glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(program);
        glUniform1i(straightLoc, straight ? 1 : 0);
        glUniform1f(opacityLoc, opacity);
        glBindTextureUnit(0, t.tex);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glBindTextureUnit(0, 0);
        glUseProgram(0);
        glDisable(GL_BLEND);
        SharedRing.glCheck("offscreen blend " + r.getClass().getName());
    }

    /** Renderer removed: free its target. */
    void remove(GlRenderer r) {
        Target t = targets.remove(r);
        if (t != null) {
            delete(t);
        }
    }

    int count() {
        return targets.size();
    }

    /** Shutdown: everything. */
    void destroy() {
        for (Target t : targets.values()) {
            delete(t);
        }
        targets.clear();
        if (program != 0) {
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
            program = 0;
            vao = 0;
        }
    }

    private static Target create(int w, int h) {
        Target t = new Target();
        t.w = w;
        t.h = h;
        t.tex = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(t.tex, 1, GL_RGBA8, w, h);
        t.depth = glCreateRenderbuffers();
        glNamedRenderbufferStorage(t.depth, GL_DEPTH32F_STENCIL8, w, h);
        t.fbo = glCreateFramebuffers();
        glNamedFramebufferTexture(t.fbo, GL_COLOR_ATTACHMENT0, t.tex, 0);
        glNamedFramebufferRenderbuffer(t.fbo, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, t.depth);
        int st = glCheckNamedFramebufferStatus(t.fbo, GL_FRAMEBUFFER);
        if (st != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("offscreen FBO incomplete: 0x" + Integer.toHexString(st));
        }
        SharedRing.glCheck("offscreen create");
        return t;
    }

    private static void delete(Target t) {
        glDeleteFramebuffers(t.fbo);
        glDeleteTextures(t.tex);
        glDeleteRenderbuffers(t.depth);
    }
}
