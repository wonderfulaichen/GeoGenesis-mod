from pathlib import Path
import hashlib,json,subprocess
OUT=Path(__file__).resolve().parent
MDK=OUT.parents[3]
ROOT=OUT.parent
OLD=ROOT/'candidate-1792779496954846600'
INTEGRATED=MDK/'build/boundary-repair-v2/integrated-1792736719405609000'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
protected=[OLD/'DeterministicSkeleton.java',MDK/'run/config/geogenesis-common.toml',
           INTEGRATED/'src/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java',
           MDK/'src/main/java/com/geogenesis/worldgen/hydrology/model/DeterministicSkeleton.java',
           MDK/'src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java']
before={str(p):sha(p) for p in protected}
source=(OLD/'DeterministicSkeleton.java').read_text(encoding='utf-8')
old='''                               Result base, PassRole role) {        int cells = nx * nz;'''
new='''                               Result base, PassRole role) {
        return build(level, parent, resolved, lakeMask, sea, discharge, basinOutlet, nx, nz,
                params, incomingCells, incomingWires, bed, networkFilter, outside, base, role, null);
    }

    record CoreBounds(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x < maxX && z >= minZ && z < maxZ;
        }
    }

    static Result build(double[] level, int[] parent, boolean[] resolved,
                        boolean[] lakeMask, boolean[] sea, double[] discharge,
                        int[] basinOutlet, int nx, int nz, Params params,
                        List<Integer> incomingCells, List<WireIn> incomingWires,
                        double[] bed, Boolean networkFilter, OutsideContinuation outside,
                        Result base, PassRole role, CoreBounds core) {
        int cells = nx * nz;'''
assert source.count(old)==1
source=source.replace(old,new)
old='''            merged = pruneDisconnected(merged, nx, nz, params.minNodes(), new java.util.HashSet<>(incoming));
            droppedNetwork = before - merged.size();'''
new='''            merged = pruneDisconnected(merged, nx, nz, params.minNodes(), new java.util.HashSet<>(incoming));
            merged = pruneHaloComponents(merged, nz, new java.util.HashSet<>(incoming), outside, core);
            droppedNetwork = before - merged.size();'''
assert source.count(old)==1
source=source.replace(old,new)
helper='''    // Source admission only: shared-node grouping is not a downstream water certificate.
    static List<Trace> pruneHaloComponents(List<Trace> traces, int nz,
            java.util.Set<Integer> incomingStarts, OutsideContinuation outside, CoreBounds core) {
        if (outside == null || core == null || traces.isEmpty()) return traces;
        int n = traces.size();
        int[] groups = new int[n];
        java.util.Map<Integer, Integer> owner = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) groups[i] = i;
        for (int i = 0; i < n; i++) for (int c : traces.get(i).cell) {
            Integer prior = owner.putIfAbsent(c, i);
            if (prior != null) groups[find(groups, prior)] = find(groups, i);
        }
        boolean[] supported = new boolean[n], hasPort = new boolean[n];
        for (int i = 0; i < n; i++) {
            Trace t = traces.get(i);
            int root = find(groups, i);
            hasPort[root] |= t.end == End.BOUNDARY_EXIT && t.port != null;
            supported[root] |= t.fromOutlet || t.hasOutsideTail()
                    || t.end == End.MOUTH_LAKE || t.end == End.MOUTH_SEA;
            for (int c : t.cell) {
                int x = outside.originX() + c / nz * outside.step();
                int z = outside.originZ() + c % nz * outside.step();
                supported[root] |= core.contains(x, z) || incomingStarts.contains(c);
            }
        }
        List<Trace> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int root = find(groups, i);
            if (!hasPort[root] || supported[root]) kept.add(traces.get(i));
        }
        return kept;
    }

'''
anchor='    private static List<Trace> pruneShortLeaves(List<Trace> traces, int minNodes,'
assert source.count(anchor)==1
source=source.replace(anchor,helper+anchor)
(OUT/'DeterministicSkeleton.java').write_text(source,encoding='utf-8')
provider=(INTEGRATED/'src/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java').read_text(encoding='utf-8')
old='''                base, role);
        if (HIER) {'''
new='''                base, role,
                new DeterministicSkeleton.CoreBounds(originX + HALO, originZ + HALO,
                        originX + HALO + REGION, originZ + HALO + REGION));
        if (HIER) {'''
assert provider.count(old)==1
provider=provider.replace(old,new)
(OUT/'ModelHydrologyProvider.java').write_text(provider,encoding='utf-8')
classes=OUT/'classes'
classes.mkdir()
cp=str(OLD/'classes')+';'+json.loads((OLD/'prepare.json').read_text())['classpath']
cmd=['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(OUT/'DeterministicSkeleton.java'),str(OUT/'ModelHydrologyProvider.java')]
with (OUT/'compile.log').open('wb') as log:
    code=subprocess.run(cmd,cwd=OUT,stdout=log,stderr=subprocess.STDOUT).returncode
report=dict(compileExit=code,sourceSHA256=sha(OUT/'DeterministicSkeleton.java'),providerSHA256=sha(OUT/'ModelHydrologyProvider.java'),
            classpath=cp,protectedBefore=before,protectedUnchanged=before=={str(p):sha(p) for p in protected},
            scope='Isolated core-supported halo source admission; geometry and port filtered together in PRIMARY/SECOND_PASS. No routing changes.')
(OUT/'prepare.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('OUT='+str(OUT),'compileExit='+str(code),'protectedUnchanged='+str(report['protectedUnchanged']),flush=True)
if code:print((OUT/'compile.log').read_text(encoding='utf-8',errors='replace'))
raise SystemExit(0 if code==0 and report['protectedUnchanged'] else 1)
