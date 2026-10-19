package io.github.andrexarlay.vulkancompat.rt.denoise;

/**
 * Byte layouts of the POD structs of native/nrd_bridge/include/vkb_nrd.h (all fields are 4- or 8-byte aligned; the C side
 * static_asserts the same sizes in vkb_nrd.cpp:54-60). {@link #verify()} is called by the tests and by {@link NrdNative}.
 */
public final class NrdLayouts {
    private NrdLayouts() {
    }

    // VkbNrdLibraryInfo: 11 x uint32
    public static final int LIBRARY_INFO_SIZE = 44;
    public static final int LI_VERSION_MAJOR = 4, LI_VERSION_MINOR = 8, LI_VERSION_BUILD = 12, LI_NORMAL_ENCODING = 16,
        LI_ROUGHNESS_ENCODING = 20, LI_NORMAL_ROUGHNESS_VK_FORMAT = 24;

    // VkbNrdResource: u64 image, u64 view, u32 format, u32 reserved
    public static final int RESOURCE_SIZE = 24;
    public static final int RES_IMAGE = 0, RES_VIEW = 8, RES_FORMAT = 16;

    // VkbNrdCreateInfo: struct_size, width, height, mode_mask, frames_in_flight, flags
    public static final int CREATE_INFO_SIZE = 24;

    // VkbNrdFrameSettings
    public static final int FRAME_SETTINGS_SIZE = 344;
    public static final int FS_FRAME_INDEX = 4, FS_ACCUMULATION_MODE = 8, FS_FLAGS = 12, FS_VIEW_TO_CLIP = 16,
        FS_VIEW_TO_CLIP_PREV = 80, FS_WORLD_TO_VIEW = 144, FS_WORLD_TO_VIEW_PREV = 208, FS_MV_SCALE = 272, FS_MV_BIAS = 284,
        FS_JITTER = 296, FS_JITTER_PREV = 304, FS_RECT = 312, FS_RECT_PREV = 320, FS_VIEW_Z_SCALE = 328,
        FS_TIME_DELTA_MS = 332, FS_DENOISING_RANGE = 336, FS_DISOCCLUSION_THRESHOLD = 340;

    // VkbNrdReblurSettings: 13 x 4 bytes
    public static final int REBLUR_SETTINGS_SIZE = 52;
    // VkbNrdSigmaSettings: 6 x 4 bytes
    public static final int SIGMA_SETTINGS_SIZE = 24;

    // VkbNrdDispatchStats
    public static final int DISPATCH_STATS_SIZE = 40;
    public static final int DS_DISPATCH_COUNT = 4, DS_CLEAR_COUNT = 8, DS_SETS = 12, DS_CONSTANT_BYTES = 16, DS_PIPELINES = 20,
        DS_POOL_MEMORY = 24, DS_CPU_MS = 32;

    /** Number of VkbNrdResource slots (VKB_NRD_RES_COUNT). */
    public static final int RES_COUNT = 32;

    // slots used by the RT path (VKB_NRD_RES_*)
    public static final int RES_IN_MV = 0, RES_IN_NORMAL_ROUGHNESS = 1, RES_IN_VIEWZ = 2, RES_IN_DIFF_RADIANCE_HITDIST = 6,
        RES_IN_SPEC_RADIANCE_HITDIST = 7, RES_OUT_DIFF_RADIANCE_HITDIST = 18, RES_OUT_SPEC_RADIANCE_HITDIST = 19;

    // VKB_NRD_FRAME_* flags, VKB_NRD_ACCUM_*
    public static final int FRAME_MV_WORLD_SPACE = 1, FRAME_ENABLE_VALIDATION = 2, FRAME_HISTORY_CONFIDENCE = 4;
    public static final int ACCUM_CONTINUE = 0, ACCUM_RESTART = 1, ACCUM_CLEAR_AND_RESTART = 2;

    /** Expected API version of vkb_nrd.dll (VKB_NRD_API_VERSION 0.1.0). */
    public static final int API_VERSION = (0 << 16) | (1 << 8) | 0;

    /** Throws when a constant above is inconsistent (guards against editing one offset only). */
    public static void verify() {
        if (LI_NORMAL_ROUGHNESS_VK_FORMAT + 4 + 16 != LIBRARY_INFO_SIZE) {
            throw new IllegalStateException("VkbNrdLibraryInfo layout");
        }
        if (FS_DISOCCLUSION_THRESHOLD + 4 != FRAME_SETTINGS_SIZE || FS_VIEW_TO_CLIP + 64 != FS_VIEW_TO_CLIP_PREV
            || FS_VIEW_TO_CLIP_PREV + 64 != FS_WORLD_TO_VIEW || FS_WORLD_TO_VIEW + 64 != FS_WORLD_TO_VIEW_PREV
            || FS_WORLD_TO_VIEW_PREV + 64 != FS_MV_SCALE || FS_MV_SCALE + 12 != FS_MV_BIAS || FS_MV_BIAS + 12 != FS_JITTER
            || FS_JITTER + 8 != FS_JITTER_PREV || FS_JITTER_PREV + 8 != FS_RECT || FS_RECT + 8 != FS_RECT_PREV
            || FS_RECT_PREV + 8 != FS_VIEW_Z_SCALE) {
            throw new IllegalStateException("VkbNrdFrameSettings layout");
        }
        if (DS_POOL_MEMORY + 8 != DS_CPU_MS || DS_CPU_MS + 8 != DISPATCH_STATS_SIZE) {
            throw new IllegalStateException("VkbNrdDispatchStats layout");
        }
    }
}
