package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto-policy for the GI stack: recommends a quality tier and the per-technology switches (ReSTIR DI, ReSTIR PT, NV SER)
 * from the device memory budget and the enabled feature mask. A pure function — no GPU, no game state — so the gates are
 * unit-tested without a running game ({@code GiStackPolicyTest}).
 * <p>
 * The memory model mirrors the renderer: the reservoir buffer owner gets a quarter of device memory
 * ({@code ReferencePathTraceBackend.bufferBudget}); the DI ring is two 64-byte records per render pixel and the PT ring two
 * 144-byte records ({@code RtPathTraceSlangPass.RESERVOIR_BYTES} / {@code PT_RESERVOIR_BYTES}). A technology is only
 * recommended when its ring fits that budget and, for SER, when the device really enabled the NV feature. The policy
 * recommends; it never forces — the measured costs (PT +24.7% gpu_rt and +597 MiB at 1080p, RD16) stay the player's choice.
 */
public final class GiStackPolicy {

    /** Cheapest working GI (DI only) → DI + PT → DI + PT + SER. The order is the auto-policy's monotone scale. */
    public enum Tier { BASELINE, BALANCED, MAX }

    /** What the device reports: total local memory and the mask of features the logical device was created with. */
    public record Device(long memoryBytes, int capabilityMask) {
        public boolean has(int cap) {
            return (capabilityMask & cap) != 0;
        }
    }

    /**
     * A recommended configuration. {@code restirPtSpatial} rides on {@code restirPt} (it pools the PT ring); {@code ser}
     * needs the raygen pipeline. {@code reason} explains every downgrade so the screen can show why a tier was capped.
     */
    public record Plan(Tier tier, int spp, int bounces, int distance, boolean restirDi, boolean restirPt,
                       boolean restirPtSpatial, boolean ser, long diRingBytes, long ptRingBytes, long budgetBytes,
                       String reason) {
    }

    static final int DI_RECORD_BYTES = 64;
    static final int PT_RECORD_BYTES = 144;
    /** Auto leaves a quarter of the budget free so a maximised window (a larger extent) still fits; an explicit tier uses it all. */
    private static final double AUTO_HEADROOM = 0.75;
    private static final long MIB = 1024L * 1024L;

    private GiStackPolicy() {
    }

    static long diRingBytes(int width, int height) {
        return 2L * width * height * DI_RECORD_BYTES;
    }

    static long ptRingBytes(int width, int height) {
        return 2L * width * height * PT_RECORD_BYTES;
    }

    static long bufferBudget(long deviceMemoryBytes) {
        return deviceMemoryBytes > 0 ? deviceMemoryBytes / 4 : 512L * MIB;
    }

    /** SER is the raygen invocation-reorder path: it needs both the ray tracing pipeline and the NV reorder feature. */
    public static boolean serAvailable(int capabilityMask) {
        return (capabilityMask & BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE) != 0
            && (capabilityMask & BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV) != 0;
    }

    /** The richest tier whose memory and feature requirements this device meets at this render extent. */
    public static Plan recommend(Device device, int width, int height) {
        long budget = bufferBudget(device.memoryBytes());
        long di = diRingBytes(width, height);
        long pt = ptRingBytes(width, height);
        boolean diFits = di <= (long) (budget * AUTO_HEADROOM);
        boolean ptFits = diFits && pt <= budget - di && di + pt <= (long) (budget * AUTO_HEADROOM);
        boolean ser = serAvailable(device.capabilityMask());
        Tier tier = ptFits && ser ? Tier.MAX : ptFits ? Tier.BALANCED : Tier.BASELINE;
        // Auto always considers PT, so a downgrade to Baseline because the ring does not fit is explained in the reason.
        return build(tier, device, width, height, budget, di, pt, AUTO_HEADROOM, true);
    }

    /** An explicit tier choice: keeps every technology the tier names that the device can actually run, and says what it dropped. */
    public static Plan forTier(Tier tier, Device device, int width, int height) {
        long budget = bufferBudget(device.memoryBytes());
        return build(tier, device, width, height, budget, diRingBytes(width, height), ptRingBytes(width, height), 1.0,
            tier != Tier.BASELINE);
    }

    private static Plan build(Tier tier, Device device, int width, int height, long budget, long di, long pt,
                              double headroom, boolean ptDesired) {
        List<String> notes = new ArrayList<>();
        boolean diFits = di <= (long) (budget * headroom);
        if (!diFits) {
            notes.add("DI ring " + mib(di) + " MiB does not fit the " + mib(budget) + " MiB budget");
        }
        boolean ptFits = diFits && pt <= budget - di && di + pt <= (long) (budget * headroom);
        if (ptDesired && !ptFits) {
            notes.add("PT ring " + mib(pt) + " MiB does not fit the " + mib(budget) + " MiB budget");
        }
        boolean serWanted = tier == Tier.MAX;
        boolean ser = serWanted && serAvailable(device.capabilityMask());
        if (serWanted && !ser) {
            notes.add("SER unavailable: needs the NV ray tracing invocation reorder feature");
        }
        boolean restirPt = ptDesired && ptFits;
        int bounces = tier == Tier.BASELINE ? 2 : 3;
        int distance = tier == Tier.BASELINE ? 256 : 512;
        String reason = notes.isEmpty()
            ? "Fits the " + mib(budget) + " MiB reservoir budget at " + width + "x" + height
            : String.join("; ", notes);
        return new Plan(tier, 1, bounces, distance, diFits, restirPt, restirPt, ser, di, pt, budget, reason);
    }

    private static long mib(long bytes) {
        return Math.max(0L, bytes / MIB);
    }

    /** One-line summary for the log and the settings tooltip. */
    public static String describe(Plan plan) {
        return String.format(Locale.ROOT, "%s: spp=%d bounces=%d dist=%d di=%s pt=%s ser=%s (%s)",
            plan.tier(), plan.spp(), plan.bounces(), plan.distance(), plan.restirDi(), plan.restirPt(), plan.ser(), plan.reason());
    }
}