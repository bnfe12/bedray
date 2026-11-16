package io.github.andrexarlay.bedray.api.v1.light;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightProvidersTest {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final CameraSnapshot CAMERA = new CameraSnapshot(0, 64, 0, IDENTITY, IDENTITY);

    @AfterEach
    void reset() {
        LightProviders.clear();
    }

    @Test
    void invalidLightsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PointLight(Double.NaN, 0, 0, 1, 1, 1, 8));
        assertThrows(IllegalArgumentException.class, () -> new PointLight(0, 0, 0, -1, 1, 1, 8));
        assertThrows(IllegalArgumentException.class, () -> new PointLight(0, 0, 0, 1, 1, 1, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new PointLight(0, 0, 0, 1, 1, 1, 65));
        assertDoesNotThrow(() -> new PointLight(1, 2, 3, 1, 0.5f, 0, 16));
    }

    @Test
    void registrationRequiresAWellFormedUniqueId() {
        LightProvider none = c -> List.of();
        assertThrows(IllegalArgumentException.class, () -> LightProviders.register("Bad Id", none));
        LightProviders.register("example", none);
        assertThrows(IllegalStateException.class, () -> LightProviders.register("example", none));
        assertTrue(LightProviders.unregister("example"));
    }

    @Test
    void perProviderAndTotalCapsApply() {
        LightProviders.register("a", c -> many(200));
        LightProviders.register("b", c -> many(200));
        LightProviders.register("c", c -> many(200));
        LightProviders.register("d", c -> many(200));
        LightProviders.register("e", c -> many(200));
        List<PointLight> lights = LightProviders.collect(CAMERA);
        assertEquals(LightProviders.MAX_TOTAL, lights.size());
        assertEquals(LightProviders.MAX_PER_PROVIDER, LightProviders.status().get(0).lastAccepted());
        assertEquals(200 - LightProviders.MAX_PER_PROVIDER, LightProviders.status().get(0).lastDropped());
    }

    @Test
    void aThrowingProviderIsIsolatedThenDisabled() {
        LightProviders.register("broken", c -> {
            throw new IllegalStateException("boom");
        });
        LightProviders.register("fine", c -> List.of(new PointLight(0, 0, 0, 1, 1, 1, 8)));
        for (int i = 0; i < LightProviders.MAX_CONSECUTIVE_FAILURES; i++) {
            assertEquals(1, LightProviders.collect(CAMERA).size(), "the healthy provider still contributes");
        }
        assertTrue(LightProviders.status().get(0).disabled());
        assertFalse(LightProviders.status().get(1).disabled());
    }

    @Test
    void thisBuildDoesNotClaimTheHostAppliesTheLights() {
        assertFalse(LightProviders.appliedByHost());
    }

    private static List<PointLight> many(int n) {
        List<PointLight> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new PointLight(i, 0, 0, 1, 1, 1, 8));
        }
        return list;
    }
}
