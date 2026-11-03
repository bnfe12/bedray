// Prints the ABI surface that the Java FFM layer mirrors and fails when a
// structure size drifts from the documented v1 layout.

#include "bedray_core.h"

#include <cstdio>

namespace {

int failures = 0;

void expect(const char* name, unsigned long long actual, unsigned long long expected) {
    const bool ok = actual == expected;
    std::printf("%-28s %6llu  expected %6llu  %s\n",
        name, actual, expected, ok ? "ok" : "MISMATCH");
    if (!ok) {
        ++failures;
    }
}

} // namespace

int main() {
    std::printf("bedray_core ABI %u\n\n", bedray_core_abi_version());

    BedrayAbiInfo info{};
    bedray_core_abi_info(&info);

    expect("BedrayError", sizeof(BedrayError), 1028);
    expect("BedrayAbiInfo", sizeof(BedrayAbiInfo), 32);
    expect("BedrayCoreDesc", sizeof(BedrayCoreDesc), 72);
    expect("BedrayCapabilities", sizeof(BedrayCapabilities), 56);
    expect("BedrayDescriptorBinding", sizeof(BedrayDescriptorBinding), 16);
    expect("BedrayProgramDesc", sizeof(BedrayProgramDesc), 56);
    expect("BedrayResource", sizeof(BedrayResource), 48);
    expect("BedrayFrameDesc", sizeof(BedrayFrameDesc), 64);

    std::printf("\nreported by bedray_core_abi_info:\n");
    expect("abi_version", info.abi_version, BEDRAY_CORE_ABI_VERSION);
    expect("sizeof_core_desc", info.sizeof_core_desc, sizeof(BedrayCoreDesc));
    expect("sizeof_capabilities", info.sizeof_capabilities, sizeof(BedrayCapabilities));
    expect("sizeof_program_desc", info.sizeof_program_desc, sizeof(BedrayProgramDesc));
    expect("sizeof_frame_desc", info.sizeof_frame_desc, sizeof(BedrayFrameDesc));
    expect("sizeof_resource", info.sizeof_resource, sizeof(BedrayResource));
    expect("sizeof_error", info.sizeof_error, sizeof(BedrayError));

    if (failures != 0) {
        std::printf("\n%d ABI check(s) failed\n", failures);
        return 1;
    }
    std::printf("\nABI layout matches version %u\n", BEDRAY_CORE_ABI_VERSION);
    return 0;
}
