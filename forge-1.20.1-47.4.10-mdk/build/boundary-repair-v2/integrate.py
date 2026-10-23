from pathlib import Path
import importlib.util, shutil, subprocess, os, time

root = Path('build/boundary-repair-v2')
out = root / ('integrated-' + str(time.time_ns()))
src = out / 'src/com/geogenesis/worldgen/hydrology/model'
src.mkdir(parents=True)
wire = root / 'ports-wire'
for p in (wire / 'out/v2/src/com/geogenesis/worldgen/hydrology/model').glob('*.java'):
    shutil.copy2(p, src / p.name)
for name in ['BoundaryPort.java', 'PortEgress.java', 'WireIn.java', 'WireReject.java']:
    shutil.copy2(wire / 'java/com/geogenesis/worldgen/hydrology/model' / name, src / name)
shutil.copy2(root / 'OrderedSpillSearch.java', src / 'OrderedSpillSearch.java')
p = src / 'DeterministicSkeleton.java'
t = p.read_text(encoding='utf-8')

def sub(old, new):
    global t
    assert t.count(old) == 1, (old[:150], t.count(old))
    t = t.replace(old, new)

sub('        var ownSegments = new TraceSegments(nz);',
    '        var ownSegments = new TraceSegments(nz);\n        java.util.ArrayDeque<Integer> spillSteps = new java.util.ArrayDeque<>();')
sub('            int join = forceWireStep ? -1 : nearbyJoin(',
    '            int join = forceWireStep || !spillSteps.isEmpty() ? -1 : nearbyJoin(')
sub('            } else if (STRICT_D8 && bed != null) {',
    '''            } else if (!spillSteps.isEmpty()) {
                p = spillSteps.removeFirst();
                curX = p / nz; curZ = p % nz;
                double dx = p / nz - k / nz, dz = p % nz - k % nz;
                double len = Math.hypot(dx, dz);
                if (len > 0) { lastDirX = dx / len; lastDirZ = dz / len; }
            } else if (STRICT_D8 && bed != null) {''')
old = '''                        if (strictVisited == null) strictVisited = new java.util.HashSet<>();
                        strictVisited.add(k);
                        int sp = spillCell(k, bed, nx, nz, strictVisited);
                        p = (sp >= 0 && bed[sp] - bed[k] <= MAX_SPILL_RISE) ? sp : -1;'''
new = '''                        final int spillStart = k;
                        OrderedSpillSearch.Guards guards = new OrderedSpillSearch.Guards() {
                            public boolean blocked(int c) { return selfStamp[c] == stamp && c != spillStart; }
                            public boolean receiver(int c) { return lakeMask[c] || sea[c]
                                    || covered[c] && selfStamp[c] != stamp; }
                            public boolean blockedEdge(int a, int b) {
                                return ownSegments.crosses(a, b) || STEP_CROSS_GUARD
                                        && segmentCrossesAny(a / nz, a % nz, b / nz, b % nz, segments);
                            }
                        };
                        OrderedSpillSearch.Result spill = new OrderedSpillSearch(bed, nx, nz, k,
                                MAX_SPILL_RISE, outside == null ? 0 : outside.originX(),
                                outside == null ? 0 : outside.originZ(), outside == null ? 1 : outside.step(),
                                cells, guards).find();
                        if (spill.accepted()) {
                            for (int q = 1; q < spill.path().length; q++) spillSteps.addLast(spill.path()[q]);
                            p = spillSteps.removeFirst();
                            curX = p / nz; curZ = p % nz;
                        } else if (spill.status() == OrderedSpillSearch.Status.NO_ESCAPE) {
                            p = -1;
                        } else {
                            stopReason = "SPILL_" + spill.status(); end = End.UNRESOLVED; break;
                        }'''
sub(old, new)
sub('                                cells, guards).find();', '''                                cells, guards).find();
                        if (outside != null && outside.originX() + k / nz * outside.step() == -362
                                && outside.originZ() + k % nz * outside.step() == 652) {
                            System.out.println("[BOUNDARY-SPILL-TARGET] " + spill.status()
                                    + " crest=" + spill.crest() + " boundary=" + spill.boundaryBound()
                                    + " expanded=" + spill.expanded() + " path=" + spill.path().length);
                            dumpSpillFixture(bed, covered, selfStamp, stamp, spillStart, guards);
                        }''')
# Keep observations on isolated copies, including the exact receiver and edge masks.
t = t.rstrip()[:-1] + '''
    private static void dumpSpillFixture(double[] bed, boolean[] covered, int[] selfStamp,
                                        int stamp, int start, OrderedSpillSearch.Guards guards) {
        String target = System.getProperty("geogenesis.spillFixture");
        if (target == null) return;
        try (var out = new java.io.DataOutputStream(new java.io.FileOutputStream(target))) {
            out.writeInt(bed.length); out.writeInt(start);
            for (int i = 0; i < bed.length; i++) {
                out.writeDouble(bed[i]); out.writeBoolean(guards.blocked(i));
                out.writeBoolean(guards.receiver(i));
            }
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
'''
sub('if (PORT_EGRESS && STRICT_D8 && VECTOR_TRACE && bed != null && outside != null) {',
    'if (PORT_EGRESS && !(wiredFirstStep && path.size() == 1) && spillSteps.isEmpty()\n                    && STRICT_D8 && VECTOR_TRACE && bed != null && outside != null) {')
sub('                        mergedIncoming++;\n                        if (incomingDiag != null) incomingDiag.add(new IncomingDiagnostic(',
    '                        wiredAccepted.add(start);\n                        mergedIncoming++;\n                        if (incomingDiag != null) incomingDiag.add(new IncomingDiagnostic(')
