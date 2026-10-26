package io.github.andrexarlay.bedray.api.v1;

import java.util.List;

/** One immutable, tinted cell appearance with actual baked geometry, never collision-box proxies. */
public record BlockModelGeometry(List<ModelQuad> quads) {
    public static final int MAX_QUADS = 64;
    public BlockModelGeometry {
        quads = List.copyOf(quads);
        if (quads.isEmpty() || quads.size() > MAX_QUADS)
            throw new IllegalArgumentException("A model requires 1..64 quads");
    }
}
