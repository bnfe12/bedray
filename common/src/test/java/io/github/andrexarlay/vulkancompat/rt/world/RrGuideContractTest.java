package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RrGuideContractTest {
    @Test void rrGuidesRemainExplicitWithMotionEnabledOrDisabled() {
        int guides = RtPathTraceSlangPass.OPTION_GBUFFER;
        int rr = RtPathTraceSlangPass.OPTION_RR_GUIDES;
        int motion = RtPathTraceSlangPass.OPTION_NRD_MOTION;
        assertEquals(guides | rr | motion, RtSlangPathPass.guideOptionFlags(true, true, true));
        assertEquals(guides | rr, RtSlangPathPass.guideOptionFlags(true, false, true));
        assertEquals(guides | motion, RtSlangPathPass.guideOptionFlags(true, true, false));
        assertEquals(0, RtSlangPathPass.guideOptionFlags(false, true, true));
        assertEquals(rr | motion, ((rr | motion | RtPathTraceSlangPass.PT_STATE_MASK)
            & ~RtPathTraceSlangPass.PT_STATE_MASK), "PT history rewriting must preserve the RR/motion contract");
    }

    @Test void ptFullOverwritePublishesOnlyAfterSuccessWithoutClearingTheRing() {
        var pt = new RestirDiState(false);
        pt.layoutFor(1920, 1080);
        assertFalse(pt.historyWritten());
        pt.afterFullOverwrite(false);
        assertTrue(pt.resetPending());
        assertFalse(pt.historyWritten());
        pt.afterFullOverwrite(true);
        assertFalse(pt.resetPending());
        assertTrue(pt.historyWritten());
        assertEquals(1, pt.parity());
        pt.reset();
        assertTrue(pt.resetPending());
        assertFalse(pt.historyWritten());
        assertEquals(0, pt.parity());
        pt.afterFullOverwrite(true);
        pt.spatialPublished();
        assertEquals(0, pt.parity());
        pt.layoutFor(1280, 720);
        assertFalse(pt.historyWritten());
        assertTrue(pt.resetPending());
    }
}
