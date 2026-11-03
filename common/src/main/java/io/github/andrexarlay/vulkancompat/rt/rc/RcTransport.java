package io.github.andrexarlay.vulkancompat.rt.rc;

import java.util.List;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry.*;

/** CPU optical-operator algebra. Confidence describes evidence and never scales transport or quadrature weights. */
public final class RcTransport {
    private RcTransport() {}

    public record Segment(Vec3 radiance, Vec3 transmission, boolean valid, double confidence, boolean terminalKnown) {
        public boolean opaque() { return transmission != null && transmission.x() == 0 && transmission.y() == 0 && transmission.z() == 0; }
    }
    public record WeightedBranch(double weight, Segment front, Segment back) {}
    public record WeightedSample(double weight, Segment sample) {}
    public record Irradiance(Vec3 value, boolean valid, double confidence, boolean terminalKnown) {}

    public static Segment invalid() { return new Segment(ZERO, ONE, false, 0, false); }
    public static Segment finiteMiss() { return new Segment(ZERO, ONE, true, 1, false); }
    public static Segment interval(Vec3 radiance, Vec3 transmission, double confidence) {
        Segment result = new Segment(radiance, transmission, true, confidence, false);
        return wellFormed(result) ? new Segment(radiance, transmission, true, confidence, result.opaque()) : invalid();
    }

    public static Segment compose(Segment front, Segment back) {
        if (!wellFormed(front)) return invalid();
        if (front.opaque()) return new Segment(front.radiance, ZERO, true, front.confidence, true);
        // A proven environment endpoint cannot be followed by another physical interval.
        if (front.terminalKnown || !wellFormed(back)) return invalid();
        Vec3 radiance = front.radiance.add(front.transmission.multiply(back.radiance));
        Vec3 transmission = front.transmission.multiply(back.transmission);
        boolean known = back.terminalKnown || transmission.x() == 0 && transmission.y() == 0 && transmission.z() == 0;
        Segment result = new Segment(radiance, transmission, true, Math.min(front.confidence, back.confidence), known);
        return wellFormed(result) ? result : invalid();
    }

    /** Compose each correlated pair first; every positive-weight branch is required before normalization. */
    public static Segment resolve(List<WeightedBranch> branches) {
        return average(branches.stream().map(b -> new WeightedSample(b.weight, compose(b.front, b.back))).toList());
    }

    public static Segment average(List<WeightedSample> samples) {
        double sum = 0, confidence = 1;
        Vec3 radiance = ZERO, transmission = ZERO;
        boolean known = true;
        for (WeightedSample weighted : samples) {
            double weight = weighted.weight;
            if (!Double.isFinite(weight) || weight < 0) return invalid();
            if (weight == 0) continue;
            Segment sample = weighted.sample;
            if (!wellFormed(sample)) return invalid();
            sum += weight;
            radiance = radiance.add(sample.radiance.scale(weight));
            transmission = transmission.add(sample.transmission.scale(weight));
            confidence = Math.min(confidence, sample.confidence);
            known &= sample.terminalKnown || sample.opaque();
        }
        if (!Double.isFinite(sum) || Math.abs(sum - 1) > 1e-12) return invalid();
        Segment result = new Segment(radiance.scale(1 / sum), transmission.scale(1 / sum), true, confidence, known);
        return wellFormed(result) ? result : invalid();
    }

    /** Geometric weights already integrate cosine and solid angle; do not normalize them into a radiance average. */
    public static Irradiance integrate(List<WeightedSample> samples) {
        Vec3 energy = ZERO;
        double confidence = 1, sum = 0;
        boolean known = true;
        for (WeightedSample weighted : samples) {
            if (!Double.isFinite(weighted.weight) || weighted.weight < 0) return new Irradiance(ZERO, false, 0, false);
            if (weighted.weight == 0) continue;
            if (!wellFormed(weighted.sample)) return new Irradiance(ZERO, false, 0, false);
            sum += weighted.weight;
            energy = energy.add(weighted.sample.radiance.scale(weighted.weight));
            confidence = Math.min(confidence, weighted.sample.confidence);
            known &= weighted.sample.terminalKnown || weighted.sample.opaque();
        }
        return energy.finite() && Double.isFinite(sum) && sum > 0
            ? new Irradiance(energy, true, confidence, known) : new Irradiance(ZERO, false, 0, false);
    }

    private static boolean wellFormed(Segment sample) {
        return sample != null && sample.valid && sample.radiance != null && sample.transmission != null
            && sample.radiance.finite() && sample.transmission.finite()
            && sample.radiance.x() >= 0 && sample.radiance.y() >= 0 && sample.radiance.z() >= 0
            && sample.transmission.x() >= 0 && sample.transmission.y() >= 0 && sample.transmission.z() >= 0
            && sample.transmission.x() <= 1 && sample.transmission.y() <= 1 && sample.transmission.z() <= 1
            && Double.isFinite(sample.confidence) && sample.confidence >= 0 && sample.confidence <= 1;
    }
}
