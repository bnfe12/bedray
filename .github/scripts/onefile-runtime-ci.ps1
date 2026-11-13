$ErrorActionPreference = 'Stop'
$env:VKB_NRD_SRC = (Resolve-Path 'deps/NRD').Path
$env:VKB_VK_HEADERS = (Resolve-Path 'deps/Vulkan-Headers/include').Path
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$install = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $install) { throw 'MSVC was not found' }
$env:VKB_VCVARS = Join-Path $install 'VC/Auxiliary/Build/vcvars64.bat'
$env:VKB_CMAKE = (Get-Command cmake).Source
$env:VKB_NINJA = (Get-Command ninja).Source
& ./source/native/nrd_bridge/build_nrd.bat
if ($LASTEXITCODE -ne 0) { throw "NRD build failed: $LASTEXITCODE" }
& ./source/native/nrd_bridge/build.bat
if ($LASTEXITCODE -ne 0) { throw "NRD wrapper build failed: $LASTEXITCODE" }

$out = (New-Item -ItemType Directory -Force 'evidence/onefile').FullName
$inputs = [ordered]@{
    'NRD.dll' = 'source/native/nrd_bridge/build/NRD.dll'
    'vkb_nrd.dll' = 'source/native/nrd_bridge/build/vkb_nrd.dll'
    'vkb_dlss.dll' = 'evidence/vendor/partner/Release/vkb_dlss_nr_contract.dll'
    'vkb_sl.dll' = 'evidence/vendor/runtime/vkb_sl.dll'
    'NRD-LICENSE.txt' = 'deps/NRD/LICENSE.txt'
    'DLSS-LICENSE.txt' = 'evidence/vendor/runtime/DLSS-LICENSE.txt'
}
$hashes = [ordered]@{}
foreach ($entry in $inputs.GetEnumerator()) {
    $target = Join-Path $out $entry.Key
    Copy-Item -LiteralPath $entry.Value -Destination $target
    $hashes[$entry.Key] = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
}
@{ revision = (git -C source rev-parse HEAD); sha256 = $hashes } | ConvertTo-Json | Set-Content (Join-Path $out 'manifest.json') -Encoding utf8NoBOM
