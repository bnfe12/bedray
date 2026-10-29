package io.github.andrexarlay.vulkancompat.rt.rc;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry.*;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcTransport.*;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometryTest.vectorEquals;
import static org.junit.jupiter.api.Assertions.*;

class RcTransportTest {
    @Test void coloredCompositionRetainsEvidenceAndExactOpaqueFrontNeedsNoTail() {
        Segment front = interval(new Vec3(1, 2, 3), new Vec3(0.1, 0.3, 0.8), 0.9);
        Segment back = interval(new Vec3(10, 20, 30), new Vec3(0.5, 0.4, 0.25), 0.2);
        Segment result = compose(front, back);
        vectorEquals(new Vec3(2, 8, 27), result.radiance(), 1e-14);
        vectorEquals(new Vec3(0.05, 0.12, 0.2), result.transmission(), 1e-15);
        assertEquals(0.2, result.confidence()); assertFalse(result.terminalKnown());
        Segment opaque = interval(new Vec3(3, 2, 1), ZERO, 0.7);
        assertTrue(compose(opaque, invalid()).valid()); assertTrue(compose(opaque, invalid()).terminalKnown());
        assertEquals(0.7, compose(opaque, invalid()).confidence());
        assertFalse(compose(interval(ZERO, new Vec3(Double.MIN_VALUE, 0, 0), 1), invalid()).valid());
        assertFalse(compose(invalid(), opaque).valid());
        assertEquals(front, compose(finiteMiss(), front));
        assertEquals(front, compose(front, finiteMiss()));
    }

    @Test void composeBeforeAveragePreservesOcclusionCorrelationAndNeverWeightsByConfidence() {
        Segment blocker = interval(ZERO, ZERO, 0.1), vacuum = finiteMiss();
        Segment light = interval(new Vec3(100, 100, 100), ONE, 1), dark = interval(ZERO, ONE, 1);
        Segment result = resolve(List.of(new WeightedBranch(0.5, blocker, light), new WeightedBranch(0.5, vacuum, dark)));
        vectorEquals(ZERO, result.radiance(), 0); assertEquals(0.1, result.confidence());
        Segment wrong = compose(average(List.of(new WeightedSample(0.5, blocker), new WeightedSample(0.5, vacuum))),
            average(List.of(new WeightedSample(0.5, light), new WeightedSample(0.5, dark))));
        assertEquals(25, wrong.radiance().x(), 0);
        Segment constant = interval(new Vec3(2, 3, 4), ONE, 0.01);
        var weighted = average(List.of(new WeightedSample(1.0 / 32, constant), new WeightedSample(31.0 / 32, constant)));
        vectorEquals(constant.radiance(), weighted.radiance(), 1e-15);
        assertEquals(0.01, weighted.confidence());
    }

    @Test void requiredMissingBranchInvalidatesRatherThanRenormalizesAndFiniteMissNeverAddsSky() {
        assertFalse(average(List.of(new WeightedSample(1, finiteMiss()), new WeightedSample(1e-100, invalid()))).valid());
        assertTrue(average(List.of(new WeightedSample(1, finiteMiss()), new WeightedSample(0, invalid()))).valid());
        assertFalse(average(List.of(new WeightedSample(0, finiteMiss()))).valid());
        assertFalse(average(List.of(new WeightedSample(0.999, finiteMiss()))).valid(), "missing quadrature weight cannot be renormalized away");
        assertFalse(average(List.of(new WeightedSample(-1, finiteMiss()))).valid());
        assertFalse(average(List.of(new WeightedSample(Double.NaN, finiteMiss()))).valid());
        assertFalse(compose(new Segment(new Vec3(Double.NaN, 0, 0), ONE, true, 1, false), finiteMiss()).valid());
        Segment huge = interval(new Vec3(Double.MAX_VALUE, 0, 0), ONE, 1);
        assertFalse(compose(huge, huge).valid());
        assertFalse(compose(new Segment(ONE, ONE, true, 1, true), finiteMiss()).valid(), "proven environment endpoint has no interval behind it");
        Segment terminal = finiteMiss();
        assertTrue(terminal.valid()); assertFalse(terminal.terminalKnown()); vectorEquals(ZERO, terminal.radiance(), 0);
        assertFalse(average(List.of(new WeightedSample(0.5, interval(ONE, ZERO, 1)), new WeightedSample(0.5, terminal))).terminalKnown());
        assertFalse(integrate(List.of()).valid());
    }

    @Test void sameRayFiveWindowsMatchIndependentWholeRayAtEveryJoinAndFloatNeighbor() {
        int scenes = 0;
        for (float join : new float[] {8, 16, 32, 64}) for (float position : new float[] {Math.nextDown(join), join, Math.nextUp(join)}) {
            for (Vec3 transmission : List.of(new Vec3(0.8, 0.4, 0.2), ZERO)) {
                List<Event> events = new ArrayList<>(List.of(
                    new Event(4, new Vec3(0.1, 0.2, 0.3), new Vec3(0.9, 0.8, 0.7)),
                    new Event(position, new Vec3(2, 3, 4), transmission),
                    new Event(100, new Vec3(5, 6, 7), ONE),
                    new Event(128, new Vec3(1e6, 1e6, 1e6), ZERO)));
                events.sort(Comparator.comparingDouble(Event::position));
                Vec3 expectedL = ZERO, expectedT = ONE;
                int expectedEvents = 0;
                for (Event event : events) if (event.position >= 4 && event.position < 128) {
                    expectedL = expectedL.add(expectedT.multiply(event.radiance));
                    expectedT = expectedT.multiply(event.transmission); expectedEvents++;
                }
                Segment composed = finiteMiss(); int[] reads = {0};
                for (int level = 0; level < LEVELS; level++) {
                    float start = (float) near(level), end = (float) far(level);
                    Bridge interval = bridge(new Vec3(start, 0, 0), new Vec3(end, 0, 0), new Vec3(1, 0, 0)).orElseThrow();
                    Segment part = finiteMiss();
                    for (Event event : events) if (interval.contains(event.position - start)) {
                        part = compose(part, interval(event.radiance, event.transmission, 1)); reads[0]++;
                    }
                    composed = compose(composed, part);
                }
                assertEquals(expectedEvents, reads[0], "endpoint event is assigned exactly once, even behind an opaque event");
                vectorEquals(expectedL, composed.radiance(), 3e-15); vectorEquals(expectedT, composed.transmission(), 3e-15);
                scenes++;
            }
        }
        assertEquals(24, scenes);
    }

    private record Event(float position, Vec3 radiance, Vec3 transmission) {}
}
