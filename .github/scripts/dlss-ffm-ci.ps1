param(
    [Parameter(Mandatory)][string]$SourceRoot,
    [Parameter(Mandatory)][string]$RuntimeRoot,
    [Parameter(Mandatory)][string]$DependencyRoot,
    [Parameter(Mandatory)][string]$EvidenceRoot,
    [string]$PartnerWrapper
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
$RuntimeRoot = (Resolve-Path -LiteralPath $RuntimeRoot).Path
New-Item -ItemType Directory -Force -Path $DependencyRoot, $EvidenceRoot | Out-Null
$DependencyRoot = (Resolve-Path -LiteralPath $DependencyRoot).Path
$EvidenceRoot = (Resolve-Path -LiteralPath $EvidenceRoot).Path
$log = Join-Path $EvidenceRoot 'ffm.log'
function Invoke-Checked([string]$Exe, [string[]]$Arguments) {
    "> $Exe $($Arguments -join ' ')" | Tee-Object -FilePath $log -Append | Out-Host
    & $Exe @Arguments 2>&1 | Tee-Object -FilePath $log -Append | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$Exe exited with $LASTEXITCODE" }
}
$slf4j = Join-Path $DependencyRoot 'slf4j-api-2.0.12.jar'
Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.12/slf4j-api-2.0.12.jar' -OutFile $slf4j
if ((Get-FileHash -LiteralPath $slf4j -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'a79502b8abdfbd722846a27691226a4088682d6d35654f9b80e2a9ccacf7ed47') {
    throw 'SLF4J API checksum mismatch'
}
$classes = Join-Path $EvidenceRoot 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$base = 'common/src/main/java/io/github/andrexarlay/vulkancompat/'
$sources = @(
    'core/src/main/java/io/github/andrexarlay/vulkanbridge/api/Experimental.java',
    'core/src/main/java/io/github/andrexarlay/vulkanbridge/api/LibraryDescriptor.java',
    'core/src/main/java/io/github/andrexarlay/vulkanbridge/platform/LogPaths.java',
    ($base + 'rtx/config/UpscalerMode.java'),
    ($base + 'rtx/upscaler/dlss/DlssQuality.java'),
    ($base + 'rtx/upscaler/dlss/DlssSrPreset.java'),
    ($base + 'rtx/upscaler/dlss/DlssLayouts.java'),
    ($base + 'rtx/upscaler/dlss/DlssNativeException.java'),
    ($base + 'rtx/upscaler/dlss/DlssNative.java'),
    ($base + 'rtx/upscaler/dlss/DlssNrNative.java'),
    ($base + 'rtx/upscaler/dlss/rr/DlssRrParams.java'),
    ($base + 'rtx/upscaler/dlss/rr/DlssRayReconstruction.java'),
    'common/src/test/java/io/github/andrexarlay/vulkancompat/rtx/upscaler/dlss/DlssResourceContractProbe.java'
) | ForEach-Object { Join-Path $SourceRoot $_ }
Invoke-Checked 'javac' (@('-encoding', 'UTF-8', '-cp', $slf4j, '-d', $classes) + $sources)
$probe = 'io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssResourceContractProbe'
foreach ($test in @(
    @{dll='vendor_ngx_fault.dll'; kind='ngx'}, @{dll='vendor_sl_fault.dll'; kind='sl'},
    @{dll='vkb_dlss.dll'; kind='production-ngx'}, @{dll='vkb_sl.dll'; kind='production'}
)) {
    Invoke-Checked 'java' @('--enable-native-access=ALL-UNNAMED', '-cp', "$classes;$slf4j", $probe,
        (Join-Path $RuntimeRoot $test.dll), $test.kind)
}
if ($PartnerWrapper) {
    $PartnerWrapper = (Resolve-Path -LiteralPath $PartnerWrapper).Path
    Invoke-Checked 'java' @('--enable-native-access=ALL-UNNAMED', '-cp', "$classes;$slf4j", $probe, $PartnerWrapper, 'partner-ngx')
}
$manifestPath = Join-Path $RuntimeRoot 'manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
$manifest['actual_host_ffm_contracts'] = 'passed'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
