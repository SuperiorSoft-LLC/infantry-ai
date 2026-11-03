# Checks the INFANTRY_*/AGENTHQ_* fallback with only the legacy variables set (no packaging). Exit 0 = pass.
. (Join-Path $PSScriptRoot 'infantry-env.ps1')
$fail = 0
function Check($name, $ok) { if (-not $ok) { Write-Host "FAIL $name"; $script:fail++ } else { Write-Host "ok   $name" } }
foreach ($n in 'BUILD_ROOT','PROJECT_ID','STATE_DIR','CLI') { Remove-Item "env:INFANTRY_$n" -ErrorAction SilentlyContinue; Remove-Item "env:AGENTHQ_$n" -ErrorAction SilentlyContinue }
$env:AGENTHQ_BUILD_ROOT = 'C:\legacy-root'
Check 'legacy only' ((Get-InfantryEnv 'BUILD_ROOT') -eq 'C:\legacy-root')
$env:INFANTRY_BUILD_ROOT = 'C:\new-root'
Check 'new wins' ((Get-InfantryEnv 'BUILD_ROOT') -eq 'C:\new-root')
Remove-Item env:INFANTRY_BUILD_ROOT
$env:AGENTHQ_CLI = 'C:\x\agenthq.cmd'
Check 'legacy CLI' ((Get-InfantryCli) -eq 'C:\x\agenthq.cmd')
$env:AGENTHQ_BUILD_ROOT = 'C:\legacy-root'
$out = & powershell -NoProfile -File (Join-Path $PSScriptRoot 'swap-current.ps1') -WhatIf 2>&1 | Out-String
Check 'swap-current sees legacy build root' ($out -notmatch 'build.root is not set')
exit $fail
