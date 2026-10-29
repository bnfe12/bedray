package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * Java mirror of {@code native/bedray_core/include/bedray_core.h} (ABI v2).
 *
 * <p>The C header is the source of truth. Every size and offset here is checked
 * against the values the loaded DLL reports from {@code bedray_core_abi_info};
 * a mismatch is a hard load failure, not a silent reinterpretation of memory.
 */
public final class BedrayCoreLayouts {
    public static final int ABI_VERSION = 2;
    public static final int ERROR_MESSAGE_BYTES = 1024;

    /** {@code BedrayStatus}. */
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_UNSUPPORTED = 2;
    public static final int STATUS_VULKAN = 3;
    public static final int STATUS_OUT_OF_MEMORY = 4;
    public static final int STATUS_DEVICE_LOST = 5;
    public static final int STATUS_ABI_MISMATCH = 6;
    public static final int STATUS_INTERNAL = 7;
    public static final int STATUS_IN_USE = 8;

    /** {@code BedrayCapability} bits. */
    public static final int CAP_RAY_QUERY = 1 << 0;
    public static final int CAP_ACCELERATION_STRUCTURE = 1 << 1;
    public static final int CAP_BUFFER_DEVICE_ADDRESS = 1 << 2;
    public static final int CAP_DESCRIPTOR_INDEXING = 1 << 3;
    public static final int CAP_SYNCHRONIZATION2 = 1 << 4;
    public static final int CAP_TIMELINE_SEMAPHORE = 1 << 5;
    public static final int CAP_DYNAMIC_RENDERING = 1 << 6;
    public static final int CAP_OPACITY_MICROMAP = 1 << 7;
    public static final int CAP_DESCRIPTOR_BUFFER = 1 << 8;
    /** {@code shaderInt64}; required by physical-storage-buffer addressing and 64-bit shader work. */
    public static final int CAP_SHADER_INT64 = 1 << 9;
    /** Every capability bit this ABI version defines. */
    public static final int CAP_ALL = (1 << 10) - 1;

    /** {@code VkShaderStageFlagBits#VK_SHADER_STAGE_COMPUTE_BIT}; ABI v2 registers compute programs only. */
    public static final int VK_SHADER_STAGE_COMPUTE_BIT = 0x20;

    /** {@code VkImageLayout#VK_IMAGE_LAYOUT_GENERAL}; the RT renderer keeps every storage image in GENERAL. */
    public static final int VK_IMAGE_LAYOUT_GENERAL = 1;

    /** {@code VkImageLayout#VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}. */
    public static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;

    /** {@code VkDescriptorType} values accepted by ABI v2. */
    public static final int DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;
    public static final int DESCRIPTOR_TYPE_SAMPLED_IMAGE = 2;
    public static final int DESCRIPTOR_TYPE_STORAGE_IMAGE = 3;
    public static final int DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;
    public static final int DESCRIPTOR_TYPE_STORAGE_BUFFER = 7;
    public static final int DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR = 1000150000;

    /** {@code BEDRAY_PATH_TRACE_FRAME_*} frame bits of the additive path-trace ABI. */
    public static final int PATH_TRACE_FRAME_RESET_HISTORY = 1 << 0;
    public static final int PATH_TRACE_FRAME_ACCUMULATE = 1 << 1;
    public static final int PATH_TRACE_FRAME_CAMERA_STATIC = 1 << 2;
    public static final int PATH_TRACE_FRAME_FLIP_Y = 1 << 3;
    public static final int PATH_TRACE_FRAME_SPATIAL = 1 << 4;
    /** {@code scene.sections} is bedray_scene's 64-byte GeometrySection table and the trace uses path_trace_scene.slang. */
    public static final int PATH_TRACE_FRAME_NATIVE_SCENE = 1 << 5;
    public static final int PATH_TRACE_FRAME_NATIVE_DI = 1 << 6;
    public static final int PATH_TRACE_FRAME_RESET_DI = 1 << 7;
    public static final int PATH_TRACE_FEATURE_NATIVE_DI = 1 << 0;
    public static final int PATH_TRACE_FEATURE_GUIDE_BINDINGS = 1 << 1;
    public static final int PATH_TRACE_FEATURE_GUIDE_OUTPUTS = 1 << 2;
    public static final int PATH_TRACE_FEATURE_NRD_MOTION = 1 << 3;

    /** {@code BEDRAY_PATH_DISPLAY_*} bits; the pass adds the ones it derives itself. */
    public static final int PATH_DISPLAY_ACCUMULATE = 1 << 0;
    public static final int PATH_DISPLAY_TONEMAP = 1 << 1;
    public static final int PATH_DISPLAY_REPROJECT = 1 << 2;
    public static final int PATH_DISPLAY_CAMERA_STATIC = 1 << 3;
    public static final int PATH_DISPLAY_FLIP_Y = 1 << 4;

