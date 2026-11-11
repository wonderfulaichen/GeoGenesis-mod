package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.End;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.River;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.RiverLink;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Shaped;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Trace;

/** Synthetic array/topology tests only. Run main directly; any failed assertion exits nonzero. */
public final class RiverDepthProfileProbe {
    private static int pass, fail;

    private RiverDepthProfileProbe() { }

    public static void main(String[] args) {
        try {
            rawAndReadOnly();
            nullPipeAndTraceBoundaries();
            emptyCellBoundaries();
            degenerateShapeBoundaries();
            realTopologyAndProjection();
            serialFinalizedDownstream();
            confluencesAndCycles();
            confluenceFinalizedDownstream();
            lakeMouth();
            shallowInterfaceLegality();
            outletIdentity();
            seaMouth();
            shortOverlappingTransitions();
            boundaryProjectionAndMembership();
            boundaryJoinUnifiedPlan();
            boundaryReceiverWithOwnJoin();
            boundaryHeadAndConflicts();
            boundaryAnchorAcrossWindows(-444, 96);
            boundaryAnchorAcrossWindows(-228, 608);
            invalidInputs();
        } catch (Throwable ex) {
            fail++;
            ex.printStackTrace();
        }
        System.out.printf("RiverDepthProfileProbe PASS=%d FAIL=%d (synthetic only)%n", pass, fail);
        if (fail != 0) System.exit(1);
    }

    private static final class Fixture {
        final int n = 64;
        final int step;
        final double[] bed = new double[n * n], surface = new double[n * n];
        final boolean[] lake = new boolean[n * n], sea = new boolean[n * n], resolved = new boolean[n * n];
        final int[] ids = new int[n * n], outlets = {-1, -1};
        final int[] parent = new int[n * n];

        Fixture(int step) {
            this.step = step;
            Arrays.fill(bed, 100);
            Arrays.fill(surface, 100);
            Arrays.fill(ids, -1);
            Arrays.fill(resolved, true);
            Arrays.fill(parent, -1);
        }

        int cell(int x, int z) { return x * n + z; }

        void water(int c, int basin, double floor, double top) {
            ids[c] = basin;
            lake[c] = true;
            bed[c] = floor;
            surface[c] = top;
        }

        HydrologyPipeline.Result pipe() {
            var lakes = new LakeWaterBalance.Result(new double[n * n], lake, List.of(), 0, 1,
                    Map.of(), Map.of(), ids, outlets);
            // level/depth are deliberately wrong: only waterSurface - bed is authoritative.
            return new HydrologyPipeline.Result(n, n, step, bed, sea, resolved,
                    new double[n * n], new double[n * n], lake, parent, new double[n * n],
                    new boolean[n * n], new double[n * n], lakes, null,
                    new double[n * n], surface, 0, true, 0);
        }
    }

    private static Shaped line(double x0, double z0, double x1, double z1, int segments) {
        double[] x = new double[segments + 1], z = new double[segments + 1];
        for (int i = 0; i <= segments; i++) {
            x[i] = x0 + (x1 - x0) * i / segments;
            z[i] = z0 + (z1 - z0) * i / segments;
        }
        return new Shaped(x, z);
    }

    private static double[] filled(int n, double value) {
        double[] row = new double[n];
        Arrays.fill(row, value);
        return row;
    }

    private static List<double[]> build(List<Shaped> polys, Fixture f, List<Trace> traces,
                                         List<River> rivers, List<double[]> halves) {
        return RiverDepthProfile.build(polys, f == null ? null : f.pipe(), 0, 0,
                f == null ? 64 : f.n, f == null ? 1 : f.step, traces, rivers, halves);
    }

    private static void rawAndReadOnly() {
        Fixture f = new Fixture(1);
        Shaped sh = line(4, 4, 4, 44, 40);
        double[] half = new double[sh.size()];
        for (int i = 0; i < half.length; i++) half[i] = 0.5 + 0.1 * i;
        double[] copy = half.clone(), x = sh.x.clone(), z = sh.z.clone(), bed = f.bed.clone();
        List<double[]> out = build(List.of(sh), f, null, null, List.of(half));
        boolean same = true;
        for (int i = 0; i < half.length; i++) same &= out.get(0)[i] == RiverCrossSection.centerDepth(2 * half[i]);
        check("unconstrained depths call centreDepth at every original width", same);
        check("widths and shape are read-only", Arrays.equals(copy, half)
                && Arrays.equals(x, sh.x) && Arrays.equals(z, sh.z));
        check("pipeline bed is read-only", Arrays.equals(bed, f.bed));
        check("output is not width array alias", out.get(0) != half);
        check("null polys empty", RiverDepthProfile.build(null, null, 0, 0, 0, 0,
                null, null, null).isEmpty());
        Shaped empty = new Shaped(new double[0], new double[0]);
        check("empty shape aligned", build(List.of(empty), null, null, null,
                List.of(new double[0])).get(0).length == 0);
    }

