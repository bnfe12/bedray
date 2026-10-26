package io.github.andrexarlay.vulkancompat.rt.particles;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the weather layer decides with the state the vanilla renderer showed it: one draw set per frame (the game repeats the same
 * columns for every improved-transparency stage), the index ranges of the vanilla upload, no draw from an incomplete capture, and
 * the settings that switch it on or off.
 * <p>
 * The GPU work itself (the pass over {@code bedray_rt_particles} and the composite) needs a live device and is verified in game;
 * the buffers here are proxies, only their identity matters.
 */
class RtWeatherLayerTest {
    private boolean oldParticles;
    private boolean oldWeather;
    private RtMode oldMode;

    @BeforeEach
    void rememberSettingsAndStartAColdLayer() {
        oldParticles = RtSettings.rtParticles;
        oldWeather = RtSettings.rtWeather;
        oldMode = VulkanRtxConfig.rtMode;
        RtGeometryGate.clearFailure();
        RtParticleLayer.clearFailure();
        RtWeatherLayer.resetForTests();
    }

    @AfterEach
    void restoreSettings() {
        RtSettings.rtParticles = oldParticles;
        RtSettings.rtWeather = oldWeather;
        VulkanRtxConfig.rtMode = oldMode;
        RtGeometryGate.setForTests(false);
        RtParticleLayer.clearFailure();
        RtWeatherLayer.resetForTests();
    }

    // ---- the ledger: which visit of the frame counts ----

    @Test
    void theFirstVisitOfTheFrameWinsAndTheTransparencyStagesAreDropped() {
        WeatherVisitLedger ledger = new WeatherVisitLedger();
        assertTrue(ledger.accept(240, 0));
        // depth bounds / transmittance / accumulate all call the same render with the same columns
        assertFalse(ledger.accept(240, 0));
        assertFalse(ledger.accept(240, 0));
        // a later stage is never allowed to replace what the frame already has
        assertFalse(ledger.accept(120, 90));
        assertEquals(240, ledger.columns());
        assertTrue(ledger.visited());
    }

    @Test
    void columnsAreIndexedTheWayTheGameUploadedThem() {
        WeatherVisitLedger ledger = new WeatherVisitLedger();
        assertTrue(ledger.accept(7, 5));
        assertEquals(42, ledger.rainIndexCount(), "7 rain columns of 6 indices, starting at 0");
        assertEquals(30, ledger.snowIndexCount());
        assertEquals(42, ledger.snowFirstIndex(), "snow starts behind every rain column");
        assertEquals(72, ledger.requiredIndices());
        assertEquals(12, ledger.columns());
    }

    @Test
    void aVisitWithoutColumnsRecordsNothingAndNegativesAreRejected() {
        WeatherVisitLedger ledger = new WeatherVisitLedger();
        assertFalse(ledger.accept(0, 0), "fair weather: vanilla returns before drawing, so the layer must not open a pass");
        assertFalse(ledger.visited());
        assertFalse(ledger.accept(-1, 4));
        assertFalse(ledger.accept(4, -1));
        assertFalse(ledger.visited(), "a rejected visit must not consume the frame");
        assertTrue(ledger.accept(4, 0));
    }

    @Test
    void clearForgetsTheFrame() {
        WeatherVisitLedger ledger = new WeatherVisitLedger();
        assertTrue(ledger.accept(10, 0));
        ledger.clear();
        assertFalse(ledger.visited());
        assertEquals(0, ledger.columns());
        assertTrue(ledger.accept(0, 8), "the next frame starts empty, rain included");
        assertEquals(8, ledger.columns());
        assertEquals(0, ledger.snowFirstIndex(), "a frame with snow only starts at index 0");
    }

    // ---- the layer: capture state and the settings gate ----

    @Test
    void aVisitWithoutBuffersIsCountedButNeverDrawn() {
        RtWeatherLayer.beginFrame();
        RtWeatherLayer.capture(null, PrimitiveTopology.QUADS, null, 40, null, 10, null, null);
        assertFalse(RtWeatherLayer.hasDraws(), "half captured state must not reach the pass");
        assertTrue(RtWeatherLayer.telemetry().contains("columns=50"), RtWeatherLayer.telemetry());
        // the second visit of the same frame (an OIT stage) is dropped, so the counters stay as they were
        RtWeatherLayer.capture(null, PrimitiveTopology.QUADS, null, 40, null, 10, null, null);
        assertTrue(RtWeatherLayer.telemetry().contains("frames=1;"), RtWeatherLayer.telemetry());
        // a new frame may capture again
        RtWeatherLayer.beginFrame();
        RtWeatherLayer.capture(null, PrimitiveTopology.QUADS, null, 40, null, 10, null, null);
        assertTrue(RtWeatherLayer.telemetry().contains("frames=2;"), RtWeatherLayer.telemetry());
    }

