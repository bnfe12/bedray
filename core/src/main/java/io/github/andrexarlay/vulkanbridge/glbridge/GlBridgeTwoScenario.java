package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.FramePhase;
import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlFrame;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions.Latency;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Autotest scenario {@code glbridge_two} (API 0.6.0): two renderers with different {@link GlRendererOptions} must not affect each other.
 * Quads in the upper half (away from the chat panel). A = left quad, straight red (1,0,0,0.5), options STRAIGHT / opacity 1. B = right quad, opaque blue (0,0,1,1), options PREMULTIPLIED /
 * opacity 0.5. Over a game pixel g both must give 50 %: left = 0.5*red + 0.5*g, right = 0.5*blue + 0.5*g. Steps (screens):
 * <ol>
 *   <li>{@code two_hidden.png}: both registered with visible=false (baseline, nothing composited);</li>
 *   <li>{@code two_same_lane.png}: both FRAME_END/SAME_FRAME (one lane, both blended through GL offscreen targets);</li>
 *   <li>{@code two_split_latency.png}: B switched to PIPELINED_N_MINUS_1 (two lanes, each through the Vulkan push constants);</li>
 *   <li>{@code two_split_phase.png}: B additionally composited at BEFORE_GUI;</li>
 *   <li>{@code two_a_hidden.png}: A visible=false only: left back to the game, right unchanged.</li>
 * </ol>
 * Every screen is read back from the PNG and checked at the quad centres and a control point (sky between the quads, must equal the
 * baseline) with a tolerance of {@value #TOL}/255 per channel (the scene animates slightly between shots). PASS also needs onRemoved
 * on both after unregister.
 */
public final class GlBridgeTwoScenario implements AutotestScenario {
    static final int TOL = 12;

    /** A quad at x = cx (NDC), half-size 0.3, written without blending. */
    static final class Quad implements GlRenderer {
        private final float cx;
        private final float[] rgba;
        private int program, vao, colorLoc, offsetLoc;
        volatile long frames;
        volatile boolean removed;

        Quad(float cx, float... rgba) {
            this.cx = cx;
            this.rgba = rgba;
        }

        @Override
        public void render(GlFrame f) {
            if (program == 0) {
                String vs = "#version 460 core\nuniform float cx;\n"
                    + "void main() { vec2 q = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) - 0.5;\n"
                    + "  gl_Position = vec4(cx + q.x * 0.6, 0.5 + q.y * 0.6, 0.0, 1.0); }\n";
                String fs = "#version 460 core\nuniform vec4 color; out vec4 o;\nvoid main() { o = color; }\n";
                program = TrianglePainter.link(vs, fs);
                colorLoc = glGetUniformLocation(program, "color");
                offsetLoc = glGetUniformLocation(program, "cx");
                vao = glCreateVertexArrays();
            }
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glUseProgram(program);
            glBindVertexArray(vao);
            glUniform4f(colorLoc, rgba[0], rgba[1], rgba[2], rgba[3]);
            glUniform1f(offsetLoc, cx);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glBindVertexArray(0);
            glUseProgram(0);
            frames++;
        }

        @Override
        public void onRemoved() {
            if (program != 0) {
                glDeleteProgram(program);
                glDeleteVertexArrays(vao);
            }
            removed = true;
        }
    }

    @Override
    public String name() {
        return "glbridge_two";
    }

    private final List<String> failures = new ArrayList<>();
    private int[][] base;

    @Override
    public void run(AutotestContext ctx) {
        GlBridge b = VulkanBridge.glBridge();
        if (b.state() != GlBridge.State.READY) {
            ctx.fail("GL bridge is not READY: " + b.state() + " " + b.failure(), null);
            return;
        }
        qa = new Quad(-0.5f, 1f, 0f, 0f, 0.5f);
        qb = new Quad(0.5f, 0f, 0f, 1f, 1f);
        GlRendererOptions oa = GlRendererOptions.DEFAULTS.withAlphaMode(GlBridge.AlphaMode.STRAIGHT);
        GlRendererOptions ob = GlRendererOptions.DEFAULTS.withOpacity(0.5f);
        b.registerRenderer(qa, oa.withVisible(false));
        b.registerRenderer(qb, ob.withVisible(false));
        long c0 = b.compositedFrames();
        Path dir = ctx.minecraft().gameDirectory.toPath().resolve("autotest").resolve(name());
        ctx.waitFrames(10, () -> ctx.shot("two_hidden.png", () -> {
            if (b.compositedFrames() != c0) {
                failures.add("hidden renderers were composited: " + (b.compositedFrames() - c0));
            }
            base = read(ctx, dir.resolve("two_hidden.png"));
            b.setOptions(qa, oa);
            b.setOptions(qb, ob);
            step(ctx, b, dir, "two_same_lane.png", true, true, () -> {
                b.setOptions(qb, ob.withLatency(Latency.PIPELINED_N_MINUS_1));
                step(ctx, b, dir, "two_split_latency.png", true, true, () -> {
                    b.setOptions(qb, ob.withLatency(Latency.PIPELINED_N_MINUS_1).withPhase(FramePhase.BEFORE_GUI));
                    step(ctx, b, dir, "two_split_phase.png", true, true, () -> {
                        b.setOptions(qa, oa.withVisible(false));
                        step(ctx, b, dir, "two_a_hidden.png", false, true, () -> {
                            b.unregisterRenderer(qa);
                            b.unregisterRenderer(qb);
                            ctx.waitFrames(5, () -> {
                                ctx.log("event", "two_done", "framesA", String.valueOf(qa.frames), "framesB", String.valueOf(qb.frames),
                                    "removedA", String.valueOf(qa.removed), "removedB", String.valueOf(qb.removed),
                                    "composited", String.valueOf(b.compositedFrames()), "failures", String.valueOf(failures.size()));
                                if (!qa.removed || !qb.removed) {
                                    failures.add("onRemoved missing: A=" + qa.removed + " B=" + qb.removed);
                                }
                                if (b.state() != GlBridge.State.READY) {
                                    failures.add("state " + b.state() + " " + b.failure());
                                }
                                if (failures.isEmpty()) {
                                    ctx.finish();
                                } else {
                                    ctx.fail("glbridge_two: " + String.join("; ", failures), null);
                                }
                            });
                        });
                    });
                });
            });
        }));
    }

    private Quad qa, qb;

    /**
     * Fresh baseline per step (the sky and clouds drift within seconds): both renderers hidden for 10 frames, {@code ref_<file>}, then the
     * step's options restored for 15 frames and {@code <file>} checked against that reference.
     */
    private void step(AutotestContext ctx, GlBridge b, Path dir, String file, boolean aVisible, boolean bVisible, Runnable next) {
        GlRendererOptions oa = b.options(qa), ob = b.options(qb);
        b.setOptions(qa, oa.withVisible(false));
        b.setOptions(qb, ob.withVisible(false));
        ctx.waitFrames(10, () -> ctx.shot("ref_" + file, () -> {
            base = read(ctx, dir.resolve("ref_" + file));
            b.setOptions(qa, oa);
            b.setOptions(qb, ob);
            ctx.waitFrames(15, () -> ctx.shot(file, () -> {
                int[][] got = read(ctx, dir.resolve(file));
                if (got != null && base != null) {
                    // [0] left quad centre, [1] right quad centre, [2] control point (sky between the quads)
                    int[] expLeft = aVisible ? mix(new int[] {255, 0, 0}, base[0]) : base[0];
                    int[] expRight = bVisible ? mix(new int[] {0, 0, 255}, base[1]) : base[1];
                    check(ctx, file, "left", got[0], expLeft);
                    check(ctx, file, "right", got[1], expRight);
                    check(ctx, file, "control", got[2], base[2]);
                }
                next.run();
            }));
        }));
    }

    private static int[] mix(int[] c, int[] g) {
        return new int[] {(c[0] + g[0]) / 2, (c[1] + g[1]) / 2, (c[2] + g[2]) / 2};
    }

    private void check(AutotestContext ctx, String file, String where, int[] got, int[] exp) {
        int d = Math.max(Math.abs(got[0] - exp[0]), Math.max(Math.abs(got[1] - exp[1]), Math.abs(got[2] - exp[2])));
        boolean ok = d <= TOL;
        ctx.log("event", "two_pixel", "shot", file, "at", where, "got", rgb(got), "expected", rgb(exp), "maxDiff", String.valueOf(d),
            "result", ok ? "PASS" : "FAIL");
        if (!ok) {
            failures.add(file + " " + where + " got " + rgb(got) + " expected " + rgb(exp));
        }
    }

    private static String rgb(int[] c) {
        return String.format(Locale.ROOT, "%d,%d,%d", c[0], c[1], c[2]);
    }

    /** Mean RGB of 7x7 patches: quad centres at 25 % height from the top, control at (0.5, 0.1). The quads sit in the upper half on purpose: the chat panel of the test world covers the middle, and a BEFORE_GUI composite is (correctly) covered by it (first runs: right = 0.25 blue + 0.5 game under the chat background, two_split_phase.png). */
    private int[][] read(AutotestContext ctx, Path png) {
        try {
            BufferedImage img = ImageIO.read(png.toFile());
            if (img == null) {
                failures.add("cannot decode " + png.getFileName());
                return null;
            }
            int w = img.getWidth(), h = img.getHeight();
            return new int[][] {patch(img, w / 4, h / 4), patch(img, 3 * w / 4, h / 4), patch(img, w / 2, h / 10)};
        } catch (IOException e) {
            failures.add("cannot read " + png.getFileName() + ": " + e);
            return null;
        }
    }

    private static int[] patch(BufferedImage img, int cx, int cy) {
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = cy - 3; y <= cy + 3; y++) {
            for (int x = cx - 3; x <= cx + 3; x++) {
                int p = img.getRGB(x, y);
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                bl += p & 0xFF;
                n++;
            }
        }
        return new int[] {(int) (r / n), (int) (g / n), (int) (bl / n)};
    }
}