    private static void nullPipeAndTraceBoundaries() {
        Shaped sh = line(4, 4, 4, 44, 40);
        List<double[]> halves = List.of(filled(41, 1));
        var baseline = build(List.of(sh), null, null, null, halves);
        for (End end : End.values()) {
            Trace t = new Trace(new int[]{1, 2}, end, true);
            var result = RiverDepthProfile.buildDetailed(List.of(sh), null, 0, 0, 0, 0,
                    List.of(t), null, halves);
            check("pipe=null preserves raw Hc for " + end, identical(result.depths(), baseline));
            check("pipe=null never certifies water endpoints for " + end,
                    result.endpoints().stream().noneMatch(RiverDepthProfile.Endpoint::applied));
        }
        Fixture f = new Fixture(1);
        for (List<Trace> traces : Arrays.asList(null, List.<Trace>of(), Arrays.<Trace>asList((Trace) null))) {
            var result = RiverDepthProfile.buildDetailed(List.of(sh), f.pipe(), 0, 0, f.n, f.step,
                    traces, null, halves);
            check("null/empty/null-entry traces preserve raw Hc", identical(result.depths(), baseline));
            check("missing traces produce no invented endpoints", result.endpoints().isEmpty());
        }
        var shortTable = RiverDepthProfile.buildDetailed(List.of(sh, sh), f.pipe(), 0, 0, f.n, f.step,
                List.of(new Trace(new int[]{1}, End.DEAD_END, false)), null,
                List.of(halves.get(0), halves.get(0)));
        check("short trace table still returns all shape rows", shortTable.depths().size() == 2
                && Arrays.equals(shortTable.depths().get(0), shortTable.depths().get(1)));
        check("empty polys detailed result empty", RiverDepthProfile.buildDetailed(List.of(), null,
                0, 0, 0, 0, null, null, null).depths().isEmpty());
        check("null polys detailed evidence empty", RiverDepthProfile.buildDetailed(null, null,
                0, 0, 0, 0, null, null, null).endpoints().isEmpty());
    }

    private static void emptyCellBoundaries() {
        Fixture f = new Fixture(1);
        Shaped sh = line(4, 4, 4, 44, 40);
        List<double[]> halves = List.of(filled(41, 1));
        var baseline = build(List.of(sh), null, null, null, halves);
        for (End end : End.values()) {
            for (Trace t : List.of(new Trace(new int[0], end, true),
                    new Trace(null, end, true, new double[0], new double[0], 0, null),
                    new Trace(new int[]{-1, f.n * f.n}, end, true))) {
                for (HydrologyPipeline.Result pipe : Arrays.asList(null, f.pipe())) {
                    var result = RiverDepthProfile.buildDetailed(List.of(sh), pipe, 0, 0, f.n, f.step,
                            List.of(t), null, halves);
                    check("empty/null/out-of-grid cells preserve raw Hc for " + end,
                            identical(result.depths(), baseline));
                    check("empty/null/out-of-grid cells cannot certify water for " + end,
                            result.endpoints().stream().noneMatch(RiverDepthProfile.Endpoint::applied));
                }
            }
        }
    }

    private static void degenerateShapeBoundaries() {
        Fixture f = new Fixture(1);
        Shaped empty = new Shaped(new double[0], new double[0]);
        Shaped point = new Shaped(new double[]{4}, new double[]{4});
        var result = RiverDepthProfile.buildDetailed(Arrays.asList(null, empty, point), f.pipe(),
                0, 0, f.n, f.step, List.of(new Trace(new int[0], End.MOUTH_LAKE, true)), null,
                List.of(new double[0], new double[0], new double[]{1}));
        check("null/empty/single-point shapes keep row alignment", result.depths().size() == 3
                && result.depths().get(0).length == 0 && result.depths().get(1).length == 0
                && result.depths().get(2).length == 1);
        near("unconstrained single point keeps raw Hc", result.depths().get(2)[0], RiverCrossSection.centerDepth(2));
        check("no water identity invented for empty shapes", result.endpoints().isEmpty());
        int c = f.cell(4, 4);
        f.water(c, 0, 99.75, 100);
        var wetPoint = RiverDepthProfile.buildDetailed(List.of(point), f.pipe(), 0, 0, f.n, f.step,
                List.of(new Trace(new int[]{c}, End.MOUTH_LAKE, false)), null, List.of(new double[]{1}));
        near("single-point unsupported shallow endpoint retains raw Hc", wetPoint.depths().get(0)[0], RiverCrossSection.centerDepth(2));
        near("single-point unsupported observation remains visible", wetPoint.endpoints().get(0).depth(), 0.25);
        check("single-point floor-usable H=.25 still fails fill prerequisite", !wetPoint.endpoints().get(0).applied()
                && wetPoint.endpoints().get(0).floorUsable());
        var edge = new River(new int[]{0, 1}, new int[]{1, 2}, new int[]{0, 1}, End.DEAD_END,
                false, f.n, -1, -1, RiverLink.TERMINAL);
        Shaped up = line(4, 0, 4, 4, 4);
        var noReceiver = build(List.of(up, empty), null, null, List.of(edge),
                List.of(filled(5, 1), new double[0]));
        near("empty topological receiver imposes no depth", last(noReceiver.get(0)), RiverCrossSection.centerDepth(2));
        var oneReceiver = build(List.of(up, point), null, null, List.of(edge),
                List.of(filled(5, 1), new double[]{3}));
        near("single-point topological receiver provides its only Hc", last(oneReceiver.get(0)), RiverCrossSection.centerDepth(6));
    }

    /** downstreamNode=0 refers to traceOf[0]=2, NOT shape 0 or receiver.traceIdx[0]. */
    private static void realTopologyAndProjection() {
        Shaped up = line(0, 0, 10, 25, 40);
        Shaped decoy = line(10, 25, 10, 65, 40); // closer/exact decoy cannot override topology
        Shaped recv = line(12, 0, 12, 40, 4);
        List<Shaped> polys = List.of(up, decoy, recv);
        List<double[]> halves = List.of(filled(up.size(), 0.5), filled(decoy.size(), 6),
                new double[]{1, 2, 3, 4, 5});
        var sender = new River(new int[]{0}, new int[]{1, 2}, new int[]{0, 0}, End.JOIN,
                false, 64, 1, 0, RiverLink.CONNECTED);
        var receiver = new River(new int[]{2}, new int[]{2, 3}, new int[]{2, 2}, End.DEAD_END,
                false, 64, -1, -1, RiverLink.TERMINAL);
        List<River> rivers = List.of(sender, receiver);
        List<double[]> out = build(polys, null, null, rivers, halves);
        double expected = (RiverCrossSection.centerDepth(6) + RiverCrossSection.centerDepth(8)) / 2;
        near("serial tail interpolates projected Hc, not coarse/node index or interpolated width", last(out.get(0)), expected);
        near("receiver head not mistaken for junction", out.get(2)[0], RiverCrossSection.centerDepth(2));
        near("closer nonreceiver left unchanged", last(out.get(1)), RiverCrossSection.centerDepth(12));
        near("serial fade leaves upstream beyond 16 blocks intact", out.get(0)[0],
                RiverCrossSection.centerDepth(1));
        var revSender = new River(sender.traceIdx, sender.cells, sender.traceOf, sender.end,
                false, 64, 0, 0, RiverLink.CONNECTED);
        List<double[]> reversed = build(polys, null, null, List.of(receiver, revSender), halves);
        check("remapped river enumeration is bit-identical", identical(out, reversed));
        List<double[]> unlinked = build(polys, null, null, List.of(), halves);
        near("no real edge means no proximity match", last(unlinked.get(0)), RiverCrossSection.centerDepth(1));
    }

