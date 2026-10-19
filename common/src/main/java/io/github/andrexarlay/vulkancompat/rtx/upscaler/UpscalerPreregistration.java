package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import java.util.EnumMap;
import java.util.Map;

/**
 * Policy and bookkeeping of the device/instance requirements of the temporal upscalers (XeSS, DLSS).
 * <p>
 * The Vulkan device is created once at startup. XeSS needs VK_EXT_mutable_descriptor_type and instance API 1.3, DLSS needs
 * VK_NVX_binary_import / VK_NVX_image_view_handle / VK_KHR_buffer_device_address / VK_KHR_push_descriptor and the instance
 * extension VK_KHR_get_physical_device_properties2. To switch the upscaler while the game runs, these requirements are
 * registered for EVERY upscaler whose library is available, not only for the selected one ("preregistration").
 * Side effects and their justification: {@code docs/experiments/runtime-switch.md}.
 * <p>
 * {@code -Dvkrtx.upscaler.preregister=false} is the emergency switch: only the requested backend (-Dvkbridge.xess/-Dvkbridge.dlss
 * or the toml read before the device exists) registers its requirements, and switching to another backend needs a restart.
 * <p>
 * Pure Java, no Minecraft classes (unit-tested).
 */
public final class UpscalerPreregistration {
    public static final String PROPERTY = "vkrtx.upscaler.preregister";

    /** What the provider of one backend does at device creation. */
    public enum Decision {
        /** The backend was requested before the device existed. */
        REGISTER_REQUESTED,
        /** Not requested, but its requirements are added so that it can be selected at runtime. */
        REGISTER_PREREGISTERED,
        /** Not requested and preregistration is switched off. */
        SKIP
    }

    private static final Map<UpscalerBackend, Boolean> decided = new EnumMap<>(UpscalerBackend.class);

    private UpscalerPreregistration() {}

    /** {@code true} unless {@code -Dvkrtx.upscaler.preregister=false}. Any other non-empty value than true/false is an error. */
    public static boolean enabled() {
        return parseEnabled(System.getProperty(PROPERTY));
    }

    static boolean parseEnabled(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("-D" + PROPERTY + " must be true or false, got " + raw);
    }

    public static Decision decide(boolean requested, boolean preregisterEnabled) {
        if (requested) {
            return Decision.REGISTER_REQUESTED;
        }
        return preregisterEnabled ? Decision.REGISTER_PREREGISTERED : Decision.SKIP;
    }

    /** Called by the provider of {@code backend} once it knows whether the requirements were really registered. */
    public static synchronized void record(UpscalerBackend backend, boolean registered) {
        decided.put(backend, registered);
    }

    /** True when the device requirements of {@code backend} were registered (the provider already ran). */
    public static synchronized boolean isRegistered(UpscalerBackend backend) {
        return Boolean.TRUE.equals(decided.get(backend));
    }

    /** True once all providers (XeSS, DLSS, FSR) ran: before that the answer of {@link #isRegistered} is not final. */
    public static synchronized boolean providersDecided() {
        return decided.containsKey(UpscalerBackend.XESS) && decided.containsKey(UpscalerBackend.DLSS)
            && decided.containsKey(UpscalerBackend.FSR);
    }

    /**
     * Whether selecting {@code backend} needs a game restart: only the temporal backends do, and only when their device
     * requirements were not registered (preregistration off, library missing at startup, library appeared later).
     * BILINEAR never needs one.
     */
    public static boolean requiresRestart(UpscalerBackend backend) {
        return !backend.isBuiltIn() && !isRegistered(backend);
    }

    /** Test hook. */
    public static synchronized void resetForTests() {
        decided.clear();
    }
}
