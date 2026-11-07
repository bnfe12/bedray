package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-person shadow mode rides in the top two bits of {@code entityState.z}, next to the avatar's triangle count, so
 * no push-constant layout (Java, Slang and native) has to change. Every pass masks the count back out.
 */
class SelfShadowPackingTest {

    @Test
    void countAndModeRoundTripThroughTheShaderUnpacking() {
        int[] counts = {0, 1, 3_000, SelfAvatarGate.COUNT_MASK};
        int[] modes = {RtSettings.SELF_SHADOW_FULL, RtSettings.SELF_SHADOW_NO_FOOTPRINT, RtSettings.SELF_SHADOW_OFF};
        for (int count : counts) {
            for (int mode : modes) {
                int word = SelfAvatarGate.packCountAndShadow(count, mode);
                assertEquals(count, word & 0x3FFFFFFF, "pc.entityState.z & 0x3FFFFFFFu is the count");
                assertEquals(mode, word >>> 30, "pc.entityState.z >> 30u is the mode (unsigned shift in Slang)");
            }
        }
    }

    @Test
    void outOfRangeValuesAreRejectedNotWrapped() {
        assertThrows(IllegalArgumentException.class, () -> SelfAvatarGate.packCountAndShadow(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> SelfAvatarGate.packCountAndShadow(SelfAvatarGate.COUNT_MASK + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> SelfAvatarGate.packCountAndShadow(10, 3));
    }

    @Test
    void theDefaultKeepsTheBodyShadowWithoutTheBlotUnderfoot() {
        assertEquals(RtSettings.SELF_SHADOW_NO_FOOTPRINT, RtSettings.selfShadow);
    }

    @Test
    void bothHostWritersPackTheMode() throws IOException {
        String base = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/";
        for (String file : new String[] {"RtPathTraceSlangPass.java", "BedrayPathTraceNative.java"}) {
            String source = Files.readString(Path.of(base + file));
            assertTrue(source.contains("SelfAvatarGate.packCountAndShadow("), file + " must pack the self_shadow mode");
            assertTrue(source.contains("RtSettings.selfShadow)"), file + " packs the live setting");
        }
    }
}
