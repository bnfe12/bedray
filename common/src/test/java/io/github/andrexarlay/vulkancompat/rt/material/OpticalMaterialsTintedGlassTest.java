package io.github.andrexarlay.vulkancompat.rt.material;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2: vanilla tinted_glass is see-through to the camera but blocks ALL light (unlike clear and stained glass, which
 * transmit tinted light). The baker must tag it with a dedicated optical-profile id whose transmittance is zero, so the
 * visibility walk can stop sun/shadow rays while the camera path keeps the pane a translucent filter. The id lives in the
 * five high source-metadata bits the atlas already carries; no new GPU binding is needed for the sentinel.
 */
class OpticalMaterialsTintedGlassTest {

    /** A resource provider with no packs: the vanilla auto-PBR path, where every sidecar lookup is empty. */
    private static ResourceProvider emptyResources() {
        return (ResourceProvider) Proxy.newProxyInstance(
            ResourceProvider.class.getClassLoader(), new Class<?>[] {ResourceProvider.class},
            (proxy, method, args) -> {
                Class<?> rt = method.getReturnType();
                if (rt == Optional.class) return Optional.empty();
                if (rt == Map.class) return Map.of();
                if (rt == boolean.class) return false;
                if (rt == List.class) return List.of();
                return null;
            });
    }

    private static Identifier sprite(String path) {
        return Identifier.fromNamespaceAndPath("minecraft", path);
    }

    @Test
    void tintedGlassResolvesToTheBlockerProfileId() {
        OpticalMaterials optics = new OpticalMaterials();
        int[] material = {LabPbr.pack(2, 10, 0, 255)};
        optics.apply(material, sprite("block/tinted_glass"), emptyResources());
        assertEquals(OpticalMaterials.TINTED_GLASS_PROFILE_ID, OpticalMaterials.profileId(material[0]),
            "tinted_glass must resolve to the dedicated light-blocker profile, not the transmitting glass profile");
    }

    @Test
    void theBlockerProfileTransmitsNoLight() {
        OpticalMaterials optics = new OpticalMaterials();
        List<OpticalMaterials.Profile> palette = optics.snapshot();
        int id = OpticalMaterials.TINTED_GLASS_PROFILE_ID;
        OpticalMaterials.Profile blocker = id < palette.size() ? palette.get(id) : null;
        assertNotNull(blocker, "the light-blocker profile must exist at the dedicated id");
        assertEquals(0f, blocker.red(), 0f, "zero red transmittance: no light passes");
        assertEquals(0f, blocker.green(), 0f, "zero green transmittance");
        assertEquals(0f, blocker.blue(), 0f, "zero blue transmittance");
        assertEquals(1, blocker.kind(), "still a glass kind so the camera treats it as a pane");
    }

    @Test
    void clearGlassKeepsTheTransmittingProfile() {
        OpticalMaterials optics = new OpticalMaterials();
        int[] material = {LabPbr.pack(2, 10, 0, 255)};
        optics.apply(material, sprite("block/glass"), emptyResources());
        int id = OpticalMaterials.profileId(material[0]);
        assertNotEquals(OpticalMaterials.TINTED_GLASS_PROFILE_ID, id,
            "clear glass transmits light and must not share the blocker profile");
        OpticalMaterials.Profile clear = optics.snapshot().get(id);
        assertTrue(clear.red() > 0f && clear.green() > 0f && clear.blue() > 0f,
            "clear glass keeps a positive transmittance");
    }

    @Test
    void theBlockerIdRoundTripsThroughTheSourceByte() {
        int base = LabPbr.pack(2, 10, 0, 255);
        int tagged = LabPbr.withSourceFlag(base, OpticalMaterials.TINTED_GLASS_PROFILE_ID << OpticalMaterials.SHIFT);
        assertEquals(OpticalMaterials.TINTED_GLASS_PROFILE_ID, OpticalMaterials.profileId(tagged),
            "the id the baker writes into the high source bits is the id the shader decodes");
    }
}