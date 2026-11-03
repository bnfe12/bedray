package io.github.andrexarlay.vulkancompat.structure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The device has one native core and {@code SlangRuntimePool} is the only thing that creates it. {@code RtAtmosphere}
 * used to create its own {@code BedrayCore} on the same {@code VkDevice}: it was created, reported ABI 2 ready, and
 * then the JVM died inside nvoglv64.dll on the very first Vulkan call that core made (registering
 * {@code volumetric_march}, read at address 0x4, hs_err_pid12888 of 2026-10-29). A driver fault like that is not
 * catchable, so the shape itself is what this pins: no second owner, and a borrower that leases rather than holds.
 *
 * <p>Ways this silently breaks: a new subsystem wants a compute pass and reaches for {@code SlangRuntime.create}
 * because it is the shortest way to get one; or the atmosphere closes the pooled runtime instead of its lease and the
 * renderer then drives a destroyed device; or a shutdown path releases the last lease while an atmosphere pass still
 * pins a recording, which is the failure {@code destroy_slang_runtime} already reports.
 */
class SlangRuntimeOwnershipTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Path RENDERER =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");
    private static final Path ATMOSPHERE =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/world/RtAtmosphere.java");

    /** The owner, and nothing else: a second creator anywhere in the module is what killed the game. */
    private static final Set<String> CREATORS = Set.of(
        "io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntimePool.java");

    @Test
    void exactlyOneFileCreatesASlangRuntime() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(SOURCES)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String relative = SOURCES.relativize(file).toString().replace('\\', '/');
                if (code(read(file)).contains("SlangRuntime.create(")) {
                    found.add(relative);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("cannot scan " + SOURCES, e);
        }
        assertEquals(CREATORS, Set.copyOf(found),
            "every extra creator is a second BedrayCore on the same VkDevice");
    }

    /**
     * Leasing is only safe while the borrower never treats the core as its own property: closing the runtime would
     * leave the renderer driving a destroyed device, and the pool is what decides when the last lease may close it.
     */
    @Test
    void theAtmosphereLeasesTheCoreAndNeverClosesIt() {
        String atmosphere = code(read(ATMOSPHERE));
        assertTrue(atmosphere.contains("SlangRuntimePool.acquire(device)"),
            "the atmosphere must take the device's pooled core instead of making one");
        assertFalse(atmosphere.contains("slangRuntime.close()"),
            "the core is closed by the pool, after every holder released its lease");
        assertTrue(atmosphere.contains("slangLease.close()"), "but it must give its own lease back");
        assertTrue(read(ATMOSPHERE).contains("slangRuntime != null && slangRuntime.isClosed()"),
            "a world reload replaces the core, so a closed reference must not be kept");
    }

    /**
     * The order is a lifetime rule, not cosmetics: an atmosphere pass pins a recording on the same core the path pass
     * uses, so releasing the last lease first is the {@code destroy_slang_runtime} refusal.
     */
    @Test
    void everyCoreReleaseIsPrecededByTheAtmosphereRelease() {
        String renderer = code(read(RENDERER));
        List<Integer> closes = occurrences(renderer, "pathSlangLease.close()");
        List<Integer> releases = occurrences(renderer, "atmosphere.destroy()");
        assertEquals(2, closes.size(), "both shutdown branches still hand the core back to the pool");
        assertEquals(2, releases.size(), "and both must release the atmosphere passes first");
        for (int close : closes) {
            assertTrue(releases.stream().anyMatch(at -> at < close),
                "a shutdown branch releases the core while an atmosphere recording is still pinned");
        }
    }

    private static List<Integer> occurrences(String source, String needle) {
        List<Integer> at = new ArrayList<>();
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
            at.add(i);
        }
        return at;
    }

    /** Comments may name the API they forbid, so a guard reads code and not prose. */
    private static String code(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n")) {
            int comment = line.indexOf("//");
            out.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return out.toString();
    }

    private static String read(Path relative) {
        try {
            return Files.readString(relative, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
