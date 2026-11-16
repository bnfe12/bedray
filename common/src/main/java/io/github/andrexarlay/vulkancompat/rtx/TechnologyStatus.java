package io.github.andrexarlay.vulkancompat.rtx;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;

import java.util.ArrayList;
import java.util.List;

/**
 * One honest line per upscaling-adjacent technology that depends on a vendor library or an experimental path: DLSS multi frame
 * generation, AMD FSR 3 frame generation, Intel XeSS frame generation, DLSS Ray Reconstruction, DLSS Neural Rendering. Pure
 * ({@link #describe(Inputs)}) so it is unit-tested; {@link #live()} fills the inputs from the running game and never throws.
 * It is shown as a read-only settings row and says what is missing instead of hiding the feature.
 */
public final class TechnologyStatus {
    private TechnologyStatus() {
    }

    /** What the lines are built from; every field is a plain value so tests need no game. */
    public record Inputs(String frameGenAvailability, int frameGenMaxMultiplier, boolean dlssLibraryUsable,
                         String dlssReason, boolean neuralRequested, boolean rayReconstructionSelected) {
    }

    /** One line per technology, in a fixed order. */
    public static List<String> lines(Inputs in) {
        List<String> out = new ArrayList<>();
        out.add("DLSS multi frame generation: " + switch (in.frameGenAvailability()) {
            case "READY" -> "ready, 2x up to " + Math.max(2, in.frameGenMaxMultiplier()) + "x (the driver's limit)";
            case "NEEDS_RESTART" -> "files found; takes effect after a restart";
            case "NO_BINARIES" -> "not available: the Streamline files are missing (see Download DLLs)";
            case "FAILED" -> "not available: initialisation failed, see the log";
            default -> "not available (" + in.frameGenAvailability() + ")";
        });
        out.add("AMD FSR 3 frame generation (2x): code present, no accepted live result; needs amd_fidelityfx_vk.dll");
        out.add("Intel XeSS frame generation / XeLL: not implemented (the SDK path is DirectX 12 only)");
        out.add("DLSS Ray Reconstruction: " + (in.dlssLibraryUsable()
            ? (in.rayReconstructionSelected() ? "selected and the DLSS files are usable" : "the DLSS files are usable; pick it under Denoiser")
            : "not available: " + in.dlssReason()));
        out.add("DLSS Neural Rendering (DLSS 5): " + (!in.neuralRequested()
            ? "off (experimental; needs the NGX Neural Rendering runtime, frame generation and Ray Reconstruction off)"
            : in.dlssLibraryUsable() ? "requested; applies after a restart if NGX accepts the GPU and the runtime"
                : "requested but the DLSS files are not usable: " + in.dlssReason()));
        return out;
    }

    /** Multi-line text for a tooltip. */
    public static String describe(Inputs in) {
        return String.join("\n", lines(in));
    }

    /** Reads the running game; any failure degrades to a short message instead of an exception. */
    public static String live() {
        try {
            DlssAvailability.Library lib = DlssAvailability.library();
            boolean usable = lib != null && lib.usable();
            Inputs in = new Inputs(FrameGenRuntime.availability().name(), FrameGenRuntime.maxMultiplier(), usable,
                lib == null ? "library not probed yet" : lib.reason(),
                io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.dlssNeuralRendering()
                    || Boolean.getBoolean("bedray.dlss5"),
                io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.denoiser()
                    == io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.PathDenoiser.DLSS_RR);
            return describe(in);
        } catch (RuntimeException | LinkageError e) {
            return "Technology status is not available yet: " + e.getClass().getSimpleName();
        }
    }
}
