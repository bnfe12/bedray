package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5: a section's geometry is split into one BLAS geometry per pass range, and the hit that comes back names a
 * range plus a primitive that is local to it. Two representations meet at that boundary — the index stream the native
 * scene builds and the vertex lookup the traversal does — and the defect was adding the range start twice, which
 * reads a vertex that belongs to a different quad. The fix is an agreement, so both halves are pinned here.
 *
 * <p>Ways this silently breaks: a later helper adds {@code range.firstQuad} again because the base it was handed
 * "looks absolute"; the BLAS range stops carrying {@code firstVertex} and a local index resolves onto the previous
 * quad; the producer switches to absolute indices, which the build range then offsets a second time; or the header
 * stops saying whether {@code firstQuad} counts quads or vertices, so {@code * 4u} and {@code * 6u} become guesses.
 */
class RtSlangSectionIndexTest {

    private static final String GEOMETRY = "src/main/resources/shaders/slang/contracts/geometry.slang";
    private static final Path SCENE_CPP = Path.of("../native/bedray_scene/src/acceleration_scene.cpp");
    private static final Path SCENE_HEADER = Path.of("../native/bedray_scene/include/bedray_scene.h");

    /** The one place the range start enters a vertex index. */
    @Test
    void theRangeOffsetIsAppliedExactlyOncePerVertexBase() {
        String base = block(read(GEOMETRY), "uint geometryVertexBase(GeometrySection section, "
            + "uint geometryIndex, uint primitive) {");
        assertEquals(1, count(base, "firstQuad"),
            "the range start is added once here and nowhere else; twice is the P0-5 defect");
        assertTrue(base.contains("return (range.firstQuad + (primitive >> 1u)) * 4u;"),
            "a primitive is one half of a quad, and a quad is four vertices");
    }

    /** Every reader downstream works from the base, which is what keeps the addition single. */
    @Test
    void theVertexReadersReuseTheBaseAndNeverReaddTheRange() {
        String triangles = block(read(GEOMETRY), "uint3 geometryTriangleVertices(GeometrySection section, "
            + "uint geometryIndex, uint primitive) {");
        assertEquals(0, count(triangles, "firstQuad"),
            "this helper must offset by nothing: the base it asks for is already range-relative");
        assertTrue(triangles.contains("uint base = geometryVertexBase(section, geometryIndex, primitive);"),
            "and it must get that base from the one helper that computes it");
        assertTrue(triangles.contains("return uint3(base + (second ? 2u : 0u),")
                && triangles.contains("base + (second ? 3u : 1u),")
                && triangles.contains("base + (second ? 0u : 2u));"),
            "the two triangles of a quad are (0,1,2) and (2,3,0) — the same template the producer writes");
    }

    /**
     * The build range is what makes the local indices legal, so its two offsets have to come from the same
     * {@code quad_start}: the index stream advances six entries per quad and the vertex stream four.
     */
    @Test
    void theBlasRangeOffsetsTheSameQuadStartTheShaderAdds() throws IOException {
        String scene = read(SCENE_CPP);
        assertTrue(Files.isRegularFile(SCENE_CPP), SCENE_CPP + ": the native range wiring must stay readable");
        int quadStream = scene.indexOf("if (view.quad_stream) {");
        assertTrue(quadStream >= 0, "the quad-stream branch is what the section geometry takes");
        String branch = scene.substring(quadStream, quadStream + 600);
        assertTrue(branch.contains("range.firstVertex = view.quad_start[geometry] * 4u;"),
            "firstVertex offsets the vertex stream by the range's first quad, so a local index stays local");
        assertTrue(branch.contains("range.primitiveOffset = view.quad_start[geometry] * 6u * blas_index_bytes(view);"),
            "and the index stream by six entries per quad — both from the same quad_start or the two disagree");
        assertTrue(branch.contains("range.primitiveCount = blas_quad_count(view, geometry) * 2u;"),
            "two triangles per quad, or the range reads past its own geometry");
        assertTrue(scene.contains("Native-scene indices are LOCAL per quad"),
            "the producer and the shader agree because this says so out loud next to the offsets");
    }

    /** The header is the only place that states the unit of the field the shader adds. */
    @Test
    void theHeaderSaysTheRangeStartCountsQuads() throws IOException {
        String header = read(SCENE_HEADER);
        assertTrue(header.contains("ranges[g].firstQuad  first quad of geometry g"),
            "the contract names the unit: quads, so the shader multiplies by 4 and the range by 6");
    }

    /** An index past the declared ranges is a stale hit after a table update, not a geometry to read. */
    @Test
    void anIndexPastTheDeclaredRangesResolvesToNoRange() {
        String lookup = block(read(GEOMETRY), "int geometryRangeForPrimitive(GeometrySection section, "
            + "uint geometryIndex) {");
        assertTrue(lookup.contains("uint count = min(section.meta.y, MAX_SECTION_GEOMETRIES);"),
            "the declared count is clamped by the array the section actually has");
        assertTrue(lookup.contains("return geometryIndex < count ? int(geometryIndex) : -1;"),
            "out of range has to be no range; returning 0 here silently reads the first pass");
        String base = block(read(GEOMETRY), "uint geometryVertexBase(GeometrySection section, "
            + "uint geometryIndex, uint primitive) {");
        assertTrue(base.indexOf("if (rangeIndex < 0) return 0u;") >= 0
                && base.indexOf("if (rangeIndex < 0) return 0u;") < base.indexOf("uint(rangeIndex)"),
            "the negative has to be stopped before anything casts it back to unsigned");
    }

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "the shader no longer declares " + header);
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block at offset " + at);
    }

    private static int count(String source, String needle) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + needle.length();
        }
    }

    private static String read(String relative) {
        return read(Path.of(relative));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }
}
