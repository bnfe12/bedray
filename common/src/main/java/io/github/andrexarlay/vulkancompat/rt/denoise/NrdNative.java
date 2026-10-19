package io.github.andrexarlay.vulkancompat.rt.denoise;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM (Java 25) binding of native/nrd_bridge/include/vkb_nrd.h (API 0.1.0). The signatures are written by hand from that header
 * (line numbers of the header in the comments). vkb_nrd.dll is loaded only from the path given by the caller, which must come
 * from the Bedray Core {@code NativeLibraries.resolve("nrd")} ({@link BridgeNrdLocator}); never from a guessed path.
 *
 * <p>Threading: vkb_nrd keeps its last error per thread; call everything from the render thread.
 */
public final class NrdNative implements AutoCloseable {
    /** vkb_nrd_init output (VkbNrdLibraryInfo). */
    public record LibraryInfo(int major, int minor, int build, int normalEncoding, int roughnessEncoding, int normalRoughnessVkFormat) {
        @Override
        public String toString() {
            return major + "." + minor + "." + build;
        }
    }

    /** VkbNrdDispatchStats. */
    public record Stats(int dispatches, int clears, int descriptorSets, int constantBytes, int pipelines, long poolMemoryBytes, float cpuMs) {
    }

    private final Arena arena = Arena.ofShared();
    private final Path libraryPath;
    private final MethodHandle getApiVersion;
    private final MethodHandle getLastError;
    private final MethodHandle getRequiredFeatures;
    private final MethodHandle init;
    private final MethodHandle createDenoiser;
    private final MethodHandle resize;
    private final MethodHandle setFrameSettings;
    private final MethodHandle setReblurSettings;
    private final MethodHandle dispatch;
    private final MethodHandle destroy;
    private final MethodHandle shutdown;

