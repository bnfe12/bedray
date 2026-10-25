package io.github.andrexarlay.bedray.api.v1;

/** Requested loaded-world domain and enforced upload/memory budgets. Section Y bounds are inclusive. */
public record WorldCoverageBounds(int centerSectionX, int centerSectionZ,
        int requestedRenderDistanceChunks, int effectiveRenderDistanceChunks,
        int minSectionY, int maxSectionY, long budgetGpuBytes, int uploadBudgetBytes) {
    public WorldCoverageBounds {
        if (requestedRenderDistanceChunks < 1 || effectiveRenderDistanceChunks < 1
                || effectiveRenderDistanceChunks > requestedRenderDistanceChunks
                || effectiveRenderDistanceChunks > 64 || minSectionY > maxSectionY
                || (long) maxSectionY - minSectionY > 255 || budgetGpuBytes <= 0 || uploadBudgetBytes <= 0)
            throw new IllegalArgumentException("Invalid world streaming bounds");
        Math.subtractExact(centerSectionX, effectiveRenderDistanceChunks);
        Math.addExact(centerSectionX, effectiveRenderDistanceChunks);
        Math.subtractExact(centerSectionZ, effectiveRenderDistanceChunks);
        Math.addExact(centerSectionZ, effectiveRenderDistanceChunks);
    }
    public int minSectionX() { return centerSectionX - effectiveRenderDistanceChunks; }
    public int maxSectionX() { return centerSectionX + effectiveRenderDistanceChunks; }
    public int minSectionZ() { return centerSectionZ - effectiveRenderDistanceChunks; }
    public int maxSectionZ() { return centerSectionZ + effectiveRenderDistanceChunks; }
    public boolean contains(SectionKey key) {
        return key.x() >= minSectionX() && key.x() <= maxSectionX()
            && key.y() >= minSectionY && key.y() <= maxSectionY
            && key.z() >= minSectionZ() && key.z() <= maxSectionZ();
    }
}
