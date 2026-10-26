package io.github.andrexarlay.bedray.api.v1;

/** Absolute section coordinates. Block coordinates use floor division by sixteen. */
public record SectionKey(int x, int y, int z) {}
