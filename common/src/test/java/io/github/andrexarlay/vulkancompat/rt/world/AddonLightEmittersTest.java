package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.bedray.api.v1.light.PointLight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonLightEmittersTest {
    @Test
    void aLightBecomesOneTwoSidedWhiteTexelRecordRelativeToTheCamera() {
        PointLight light = new PointLight(110.0, 70.0, -3.0, 1.0f, 0.5f, 0.0f, 8.0f);
        float[] r = AddonLightEmitters.records(List.of(light), 100.0, 64.0, 0.0, 128.0f);
        assertEquals(RtEmitters.FLOATS, r.length);
        assertEquals(10.0f - AddonLightEmitters.SIDE / 2, r[0], 1e-4);
        assertEquals(6.0f, r[1], 1e-4);
        assertEquals(-3.0f - AddonLightEmitters.SIDE / 2, r[2], 1e-4);
        assertEquals(AddonLightEmitters.SIDE * AddonLightEmitters.SIDE, r[3], 1e-6);
        assertEquals(1.0f, r[11], "two-sided");
        assertEquals(1.0f, r[15], "entity atlas slot 0, plus one");
        assertEquals(1.0f, r[RtEmitters.TINT_OFFSET], 1e-6);
        assertEquals(0.5f, r[RtEmitters.TINT_OFFSET + 1], 1e-6);
        assertEquals(0.0f, r[RtEmitters.TINT_OFFSET + 2], 1e-6);
    }

    @Test
    void brighterAndWiderLightsGetMorePower() {
        float[] dim = AddonLightEmitters.records(List.of(new PointLight(0, 0, 0, 1, 1, 1, 8)), 0, 0, 0, 64);
        float[] strong = AddonLightEmitters.records(List.of(new PointLight(0, 0, 0, 4, 4, 4, 8)), 0, 0, 0, 64);
        float[] wide = AddonLightEmitters.records(List.of(new PointLight(0, 0, 0, 1, 1, 1, 16)), 0, 0, 0, 64);
        assertTrue(strong[7] > dim[7]);
        assertTrue(wide[7] > dim[7]);
        assertEquals(dim[7] * 4, wide[7], dim[7] * 1e-3);
    }

    @Test
    void blackAndDistantLightsAreDropped() {
        PointLight black = new PointLight(0, 0, 0, 0, 0, 0, 8);
        PointLight far = new PointLight(1000, 0, 0, 1, 1, 1, 8);
        assertEquals(0, AddonLightEmitters.records(List.of(black, far), 0, 0, 0, 128).length);
        assertEquals(RtEmitters.FLOATS, AddonLightEmitters.records(List.of(black, new PointLight(5, 0, 0, 1, 1, 1, 8)), 0, 0, 0, 128).length);
    }

    @Test
    void concatKeepsTheFirstArrayWhenThereIsNothingToAdd() {
        float[] first = new float[RtEmitters.FLOATS];
        assertEquals(first, AddonLightEmitters.concat(first, new float[0]));
        assertEquals(2 * RtEmitters.FLOATS, AddonLightEmitters.concat(first, new float[RtEmitters.FLOATS]).length);
    }
}
