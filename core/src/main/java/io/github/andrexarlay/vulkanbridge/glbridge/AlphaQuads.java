package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlFrame;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Alpha test renderer (no GL blending, values written as given): LEFT quad straight red (1,0,0,0.5), RIGHT quad premultiplied red (0.5,0,0,0.5).
 * Correct result for 50 % red over the game: PREMULTIPLIED mode -> right quad correct, left too bright; STRAIGHT mode -> left correct, right too dark.
 */
final class AlphaQuads implements GlRenderer {
    private int program, vao, colorLoc, offsetLoc;

    @Override
    public void render(GlFrame f) {
        if (program == 0) {
            String vs = "#version 460 core\nuniform float cx;\n"
                + "void main() { vec2 q = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) - 0.5;\n"
                + "  gl_Position = vec4(cx + q.x * 0.6, q.y * 0.6, 0.0, 1.0); }\n";
            String fs = "#version 460 core\nuniform vec4 color; out vec4 o;\nvoid main() { o = color; }\n";
            program = TrianglePainter.link(vs, fs);
            colorLoc = glGetUniformLocation(program, "color");
            offsetLoc = glGetUniformLocation(program, "cx");
            vao = glCreateVertexArrays();
        }
        glDisable(GL_BLEND);
        glUseProgram(program);
        glBindVertexArray(vao);
        glUniform4f(colorLoc, 1f, 0f, 0f, 0.5f);
        glUniform1f(offsetLoc, -0.5f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glUniform4f(colorLoc, 0.5f, 0f, 0f, 0.5f);
        glUniform1f(offsetLoc, 0.5f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glUseProgram(0);
    }
}
