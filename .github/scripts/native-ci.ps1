param(
    [Parameter(Mandatory)][string]$SourceRoot,
    [Parameter(Mandatory)][string]$Headers,
    [Parameter(Mandatory)][string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
$BuildRoot = (Resolve-Path -LiteralPath $BuildRoot).Path
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
$Headers = (Resolve-Path -LiteralPath $Headers).Path
$log = Join-Path $BuildRoot 'commands.log'

function Invoke-Checked([string]$Exe, [string[]]$Arguments) {
    "`n> $Exe $($Arguments -join ' ')" | Tee-Object -FilePath $log -Append
    & $Exe @Arguments 2>&1 | Tee-Object -FilePath $log -Append
    $code = $LASTEXITCODE
    "exit_code=$code" | Tee-Object -FilePath $log -Append
    if ($code -ne 0) { throw "$Exe exited with $code" }
}

Invoke-Checked 'cmake' @('--version')
Invoke-Checked 'ctest' @('--version')
Invoke-Checked 'git' @('-C', $SourceRoot, 'rev-parse', 'HEAD')
foreach ($module in @('bedray_core', 'bedray_scene', 'bedray_frame_lifetime')) {
    $build = Join-Path $BuildRoot $module
    $options = @('-S', (Join-Path $SourceRoot "native/$module"), '-B', $build,
        '-G', 'Visual Studio 17 2022', '-A', 'x64')
    if ($module -ne 'bedray_frame_lifetime') {
        $options += "-DBEDRAY_VULKAN_HEADERS=$Headers"
    }
    if ($module -eq 'bedray_core') {
        $options += @('-DBEDRAY_ENABLE_RENDER_GRAPH=ON', '-DBEDRAY_GRAPH_BUILD_TESTS=ON',
            '-DBEDRAY_HISTORY_BUILD_TESTS=ON', '-DBEDRAY_GPU_RESOURCES_TESTS=ON',
            '-DBEDRAY_GPU_RESOURCES_SHARED_ABI=ON')
    }
    if ($module -eq 'bedray_frame_lifetime') { $options += '-DBUILD_TESTING=ON' }
    Invoke-Checked 'cmake' $options
    Invoke-Checked 'cmake' @('--build', $build, '--config', 'Release', '--parallel', '2')
    Invoke-Checked 'ctest' @('--test-dir', $build, '-C', 'Release', '--output-on-failure',
        '--no-tests=error', '--output-junit', (Join-Path $build 'ctest.xml'))
    # The baseline did not register the probe with CTest; run it on both refs.
    if ($module -eq 'bedray_core') {
        Invoke-Checked (Join-Path $build 'Release/bedray_abi_probe.exe') @()
    }
}
