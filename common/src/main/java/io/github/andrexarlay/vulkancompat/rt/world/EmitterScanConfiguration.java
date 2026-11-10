package io.github.andrexarlay.vulkancompat.rt.world;

/** Atlas identity and selection settings belong to the immutable inputs of a multi-frame section scan. */
record EmitterScanConfiguration(Object materials, float range, int budget) {
    boolean matches(Object nextMaterials, float nextRange, int nextBudget) {
        return materials == nextMaterials && range == nextRange && budget == nextBudget;
    }
}
