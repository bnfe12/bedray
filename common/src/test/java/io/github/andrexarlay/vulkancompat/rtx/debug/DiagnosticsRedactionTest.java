package io.github.andrexarlay.vulkancompat.rtx.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosticsRedactionTest {
    @Test
    void shortIdentitiesKeepWordBoundariesAndExistingRedactions() {
        var scrubber = DiagnosticsScrubber.of("A", "xy", "Я", "redacted");
        String expected = "user <redacted> on <redacted> with <redacted>; xylophone";
        assertEquals(expected, scrubber.scrub("user a on XY with я; xylophone"));
        assertEquals(expected, scrubber.scrub(expected));
    }

    @Test
    void redactsShortBearerTokensAndSecretsWithWhitespace() {
        var scrubber = DiagnosticsScrubber.patternsOnly();
        assertEquals("Authorization: Bearer <redacted>", scrubber.scrub("Authorization: Bearer Ab3"));
        assertEquals("password=<redacted>\nnext line", scrubber.scrub("password=two words\nnext line"));
        assertEquals("{\"api_key\": <redacted>", scrubber.scrub("{\"api_key\": \"two words\"}"));
        assertEquals("accessToken=<redacted> x", scrubber.scrub("accessToken=eyJhbGciOi.abc x"));
    }
}
