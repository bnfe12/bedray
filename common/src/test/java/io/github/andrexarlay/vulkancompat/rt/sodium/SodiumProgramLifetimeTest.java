package io.github.andrexarlay.vulkancompat.rt.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SodiumDequantize} is destroyed with {@code RtWorld}, and {@code SlangRuntime.program} hands out a cached
 * instance ({@code programs.get(name)}), so the decoder only borrows the {@code SlangProgram}. Closing it frees the
 * core's pipeline but leaves the dead entry in the runtime's map; the next world load gets that same object back and
 * feeds its freed handle to a fresh {@code BedraySodiumNative}. The owner crash was a hang on the first decode after a
 * reload, not an exception, because the native side trusts the handle it was given.
 * <p>
 * Ways this silently breaks: a {@code close()} reappearing in {@code destroy()} because "this object created it", a
 * {@code SlangProgram} field added back so it looks like something this class owns, {@code nativeHandle()} returning a
 * freed handle instead of refusing (the poison then only shows up as GPU-side corruption), or the runtime clearing its
 * cache on close so a consumer believes it may release programs itself.
 */
class SodiumProgramLifetimeTest {

    private static final Path DECODER =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/sodium/SodiumDequantize.java");
    private static final Path RUNTIME =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java");
    private static final Path PROGRAM =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangProgram.java");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    @Test
    void destroyKeepsTheBorrowedProgramAlive() {
        String source = read(DECODER);
        assertFalse(source.contains("program.close()"),
            "the decoder borrows the cached program; closing it frees a pipeline still registered in SlangRuntime");
        assertFalse(source.contains("import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;"),
            "holding a SlangProgram field is what makes close() look owned; the handle is all this class needs");
        String destroy = block(source, "public void destroy() {");
        assertTrue(destroy.contains("closed = true;"), "destroy still marks this decoder finished");
        assertTrue(destroy.contains("if (!recordings.isEmpty()) throw new IllegalStateException"),
            "and still refuses to drop an unreleased recording, which is what keeps the borrow honest");
    }

    @Test
    void theRuntimeIsTheOnlyOwnerOfTheCache() {
        String runtime = read(RUNTIME);
        assertTrue(runtime.contains("SlangProgram existing = programs.get(name);")
                && runtime.contains("return Optional.of(existing);"),
            "a second world load gets the very same instance, so a closed one is a poisoned cache");
        assertTrue(runtime.contains("programs.put(name, program);"),
            "registration stores it in the map the runtime alone drains");
        assertTrue(runtime.contains("for (SlangProgram program : programs.values()) program.coreDestroyed();"),
            "the runtime invalidates every cached program when the core is destroyed");
    }

    @Test
    void aClosedProgramRefusesToHandOutItsHandle() {
        String program = read(PROGRAM);
        assertTrue(program.contains("library.destroyProgram(core, handle);"),
            "close really does free the core's program, which is why the stale handle is dangerous");
        String handle = block(program, "public long nativeHandle() {");
        assertTrue(handle.contains("if (closed) {"), "a closed instance must not leak the handle it no longer owns");
        assertTrue(handle.contains("throw new IllegalStateException(\"SlangProgram \" + name() + \" is closed\");"),
            "the failure has to be a Java exception at construction, not GPU-side corruption later");
        assertTrue(handle.indexOf("return handle;") > handle.indexOf("if (closed) {"),
            "the guard precedes the return, so every caller is checked");
    }

    private static String block(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(start >= 0, "missing " + header);
        int depth = 0;
        for (int i = start + header.length() - 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced " + header);
    }
}
