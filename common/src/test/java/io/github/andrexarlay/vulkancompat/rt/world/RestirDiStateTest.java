package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring state is the one part of ReSTIR DI that needs no device: it decides which half a frame reads, whether that
 * half holds records and whether the storage has to be cleared first. Every one of those answers is a frame of
 * corruption when it is wrong, so they are pinned here rather than only in the shader.
 */
class RestirDiStateTest {

    @Test
    void anExternalRingAlternatesHalvesAndMovesOnlyOnAWrite() {
        RestirDiState di = new RestirDiState(false);
        assertTrue(di.resetPending(), "a ring nobody has filled is not safe to read");
        assertEquals(0, di.parity(), "the first dispatch owns half zero");

        di.ringCleared();
        assertFalse(di.resetPending(), "the fill cleared both halves");
        assertFalse(di.historyWritten(), "and wrote nothing to reuse yet");
        assertEquals(RestirDiState.FLAG_ENABLED, di.flags(false, false),
            "a frame with no history asks the shader to start one");

        di.afterDispatch(true);
        assertTrue(di.historyWritten());
        assertEquals(1, di.parity(), "the next frame reads the half this one wrote");
        assertEquals(RestirDiState.FLAG_ENABLED | RestirDiState.FLAG_HISTORY, di.flags(false, false));

        di.afterDispatch(false);
        assertFalse(di.historyWritten(), "a frame without a reservoir list wrote nothing worth merging");
        assertEquals(1, di.parity(), "and the half it did not write is still the half the next frame reads");
        assertEquals(RestirDiState.FLAG_ENABLED, di.flags(false, false));

        di.afterDispatch(true);
        assertEquals(0, di.parity(), "two writes return to the first half");
    }

    @Test
    void aClearedRingForgetsTheParityItHad() {
        RestirDiState di = new RestirDiState(false);
        di.ringCleared();
        di.afterDispatch(true);
        di.afterDispatch(true);
        assertEquals(0, di.parity());

        di.reset();
        assertTrue(di.resetPending());
        assertFalse(di.historyWritten(), "the records the reset discards cannot be reused");
        assertEquals(0, di.parity(), "both halves are zeroed, so the ring starts over at the first one");
    }

    @Test
    void aNativeRingNeverNamesAParityOrAHistoryBit() {
        RestirDiState di = new RestirDiState(true);
        assertTrue(di.available(false), "the core's ring exists even when the host holds no buffer");
        assertTrue(di.resetPending(), "and it starts out needing a clear, requested with the first dispatch");

        di.afterDispatch(true);
        assertFalse(di.resetPending(), "the core cleared the ring it owns when it ran DI");
        assertTrue(di.historyWritten(), "its own half is what the next frame reads");
        assertEquals(RestirDiState.FLAG_ENABLED, di.flags(false, false),
            "the core derives history from its own parity: a host HISTORY bit would be a second opinion");
        for (int frame = 0; frame < 4; frame++) {
            di.afterDispatch(true);
            assertEquals(0, di.parity(), "the host has no parity to name with a native ring");
        }
    }

    @Test
    void aResetForwardedToTheCoreSurvivesASkippedFrame() {
        RestirDiState di = new RestirDiState(true);
        di.afterDispatch(true);
        di.reset();
        di.afterDispatch(false);
        assertTrue(di.resetPending(), "the core touches the ring only when it runs DI, so the request is still open");
        di.afterDispatch(true);
        assertFalse(di.resetPending(), "and the first dispatch that runs DI consumes it");
    }

    @Test
    void aNewExtentInvalidatesEvenWhenThePixelCountIsTheSame() {
        RestirDiState di = new RestirDiState(false);
        di.layoutFor(1920, 1080);
        di.ringCleared();
        di.afterDispatch(true);
        assertFalse(di.extentDiffers(1920, 1080), "the records are addressed for this extent");
        assertTrue(di.extentDiffers(1080, 1920),
            "a transposed swap keeps the pixel count and moves every slot, so the old records must not be read");
        di.layoutFor(1080, 1920);
        assertTrue(di.resetPending(), "re-laying out the same storage clears it rather than reinterpret it");
        assertFalse(di.historyWritten());
    }

    @Test
    void freshStorageIsAlwaysCleared() {
        RestirDiState di = new RestirDiState(false);
        di.layoutFor(640, 360);
        di.ringCleared();
        di.afterDispatch(true);
        assertFalse(di.resetPending());

        di.layoutFor(640, 360);
        assertTrue(di.resetPending(), "a newly allocated buffer holds uninitialised memory, not the old records");
    }

    @Test
    void theModeLogReportsWhatTheFrameWillDo() {
        RestirDiState external = new RestirDiState(false);
        assertEquals("off", external.mode(false, true, true), "the player's switch wins over everything");
        assertEquals("off", external.mode(true, false, true), "and a ring the pass does not hold cannot be bound");
        assertEquals("fresh", external.mode(true, true, false), "first frame: the shader starts the history it uses");
        external.ringCleared();
        external.afterDispatch(true);
        assertEquals("temporal+spatial", external.mode(true, true, true));

        RestirDiState nativeRing = new RestirDiState(true);
        assertEquals("fresh+spatial", nativeRing.mode(true, false, true),
            "the core's ring needs no host buffer, and the stage is still the player's switch");
    }
}
