# Shared classpath builder for this sandbox (ASCII only).
param([string]$Arm = 'cand')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'D:\Office software\Development Project\GeoGenesis-mod'
$base = Join-Path $repo 'forge-1.20.1-47.4.10-mdk\build\terrain-upgrade\baseline'
$jars = ((Get-Content (Join-Path $base 'classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cpbase = "$base\classes;$base\src-main\resources;$jars"
switch ($Arm) {
    'cand'     { "$root\classes-cand;$root\classes-head;$cpbase" }
    'base'     { "$root\classes-baseline;$root\classes-cand;$root\classes-head;$cpbase" }
    'a3a7686'  { "$root\classes-3a7686;$root\classes-head;$cpbase" }
    default    { throw "unknown arm $Arm" }
}
