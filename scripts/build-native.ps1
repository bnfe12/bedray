# Builds the native bridges via their build.bat files and optionally runs the probes.
#   native/dlss_bridge: vkb_dlss.dll + dlss_probe.exe (always)
#   native/nrd_bridge:  NRD.dll (build_nrd.bat, only if missing) + vkb_nrd.dll + nrd_probe.exe (with -Nrd)
# Usage: scripts\build-native.ps1 [-Probe] [-Nrd] [-RebuildNrd]
#   -Probe       run dlss_probe (and nrd_probe with -Nrd) after the build
#   -Nrd         additionally build the NRD bridge (needs NRD sources, see docs/DEVELOPMENT.md)
#   -RebuildNrd  with -Nrd: run build_nrd.bat even if native\nrd_bridge\build\NRD.dll already exists
# Env overrides: VKB_NGX_SDK, VKB_VK_HEADERS, VKB_VCVARS, VKB_NRD_SRC, VKB_CMAKE, VKB_NINJA (see docs/DEVELOPMENT.md).
param([switch]$Probe, [switch]$Nrd, [switch]$RebuildNrd)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# ---- DLSS bridge ----
$bridge = Join-Path $root 'native\dlss_bridge'
$bat = Join-Path $bridge 'build.bat'

& cmd.exe /c "`"$bat`""
if ($LASTEXITCODE -ne 0) { throw "build.bat failed with exit code $LASTEXITCODE" }

$dll = Join-Path $bridge 'build\vkb_dlss.dll'
$exe = Join-Path $bridge 'build\dlss_probe.exe'
if (-not (Test-Path $dll) -or -not (Test-Path $exe)) { throw 'build.bat reported success but outputs are missing' }
Write-Host "built: $dll"
Write-Host "built: $exe"

if ($Probe) {
    & $exe
    if ($LASTEXITCODE -ne 0) { throw "dlss_probe failed with exit code $LASTEXITCODE" }
}

# ---- NRD bridge ----
if ($Nrd) {
    $nrdDir = Join-Path $root 'native\nrd_bridge'
    $nrdDll = Join-Path $nrdDir 'build\NRD.dll'
    if ($RebuildNrd -or -not (Test-Path $nrdDll)) {
        & cmd.exe /c "`"$(Join-Path $nrdDir 'build_nrd.bat')`""
        if ($LASTEXITCODE -ne 0) { throw "build_nrd.bat failed with exit code $LASTEXITCODE" }
    }
    & cmd.exe /c "`"$(Join-Path $nrdDir 'build.bat')`""
    if ($LASTEXITCODE -ne 0) { throw "nrd_bridge build.bat failed with exit code $LASTEXITCODE" }

    $vdll = Join-Path $nrdDir 'build\vkb_nrd.dll'
    $pexe = Join-Path $nrdDir 'build\nrd_probe.exe'
    foreach ($f in @($nrdDll, $vdll, $pexe)) {
        if (-not (Test-Path $f)) { throw "NRD build reported success but $f is missing" }
        Write-Host "built: $f"
    }
    if ($Probe) {
        & $pexe
        if ($LASTEXITCODE -ne 0) { throw "nrd_probe failed with exit code $LASTEXITCODE" }
    }
}