    /** {@code BEDRAY_PATH_TRACE_DI_*} bits of {@code BedrayPathTracePath.di_state[0]}. */
    public static final int PATH_TRACE_DI_ENABLED = 1 << 0;
    public static final int PATH_TRACE_DI_HISTORY = 1 << 1;
    public static final int PATH_TRACE_DI_STATIC = 1 << 2;
    public static final int PATH_TRACE_DI_FLIP_Y = 1 << 3;

    static final StructLayout ERROR = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("status"),
        MemoryLayout.sequenceLayout(ERROR_MESSAGE_BYTES, ValueLayout.JAVA_BYTE).withName("message")
    ).withName("BedrayError");

    static final StructLayout ABI_INFO = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("sizeof_core_desc"),
        ValueLayout.JAVA_INT.withName("sizeof_capabilities"),
        ValueLayout.JAVA_INT.withName("sizeof_program_desc"),
        ValueLayout.JAVA_INT.withName("sizeof_frame_desc"),
        ValueLayout.JAVA_INT.withName("sizeof_resource"),
        ValueLayout.JAVA_INT.withName("sizeof_error"),
        ValueLayout.JAVA_INT.withName("reserved")
    ).withName("BedrayAbiInfo");

    static final StructLayout CORE_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("instance"),
        ValueLayout.JAVA_LONG.withName("physical_device"),
        ValueLayout.JAVA_LONG.withName("device"),
        ValueLayout.JAVA_LONG.withName("queue"),
        ValueLayout.JAVA_INT.withName("queue_family"),
        ValueLayout.JAVA_INT.withName("api_version"),
        ValueLayout.JAVA_LONG.withName("get_instance_proc_addr"),
        ValueLayout.JAVA_LONG.withName("get_device_proc_addr"),
        ValueLayout.JAVA_INT.withName("capabilities"),
        ValueLayout.JAVA_INT.withName("reserved")
    ).withName("BedrayCoreDesc");

    static final StructLayout CAPABILITIES = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_INT.withName("max_push_constants"),
        ValueLayout.JAVA_INT.withName("max_compute_work_group_count_x"),
        ValueLayout.JAVA_INT.withName("max_compute_work_group_count_y"),
        ValueLayout.JAVA_INT.withName("max_compute_work_group_count_z"),
        ValueLayout.JAVA_INT.withName("max_compute_work_group_invocations"),
        ValueLayout.JAVA_INT.withName("reserved"),
        ValueLayout.JAVA_LONG.withName("max_storage_buffer_range"),
        ValueLayout.JAVA_LONG.withName("max_uniform_buffer_range"),
        ValueLayout.JAVA_LONG.withName("device_memory_bytes")
    ).withName("BedrayCapabilities");

    static final StructLayout DESCRIPTOR_BINDING = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("binding"),
        ValueLayout.JAVA_INT.withName("descriptor_type"),
        ValueLayout.JAVA_INT.withName("descriptor_count"),
        ValueLayout.JAVA_INT.withName("stage_flags")
    ).withName("BedrayDescriptorBinding");

    static final StructLayout PROGRAM_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.ADDRESS.withName("name"),
        ValueLayout.ADDRESS.withName("entry_point"),
        ValueLayout.ADDRESS.withName("spirv"),
        ValueLayout.JAVA_LONG.withName("spirv_word_count"),
        ValueLayout.ADDRESS.withName("bindings"),
        ValueLayout.JAVA_INT.withName("binding_count"),
        ValueLayout.JAVA_INT.withName("push_constant_bytes")
    ).withName("BedrayProgramDesc");

    static final StructLayout RESOURCE = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("binding"),
        ValueLayout.JAVA_INT.withName("descriptor_type"),
        ValueLayout.JAVA_INT.withName("image_layout"),
        ValueLayout.JAVA_INT.withName("reserved"),
        ValueLayout.JAVA_LONG.withName("handle"),
        ValueLayout.JAVA_LONG.withName("sampler"),
        ValueLayout.JAVA_LONG.withName("offset"),
        ValueLayout.JAVA_LONG.withName("range")
    ).withName("BedrayResource");

    static final StructLayout FRAME_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("command_buffer"),
        ValueLayout.JAVA_LONG.withName("program"),
        ValueLayout.JAVA_INT.withName("group_count_x"),
        ValueLayout.JAVA_INT.withName("group_count_y"),
        ValueLayout.JAVA_INT.withName("group_count_z"),
        ValueLayout.JAVA_INT.withName("resource_count"),
        ValueLayout.ADDRESS.withName("resources"),
        ValueLayout.ADDRESS.withName("push_constants"),
        ValueLayout.JAVA_INT.withName("push_constant_bytes"),
        ValueLayout.JAVA_INT.withName("reserved")
    ).withName("BedrayFrameDesc");

    // ---- additive path-trace ABI (see the "Additive path-trace pass ABI" block
    // in bedray_core.h). These layouts do not touch any ABI v2 struct above.

    public static final StructLayout PT_GUIDES_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("struct_size"),
        ValueLayout.JAVA_LONG.withName("view_z"),
        ValueLayout.JAVA_LONG.withName("normal_rough"),
        ValueLayout.JAVA_LONG.withName("specular"),
        ValueLayout.JAVA_LONG.withName("diffuse"),
        ValueLayout.JAVA_LONG.withName("albedo"),
        ValueLayout.JAVA_LONG.withName("direct"),
        ValueLayout.JAVA_LONG.withName("specular_albedo")
    ).withName("BedrayPathTraceGuides");

    public static final StructLayout PT_BUFFER = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("handle"),
        ValueLayout.JAVA_LONG.withName("offset"),
        ValueLayout.JAVA_LONG.withName("bytes"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_INT.withName("reserved")
    ).withName("BedrayPathTraceBuffer");

    public static final StructLayout PT_RANGE = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("handle"),
        ValueLayout.JAVA_LONG.withName("offset"),
        ValueLayout.JAVA_LONG.withName("bytes")
    ).withName("BedrayPathTraceRange");

    public static final StructLayout PT_SCENE = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("tlas"),
        PT_BUFFER.withName("sections"),
        ValueLayout.JAVA_LONG.withName("albedo_view"),
        ValueLayout.JAVA_LONG.withName("material_view"),
        ValueLayout.JAVA_LONG.withName("normal_view"),
        ValueLayout.JAVA_LONG.withName("atlas_sampler"),
        PT_BUFFER.withName("block_materials"),
        PT_BUFFER.withName("emitters"),
        PT_BUFFER.withName("reservoirs"),
        PT_RANGE.withName("entity_positions"),
        PT_RANGE.withName("entity_indices"),
        PT_RANGE.withName("entity_attrs"),
        PT_RANGE.withName("entity_rects"),
        PT_RANGE.withName("entity_texels"),
        PT_RANGE.withName("entity_previous")
    ).withName("BedrayPathTraceScene");

    public static final StructLayout PT_CAMERA = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("view"),
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("projection"),
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("view_projection"),
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("inv_view_projection"),
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("prev_view_projection"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("origin"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("forward"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("right"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("up"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("jitter"),
        ValueLayout.JAVA_INT.withName("width"),
        ValueLayout.JAVA_INT.withName("height"),
        ValueLayout.JAVA_INT.withName("display_width"),
        ValueLayout.JAVA_INT.withName("display_height")
    ).withName("BedrayPathTraceCamera");

    public static final StructLayout PT_LIGHTING = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("sun_direction"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("sun_color"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("moon_direction"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("moon_color"),
        ValueLayout.JAVA_FLOAT.withName("exposure"),
        ValueLayout.JAVA_FLOAT.withName("max_radiance"),
        ValueLayout.JAVA_FLOAT.withName("reprojection_tolerance"),
        ValueLayout.JAVA_INT.withName("reserved")
    ).withName("BedrayPathTraceLighting");

    public static final StructLayout PT_ATMOSPHERE = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("zenith_color"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("horizon_color"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("fog_color"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("fog"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("cloud")
    ).withName("BedrayPathTraceAtmosphere");

    public static final StructLayout PT_PATH = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("max_depth"),
        ValueLayout.JAVA_INT.withName("max_surface_steps"),
        ValueLayout.JAVA_INT.withName("sample_index"),
        ValueLayout.JAVA_INT.withName("material_count"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_FLOAT.withName("ray_bias"),
        ValueLayout.JAVA_FLOAT.withName("alpha_cutoff"),
        ValueLayout.JAVA_FLOAT.withName("max_distance"),
        ValueLayout.JAVA_FLOAT.withName("max_radiance"),
        ValueLayout.JAVA_FLOAT.withName("sun_probability"),
        ValueLayout.JAVA_INT.withName("emitter_count"),
        ValueLayout.JAVA_FLOAT.withName("emitter_probability"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("emitter_camera_relative"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("di_camera_delta"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT).withName("di_state"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT).withName("entity_state")
    ).withName("BedrayPathTracePath");

    public static final StructLayout PT_FRAME_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("command_buffer"),
        ValueLayout.JAVA_LONG.withName("output_view"),
        ValueLayout.JAVA_LONG.withName("depth_view"),
        PT_SCENE.withName("scene"),
        PT_CAMERA.withName("camera"),
        PT_LIGHTING.withName("lighting"),
        PT_ATMOSPHERE.withName("atmosphere"),
        PT_PATH.withName("path"),
        ValueLayout.JAVA_INT.withName("frame_index"),
        ValueLayout.JAVA_INT.withName("samples_per_frame"),
        ValueLayout.JAVA_INT.withName("accum_frames"),
        ValueLayout.JAVA_INT.withName("display_flags"),
        ValueLayout.JAVA_FLOAT.withName("frame_time"),
        MemoryLayout.sequenceLayout(5, ValueLayout.JAVA_INT).withName("reserved")
    ).withName("BedrayPathTraceFrameDesc");

    public static final StructLayout PT_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("program_trace"),
        ValueLayout.JAVA_LONG.withName("program_spatial"),
        ValueLayout.JAVA_LONG.withName("program_display"),
        ValueLayout.JAVA_LONG.withName("image_budget_bytes"),
        ValueLayout.JAVA_LONG.withName("buffer_budget_bytes"),
        ValueLayout.JAVA_INT.withName("width"),
        ValueLayout.JAVA_INT.withName("height"),
        ValueLayout.JAVA_INT.withName("reserved0"),
        ValueLayout.JAVA_INT.withName("reserved1"),
        ValueLayout.JAVA_LONG.withName("program_trace_scene"),
        ValueLayout.JAVA_LONG.withName("reserved")
    ).withName("BedrayPathTraceDesc");

    static final long ERROR_STATUS = ERROR.byteOffset(groupElement("status"));
    static final long ERROR_MESSAGE = ERROR.byteOffset(groupElement("message"));

    static final long AI_ABI_VERSION = ABI_INFO.byteOffset(groupElement("abi_version"));
    static final long AI_SIZEOF_CORE_DESC = ABI_INFO.byteOffset(groupElement("sizeof_core_desc"));
    static final long AI_SIZEOF_CAPABILITIES = ABI_INFO.byteOffset(groupElement("sizeof_capabilities"));
    static final long AI_SIZEOF_PROGRAM_DESC = ABI_INFO.byteOffset(groupElement("sizeof_program_desc"));
    static final long AI_SIZEOF_FRAME_DESC = ABI_INFO.byteOffset(groupElement("sizeof_frame_desc"));
    static final long AI_SIZEOF_RESOURCE = ABI_INFO.byteOffset(groupElement("sizeof_resource"));
    static final long AI_SIZEOF_ERROR = ABI_INFO.byteOffset(groupElement("sizeof_error"));

    static final long CD_ABI_VERSION = CORE_DESC.byteOffset(groupElement("abi_version"));
    static final long CD_INSTANCE = CORE_DESC.byteOffset(groupElement("instance"));
    static final long CD_PHYSICAL_DEVICE = CORE_DESC.byteOffset(groupElement("physical_device"));
    static final long CD_DEVICE = CORE_DESC.byteOffset(groupElement("device"));
    static final long CD_QUEUE = CORE_DESC.byteOffset(groupElement("queue"));
    static final long CD_QUEUE_FAMILY = CORE_DESC.byteOffset(groupElement("queue_family"));
    static final long CD_API_VERSION = CORE_DESC.byteOffset(groupElement("api_version"));
    static final long CD_GET_INSTANCE_PROC_ADDR = CORE_DESC.byteOffset(groupElement("get_instance_proc_addr"));
    static final long CD_GET_DEVICE_PROC_ADDR = CORE_DESC.byteOffset(groupElement("get_device_proc_addr"));
    static final long CD_CAPABILITIES = CORE_DESC.byteOffset(groupElement("capabilities"));

    static final long CAP_ABI_VERSION = CAPABILITIES.byteOffset(groupElement("abi_version"));
    static final long CAP_FLAGS = CAPABILITIES.byteOffset(groupElement("flags"));
    static final long CAP_MAX_PUSH_CONSTANTS = CAPABILITIES.byteOffset(groupElement("max_push_constants"));
    static final long CAP_MAX_GROUP_COUNT_X = CAPABILITIES.byteOffset(groupElement("max_compute_work_group_count_x"));
    static final long CAP_MAX_GROUP_COUNT_Y = CAPABILITIES.byteOffset(groupElement("max_compute_work_group_count_y"));
    static final long CAP_MAX_GROUP_COUNT_Z = CAPABILITIES.byteOffset(groupElement("max_compute_work_group_count_z"));
    static final long CAP_MAX_INVOCATIONS = CAPABILITIES.byteOffset(groupElement("max_compute_work_group_invocations"));
    static final long CAP_MAX_STORAGE_RANGE = CAPABILITIES.byteOffset(groupElement("max_storage_buffer_range"));
    static final long CAP_MAX_UNIFORM_RANGE = CAPABILITIES.byteOffset(groupElement("max_uniform_buffer_range"));
    static final long CAP_DEVICE_MEMORY = CAPABILITIES.byteOffset(groupElement("device_memory_bytes"));

    static final long DB_BINDING = DESCRIPTOR_BINDING.byteOffset(groupElement("binding"));
    static final long DB_DESCRIPTOR_TYPE = DESCRIPTOR_BINDING.byteOffset(groupElement("descriptor_type"));
    static final long DB_DESCRIPTOR_COUNT = DESCRIPTOR_BINDING.byteOffset(groupElement("descriptor_count"));
    static final long DB_STAGE_FLAGS = DESCRIPTOR_BINDING.byteOffset(groupElement("stage_flags"));

    static final long PD_ABI_VERSION = PROGRAM_DESC.byteOffset(groupElement("abi_version"));
    static final long PD_NAME = PROGRAM_DESC.byteOffset(groupElement("name"));
    static final long PD_ENTRY_POINT = PROGRAM_DESC.byteOffset(groupElement("entry_point"));
    static final long PD_SPIRV = PROGRAM_DESC.byteOffset(groupElement("spirv"));
    static final long PD_SPIRV_WORD_COUNT = PROGRAM_DESC.byteOffset(groupElement("spirv_word_count"));
    static final long PD_BINDINGS = PROGRAM_DESC.byteOffset(groupElement("bindings"));
    static final long PD_BINDING_COUNT = PROGRAM_DESC.byteOffset(groupElement("binding_count"));
    static final long PD_PUSH_CONSTANT_BYTES = PROGRAM_DESC.byteOffset(groupElement("push_constant_bytes"));

    static final long R_BINDING = RESOURCE.byteOffset(groupElement("binding"));
    static final long R_DESCRIPTOR_TYPE = RESOURCE.byteOffset(groupElement("descriptor_type"));
    static final long R_IMAGE_LAYOUT = RESOURCE.byteOffset(groupElement("image_layout"));
    static final long R_HANDLE = RESOURCE.byteOffset(groupElement("handle"));
    static final long R_SAMPLER = RESOURCE.byteOffset(groupElement("sampler"));
    static final long R_OFFSET = RESOURCE.byteOffset(groupElement("offset"));
    static final long R_RANGE = RESOURCE.byteOffset(groupElement("range"));

    static final long FD_ABI_VERSION = FRAME_DESC.byteOffset(groupElement("abi_version"));
    static final long FD_COMMAND_BUFFER = FRAME_DESC.byteOffset(groupElement("command_buffer"));
    static final long FD_PROGRAM = FRAME_DESC.byteOffset(groupElement("program"));
    static final long FD_GROUP_COUNT_X = FRAME_DESC.byteOffset(groupElement("group_count_x"));
    static final long FD_GROUP_COUNT_Y = FRAME_DESC.byteOffset(groupElement("group_count_y"));
    static final long FD_GROUP_COUNT_Z = FRAME_DESC.byteOffset(groupElement("group_count_z"));
    static final long FD_RESOURCE_COUNT = FRAME_DESC.byteOffset(groupElement("resource_count"));
    static final long FD_RESOURCES = FRAME_DESC.byteOffset(groupElement("resources"));
    static final long FD_PUSH_CONSTANTS = FRAME_DESC.byteOffset(groupElement("push_constants"));
    static final long FD_PUSH_CONSTANT_BYTES = FRAME_DESC.byteOffset(groupElement("push_constant_bytes"));

    // ---- path-trace offsets

    public static final long PTFD_ABI_VERSION = PT_FRAME_DESC.byteOffset(groupElement("abi_version"));
    public static final long PTFD_FLAGS = PT_FRAME_DESC.byteOffset(groupElement("flags"));
    public static final long PTFD_COMMAND_BUFFER = PT_FRAME_DESC.byteOffset(groupElement("command_buffer"));
    public static final long PTFD_OUTPUT_VIEW = PT_FRAME_DESC.byteOffset(groupElement("output_view"));
    public static final long PTFD_DEPTH_VIEW = PT_FRAME_DESC.byteOffset(groupElement("depth_view"));
    public static final long PTFD_SCENE = PT_FRAME_DESC.byteOffset(groupElement("scene"));
    public static final long PTFD_CAMERA = PT_FRAME_DESC.byteOffset(groupElement("camera"));
    public static final long PTFD_LIGHTING = PT_FRAME_DESC.byteOffset(groupElement("lighting"));
    public static final long PTFD_ATMOSPHERE = PT_FRAME_DESC.byteOffset(groupElement("atmosphere"));
    public static final long PTFD_PATH = PT_FRAME_DESC.byteOffset(groupElement("path"));
    public static final long PTFD_FRAME_INDEX = PT_FRAME_DESC.byteOffset(groupElement("frame_index"));
    public static final long PTFD_SAMPLES_PER_FRAME = PT_FRAME_DESC.byteOffset(groupElement("samples_per_frame"));
    public static final long PTFD_ACCUM_FRAMES = PT_FRAME_DESC.byteOffset(groupElement("accum_frames"));
    public static final long PTFD_DISPLAY_FLAGS = PT_FRAME_DESC.byteOffset(groupElement("display_flags"));
    public static final long PTFD_FRAME_TIME = PT_FRAME_DESC.byteOffset(groupElement("frame_time"));

    public static final long PTS_TLAS = PT_SCENE.byteOffset(groupElement("tlas"));
    public static final long PTS_SECTIONS = PT_SCENE.byteOffset(groupElement("sections"));
    public static final long PTS_ALBEDO_VIEW = PT_SCENE.byteOffset(groupElement("albedo_view"));
    public static final long PTS_MATERIAL_VIEW = PT_SCENE.byteOffset(groupElement("material_view"));
    public static final long PTS_NORMAL_VIEW = PT_SCENE.byteOffset(groupElement("normal_view"));
    public static final long PTS_ATLAS_SAMPLER = PT_SCENE.byteOffset(groupElement("atlas_sampler"));
    public static final long PTS_BLOCK_MATERIALS = PT_SCENE.byteOffset(groupElement("block_materials"));
    public static final long PTS_EMITTERS = PT_SCENE.byteOffset(groupElement("emitters"));
    public static final long PTS_RESERVOIRS = PT_SCENE.byteOffset(groupElement("reservoirs"));
    public static final long PTS_ENTITY_POSITIONS = PT_SCENE.byteOffset(groupElement("entity_positions"));
    public static final long PTS_ENTITY_INDICES = PT_SCENE.byteOffset(groupElement("entity_indices"));
    public static final long PTS_ENTITY_ATTRS = PT_SCENE.byteOffset(groupElement("entity_attrs"));
    public static final long PTS_ENTITY_RECTS = PT_SCENE.byteOffset(groupElement("entity_rects"));
    public static final long PTS_ENTITY_TEXELS = PT_SCENE.byteOffset(groupElement("entity_texels"));
    public static final long PTS_ENTITY_PREVIOUS = PT_SCENE.byteOffset(groupElement("entity_previous"));

    public static final long PTB_HANDLE = PT_BUFFER.byteOffset(groupElement("handle"));
    public static final long PTB_OFFSET = PT_BUFFER.byteOffset(groupElement("offset"));
    public static final long PTB_BYTES = PT_BUFFER.byteOffset(groupElement("bytes"));

    public static final long PTR_HANDLE = PT_RANGE.byteOffset(groupElement("handle"));
    public static final long PTR_OFFSET = PT_RANGE.byteOffset(groupElement("offset"));
    public static final long PTR_BYTES = PT_RANGE.byteOffset(groupElement("bytes"));

    public static final long PTC_VIEW = PT_CAMERA.byteOffset(groupElement("view"));
    public static final long PTC_PROJECTION = PT_CAMERA.byteOffset(groupElement("projection"));
    public static final long PTC_VIEW_PROJECTION = PT_CAMERA.byteOffset(groupElement("view_projection"));
    public static final long PTC_INV_VIEW_PROJECTION = PT_CAMERA.byteOffset(groupElement("inv_view_projection"));
    public static final long PTC_PREV_VIEW_PROJECTION = PT_CAMERA.byteOffset(groupElement("prev_view_projection"));
    public static final long PTC_ORIGIN = PT_CAMERA.byteOffset(groupElement("origin"));
    public static final long PTC_FORWARD = PT_CAMERA.byteOffset(groupElement("forward"));
    public static final long PTC_RIGHT = PT_CAMERA.byteOffset(groupElement("right"));
    public static final long PTC_UP = PT_CAMERA.byteOffset(groupElement("up"));
    public static final long PTC_JITTER = PT_CAMERA.byteOffset(groupElement("jitter"));
    public static final long PTC_WIDTH = PT_CAMERA.byteOffset(groupElement("width"));
    public static final long PTC_HEIGHT = PT_CAMERA.byteOffset(groupElement("height"));
    public static final long PTC_DISPLAY_WIDTH = PT_CAMERA.byteOffset(groupElement("display_width"));
    public static final long PTC_DISPLAY_HEIGHT = PT_CAMERA.byteOffset(groupElement("display_height"));

    public static final long PTL_SUN_DIRECTION = PT_LIGHTING.byteOffset(groupElement("sun_direction"));
    public static final long PTL_SUN_COLOR = PT_LIGHTING.byteOffset(groupElement("sun_color"));
    public static final long PTL_MOON_DIRECTION = PT_LIGHTING.byteOffset(groupElement("moon_direction"));
    public static final long PTL_MOON_COLOR = PT_LIGHTING.byteOffset(groupElement("moon_color"));
    public static final long PTL_EXPOSURE = PT_LIGHTING.byteOffset(groupElement("exposure"));
    public static final long PTL_MAX_RADIANCE = PT_LIGHTING.byteOffset(groupElement("max_radiance"));
    public static final long PTL_REPROJECTION_TOLERANCE = PT_LIGHTING.byteOffset(groupElement("reprojection_tolerance"));

    public static final long PTA_ZENITH_COLOR = PT_ATMOSPHERE.byteOffset(groupElement("zenith_color"));
    public static final long PTA_HORIZON_COLOR = PT_ATMOSPHERE.byteOffset(groupElement("horizon_color"));
    public static final long PTA_FOG_COLOR = PT_ATMOSPHERE.byteOffset(groupElement("fog_color"));
    public static final long PTA_FOG = PT_ATMOSPHERE.byteOffset(groupElement("fog"));
    public static final long PTA_CLOUD = PT_ATMOSPHERE.byteOffset(groupElement("cloud"));

    public static final long PTP_MAX_DEPTH = PT_PATH.byteOffset(groupElement("max_depth"));
    public static final long PTP_MAX_SURFACE_STEPS = PT_PATH.byteOffset(groupElement("max_surface_steps"));
    public static final long PTP_SAMPLE_INDEX = PT_PATH.byteOffset(groupElement("sample_index"));
    public static final long PTP_MATERIAL_COUNT = PT_PATH.byteOffset(groupElement("material_count"));
    public static final long PTP_FLAGS = PT_PATH.byteOffset(groupElement("flags"));
    public static final long PTP_RAY_BIAS = PT_PATH.byteOffset(groupElement("ray_bias"));
    public static final long PTP_ALPHA_CUTOFF = PT_PATH.byteOffset(groupElement("alpha_cutoff"));
    public static final long PTP_MAX_DISTANCE = PT_PATH.byteOffset(groupElement("max_distance"));
    public static final long PTP_MAX_RADIANCE = PT_PATH.byteOffset(groupElement("max_radiance"));
    public static final long PTP_SUN_PROBABILITY = PT_PATH.byteOffset(groupElement("sun_probability"));
    public static final long PTP_EMITTER_COUNT = PT_PATH.byteOffset(groupElement("emitter_count"));
    public static final long PTP_EMITTER_PROBABILITY = PT_PATH.byteOffset(groupElement("emitter_probability"));
    public static final long PTP_EMITTER_CAMERA_RELATIVE = PT_PATH.byteOffset(groupElement("emitter_camera_relative"));
    public static final long PTP_DI_CAMERA_DELTA = PT_PATH.byteOffset(groupElement("di_camera_delta"));
    public static final long PTP_DI_STATE = PT_PATH.byteOffset(groupElement("di_state"));
    public static final long PTP_ENTITY_STATE = PT_PATH.byteOffset(groupElement("entity_state"));

    public static final long PTD_ABI_VERSION = PT_DESC.byteOffset(groupElement("abi_version"));
    public static final long PTD_PROGRAM_TRACE = PT_DESC.byteOffset(groupElement("program_trace"));
    public static final long PTD_PROGRAM_SPATIAL = PT_DESC.byteOffset(groupElement("program_spatial"));
    public static final long PTD_PROGRAM_DISPLAY = PT_DESC.byteOffset(groupElement("program_display"));
    public static final long PTD_IMAGE_BUDGET_BYTES = PT_DESC.byteOffset(groupElement("image_budget_bytes"));
    public static final long PTD_BUFFER_BUDGET_BYTES = PT_DESC.byteOffset(groupElement("buffer_budget_bytes"));
    public static final long PTD_WIDTH = PT_DESC.byteOffset(groupElement("width"));
    public static final long PTD_HEIGHT = PT_DESC.byteOffset(groupElement("height"));
    public static final long PTD_PROGRAM_TRACE_SCENE = PT_DESC.byteOffset(groupElement("program_trace_scene"));

    private BedrayCoreLayouts() {
    }

    /** Fails when the JDK-computed layout no longer matches the C header. */
    static void verify() {
        require("BedrayError", ERROR.byteSize(), 1028);
        require("BedrayAbiInfo", ABI_INFO.byteSize(), 32);
        require("BedrayCoreDesc", CORE_DESC.byteSize(), 72);
        require("BedrayCapabilities", CAPABILITIES.byteSize(), 56);
        require("BedrayDescriptorBinding", DESCRIPTOR_BINDING.byteSize(), 16);
        require("BedrayProgramDesc", PROGRAM_DESC.byteSize(), 56);
        require("BedrayResource", RESOURCE.byteSize(), 48);
        require("BedrayFrameDesc", FRAME_DESC.byteSize(), 64);
        require("BedrayPathTraceBuffer", PT_BUFFER.byteSize(), 32);
        require("BedrayPathTraceRange", PT_RANGE.byteSize(), 24);
        require("BedrayPathTraceScene", PT_SCENE.byteSize(), 312);
        require("BedrayPathTraceCamera", PT_CAMERA.byteSize(), 416);
        require("BedrayPathTraceLighting", PT_LIGHTING.byteSize(), 80);
        require("BedrayPathTraceAtmosphere", PT_ATMOSPHERE.byteSize(), 80);
        require("BedrayPathTracePath", PT_PATH.byteSize(), 112);
        require("BedrayPathTraceGuides", PT_GUIDES_DESC.byteSize(), 64);
        require("BedrayPathTraceFrameDesc", PT_FRAME_DESC.byteSize(), 1072);
        require("BedrayPathTraceDesc", PT_DESC.byteSize(), 80);
        require("BedrayPathTraceDesc.program_trace_scene", PTD_PROGRAM_TRACE_SCENE, 64);
        require("BedrayPathTraceFrameDesc.scene", PTFD_SCENE, 32);
        require("BedrayPathTraceFrameDesc.camera", PTFD_CAMERA, 344);
        require("BedrayPathTraceFrameDesc.lighting", PTFD_LIGHTING, 760);
        require("BedrayPathTraceFrameDesc.atmosphere", PTFD_ATMOSPHERE, 840);
        require("BedrayPathTraceFrameDesc.path", PTFD_PATH, 920);
        require("BedrayPathTraceFrameDesc.frame_index", PTFD_FRAME_INDEX, 1032);
        require("BedrayPathTraceFrameDesc.frame_time", PTFD_FRAME_TIME, 1048);
        require("BedrayPathTraceScene.sections", PTS_SECTIONS, 8);
        require("BedrayPathTraceScene.albedo_view", PTS_ALBEDO_VIEW, 40);
        require("BedrayPathTraceScene.block_materials", PTS_BLOCK_MATERIALS, 72);
        require("BedrayPathTraceScene.emitters", PTS_EMITTERS, 104);
        require("BedrayPathTraceScene.reservoirs", PTS_RESERVOIRS, 136);
        require("BedrayPathTraceScene.entity_positions", PTS_ENTITY_POSITIONS, 168);
        require("BedrayPathTracePath.emitter_camera_relative", PTP_EMITTER_CAMERA_RELATIVE, 48);
        require("BedrayPathTracePath.di_camera_delta", PTP_DI_CAMERA_DELTA, 64);
        require("BedrayPathTracePath.di_state", PTP_DI_STATE, 80);
        require("BedrayPathTracePath.entity_state", PTP_ENTITY_STATE, 96);
    }

    private static void require(String name, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException("ABI v2 layout drift: " + name + " is " + actual + " bytes, expected " + expected);
        }
    }

    /** Size of one {@code BedrayResource} element, for callers allocating an array. */
    public static long resourceSize() {
        return RESOURCE.byteSize();
    }

    /** Size of one {@code BedrayDescriptorBinding} element, for callers allocating an array. */
    public static long descriptorBindingSize() {
        return DESCRIPTOR_BINDING.byteSize();
    }

    /**
     * Maps a {@code VkDescriptorType} name (as written in a generated Slang manifest) to its Vulkan
     * value. Names are Vulkan's own, not a Bedray invention, so the mapping is stable.
     */
    public static int descriptorTypeId(String name) {
        return switch (name) {
            case "COMBINED_IMAGE_SAMPLER" -> DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case "SAMPLED_IMAGE" -> DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case "STORAGE_IMAGE" -> DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case "UNIFORM_BUFFER" -> DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case "STORAGE_BUFFER" -> DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case "ACCELERATION_STRUCTURE_KHR" -> DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
            default -> throw new IllegalArgumentException("ABI v2 cannot register descriptor type " + name);
        };
    }

    /** Inverse of {@link #descriptorTypeId(String)}, for diagnostics. */
    public static String descriptorTypeName(int id) {
        return switch (id) {
            case DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER -> "COMBINED_IMAGE_SAMPLER";
            case DESCRIPTOR_TYPE_SAMPLED_IMAGE -> "SAMPLED_IMAGE";
            case DESCRIPTOR_TYPE_STORAGE_IMAGE -> "STORAGE_IMAGE";
            case DESCRIPTOR_TYPE_UNIFORM_BUFFER -> "UNIFORM_BUFFER";
            case DESCRIPTOR_TYPE_STORAGE_BUFFER -> "STORAGE_BUFFER";
            case DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR -> "ACCELERATION_STRUCTURE_KHR";
            default -> "descriptor type " + id;
        };
    }
}
