package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * How the visible renderers of one lane (phase + latency) reach the shared image in one frame. Pure Java (unit tested).
 * <ul>
 *   <li>One renderer: it draws straight into the shared image and its alpha mode / opacity go to the Vulkan composite pass as push
 *       constants (exactly the 0.5.x path, no extra pass).</li>
 *   <li>Several renderers: the Vulkan composite runs with PREMULTIPLIED / 1. Renderers with default blending (PREMULTIPLIED, opacity 1)
 *       draw straight into the shared image in registration order; every other renderer draws into its own GL offscreen target,
 *       which the bridge then blends into the shared image with that renderer's alpha mode and opacity (premultiplied "over"), so the
 *       options of one renderer never change how another one is blended.</li>
 * </ul>
 */
final class CompositePlan {
    record Item(GlRenderer renderer, boolean intermediate, boolean straight, float opacity) {}

    final List<Item> items;
    /** Push constants of the Vulkan composite pass. */
    final boolean straight;
    final float opacity;

    private CompositePlan(List<Item> items, boolean straight, float opacity) {
        this.items = List.copyOf(items);
        this.straight = straight;
        this.opacity = opacity;
    }

    static CompositePlan of(List<GlRenderer> renderers, List<GlRendererOptions> options) {
        if (renderers.size() != options.size()) {
            throw new IllegalArgumentException("renderers/options size mismatch");
        }
        if (renderers.size() == 1) {
            GlRendererOptions o = options.get(0);
            boolean straight = o.alphaMode() == GlBridge.AlphaMode.STRAIGHT;
            return new CompositePlan(List.of(new Item(renderers.get(0), false, straight, o.opacity())), straight, o.opacity());
        }
        List<Item> items = new ArrayList<>(renderers.size());
        for (int i = 0; i < renderers.size(); i++) {
            GlRendererOptions o = options.get(i);
            boolean straight = o.alphaMode() == GlBridge.AlphaMode.STRAIGHT;
            boolean defaultBlend = !straight && o.opacity() == 1f;
            items.add(new Item(renderers.get(i), !defaultBlend, straight, o.opacity()));
        }
        return new CompositePlan(items, false, 1f);
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    int intermediates() {
        int n = 0;
        for (Item i : items) {
            if (i.intermediate()) {
                n++;
            }
        }
        return n;
    }
}
