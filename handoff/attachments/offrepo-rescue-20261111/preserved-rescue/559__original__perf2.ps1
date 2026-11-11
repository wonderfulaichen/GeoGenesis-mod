$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$base='D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk\build\terrain-upgrade\baseline'
$jars=((Get-Content "$base/classpath-jars.txt" -Raw) -replace "`r?`n",'').Trim()
$common="$root/classes-head;$base/classes;$base/src-main/resources;$jars"
$cps=@{A="$root/classes-perf2;$root/classes-3a7686;$common";B="$root/classes-perf2;$root/classes-baseline;$root/classes-cand;$common";C="$root/classes-perf2;$root/classes-cand;$common"}
$jdk='D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
foreach($arm in @('A','B','C')) { $u=& "$jdk/javap.exe" -p -cp $cps[$arm] 'com.geogenesis.worldgen.terrain.candidate.DoubleFullMorphLand$Tile'; if($LASTEXITCODE -ne 0){throw 'javap failed'}; $found=($u | Select-String 'chunkStart') -ne $null; if($found -ne ($arm -eq 'C')){throw "bad class $arm"}; Write-Output "identity $arm indexed=$found" }
$orders=@('ABC','ACB','BAC','BCA','CAB','CBA'); $zones=@(0,1831,-1832,29295)
'round,position,arm,zone,batch,elapsed_ns,hash,sum' | Set-Content "$root/perf2.csv" -Encoding ASCII
for($r=0;$r -lt 6;$r++){ $order=$orders[$r]; for($pos=0;$pos -lt 3;$pos++){ $arm=[string]$order[$pos]; foreach($z in $zones){
 $out=& "$jdk/java.exe" -Xmx8g -cp $cps[$arm] com.geogenesis.worldgen.terrain.candidate.PerfE2 $z "$base/config-input.toml" 2>&1
 if($LASTEXITCODE -ne 0){throw "JVM failed $r $arm $z : $out"}; $data=@($out | Where-Object {"$_" -match '^-?\d+,\d+,\d+,[0-9a-f]{16},'}); if($data.Count -ne 25){throw "bad rows $r $arm $z count=$($data.Count) output=$out"}
 $data | ForEach-Object {"$r,$pos,$arm,$_"} | Add-Content "$root/perf2.csv" -Encoding ASCII
 Write-Output "done round=$r arm=$arm zone=$z"
}}}
