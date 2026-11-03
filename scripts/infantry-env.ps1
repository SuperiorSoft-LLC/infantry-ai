# Env lookup with the RENAME-2 legacy fallback: INFANTRY_<suffix> first, else AGENTHQ_<suffix>. Dot-source it.
function Get-InfantryEnv([string]$Suffix) {
  foreach ($prefix in 'INFANTRY_', 'AGENTHQ_') {
    $v = [Environment]::GetEnvironmentVariable($prefix + $Suffix)
    if ($v) { return $v }
  }
  return $null
}
# The CLI launcher: INFANTRY_CLI / AGENTHQ_CLI, else infantry, else the deprecated agenthq alias on PATH.
function Get-InfantryCli {
  $c = Get-InfantryEnv 'CLI'
  if ($c) { return $c }
  foreach ($n in 'infantry', 'agenthq') {
    $s = (Get-Command $n -ErrorAction SilentlyContinue).Source
    if ($s) { return $s }
  }
  return $null
}
