package io.github.andrexarlay.vulkancompat.rt.scene;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * Java mirror of {@code native/bedray_scene/include/bedray_scene.h} (ABI v2).
 *
 * <p>The C header is the source of truth. {@code structLayout} does not insert C
 * padding, so every hole is an explicit field: the layouts below are the byte
 * image the DLL reads, not a convenient Java description of it. Sizes and
 * offsets are checked against {@code bedray_scene_abi_info} when the DLL is
 * loaded, so a header edit that is not mirrored here is a hard load failure.
 *
 * <p>ABI v2 made the section record shader-facing: 64 bytes, a stable section
 * identity in {@code origin.w}, a device address of the {@code GeometryVertex}
 * stream, and three packed pass ranges. The input grew to 96 bytes to carry the
 * section origin, the geometry count, the per-geometry quad starts and the pass
 * bits, because the path tracer no longer reads the Java table layout.
 */
public final class BedraySceneLayouts {
    public static final int ABI_VERSION = 2;
    public static final int ERROR_MESSAGE_BYTES = 1024;
    public static final int MAX_FRAMES_IN_FLIGHT = 8;
    public static final int TABLE_COUNT = 2;
    public static final int MAX_SECTION_GEOMETRIES = 3;
    public static final int SECTION_RECORD_BYTES = 64;
    public static final int INSTANCE_RECORD_BYTES = 64;
    public static final int GEOMETRY_VERTEX_BYTES = 64;

    /** {@code BedraySceneStatus}. */
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_UNSUPPORTED = 2;
    public static final int STATUS_VULKAN = 3;
    public static final int STATUS_OUT_OF_MEMORY = 4;
    public static final int STATUS_DEVICE_LOST = 5;
    public static final int STATUS_ABI_MISMATCH = 6;
    public static final int STATUS_INTERNAL = 7;
    public static final int STATUS_IN_USE = 8;
    public static final int STATUS_STALE = 9;
    public static final int STATUS_UNKNOWN_ID = 10;
    public static final int STATUS_LIMIT = 11;

    /** {@code BedraySceneCapability} bits; the first two are required by the DLL. */
    public static final int CAP_ACCELERATION_STRUCTURE = 1 << 0;
    public static final int CAP_DEVICE_ADDRESS = 1 << 1;
    public static final int CAP_AS_UPDATE = 1 << 2;

    /** {@code BedraySceneTableKind}. */
    public static final int TABLE_MATERIAL = 0;
    public static final int TABLE_LIGHT = 1;

    /** {@code BedraySceneIndexType}. */
    public static final int INDEX_NONE = 0;
    public static final int INDEX_UINT16 = 1;
    public static final int INDEX_UINT32 = 2;

    /** {@code BedraySceneSectionInput.flags}: the stream is 64-byte {@code GeometryVertex} records. */
    public static final int SECTION_FLAG_GEOMETRY_VERTEX = 1 << 0;

    /** {@code BEDRAY_SCENE_FRAME_*} output flags of {@code bedray_scene_prepare_frame}. */
    public static final int FRAME_TLAS_REBUILT = 1 << 0;
    public static final int FRAME_TLAS_UPDATED = 1 << 1;
    public static final int FRAME_BLAS_REBUILT = 1 << 2;
    public static final int FRAME_BLAS_REFIT = 1 << 3;
    public static final int FRAME_UPLOADED = 1 << 4;
    public static final int FRAME_TABLES_UPLOADED = 1 << 5;
    public static final int FRAME_IDLE = 1 << 6;

