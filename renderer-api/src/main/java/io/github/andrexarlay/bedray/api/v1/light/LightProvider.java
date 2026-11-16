package io.github.andrexarlay.bedray.api.v1.light;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;

import java.util.List;

/**
 * Supplies extra lights once per rendered frame, on the render thread. It must return quickly, must not retain the camera
 * and must not touch game state it does not own. Returning null or an empty list is fine. A provider that throws is skipped
 * for that frame and disabled after repeated failures (see {@link LightProviders#status()}).
 */
@FunctionalInterface
public interface LightProvider {
    List<PointLight> lights(CameraSnapshot camera);
}
