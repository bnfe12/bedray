package io.github.andrexarlay.vulkancompat.rt.world;

/** Dimensions and scheduled primary samples of an accepted trace recording, independent of later resizes/settings. */
record RtTraceTelemetry(int traceWidth, int traceHeight, int outputWidth, int outputHeight, int spp, int bounces) {
    RtTraceTelemetry {
        if (traceWidth <= 0 || traceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0 || spp <= 0 || bounces < 0)
            throw new IllegalArgumentException("Invalid trace telemetry extent or sampling settings");
    }

    long tracePixels() { return (long) traceWidth * traceHeight; }
    long outputPixels() { return (long) outputWidth * outputHeight; }
    /** Scheduled primary samples only: secondary, shadow and visibility ray queries are not counted here. */
    long primarySamples() { return Math.multiplyExact(tracePixels(), spp); }
    String traceExtent() { return traceWidth + "x" + traceHeight; }
    String outputExtent() { return outputWidth + "x" + outputHeight; }
}
