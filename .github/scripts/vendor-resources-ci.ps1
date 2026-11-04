param(
    [Parameter(Mandatory)][string]$SourceRoot,
    [Parameter(Mandatory)][string]$Headers,
    [Parameter(Mandatory)][string]$BuildRoot,
    [Parameter(Mandatory)][string]$DependencyRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ngxRevision = '9981c6350dc58c0ce21f024fd65e31e851c31689' # NVIDIA/DLSS v310.9.16
$slRevision = '2122257e0fce486f91b385aa63b9a09b0a34b363' # NVIDIA-RTX/Streamline v2.14.1
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
$Headers = (Resolve-Path -LiteralPath $Headers).Path
New-Item -ItemType Directory -Force -Path $BuildRoot, $DependencyRoot | Out-Null
$BuildRoot = (Resolve-Path -LiteralPath $BuildRoot).Path
$DependencyRoot = (Resolve-Path -LiteralPath $DependencyRoot).Path
if ($DependencyRoot.Equals($SourceRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $DependencyRoot.StartsWith($SourceRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'DependencyRoot must be outside the source checkout'
}
$log = Join-Path $BuildRoot 'commands.log'
function Invoke-Checked([string]$Exe, [string[]]$Arguments) {
    "> $Exe $($Arguments -join ' ')" | Tee-Object -FilePath $log -Append | Out-Host
    & $Exe @Arguments 2>&1 | Tee-Object -FilePath $log -Append | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$Exe exited with $LASTEXITCODE" }
}
function Get-PinnedSdk([string]$Name, [string]$Repository, [string]$Revision, [string[]]$Paths) {
    $target = Join-Path $DependencyRoot $Name
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    if (!(Test-Path -LiteralPath (Join-Path $target '.git'))) {
        Invoke-Checked 'git' @('-C', $target, 'init', '--quiet')
        Invoke-Checked 'git' @('-C', $target, 'remote', 'add', 'origin', "https://github.com/$Repository.git")
    }
    $remote = (& git -C $target remote get-url origin).Trim()
    if ($LASTEXITCODE -ne 0 -or $remote -ne "https://github.com/$Repository.git") { throw "Unexpected SDK origin at $target" }
    Invoke-Checked 'git' @('-C', $target, 'sparse-checkout', 'init', '--cone')
    Invoke-Checked 'git' (@('-C', $target, 'sparse-checkout', 'set') + $Paths)
    Invoke-Checked 'git' @('-C', $target, 'fetch', '--depth', '1', '--filter=blob:none', 'origin', $Revision)
    Invoke-Checked 'git' @('-C', $target, 'checkout', '--detach', $Revision)
    $actual = (& git -C $target rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -ne $Revision) { throw "SDK revision mismatch at $target" }
    return $target
}
# Downloaded SDK content stays outside the source checkout and is never packaged in artifacts.
$ngx = Get-PinnedSdk 'DLSS' 'NVIDIA/DLSS' $ngxRevision @('include', 'lib/Windows_x86_64/x64')
$sl = Get-PinnedSdk 'Streamline' 'NVIDIA-RTX/Streamline' $slRevision @('include')
$sourceRevision = (& git -C $SourceRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source revision' }
Invoke-Checked 'cmake' @('-S', (Join-Path $SourceRoot 'native/vendor_resource_tests'), '-B', $BuildRoot,
    '-G', 'Visual Studio 17 2022', '-A', 'x64', "-DVKB_NGX_SDK=$ngx", "-DVKB_SL_SDK=$sl", "-DBEDRAY_VULKAN_HEADERS=$Headers")
Invoke-Checked 'cmake' @('--build', $BuildRoot, '--config', 'Release', '--parallel', '2')
Invoke-Checked 'ctest' @('--test-dir', $BuildRoot, '-C', 'Release', '--output-on-failure', '--no-tests=error',
    '--output-junit', (Join-Path $BuildRoot 'ctest.xml'))
$runtime = Join-Path $BuildRoot 'runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$artifacts = @(
    @{ source='production/ngx/Release/vkb_dlss.dll'; name='vkb_dlss.dll'; kind='production-ngx' },
    @{ source='production/sl/Release/vkb_sl.dll'; name='vkb_sl.dll'; kind='production-streamline' },
    @{ source='Release/vendor_ngx_fault.dll'; name='vendor_ngx_fault.dll'; kind='cpu-fault-test-only' },
    @{ source='Release/vendor_sl_fault.dll'; name='vendor_sl_fault.dll'; kind='cpu-fault-test-only' }
)
$files = foreach ($artifact in $artifacts) {
    $inputFile = Join-Path $BuildRoot $artifact.source
    Copy-Item -LiteralPath $inputFile -Destination (Join-Path $runtime $artifact.name)
    [ordered]@{ name=$artifact.name; kind=$artifact.kind; sha256=(Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash.ToLowerInvariant() }
}
[ordered]@{
    schema=1; revision=$sourceRevision; platform='windows-x64-release'; resource_contract_version=1
    ngx_sdk_revision=$ngxRevision; streamline_sdk_revision=$slRevision
    actual_wrapper_cpu_contracts='passed'; gpu_validated=$false; artifacts=@($files)
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runtime 'manifest.json') -Encoding utf8NoBOM