    private NrdNative(Path libraryPath) {
        NrdLayouts.verify();
        this.libraryPath = libraryPath;
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            arena.close();
            throw new NrdException("load", "cannot load " + libraryPath + " (x64 DLL expected): " + e, e);
        }
        Linker linker = Linker.nativeLinker();
        try {
            getApiVersion = bind(linker, lookup, "vkb_nrd_get_api_version", FunctionDescriptor.of(JAVA_INT));              // vkb_nrd.h:195
            getLastError = bind(linker, lookup, "vkb_nrd_get_last_error_string", FunctionDescriptor.of(ADDRESS));           // :196
            getRequiredFeatures = bind(linker, lookup, "vkb_nrd_get_required_features", FunctionDescriptor.of(JAVA_INT));   // :197
            // int32 vkb_nrd_init(u64 instance, u64 phys, u64 device, void* gipa, void* gdpa, const char* path, VkbNrdLibraryInfo*)  :201
            init = bind(linker, lookup, "vkb_nrd_init",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
            // int32 vkb_nrd_create_denoiser(const VkbNrdCreateInfo*, u64* out)                                                 :207
            createDenoiser = bind(linker, lookup, "vkb_nrd_create_denoiser", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            resize = bind(linker, lookup, "vkb_nrd_resize", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT)); // :210
            setFrameSettings = bind(linker, lookup, "vkb_nrd_set_frame_settings", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)); // :211
            setReblurSettings = bind(linker, lookup, "vkb_nrd_set_reblur_settings", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)); // :212
            // int32 vkb_nrd_dispatch(u64 handle, u64 cmd, u32 mode_mask, const VkbNrdResource*, VkbNrdDispatchStats*)          :219
            dispatch = bind(linker, lookup, "vkb_nrd_dispatch",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));
            destroy = bind(linker, lookup, "vkb_nrd_destroy", FunctionDescriptor.ofVoid(JAVA_LONG));                          // :222
            shutdown = bind(linker, lookup, "vkb_nrd_shutdown", FunctionDescriptor.ofVoid());                                // :223
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /** Loads vkb_nrd.dll from an explicit file and checks the API version. */
    public static NrdNative load(Path wrapperDll) {
        if (!Files.isRegularFile(wrapperDll)) {
            throw new NrdException("load", "vkb_nrd.dll not found at " + wrapperDll, null);
        }
        NrdNative n = new NrdNative(wrapperDll.toAbsolutePath().normalize());
        int v = n.apiVersion();
        if (v != NrdLayouts.API_VERSION) {
            n.arena.close();
            throw new NrdException("load", "vkb_nrd.dll API version 0x" + Integer.toHexString(v) + " != expected 0x"
                + Integer.toHexString(NrdLayouts.API_VERSION) + " (rebuild it from this repository)", null);
        }
        return n;
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new NrdException("load", "vkb_nrd.dll has no export " + name, null));
        return linker.downcallHandle(symbol, fd);
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public int apiVersion() {
        return invokeInt("api_version", getApiVersion);
    }

    /** VKB_NRD_FEATURE_* mask the VkDevice must have enabled. */
    public int requiredFeatures() {
        return invokeInt("required_features", getRequiredFeatures);
    }

    public String lastError() {
        try {
            MemorySegment p = (MemorySegment) getLastError.invokeExact();
            return p.reinterpret(4096).getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new NrdException("last_error", "vkb_nrd_get_last_error_string failed", t);
        }
    }

    private int invokeInt(String stage, MethodHandle h) {
        try {
            return (int) h.invokeExact();
        } catch (Throwable t) {
            throw new NrdException(stage, "call failed", t);
        }
    }

    private void check(String stage, int code) {
        if (code != 0) {
            throw new NrdException(stage, code, lastError());
        }
    }

    /**
     * vkb_nrd_init: loads NRD.dll, checks version/ABI, stores the Vulkan handles. {@code gipa}/{@code gdpa} are the addresses of
     * vkGetInstanceProcAddr / vkGetDeviceProcAddr; handles are the native addresses of the engine's VkInstance/VkPhysicalDevice/VkDevice.
     */
    public LibraryInfo init(long instance, long physicalDevice, long device, long gipa, long gdpa, Path nrdDll) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(NrdLayouts.LIBRARY_INFO_SIZE, 8);
            info.set(JAVA_INT, 0, NrdLayouts.LIBRARY_INFO_SIZE);
            MemorySegment path = a.allocateFrom(nrdDll.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
            int r;
            try {
                r = (int) init.invokeExact(instance, physicalDevice, device, gipa, gdpa, path, info);
            } catch (Throwable t) {
                throw new NrdException("init", "call failed", t);
            }
            check("init", r);
            return new LibraryInfo(info.get(JAVA_INT, NrdLayouts.LI_VERSION_MAJOR), info.get(JAVA_INT, NrdLayouts.LI_VERSION_MINOR),
                info.get(JAVA_INT, NrdLayouts.LI_VERSION_BUILD), info.get(JAVA_INT, NrdLayouts.LI_NORMAL_ENCODING),
                info.get(JAVA_INT, NrdLayouts.LI_ROUGHNESS_ENCODING), info.get(JAVA_INT, NrdLayouts.LI_NORMAL_ROUGHNESS_VK_FORMAT));
        }
    }

    /** vkb_nrd_create_denoiser; {@code modeMask} is VKB_NRD_MODE_* (see {@link DenoiserMode#nativeModeMask()}). */
    public long createDenoiser(int width, int height, int modeMask, int framesInFlight) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ci = a.allocate(NrdLayouts.CREATE_INFO_SIZE, 4);
            ci.set(JAVA_INT, 0, NrdLayouts.CREATE_INFO_SIZE);
            ci.set(JAVA_INT, 4, width);
            ci.set(JAVA_INT, 8, height);
            ci.set(JAVA_INT, 12, modeMask);
            ci.set(JAVA_INT, 16, framesInFlight);
            ci.set(JAVA_INT, 20, 0);
            MemorySegment out = a.allocate(JAVA_LONG);
            int r;
            try {
                r = (int) createDenoiser.invokeExact(ci, out);
            } catch (Throwable t) {
                throw new NrdException("create_denoiser", "call failed", t);
            }
            check("create_denoiser", r);
            return out.get(JAVA_LONG, 0);
        }
    }

    /** The device must be idle. */
    public void resize(long handle, int width, int height) {
        int r;
        try {
            r = (int) resize.invokeExact(handle, width, height);
        } catch (Throwable t) {
            throw new NrdException("resize", "call failed", t);
        }
        check("resize", r);
    }

    public void setFrameSettings(long handle, MemorySegment settings) {
        int r;
        try {
            r = (int) setFrameSettings.invokeExact(handle, settings);
        } catch (Throwable t) {
            throw new NrdException("set_frame_settings", "call failed", t);
        }
        check("set_frame_settings", r);
    }

    public void setReblurSettings(long handle, MemorySegment settings) {
        int r;
        try {
            r = (int) setReblurSettings.invokeExact(handle, settings);
        } catch (Throwable t) {
            throw new NrdException("set_reblur_settings", "call failed", t);
        }
        check("set_reblur_settings", r);
    }

    /** Records the denoiser dispatches into {@code commandBuffer}; {@code resources} holds RES_COUNT x VkbNrdResource. */
    public Stats dispatch(long handle, long commandBuffer, int modeMask, MemorySegment resources) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(NrdLayouts.DISPATCH_STATS_SIZE, 8);
            st.set(JAVA_INT, 0, NrdLayouts.DISPATCH_STATS_SIZE);
            int r;
            try {
                r = (int) dispatch.invokeExact(handle, commandBuffer, modeMask, resources, st);
            } catch (Throwable t) {
                throw new NrdException("dispatch", "call failed", t);
            }
            check("dispatch", r);
            return new Stats(st.get(JAVA_INT, NrdLayouts.DS_DISPATCH_COUNT), st.get(JAVA_INT, NrdLayouts.DS_CLEAR_COUNT),
                st.get(JAVA_INT, NrdLayouts.DS_SETS), st.get(JAVA_INT, NrdLayouts.DS_CONSTANT_BYTES),
                st.get(JAVA_INT, NrdLayouts.DS_PIPELINES), st.get(JAVA_LONG, NrdLayouts.DS_POOL_MEMORY),
                st.get(JAVA_FLOAT, NrdLayouts.DS_CPU_MS));
        }
    }

    /** The device must be idle. */
    public void destroyDenoiser(long handle) {
        try {
            destroy.invokeExact(handle);
        } catch (Throwable t) {
            throw new NrdException("destroy", "call failed", t);
        }
    }

    /** Unloads NRD.dll and destroys what remains (device idle). The wrapper DLL stays mapped until the JVM exits. */
    @Override
    public void close() {
        try {
            shutdown.invokeExact();
        } catch (Throwable t) {
            throw new NrdException("shutdown", "call failed", t);
        } finally {
            arena.close();
        }
    }
}
