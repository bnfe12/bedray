package io.github.andrexarlay.vulkancompat.rt.slang;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;

/**
 * One AOT-compiled Slang entry pass: the generated manifest plus the SPIR-V words {@code slangc} produced.
 *
 * <p>The pass is immutable and safe to cache; the native core copies the words into a {@code VkShaderModule} when
 * the program is registered, so nothing here has to stay alive after that call.
 */
public final class SlangPass {
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int SPIRV_HEADER_WORDS = 5;

    private final SlangPassManifest manifest;
    private final byte[] spirv;

    public SlangPass(SlangPassManifest manifest, byte[] spirv) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
        this.manifest = manifest;
        this.spirv = spirv.clone();
        if (this.spirv.length < SPIRV_HEADER_WORDS * 4 || this.spirv.length % 4 != 0) {
            throw new IllegalArgumentException(manifest.name() + ": SPIR-V is " + this.spirv.length
                + " bytes, expected whole words");
        }
        int magic = (this.spirv[0] & 0xFF) | ((this.spirv[1] & 0xFF) << 8)
            | ((this.spirv[2] & 0xFF) << 16) | ((this.spirv[3] & 0xFF) << 24);
        if (magic != SPIRV_MAGIC) {
            throw new IllegalArgumentException(manifest.name() + ": not a SPIR-V module (magic 0x"
                + Integer.toHexString(magic) + ")");
        }
        int words = this.spirv.length / 4;
        if (words != manifest.spirvWordCount()) {
            throw new IllegalArgumentException(manifest.name() + ": manifest says " + manifest.spirvWordCount()
                + " SPIR-V words, file has " + words);
        }
    }

    public SlangPassManifest manifest() {
        return manifest;
    }

    public String name() {
        return manifest.name();
    }

    public byte[] spirv() {
        return spirv.clone();
    }

    public int wordCount() {
        return spirv.length / 4;
    }

    /** Loads a pass from the mod resources; empty when it was not compiled (no Slang SDK at build time). */
    public static Optional<SlangPass> load(String name) {
        return SlangPassLibrary.load(name);
    }

    /** Copies the words into {@code arena} as a native little-endian array for {@code bedray_core_register_program}. */
    MemorySegment toNative(Arena arena) {
        MemorySegment segment = arena.allocate(spirv.length, 4);
        MemorySegment.copy(spirv, 0, segment, ValueLayout.JAVA_BYTE, 0, spirv.length);
        return segment;
    }

    @Override
    public String toString() {
        return "SlangPass[" + manifest.name() + ", " + wordCount() + " words]";
    }
}
