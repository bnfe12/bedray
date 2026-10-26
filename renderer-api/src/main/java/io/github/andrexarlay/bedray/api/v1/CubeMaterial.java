package io.github.andrexarlay.bedray.api.v1;

import java.util.List;

/** Six proven full boundary faces, ordered DOWN, UP, NORTH, SOUTH, WEST, EAST.
 * Layered/cutout/partial geometry must not be represented by this type.
 */
public record CubeMaterial(List<CubeFaceMaterial> faces) {
    public static final int FACE_COUNT = 6;
    public CubeMaterial {
        faces = List.copyOf(faces);
        if (faces.size() != FACE_COUNT) throw new IllegalArgumentException("Expected six cube faces");
    }
    public CubeFaceMaterial face(int index) { return faces.get(index); }
}