    static final StructLayout ERROR = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("status"),
        MemoryLayout.sequenceLayout(ERROR_MESSAGE_BYTES, ValueLayout.JAVA_BYTE).withName("message")
    ).withName("BedraySceneError");

    static final StructLayout ABI_INFO = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("sizeof_desc"),
        ValueLayout.JAVA_INT.withName("sizeof_section_input"),
        ValueLayout.JAVA_INT.withName("sizeof_entity_input"),
        ValueLayout.JAVA_INT.withName("sizeof_update"),
        ValueLayout.JAVA_INT.withName("sizeof_table_desc"),
        ValueLayout.JAVA_INT.withName("sizeof_frame_desc"),
        ValueLayout.JAVA_INT.withName("sizeof_frame_info"),
        ValueLayout.JAVA_INT.withName("sizeof_resources"),
        ValueLayout.JAVA_INT.withName("sizeof_section_record"),
        ValueLayout.JAVA_INT.withName("sizeof_error"),
        ValueLayout.JAVA_INT.withName("reserved")
    ).withName("BedraySceneAbiInfo");

    public static final StructLayout DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("instance"),
        ValueLayout.JAVA_LONG.withName("physical_device"),
        ValueLayout.JAVA_LONG.withName("device"),
        ValueLayout.JAVA_LONG.withName("get_instance_proc_addr"),
        ValueLayout.JAVA_LONG.withName("get_device_proc_addr"),
        ValueLayout.JAVA_INT.withName("api_version"),
        ValueLayout.JAVA_INT.withName("capabilities"),
        ValueLayout.JAVA_INT.withName("frames_in_flight"),
        ValueLayout.JAVA_INT.withName("max_sections"),
        ValueLayout.JAVA_INT.withName("max_entities"),
        ValueLayout.JAVA_INT.withName("max_instances"),
        ValueLayout.JAVA_INT.withName("max_table_bytes"),
        ValueLayout.JAVA_INT.withName("reserved"),
        ValueLayout.JAVA_LONG.withName("max_geometry_bytes")
    ).withName("BedraySceneDesc");

    public static final StructLayout SECTION_INPUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("section_id"),
        ValueLayout.JAVA_LONG.withName("geometry_revision"),
        ValueLayout.ADDRESS.withName("vertices"),
        ValueLayout.JAVA_LONG.withName("vertex_count"),
        ValueLayout.JAVA_INT.withName("vertex_stride"),
        ValueLayout.JAVA_INT.withName("index_type"),
        ValueLayout.ADDRESS.withName("indices"),
        ValueLayout.JAVA_LONG.withName("index_count"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT).withName("origin"),
        ValueLayout.JAVA_INT.withName("geometry_count"),
        MemoryLayout.sequenceLayout(MAX_SECTION_GEOMETRIES, ValueLayout.JAVA_INT).withName("quad_start"),
        ValueLayout.JAVA_INT.withName("pass_bits"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_INT.withName("reserved")
    ).withName("BedraySceneSectionInput");

    public static final StructLayout ENTITY_INPUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("entity_id"),
        ValueLayout.JAVA_LONG.withName("geometry_revision"),
        ValueLayout.JAVA_LONG.withName("topology_revision"),
        ValueLayout.ADDRESS.withName("vertices"),
        ValueLayout.JAVA_LONG.withName("vertex_count"),
        ValueLayout.ADDRESS.withName("indices"),
        ValueLayout.JAVA_LONG.withName("index_count"),
        ValueLayout.JAVA_INT.withName("vertex_stride"),
        ValueLayout.JAVA_INT.withName("index_type"),
        ValueLayout.JAVA_INT.withName("instance_mask"),
        ValueLayout.JAVA_INT.withName("reserved"),
        MemoryLayout.sequenceLayout(12, ValueLayout.JAVA_FLOAT).withName("transform")
    ).withName("BedraySceneEntityInput");

    public static final StructLayout UPDATE = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.ADDRESS.withName("sections"),
        ValueLayout.JAVA_INT.withName("section_count"),
        ValueLayout.JAVA_INT.withName("removed_section_count"),
        ValueLayout.ADDRESS.withName("removed_sections"),
        ValueLayout.ADDRESS.withName("entities"),
        ValueLayout.JAVA_INT.withName("entity_count"),
        ValueLayout.JAVA_INT.withName("removed_entity_count"),
        ValueLayout.ADDRESS.withName("removed_entities")
    ).withName("BedraySceneUpdate");

    public static final StructLayout TABLE_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("table_kind"),
        ValueLayout.JAVA_LONG.withName("revision"),
        ValueLayout.ADDRESS.withName("data"),
        ValueLayout.JAVA_LONG.withName("size"),
        ValueLayout.JAVA_LONG.withName("reserved")
    ).withName("BedraySceneTableDesc");

    public static final StructLayout FRAME_DESC = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("frame_index"),
        ValueLayout.JAVA_LONG.withName("command_buffer")
    ).withName("BedraySceneFrameDesc");

    public static final StructLayout FRAME_INFO = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("frame_index"),
        ValueLayout.JAVA_LONG.withName("tlas"),
        ValueLayout.JAVA_LONG.withName("instance_count"),
        ValueLayout.JAVA_LONG.withName("blas_builds"),
        ValueLayout.JAVA_LONG.withName("blas_refits"),
        ValueLayout.JAVA_LONG.withName("deferred_updates"),
        ValueLayout.JAVA_LONG.withName("upload_bytes"),
        ValueLayout.JAVA_LONG.withName("section_count"),
        ValueLayout.JAVA_LONG.withName("reserved")
    ).withName("BedraySceneFrameInfo");

    public static final StructLayout RESOURCES = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("abi_version"),
        ValueLayout.JAVA_INT.withName("section_count"),
        ValueLayout.JAVA_INT.withName("instance_count"),
        ValueLayout.JAVA_INT.withName("reserved"),
        ValueLayout.JAVA_LONG.withName("tlas"),
        ValueLayout.JAVA_LONG.withName("instance_buffer"),
        ValueLayout.JAVA_LONG.withName("instance_buffer_address"),
        ValueLayout.JAVA_LONG.withName("section_table_buffer"),
        ValueLayout.JAVA_LONG.withName("section_table_address"),
        ValueLayout.JAVA_LONG.withName("section_table_bytes"),
        ValueLayout.JAVA_LONG.withName("material_table_buffer"),
        ValueLayout.JAVA_LONG.withName("material_table_address"),
        ValueLayout.JAVA_LONG.withName("material_table_bytes"),
        ValueLayout.JAVA_LONG.withName("light_table_buffer"),
        ValueLayout.JAVA_LONG.withName("light_table_address"),
        ValueLayout.JAVA_LONG.withName("light_table_bytes")
    ).withName("BedraySceneResources");

    public static final StructLayout SECTION_RANGE = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("first_quad"),
        ValueLayout.JAVA_INT.withName("quad_count_pass")
    ).withName("BedraySceneSectionRange");

    public static final StructLayout SECTION_RECORD = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT).withName("origin"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT).withName("meta"),
        ValueLayout.JAVA_LONG.withName("vertex_address"),
        MemoryLayout.sequenceLayout(MAX_SECTION_GEOMETRIES, SECTION_RANGE).withName("ranges")
    ).withName("BedraySceneSectionRecord");

    static final long ERROR_STATUS = ERROR.byteOffset(groupElement("status"));
    static final long ERROR_MESSAGE = ERROR.byteOffset(groupElement("message"));

    static final long AI_ABI_VERSION = ABI_INFO.byteOffset(groupElement("abi_version"));
    static final long AI_SIZEOF_DESC = ABI_INFO.byteOffset(groupElement("sizeof_desc"));
    static final long AI_SIZEOF_SECTION_INPUT = ABI_INFO.byteOffset(groupElement("sizeof_section_input"));
    static final long AI_SIZEOF_ENTITY_INPUT = ABI_INFO.byteOffset(groupElement("sizeof_entity_input"));
    static final long AI_SIZEOF_UPDATE = ABI_INFO.byteOffset(groupElement("sizeof_update"));
    static final long AI_SIZEOF_TABLE_DESC = ABI_INFO.byteOffset(groupElement("sizeof_table_desc"));
    static final long AI_SIZEOF_FRAME_DESC = ABI_INFO.byteOffset(groupElement("sizeof_frame_desc"));
    static final long AI_SIZEOF_FRAME_INFO = ABI_INFO.byteOffset(groupElement("sizeof_frame_info"));
    static final long AI_SIZEOF_RESOURCES = ABI_INFO.byteOffset(groupElement("sizeof_resources"));
    static final long AI_SIZEOF_SECTION_RECORD = ABI_INFO.byteOffset(groupElement("sizeof_section_record"));
    static final long AI_SIZEOF_ERROR = ABI_INFO.byteOffset(groupElement("sizeof_error"));

    static final long CD_ABI_VERSION = DESC.byteOffset(groupElement("abi_version"));
    static final long CD_FLAGS = DESC.byteOffset(groupElement("flags"));
    static final long CD_INSTANCE = DESC.byteOffset(groupElement("instance"));
    static final long CD_PHYSICAL_DEVICE = DESC.byteOffset(groupElement("physical_device"));
    static final long CD_DEVICE = DESC.byteOffset(groupElement("device"));
    static final long CD_GET_INSTANCE_PROC_ADDR = DESC.byteOffset(groupElement("get_instance_proc_addr"));
    static final long CD_GET_DEVICE_PROC_ADDR = DESC.byteOffset(groupElement("get_device_proc_addr"));
    static final long CD_API_VERSION = DESC.byteOffset(groupElement("api_version"));
    static final long CD_CAPABILITIES = DESC.byteOffset(groupElement("capabilities"));
    static final long CD_FRAMES_IN_FLIGHT = DESC.byteOffset(groupElement("frames_in_flight"));
    static final long CD_MAX_SECTIONS = DESC.byteOffset(groupElement("max_sections"));
    static final long CD_MAX_ENTITIES = DESC.byteOffset(groupElement("max_entities"));
    static final long CD_MAX_INSTANCES = DESC.byteOffset(groupElement("max_instances"));
    static final long CD_MAX_TABLE_BYTES = DESC.byteOffset(groupElement("max_table_bytes"));
    static final long CD_MAX_GEOMETRY_BYTES = DESC.byteOffset(groupElement("max_geometry_bytes"));

    public static final long SI_SECTION_ID = SECTION_INPUT.byteOffset(groupElement("section_id"));
    public static final long SI_GEOMETRY_REVISION = SECTION_INPUT.byteOffset(groupElement("geometry_revision"));
    public static final long SI_VERTICES = SECTION_INPUT.byteOffset(groupElement("vertices"));
    public static final long SI_VERTEX_COUNT = SECTION_INPUT.byteOffset(groupElement("vertex_count"));
    public static final long SI_VERTEX_STRIDE = SECTION_INPUT.byteOffset(groupElement("vertex_stride"));
    public static final long SI_INDEX_TYPE = SECTION_INPUT.byteOffset(groupElement("index_type"));
    public static final long SI_INDICES = SECTION_INPUT.byteOffset(groupElement("indices"));
    public static final long SI_INDEX_COUNT = SECTION_INPUT.byteOffset(groupElement("index_count"));
    public static final long SI_ORIGIN = SECTION_INPUT.byteOffset(groupElement("origin"));
    public static final long SI_GEOMETRY_COUNT = SECTION_INPUT.byteOffset(groupElement("geometry_count"));
    public static final long SI_QUAD_START = SECTION_INPUT.byteOffset(groupElement("quad_start"));
    public static final long SI_PASS_BITS = SECTION_INPUT.byteOffset(groupElement("pass_bits"));
    public static final long SI_FLAGS = SECTION_INPUT.byteOffset(groupElement("flags"));

    public static final long EI_ENTITY_ID = ENTITY_INPUT.byteOffset(groupElement("entity_id"));
    public static final long EI_GEOMETRY_REVISION = ENTITY_INPUT.byteOffset(groupElement("geometry_revision"));
    public static final long EI_TOPOLOGY_REVISION = ENTITY_INPUT.byteOffset(groupElement("topology_revision"));
    public static final long EI_VERTICES = ENTITY_INPUT.byteOffset(groupElement("vertices"));
    public static final long EI_VERTEX_COUNT = ENTITY_INPUT.byteOffset(groupElement("vertex_count"));
    public static final long EI_INDICES = ENTITY_INPUT.byteOffset(groupElement("indices"));
    public static final long EI_INDEX_COUNT = ENTITY_INPUT.byteOffset(groupElement("index_count"));
    public static final long EI_VERTEX_STRIDE = ENTITY_INPUT.byteOffset(groupElement("vertex_stride"));
    public static final long EI_INDEX_TYPE = ENTITY_INPUT.byteOffset(groupElement("index_type"));
    public static final long EI_INSTANCE_MASK = ENTITY_INPUT.byteOffset(groupElement("instance_mask"));
    public static final long EI_TRANSFORM = ENTITY_INPUT.byteOffset(groupElement("transform"));

    public static final long U_ABI_VERSION = UPDATE.byteOffset(groupElement("abi_version"));
    public static final long U_FLAGS = UPDATE.byteOffset(groupElement("flags"));
    public static final long U_SECTIONS = UPDATE.byteOffset(groupElement("sections"));
    public static final long U_SECTION_COUNT = UPDATE.byteOffset(groupElement("section_count"));
    public static final long U_REMOVED_SECTION_COUNT = UPDATE.byteOffset(groupElement("removed_section_count"));
    public static final long U_REMOVED_SECTIONS = UPDATE.byteOffset(groupElement("removed_sections"));
    public static final long U_ENTITIES = UPDATE.byteOffset(groupElement("entities"));
    public static final long U_ENTITY_COUNT = UPDATE.byteOffset(groupElement("entity_count"));
    public static final long U_REMOVED_ENTITY_COUNT = UPDATE.byteOffset(groupElement("removed_entity_count"));
    public static final long U_REMOVED_ENTITIES = UPDATE.byteOffset(groupElement("removed_entities"));

    public static final long TD_ABI_VERSION = TABLE_DESC.byteOffset(groupElement("abi_version"));
    public static final long TD_TABLE_KIND = TABLE_DESC.byteOffset(groupElement("table_kind"));
    public static final long TD_REVISION = TABLE_DESC.byteOffset(groupElement("revision"));
    public static final long TD_DATA = TABLE_DESC.byteOffset(groupElement("data"));
    public static final long TD_SIZE = TABLE_DESC.byteOffset(groupElement("size"));

    static final long FD_ABI_VERSION = FRAME_DESC.byteOffset(groupElement("abi_version"));
    static final long FD_FLAGS = FRAME_DESC.byteOffset(groupElement("flags"));
    static final long FD_FRAME_INDEX = FRAME_DESC.byteOffset(groupElement("frame_index"));
    static final long FD_COMMAND_BUFFER = FRAME_DESC.byteOffset(groupElement("command_buffer"));

    static final long FI_ABI_VERSION = FRAME_INFO.byteOffset(groupElement("abi_version"));
    static final long FI_FLAGS = FRAME_INFO.byteOffset(groupElement("flags"));
    static final long FI_FRAME_INDEX = FRAME_INFO.byteOffset(groupElement("frame_index"));
    static final long FI_TLAS = FRAME_INFO.byteOffset(groupElement("tlas"));
    static final long FI_INSTANCE_COUNT = FRAME_INFO.byteOffset(groupElement("instance_count"));
    static final long FI_BLAS_BUILDS = FRAME_INFO.byteOffset(groupElement("blas_builds"));
    static final long FI_BLAS_REFITS = FRAME_INFO.byteOffset(groupElement("blas_refits"));
    static final long FI_DEFERRED_UPDATES = FRAME_INFO.byteOffset(groupElement("deferred_updates"));
    static final long FI_UPLOAD_BYTES = FRAME_INFO.byteOffset(groupElement("upload_bytes"));
    static final long FI_SECTION_COUNT = FRAME_INFO.byteOffset(groupElement("section_count"));

    public static final long R_ABI_VERSION = RESOURCES.byteOffset(groupElement("abi_version"));
    public static final long R_SECTION_COUNT = RESOURCES.byteOffset(groupElement("section_count"));
    public static final long R_INSTANCE_COUNT = RESOURCES.byteOffset(groupElement("instance_count"));
    public static final long R_TLAS = RESOURCES.byteOffset(groupElement("tlas"));
    public static final long R_INSTANCE_BUFFER = RESOURCES.byteOffset(groupElement("instance_buffer"));
    public static final long R_INSTANCE_BUFFER_ADDRESS = RESOURCES.byteOffset(groupElement("instance_buffer_address"));
    public static final long R_SECTION_TABLE_BUFFER = RESOURCES.byteOffset(groupElement("section_table_buffer"));
    public static final long R_SECTION_TABLE_ADDRESS = RESOURCES.byteOffset(groupElement("section_table_address"));
    public static final long R_SECTION_TABLE_BYTES = RESOURCES.byteOffset(groupElement("section_table_bytes"));
    public static final long R_MATERIAL_TABLE_BUFFER = RESOURCES.byteOffset(groupElement("material_table_buffer"));
    public static final long R_MATERIAL_TABLE_ADDRESS = RESOURCES.byteOffset(groupElement("material_table_address"));
    public static final long R_MATERIAL_TABLE_BYTES = RESOURCES.byteOffset(groupElement("material_table_bytes"));
    public static final long R_LIGHT_TABLE_BUFFER = RESOURCES.byteOffset(groupElement("light_table_buffer"));
    public static final long R_LIGHT_TABLE_ADDRESS = RESOURCES.byteOffset(groupElement("light_table_address"));
    public static final long R_LIGHT_TABLE_BYTES = RESOURCES.byteOffset(groupElement("light_table_bytes"));

    private BedraySceneLayouts() {
    }

    /** Fails when the JDK-computed layout no longer matches the C header. */
    static void verify() {
        require("BedraySceneError", ERROR.byteSize(), 1028);
        require("BedraySceneAbiInfo", ABI_INFO.byteSize(), 48);
        require("BedraySceneDesc", DESC.byteSize(), 88);
        require("BedraySceneSectionInput", SECTION_INPUT.byteSize(), 96);
        require("BedraySceneEntityInput", ENTITY_INPUT.byteSize(), 120);
        require("BedraySceneUpdate", UPDATE.byteSize(), 56);
        require("BedraySceneTableDesc", TABLE_DESC.byteSize(), 40);
        require("BedraySceneFrameDesc", FRAME_DESC.byteSize(), 24);
        require("BedraySceneFrameInfo", FRAME_INFO.byteSize(), 80);
        require("BedraySceneResources", RESOURCES.byteSize(), 112);
        require("BedraySceneSectionRange", SECTION_RANGE.byteSize(), 8);
        require("BedraySceneSectionRecord", SECTION_RECORD.byteSize(), 64);
        require("BedraySceneDesc.get_device_proc_addr", CD_GET_DEVICE_PROC_ADDR, 40);
        require("BedraySceneDesc.max_geometry_bytes", CD_MAX_GEOMETRY_BYTES, 80);
        require("BedraySceneSectionInput.vertices", SI_VERTICES, 16);
        require("BedraySceneSectionInput.index_count", SI_INDEX_COUNT, 48);
        require("BedraySceneSectionInput.origin", SI_ORIGIN, 56);
        require("BedraySceneSectionInput.geometry_count", SI_GEOMETRY_COUNT, 68);
        require("BedraySceneSectionInput.quad_start", SI_QUAD_START, 72);
        require("BedraySceneSectionInput.pass_bits", SI_PASS_BITS, 84);
        require("BedraySceneSectionInput.flags", SI_FLAGS, 88);
        require("BedraySceneEntityInput.transform", EI_TRANSFORM, 72);
        require("BedraySceneUpdate.sections", U_SECTIONS, 8);
        require("BedraySceneUpdate.entities", U_ENTITIES, 32);
        require("BedraySceneUpdate.removed_entities", U_REMOVED_ENTITIES, 48);
        require("BedraySceneTableDesc.data", TD_DATA, 16);
        require("BedraySceneResources.section_table_buffer", R_SECTION_TABLE_BUFFER, 40);
        require("BedraySceneResources.material_table_buffer", R_MATERIAL_TABLE_BUFFER, 64);
        require("BedraySceneResources.light_table_buffer", R_LIGHT_TABLE_BUFFER, 88);
    }

    private static void require(String name, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException("bedray_scene ABI v2 layout drift: " + name + " is "
                + actual + " bytes, expected " + expected);
        }
    }

    /** Size of one {@code BedraySceneSectionInput} element, for callers allocating an array. */
    public static long sectionInputSize() {
        return SECTION_INPUT.byteSize();
    }

    /** Size of one {@code BedraySceneEntityInput} element, for callers allocating an array. */
    public static long entityInputSize() {
        return ENTITY_INPUT.byteSize();
    }

    /** Size of one shader-facing {@code BedraySceneSectionRecord}; also the table stride. */
    public static long sectionRecordSize() {
        return SECTION_RECORD.byteSize();
    }

    /**
     * Packs a pass id and a quad count the way {@code BedraySceneSectionRange}
     * does: bits 0..15 count, bits 16..17 pass. The Slang decoder reads the same
     * word, so the packing lives here once.
     */
    public static int quadCountPass(int quadCount, int pass) {
        if (quadCount < 0 || quadCount > 0xFFFF) {
            throw new IllegalArgumentException("quad count " + quadCount + " does not fit 16 bits");
        }
        if (pass < 0 || pass > 2) {
            throw new IllegalArgumentException("pass " + pass + " is not 0..2");
        }
        return (quadCount & 0xFFFF) | ((pass & 0x3) << 16);
    }
}
