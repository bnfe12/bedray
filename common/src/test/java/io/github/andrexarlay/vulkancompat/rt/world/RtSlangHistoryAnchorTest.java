package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-6: {@code SurfaceHit.worldPosition} is built from a section origin folded modulo 1024, so two blocks exactly one
 * region apart share an anchor and a point past the seam jumps a thousand blocks backwards. That is fine for a
 * procedural pattern and wrong for a history key, because reprojection asks "is this the same surface the previous
 * frame saw".
 *
 * <p>Ways this silently breaks: a pass starts feeding {@code historyValidate} the wrapped anchor and accumulates
 * another region's radiance wherever the normal, depth and material happen to agree; or {@code experimental} gets
 * imported into a root and its unverified key goes live; or a new reader appears and the one field comment that says
 * "not an identity" stops being the whole defence.
 */
class RtSlangHistoryAnchorTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path ROOT = SHADERS.resolve("bedray.slang");
    private static final Path LEGACY_ROOT = SHADERS.resolve("bedray_legacy.slang");
    private static final Path HIT = SHADERS.resolve("contracts/hit.slang");

    /** Backends that fill the field in, plus the contract's own empty hit; the modulo is the point of the item. */
    private static final List<String> WRITERS = List.of(
        "contracts/hit.slang",
        "traversal/hardware_traversal.slang",
        "traversal/legacy_section_traversal.slang",
        "traversal/voxel_traversal.slang",
        "traversal/entity_geometry.slang");

    /**
     * The only things allowed to read it: three documented procedural anchors and one unreachable module. The tracer
     * reads it to name a world-locked shadow cell and a leaf's own block - a pattern derived from where the surface is,
     * not an identity - so two surfaces that wrap to the same anchor still shade alike.
     */
    private static final List<String> READERS = List.of(
        "experimental/restir_pt.slang",
        "pathtrace/path_tracer.slang",
        "surface/surface.slang");

    @Test
    void onlyTheDocumentedAnchorsReadTheWrappedPosition() throws IOException {
        TreeSet<String> readers = new TreeSet<>();
        TreeSet<String> writers = new TreeSet<>();
        for (Path path : shaders()) {
            String relative = SHADERS.relativize(path).toString().replace('\\', '/');
            for (String line : code(Files.readString(path, StandardCharsets.UTF_8)).split("\n")) {
                if (!line.matches("(?s).*\\b\\w+\\.worldPosition\\b.*")) {
                    continue;
                }
                (line.matches("(?s).*\\.worldPosition\\s*=.*") ? writers : readers).add(relative);
            }
        }
        assertEquals(new TreeSet<>(WRITERS), writers,
            "the traversal backends and the contract's empty hit are the only producers; a new one must state "
                + "whether its anchor is wrapped");
        assertEquals(new TreeSet<>(READERS), readers,
            "every reader of a 1024-wrapped anchor has to be a procedural pattern or a module proven unreachable "
                + "below; a history key needs a globally unique position");
    }

    /**
     * The one history validation that uses the wrapped anchor lives in a module no root imports, so it cannot reach a
     * frame. If that import is ever added, this fails before the accumulator does.
     */
    @Test
    void theModuleThatKeysHistoryOnTheAnchorIsStillUnreachable() throws IOException {
        String roots = code(Files.readString(ROOT, StandardCharsets.UTF_8)
            + Files.readString(LEGACY_ROOT, StandardCharsets.UTF_8));
        assertTrue(roots.indexOf("restir_pt") < 0,
            "restir_pt is the only caller that feeds a wrapped anchor to historyValidate, so importing it into a "
                + "root makes P1-6 live without changing a line of its arithmetic");

        String restir = Files.readString(SHADERS.resolve("experimental/restir_pt.slang"), StandardCharsets.UTF_8);
        assertTrue(restir.contains("HistoryStatus status = historyValidate(storedReceiver, receiver.worldPosition,"),
            "the unvalidated key the guard above is scoped to");
        assertTrue(restir.contains("RESTIR_PT_NOTES.md"),
            "the module has to keep pointing at the note that explains why this is not an identity");

        TreeSet<String> callers = new TreeSet<>();
        for (Path path : shaders()) {
            String relative = SHADERS.relativize(path).toString().replace('\\', '/');
            if (relative.startsWith("experimental/")) {
                continue;
            }
            if (code(Files.readString(path, StandardCharsets.UTF_8)).contains("historyValidate(")) {
                callers.add(relative);
            }
        }
        assertEquals(new TreeSet<>(List.of("contracts/history.slang")), callers,
            "outside experimental only the definition lives here: no live pass validates history at all");
    }

    /** The wrap is deliberate, so the field has to keep saying so where a reader would see it. */
    @Test
    void theFieldAnnouncesThatItIsNotAUniqueIdentity() throws IOException {
        String hit = Files.readString(HIT, StandardCharsets.UTF_8);
        int at = hit.indexOf("float3    worldPosition;");
        assertTrue(at > 0, "the contract still declares the field");
        String above = hit.substring(Math.max(0, at - 400), at);
        assertTrue(above.contains("& 1023") || above.contains("1024"),
            "the comment has to name the wrap it is warning about");
        assertTrue(above.contains("must not by itself key history"),
            "and it has to forbid the use this test is guarding");
    }

    private static List<Path> shaders() throws IOException {
        try (var files = Files.walk(SHADERS)) {
            return files.filter(path -> path.toString().endsWith(".slang")).sorted().toList();
        }
    }

    /** Source with {@code //} comments removed, so prose about a field is not read as a use of it. */
    private static String code(String source) {
        StringBuilder kept = new StringBuilder();
        for (String line : source.split("\r?\n")) {
            int comment = line.indexOf("//");
            kept.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return kept.toString();
    }
}
