package io.github.andrexarlay.vulkancompat.rt.particles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleGroupLedgerTest {
    @Test
    void eachGroupIsCapturedOnceEvenWhenTheGameExecutesItPerOitStage() {
        ParticleGroupLedger ledger = new ParticleGroupLedger();
        assertTrue(ledger.firstVisit(0));
        assertTrue(ledger.firstVisit(1));
        // depth bounds, transmittance, accumulate: the same group index again
        assertFalse(ledger.firstVisit(1));
        assertFalse(ledger.firstVisit(1));
        assertFalse(ledger.firstVisit(0));
        assertEquals(2, ledger.groups());
    }

    @Test
    void clearForgetsTheFrame() {
        ParticleGroupLedger ledger = new ParticleGroupLedger();
        assertTrue(ledger.firstVisit(3));
        ledger.clear();
        assertEquals(0, ledger.groups());
        assertTrue(ledger.firstVisit(3));
    }

    @Test
    void negativeIndexIsRejected() {
        assertFalse(new ParticleGroupLedger().firstVisit(-1));
    }
}
