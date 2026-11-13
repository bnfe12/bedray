package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Description + state of a pack's render targets (colortex/depthtex/shadowtex/shadowcolor): formats, ping-pong indices and
 * size. Pure layout/state; the GPU images are created by the forward executor from these descriptions. Unknown format maps
 * to -1 so the caller can report it by name instead of silently picking a wrong one.
 */
public final class IrisTargets {

    public record ColorDesc(int index, String format, int vkFormat) {}
    public record DepthDesc(int index, int vkFormat) {}

    private static final Map<String, Integer> FORMATS = Map.of(
        "RGBA8", 37,      // VK_FORMAT_R8G8B8A8_UNORM
        "RGBA16F", 97,    // VK_FORMAT_R16G16B16A16_SFLOAT
        "RGBA32F", 109,   // VK_FORMAT_R32G32B32A32_SFLOAT
        "R32F", 100,      // VK_FORMAT_R32_SFLOAT
        "RG16F", 103,     // VK_FORMAT_R16G16_SFLOAT
        "RG32F", 106,     // VK_FORMAT_R32G32_SFLOAT
        "R16F", 98,       // VK_FORMAT_R16_SFLOAT
        "RGB8", 23,       // VK_FORMAT_R8G8B8_UNORM
        "RGB16F", 90      // VK_FORMAT_R16G16B16_SFLOAT
    );
    private static final int D32 = 126; // VK_FORMAT_D32_SFLOAT

    private final List<ColorDesc> colors;
    private final List<DepthDesc> depths;
    private final List<DepthDesc> shadowDepths;
    private final List<ColorDesc> shadowColors;
    private final int[] ping;
    private int width;
    private int height;

    private IrisTargets(List<ColorDesc> colors, List<DepthDesc> depths, List<DepthDesc> shadowDepths, List<ColorDesc> shadowColors) {
        this.colors = List.copyOf(colors);
        this.depths = List.copyOf(depths);
        this.shadowDepths = List.copyOf(shadowDepths);
        this.shadowColors = List.copyOf(shadowColors);
        this.ping = new int[colors.size()];
    }

    public static IrisTargets create(IrisPackSettings settings) {
        int count = 8;
        for (int i = 0; i < 16; i++) {
            if (!"RGBA8".equals(settings.colortexFormat(i)) || settings.customTexture("colortex" + i) != null) {
                count = Math.max(count, i + 1);
            }
        }
        List<ColorDesc> colors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String fmt = settings.colortexFormat(i);
            colors.add(new ColorDesc(i, fmt, vkFormat(fmt)));
        }
        List<DepthDesc> depths = List.of(new DepthDesc(0, D32), new DepthDesc(1, D32), new DepthDesc(2, D32));
        List<DepthDesc> shadowDepths = List.of(new DepthDesc(0, D32), new DepthDesc(1, D32));
        List<ColorDesc> shadowColors = List.of(new ColorDesc(0, "RGBA8", vkFormat("RGBA8")), new ColorDesc(1, "RGBA8", vkFormat("RGBA8")));
        return new IrisTargets(colors, depths, shadowDepths, shadowColors);
    }

    public static int vkFormat(String format) {
        return FORMATS.getOrDefault(format, -1);
    }

    public List<ColorDesc> colors() {
        return colors;
    }

    public List<DepthDesc> depths() {
        return depths;
    }

    public List<DepthDesc> shadowDepths() {
        return shadowDepths;
    }

    public List<ColorDesc> shadowColors() {
        return shadowColors;
    }

    public int colorCount() {
        return colors.size();
    }

    public void setSize(int w, int h) {
        this.width = w;
        this.height = h;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean needsRecreate(int w, int h) {
        return w != width || h != height;
    }

    public int readIndex(int i) {
        return ping[i];
    }

    public int writeIndex(int i) {
        return 1 - ping[i];
    }

    public void swap(int i) {
        ping[i] = 1 - ping[i];
    }
}