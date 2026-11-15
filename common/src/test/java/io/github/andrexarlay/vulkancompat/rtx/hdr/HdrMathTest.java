package io.github.andrexarlay.vulkancompat.rtx.hdr;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2: the PQ and gamut maths of the HDR output pass (Java reference) and its agreement with the shader source. */
public class HdrMathTest {
    @Test
    public void pqEndpointsAndReferenceWhite() {
        assertEquals(0.0, HdrMath.pqEncode(0.0), 1.0e-5); // ST 2084 gives c1^m2 (about 7e-7) at zero, not exactly 0
        assertEquals(1.0, HdrMath.pqEncode(10000.0), 1.0e-9);
        // 203 nits (BT.2408 reference white) sits near code value 0.58, 100 nits near 0.508.
        assertEquals(0.58, HdrMath.pqEncode(203.0), 0.005);
        assertEquals(0.508, HdrMath.pqEncode(100.0), 0.005);
    }

    @Test
    public void pqRoundTrips() {
        for (double nits : new double[] {0.5, 10, 80, 200, 1000, 4000, 9000}) {
            assertEquals(nits, HdrMath.pqDecode(HdrMath.pqEncode(nits)), nits * 1.0e-6 + 1.0e-6);
        }
    }

    @Test
    public void whiteStaysWhiteInRec2020() {
        double[] w = HdrMath.rec709ToRec2020(1.0, 1.0, 1.0);
        for (double c : w) {
            assertEquals(1.0, c, 1.0e-4);
        }
        double[] red = HdrMath.rec709ToRec2020(1.0, 0.0, 0.0);
        assertTrue(red[0] < 1.0 && red[1] > 0.0 && red[2] > 0.0, "709 red is inside the 2020 gamut");
    }

    @Test
    public void shaderCarriesTheSameConstants() throws Exception {
        String src;
        try (InputStream in = HdrMathTest.class.getResourceAsStream("/shaders/slang/passes/hdr_output.slang")) {
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String c : new String[] {"0.1593017578125", "78.84375", "0.8359375", "18.8515625", "18.6875",
            "0.627404", "0.329283", "0.043313", "0.069097", "0.919540", "0.011362", "0.016391", "0.088013", "0.895595"}) {
            assertTrue(src.contains(c), "hdr_output.slang lacks constant " + c);
        }
    }
}
