package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisTargetsTest {

    private static IrisPackSettings settings(String props) {
        return IrisPackSettings.parse(PackProperties.parse(props), Set.of("world0"));
    }

    @Test
    void defaultColorCountAndFormats() {
        IrisTargets t = IrisTargets.create(settings(""));
        assertEquals(8, t.colorCount());
        assertEquals("RGBA8", t.colors().get(0).format());
        assertEquals(37, t.colors().get(0).vkFormat()); // VK_FORMAT_R8G8B8A8_UNORM
    }

    @Test
    void declaredFormatsAndHigherIndexExtendCount() {
        IrisTargets t = IrisTargets.create(settings("colortex1.format=RGBA16F\ncolortex9.format=R32F\n"));
        assertEquals(10, t.colorCount());
        assertEquals(97, t.colors().get(1).vkFormat()); // VK_FORMAT_R16G16B16A16_SFLOAT
        assertEquals(100, t.colors().get(9).vkFormat()); // VK_FORMAT_R32_SFLOAT
    }

    @Test
    void depthAndShadowTargetsPresent() {
        IrisTargets t = IrisTargets.create(settings(""));
        assertEquals(3, t.depths().size());
        assertEquals(126, t.depths().get(0).vkFormat()); // VK_FORMAT_D32_SFLOAT
        assertEquals(2, t.shadowDepths().size());
        assertEquals(2, t.shadowColors().size());
    }

    @Test
    void pingPongSwaps() {
        IrisTargets t = IrisTargets.create(settings(""));
        assertEquals(0, t.readIndex(0));
        assertEquals(1, t.writeIndex(0));
        t.swap(0);
        assertEquals(1, t.readIndex(0));
        assertEquals(0, t.writeIndex(0));
        t.swap(0);
        assertEquals(0, t.readIndex(0));
    }

    @Test
    void resizeTriggersRecreate() {
        IrisTargets t = IrisTargets.create(settings(""));
        t.setSize(1280, 720);
        assertFalse(t.needsRecreate(1280, 720));
        assertTrue(t.needsRecreate(1920, 1080));
        t.setSize(1920, 1080);
        assertFalse(t.needsRecreate(1920, 1080));
    }

    @Test
    void unknownFormatIsNamedNotSilent() {
        assertEquals(-1, IrisTargets.vkFormat("BOGUS"));
    }
}