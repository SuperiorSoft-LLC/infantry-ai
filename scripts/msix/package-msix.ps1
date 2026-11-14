# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
#
# STORE-1: packs the jpackage app image (createDistributable's InfantryAI folder) into an MSIX for the Microsoft Store.
#
#   pwsh scripts/msix/package-msix.ps1 -AppImage <...>/app/InfantryAI -Version 1.5.3 -Out build/msix/InfantryAI-1.5.3.msix
#
# The identity comes from superiorSoftLibrary/infantryUi/msix/msix.json (developer data); the environment variables
# MSSTORE_IDENTITY_NAME / MSSTORE_PUBLISHER / MSSTORE_PUBLISHER_DISPLAY_NAME override it (CI passes repository variables).
# The package is UNSIGNED on purpose: the Store signs what it distributes. To sideload it for a local test, sign it with a
# self-signed certificate whose subject equals the publisher (docs/STORE.md, "Local test install").
# The launcher's .cfg gets -Dinfantry.package=msix, so the app knows the Store owns its updates (StoreInstall).
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $AppImage,
    [Parameter(Mandatory)] [string] $Version,
    [Parameter(Mandatory)] [string] $Out,
    [string] $Config,
    [string] $Template,
    [string] $Icon,
    [string] $MakeAppx = $env:MAKEAPPX
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3
# Windows PowerShell 5.1 leaves $PSScriptRoot empty in parameter defaults, so the repo paths are resolved here.
$ui = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..\superiorSoftLibrary\infantryUi'
if (-not $Config) { $Config = Join-Path $ui 'msix\msix.json' }
if (-not $Template) { $Template = Join-Path $ui 'msix\AppxManifest.template.xml' }
if (-not $Icon) { $Icon = Join-Path $ui 'icons\infantry.ico' }

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "STORE-1: -Version '$Version' is not MAJOR.MINOR.BUILD (the MSI package version)" }
$launcher = Join-Path $AppImage 'InfantryAI.exe'
$cfg = Join-Path $AppImage 'app\InfantryAI.cfg'
if (-not (Test-Path $launcher) -or -not (Test-Path $cfg)) { throw "STORE-1: $AppImage is not the jpackage app image (no InfantryAI.exe / app\InfantryAI.cfg)" }

# makeappx.exe: -MakeAppx / MAKEAPPX, else the newest Windows 10/11 SDK's x64 copy.
if (-not $MakeAppx) {
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    $MakeAppx = Get-ChildItem -Path $kits -Filter makeappx.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object { $_.Directory.Parent.Name } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $MakeAppx -or -not (Test-Path $MakeAppx)) { throw 'STORE-1: makeappx.exe not found (install the Windows SDK, or pass -MakeAppx / set MAKEAPPX)' }

# Identity: msix.json, then the MSSTORE_* overrides.
$id = Get-Content -Raw $Config | ConvertFrom-Json
$values = [ordered]@{}
foreach ($p in $id.PSObject.Properties) { if (-not $p.Name.StartsWith('_')) { $values[$p.Name] = [string]$p.Value } }
$overrides = @{ identityName = $env:MSSTORE_IDENTITY_NAME; publisher = $env:MSSTORE_PUBLISHER; publisherDisplayName = $env:MSSTORE_PUBLISHER_DISPLAY_NAME }
foreach ($k in $overrides.Keys) { if ($overrides[$k]) { $values[$k] = $overrides[$k] } }
$values['version'] = "$Version.0"   # the Store wants MAJOR.MINOR.BUILD.0 (the revision is reserved)
$placeholder = $values['identityName'].StartsWith('PLACEHOLDER') -or $values['publisher'] -eq 'CN=00000000-0000-0000-0000-000000000000'
if ($placeholder) { Write-Host '::warning::STORE-1: msix.json still holds the PLACEHOLDER identity; this package builds but Partner Center will reject it (docs/STORE.md)' }

# The layout: a copy of the app image, the patched .cfg, the logos and the manifest.
$outFile = [System.IO.Path]::GetFullPath($Out)
$layout = Join-Path ([System.IO.Path]::GetDirectoryName($outFile)) 'layout'
if (Test-Path $layout) { Remove-Item -Recurse -Force $layout }
New-Item -ItemType Directory -Force $layout | Out-Null
Copy-Item -Recurse -Force (Join-Path $AppImage '*') $layout

$layoutCfg = Join-Path $layout 'app\InfantryAI.cfg'
$lines = [System.Collections.Generic.List[string]](Get-Content $layoutCfg)
$at = $lines.IndexOf('[JavaOptions]')
if ($at -lt 0) { $lines.Add('[JavaOptions]'); $at = $lines.Count - 1 }
$lines.Insert($at + 1, 'java-options=-Dinfantry.package=msix')
Set-Content -Path $layoutCfg -Value $lines -Encoding ascii

Add-Type -AssemblyName System.Drawing
$assets = Join-Path $layout 'Assets'
New-Item -ItemType Directory -Force $assets | Out-Null
$source = (New-Object System.Drawing.Icon((Resolve-Path $Icon).Path, 256, 256)).ToBitmap()
function Write-Logo([string] $name, [int] $w, [int] $h) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)
    $side = [Math]::Min($w, $h)
    $g.DrawImage($source, [int](($w - $side) / 2), [int](($h - $side) / 2), $side, $side)
    $g.Dispose()
    $bmp.Save((Join-Path $assets $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
Write-Logo 'StoreLogo.png' 50 50
Write-Logo 'Square44x44Logo.png' 44 44
Write-Logo 'Square150x150Logo.png' 150 150
Write-Logo 'Wide310x150Logo.png' 310 150
$source.Dispose()

$manifest = Get-Content -Raw $Template
foreach ($k in $values.Keys) { $manifest = $manifest.Replace('${' + $k + '}', [System.Security.SecurityElement]::Escape($values[$k])) }
if ($manifest -match '\$\{[A-Za-z]+\}') { throw "STORE-1: the manifest template has an unfilled token: $($Matches[0])" }
[System.IO.File]::WriteAllText((Join-Path $layout 'AppxManifest.xml'), $manifest, (New-Object System.Text.UTF8Encoding($false)))

if (Test-Path $outFile) { Remove-Item -Force $outFile }
& $MakeAppx pack /o /h SHA256 /d $layout /p $outFile | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { throw "STORE-1: makeappx pack failed (exit $LASTEXITCODE)" }
Remove-Item -Recurse -Force $layout
Write-Host "STORE-1: $outFile ($([Math]::Round((Get-Item $outFile).Length / 1MB)) MB, identity $($values['identityName']) $($values['version']))"