    private static void serialFinalizedDownstream() {
        Fixture f = new Fixture(1);
        int mouth = f.cell(20, 58);
        f.water(mouth, 0, 99.25, 100);
        Shaped a = line(20, 10, 20, 40, 30);
        Shaped b = line(20, 40, 20, 48, 8);
        Shaped c = line(20, 48, 20, 58, 10);
        List<Trace> traces = List.of(
                new Trace(new int[]{f.cell(20, 10), f.cell(20, 40)}, End.JOIN, false),
                new Trace(new int[]{f.cell(20, 40), f.cell(20, 48)}, End.JOIN, false),
                new Trace(new int[]{f.cell(20, 48), mouth}, End.MOUTH_LAKE, false));
        var river = new River(new int[]{0, 1, 2}, new int[]{1, 2, 3}, new int[]{0, 1, 2},
                End.MOUTH_LAKE, false, 64, -1, -1, RiverLink.TERMINAL);
        var rows = build(List.of(a, b, c), f, traces, List.of(river),
                List.of(filled(31, 6), filled(9, 3), filled(11, 1)));
        near("serial chain middle reads finalized lake fade", last(rows.get(1)), rows.get(2)[0]);
        near("serial chain upstream reads finalized middle fade", last(rows.get(0)), rows.get(1)[0]);
        near("serial chain supported H<1 lake remains exact", last(rows.get(2)), 0.75);
        check("serial depth is not downstream raw-width depth", rows.get(1)[0] < RiverCrossSection.centerDepth(6));
        f.water(mouth, 0, 99.75, 100);
        List<Shaped> polys = List.of(a, b, c);
        List<double[]> halves = List.of(filled(31, 6), filled(9, 3), filled(11, 1));
        var unsupported = RiverDepthProfile.buildDetailed(polys, f.pipe(), 0, 0, f.n, f.step,
                traces, List.of(river), halves);
        check("unsupported lake H not propagated through serial chain", identical(unsupported.depths(),
                build(polys, null, traces, List.of(river), halves)));
        check("unsupported serial terminal still records rejected endpoint", unsupported.endpoints().size() == 1
                && !unsupported.endpoints().get(0).applied()
                && unsupported.endpoints().get(0).status().equals("UNSUPPORTED_SHALLOW_FILL_DEPTH"));
        near("serial rejected endpoint retains observed H", unsupported.endpoints().get(0).depth(), 0.25);
    }

    private static void confluencesAndCycles() {
        Shaped trunk = line(20, 0, 20, 60, 60), a = line(0, 30, 20, 30, 20);
        List<Shaped> polys = List.of(trunk, a);
        List<double[]> halves = List.of(filled(61, 6), filled(21, 0.5));
        var trunkUnit = new River(new int[]{0}, new int[]{10, 11, 12}, new int[]{0, 0, 0},
                End.DEAD_END, false, 64, -1, -1, RiverLink.TERMINAL);
        var tribUnit = new River(new int[]{1}, new int[]{4, 11}, new int[]{1, 1},
                End.JOIN, false, 64, 0, 1, RiverLink.CONNECTED);
        var out = build(polys, null, null, List.of(trunkUnit, tribUnit), halves);
        near("true tributary tail matches receiver at actual confluence", last(out.get(1)), out.get(0)[30]);
        near("trunk remains its own depth", out.get(0)[30], RiverCrossSection.centerDepth(12));
        for (int i = 0; i <= 4; i++) {
            near("tributary at/beyond 16-block tail boundary unchanged node=" + i,
                    out.get(1)[i], RiverCrossSection.centerDepth(1));
        }
        near("tributary 8 blocks before mouth smoothly matches", out.get(1)[12], 2.5);
        check("tributary retains shallow upstream instead of whole-branch equal depth",
                out.get(1)[0] < last(out.get(1)) && out.get(1)[19] < last(out.get(1)));
        var intoHead = new River(tribUnit.traceIdx, tribUnit.cells, tribUnit.traceOf, End.JOIN,
                false, 64, 0, 0, RiverLink.CONNECTED);
        var other = new River(new int[]{2}, new int[]{5, 10}, new int[]{2, 2}, End.JOIN,
                false, 64, 0, 0, RiverLink.CONNECTED);
        var multi = build(List.of(trunk, a, a), null, null, List.of(trunkUnit, intoHead, other),
                List.of(halves.get(0), halves.get(1), filled(21, 1)));
        near("two head inflows each match receiver locally", last(multi.get(1)), multi.get(0)[0]);
        near("second head inflow matches same finalized receiver", last(multi.get(2)), multi.get(0)[0]);
        near("first head inflow upstream remains its own depth", multi.get(1)[0], RiverCrossSection.centerDepth(1));
        near("second head inflow upstream remains its own depth", multi.get(2)[0], RiverCrossSection.centerDepth(2));
        var cycle0 = new River(new int[]{0}, new int[]{1}, new int[]{0}, End.JOIN,
                false, 64, 1, 0, RiverLink.CONNECTED);
        var cycle1 = new River(new int[]{1}, new int[]{2}, new int[]{1}, End.JOIN,
                false, 64, 0, 0, RiverLink.CONNECTED);
        check("cycles do not propagate depth", identical(build(polys, null, null, null, halves),
                build(polys, null, null, List.of(cycle0, cycle1), halves)));
    }

