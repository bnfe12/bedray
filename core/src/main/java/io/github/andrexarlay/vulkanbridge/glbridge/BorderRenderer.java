package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlFrame;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Resize test renderer: an opaque 4 px red frame at the very edge of the framebuffer plus a 4 px blue cross through the centre, drawn with
 * scissored clears at exact pixel positions. If the shared image has the right size the lines are crisp 4 px wide on screen; if the
 * overlay is stretched from another size they are blurred and have the wrong width. Records every onResize call.
 */
final class BorderRenderer implements GlRenderer {
    volatile GlFrame last;
    volatile int resizeCalls;
    volatile int lastResizeW, lastResizeH;
    volatile boolean removedCalled;

    @Override
    public void onResize(int width, int height) {
        resizeCalls++;
        lastResizeW = width;
        lastResizeH = height;
    }

    @Override
    public void onRemoved() {
        removedCalled = true;
    }

    @Override
    public void render(GlFrame f) {
        last = f;
        int w = f.width(), h = f.height(), t = 4;
        glEnable(GL_SCISSOR_TEST);
        glClearColor(1f, 0f, 0f, 1f);
        rect(0, 0, w, t);
        rect(0, h - t, w, t);
        rect(0, 0, t, h);
        rect(w - t, 0, t, h);
        glClearColor(0f, 0.3f, 1f, 1f);
        rect(0, h / 2 - t / 2, w, t);
        rect(w / 2 - t / 2, 0, t, h);
        glDisable(GL_SCISSOR_TEST);
    }

    private static void rect(int x, int y, int w, int h) {
        glScissor(x, y, w, h);
        glClear(GL_COLOR_BUFFER_BIT);
    }
}
