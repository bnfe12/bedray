package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code outDepth} contract (binding 12, {@code rt_depth}): the distance along the unit primary ray from the eye the
 * tracer started at - {@code FrameConstants.camera.origin}, the view-bob offset from the raw camera - to the first
 * accepted surface, {@code 1e6} on a miss. It is not linear view depth (DLSS-RR receives that as {@code outViewZ}) and
 * not hardware depth (the upscalers and frame generation read the raster depth copy).
 *
 * <p>path_display keeps a depth history of its own to validate reprojection. It used to store the ray distance from the
 * bobbed eye and compare it with {@code length(previous)}, a distance from the previous frame's raw camera: two different
 * origins, apart by the bob offset. While walking that offset (~0.05-0.1 block) exceeds the 2 % tolerance of every
 * surface nearer than a few blocks, so near walls lost their accumulation on every step. The history now stores the
 * distance from the raw camera, the same quantity the reprojection computes.
 */
class RtSlangDepthContractTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path DISPLAY = SHADERS.resolve("passes/path_display.slang");
    private static final Path ENTRY = SHADERS.resolve("pathtrace/path_trace_entry.slang");
    private static final double TOLERANCE = RtSlangPathPass.REPROJECTION_TOLERANCE;

    private static double length(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double[] minus(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    /** A surface 1.5 blocks ahead, the raw camera one step back last frame, the eye bobbing in both frames. */
    @Test
    void aWalkingEyeKeepsTheHistoryOfANearSurface() {
        double[] point = {0.3, 1.4, 1.5};            // world, relative to this frame's raw camera at the origin
        double[] previousCamera = {0.0, 0.0, -0.2};  // the raw camera one step back
        double[] previousBob = {0.03, 0.09, 0.0};
        double[] bob = {-0.03, 0.04, 0.0};

        // What the tracer wrote last frame, and what the display stored from it under each rule.
        double previousRay = length(minus(minus(point, previousCamera), previousBob));
        double storedOld = previousRay;
        double storedNew = length(minus(point, previousCamera));

        // This frame: the tracer's ray distance from the bobbed eye, then the display's reprojection.
        double hitDistance = length(minus(point, bob));
        double[] direction = minus(point, bob);
        for (int i = 0; i < 3; i++) direction[i] /= hitDistance;
        double[] position = {bob[0] + direction[0] * hitDistance, bob[1] + direction[1] * hitDistance,
            bob[2] + direction[2] * hitDistance};
        double[] cameraDelta = minus(new double[3], previousCamera);   // raw camera now minus raw camera then
        double previousDistance = length(new double[] {position[0] + cameraDelta[0], position[1] + cameraDelta[1],
            position[2] + cameraDelta[2]});
        double window = TOLERANCE * Math.max(previousDistance, 1.0);

        assertTrue(Math.abs(storedOld - previousDistance) > window,
            "the old history mixed origins: the bob offset alone breaks the 2 % window");
        assertEquals(storedNew, previousDistance, 1.0e-12, "the raw-camera history is exactly what reprojection computes");
        // The value the display now stores is length(origin + dir * rayDistance) of the frame that wrote it.
        assertEquals(length(position), length(point), 1.0e-12);
    }

    @Test
    void theDisplayStoresAndComparesRawCameraDistances() throws IOException {
        String display = read(DISPLAY);
        assertTrue(display.contains("float pathDisplayHistoryDistance(FrameConstants f, int2 p, float rayDistance) {"),
            "one conversion from the tracer's ray distance to the history's raw-camera distance");
        assertTrue(display.contains("return length(f.camera.origin.xyz + pathDisplayViewDirection(f, p) * rayDistance);"));
        assertTrue(display.contains("float nextDepth = pathDisplayHistoryDistance(frame, p, depth[p]);"),
            "what the next frame compares against is a raw-camera distance");
        assertTrue(display.contains("&& abs(oldDepth - pathDisplayHistoryDistance(frame, p, hitDistance))"),
            "the still-camera test compares the same quantity it stored");
        assertTrue(display.contains("float previousDistance = length(previous);"),
            "and the reprojection's expected distance is from the previous raw camera");
        assertFalse(display.contains("float nextDepth = depth[p];"), "the ray distance is never stored as history");
        String entry = read(ENTRY);
        assertTrue(entry.contains("outDepth[p] = (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u ? result.firstHitDistance : 1.0e6;"),
            "the tracer's side of the contract: ray distance from the eye, 1e6 on a miss");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