    /** Receiver has a lake fade; its final junction Hc must be read even with upstream index 0. */
    private static void confluenceFinalizedDownstream() {
        Fixture f = new Fixture(1);
        Shaped trib = line(0, 52.5, 20, 52.5, 40);
        Shaped trunk = line(20, 10, 20, 60, 50);
        f.water(f.cell(20, 60), 0, 99.25, 100);
        Trace a = new Trace(new int[]{f.cell(0, 52), f.cell(20, 52)}, End.JOIN, false);
        Trace b = new Trace(new int[]{f.cell(20, 10), f.cell(20, 52), f.cell(20, 60)}, End.MOUTH_LAKE, false);
        var upstream = new River(new int[]{0}, a.cell, new int[]{0, 0}, End.JOIN,
                false, 64, 1, 1, RiverLink.CONNECTED);
        var receiver = new River(new int[]{1}, b.cell, new int[]{1, 1, 1}, End.MOUTH_LAKE,
                false, 64, -1, -1, RiverLink.TERMINAL);
        List<Shaped> polys = List.of(trib, trunk);
        List<Trace> traces = List.of(a, b);
        List<double[]> halves = List.of(filled(41, 0.5), filled(51, 6));
        var rows = build(polys, f, traces, List.of(upstream, receiver), halves);
        // z=52.5 lies halfway between receiver shape nodes 42/43, inside the lake fade.
        double expected = (rows.get(1)[42] + rows.get(1)[43]) / 2;
        near("confluence tail matches finalized projected receiver, not raw width depth", last(rows.get(0)), expected);
        check("junction depth differs from receiver raw Hc", expected < RiverCrossSection.centerDepth(12));
        near("winning-segment Hc switch agrees exactly at projected junction", last(rows.get(0)), expected);
        near("0.5-block-resampled tributary unchanged exactly 16 blocks upstream", rows.get(0)[8], 1);
        near("tributary midpoint transition uses world arc not node count", rows.get(0)[24], (1 + expected) / 2);
        check("junction matching does not rewrite receiver profile", identical(
                build(List.of(trunk), f, List.of(b), null, List.of(halves.get(1))), List.of(rows.get(1))));
        var remapped = new River(upstream.traceIdx, upstream.cells, upstream.traceOf, End.JOIN,
                false, 64, 0, 1, RiverLink.CONNECTED);
        check("real confluence independent of remapped river enumeration", identical(rows,
                build(polys, f, traces, List.of(receiver, remapped), halves)));
        // Also exercise a deep tributary into this shallower receiving section: match down locally.
        var deep = build(polys, f, traces, List.of(upstream, receiver),
                List.of(filled(41, 6), halves.get(1)));
        near("deep tributary locally shallows to receiver", last(deep.get(0)), expected);
        near("deep tributary upstream is not lowered globally", deep.get(0)[0], RiverCrossSection.centerDepth(12));
    }

    private static void lakeMouth() {
        for (int step : new int[]{1, 2, 4}) {
            Fixture f = new Fixture(step);
            int mouth = f.cell(8, 20), decoy = f.cell(8, 21);
            f.water(mouth, 0, 100.2, 100.45); // H=.25, no integer water column
            f.water(decoy, 1, 80, 110);
            f.parent[mouth] = decoy; // advancing parent would select the wrong basin
            Shaped sh = line(8 * step, 20 * step - 40, 8 * step, 20 * step, 40);
            Trace t = new Trace(new int[]{f.cell(8, 19), mouth}, End.MOUTH_LAKE, false);
            var detail = RiverDepthProfile.buildDetailed(List.of(sh), f.pipe(), 0, 0, f.n, step,
                    List.of(t), null, List.of(filled(41, 2)));
            double[] row = detail.depths().get(0);
            near("unsupported lake mouth does not reduce tail step=" + step, last(row), RiverCrossSection.centerDepth(4));
            near("16 BLOCK boundary unchanged step=" + step, row[24], RiverCrossSection.centerDepth(4));
            near("unsupported lake leaves midpoint unchanged step=" + step, row[32], RiverCrossSection.centerDepth(4));
            var e = detail.endpoints().get(0);
            check("sub-block endpoint explicit unsupported evidence", !e.applied() && !e.floorUsable()
                    && e.cell() == mouth && e.basin() == 0 && e.status().startsWith("UNSUPPORTED_SHALLOW"));
            near("unsupported sub-block observation retained", e.depth(), 0.25);
            check("unsupported constraint is NaN, not observed shallow H", Double.isNaN(e.constraintDepth()));
            f.water(mouth, 0, 99.5, 100.25);
            var wetFloor = RiverDepthProfile.buildDetailed(List.of(sh), f.pipe(), 0, 0, f.n, step,
                    List.of(t), null, List.of(filled(41, 2)));
            check("supported .5<H<1 crosses floor and applies", wetFloor.endpoints().get(0).floorUsable()
                    && wetFloor.endpoints().get(0).applied());
            near("supported .75 lake depth not clamped to one", last(wetFloor.depths().get(0)), 0.75);
            near("supported .75 lake smooth midpoint", wetFloor.depths().get(0)[32],
                    (RiverCrossSection.centerDepth(4) + 0.75) / 2);
            f.ids[mouth] = -1;
            near("unknown basin never uses adjacent other lake", last(build(List.of(sh), f,
                    List.of(t), null, List.of(filled(41, 2))).get(0)), RiverCrossSection.centerDepth(4));
            f.ids[mouth] = 0;
            f.resolved[mouth] = false;
            near("unresolved mouth no constraint", last(build(List.of(sh), f,
                    List.of(t), null, List.of(filled(41, 2))).get(0)), RiverCrossSection.centerDepth(4));
            f.resolved[mouth] = true;
            f.lake[mouth] = false;
            near("known basin but dry mouth does not use neighbouring lake", last(build(List.of(sh), f,
                    List.of(t), null, List.of(filled(41, 2))).get(0)), RiverCrossSection.centerDepth(4));
            f.lake[mouth] = true;
            f.surface[mouth] = f.bed[mouth];
            near("zero lake depth is unknown, not a zero-depth pin", last(build(List.of(sh), f,
                    List.of(t), null, List.of(filled(41, 2))).get(0)), RiverCrossSection.centerDepth(4));
            f.surface[mouth] = f.bed[mouth] + 0.25;
            Shaped shifted = line(8 * step - 512, 20 * step - 40 - 768,
                    8 * step - 512, 20 * step - 768, 40);
            var translated = RiverDepthProfile.build(List.of(shifted), f.pipe(), -512, -768, f.n, step,
                    List.of(t), null, List.of(filled(41, 2)));
            var original = build(List.of(sh), f, List.of(t), null, List.of(filled(41, 2)));
            check("negative world translation preserves actual cell identity and arc", identical(translated, original));
        }
    }

