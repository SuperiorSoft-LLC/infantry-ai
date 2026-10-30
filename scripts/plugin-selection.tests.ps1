# BUILD-PLUGINS-1 scoped tests (no Gradle): powershell -File scripts/plugin-selection.tests.ps1 ; exit 0 = all pass.
. "$PSScriptRoot\plugin-selection.ps1"
$root = Join-Path (Split-Path -Parent $PSScriptRoot) 'plugins'
$fails = 0
function Check([string]$Name, [bool]$Ok) { if ($Ok) { "ok   $Name" } else { "FAIL $Name"; $script:fails++ } }
function Throws([scriptblock]$B) { try { & $B | Out-Null; $null } catch { $_.Exception.Message } }

# A scratch plugins root: plugins/core has no modules today, so the guard is tested on a fixture with one of each class.
$real = @(Get-PluginInventory $root)
Check 'real inventory has official and optional plugins' (($real | Where-Object Class -eq 'optional').Count -gt 0 -and ($real | Where-Object Class -eq 'official').Count -gt 0)
$root = Join-Path ([IO.Path]::GetTempPath()) ("plugin-sel-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
foreach ($n in 'core/Core_A', 'official/Off_B', 'official/Opt_C', 'official/Opt_D') {
  $d = Join-Path $root $n; New-Item -ItemType Directory -Path $d | Out-Null
  Set-Content -LiteralPath (Join-Path $d 'module.json') -Value ('{"name":"' + (Split-Path $n -Leaf) + '","version":"1.2.3","jar":"build/libs/x.jar"}')
}
Set-Content -LiteralPath (Join-Path $root 'build-presets.json') -Value '{"classes":{"Opt_C":"optional","Opt_D":"optional"},"presets":{"full":{"exclude":[]},"no-optional":{"exclude":["@optional"]}}}'
$inv = @(Get-PluginInventory $root)
Check 'fixture inventory has core, official, optional' (@($inv | Where-Object Class -eq 'core').Count -eq 1 -and @($inv | Where-Object Class -eq 'official').Count -eq 1 -and @($inv | Where-Object Class -eq 'optional').Count -eq 2)
$opt = 'Opt_C'; $core = 'Core_A'; $off = 'Off_B'

$none = Resolve-PluginSelection $inv $root @() @() ''
Check 'default excludes nothing' ($none.Excluded.Count -eq 0 -and $none.Included.Count -eq $inv.Count)
Check 'exclude optional works' ((Resolve-PluginSelection $inv $root @() @($opt) '').Excluded -contains $opt)
$m = Throws { Resolve-PluginSelection $inv $root @() @($core) '' }
Check 'core cannot be excluded (reason given)' ($m -match "cannot exclude '$core'" -and $m -match "class 'core'")
Check 'official cannot be excluded' ((Throws { Resolve-PluginSelection $inv $root @() @($off) '' }) -match "class 'official'")
Check 'unknown exclude refused' ((Throws { Resolve-PluginSelection $inv $root @() @('Nope_X') '' }) -match 'unknown')
Check 'unknown preset refused' ((Throws { Resolve-PluginSelection $inv $root @() @() 'bogus' }) -match 'unknown preset')
$no = Resolve-PluginSelection $inv $root @() @() 'no-optional'
Check 'no-optional leaves out exactly the optional set' (($no.Excluded -join ',') -eq ((($inv | Where-Object Class -eq 'optional').Id | Sort-Object) -join ','))
$inc = Resolve-PluginSelection $inv $root @($opt) @() 'no-optional'
Check 'include keeps one optional plugin' (($inc.Excluded -notcontains $opt) -and $inc.Included -contains $opt)

$mf = New-BuildManifest $inv $no 'abc' 'devSign' 'no-optional'
Check 'manifest lists included and excluded' ($mf.excluded.Count -eq $no.Excluded.Count -and $mf.included.Count -eq $no.Included.Count -and $mf.preset -eq 'no-optional')
Check 'manifest keeps core in included' (@($mf.included | Where-Object id -eq $core).Count -eq 1)

Remove-Item -LiteralPath $root -Recurse -Force
if ($fails) { "$fails FAILED"; exit 1 } else { 'all passed'; exit 0 }
