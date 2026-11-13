package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GI-stack auto-policy recommends a quality tier and per-technology switches from the device memory budget and the
 * enabled feature mask. It is a pure function (no GPU, no game state), so the memory and feature gates are checked here
 * without a running game. The ring sizes and the budget rule mirror {@code ReferencePathTraceBackend} / the Slang pass:
 * DI = two 64-byte records per pixel, PT = two 144-byte records per pixel, budget = a quarter of device memory.
 */
class GiStackPolicyTest {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;

    private static final int NV_SER = BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE | BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV;
    private static final int RT_BASE = BedrayCoreLayouts.CAP_RAY_QUERY | BedrayCoreLayouts.CAP_ACCELERATION_STRUCTURE;

    private static GiStackPolicy.Device device(long mem, int caps) {
        return new GiStackPolicy.Device(mem, caps);
    }

    @Test
    void ringSizesMatchTheMeasuredRings() {
        // 1080p: DI 253 MiB, PT 569 MiB (the +569 MiB reserved delta measured in restir_pt_rd16 is exactly the PT ring).
        assertEquals(2L * W * H * 64, GiStackPolicy.diRingBytes(W, H));
        assertEquals(2L * W * H * 144, GiStackPolicy.ptRingBytes(W, H));
        assertEquals(253, GiStackPolicy.diRingBytes(W, H) / MIB);
        assertEquals(569, GiStackPolicy.ptRingBytes(W, H) / MIB);
    }

    @Test
    void budgetMirrorsTheBufferOwner() {
        assertEquals(8L * GIB / 4, GiStackPolicy.bufferBudget(8L * GIB));
        assertEquals(512L * MIB, GiStackPolicy.bufferBudget(0));
        assertEquals(512L * MIB, GiStackPolicy.bufferBudget(-1));
    }

    @Test
    void an8GbCardWithNvSerGetsMax() {
        GiStackPolicy.Plan plan = GiStackPolicy.recommend(device(8L * GIB, RT_BASE | NV_SER), W, H);
        assertEquals(GiStackPolicy.Tier.MAX, plan.tier());
        assertTrue(plan.restirDi());
        assertTrue(plan.restirPt());
        assertTrue(plan.restirPtSpatial());
        assertTrue(plan.ser());
        assertEquals(1, plan.spp());
        assertEquals(3, plan.bounces());
        assertEquals(512, plan.distance());
    }

    @Test
    void withoutTheNvFeatureAutoStopsAtBalanced() {
        GiStackPolicy.Plan plan = GiStackPolicy.recommend(device(8L * GIB, RT_BASE), W, H);
        assertEquals(GiStackPolicy.Tier.BALANCED, plan.tier());
        assertTrue(plan.restirPt(), "PT fits the 2 GiB budget, so Balanced keeps it");
        assertFalse(plan.ser(), "SER needs the NV reorder feature");
    }

    @Test
    void aTightBudgetDropsPtAndSaysWhy() {
        // 2 GiB card -> 512 MiB budget; the 569 MiB PT ring does not fit next to DI.
        GiStackPolicy.Plan plan = GiStackPolicy.recommend(device(2L * GIB, RT_BASE | NV_SER), W, H);
        assertEquals(GiStackPolicy.Tier.BASELINE, plan.tier());
        assertTrue(plan.restirDi(), "the 253 MiB DI ring still fits");
        assertFalse(plan.restirPt());
        assertFalse(plan.ser());
        assertTrue(plan.reason().toLowerCase().contains("pt"), "the reason must name what was dropped: " + plan.reason());
    }

    @Test
    void aBudgetTooSmallEvenForDiTurnsItOff() {
        // 1 GiB card -> 256 MiB budget; the 253 MiB DI ring does not fit with the auto headroom.
        GiStackPolicy.Plan plan = GiStackPolicy.recommend(device(1L * GIB, RT_BASE | NV_SER), W, H);
        assertEquals(GiStackPolicy.Tier.BASELINE, plan.tier());
        assertFalse(plan.restirDi());
        assertFalse(plan.restirPt());
    }

    @Test
    void explicitMaxWithoutNvDisablesSerButKeepsPt() {
        GiStackPolicy.Plan plan = GiStackPolicy.forTier(GiStackPolicy.Tier.MAX, device(8L * GIB, RT_BASE), W, H);
        assertTrue(plan.restirPt());
        assertFalse(plan.ser());
        assertTrue(plan.reason().toLowerCase().contains("ser"), "the reason must explain the missing SER: " + plan.reason());
    }

    @Test
    void explicitBaselineIsTheCheapestWorkingGi() {
        GiStackPolicy.Plan plan = GiStackPolicy.forTier(GiStackPolicy.Tier.BASELINE, device(8L * GIB, RT_BASE | NV_SER), W, H);
        assertEquals(GiStackPolicy.Tier.BASELINE, plan.tier());
        assertTrue(plan.restirDi());
        assertFalse(plan.restirPt());
        assertFalse(plan.ser());
        assertEquals(2, plan.bounces());
        assertEquals(256, plan.distance());
    }

    @Test
    void serNeedsBothThePipelineAndTheReorderFeature() {
        assertFalse(GiStackPolicy.serAvailable(RT_BASE | BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV),
            "reorder alone (compute ray query) is not the raygen SER path");
        assertFalse(GiStackPolicy.serAvailable(RT_BASE | BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE),
            "the pipeline alone without reorder is not SER");
        assertTrue(GiStackPolicy.serAvailable(RT_BASE | NV_SER));
    }

    @Test
    void aRicherDeviceNeverDowngradesTheAutoTier() {
        GiStackPolicy.Tier small = GiStackPolicy.recommend(device(2L * GIB, RT_BASE), W, H).tier();
        GiStackPolicy.Tier mid = GiStackPolicy.recommend(device(8L * GIB, RT_BASE), W, H).tier();
        GiStackPolicy.Tier big = GiStackPolicy.recommend(device(16L * GIB, RT_BASE | NV_SER), W, H).tier();
        assertTrue(small.ordinal() <= mid.ordinal());
        assertTrue(mid.ordinal() <= big.ordinal());
    }

    @Test
    void aLargerRenderExtentCanDropTheTier() {
        // 4K needs four times the ring memory; on an 8 GiB card the PT ring no longer fits with headroom.
        GiStackPolicy.Plan hd = GiStackPolicy.recommend(device(8L * GIB, RT_BASE | NV_SER), W, H);
        GiStackPolicy.Plan uhd = GiStackPolicy.recommend(device(8L * GIB, RT_BASE | NV_SER), 3840, 2160);
        assertTrue(hd.tier().ordinal() >= uhd.tier().ordinal());
        assertFalse(uhd.restirPt(), "the 4K PT ring (~2.3 GiB) exceeds the 2 GiB budget");
    }
}