    /** Legality applies equally to lake source/mouth and sea mouth; observations stay intact. */
    private static void shallowInterfaceLegality() {
        double[][] cases = {
                {99.75, 100, 0},       // H=.25, floor usable but fill rejects
                {99.75, 100.25, 0},   // H=.5, strict fill boundary rejects
                {100.125, 100.875, 0},// H=.75, floor same layer rejects
                {99.5, 100.25, 1},    // H=.75, both prerequisites pass
                {99.75, 100.250001, 1}, // just above strict .5 boundary
                {100.25, 100.5, 0}    // both prerequisites fail
        };
        for (String kind : List.of("LAKE_SOURCE", "LAKE_MOUTH", "SEA_MOUTH")) {
            for (double[] test : cases) {
                Fixture f = new Fixture(1);
                boolean source = kind.equals("LAKE_SOURCE"), supported = test[2] == 1;
                int cell = source ? f.cell(20, 9) : f.cell(20, 50);
                if (kind.equals("SEA_MOUTH")) f.sea[cell] = true;
                else f.water(cell, 0, test[0], test[1]);
                f.bed[cell] = test[0];
                f.surface[cell] = test[1];
                f.outlets[0] = f.cell(20, 10);
                End end = source ? End.DEAD_END : kind.equals("SEA_MOUTH") ? End.MOUTH_SEA : End.MOUTH_LAKE;
                Trace t = new Trace(new int[]{f.cell(20, 10), f.cell(20, 50)}, end, source);
                Shaped sh = line(20, 10, 20, 50, 40);
                List<double[]> halves = List.of(filled(41, 2));
                double[] bedBefore = f.bed.clone(), surfaceBefore = f.surface.clone();
                var result = RiverDepthProfile.buildDetailed(List.of(sh), f.pipe(), 0, 0, f.n, f.step,
                        List.of(t), null, halves);
                var endpoint = result.endpoints().get(0);
                String label = kind + " bed=" + test[0] + " S=" + test[1];
                double observed = test[1] - test[0];
                near("observed depth retained " + label, endpoint.depth(), observed);
                check("explicit application legality " + label, endpoint.applied() == supported);
                check("floor evidence independently retained " + label,
                        endpoint.floorUsable() == (Math.floor(test[1]) > Math.floor(test[0])));
                if (supported) {
                    near("supported constraint is observed H " + label, endpoint.constraintDepth(), observed);
                    near("supported shallow endpoint not clamped " + label,
                            result.depths().get(0)[source ? 0 : 40], observed);
                    check("supported status OK " + label, endpoint.status().equals("OK"));
                } else {
                    check("unsupported constraint NaN " + label, Double.isNaN(endpoint.constraintDepth()));
                    check("unsupported status explicit " + label, endpoint.status().startsWith("UNSUPPORTED_SHALLOW"));
                    check("unsupported interface leaves WHOLE profile bit-identical " + label,
                            identical(result.depths(), build(List.of(sh), null, null, null, halves)));
                }
                check("legality does not change bed or surface " + label,
                        Arrays.equals(bedBefore, f.bed) && Arrays.equals(surfaceBefore, f.surface));
            }
        }
    }

    private static void outletIdentity() {
        Fixture f = new Fixture(1);
        int source = f.cell(20, 20), own = f.cell(20, 19), foreign = f.cell(19, 20);
        f.outlets[0] = source;
        f.ids[source] = 1; // outlet identity intentionally differs from the source's basinId
        f.water(own, 0, 98, 100);
        f.water(foreign, 1, 70, 110);
        Shaped sh = line(20, 20, 20, 60, 40);
        Trace t = new Trace(new int[]{source, f.cell(20, 60)}, End.DEAD_END, true);
        List<double[]> halves = List.of(filled(41, 1));
        near("outlet uses sourceBasinOf not source basinId", build(List.of(sh), f, List.of(t), null, halves).get(0)[0], 2);
        f.water(f.cell(21, 19), 0, 99, 100);
        near("multiple valid shore cells use conservative shallow section", build(List.of(sh), f,
                List.of(t), null, halves).get(0)[0], 1);
        f.lake[own] = false;
        f.lake[f.cell(21, 19)] = false;
        f.water(f.cell(20, 18), 0, 90, 100); // same basin but NOT adjacent
        near("no adjacent own-basin lake does not search farther", build(List.of(sh), f,
                List.of(t), null, halves).get(0)[0], RiverCrossSection.centerDepth(2));
        f.lake[own] = true;
        f.outlets[1] = source;
        near("ambiguous source basin imposes nothing", build(List.of(sh), f,
                List.of(t), null, halves).get(0)[0], RiverCrossSection.centerDepth(2));
        f.outlets[1] = -1;
        f.water(f.cell(21, 19), 0, 97, 101);
        near("conflicting own-basin water planes stay unknown", build(List.of(sh), f,
                List.of(t), null, halves).get(0)[0], RiverCrossSection.centerDepth(2));
    }

