package io.github.andrexarlay.vulkancompat.rt.world;

/** Recoverable RR failure only after the whole, never-submitted host recording has been discarded. */
final class RtDenoiserFrameDiscard extends RuntimeException {
    RtDenoiserFrameDiscard(String message, Throwable cause) { super(message, cause); }
}
