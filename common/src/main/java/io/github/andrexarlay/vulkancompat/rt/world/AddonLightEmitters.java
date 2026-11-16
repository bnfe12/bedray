package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.bedray.api.v1.light.PointLight;

import java.util.List;

/**
 * Turns the point lights add-ons supplied through {@code LightProviders} into emitter records of the same 24-float layout the
 * entity emitters use (positions relative to the camera). A light is a small two-sided quad textured with slot 0 of the entity
 * atlas, which is one fully emissive white texel, and coloured through the tint. Intensity is the largest colour component
 * times {@code (radius / 8)^2} of a reference power of 0.6 (one torch, see {@link RtEmitters}); the emitter falloff itself is
 * global, so the radius is a brightness scale and the distance the host stops considering the light, not a hard cut-off shape.
 * These numbers are a first calibration and are expected to be tuned against the picture.
 */
public final class AddonLightEmitters {
    /** Side of the light quad in blocks; small enough to read as a point. */
    static final float SIDE = 0.2f;
    /** Luminous power of a reference light (colour component 1, radius 8): one torch. */
    static final float REFERENCE_POWER = 0.6f;
    static final float MAX_BOOST = 8192.0f;

    private AddonLightEmitters() {
    }

    /**
     * Builds records for the lights that are within {@code maxRange + light radius} of the camera. Returns an array whose length is
     * a multiple of {@link RtEmitters#FLOATS}; empty when nothing qualifies.
     */
    public static float[] records(List<PointLight> lights, double camX, double camY, double camZ, float maxRange) {
        if (lights == null || lights.isEmpty()) {
            return new float[0];
        }
        float[] out = new float[lights.size() * RtEmitters.FLOATS];
        int n = 0;
        for (PointLight light : lights) {
            float peak = Math.max(light.red(), Math.max(light.green(), light.blue()));
            if (peak <= 0.0f) {
                continue;
            }
            double dx = light.x() - camX;
            double dy = light.y() - camY;
            double dz = light.z() - camZ;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > maxRange + light.radiusBlocks()) {
                continue;
            }
            write(out, n * RtEmitters.FLOATS, light, peak, (float) dx, (float) dy, (float) dz);
            n++;
        }
        return n == out.length / RtEmitters.FLOATS ? out : java.util.Arrays.copyOf(out, n * RtEmitters.FLOATS);
    }

    private static void write(float[] r, int o, PointLight light, float peak, float cx, float cy, float cz) {
        float half = SIDE * 0.5f;
        float area = SIDE * SIDE;
        float scale = light.radiusBlocks() / 8.0f;
        float boost = Math.min(MAX_BOOST, Math.max(1.0f, REFERENCE_POWER * peak * scale * scale / area));
        r[o] = cx - half;
        r[o + 1] = cy;
        r[o + 2] = cz - half;
        r[o + 3] = area;
        r[o + 4] = SIDE;
        r[o + 5] = 0.0f;
        r[o + 6] = 0.0f;
        r[o + 7] = boost;
        r[o + 8] = 0.0f;
        r[o + 9] = 0.0f;
        r[o + 10] = SIDE;
        r[o + 11] = 1.0f;          // two-sided
        r[o + 12] = 0.0f;          // uv corner inside the 1x1 texel
        r[o + 13] = 0.0f;
        r[o + 14] = 1.0f;          // mean emission of the white texel
        r[o + 15] = 1.0f;          // entity atlas slot 0, plus one
        r[o + 16] = 1.0f;
        r[o + 17] = 0.0f;
        r[o + 18] = 0.0f;
        r[o + 19] = 1.0f;
        int tint = RtEmitters.TINT_OFFSET;
        r[tint] = light.red() / peak;
        r[tint + 1] = light.green() / peak;
        r[tint + 2] = light.blue() / peak;
        r[tint + 3] = 1.0f;
    }

    /**
     * The extra emitter records of this frame: empty unless the add-on light switch is on and a provider is registered. Builds the
     * camera snapshot the providers see from this frame matrices, collects, converts and tells the API whether lights were applied.
     */
    public static float[] forFrame(org.joml.Matrix4f projection, org.joml.Matrix4f viewRotation, double camX, double camY,
                                   double camZ, float maxRange) {
        boolean wanted = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.addonLights()
            && io.github.andrexarlay.bedray.api.v1.light.LightProviders.hasProviders();
        if (!wanted) {
            io.github.andrexarlay.bedray.api.v1.light.LightProviders.markApplied(false);
            return new float[0];
        }
        float[] inverseProjection = new float[16];
        float[] inverseView = new float[16];
        new org.joml.Matrix4f(projection).invert().get(inverseProjection);
        new org.joml.Matrix4f(viewRotation).invert().get(inverseView);
        float[] records;
        try {
            io.github.andrexarlay.bedray.api.v1.CameraSnapshot snapshot =
                new io.github.andrexarlay.bedray.api.v1.CameraSnapshot(camX, camY, camZ, inverseProjection, inverseView);
            records = records(io.github.andrexarlay.bedray.api.v1.light.LightProviders.collect(snapshot), camX, camY, camZ, maxRange);
        } catch (RuntimeException e) {
            records = new float[0];
        }
        io.github.andrexarlay.bedray.api.v1.light.LightProviders.markApplied(records.length > 0);
        return records;
    }

    /** Concatenates two record arrays; returns {@code first} unchanged when {@code second} is empty. */
    public static float[] concat(float[] first, float[] second) {
        if (second.length == 0) {
            return first;
        }
        float[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
