package io.github.andrexarlay.bedray.compute;

/** Test-only bridge to the package-private history math. */
public final class TemporalHistoryAccess {
    private TemporalHistoryAccess() {}
    public static boolean invert(float[] matrix, float[] out) { return TemporalHistory.invert(matrix, out); }
}
