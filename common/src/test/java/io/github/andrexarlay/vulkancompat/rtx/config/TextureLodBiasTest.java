package io.github.andrexarlay.vulkancompat.rtx.config;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 test runner for {@link TextureLodBiasChecks}.
 * Verifies automatic negative Texture LOD Bias, point sampling sharpness,
 * RCAS integration, and resource pack resolution adaptation.
 */
class TextureLodBiasTest {

    @Test
    void testTextureLodBiasLogic() throws Exception {
        TextureLodBiasChecks.runAll();
    }
}