    private static void seaMouth() {
        Fixture f = new Fixture(1);
        int mouth = f.cell(20, 50);
        f.sea[mouth] = true;
        f.bed[mouth] = 43;
        f.surface[mouth] = 63;
        Shaped sh = line(20, 10, 20, 50, 40);
        Trace t = new Trace(new int[]{f.cell(20, 49), mouth}, End.MOUTH_SEA, false);
        double[] before = f.bed.clone(), surfaces = f.surface.clone();
        var rows = build(List.of(sh), f, List.of(t), null, List.of(filled(41, 1)));
        near("actual sea cell depth, not guessed sea level", last(rows.get(0)), 20);
        near("sea influence only final 16 blocks", rows.get(0)[24], RiverCrossSection.centerDepth(2));
        near("candidate sea midpoint retains uncapped natural-depth fade", rows.get(0)[32],
                (RiverCrossSection.centerDepth(2) + 20) / 2);
        check("KNOWN RISK: river-side non-sea transition Hc exceeds nominal Hmax",
                !f.sea[f.cell(20, 42)] && rows.get(0)[32] > RiverCrossSection.CENTER_DEPTH_MAX);
        System.out.println("KNOWN_LIMITATION coarse pipe.bed is not nearby getChunkCells E; "
                + "natural sea depth=20 yields river-side Hc=" + rows.get(0)[32]
                + "; excavation safety NOT VERIFIED, no cap added");
        check("sea bed/surface not carved or written", Arrays.equals(before, f.bed) && Arrays.equals(surfaces, f.surface));
        f.sea[mouth] = false;
        near("sea end label without actual sea mask imposes nothing", last(build(List.of(sh), f,
                List.of(t), null, List.of(filled(41, 1))).get(0)), RiverCrossSection.centerDepth(2));
        f.sea[mouth] = true;
        f.surface[mouth] = Double.NaN;
        near("nonfinite sea surface no constraint", last(build(List.of(sh), f,
                List.of(t), null, List.of(filled(41, 1))).get(0)), RiverCrossSection.centerDepth(2));
    }

    private static void shortOverlappingTransitions() {
        Fixture f = new Fixture(1);
        int source = f.cell(20, 20), mouth = f.cell(20, 40);
        f.outlets[0] = source;
        f.water(f.cell(20, 19), 0, 98, 100);
        f.water(mouth, 1, 99.25, 100);
        Shaped sh = line(20, 20, 20, 40, 20);
        Trace t = new Trace(new int[]{source, mouth}, End.MOUTH_LAKE, true);
        double[] row = build(List.of(sh), f, List.of(t), null, List.of(filled(21, 5))).get(0);
        boolean linear = true;
        for (int i = 0; i < row.length; i++) linear &= Math.abs(row[i] - (2 - 1.25 * i / 20)) < 1e-12;
        check("supported overlapping fades use one deterministic arc-linear interpolation", linear);
        near("overlap source exact, not pressed by mouth", row[0], 2);
        near("overlap supported mouth exact, not raised by source", last(row), 0.75);
        f.water(mouth, 1, 99.75, 100);
        var rejected = RiverDepthProfile.buildDetailed(List.of(sh), f.pipe(), 0, 0, f.n, f.step,
                List.of(t), null, List.of(filled(21, 5)));
        f.lake[mouth] = false;
        check("unsupported short mouth cannot press a supported source fade", identical(rejected.depths(),
                build(List.of(sh), f, List.of(t), null, List.of(filled(21, 5)))));
        near("supported source remains exact when shallow mouth rejected", rejected.depths().get(0)[0], 2);
        near("rejected mouth does not flatten short profile to shallow H", last(rejected.depths().get(0)),
                RiverCrossSection.centerDepth(10));
        double[] nonuniform = {7, 7, 7};
        RiverDepthProfile.blendEndpoints(nonuniform, new double[]{0, 2, 20}, 2, 0.25);
        near("overlap uses real arc, not node index", nonuniform[1], 1.825);
        double[] zero = {3};
        expect("conflicting endpoints on zero length explicit", IllegalStateException.class,
                () -> RiverDepthProfile.blendEndpoints(zero, new double[]{0}, 2, 0.25));
    }

    private static void boundaryProjectionAndMembership() {
        Fixture f = new Fixture(1);
        int cell = f.cell(20, 31);
        Shaped sh = line(20, 10, 20, 70, 30); // anchor z31 lies INSIDE [30,32]
        Trace member = new Trace(new int[]{cell}, End.DEAD_END, false);
        Trace nonmember = new Trace(new int[]{f.cell(20, 30)}, End.DEAD_END, false);
        List<double[]> halves = List.of(filled(31, 0.5), filled(31, 6));
        double[] x = sh.x.clone(), z = sh.z.clone(), widths = halves.get(0).clone();
        Map<Integer, Double> map = Map.of(cell, 2.0);
        var rows = RiverDepthProfile.build(List.of(sh, sh), null, 0, 0, f.n, 1,
                List.of(member, nonmember), null, halves, map);
        double target = RiverCrossSection.centerDepth(4);
        near("interior boundary platform left bracket exact", rows.get(0)[10], target);
        near("interior boundary platform right bracket exact", rows.get(0)[11], target);
        double[][] arc = RiverWidthProfile.arcLengths(List.of(sh));
        double[] hit = RiverWidthProfile.projectOnto(sh, arc[0], 20, 31);
        near("interior boundary interpolated anchor EXACT", RiverWidthProfile.lerpAt(rows.get(0), hit), target);
        near("nonmember coincident river not pinned", rows.get(1)[10], RiverCrossSection.centerDepth(12));
        near("pin influence 16 blocks beyond platform unchanged", rows.get(0)[19], 1);
        near("pin influence 8 blocks beyond platform smooth", rows.get(0)[15], (target + 1) / 2);
        check("boundary input widths and coordinates read-only", Arrays.equals(x, sh.x)
                && Arrays.equals(z, sh.z) && Arrays.equals(widths, halves.get(0)) && map.get(cell) == 2);
        var legacy = build(List.of(sh), null, List.of(member), null, List.of(halves.get(0)));
        var emptyMap = RiverDepthProfile.build(List.of(sh), null, 0, 0, f.n, 1,
                List.of(member), null, List.of(halves.get(0)), Map.of());
        var nullMap = RiverDepthProfile.buildDetailed(List.of(sh), null, 0, 0, f.n, 1,
                List.of(member), null, List.of(halves.get(0)), null);
        check("legacy/empty/null map overloads bit-identical", identical(legacy, emptyMap)
                && identical(legacy, nullMap.depths()));
        Trace empty = new Trace(new int[0], End.DEAD_END, false);
        check("empty trace cannot acquire nearby boundary pin", identical(legacy,
                RiverDepthProfile.build(List.of(sh), null, 0, 0, f.n, 1,
                        List.of(empty), null, List.of(halves.get(0)), map)));
    }

