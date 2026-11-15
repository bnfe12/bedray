package io.github.andrexarlay.vulkancompat.rtx.hdr;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Step 1 of HDR output: a passive probe. It only reads what the driver offers and writes {@code [VKB-HDR]} lines to the log;
 * nothing about the engine swapchain changes. The one opt-in side effect is {@code -Dbedray.hdr.probe=colorspace}, which makes
 * the instance enable {@code VK_EXT_swapchain_colorspace}; without it the driver lists only {@code SRGB_NONLINEAR} formats and
 * the HDR colour spaces stay invisible.
 */
public final class HdrProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/HDR");

    public static final String EXT_SWAPCHAIN_COLORSPACE = "VK_EXT_swapchain_colorspace";
    public static final String EXT_HDR_METADATA = "VK_EXT_hdr_metadata";
    public static final String PROPERTY = "bedray.hdr.probe";

    private HdrProbe() {}

    /** True when {@code -Dbedray.hdr.probe=colorspace} asks the instance to enable the colour space extension. */
    public static boolean wantColorspaceExtension() {
        return "colorspace".equalsIgnoreCase(System.getProperty(PROPERTY, "").trim());
    }

    public static String colorSpaceName(int cs) {
        return switch (cs) {
            case 0 -> "SRGB_NONLINEAR";
            case 1000104001 -> "EXTENDED_SRGB_LINEAR (scRGB)";
            case 1000104002 -> "DISPLAY_P3_LINEAR";
            case 1000104003 -> "DISPLAY_P3_NONLINEAR";
            case 1000104004 -> "DCI_P3_NONLINEAR";
            case 1000104005 -> "BT709_LINEAR";
            case 1000104006 -> "BT709_NONLINEAR";
            case 1000104007 -> "BT2020_LINEAR";
            case 1000104008 -> "HDR10_ST2084 (PQ)";
            case 1000104009 -> "DOLBYVISION";
            case 1000104010 -> "HDR10_HLG";
            case 1000104011 -> "ADOBERGB_LINEAR";
            case 1000104012 -> "ADOBERGB_NONLINEAR";
            case 1000104013 -> "PASS_THROUGH";
            case 1000104014 -> "EXTENDED_SRGB_NONLINEAR";
            default -> "UNKNOWN(" + cs + ")";
        };
    }

    public static String formatName(int fmt) {
        return switch (fmt) {
            case 37 -> "R8G8B8A8_UNORM";
            case 43 -> "R8G8B8A8_SRGB";
            case 44 -> "B8G8R8A8_UNORM";
            case 50 -> "B8G8R8A8_SRGB";
            case 58 -> "A2R10G10B10_UNORM_PACK32";
            case 64 -> "A2B10G10R10_UNORM_PACK32";
            case 97 -> "R16G16B16A16_SFLOAT";
            default -> "VkFormat(" + fmt + ")";
        };
    }

    /** True for the colour spaces that carry more than SDR sRGB: scRGB, HDR10, HLG, Dolby Vision, BT.2020 linear. */
    public static boolean isHdrColorSpace(int cs) {
        return cs == 1000104001 || cs == 1000104007 || cs == 1000104008 || cs == 1000104009 || cs == 1000104010
            || cs == 1000104014;
    }

    // The extension lists are heap-allocated on purpose: this runs inside the engine's constructors, where the 64 KB
    // MemoryStack is already partly used, and a device exposes hundreds of 260-byte VkExtensionProperties.
    public static Set<String> instanceExtensions() {
        int[] n = new int[1];
        VK10.vkEnumerateInstanceExtensionProperties((CharSequence) null, n, null);
        VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(n[0]);
        try {
            VK10.vkEnumerateInstanceExtensionProperties((CharSequence) null, n, props);
            return names(props, n[0]);
        } finally {
            props.free();
        }
    }

    public static Set<String> deviceExtensions(VkPhysicalDevice pd) {
        int[] n = new int[1];
        VK10.vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, n, null);
        VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(n[0]);
        try {
            VK10.vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, n, props);
            return names(props, n[0]);
        } finally {
            props.free();
        }
    }

    private static Set<String> names(VkExtensionProperties.Buffer props, int count) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < Math.min(count, props.limit()); i++) {
            out.add(props.get(i).extensionNameString());
        }
        return out;
    }

    /** Logged once from the instance mixin: what the loader offers and whether the opt-in enabled the colour space extension. */
    public static void logInstance(boolean extensionEnabled, boolean offered) {
        LOGGER.info("[VKB-HDR] instance: {}={} enabled={} (enabled by hdr_output = HDR10 or -D{}=colorspace)",
            EXT_SWAPCHAIN_COLORSPACE, offered ? "offered" : "NOT offered", extensionEnabled, PROPERTY);
    }

    /** Logged after the engine surface exists: device extensions and every (format, colour space) pair the surface reports. */
    public static void logSurface(VkPhysicalDevice pd, long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Set<String> dev = deviceExtensions(pd);
            boolean csEnabled = pd.getInstance().getCapabilities().VK_EXT_swapchain_colorspace;
            LOGGER.info("[VKB-HDR] device: {}={} instance_{}_enabled={}", EXT_HDR_METADATA,
                dev.contains(EXT_HDR_METADATA) ? "offered" : "NOT offered", EXT_SWAPCHAIN_COLORSPACE, csEnabled);
            IntBuffer n = stack.callocInt(1);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, n, null);
            VkSurfaceFormatKHR.Buffer fmts = VkSurfaceFormatKHR.calloc(n.get(0), stack);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, n, fmts);
            int hdr = 0;
            for (int i = 0; i < fmts.limit(); i++) {
                VkSurfaceFormatKHR f = fmts.get(i);
                boolean h = isHdrColorSpace(f.colorSpace());
                hdr += h ? 1 : 0;
                LOGGER.info("[VKB-HDR] surface format {}/{}: {} + {}{}", i + 1, fmts.limit(), formatName(f.format()),
                    colorSpaceName(f.colorSpace()), h ? "  <-- HDR" : "");
            }
            LOGGER.info("[VKB-HDR] surface summary: formats={} hdr_pairs={}", fmts.limit(), hdr);
        } catch (Throwable t) {
            LOGGER.error("[VKB-HDR] surface probe failed", t);
        }
    }
}