    @Test
    void onlyCompleteStateCanBeDrawn() {
        GpuBufferSlice vertices = slice();
        GpuTextureView texture = proxy(GpuTextureView.class);
        GpuSampler sampler = proxy(GpuSampler.class);
        RtWeatherLayer.Columns full =
            new RtWeatherLayer.Columns(vertices, PrimitiveTopology.QUADS, texture, sampler, texture, sampler, vertices, texture);
        assertTrue(full.complete(12, 4));
        assertTrue(full.complete(12, 0), "a missing snow texture does not stop the rain part");
        assertTrue(full.complete(0, 4));
        assertFalse(full.complete(0, 0), "an empty draw set is not worth a pass");
        RtWeatherLayer.Columns noSnowTexture =
            new RtWeatherLayer.Columns(vertices, PrimitiveTopology.QUADS, texture, sampler, null, sampler, vertices, texture);
        assertFalse(noSnowTexture.complete(12, 4), "snow columns without their texture would sample garbage");
        assertTrue(noSnowTexture.complete(12, 0));
        RtWeatherLayer.Columns noTransforms =
            new RtWeatherLayer.Columns(vertices, PrimitiveTopology.QUADS, texture, sampler, texture, sampler, null, texture);
        assertFalse(noTransforms.complete(12, 4), "without DynamicTransforms the pipeline reads an unbound uniform");
        RtWeatherLayer.Columns noVertices =
            new RtWeatherLayer.Columns(null, PrimitiveTopology.QUADS, texture, sampler, texture, sampler, vertices, texture);
        assertFalse(noVertices.complete(12, 4));
    }

    @Test
    void weatherIsOnWhenParticlesAreOnAndTurnsOffWithThem() {
        try {
            RtSettings.rtParticles = true;
            RtSettings.rtWeather = true;
            assertTrue(RtSettings.weather());
            RtSettings.rtWeather = false;
            assertFalse(RtSettings.weather(), "own switch off: no weather capture");
            RtSettings.rtWeather = true;
            RtSettings.rtParticles = false;
            assertFalse(RtSettings.weather(), "no particles means no layer for the columns to ride on");
            RtSettings.rtParticles = true;
            RtSettings.rtWeather = false;
            assertFalse(RtSettings.weather());
        } finally {
            RtSettings.rtParticles = true;
            RtSettings.rtWeather = true;
        }
    }

    @Test
    void capturingNeedsTheRayTracedImageAndNotOnlyTheSettings() {
        RtSettings.rtParticles = true;
        RtSettings.rtWeather = true;
        RtWeatherLayer.beginFrame();
        assertFalse(RtWeatherLayer.capturing(), "raster frame (RT not displayed): nothing is captured");
        VulkanRtxConfig.rtMode = RtMode.PATH_TRACED;
        RtGeometryGate.setForTests(true);
        assertTrue(RtWeatherLayer.capturing(), "RT displayed with both settings on: weather is captured");
        RtSettings.rtWeather = false;
        assertFalse(RtWeatherLayer.capturing());
        RtSettings.rtWeather = true;
        RtParticleLayer.clearFailure();
        RtWeatherLayer.captureFailed(new IllegalStateException("the capture of a broken frame"));
        assertFalse(RtWeatherLayer.capturing(), "a failure stops weather, the frame keeps rendering");
        RtWeatherLayer.clearFailure();
        assertTrue(RtWeatherLayer.capturing(), "switching RT on again forgets the failure");
    }

    private static GpuBufferSlice slice() {
        return new GpuBufferSlice(proxy(GpuBuffer.class), 0L, 4096L);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface) {
        InvocationHandler handler = (target, method, args) -> {
            Class<?> r = method.getReturnType();
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class) {
                return 0;
            }
            if (r == long.class) {
                return 0L;
            }
            if (r == float.class) {
                return 0f;
            }
            if (r == double.class) {
                return 0d;
            }
            return null;
        };
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
    }
}
