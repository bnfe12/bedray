package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssLayouts;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssNativeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * NVIDIA DLSS Ray Reconstruction (DLSS-D / DLSS 3.5+) FFM binding and management module.
 * Marked [DLSS-RR].
 * <p>
 * This class is strictly isolated from standard DLSS Super Resolution to prevent regressions
 * in the active production upscaling pipeline. Bedray Core DLL is intentionally pinned for the
 * process lifetime: NGX and vendor frame-generation code can retain callbacks into it after an
 * RR feature has failed and been released.
 */
public final class DlssRayReconstruction implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssRR");
    private static final Arena LIBRARY_ARENA = Arena.global();
    private static Path loadedLibraryPath;
    private static SymbolLookup loadedLibrary;

    public static final String NGX_DLSSD_DLL = "nvngx_dlssd.dll";
    public static final String NGX_DLSSNR_DLL = "nvngx_dlssnr.dll";

    public static final int SIZE_RR_CREATE_INFO = 48;
    public static final int SIZE_RR_EVAL_INFO = 968;

    /** FFM layout for VkbDlssRrCreateInfo (48 bytes, 8-byte aligned). */
    public static final StructLayout RR_CREATE_INFO = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("render_width"),
        JAVA_INT.withName("render_height"),
        JAVA_INT.withName("output_width"),
        JAVA_INT.withName("output_height"),
        JAVA_INT.withName("quality_mode"),
        JAVA_INT.withName("flags"),
        JAVA_INT.withName("enable_output_subrects"),
        JAVA_INT.withName("roughness_mode"),
        JAVA_INT.withName("depth_type"),
        MemoryLayout.sequenceLayout(2, JAVA_INT).withName("reserved")
    );

    /** FFM layout for VkbDlssRrEvalInfo (968 bytes, 8-byte aligned). */
    public static final StructLayout RR_EVAL_INFO = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("reset"),
        DlssLayouts.IMAGE.withName("color"),
        DlssLayouts.IMAGE.withName("output"),
        DlssLayouts.IMAGE.withName("depth"),
        DlssLayouts.IMAGE.withName("motion_vectors"),
        DlssLayouts.IMAGE.withName("exposure"),
        DlssLayouts.IMAGE.withName("diffuse_albedo"),
        DlssLayouts.IMAGE.withName("specular_albedo"),
        DlssLayouts.IMAGE.withName("normals"),
        DlssLayouts.IMAGE.withName("roughness"),
        DlssLayouts.IMAGE.withName("diffuse_hit_distance"),
        DlssLayouts.IMAGE.withName("specular_hit_distance"),
        DlssLayouts.IMAGE.withName("ray_tracing_hit_distance"),
        DlssLayouts.IMAGE.withName("transparency_mask"),
        DlssLayouts.IMAGE.withName("disocclusion_mask"),
        JAVA_FLOAT.withName("jitter_x"),
        JAVA_FLOAT.withName("jitter_y"),
        JAVA_FLOAT.withName("mv_scale_x"),
        JAVA_FLOAT.withName("mv_scale_y"),
        JAVA_FLOAT.withName("sharpness"),
        JAVA_FLOAT.withName("pre_exposure"),
        JAVA_FLOAT.withName("exposure_scale"),
        JAVA_FLOAT.withName("frame_time_delta_ms"),
        JAVA_INT.withName("render_subrect_width"),
        JAVA_INT.withName("render_subrect_height"),
        JAVA_INT.withName("has_matrices"),
        MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("world_to_view_matrix"),
        MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("view_to_clip_matrix"),
        JAVA_INT.withName("reserved")
    );

    private final MethodHandle isSupportedHandle;
    private final MethodHandle createFeatureHandle;
    private final MethodHandle evaluateHandle;
    private final MethodHandle releaseFeatureHandle;
    private final MethodHandle getLastErrorStringHandle;
    private final MethodHandle getLastNgxResultHandle;
    private final MethodHandle initHandle;

    private DlssRayReconstruction(SymbolLookup lookup) {
        verifyLayouts();
        Linker linker = Linker.nativeLinker();
        isSupportedHandle = bind(linker, lookup, "vkb_dlss_rr_is_supported",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        createFeatureHandle = bind(linker, lookup, "vkb_dlss_rr_create_feature",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
        evaluateHandle = bind(linker, lookup, "vkb_dlss_rr_evaluate",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
        releaseFeatureHandle = bind(linker, lookup, "vkb_dlss_rr_release_feature",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        getLastErrorStringHandle = bind(linker, lookup, "vkb_dlss_get_last_error_string",
            FunctionDescriptor.of(ADDRESS));
        getLastNgxResultHandle = bind(linker, lookup, "vkb_dlss_get_last_ngx_result",
            FunctionDescriptor.of(JAVA_INT));
        initHandle = bind(linker, lookup, "vkb_dlss_init",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    }

    /**
     * Checks whether native DLSS Ray Reconstruction libraries ({@code nvngx_dlssd.dll} or {@code nvngx_dlssnr.dll})
     * exist in the specified feature folder.
     */
    public static boolean isDlssdDllPresent(Path featureDir) {
        if (featureDir == null) return false;
        return Files.isRegularFile(featureDir.resolve(NGX_DLSSD_DLL))
            || Files.isRegularFile(featureDir.resolve(NGX_DLSSNR_DLL));
    }

    /**
     * Verifies that the FFM memory layouts match the exact byte sizes static_assert'ed in C++.
     */
    public static void verifyLayouts() {
        DlssLayouts.verify();
        if (RR_CREATE_INFO.byteSize() != SIZE_RR_CREATE_INFO) {
            throw new IllegalStateException("[DLSS-RR] RR_CREATE_INFO size is " + RR_CREATE_INFO.byteSize() + ", expected " + SIZE_RR_CREATE_INFO);
        }
        if (RR_EVAL_INFO.byteSize() != SIZE_RR_EVAL_INFO) {
            throw new IllegalStateException("[DLSS-RR] RR_EVAL_INFO size is " + RR_EVAL_INFO.byteSize() + ", expected " + SIZE_RR_EVAL_INFO);
        }
    }

    /**
     * Loads the Ray Reconstruction FFM binding from {@code vkb_dlss.dll}.
     */
    public static synchronized DlssRayReconstruction load(Path vkbDlssDll) {
        if (vkbDlssDll == null || !Files.isRegularFile(vkbDlssDll)) {
            throw new DlssNativeException("[DLSS-RR] vkb_dlss.dll path is invalid: " + LogPaths.show(vkbDlssDll), null);
        }
        // Do not associate this DLL with a denoiser-owned arena. Closing that arena on an RR
        // fallback can unload vkb_dlss.dll while NGX/Streamline still has native callbacks into
        // the wrapper. Arena.global() keeps the library and its downcall targets alive until JVM
        // shutdown, matching DlssRuntime's process-lifetime ownership of the same wrapper.
        try {
            Path requestedPath = vkbDlssDll.toAbsolutePath().normalize();
            if (loadedLibrary == null) {
                loadedLibrary = SymbolLookup.libraryLookup(requestedPath, LIBRARY_ARENA);
                loadedLibraryPath = requestedPath;
            } else if (!requestedPath.equals(loadedLibraryPath)) {
                throw new DlssNativeException("[DLSS-RR] vkb_dlss.dll already pinned from " + LogPaths.show(loadedLibraryPath)
                    + "; refusing a second wrapper at " + LogPaths.show(requestedPath), null);
            }
            DlssRayReconstruction rr = new DlssRayReconstruction(loadedLibrary);
            LOGGER.info("[DLSS-RR] Initialized native FFM Ray Reconstruction binding from {} (process-pinned)", LogPaths.show(requestedPath));
            return rr;
        } catch (Throwable t) {
            throw new DlssNativeException("[DLSS-RR] Failed to load vkb_dlss.dll: " + LogPaths.show(t.getMessage()), t);
        }
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment sym = lookup.find(name).orElseThrow(() ->
            new DlssNativeException("[DLSS-RR] Export not found in vkb_dlss.dll: " + name, null));
        return linker.downcallHandle(sym, fd);
    }

    private DlssNative.Status lastStatus(int code) {
        try {
            MemorySegment msg = ((MemorySegment) getLastErrorStringHandle.invokeExact()).reinterpret(Long.MAX_VALUE);
            int ngx = (int) getLastNgxResultHandle.invokeExact();
            return new DlssNative.Status(code, code == DlssNative.OK ? "" : msg.getString(0), ngx);
        } catch (Throwable t) {
            throw new DlssNativeException("[DLSS-RR] get_last_error_string failed", t);
        }
    }

    /**
     * Initializes NGX via {@code vkb_dlss_init} on the specified Vulkan device.
     */
    public DlssNative.Status init(long vkInstance, long vkPhysicalDevice, long vkDevice,
                                  long getInstanceProcAddr, long getDeviceProcAddr,
                                  Path featureDir, Path logDir, String projectId, String engineVersion,
                                  DlssNative.Caps[] outCaps) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment caps = a.allocate(DlssLayouts.CAPS);
            caps.set(JAVA_INT, 0, DlssLayouts.SIZE_CAPS);
            MemorySegment featPath = featureDir != null
                ? a.allocateFrom(JAVA_CHAR, (featureDir.toString() + "\0").toCharArray())
                : MemorySegment.NULL;
            MemorySegment logPath = logDir != null
                ? a.allocateFrom(JAVA_CHAR, (logDir.toString() + "\0").toCharArray())
                : MemorySegment.NULL;
            MemorySegment proj = projectId != null && !projectId.isEmpty()
                ? a.allocateFrom(projectId)
                : MemorySegment.NULL;
            MemorySegment eng = engineVersion != null && !engineVersion.isEmpty()
                ? a.allocateFrom(engineVersion)
                : MemorySegment.NULL;

            int rc = (int) initHandle.invokeExact(
                vkInstance, vkPhysicalDevice, vkDevice,
                MemorySegment.ofAddress(getInstanceProcAddr),
                MemorySegment.ofAddress(getDeviceProcAddr),
                featPath, logPath, proj, eng, caps
            );
            if (outCaps != null && outCaps.length > 0) {
                outCaps[0] = new DlssNative.Caps(
                    caps.get(JAVA_INT, 4) != 0,
                    caps.get(JAVA_INT, 8) != 0,
                    caps.get(JAVA_INT, 12),
                    caps.get(JAVA_INT, 16),
                    caps.get(JAVA_INT, 20),
                    caps.get(JAVA_INT, 24)
                );
            }
            return lastStatus(rc);
        } catch (Throwable t) {
            throw new DlssNativeException("[DLSS-RR] init failed", t);
        }
    }

    /**
     * Queries whether DLSS Ray Reconstruction is supported on the initialized GPU device.
     * Note: {@code vkb_dlss_init} must have been called before this query.
     */
    public boolean isSupported() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment outSupported = a.allocate(JAVA_INT);
            int rc = (int) isSupportedHandle.invokeExact(outSupported);
            if (rc != DlssNative.OK) {
                LOGGER.warn("[DLSS-RR] is_supported query returned code {}: {}", rc, lastStatus(rc));
                return false;
            }
            return outSupported.get(JAVA_INT, 0) != 0;
        } catch (Throwable t) {
            LOGGER.error("[DLSS-RR] is_supported invocation failed", t);
            return false;
        }
    }

    /**
     * Raw status of the support check (for test and diagnostic validation).
     */
    public DlssNative.Status querySupportStatus(int[] outSupported) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment outSup = a.allocate(JAVA_INT);
            int rc = (int) isSupportedHandle.invokeExact(outSup);
            if (outSupported != null && outSupported.length > 0) {
                outSupported[0] = outSup.get(JAVA_INT, 0);
            }
            return lastStatus(rc);
        } catch (Throwable t) {
            throw new DlssNativeException("[DLSS-RR] querySupportStatus failed", t);
        }
    }

    /**
     * Creates a Ray Reconstruction feature handle recorded into {@code commandBuffer}.
     */
    public DlssNative.Status createFeature(long commandBuffer, DlssRrParams.CreateInfo params, long[] outFeature) {
        if (params == null || outFeature == null || outFeature.length == 0) {
            throw new IllegalArgumentException("[DLSS-RR] params and outFeature must be non-null");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(RR_CREATE_INFO);
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "struct_size"), SIZE_RR_CREATE_INFO);
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "render_width"), params.renderWidth());
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "render_height"), params.renderHeight());
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "output_width"), params.outputWidth());
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "output_height"), params.outputHeight());
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "quality_mode"), params.quality().nativeValue());
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "flags"), params.flags());
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "enable_output_subrects"), params.enableOutputSubrects() ? 1 : 0);
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "roughness_mode"), params.roughnessMode());
            info.set(JAVA_INT, DlssLayouts.offset(RR_CREATE_INFO, "depth_type"), params.depthType());

            MemorySegment handleSeg = a.allocate(JAVA_LONG);
            int rc = (int) createFeatureHandle.invokeExact(commandBuffer, info, handleSeg);
            if (rc == DlssNative.OK) {
                outFeature[0] = handleSeg.get(JAVA_LONG, 0);
            }
            return lastStatus(rc);
        } catch (Throwable t) {
            throw new DlssNativeException("[DLSS-RR] createFeature failed", t);
        }
    }

    /**
     * Evaluates Ray Reconstruction into {@code commandBuffer}.
     */
    public DlssNative.Status evaluate(long commandBuffer, long feature, DlssRrParams.EvalInfo eval) {
        if (eval == null) {
            throw new IllegalArgumentException("[DLSS-RR] eval info cannot be null");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(RR_EVAL_INFO);
            s.set(JAVA_INT, 0, SIZE_RR_EVAL_INFO);
            s.set(JAVA_INT, 4, eval.reset() ? 1 : 0);

            writeImage(s, "color", eval.color());
            writeImage(s, "output", eval.output());
            writeImage(s, "depth", eval.depth());
            writeImage(s, "motion_vectors", eval.motionVectors());
            writeImage(s, "exposure", eval.exposure());
            writeImage(s, "diffuse_albedo", eval.diffuseAlbedo());
            writeImage(s, "specular_albedo", eval.specularAlbedo());
            writeImage(s, "normals", eval.normals());
            writeImage(s, "roughness", eval.roughness());
            writeImage(s, "diffuse_hit_distance", eval.diffuseHitDistance());
            writeImage(s, "specular_hit_distance", eval.specularHitDistance());
            writeImage(s, "ray_tracing_hit_distance", eval.rayTracingHitDistance());
            writeImage(s, "transparency_mask", eval.transparencyMask());
            writeImage(s, "disocclusion_mask", eval.disocclusionMask());

            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "jitter_x"), eval.jitterX());
            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "jitter_y"), eval.jitterY());
            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "mv_scale_x"), eval.mvScaleX());
            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "mv_scale_y"), eval.mvScaleY());
            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "sharpness"), eval.sharpness());
            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "pre_exposure"), eval.preExposure());
            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "exposure_scale"), eval.exposureScale());
            s.set(JAVA_FLOAT, DlssLayouts.offset(RR_EVAL_INFO, "frame_time_delta_ms"), eval.frameTimeDeltaMs());
            s.set(JAVA_INT, DlssLayouts.offset(RR_EVAL_INFO, "render_subrect_width"), eval.renderSubrectWidth());
            s.set(JAVA_INT, DlssLayouts.offset(RR_EVAL_INFO, "render_subrect_height"), eval.renderSubrectHeight());

            int matrixFlags = 0;
            if (eval.worldToViewMatrix() != null) {
                matrixFlags |= 1;
                long w2vBase = DlssLayouts.offset(RR_EVAL_INFO, "world_to_view_matrix");
                for (int i = 0; i < 16; i++) {
                    s.set(JAVA_FLOAT, w2vBase + (long) i * JAVA_FLOAT.byteSize(), eval.worldToViewMatrix()[i]);
                }
            }
            if (eval.viewToClipMatrix() != null) {
                matrixFlags |= 2;
                long v2cBase = DlssLayouts.offset(RR_EVAL_INFO, "view_to_clip_matrix");
                for (int i = 0; i < 16; i++) {
                    s.set(JAVA_FLOAT, v2cBase + (long) i * JAVA_FLOAT.byteSize(), eval.viewToClipMatrix()[i]);
                }
            }
            s.set(JAVA_INT, DlssLayouts.offset(RR_EVAL_INFO, "has_matrices"), matrixFlags);

            int rc = (int) evaluateHandle.invokeExact(commandBuffer, feature, s);
            return lastStatus(rc);
        } catch (Throwable t) {
            throw new DlssNativeException("[DLSS-RR] evaluate failed", t);
        }
    }

    /**
     * Releases a Ray Reconstruction feature handle.
     */
    public DlssNative.Status releaseFeature(long feature) {
        try {
            int rc = (int) releaseFeatureHandle.invokeExact(feature);
            return lastStatus(rc);
        } catch (Throwable t) {
            throw new DlssNativeException("[DLSS-RR] releaseFeature failed", t);
        }
    }

    private static void writeImage(MemorySegment evalInfo, String member, DlssRrParams.Image img) {
        long base = DlssLayouts.offset(RR_EVAL_INFO, member);
        if (img == null || !img.isValid()) {
            // zero out the image struct
            evalInfo.asSlice(base, DlssLayouts.SIZE_IMAGE).fill((byte) 0);
            return;
        }
        evalInfo.set(JAVA_LONG, base + DlssLayouts.offset(DlssLayouts.IMAGE, "image"), img.image());
        evalInfo.set(JAVA_LONG, base + DlssLayouts.offset(DlssLayouts.IMAGE, "view"), img.view());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "format"), img.format());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "width"), img.width());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "height"), img.height());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "aspect_mask"), img.aspectMask());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "base_mip_level"), 0);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "level_count"), 1);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "base_array_layer"), 0);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "layer_count"), 1);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "read_write"), img.readWrite() ? 1 : 0);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "reserved"), 0);
    }

    @Override
    public void close() {
        // Releasing an individual RR feature must never unload the shared wrapper while NGX or
        // Frame Generation may still call into it. LIBRARY_ARENA owns it until JVM shutdown.
    }
}
