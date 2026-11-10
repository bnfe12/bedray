#include "bedray_scene.h"
#include "bedray_core_memory.h"
#include <windows.h>
#include <cstdio>

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) return 1;
    HMODULE library = LoadLibraryW(argv[1]);
    if (!library) return 2;
    const char* exports[] = {
        "bedray_core_discard_recording",
        "bedray_core_memory_version", "bedray_core_memory_configure", "bedray_core_memory_attach", "bedray_core_memory_stats",
        "bedray_scene_abi_version", "bedray_scene_features", "bedray_scene_abi_info", "bedray_scene_create",
        "bedray_scene_update", "bedray_scene_update_table", "bedray_scene_publish_frame_table", "bedray_scene_upload_materials", "bedray_scene_set_view",
        "bedray_scene_select_sections", "bedray_scene_section_ids",
        "bedray_scene_prepare_frame", "bedray_scene_retire_producer",
        "bedray_scene_release_frame", "bedray_scene_discard_frame",
        "bedray_scene_query_resources", "bedray_scene_resident_bytes", "bedray_scene_destroy"
    };
    for (const char* name : exports) {
        if (!GetProcAddress(library, name)) {
            std::fprintf(stderr, "core DLL missing scene export: %s\n", name);
            FreeLibrary(library);
            return 3;
        }
    }
    const auto version = reinterpret_cast<decltype(&bedray_scene_abi_version)>(
        GetProcAddress(library, "bedray_scene_abi_version"));
    const auto features = reinterpret_cast<decltype(&bedray_scene_features)>(
        GetProcAddress(library, "bedray_scene_features"));
    const auto info = reinterpret_cast<decltype(&bedray_scene_abi_info)>(
        GetProcAddress(library, "bedray_scene_abi_info"));
    BedraySceneAbiInfo abi{};
    info(&abi);
    const auto memory_version = reinterpret_cast<decltype(&bedray_core_memory_version)>(
        GetProcAddress(library, "bedray_core_memory_version"));
    const auto configure_memory = reinterpret_cast<decltype(&bedray_core_memory_configure)>(
        GetProcAddress(library, "bedray_core_memory_configure"));
    BedrayError memory_error{};
    const bool valid = (features() & BEDRAY_SCENE_FEATURE_SAFE_AS_UPDATE) != 0 && memory_version() == 1 && sizeof(BedrayMemoryStats) == 72
        && configure_memory(nullptr, 0, 0, 0, &memory_error) == BEDRAY_ERROR_INVALID_ARGUMENT
        && memory_error.message[0] != 0 && version() == BEDRAY_SCENE_ABI_VERSION
        && abi.sizeof_section_record == sizeof(BedraySceneSectionRecord)
        && abi.sizeof_resources == sizeof(BedraySceneResources);
    FreeLibrary(library);
    if (!valid) return 4;
    std::puts("core DLL exports the scene ABI v2 (no GPU)");
    return 0;
}
