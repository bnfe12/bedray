package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;

/**
 * What a requested denoiser resolves to with this run's libraries and session state, plus the reason when it resolves
 * to {@link DenoiserMode#OFF}. The table is pure: the renderer gathers the live availability and logs the result, so
 * every branch here is unit-testable without Vulkan, NGX or NRD.
 */
public final class DenoiserResolution {
    private DenoiserResolution() {
    }

    public record Result(DenoiserMode mode, String reason) {
    }

    /**
     * @param want           the persisted setting ({@code path_denoiser})
     * @param dlssRrFailed   a DLSS-RR init/record failure earlier this session
     * @param nrdFailed      an NRD failure earlier this session
     * @param dlssUsable     {@code vkb_dlss.dll} loaded and usable
     * @param dlssReason     why it is not usable ({@code "null"} when the library object itself is missing)
     * @param frameGenOwnsNgx frame generation loaded NGX, so only Streamline's plugin can create RR
     * @param nrdUsable      the NRD natives ({@code NRD.dll} + {@code vkb_nrd.dll}) are usable
     * @param nrdReason      why they are not ({@code "null"} when the availability object is missing)
     * @param requestedFlag  {@code -Dvkrtx.denoiser} when it names an NRD variant; {@code null}/other values keep REBLUR
     */
    public static Result resolve(RtSettings.PathDenoiser want, boolean dlssRrFailed, boolean nrdFailed,
                                 boolean dlssUsable, String dlssReason, boolean frameGenOwnsNgx,
                                 boolean nrdUsable, String nrdReason, DenoiserMode requestedFlag) {
        return switch (want) {
            case OFF -> new Result(DenoiserMode.OFF, "setting");
            case DLSS_RR -> {
                DenoiserMode mode = DenoiserMode.OFF;
                String reason = "setting";
                if (dlssRrFailed) {
                    reason = "dlss_rr_failed_earlier_this_session";
                } else if (frameGenOwnsNgx) {
                    // Frame generation owns NGX: raw NGX cannot create Ray Reconstruction next to it (0xBAD0000B in the
                    // owner's configuration), only the Streamline plugin can. Say so instead of trying and failing.
                    reason = "frame_generation_needs_sl.dlss_d.dll+nvngx_dlssd.dll_in_the_streamline_folder";
                } else if (!dlssUsable) {
                    reason = "dlss_library_unusable " + dlssReason;
                } else {
                    mode = DenoiserMode.DLSS_RR;
                }
                if (mode == DenoiserMode.OFF && !nrdFailed && nrdUsable) {
                    mode = DenoiserMode.NRD_REBLUR;
                    reason += " -> fallback_to_nrd";
                }
                yield new Result(mode, reason);
            }
            case NRD -> {
                if (nrdFailed) {
                    yield new Result(DenoiserMode.OFF, "nrd_failed_earlier_this_session");
                }
                if (!nrdUsable) {
                    yield new Result(DenoiserMode.OFF, "nrd_library_unusable " + nrdReason);
                }
                yield new Result(requestedFlag != null && requestedFlag.isNrd() ? requestedFlag : DenoiserMode.NRD_REBLUR,
                    "setting");
            }
        };
    }
}
