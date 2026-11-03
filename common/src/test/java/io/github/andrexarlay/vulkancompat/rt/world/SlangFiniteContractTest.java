package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-6: {@code isFinite()} used {@code value == value}. Under fast-math the compiler may fold that to
 * {@code true} and assume ordered comparisons with NaN have a definite result, so a NaN would pass the guard
 * that exists to keep it out of radiance and reservoir state.
 * <p>
 * The shared helper now classifies the IEEE-754 exponent bits instead. The numeric half below proves the
 * predicate on the actual float bit patterns; the source half pins the shader to it.
 */
class SlangFiniteContractTest {
    private static final String SOURCE = "src/main/resources/shaders/slang/contracts/math.slang";

    @Test
    void isFiniteTestsTheExponentAndNotTheValue() throws IOException {
        String source = Files.readString(Path.of(SOURCE));
        assertTrue(source.contains("return (asuint(value) & 0x7f800000u) != 0x7f800000u;"),
            "the guard must be a bit test: integer ops are unaffected by fast-math");
        assertFalse(source.lines().anyMatch(line -> !line.trim().startsWith("//") && line.contains("value == value")),
            "a self-comparison may be folded to true before the NaN check runs");
    }

    @Test
    void theExponentMaskClassifiesEveryIeee754Class() {
        for (float finite : new float[] {0.0f, -0.0f, 1.0f, -1.0f, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_NORMAL}) {
            assertTrue(isFinite(finite), "finite value rejected: " + finite);
        }
        for (float nonFinite : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.intBitsToFloat(0x7fc00001)}) {
            assertFalse(isFinite(nonFinite), "non-finite value accepted: " + nonFinite);
        }
    }

    private static boolean isFinite(float value) {
        return (Float.floatToRawIntBits(value) & 0x7f800000) != 0x7f800000;
    }
}
