package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.poc.RtTestPipeline;
import io.github.andrexarlay.vulkancompat.rt.poc.SimpleAccelerationStructureBuilder;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameParams;
import io.github.andrexarlay.vulkancompat.rt.world.RtPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Offscreen RT dispatch + readback on a real ray-query device (L2). Fails rather than skips if no RT device. */
@EnabledIfSystemProperty(named = "bedray.rt.rtProbe", matches = "true")
class RtBaselineProbeTest {
    private static final int WIDTH = 320, HEIGHT = 240;

    @Test void rtTestRendersStableTriangleHit() throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             SimpleAccelerationStructureBuilder as = new SimpleAccelerationStructureBuilder(probe.device);
             RtTestPipeline pipeline = new RtTestPipeline(probe.device)) {
            byte[] first = probe.render(cmd -> {
                as.buildAll(cmd);
                pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT);
            });
            byte[] second = probe.render(cmd -> pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT));
            int greenHits = 0;
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                int r = Byte.toUnsignedInt(first[i * 4]), g = Byte.toUnsignedInt(first[i * 4 + 1]), b = Byte.toUnsignedInt(first[i * 4 + 2]);
                if (g > 180 && r < 80 && b < 120) greenHits++;
            }
            assertTrue(greenHits > 100, "rt_test must hit the hardware triangle: greenHits=" + greenHits);
            assertEquals(hash(first), hash(second), "rt_test baseline must be deterministic across renders");
            System.out.printf("[BEDRAY-RT-PROBE] device=%s rt_test greenHits=%d hash=%s%n",
                probe.deviceName, greenHits, hash(first));
        }
    }

    @Test void rtRaysRendersStableScene() throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             RtBaselineScene scene = new RtBaselineScene(probe)) {
            RtPipeline pipeline = new RtPipeline(probe.device, false);
            try {
                RtPipeline.Views views = new RtPipeline.Views(scene.tlas, scene.table, scene.outputView,
                    scene.atlasView, scene.materialView, scene.normalView, 0L, null, scene.depthView, scene.lightmapView);
                pipeline.bind(views);
                byte[] first = probe.render(cmd -> {
                    scene.build(cmd);
                    pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT);
                });
                byte[] second = probe.render(cmd -> {
                    scene.build(cmd);
                    pipeline.dispatch(cmd, scene.params(1, 0), WIDTH, HEIGHT);
                });
                int hits = 0;
                for (int i = 0; i < WIDTH * HEIGHT; i++) {
                    int r = Byte.toUnsignedInt(first[i * 4]), g = Byte.toUnsignedInt(first[i * 4 + 1]), b = Byte.toUnsignedInt(first[i * 4 + 2]);
                    if (r + g + b > 24) hits++;
                }
                assertTrue(hits > WIDTH * HEIGHT / 10, "rt_rays camera must see the scene: hits=" + hits);
                // frame differs (water/time noise) so compare a re-render at the SAME frame for determinism.
                byte[] again = probe.render(cmd -> {
                    scene.build(cmd);
                    pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT);
                });
                assertEquals(hash(first), hash(again), "rt_rays baseline must be deterministic at a fixed frame");
                System.out.printf("[BEDRAY-RT-PROBE] device=%s rt_rays hits=%d hash=%s%n", probe.deviceName, hits, hash(first));
            } finally {
                pipeline.destroy();
            }
        }
    }

    static String hash(byte[] rgba) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba));
    }
}