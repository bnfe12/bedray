package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure supported/enabled/implemented logic for the GI execution status row. No GPU: the mask and the startup requests are
 * the only inputs, so the honesty contract (a supported-but-unimplemented feature is never reported as a working toggle)
 * is checked without a game. The real RTX 5060 Ti mask 0x677 lacks bits 11/12, so SER must read unsupported there.
 */
class GiExecutionStatusTest {

    private static final int PIPELINE = BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE;
    private static final int REORDER = BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV;
    private static final int MICROMAP = BedrayCoreLayouts.CAP_OPACITY_MICROMAP;
    private static final int FULL = PIPELINE | REORDER | MICROMAP;
    private static final int DEVICE_5060TI = 0x677;

    private static GiExecutionStatus.Tech tech(List<GiExecutionStatus.Tech> list, String key) {
        return list.stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void fullDeviceWithBothPathsRequested() {
        var list = GiExecutionStatus.technologies(FULL, true, true);
        var raygen = tech(list, "raygen");
        assertTrue(raygen.supported());
        assertTrue(raygen.enabled());
        assertTrue(raygen.implemented());
        var ser = tech(list, "ser");
        assertTrue(ser.supported());
        assertTrue(ser.enabled());
        assertTrue(ser.implemented());
    }

    @Test
    void ommIsSupportedButNeverImplementedOrEnabled() {
        var omm = tech(GiExecutionStatus.technologies(FULL, true, true), "omm");
        assertTrue(omm.supported(), "micromap bit present -> supported");
        assertFalse(omm.enabled(), "OMM has no runtime path; never enabled");
        assertFalse(omm.implemented(), "OMM is deferred; not implemented");
        assertEquals("supported, not implemented",
            GiExecutionStatus.state(omm.supported(), omm.enabled(), omm.implemented()));
    }

    @Test
    void partitionedTlasIsNotImplementedAndRegionalUpdateIsTheActivePath() {
        var ptlas = tech(GiExecutionStatus.technologies(FULL, true, true), "ptlas");
        assertFalse(ptlas.supported());
        assertFalse(ptlas.enabled());
        assertFalse(ptlas.implemented());
        assertTrue(ptlas.note().contains("Regional in-place"));
    }

    @Test
    void realDeviceMaskReportsSerUnsupported() {
        var ser = tech(GiExecutionStatus.technologies(DEVICE_5060TI, true, true), "ser");
        assertFalse(ser.supported(), "0x677 lacks the NV invocation-reorder bit");
        assertFalse(ser.enabled(), "a requested SER on an unsupported device is not enabled");
        assertTrue(ser.implemented(), "the code exists; only the device feature is absent");
        var raygen = tech(GiExecutionStatus.technologies(DEVICE_5060TI, true, true), "raygen");
        assertFalse(raygen.supported(), "0x677 lacks the ray tracing pipeline bit");
        assertFalse(raygen.enabled());
    }

    @Test
    void requestedSerWithoutReorderFeatureIsNotEnabled() {
        var ser = tech(GiExecutionStatus.technologies(PIPELINE, false, true), "ser");
        assertFalse(ser.supported());
        assertFalse(ser.enabled());
    }

    @Test
    void raygenEnabledOnlyWhenRequestedAndSupported() {
        assertTrue(tech(GiExecutionStatus.technologies(PIPELINE, true, false), "raygen").enabled());
        assertFalse(tech(GiExecutionStatus.technologies(PIPELINE, false, false), "raygen").enabled());
        assertFalse(tech(GiExecutionStatus.technologies(0, true, false), "raygen").enabled());
    }

    @Test
    void stateLabelsAreHonest() {
        assertEquals("enabled", GiExecutionStatus.state(true, true, true));
        assertEquals("supported, off", GiExecutionStatus.state(true, false, true));
        assertEquals("unsupported", GiExecutionStatus.state(false, false, true));
        assertEquals("supported, not implemented", GiExecutionStatus.state(true, false, false));
        assertEquals("not implemented", GiExecutionStatus.state(false, false, false));
    }

    @Test
    void summaryListsEveryTechnology() {
        String summary = GiExecutionStatus.summary(FULL, true, true);
        assertTrue(summary.contains("raygen=enabled"));
        assertTrue(summary.contains("ser=enabled"));
        assertTrue(summary.contains("omm=supported, not implemented"));
        assertTrue(summary.contains("ptlas=not implemented"));
    }

    @Test
    void describeHasOneLinePerTechnology() {
        String describe = GiExecutionStatus.describe(FULL, true, true);
        assertEquals(4, describe.lines().count());
        assertTrue(describe.contains("Raygen PT pipeline"));
        assertTrue(describe.contains("NV Shader Execution Reordering"));
    }
}