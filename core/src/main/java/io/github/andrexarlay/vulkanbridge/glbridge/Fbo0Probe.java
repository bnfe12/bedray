package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlFrame;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Shows the "framebuffer 0" limitation of Tier 2. Draws a RED quad on the left after a raw {@code glBindFramebuffer(GL_FRAMEBUFFER, 0)}
 * (what foreign GL code does to "draw to the window") and a GREEN quad on the right after
 * {@code glBindFramebuffer(GL_FRAMEBUFFER, bridge.mapFramebuffer(0))}. Expected on screen: only the green quad.
 * The raw quad lands in the default framebuffer of the bridge's hidden SDL window; one pixel of it is read back and logged.
 */
final class Fbo0Probe implements GlRenderer {
    private final GlBridge bridge;
    private int program;
    private int vao;
    private int colorLoc;
    private int offsetLoc;
    volatile String hiddenPixel = "not_read";
    volatile int rawBinding = -1;
    volatile int mappedBinding = -1;

    Fbo0Probe(GlBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void render(GlFrame f) {
        if (program == 0) {
            build();
        }
        glUseProgram(program);
        glBindVertexArray(vao);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        rawBinding = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glViewport(0, 0, f.width(), f.height());
        glUniform4f(colorLoc, 1f, 0f, 0f, 1f);
        glUniform1f(offsetLoc, -0.5f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (f.frame() == 30) {
            ByteBuffer px = BufferUtils.createByteBuffer(4);
            glReadBuffer(GL_BACK);
            glReadPixels(f.width() / 4, f.height() / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            hiddenPixel = String.format("%02X%02X%02X%02X", px.get(0) & 255, px.get(1) & 255, px.get(2) & 255, px.get(3) & 255);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, bridge.mapFramebuffer(0));
        mappedBinding = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glViewport(0, 0, f.width(), f.height());
        glUniform4f(colorLoc, 0f, 1f, 0f, 1f);
        glUniform1f(offsetLoc, 0.5f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void build() {
        String vs = "#version 460 core\n"
            + "uniform float cx;\n"
            + "void main() { vec2 q = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) - 0.5;\n"
            + "  gl_Position = vec4(cx + q.x * 0.4, q.y * 0.8, 0.0, 1.0); }\n";
        String fs = "#version 460 core\nuniform vec4 color; out vec4 o;\nvoid main() { o = color; }\n";
        program = TrianglePainter.link(vs, fs);
        colorLoc = glGetUniformLocation(program, "color");
        offsetLoc = glGetUniformLocation(program, "cx");
        vao = glCreateVertexArrays();
    }
}
