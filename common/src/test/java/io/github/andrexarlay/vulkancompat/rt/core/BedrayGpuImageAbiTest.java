package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.*;

/** Loads the actual DLL and checks the image ABI; no Vulkan device is created. */
class BedrayGpuImageAbiTest {
    private static Path dll() {
        Path dll = Path.of(System.getProperty("bedray.core.library",
            "../build/bedray-native/windows-x64/Release/bedray_core.dll")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(dll), "native core not built at " + dll);
        return dll;
    }

    @Test
    void nativeImageLayoutsMatchEveryFieldUsedByFfm() throws Throwable {
        try (BedrayCoreNative library = BedrayCoreNative.load(dll()); Arena arena = Arena.ofConfined()) {
            MethodHandle info = library.resourceFunction("bedray_gpu_image_abi_info",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MemorySegment out = arena.allocate(96, 4);
            out.set(JAVA_INT, 0, 12345);
            assertEquals(1, (int) info.invokeExact(out, 95));
            assertEquals(12345, out.get(JAVA_INT, 0), "short output must not be written");
            assertEquals(0, (int) info.invokeExact(out, 96));
            assertEquals(1, out.get(JAVA_INT, 0));
            assertEquals(96, out.get(JAVA_INT, 4));
            assertEquals(248, out.get(JAVA_INT, 8));
            assertEquals(8, out.get(JAVA_INT, 12));
            int[] owner = {0, 4, 8, 16, 24, 32, 40, 44, 48, 176};
            for (int i = 0; i < owner.length; ++i) assertEquals(owner[i], out.get(JAVA_INT, 16 + i * 4L));
            assertEquals(56, out.get(JAVA_INT, 56));
            assertEquals(8, out.get(JAVA_INT, 60));
            int[] image = {0, 8, 16, 24, 32, 40, 44, 48};
            for (int i = 0; i < image.length; ++i) assertEquals(image[i], out.get(JAVA_INT, 64 + i * 4L));

            MethodHandle create = library.resourceFunction("bedray_gpu_image_owner_create",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MemorySegment desc = arena.allocate(248, 8);
            desc.set(JAVA_INT, 0, 1); desc.set(JAVA_INT, 4, 248);
            MemorySegment ownerPointer = arena.allocate(ADDRESS);
            assertEquals(1, (int) create.invokeExact(desc, ownerPointer), "missing device must fail before any Vulkan call");
            assertEquals(0, ownerPointer.get(ADDRESS, 0).address());
        }
    }

    @Test
    void nativeBufferLayoutsAndUploadExportsMatchFfm() throws Throwable {
        try (BedrayCoreNative library = BedrayCoreNative.load(dll()); Arena arena = Arena.ofConfined()) {
            MethodHandle info = library.resourceFunction("bedray_gpu_abi_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MemorySegment out = arena.allocate(104, 4);
            out.set(JAVA_INT, 0, 12345);
            assertEquals(1, (int) info.invokeExact(out, 103));
            assertEquals(12345, out.get(JAVA_INT, 0));
            assertEquals(0, (int) info.invokeExact(out, 104));
            int[] expected = {1, 104, 264, 8, 0, 4, 8, 16, 24, 32, 40, 44, 48, 176,
                48, 8, 0, 8, 16, 24, 32, 40, 16, 8, 0, 8};
            for (int i = 0; i < expected.length; ++i) assertEquals(expected[i], out.get(JAVA_INT, i * 4L));
            MethodHandle upload = library.resourceFunction("bedray_gpu_buffer_upload",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, java.lang.foreign.ValueLayout.JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
            MemorySegment buffer = arena.allocate(48, 8), token = arena.allocate(16, 8), vk = arena.allocate(JAVA_INT);
            assertEquals(1, (int) upload.invokeExact(MemorySegment.NULL, 16, MemorySegment.NULL, 0L, buffer, token, vk));
        }
    }

    @Test
    void libraryCannotUnloadWhileNativeResourceOwnerIsRetained() {
        try (BedrayCoreNative library = BedrayCoreNative.load(dll())) {
            library.retainResourceOwner(42);
            try {
                BedrayCoreException error = assertThrows(BedrayCoreException.class, library::close);
                assertEquals(BedrayCoreLayouts.STATUS_IN_USE, error.status());
            } finally {
                library.releaseResourceOwner(42);
            }
        }
    }
}