    private static void boundaryJoinUnifiedPlan() {
        Fixture f = new Fixture(1);
        int anchor = f.cell(20, 31), own = f.cell(0, 24);
        Shaped up = line(-20, 31, 20, 31, 40);
        Shaped down = line(20, 10, 20, 70, 30);
        Trace a = new Trace(new int[]{own, anchor}, End.JOIN, false);
        Trace b = new Trace(new int[]{f.cell(20, 10), anchor, f.cell(20, 60)}, End.DEAD_END, false);
        var sender = new River(new int[]{0}, a.cell, new int[]{0, 0}, End.JOIN,
                false, 64, 1, 1, RiverLink.CONNECTED);
        var receiver = new River(new int[]{1}, b.cell, new int[]{1, 1, 1}, End.DEAD_END,
                false, 64, -1, -1, RiverLink.TERMINAL);
        List<Shaped> polys = List.of(up, down);
        List<Trace> traces = List.of(a, b);
        List<double[]> halves = List.of(filled(41, 0.5), filled(31, 6));
        var rows = RiverDepthProfile.build(polys, null, 0, 0, f.n, 1, traces,
                List.of(sender, receiver), halves, Map.of(anchor, 2.0));
        double target = RiverCrossSection.centerDepth(4);
        near("boundary JOIN tail agrees with receiver already pinned", last(rows.get(0)), target);
        near("receiver bracket pin is not later overwritten", rows.get(1)[10], target);
        near("sender JOIN stays local beyond 16 blocks", rows.get(0)[24], 1);
        near("sender JOIN and duplicate boundary tail merge exact", rows.get(0)[32], (target + 1) / 2);
        var remapped = new River(sender.traceIdx, sender.cells, sender.traceOf, End.JOIN,
                false, 64, 0, 1, RiverLink.CONNECTED);
        check("boundary JOIN downstream-first independent of river order", identical(rows,
                RiverDepthProfile.build(polys, null, 0, 0, f.n, 1, traces,
                        List.of(receiver, remapped), halves, Map.of(anchor, 2.0))));
        // Boundary x0,z31 is 20 blocks before tail: a different own pin's fade overlaps JOIN.
        int near = f.cell(0, 31);
        Trace nearTrace = new Trace(new int[]{near, anchor}, End.JOIN, false);
        var multiple = RiverDepthProfile.build(polys, null, 0, 0, f.n, 1,
                List.of(nearTrace, b), List.of(sender, receiver), halves,
                Map.of(near, 1.0, anchor, 2.0));
        near("own upstream pin remains exact with overlapping JOIN fade", multiple.get(0)[20], RiverCrossSection.centerDepth(2));
        near("JOIN remains exact with own pin 20 blocks upstream", last(multiple.get(0)), target);
        near("overlapping own/JOIN supports use unified linear bridge", multiple.get(0)[30],
                (RiverCrossSection.centerDepth(2) + target) / 2);
    }

    /** The original seam hazard: receiver's own later JOIN fade would rewrite an interior anchor. */
    private static void boundaryReceiverWithOwnJoin() {
        int n = 64, anchor = 20 * n + 30;
        Shaped a = line(-20, 30, 20, 30, 40);
        Shaped b = line(20, 10, 20, 40, 30);
        Shaped c = line(20, 40, 20, 60, 20);
        Trace ta = new Trace(new int[]{anchor}, End.JOIN, false);
        Trace tb = new Trace(new int[]{20 * n + 10, anchor, 20 * n + 40}, End.JOIN, false);
        Trace tc = new Trace(new int[]{20 * n + 40, 20 * n + 60}, End.DEAD_END, false);
        var r0 = new River(new int[]{0}, ta.cell, new int[]{0}, End.JOIN, false, n, 1, 1, RiverLink.CONNECTED);
        var r1 = new River(new int[]{1, 2}, new int[]{1, 2, 3}, new int[]{1, 1, 2},
                End.DEAD_END, false, n, -1, -1, RiverLink.TERMINAL);
        List<Shaped> polys = List.of(a, b, c);
        List<Trace> traces = List.of(ta, tb, tc);
        List<River> rivers = List.of(r0, r1);
        List<double[]> halves = List.of(filled(41, 1), filled(31, 1), filled(21, 6));
        var old = build(polys, null, traces, rivers, halves);
        double target = RiverCrossSection.centerDepth(2);
        check("fixture JOIN alone really changes receiver anchor despite same nominal widths",
                old.get(1)[20] > target && last(old.get(0)) > target);
        var rows = RiverDepthProfile.buildDetailed(polys, null, 0, 0, n, 1,
                traces, rivers, halves, Map.of(anchor, 1.0)).depths();
        near("receiver own JOIN cannot overwrite boundary anchor", rows.get(1)[20], target);
        near("upstream tail reads receiver boundary final value", last(rows.get(0)), target);
        near("receiver own JOIN tail still matches downstream", last(rows.get(1)), rows.get(2)[0]);
        near("near boundary and own JOIN bridge once", rows.get(1)[25], (target + rows.get(2)[0]) / 2);
        check("sender boundary plus receiver final value are bit exact", last(rows.get(0)) == rows.get(1)[20]);
    }

