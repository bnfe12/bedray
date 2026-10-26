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