# Do not let FRAME_ESCAPE substitute a second routing rule once typed exits are enabled.
sub('                    if (FRAME_ESCAPE && outside != null) {',
    '                    if (!PORT_EGRESS && FRAME_ESCAPE && outside != null) {')
# A one-node sender still publishes its exact anchor; it must not invent an in-window segment.
sub('''                out.add(new Shaped(new double[0], new double[0]));
                continue;''', '''                if (t.port != null && m == 1) {
                    out.add(new Shaped(new double[]{t.port.anchorX()}, new double[]{t.port.anchorZ()}));
                } else out.add(new Shaped(new double[0], new double[0]));
                continue;''')
# CR interpolation must reproduce every input endpoint exactly, including internal pinned anchors.
sub('''                pts.add(new double[]{x, z});''', '''                pts.add(s == steps ? new double[]{p2x, p2z} : new double[]{x, z});''')
# Include virtual port edges in the same unique-successor invariant as in-window edges.
sub('''        for (Trace t : traces) {
            int[] c = t.cell;
            for (int i = 1; i < c.length; i++) {
                Integer prior = next.putIfAbsent(c[i - 1], c[i]);''', '''        for (Trace t : traces) {
            if (t.port != null) {
                BoundaryPort port = t.port;
                int dx = port.nextX() - port.anchorX(), dz = port.nextZ() - port.anchorZ();
                int step = Math.max(Math.abs(dx), Math.abs(dz));
                if (step <= 0) throw new IllegalStateException("zero-length port " + port);
                int to = port.cell() + dx / step * nz + dz / step;
                Integer prior = next.putIfAbsent(port.cell(), to);
                if (prior != null && prior != to) throw new IllegalStateException("port successor conflict " + port);
            }
            int[] c = t.cell;
            for (int i = 1; i < c.length; i++) {
                Integer prior = next.putIfAbsent(c[i - 1], c[i]);''')
p.write_text(t, encoding='utf-8')
port_path = src / 'BoundaryPort.java'
port_text = port_path.read_text(encoding='utf-8')
assert port_text.count('return sendKey() + "@" + anchorX + "," + anchorZ;') == 1
port_text = port_text.replace('return sendKey() + "@" + anchorX + "," + anchorZ;',
    'return sendKey() + "@" + anchorX + "," + anchorZ + "->" + nextX + "," + nextZ;')
port_path.write_text(port_text, encoding='utf-8')
# A planned-but-dry lake shore must not suppress an already published river crossing it.
provider_path = src / 'ModelHydrologyProvider.java'
provider_text = provider_path.read_text(encoding='utf-8')
anchor = '''                if (isLakeBlock) {
                    // ★ 断口现场 watch'''
assert provider_text.count(anchor) == 1
provider_text = provider_text.replace(anchor, '''                if (BOUNDARY_PORTS && isLakeBlock
                        && !isLakeAt(eroded, sharedLake ? sharedSurface : surface)) {
                    SegmentIndex.Hit shoreHit = segIndex.nearest(blockX, blockZ);
                    if (shoreHit == null && nearBorder) {
                        for (int t = 0; t < 9; t++) {
                            if (t == 4) continue;
                            if (neighDx[t] < 0 && blockX > regLoX + HALO) continue;
                            if (neighDx[t] > 0 && blockX < regHiX - HALO) continue;
                            if (neighDz[t] < 0 && blockZ > regLoZ + HALO) continue;
                            if (neighDz[t] > 0 && blockZ < regHiZ - HALO) continue;
                            RegionData rr = neigh[t] != null ? neigh[t] : regionFor(
                                    (rdRx + neighDx[t]) * REGION + REGION / 2,
                                    (rdRz + neighDz[t]) * REGION + REGION / 2);
                            if (rr.skeletonSeg() == null) continue;
                            SegmentIndex.Hit h = rr.skeletonSeg().nearest(blockX, blockZ);
                            if (h != null && (shoreHit == null || h.dist < shoreHit.dist)) shoreHit = h;
                        }
                    }
                    if (shoreHit != null && shoreHit.half > 0) {
                        isLakeBlock = false;
                        half = shoreHit.half; depth = shoreHit.depth; bestDist = shoreHit.dist;
                        if (Double.isFinite(shoreHit.surface)) surface = shoreHit.surface;
                    }
                }
                if (isLakeBlock) {
                    // ★ 断口现场 watch''')
provider_path.write_text(provider_text, encoding='utf-8')
classes = out / 'classes'
classes.mkdir()
cp = Path('build/local-flow-reassessment/classpath.txt').read_text(encoding='utf-8').strip()
sources = list(src.glob('*.java'))
# Reuse only read-only observers; never adopt their optimistic acceptance verdict as ours.
sources += [wire / 'java/com/geogenesis/worldgen/hydrology/model/PortWireAudit.java']
with (out / 'compile.log').open('wb') as f:
    result = subprocess.run(['javac', '-proc:none', '-encoding', 'UTF-8', '-cp', cp,
        '-d', str(classes), *map(str, sources)], stdout=f, stderr=subprocess.STDOUT)
print('OUT=' + str(out), flush=True)
print('compile exit=' + str(result.returncode), flush=True)
if result.returncode: print((out / 'compile.log').read_text(encoding='utf-8', errors='replace'))
(root / 'latest-integrated.txt').write_text(str(out), encoding='utf-8')
raise SystemExit(result.returncode)
