<#
.SYNOPSIS
  Checks the tree that would be exported to the public repository (tools/PUBLIC_EXPORT_ALLOWLIST.txt) and fails (exit 1)
  when it contains forbidden content: personal paths, e-mail addresses, tokens, AI-tooling or internal-notes words,
  binaries. Run from any folder:  powershell -ExecutionPolicy Bypass -File tools/public_scan.ps1
  Optional: -Export <dir> copies the scanned files to <dir> (after a successful scan).
#>
param([string]$Export = "")

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

# --- 1. files to export: tracked files that match the allowlist -------------------------------------------------------
function ConvertTo-Regex([string]$glob) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('^')
    $i = 0
    while ($i -lt $glob.Length) {
        $c = $glob[$i]
        if ($c -eq '*') {
            if (($i + 1 -lt $glob.Length) -and ($glob[$i + 1] -eq '*')) { [void]$sb.Append('.*'); $i += 2; continue }
            [void]$sb.Append('[^/]*')
        } else {
            [void]$sb.Append([regex]::Escape([string]$c))
        }
        $i++
    }
    [void]$sb.Append('$')
    return $sb.ToString()
}
$patterns = Get-Content (Join-Path $PSScriptRoot "PUBLIC_EXPORT_ALLOWLIST.txt") |
    Where-Object { $_.Trim() -ne "" -and -not $_.TrimStart().StartsWith("#") } |
    ForEach-Object { ConvertTo-Regex $_.Trim() }
$tracked = & git ls-files
$files = $tracked | Where-Object { $f = $_; ($patterns | Where-Object { $f -match $_ }).Count -gt 0 }
Write-Host ("Allowlisted files: {0} of {1} tracked" -f @($files).Count, @($tracked).Count)

# --- 2. forbidden content ----------------------------------------------------------------------------------------------
$forbiddenEverywhere = @(
    @{ Name = "personal Windows path";  Re = '(?i)\b[A-Z]:[\/]+(Users|Sky|Base)\b' },
    @{ Name = "personal Windows path";  Re = '(?i)\b[A-Z]:\\(Users|Sky|Base)\b' },
    @{ Name = "e-mail address";         Re = '(?i)\b[A-Z0-9._%+-]+@(?!users\.noreply\.github\.com)[A-Z0-9.-]+\.[A-Z]{2,}\b' },
    @{ Name = "GitHub token";           Re = '\bgh[pousr]_[A-Za-z0-9]{20,}\b' },
    @{ Name = "GitHub fine-grained token"; Re = '\bgithub_pat_[A-Za-z0-9_]{20,}\b' },
    @{ Name = "AWS access key";         Re = '\bAKIA[0-9A-Z]{16}\b' },
    @{ Name = "API key (sk-)";          Re = '\bsk-[A-Za-z0-9]{20,}\b' },
    @{ Name = "Slack token";            Re = '\bxox[baprs]-[A-Za-z0-9-]{10,}\b' },
    @{ Name = "private key block";      Re = '-----BEGIN [A-Z ]*PRIVATE KEY-----' },
    @{ Name = "AI tooling file name"; Re = '(?-i:\b(AGENTS|CLAUDE|GEMINI|STATE|TRAPS|AUDIT|PLAN)\.md\b)' },
    @{ Name = "AI tooling / internal word"; Re = '(?i)\b(Claude|Gemini|Codex|Antigravity|skybase|ChatGPT|Copilot|Obsidian vault|Notion page)\b' },
    @{ Name = "launcher instance name"; Re = '(?i)(FreesmLauncher|Vulkan Testbed|Vulkan Fabric Agent|Testbed 26\.3|ATM11)' },
    @{ Name = "internal notes word"; Re = '(?-i:\bTRAPS\b|\bAUDIT\.md\b|\bCLAUDE\.md\b|\bAGENTS\.md\b|\bGEMINI\.md\b)|(?i:\bmonorepo\b|[A-Za-z_-]+_PLAN\b|[a-z-]+-plan\.md|(?:agent|test|launcher) polygon\b|the polygon is)' },
    @{ Name = "author real name"; Re = '(?i)Andrex[ _]Arlay' },
    @{ Name = "internal handoff";       Re = '(?i)\b(handoff|AI_HANDOFF|worktree workflow)\b' }
)
# The separate companion library is not named anywhere in the export (docs, source, build files, metadata): the mod is one
# self-contained jar. Java package names such as io.github.andrexarlay.vulkanbridge do not match (no separator) and stay.
$userFacing = '.*'
$forbiddenUser = @(
    @{ Name = "separate companion library named (vulkan_bridge / vulkan-bridge / Vulkan Bridge)"; Re = '(?i)\bvulkan[-_ ]bridge\b' }
)
# Plain-language texts for users (docs, readme, changelog) do not use the word bridge at all (compound names such as
# dlss_bridge / IrisRenderBridge are not words and are fine).
$userTextOnly = '^(README|CHANGELOG|CONTRIBUTING|SECURITY|THIRD_PARTY_NOTICES)\.md$|^NOTICE$|^docs/[^/]*\.md$'
$forbiddenUserText = @(
    @{ Name = "the word bridge in user text"; Re = '(?i)\bbridge\b' }
)

