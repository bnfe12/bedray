package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlFrame;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Test painter: one RGB-gradient triangle (apex up in GL orientation, red bottom-left, green bottom-right, blue apex) with
 * alpha 1 on the transparent background the bridge cleared. Runs on the GL bridge thread; the program is built lazily on
 * the first call so that it belongs to the bridge's GL context.
 */
public final class TrianglePainter implements GlRenderer {
    private int program;
    private int vao;
    /** Last frame parameters seen on the GL thread (read by the autotest scenario). */
    volatile GlFrame last;
    private final GlBridge bridge;

    TrianglePainter(GlBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void render(GlFrame frame) {
        last = frame;
        if (program == 0) {
            build();
        }
        bridge.bindTarget();
        glUseProgram(program);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void build() {
        String vs = "#version 460 core\n"
            + "out vec3 vColor;\n"
            + "const vec2 P[3] = vec2[3](vec2(-0.45, -0.45), vec2(0.45, -0.45), vec2(0.0, 0.55));\n"
            + "const vec3 C[3] = vec3[3](vec3(1,0,0), vec3(0,1,0), vec3(0,0,1));\n"
            + "void main() { gl_Position = vec4(P[gl_VertexID], 0.0, 1.0); vColor = C[gl_VertexID]; }\n";
        String fs = "#version 460 core\n"
            + "in vec3 vColor; out vec4 o;\n"
            + "void main() { o = vec4(vColor, 1.0); }\n";
        program = link(vs, fs);
        vao = glCreateVertexArrays(); // core profile needs a bound VAO even without attributes
    }

    /** Compiles and links a vertex + fragment program on the current context; throws with the GL info log on failure. */
    static int link(String vs, String fs) {
        int v = compile(GL_VERTEX_SHADER, vs);
        int f = compile(GL_FRAGMENT_SHADER, fs);
        int program = glCreateProgram();
        glAttachShader(program, v);
        glAttachShader(program, f);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("GL link failed: " + glGetProgramInfoLog(program));
        }
        glDeleteShader(v);
        glDeleteShader(f);
        return program;
    }

    static int compile(int type, String src) {
        int sh = glCreateShader(type);
        glShaderSource(sh, src);
        glCompileShader(sh);
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("GL shader compile failed: " + glGetShaderInfoLog(sh));
        }
        return sh;
    }
}
