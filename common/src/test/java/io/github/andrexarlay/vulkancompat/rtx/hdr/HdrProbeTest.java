package io.github.andrexarlay.vulkancompat.rtx.hdr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2: names and classification of colour spaces (Vulkan registry values), no GPU. */
public class HdrProbeTest {
    @Test
    public void namesKnownColorSpaces() {
        assertEquals("SRGB_NONLINEAR", HdrProbe.colorSpaceName(0));
        assertEquals("HDR10_ST2084 (PQ)", HdrProbe.colorSpaceName(1000104008));
        assertEquals("EXTENDED_SRGB_LINEAR (scRGB)", HdrProbe.colorSpaceName(1000104001));
        assertEquals("UNKNOWN(5)", HdrProbe.colorSpaceName(5));
    }

    @Test
    public void classifiesHdrSpaces() {
        assertFalse(HdrProbe.isHdrColorSpace(0));
        assertFalse(HdrProbe.isHdrColorSpace(1000104003));
        assertTrue(HdrProbe.isHdrColorSpace(1000104001));
        assertTrue(HdrProbe.isHdrColorSpace(1000104008));
        assertTrue(HdrProbe.isHdrColorSpace(1000104010));
    }

    @Test
    public void namesEngineSwapchainFormats() {
        assertEquals("R8G8B8A8_UNORM", HdrProbe.formatName(37));
        assertEquals("B8G8R8A8_UNORM", HdrProbe.formatName(44));
        assertEquals("R16G16B16A16_SFLOAT", HdrProbe.formatName(97));
    }
}
