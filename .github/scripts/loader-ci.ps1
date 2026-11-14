param(
    [Parameter(Mandatory)][string]$SourceRoot,
    [Parameter(Mandatory)][string]$LauncherLibraries
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$source = (Resolve-Path -LiteralPath $SourceRoot).Path
$libs = Join-Path $source 'libs'
$inputs = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'loader-ci-inputs.json') -Raw | ConvertFrom-Json

function Get-VerifiedJars([string]$Directory, [object[]]$Entries) {
    foreach ($entry in $Entries) {
        if ($entry.path -notmatch '^[a-zA-Z0-9.+/_-]+\.jar$' -or $entry.path.Split('/') -contains '..') {
            throw "Invalid build input path: $($entry.path)"
        }
        $destination = Join-Path $Directory $entry.path
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Invoke-WebRequest -Uri $entry.url -OutFile $destination -TimeoutSec 90
        if ((Get-FileHash -LiteralPath $destination -Algorithm $entry.algorithm).Hash -ne $entry.digest) {
            throw "Checksum mismatch for $($entry.path)"
        }
    }
}

Get-VerifiedJars -Directory $LauncherLibraries -Entries $inputs.launcher
Get-VerifiedJars -Directory $libs -Entries $inputs.libs

# Sodium embeds the game-facing mod classes as a nested JAR.
$sodium = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $libs 'sodium-neoforge.jar'))
try {
    $nested = @($sodium.Entries | Where-Object { $_.FullName -match '^META-INF/jarjar/[^/]+-mod\.jar$' })
    if ($nested.Count -ne 1) { throw "Expected one Sodium mod jar, got $($nested.Count)" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($nested[0], (Join-Path $libs 'sodium-mod.jar'), $true)
} finally {
    $sodium.Dispose()
}
