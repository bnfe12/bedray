package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr;

import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserConfig;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rt.denoise.DlssRrDenoiser;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameParams;
import io.github.andrexarlay.vulkancompat.rt.world.RtPipeline;
import io.github.andrexarlay.vulkancompat.rt.world.RtShaderCompiler;
import io.github.andrexarlay.vulkancompat.rt.world.RtWorldChecks;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssLayouts;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * L2 unit tests and layout validations for DLSS Ray Reconstruction ([DLSS-RR]).
 * Executed without GPU and without Minecraft.
 */
public final class DlssRrChecks {
    private DlssRrChecks() {}

    public static void runAll() throws Exception {
        layouts();
        flagsAndModes();
        paramsValidation();
        denoiserModeAndConfig();
        dlssRrDenoiserContracts();
        rtFrameRendererIntegration();
        geometricNormalsAndRoughnessPacking();
        horizonBendingAndReflectionContinuity();
        velocityMotionVectorIntegration();
        dllPresence();
        realNativeBindingIfPresent();
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("[DLSS-RR] " + msg);
        }
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void layouts() {
        DlssRayReconstruction.verifyLayouts();

        // Size checks
        check(DlssRayReconstruction.RR_CREATE_INFO.byteSize() == 48, "RR_CREATE_INFO byteSize == 48");
        check(DlssRayReconstruction.RR_EVAL_INFO.byteSize() == 968, "RR_EVAL_INFO byteSize == 968");

        // RR_CREATE_INFO offsets
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "struct_size") == 0, "create struct_size @0");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "render_width") == 4, "create render_width @4");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "render_height") == 8, "create render_height @8");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "output_width") == 12, "create output_width @12");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "output_height") == 16, "create output_height @16");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "quality_mode") == 20, "create quality_mode @20");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "flags") == 24, "create flags @24");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "enable_output_subrects") == 28, "create enable_output_subrects @28");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "roughness_mode") == 32, "create roughness_mode @32");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "depth_type") == 36, "create depth_type @36");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_CREATE_INFO, "reserved") == 40, "create reserved @40");

        // RR_EVAL_INFO offsets
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "struct_size") == 0, "eval struct_size @0");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "reset") == 4, "eval reset @4");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "color") == 8, "eval color @8");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "output") == 64, "eval output @64");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "depth") == 120, "eval depth @120");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "motion_vectors") == 176, "eval motion_vectors @176");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "exposure") == 232, "eval exposure @232");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "diffuse_albedo") == 288, "eval diffuse_albedo @288");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "specular_albedo") == 344, "eval specular_albedo @344");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "normals") == 400, "eval normals @400");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "roughness") == 456, "eval roughness @456");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "diffuse_hit_distance") == 512, "eval diffuse_hit_distance @512");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "specular_hit_distance") == 568, "eval specular_hit_distance @568");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "ray_tracing_hit_distance") == 624, "eval ray_tracing_hit_distance @624");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "transparency_mask") == 680, "eval transparency_mask @680");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "disocclusion_mask") == 736, "eval disocclusion_mask @736");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "jitter_x") == 792, "eval jitter_x @792");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "jitter_y") == 796, "eval jitter_y @796");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "mv_scale_x") == 800, "eval mv_scale_x @800");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "mv_scale_y") == 804, "eval mv_scale_y @804");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "sharpness") == 808, "eval sharpness @808");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "pre_exposure") == 812, "eval pre_exposure @812");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "exposure_scale") == 816, "eval exposure_scale @816");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "frame_time_delta_ms") == 820, "eval frame_time_delta_ms @820");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "render_subrect_width") == 824, "eval render_subrect_width @824");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "render_subrect_height") == 828, "eval render_subrect_height @828");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "has_matrices") == 832, "eval has_matrices @832");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "world_to_view_matrix") == 836, "eval world_to_view_matrix @836");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "view_to_clip_matrix") == 900, "eval view_to_clip_matrix @900");
        check(DlssLayouts.offset(DlssRayReconstruction.RR_EVAL_INFO, "reserved") == 964, "eval reserved @964");
    }

    private static void flagsAndModes() {
        check(DlssRrParams.ROUGHNESS_UNPACKED == 0, "ROUGHNESS_UNPACKED == 0");
        check(DlssRrParams.ROUGHNESS_PACKED == 1, "ROUGHNESS_PACKED == 1");
        check(DlssRrParams.DEPTH_LINEAR == 0, "DEPTH_LINEAR == 0");
        check(DlssRrParams.DEPTH_HW == 1, "DEPTH_HW == 1");

        check(DlssRrParams.FLAG_MV_LOW_RES == 1, "FLAG_MV_LOW_RES == 1");
        check(DlssRrParams.FLAG_IS_HDR == (1 << 1), "FLAG_IS_HDR == 2");
        check(DlssRrParams.FLAG_DEPTH_INVERTED == (1 << 2), "FLAG_DEPTH_INVERTED == 4");
        check(DlssRrParams.FLAG_AUTO_EXPOSURE == (1 << 3), "FLAG_AUTO_EXPOSURE == 8");
        check(DlssRrParams.FLAG_MV_JITTERED == (1 << 4), "FLAG_MV_JITTERED == 16");

        check(DlssRrParams.Preset.DEFAULT.nativeValue() == 0, "Preset.DEFAULT == 0");
        check(DlssRrParams.Preset.D.nativeValue() == 4, "Preset.D == 4");
        check(DlssRrParams.Preset.E.nativeValue() == 5, "Preset.E == 5");
        check(DlssRrParams.Preset.F.nativeValue() == 6, "Preset.F == 6");
    }

    private static void paramsValidation() {
        // Valid CreateInfo
        DlssRrParams.CreateInfo ci = new DlssRrParams.CreateInfo(
            1280, 720, 1920, 1080, DlssQuality.QUALITY,
            DlssRrParams.FLAG_MV_LOW_RES | DlssRrParams.FLAG_DEPTH_INVERTED | DlssRrParams.FLAG_AUTO_EXPOSURE,
            false, DlssRrParams.ROUGHNESS_PACKED, DlssRrParams.DEPTH_HW
        );
        check(ci.renderWidth() == 1280 && ci.renderHeight() == 720, "render size preserved");
        check(ci.quality() == DlssQuality.QUALITY, "quality preserved");

        // Invalid CreateInfo
        check(throwsIae(() -> new DlssRrParams.CreateInfo(0, 720, 1920, 1080, DlssQuality.QUALITY, 0, false, 0, 0)), "renderWidth=0 throws");
        check(throwsIae(() -> new DlssRrParams.CreateInfo(1280, -1, 1920, 1080, DlssQuality.QUALITY, 0, false, 0, 0)), "renderHeight<0 throws");
        check(throwsIae(() -> new DlssRrParams.CreateInfo(1280, 720, 1920, 1080, null, 0, false, 0, 0)), "null quality throws");

        // Valid EvalInfo
        DlssRrParams.Image validImg = new DlssRrParams.Image(101L, 202L, 44, 1280, 720, 1, false);
        DlssRrParams.Image validStorage = new DlssRrParams.Image(103L, 204L, 44, 1920, 1080, 1, true);

        DlssRrParams.EvalInfo eval = new DlssRrParams.EvalInfo(
            true, validImg, validStorage, validImg, validImg, DlssRrParams.Image.NONE,
            validImg, validImg, validImg, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            0.125f, -0.25f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 16.6f, 1280, 720,
            null, null
        );
        check(eval.reset(), "eval reset true");
        check(eval.color().view() == 202L, "eval color view preserved");
        check(eval.output().readWrite(), "eval output readWrite true");

        // Null optional images canonicalized to Image.NONE
        DlssRrParams.EvalInfo evalWithNulls = new DlssRrParams.EvalInfo(
            false, validImg, validStorage, validImg, validImg, null,
            validImg, validImg, validImg, null,
            null, null, null,
            null, null,
            0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 16.6f, 1280, 720,
            null, null
        );
        check(evalWithNulls.exposure() == DlssRrParams.Image.NONE, "null exposure canonicalized");
        check(evalWithNulls.roughness() == DlssRrParams.Image.NONE, "null roughness canonicalized");

        // Invalid EvalInfo: output with readWrite=false throws
        DlssRrParams.Image nonStorageOutput = new DlssRrParams.Image(103L, 204L, 44, 1920, 1080, 1, false);
        check(throwsIae(() -> new DlssRrParams.EvalInfo(
            false, validImg, nonStorageOutput, validImg, validImg, null,
            validImg, validImg, validImg, null,
            null, null, null,
            null, null,
            0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 16.6f, 1280, 720,
            null, null
        )), "output without readWrite=true throws");

        // Invalid EvalInfo: missing required image
        check(throwsIae(() -> new DlssRrParams.EvalInfo(
            false, DlssRrParams.Image.NONE, validStorage, validImg, validImg, DlssRrParams.Image.NONE,
            validImg, validImg, validImg, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 16.6f, 1280, 720,
            null, null
        )), "invalid color image throws");

        // Invalid EvalInfo: matrix length != 16
        check(throwsIae(() -> new DlssRrParams.EvalInfo(
            false, validImg, validStorage, validImg, validImg, DlssRrParams.Image.NONE,
            validImg, validImg, validImg, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 16.6f, 1280, 720,
            new float[12], null
        )), "bad matrix length throws");
    }

    private static void denoiserModeAndConfig() {
        check(DenoiserMode.DLSS_RR.isDlssRr(), "DLSS_RR.isDlssRr() == true");
        check(!DenoiserMode.DLSS_RR.isNrd(), "DLSS_RR.isNrd() == false");
        check("dlss_rr".equals(DenoiserMode.DLSS_RR.logName()), "logName == dlss_rr");

        check(DenoiserMode.parse("dlss_rr") == DenoiserMode.DLSS_RR, "parse dlss_rr");
        check(DenoiserMode.parse("dlss-rr") == DenoiserMode.DLSS_RR, "parse dlss-rr");
        check(DenoiserMode.parse("dlss_d") == DenoiserMode.DLSS_RR, "parse dlss_d");
        check(DenoiserMode.parse("rr") == DenoiserMode.DLSS_RR, "parse rr");
        check(DenoiserMode.parse(" DLSS_RR ") == DenoiserMode.DLSS_RR, "parse trimmed case insensitive");

        boolean throwsNativeMask = false;
        try {
            DenoiserMode.DLSS_RR.nativeModeMask();
        } catch (IllegalStateException e) {
            throwsNativeMask = true;
        }
        check(throwsNativeMask, "nativeModeMask throws for DLSS_RR");

        boolean throwsPackCode = false;
        try {
            DenoiserMode.DLSS_RR.packShaderCode();
        } catch (IllegalStateException e) {
            throwsPackCode = true;
        }
        check(throwsPackCode, "packShaderCode throws for DLSS_RR");
    }

    private static void dlssRrDenoiserContracts() throws Exception {
        // Verify method signatures on DlssRrDenoiser class
        Class<?> clazz = DlssRrDenoiser.class;
        check(clazz.getMethod("ensure", org.lwjgl.vulkan.VkCommandBuffer.class, int.class, int.class, io.github.andrexarlay.vulkancompat.rt.world.RtMemory.Img.class) != null, "ensure method exists");
        check(clazz.getMethod("gbufferViews") != null, "gbufferViews method exists");
        check(clazz.getMethod("gbViewZ") != null, "gbViewZ method exists");
        check(clazz.getMethod("gbNormalRough") != null, "gbNormalRough method exists");
        check(clazz.getMethod("gbDiffuse") != null, "gbDiffuse method exists");
        check(clazz.getMethod("gbAlbedo") != null, "gbAlbedo method exists");
        check(clazz.getMethod("gbDirect") != null, "gbDirect method exists");
        check(clazz.getMethod("inMotion") != null, "inMotion method exists");
        check(clazz.getMethod("inSpecular") != null, "inSpecular method exists");
        check(clazz.getMethod("outDiffuse") != null, "outDiffuse method exists");
        check(clazz.getDeclaredMethod("writeSet", long.class) != null, "writeSet method exists");
        check(clazz.getMethod("velocityPipeline") != null, "velocityPipeline method exists");
        check(clazz.getMethod("record", org.lwjgl.vulkan.VkCommandBuffer.class, Matrix4f.class, Matrix4f.class,
            double.class, double.class, double.class, boolean.class, float.class, long.class) != null,
            "record with depthView exists");
        check(clazz.getMethod("destroy") != null, "destroy method exists");

        // Verify DlssRayReconstruction init method exists
        check(DlssRayReconstruction.class.getMethod("init", long.class, long.class, long.class, long.class, long.class,
            java.nio.file.Path.class, java.nio.file.Path.class, String.class, String.class, DlssNative.Caps[].class) != null,
            "DlssRayReconstruction.init method exists");

        // Verify DLSS-RR activates DLSS device requirements
        check(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.decide(
            DenoiserMode.DLSS_RR.isDlssRr(), false) == io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.Decision.REGISTER_REQUESTED,
            "DLSS-RR requested forces REGISTER_REQUESTED even if preregister is disabled");

        // Verify EvalInfo contracts with matrices
        float[] w2v = new float[16];
        float[] v2c = new float[16];
        w2v[0] = 1.0f;
        v2c[0] = 1.0f;

        DlssRrParams.Image color = new DlssRrParams.Image(1L, 2L, 44, 1920, 1080, 1, false);
        DlssRrParams.Image outStorage = new DlssRrParams.Image(3L, 4L, 44, 1920, 1080, 1, true);
        DlssRrParams.Image depth = new DlssRrParams.Image(5L, 6L, 44, 1920, 1080, 1, false);
        DlssRrParams.Image mv = new DlssRrParams.Image(7L, 8L, 44, 1920, 1080, 1, false);
        DlssRrParams.Image diffAlbedo = new DlssRrParams.Image(9L, 10L, 44, 1920, 1080, 1, false);
        DlssRrParams.Image specAlbedo = new DlssRrParams.Image(11L, 12L, 44, 1920, 1080, 1, false);
        DlssRrParams.Image normals = new DlssRrParams.Image(13L, 14L, 44, 1920, 1080, 1, false);

        DlssRrParams.EvalInfo eval = new DlssRrParams.EvalInfo(
            false, color, outStorage, depth, mv, DlssRrParams.Image.NONE,
            diffAlbedo, specAlbedo, normals, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 16.6f, 1920, 1080,
            w2v, v2c
        );
        check(eval.output().readWrite(), "output image must have readWrite=true");
        check(eval.worldToViewMatrix() != null && eval.worldToViewMatrix().length == 16, "w2v length 16");
        check(eval.viewToClipMatrix() != null && eval.viewToClipMatrix().length == 16, "v2c length 16");
    }

    private static void rtFrameRendererIntegration() {
        check(!RtFrameRenderer.dlssRrActive(), "dlssRrActive false by default");
        check(RtFrameRenderer.dlssRrDenoiser() == null, "dlssRrDenoiser null by default");
    }

    private static void dllPresence() throws Exception {
        check(!DlssRayReconstruction.isDlssdDllPresent(null), "null dir: false");
        Path tmp = Files.createTempDirectory("dlss_rr_test");
        try {
            check(!DlssRayReconstruction.isDlssdDllPresent(tmp), "empty dir: false");
            Path mockDlssd = tmp.resolve(DlssRayReconstruction.NGX_DLSSD_DLL);
            Files.writeString(mockDlssd, "mock");
            check(DlssRayReconstruction.isDlssdDllPresent(tmp), "dlssd file present: true");
            Files.deleteIfExists(mockDlssd);

            Path mockDlssnr = tmp.resolve(DlssRayReconstruction.NGX_DLSSNR_DLL);
            Files.writeString(mockDlssnr, "mock");
            check(DlssRayReconstruction.isDlssdDllPresent(tmp), "dlssnr file present: true");
            Files.deleteIfExists(mockDlssnr);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void realNativeBindingIfPresent() {
        String path = System.getProperty("vkbridge.test.dlssWrapper");
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            System.out.println("    ([DLSS-RR] wrapper binding check SKIPPED: no -Dvkbridge.test.dlssWrapper given)");
            return;
        }
        try (DlssRayReconstruction rr = DlssRayReconstruction.load(Path.of(path))) {
            int[] outSup = new int[1];
            DlssNative.Status status = rr.querySupportStatus(outSup);
            // Before vkb_dlss_init, native call returns ERR_NOT_INITIALIZED (code 2), not crashing
            check(status.code() == 2, "query before init returns ERR_NOT_INITIALIZED (code 2): " + status);

            // Verify error path contracts: createFeature with null cmd or before init
            long[] outFeat = new long[1];
            DlssRrParams.CreateInfo dummyCi = new DlssRrParams.CreateInfo(
                1280, 720, 1920, 1080, DlssQuality.QUALITY, 0, false, 0, 0);
            DlssNative.Status createStatus = rr.createFeature(0, dummyCi, outFeat);
            check(!createStatus.ok(), "createFeature(0, ...) before init fails gracefully: " + createStatus);

            // Release with 0 handle fails gracefully
            DlssNative.Status relStatus = rr.releaseFeature(0);
            check(!relStatus.ok(), "releaseFeature(0) fails gracefully: " + relStatus);

            System.out.println("    ([DLSS-RR] wrapper binding OK: all RR exports resolved, error paths verified, pre-init status=" + status + ")");
        }
    }

    public static void geometricNormalsAndRoughnessPacking() {
        // 1. Setting defaults, toggles and ADVANCED_KEYS
        check(RtSettings.dlssRrGeometricNormals, "dlssRrGeometricNormals default true");
        check(RtSettings.dlssRrGeometricNormals(), "dlssRrGeometricNormals() returns true");
        check(RtSettings.ADVANCED_KEYS.contains("dlss_rr_geometric_normals"), "ADVANCED_KEYS contains dlss_rr_geometric_normals");

        RtSettings.applyKeyValue("dlss_rr_geometric_normals", "false");
        check(!RtSettings.dlssRrGeometricNormals(), "dlssRrGeometricNormals false after applyKeyValue");
        RtSettings.applyKeyValue("dlss_rr_geometric_normals", "true");
        check(RtSettings.dlssRrGeometricNormals(), "dlssRrGeometricNormals true after applyKeyValue");

        // 2. FLAG_GEOMETRIC_NORMALS bit validation
        check(RtFrameParams.FLAG_GEOMETRIC_NORMALS == (1 << 11), "FLAG_GEOMETRIC_NORMALS == 1 << 11 (2048)");
        int allFlags = RtFrameParams.FLAG_SHADOWS | RtFrameParams.FLAG_SOFT_SHADOWS | RtFrameParams.FLAG_REFLECTIONS
            | RtFrameParams.FLAG_AO | RtFrameParams.FLAG_EMISSIVE | RtFrameParams.FLAG_MATERIALS
            | RtFrameParams.FLAG_ACCUMULATE | RtFrameParams.FLAG_NORMAL_MAPS | RtFrameParams.FLAG_AUTO_EXPOSURE
            | RtFrameParams.FLAG_GI | RtFrameParams.FLAG_AUTO_NORMALS;
        check((allFlags & RtFrameParams.FLAG_GEOMETRIC_NORMALS) == 0, "FLAG_GEOMETRIC_NORMALS does not collide with existing flags");

        // 3. Normal & roughness ranges and packing contracts
        check(DlssRrParams.ROUGHNESS_PACKED == 1, "ROUGHNESS_PACKED == 1");
        float[] ng = {0.0f, 1.0f, 0.0f}; // flat cube top face
        float[] ns = {0.1f, 0.98f, 0.1f}; // perturbed texture shading normal
        float[] packedWithGeom = packNormalRoughness(true, ng, ns, 0.2f);
        check(packedWithGeom[0] == 0.0f && packedWithGeom[1] == 1.0f && packedWithGeom[2] == 0.0f, "geom normals output ng exactly");
        check(Math.abs(packedWithGeom[3] - 0.2f) < 1e-5f, "roughness packed in w channel");

        float[] packedWithShading = packNormalRoughness(false, ng, ns, 0.05f);
        check(packedWithShading[0] != 0.0f, "shading normals output perturbed normal");
        check(Math.abs(packedWithShading[3] - 0.05f) < 1e-5f, "roughness 0.05 preserved");

        // Roughness clamping validation: must stay in [0.0, 1.0]
        float[] clampedLow = packNormalRoughness(true, ng, ns, -0.5f);
        check(clampedLow[3] == 0.0f, "negative roughness clamped to 0.0");
        float[] clampedHigh = packNormalRoughness(true, ng, ns, 1.5f);
        check(clampedHigh[3] == 1.0f, "excess roughness clamped to 1.0");

        // 4. SPIR-V Shader compilation with VKB_GBUFFER verifying syntax and bindings
        ByteBuffer pathGb = RtShaderCompiler.compileResource(RtPipeline.PATH_SHADER_PATH, "rt_path.comp+gbuffer",
            RtPipeline.GBUFFER_DEFINE);
        check(pathGb != null && pathGb.remaining() > 1000 && pathGb.getInt(0) == 0x07230203, "rt_path.comp+gbuffer compiles to valid SPIR-V");

        ByteBuffer raysGb = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp+gbuffer",
            RtPipeline.GBUFFER_DEFINE);
        check(raysGb != null && raysGb.remaining() > 1000 && raysGb.getInt(0) == 0x07230203, "rt_rays.comp+gbuffer compiles to valid SPIR-V");
    }

    private static float[] packNormalRoughness(boolean geometricNormals, float[] ng, float[] ns, float roughness) {
        float[] nOut = geometricNormals ? ng : ns;
        float len = (float) Math.sqrt(nOut[0] * nOut[0] + nOut[1] * nOut[1] + nOut[2] * nOut[2]);
        float nx = len > 1e-4f ? nOut[0] / len : 0.0f;
        float ny = len > 1e-4f ? nOut[1] / len : 0.0f;
        float nz = len > 1e-4f ? nOut[2] / len : 1.0f;
        float r = Math.max(0.0f, Math.min(1.0f, roughness));
        return new float[] {nx, ny, nz, r};
    }

    public static void horizonBendingAndReflectionContinuity() {
        // 1. Math check of PBR Horizon Bending formula
        float[] ng = {0.0f, 1.0f, 0.0f}; // horizontal water surface

        // Reflection vector dipping below horizontal plane
        float[] rBelow = {0.8f, -0.2f, 0.0f};
        float[] rBent = bendHorizon(rBelow, ng);
        float dotBent = dot(rBent, ng);
        check(dotBent >= 0.0009f, "reflection bent above horizon: " + dotBent);
        float len = (float) Math.sqrt(rBent[0] * rBent[0] + rBent[1] * rBent[1] + rBent[2] * rBent[2]);
        check(Math.abs(len - 1.0f) < 1e-4f, "bent vector is normalized: " + len);

        // Reflection vector already well above horizon: unchanged
        float[] rAbove = {0.6f, 0.8f, 0.0f};
        float[] rKept = bendHorizon(rAbove, ng);
        check(Math.abs(rKept[0] - rAbove[0]) < 1e-5f && Math.abs(rKept[1] - rAbove[1]) < 1e-5f,
            "reflection above horizon unchanged");

        // Extreme downward reflection vector: bends smoothly
        float[] rDown = {0.0f, -1.0f, 0.0f};
        float[] rDownBent = bendHorizon(rDown, ng);
        check(dot(rDownBent, ng) > 0.0f, "extreme downward vector bent above horizon");

        // 2. Shader source validation: rt_common.glsl, rt_path.comp, rt_rays.comp
        String commonSrc = RtWorldChecks.rtCommon();
        check(commonSrc.contains("vec3 bendHorizon(vec3 r, vec3 ng)"), "rt_common.glsl defines bendHorizon");

        String pathSrc = RtShaderCompiler.loadSource("/shaders/rt_path.comp");
        check(pathSrc.contains("r = bendHorizon(r, s.ng);"), "rt_path.comp applies bendHorizon");
        check(!pathSrc.contains("if (dot(r, s.ng) <= 0.0) {\n                break;\n            }"),
            "rt_path.comp no longer breaks ray on dot(r, s.ng) <= 0");

        String raysSrc = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        check(raysSrc.contains("rdir = bendHorizon(rdir, s.ng);"), "rt_rays.comp applies bendHorizon");
        check(!raysSrc.contains("rdir = reflect(rdir, s.ng);"), "rt_rays.comp no longer uses abrupt reflect(rdir, s.ng)");

        // 3. Shaders compile cleanly to SPIR-V
        ByteBuffer pathSpv = RtShaderCompiler.compileResource(RtPipeline.PATH_SHADER_PATH, "rt_path.comp");
        check(pathSpv != null && pathSpv.remaining() > 0, "rt_path.comp with bendHorizon compiles to SPIR-V");
        ByteBuffer raysSpv = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp");
        check(raysSpv != null && raysSpv.remaining() > 0, "rt_rays.comp with bendHorizon compiles to SPIR-V");
    }

    private static float[] bendHorizon(float[] r, float[] ng) {
        float ndotr = dot(r, ng);
        if (ndotr < 0.001f) {
            float minVal = Math.min(0.0f, ndotr);
            float x = r[0] - ng[0] * minVal + ng[0] * 0.001f;
            float y = r[1] - ng[1] * minVal + ng[1] * 0.001f;
            float z = r[2] - ng[2] * minVal + ng[2] * 0.001f;
            float l = (float) Math.sqrt(x * x + y * y + z * z);
            return new float[] {x / l, y / l, z / l};
        }
        return new float[] {r[0], r[1], r[2]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static void velocityMotionVectorIntegration() throws Exception {
        Class<?> clazz = DlssRrDenoiser.class;
        check(clazz.getMethod("velocityPipeline") != null, "velocityPipeline getter exists");
        check(clazz.getMethod("record", org.lwjgl.vulkan.VkCommandBuffer.class, Matrix4f.class, Matrix4f.class,
            double.class, double.class, double.class, boolean.class, float.class, long.class) != null,
            "record with depthView parameter exists");

        // Verify camera motion vector reprojection math
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
        Matrix4f view = new Matrix4f().lookAt(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f);
        Matrix4f currVp = new Matrix4f(proj).mul(view);
        Matrix4f invVp = new Matrix4f();
        currVp.invert(invVp);

        // Previous camera shifted by (0.5, 0.0, 0.0)
        Matrix4f prevView = new Matrix4f().lookAt(0.5f, 0.0f, 0.0f, 0.5f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f);
        Matrix4f prevVp = new Matrix4f(proj).mul(prevView);

        // Check unproject -> reproject displacement
        Vector4f clipCenter = new Vector4f(0.0f, 0.0f, 0.5f, 1.0f);
        Vector4f worldRel = new Vector4f(clipCenter).mul(invVp);
        worldRel.div(worldRel.w);

        Vector4f prevPos = new Vector4f(worldRel.x - 0.5f, worldRel.y, worldRel.z, 1.0f);
        Vector4f prevClip = new Vector4f(prevPos).mul(prevVp);
        prevClip.div(prevClip.w);

        float dx = prevClip.x - clipCenter.x;
        check(Math.abs(dx) > 1e-4f, "camera movement produces non-zero motion displacement: " + dx);

        // Verify VelocityPipeline shader compiles
        ByteBuffer spv = VelocityPipeline.compileShaderToSpirv();
        check(spv != null && spv.remaining() > 0, "velocity_reproject.comp compiles to SPIR-V");
    }
}