$binaryExt = '\.(dll|exe|lib|pdb|obj|so|dylib|zip|nupkg|class)$'
$jarAllowed = '^gradle/wrapper/gradle-wrapper\.jar$'
$skipContentExt = '\.(png|jpg|jpeg|gif|ico|spv|jar|bin|ttf|otf)$'

$problems = New-Object System.Collections.Generic.List[string]
foreach ($f in $files) {
    if ($f -match $binaryExt -or ($f -match '\.jar$' -and $f -notmatch $jarAllowed)) {
        $problems.Add("binary file in export: $f"); continue
    }
    if ($f -match '(^|/)(AGENTS|CLAUDE|GEMINI|STATE|PLAN|TRAPS|AUDIT)\.md$' -or $f -match '^\.(agents|claude|gemini|codex)/' ) {
        $problems.Add("AI/internal file in export: $f"); continue
    }
    if ($f -match $skipContentExt) { continue }
    if ($f -eq 'tools/public_scan.ps1') { continue }  # contains the forbidden patterns themselves
    if (-not (Test-Path -LiteralPath $f)) { continue }
    $lineNo = 0
    foreach ($line in [System.IO.File]::ReadLines((Join-Path $root $f))) {
        $lineNo++
        foreach ($r in $forbiddenEverywhere) {
            if ($line -match $r.Re) { $problems.Add(("{0}:{1}: {2}: {3}" -f $f, $lineNo, $r.Name, $Matches[0])) }
        }
        if ($f -match $userFacing) {
            $userLine = $line
            foreach ($r in $forbiddenUser) {
                if ($userLine -match $r.Re) { $problems.Add(("{0}:{1}: {2}: {3}" -f $f, $lineNo, $r.Name, $Matches[0])) }
            }
            if ($f -match $userTextOnly) {
                foreach ($r in $forbiddenUserText) {
                    if ($line -match $r.Re) { $problems.Add(("{0}:{1}: {2}: {3}" -f $f, $lineNo, $r.Name, $Matches[0])) }
                }
            }
        }
    }
}

if ($problems.Count -gt 0) {
    $problems | Select-Object -First 200 | ForEach-Object { Write-Host "FORBIDDEN  $_" }
    Write-Host ("public_scan FAILED: {0} problem(s)" -f $problems.Count)
    exit 1
}
Write-Host "public_scan OK: no forbidden content in the allowlisted tree"
if ($Export -ne "") {
    foreach ($f in $files) {
        $dst = Join-Path $Export $f
        New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
        Copy-Item -LiteralPath (Join-Path $root $f) -Destination $dst -Force
    }
    Write-Host "exported $(@($files).Count) files to $Export"
}
exit 0
