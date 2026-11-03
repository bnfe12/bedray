package io.github.andrexarlay.vulkancompat.rt.denoise;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NrdSignalContractTest {
    @Test void anOlderBridgeCannotSilentlyConsumeNewSparseLobeInputsWithoutReconstruction() {
        var failure = assertThrows(NrdException.class, () -> NrdNative.requirePrimaryLobeSignals(0));
        assertTrue(failure.getMessage().contains("signal_contract"));
        assertTrue(failure.getMessage().contains("rebuild"));
    }
    @Test void theExactSemanticContractIsRequiredRatherThanAssumingForwardCompatibility() {
        assertDoesNotThrow(() -> NrdNative.requirePrimaryLobeSignals(1));
        assertThrows(NrdException.class, () -> NrdNative.requirePrimaryLobeSignals(2));
        assertThrows(NrdException.class, () -> NrdNative.requirePrimaryLobeSignals(-1));
    }
}