    private static void boundaryHeadAndConflicts() {
        Fixture f = new Fixture(1);
        int source = f.cell(20, 10), pin = f.cell(20, 30);
        f.outlets[0] = source;
        f.water(f.cell(20, 9), 0, 98, 100);
        Shaped sh = line(20, 10, 20, 50, 40);
        Trace t = new Trace(new int[]{source, pin, f.cell(20, 50)}, End.DEAD_END, true);
        var rows = RiverDepthProfile.build(List.of(sh), f.pipe(), 0, 0, f.n, 1,
                List.of(t), null, List.of(filled(41, 0.5)), Map.of(pin, 3.0));
        near("head source remains exact near boundary fade", rows.get(0)[0], 2);
        near("boundary remains exact near head fade", rows.get(0)[20], RiverCrossSection.centerDepth(6));
        near("head/boundary overlapping supports bridge once", rows.get(0)[10],
                (2 + RiverCrossSection.centerDepth(6)) / 2);
        near("boundary fade leaves far tail raw", last(rows.get(0)), 1);
        expect("conflicting hard head and boundary not silently overwritten", IllegalStateException.class,
                () -> RiverDepthProfile.build(List.of(sh), f.pipe(), 0, 0, f.n, 1,
                        List.of(t), null, List.of(filled(41, 0.5)), Map.of(source, 3.0)));
        Shaped sparse = line(20, 10, 20, 50, 4);
        Trace two = new Trace(new int[]{f.cell(20, 21), f.cell(20, 22)}, End.DEAD_END, false);
        expect("overlapping different-value boundary platforms fail explicitly", IllegalStateException.class,
                () -> RiverDepthProfile.build(List.of(sparse), null, 0, 0, f.n, 1,
                        List.of(two), null, List.of(filled(5, 1)), Map.of(f.cell(20, 21), 1.0, f.cell(20, 22), 2.0)));
        var first = new java.util.LinkedHashMap<Integer, Double>();
        first.put(source, 1.0); first.put(pin, 2.0);
        var reverse = new java.util.LinkedHashMap<Integer, Double>();
        reverse.put(pin, 2.0); reverse.put(source, 1.0);
        Trace plain = new Trace(t.cell, End.DEAD_END, false);
        check("boundary map order has no effect", identical(
                RiverDepthProfile.build(List.of(sh), null, 0, 0, f.n, 1, List.of(plain), null,
                        List.of(filled(41, 0.5)), first),
                RiverDepthProfile.build(List.of(sh), null, 0, 0, f.n, 1, List.of(plain), null,
                        List.of(filled(41, 0.5)), reverse)));
    }

    /** World coordinates from the reported failures, with synthetic shapes (not nine-owner evidence). */
    private static void boundaryAnchorAcrossWindows(int x, int z) {
        int n = 64, step = 2, ox = x - 40;
        Shaped sender = line(x, z - 40, x, z, 40);
        Shaped recv = line(x, z - 21, x, z + 39, 30); // anchor between two shape nodes
        int sendOz = z - 80, recvOz = z - 40;
        int sc = 20 * n + 40, rc = 20 * n + 20;
        Trace st = new Trace(new int[]{sc}, End.BOUNDARY_EXIT, false);
        Trace rt = new Trace(new int[]{rc}, End.DEAD_END, false);
        double half = 1.25, target = RiverCrossSection.centerDepth(2 * half);
        var a = RiverDepthProfile.build(List.of(sender), null, ox, sendOz, n, step,
                List.of(st), null, List.of(filled(41, half)), Map.of(sc, half));
        var b = RiverDepthProfile.build(List.of(recv), null, ox, recvOz, n, step,
                List.of(rt), null, List.of(filled(31, 4)), Map.of(rc, half));
        double[] hit = RiverWidthProfile.projectOnto(recv, RiverWidthProfile.arcLengths(List.of(recv))[0], x, z);
        near("sender boundary exact at reported world anchor " + x + "," + z, last(a.get(0)), target);
        near("receiver interior boundary exact at reported world anchor " + x + "," + z,
                RiverWidthProfile.lerpAt(b.get(0), hit), target);
        near("two independently built window anchors equal " + x + "," + z,
                last(a.get(0)), RiverWidthProfile.lerpAt(b.get(0), hit));
    }

    private static void invalidInputs() {
        Shaped sh = line(0, 0, 0, 20, 20);
        expect("misaligned width row explicit", IllegalArgumentException.class,
                () -> build(List.of(sh), null, null, null, List.of(new double[2])));
        expect("NaN width explicit", IllegalArgumentException.class,
                () -> build(List.of(sh), null, null, null, List.of(filled(21, Double.NaN))));
        Fixture f = new Fixture(1);
        expect("pipe grid mismatch explicit", IllegalArgumentException.class,
                () -> RiverDepthProfile.build(List.of(sh), f.pipe(), 0, 0, 32, 1,
                        null, null, List.of(filled(21, 1))));
    }

    private static boolean identical(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    private static double last(double[] row) { return row[row.length - 1]; }

    private static void near(String name, double actual, double expected) {
        check(name + " actual=" + actual + " expected=" + expected, Math.abs(actual - expected) < 1e-10);
    }

    private static void expect(String name, Class<? extends Throwable> type, Runnable action) {
        try { action.run(); check(name, false); }
        catch (Throwable ex) { check(name, type.isInstance(ex)); }
    }

    private static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }
}
