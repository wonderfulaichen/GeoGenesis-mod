param([string]$Seed='9139912035078620160', [switch]$SkipCompile, [string]$OutputDirectory='build/local-flow-reassessment/final')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$out=if([IO.Path]::IsPathRooted($OutputDirectory)){$OutputDirectory}else{Join-Path $root $OutputDirectory}
New-Item -ItemType Directory -Force $out | Out-Null
Push-Location $root
try {
    $protected=@(Get-ChildItem 'src/main' -Recurse -File)
    $protected+=Get-Item 'run/config/geogenesis-common.toml'
    $plan='../.openbitfun/plans/local-flow-streaming-reassessment.plan.md'
    # TodoWrite synchronizes frontmatter statuses; verify all other bytes, never write the plan.
    $planBefore=if(Test-Path -LiteralPath $plan){[regex]::Replace([IO.File]::ReadAllText((Resolve-Path $plan)), '(?m)^(\s*status:) (pending|in_progress|completed)(\r?)$', '$1 pending$3')}else{$null}
    $before=@{}
    foreach($file in $protected){$before[$file.FullName]=(Get-FileHash -LiteralPath $file.FullName).Hash}
    $baseline=Join-Path $root 'build/local-flow-reassessment/current-input-sha256.txt'
    New-Item -ItemType Directory -Force (Split-Path -Parent $baseline) | Out-Null
    $manifest=foreach($file in $protected){'{0} {1}' -f $before[$file.FullName],$file.FullName}
    [IO.File]::WriteAllLines($baseline,$manifest,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines((Join-Path $out 'input-sha256.txt'),$manifest,[Text.UTF8Encoding]::new($false))
    if(-not $SkipCompile) {
        $ErrorActionPreference='Continue'
        & ./gradlew.bat -I tools/local-flow-reassessment.gradle localFlowClasspath --offline --console=plain *> "$out/compile.log"
        $code=$LASTEXITCODE
        $ErrorActionPreference='Stop'
        if($code -ne 0){Get-Content "$out/compile.log";throw "Gradle compile failed: $code"}
    }
    $cp=[IO.File]::ReadAllText((Join-Path $root 'build/local-flow-reassessment/classpath.txt')).Trim()
    $runs=@(
        @('ownership','com.geogenesis.worldgen.hydrology.model.LocalFlowOwnershipProbe'),
        @('join','com.geogenesis.worldgen.hydrology.model.LocalFlowJoinProbe'),
        @('basin','com.geogenesis.worldgen.hydrology.model.LocalFlowBasinProbe')
    )
    $summary=[Collections.Generic.List[string]]::new()
    $failed=$false
    foreach($run in $runs) {
        $dir=Join-Path $out $run[0];New-Item -ItemType Directory -Force $dir | Out-Null
        $arguments=@('-Xmx6g','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',
            "-DlocalFlow.output=`"$($dir.Replace('\','/'))`"",'-cp',"`"$($cp.Replace('\','/'))`"",$run[1],$Seed)
        $arg=Join-Path $out "$($run[0]).args"
        [IO.File]::WriteAllLines($arg,$arguments,[Text.UTF8Encoding]::new($false))
        $ErrorActionPreference='Continue'
        & java "@$arg" *> "$dir/stdout.log"
        $code=$LASTEXITCODE
        $ErrorActionPreference='Stop'
        $summary.Add("$($run[0]) exit=$code log=$($run[0])/stdout.log")
        if($code -ne 0){$failed=$true}
        Write-Output $summary[$summary.Count-1]
    }
    foreach($file in $protected) {
        if((Get-FileHash -LiteralPath $file.FullName).Hash -ne $before[$file.FullName]){throw "Protected input changed: $($file.FullName)"}
    }
    $planAfter=if(Test-Path -LiteralPath $plan){[regex]::Replace([IO.File]::ReadAllText((Resolve-Path $plan)), '(?m)^(\s*status:) (pending|in_progress|completed)(\r?)$', '$1 pending$3')}else{$null}
    if($planBefore -cne $planAfter){throw 'Plan changed beyond TodoWrite status synchronization'}
    $summary.Add('production_source_config_hashes=UNCHANGED; plan_except_TodoWrite_statuses=UNCHANGED')
    $summary.Add('join exit2=OPEN_GAP (exit1=assertion failure, exit3=infrastructure); basin exit1=CANDIDATE_RED (exit2=infrastructure); ownership exit1=assertion failure')
    $summary.Add('probe_exit_zero_is_not_production_acceptance; inspect candidate verdicts and decision.txt')
    [IO.File]::WriteAllLines((Join-Path $out 'exit-codes.txt'),$summary,[Text.UTF8Encoding]::new($false))
    if($failed){exit 1}
} finally {Pop-Location}
