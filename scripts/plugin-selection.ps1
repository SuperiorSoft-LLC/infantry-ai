# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# BUILD-PLUGINS-1: plugin selection for the packaging script. Pure functions (dot-sourced by package-dist.ps1 and by
# plugin-selection.tests.ps1); ids, classes and presets are DATA from plugins/build-presets.json.

function Read-Presets([string]$PluginsRoot) {
  Get-Content -LiteralPath (Join-Path $PluginsRoot 'build-presets.json') -Raw | ConvertFrom-Json
}

# Every plugin the build could carry: Id, Tier (folder core|official), Class, Version, Dir.
function Get-PluginInventory([string]$PluginsRoot) {
  $presets = Read-Presets $PluginsRoot
  $classes = @{}
  if ($presets.classes) { $presets.classes.PSObject.Properties | ForEach-Object { $classes[$_.Name] = [string]$_.Value } }
  foreach ($tier in 'core', 'official') {
    $tierDir = Join-Path $PluginsRoot $tier
    if (-not (Test-Path -LiteralPath $tierDir)) { continue }
    foreach ($d in Get-ChildItem -LiteralPath $tierDir -Directory | Where-Object { $_.Name -notlike '_*' }) {
      $json = Join-Path $d.FullName 'module.json'
      if (-not (Test-Path -LiteralPath $json)) { continue }
      $m = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json
      $class = if ($tier -eq 'core') { 'core' } elseif ($classes.ContainsKey($d.Name)) { $classes[$d.Name] } else { 'official' }
      [pscustomobject]@{ Id = $d.Name; Tier = $tier; Class = $class; Version = [string]$m.version; Dir = $d.FullName }
    }
  }
}

# Resolves what a build leaves out. Throws (message = the reason) on an unknown id/preset or a non-optional exclude.
function Resolve-PluginSelection([object[]]$Inventory, [string]$PluginsRoot, [string[]]$Include, [string[]]$Exclude, [string]$Preset) {
  $presets = Read-Presets $PluginsRoot
  $ids = @($Inventory | ForEach-Object { $_.Id })
  $optional = @($Inventory | Where-Object { $_.Class -eq 'optional' } | ForEach-Object { $_.Id })
  $excluded = [System.Collections.Generic.HashSet[string]]::new()
  if ($Preset) {
    $p = $presets.presets.PSObject.Properties[$Preset]
    if (-not $p) { throw "unknown preset '$Preset' (known: $(($presets.presets.PSObject.Properties.Name) -join ', '))" }
    foreach ($e in @($p.Value.exclude)) {
      if ($e -eq '@optional') { foreach ($o in $optional) { [void]$excluded.Add($o) } } elseif ($e) { [void]$excluded.Add($e) }
    }
  }
  foreach ($e in $Exclude) { if ($e) { [void]$excluded.Add($e.Trim()) } }
  # -Include = keep these optional plugins; every other optional plugin is left out.
  if ($Include) {
    $keep = @($Include | Where-Object { $_ } | ForEach-Object { $_.Trim() })
    foreach ($i in $keep) { if ($ids -notcontains $i) { throw "include names unknown plugin '$i'" } }
    foreach ($o in $optional) { if ($keep -notcontains $o) { [void]$excluded.Add($o) } }
    foreach ($k in $keep) { [void]$excluded.Remove($k) }
  }
  foreach ($e in $excluded) {
    $plugin = $Inventory | Where-Object { $_.Id -eq $e }
    if (-not $plugin) { throw "cannot exclude unknown plugin '$e'" }
    if ($plugin.Class -ne 'optional') { throw "cannot exclude '$e': it is class '$($plugin.Class)'; only optional plugins can be left out of a build (core and official always ship)" }
  }
  $out = @($excluded | Sort-Object)
  [pscustomobject]@{
    Excluded = $out
    Included = @($Inventory | Where-Object { $out -notcontains $_.Id } | ForEach-Object { $_.Id })
  }
}

# build-manifest.json content: what the build includes and leaves out.
function New-BuildManifest([object[]]$Inventory, [object]$Selection, [string]$Sha, [string]$Signing, [string]$Preset) {
  $row = { param($p) [ordered]@{ id = $p.Id; class = $p.Class; version = $p.Version } }
  [ordered]@{
    sha = $Sha; signing = $Signing; preset = $(if ($Preset) { $Preset } else { 'full' })
    included = @($Inventory | Where-Object { $Selection.Excluded -notcontains $_.Id } | ForEach-Object { & $row $_ })
    excluded = @($Inventory | Where-Object { $Selection.Excluded -contains $_.Id } | ForEach-Object { & $row $_ })
  }
}

# catalog.json for a local-directory catalog: one entry per packaged left-out plugin (a folder under $OptionalDir).
function New-OptionalCatalog([string]$OptionalDir, [object[]]$Inventory) {
  $entries = foreach ($d in Get-ChildItem -LiteralPath $OptionalDir -Directory | Sort-Object Name) {
    $inv = $Inventory | Where-Object { $_.Id -eq $d.Name }
    $m = Get-Content -LiteralPath (Join-Path $d.FullName 'module.json') -Raw | ConvertFrom-Json
    $jar = Join-Path $d.FullName ([string]$m.jar)
    [ordered]@{
      id = $d.Name; version = [string]$m.version; class = $inv.Class; jar = [string]$m.jar
      sha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLower()
      grants = @($m.actions | ForEach-Object { $_.id })
    }
  }
  [ordered]@{ plugins = @($entries) }
}
