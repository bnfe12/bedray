#include "bedray_scene.h"
#include <windows.h>
#include <cstdio>

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) return 1;
    HMODULE library = LoadLibraryW(argv[1]);
    if (!library) return 2;
    const char* exports[] = {
        "bedray_scene_abi_version", "bedray_scene_abi_info", "bedray_scene_create",
        "bedray_scene_update", "bedray_scene_update_table", "bedray_scene_set_view",
        "bedray_scene_prepare_frame", "bedray_scene_release_frame",
        "bedray_scene_query_resources", "bedray_scene_destroy"
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
    const auto info = reinterpret_cast<decltype(&bedray_scene_abi_info)>(
        GetProcAddress(library, "bedray_scene_abi_info"));
    BedraySceneAbiInfo abi{};
    info(&abi);
    const bool valid = version() == BEDRAY_SCENE_ABI_VERSION
        && abi.sizeof_section_record == sizeof(BedraySceneSectionRecord)
        && abi.sizeof_resources == sizeof(BedraySceneResources);
    FreeLibrary(library);
    if (!valid) return 4;
    std::puts("core DLL exports the scene ABI v2 (no GPU)");
    return 0;
}
