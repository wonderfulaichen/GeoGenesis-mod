package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.River;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Shaped;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Trace;

/**
 * Read-only, shape-aligned centre depths in blocks. No terrain queries or neighbour solves.
 * The caller must gate lake/sea columns before carving. Its nearest query keeps the same
 * winning segment; this class does not blend spatially neighbouring rivers.
 *
 * <p>Real confluence and serial edges match the receiver's finalized projected centre depth
 * only through a local 16-block tail transition, not by making the entire tributary as deep
 * as the trunk. Receivers are finalized first in reverse topological order. Water endpoints use
 * the existing pipe's bed and authoritative waterSurface, never level/depth approximations.
 * Unknown identity, unresolved water or a nonpositive depth imposes no endpoint constraint.
 * Observed depths are retained in evidence, but water endpoints constrain the profile only
 * when depth > 0.5 AND floor(surface) > floor(bed), matching the existing fill prerequisites.
 * Unsupported shallow interfaces remain unconstrained; they are not clamped to one block.
 * Supported depths between 0.5 and one block may still make the interface shallower.</p>
 *
 * <p>Each endpoint influences 16 blocks of arc length using smoothstep. If the two supports
 * overlap, one arc-linear interpolation joins the two exact endpoints instead of applying
 * competing fades. Widths, shape coordinates, traces and all pipeline arrays stay untouched.
 * Boundary half-width agreements, when supplied, anchor Hc on member traces only. Interior
 * projections pin both bracketing nodes so the interpolated anchor is exact. Boundary, head
 * and JOIN tail constraints share one plan, completed downstream-first; incompatible hard
 * constraints throw instead of silently overwriting a seam. Provider owns boundary discovery.</p>
 *
 * <p>Candidate limitations: endpoint bed/floor evidence currently uses coarse pipe.bed(),
 * not the actual nearby E from getChunkCells. Final interface validation still needs that
 * production terrain. Natural water depths may exceed the nominal width-law Hmax (e.g. sea
 * depth 20); no cap is imposed here. Fading that natural depth onto a river-side column can
 * request excessive new excavation within the 16-block transition. Lake/sea column gating
 * alone does not prevent this on land. Keep this risk visible while collecting evidence;
 * the current candidate is not a certified excavation policy.</p>
 */
final class RiverDepthProfile {
    static final double TRANSITION_BLOCKS = 16.0;
    private static final double ARC_EPS = 1e-9;

    private RiverDepthProfile() { }

    /** depth is the observation, NOT automatically a constraint; unsupported is not certified dry. */
    record Endpoint(int poly, String kind, int cell, int basin, String status,
                    double bed, double surface, double depth, boolean floorUsable) {
        boolean applied() { return Double.isFinite(depth) && depth > 0.5 && floorUsable; }
        double constraintDepth() { return applied() ? depth : Double.NaN; }
    }

    /** Optional evidence view; build() remains the simple production API. */
    record Result(List<double[]> depths, List<Endpoint> endpoints) {
        Result {
            depths = List.copyOf(depths);
            endpoints = List.copyOf(endpoints);
        }
    }

    static List<double[]> build(List<Shaped> polys, HydrologyPipeline.Result pipe,
                                       int ox, int oz, int n, int step, List<Trace> traces,
                                       List<River> rivers, List<double[]> halfSeqs) {
        return buildDetailed(polys, pipe, ox, oz, n, step, traces, rivers, halfSeqs).depths();
    }

    static List<double[]> build(List<Shaped> polys, HydrologyPipeline.Result pipe,
                                int ox, int oz, int n, int step, List<Trace> traces,
                                List<River> rivers, List<double[]> halfSeqs,
                                Map<Integer, Double> boundaryHalf) {
        return buildDetailed(polys, pipe, ox, oz, n, step, traces, rivers, halfSeqs, boundaryHalf).depths();
    }

    /** Null/short trace tables or null/empty cells provide no water identity; topology remains independent. */
    static Result buildDetailed(List<Shaped> polys, HydrologyPipeline.Result pipe,
                                       int ox, int oz, int n, int step, List<Trace> traces,
                                       List<River> rivers, List<double[]> halfSeqs) {
        return buildDetailed(polys, pipe, ox, oz, n, step, traces, rivers, halfSeqs, Map.of());
    }

    /** boundaryHalf contains only existing coarse-cell agreements; no neighbour queries are made. */
    static Result buildDetailed(List<Shaped> polys, HydrologyPipeline.Result pipe,
                                int ox, int oz, int n, int step, List<Trace> traces,
                                List<River> rivers, List<double[]> halfSeqs,
                                Map<Integer, Double> boundaryHalf) {
        List<double[]> out = initialDepths(polys, halfSeqs);
        if (out.isEmpty()) return new Result(out, List.of());
        requireGrid(pipe, n, step);
        double[][] arc = RiverWidthProfile.arcLengths(polys);
        List<List<double[]>> ownPins = boundaryPins(polys, arc, traces, boundaryHalf, ox, oz, n, step);
        int[] succ = new int[out.size()];
        Arrays.fill(succ, -1);
        if (rivers != null) for (River r : rivers) {
            RiverWidthProfile.chainEdges(succ, r, out.size(), rivers);
        }
        boolean[] cyclic = RiverWidthProfile.cycleNodes(succ, out.size());
        List<Endpoint> evidence = new ArrayList<>();
        for (int p : RiverWidthProfile.postOrder(succ, out.size())) {
            if (out.get(p).length == 0) continue;
            Trace t = traces != null && p < traces.size() ? traces.get(p) : null;
            Endpoint head = t != null && t.fromOutlet ? source(pipe, t, p, n) : null;
            Endpoint tail = mouth(pipe, t, p, n);
            if (head != null) evidence.add(head);
            if (tail != null) evidence.add(tail);
            double end = tail == null ? Double.NaN : tail.constraintDepth();
            int d = succ[p];
            if (d >= 0 && !cyclic[p] && !cyclic[d]) {
                end = receiverDepth(out, polys, arc, p, d);
            }
            List<double[]> plan = new ArrayList<>(ownPins.get(p));
            if (head != null && head.applied()) plan.add(new double[]{0, 0, head.constraintDepth()});
            double at = arc[p][arc[p].length - 1];
            if (Double.isFinite(end) && end > 0) plan.add(new double[]{at, at, end});
            applyDepthPlan(out.get(p), arc[p], plan);
        }
        return new Result(out, evidence);
    }

    /** Same cell-membership/projection contract as RiverWidthProfile.pinsByShape. */
    private static List<List<double[]>> boundaryPins(List<Shaped> polys, double[][] arc,
                                                     List<Trace> traces, Map<Integer, Double> boundaryHalf,
                                                     int ox, int oz, int n, int step) {
        List<List<double[]>> pins = new ArrayList<>(polys.size());
        for (int p = 0; p < polys.size(); p++) pins.add(new ArrayList<>());
        if (boundaryHalf == null || boundaryHalf.isEmpty() || traces == null) return pins;
        if (n <= 0 || step <= 0) throw new IllegalArgumentException("boundary pins need positive n/step");
        for (var entry : boundaryHalf.entrySet()) {
            Integer cell = entry.getKey();
            Double half = entry.getValue();
            if (cell == null || !validCell(cell, n) || half == null
                    || !Double.isFinite(half) || half <= 0) continue;
            double x = ox + cell / n * (double) step, z = oz + cell % n * (double) step;
            double depth = RiverCrossSection.centerDepth(2 * half);
            for (int p = 0; p < polys.size() && p < traces.size(); p++) {
                Trace t = traces.get(p);
                if (t == null || t.cell == null || !contains(t.cell, cell)) continue;
                Shaped sh = polys.get(p);
                if (sh == null || sh.size() == 0) continue;
                if (sh.size() == 1) {
                    pins.get(p).add(new double[]{0, 0, depth});
                    continue;
                }
                double[] hit = RiverWidthProfile.projectOnto(sh, arc[p], x, z);
                if (hit == null) continue;
                int seg = (int) hit[1];
                double lo = arc[p][seg], hi = arc[p][seg + 1];
                if (hit[2] <= ARC_EPS) hi = lo;
                else if (hit[2] >= 1 - ARC_EPS) lo = hi;
                pins.get(p).add(new double[]{lo, hi, depth});
            }
        }
        return pins;
    }

    private static boolean contains(int[] cells, int cell) {
        for (int c : cells) if (c == cell) return true;
        return false;
    }

    /** All hard platforms and endpoint pins are reconciled BEFORE any value is written. */
    static void applyDepthPlan(double[] row, double[] arc, List<double[]> pins) {
        if (row.length == 0 || pins.isEmpty()) return;
        List<double[]> sorted = new ArrayList<>();
        for (double[] pin : pins) sorted.add(pin.clone());
        sorted.sort(java.util.Comparator.<double[]>comparingDouble(a -> a[0])
                .thenComparingDouble(a -> a[1]).thenComparingDouble(a -> a[2]));
        List<double[]> plan = new ArrayList<>();
        for (double[] pin : sorted) {
            double[] last = plan.isEmpty() ? null : plan.get(plan.size() - 1);
            if (last != null && pin[0] <= last[1] + ARC_EPS) {
                if (pin[2] != last[2]) {
                    throw new IllegalStateException("conflicting depth pins at arc " + pin[0]
                            + ": " + last[2] + " vs " + pin[2]);
                }
                last[1] = Math.max(last[1], pin[1]);
            } else plan.add(pin);
        }
        writeDepthPlan(row, arc, plan);
    }

    /** Exact platforms; overlapping 16-block supports use one linear bridge, not repeated fades. */
    private static void writeDepthPlan(double[] row, double[] arc, List<double[]> plan) {
        for (int i = 0; i < row.length; i++) {
            double s = arc[i];
            double[] left = null, right = null, inside = null;
            for (double[] pin : plan) {
                if (s >= pin[0] - ARC_EPS && s <= pin[1] + ARC_EPS) { inside = pin; break; }
                if (pin[1] < s) left = pin;
                else if (pin[0] > s) { right = pin; break; }
            }
            if (inside != null) { row[i] = inside[2]; continue; }
            if (left != null && right != null && right[0] - left[1] < 2 * TRANSITION_BLOCKS) {
                double t = (s - left[1]) / (right[0] - left[1]);
                row[i] = left[2] + (right[2] - left[2]) * t;
                continue;
            }
            double[] pin = left == null ? right : right == null ? left
                    : s - left[1] < right[0] - s ? left : right;
            double distance = RiverWidthProfile.gapToRange(pin[0], pin[1], s);
            if (distance >= TRANSITION_BLOCKS) continue;
            double a = RiverWidthProfile.smoothstep01(distance / TRANSITION_BLOCKS);
            row[i] = pin[2] + (row[i] - pin[2]) * a;
        }
    }

    private static List<double[]> initialDepths(List<Shaped> polys, List<double[]> halves) {
        int count = polys == null ? 0 : polys.size();
        List<double[]> out = new ArrayList<>(count);
        if (count > 0 && (halves == null || halves.size() != count)) {
            throw new IllegalArgumentException("halfSeqs must align with polys");
        }
        for (int p = 0; p < count; p++) {
            Shaped sh = polys.get(p);
            int size = sh == null ? 0 : sh.size();
            double[] h = halves.get(p);
            if (h == null || h.length != size || (sh != null && sh.z.length != size)) {
                throw new IllegalArgumentException("shape/half sequence mismatch at poly " + p);
            }
            double[] row = new double[size];
            for (int i = 0; i < size; i++) {
                if (!Double.isFinite(h[i]) || h[i] <= 0
                        || !Double.isFinite(sh.x[i]) || !Double.isFinite(sh.z[i])) {
                    throw new IllegalArgumentException("invalid width/coordinate at poly " + p + " node " + i);
                }
                row[i] = RiverCrossSection.centerDepth(2 * h[i]);
                if (!Double.isFinite(row[i]) || row[i] <= 0) {
                    throw new IllegalStateException("invalid centre depth at poly " + p + " node " + i);
                }
            }
            out.add(row);
        }
        return out;
    }

    private static void requireGrid(HydrologyPipeline.Result pipe, int n, int step) {
        if (pipe != null && (n <= 0 || step <= 0 || pipe.nx() != n || pipe.nz() != n
                || pipe.cellBlocks() != step)) {
            throw new IllegalArgumentException("pipe grid must match n/step");
        }
    }

    /** Project only onto the topological receiver, whose own endpoint fades are finalized. */
    private static double receiverDepth(List<double[]> rows, List<Shaped> polys,
                                        double[][] arc, int up, int down) {
        Shaped sh = polys.get(up);
        if (rows.get(down).length == 0) return Double.NaN;
        if (rows.get(down).length == 1) return rows.get(down)[0];
        int last = sh.size() - 1;
        double[] hit = RiverWidthProfile.projectOnto(polys.get(down), arc[down], sh.x[last], sh.z[last]);
        return hit == null ? Double.NaN : RiverWidthProfile.lerpAt(rows.get(down), hit);
    }

    /** mouthCell already IS the wet cell. Do not advance parent or search nearby water. */
    private static Endpoint mouth(HydrologyPipeline.Result pipe, Trace t, int p, int n) {
        if (t == null) return null;
        int c = t.cell == null || t.cell.length == 0 ? -1 : t.mouthCell();
        if (t.end == DeterministicSkeleton.End.MOUTH_LAKE) {
            int basin = pipe == null || pipe.lakeResult() == null ? -1
                    : LakeWaterBalance.basinOf(pipe.lakeResult(), c);
            return lakeEndpoint(pipe, p, "LAKE_MOUTH", c, basin, n);
        }
        if (t.end != DeterministicSkeleton.End.MOUTH_SEA) return null;
        if (!validCell(c, n) || pipe == null || !marked(pipe.sea(), c)
                || marked(pipe.lake(), c)) return unknown(p, "SEA_MOUTH", c, -1, "UNKNOWN_NOT_SEA");
        return waterEndpoint(pipe, p, "SEA_MOUTH", c, -1);
    }

    /**
     * Source identity comes solely from basinOutlet. Read the eight adjacent cells, not the
     * nearest lake. Among known same-plane same-basin wet neighbours use the shallowest depth
     * (conservative shore section); equal-depth ties use cell order only for evidence.
     * Any unresolved same-basin wet neighbour or conflicting plane leaves the source unknown.
     */
    private static Endpoint source(HydrologyPipeline.Result pipe, Trace t, int p, int n) {
        int c = t.cell == null || t.cell.length == 0 ? -1 : t.sourceCell();
        var lakes = pipe == null ? null : pipe.lakeResult();
        int basin = DeterministicSkeleton.sourceBasinOf(lakes, c);
        if (!validCell(c, n) || basin < 0) {
            return unknown(p, "LAKE_SOURCE", c, basin, "UNKNOWN_IDENTITY");
        }
        Endpoint chosen = null;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            int x = c / n + dx, z = c % n + dz;
            if (x < 0 || z < 0 || x >= n || z >= n) continue;
            int k = x * n + z;
            if (!marked(pipe.lake(), k) || LakeWaterBalance.basinOf(lakes, k) != basin) continue;
            Endpoint candidate = lakeEndpoint(pipe, p, "LAKE_SOURCE", k, basin, n);
            if (!candidate.applied()) return candidate;
            if (chosen != null && Double.compare(chosen.surface(), candidate.surface()) != 0) {
                return unknown(p, "LAKE_SOURCE", c, basin, "UNKNOWN_CONFLICTING_SURFACE");
            }
            if (chosen == null || candidate.depth() < chosen.depth()) chosen = candidate;
        }
        return chosen == null ? unknown(p, "LAKE_SOURCE", c, basin, "UNKNOWN_NO_ADJACENT_LAKE") : chosen;
    }

    private static Endpoint lakeEndpoint(HydrologyPipeline.Result pipe, int p, String kind,
                                          int c, int basin, int n) {
        var lakes = pipe == null ? null : pipe.lakeResult();
        if (basin < 0 || lakes == null || LakeWaterBalance.basinOf(lakes, c) != basin) {
            return unknown(p, kind, c, basin, "UNKNOWN_IDENTITY");
        }
        if (!validCell(c, n) || !marked(pipe.lake(), c) || marked(pipe.sea(), c)) {
            return unknown(p, kind, c, basin, "UNKNOWN_NOT_LAKE");
        }
        return waterEndpoint(pipe, p, kind, c, basin);
    }

    private static Endpoint waterEndpoint(HydrologyPipeline.Result pipe, int p, String kind, int c, int basin) {
        if (!marked(pipe.resolved(), c) || pipe.bed() == null || c >= pipe.bed().length
                || pipe.waterSurface() == null || c >= pipe.waterSurface().length) {
            return unknown(p, kind, c, basin, "UNKNOWN_WATER");
        }
        double bed = pipe.bed()[c], surface = pipe.waterSurface()[c], depth = surface - bed;
        if (!Double.isFinite(bed) || !Double.isFinite(surface) || !Double.isFinite(depth) || depth <= 0) {
            return unknown(p, kind, c, basin, "UNKNOWN_NONPOSITIVE_DEPTH");
        }
        boolean usable = Math.floor(surface) > Math.floor(bed);
        String status = depth <= 0.5
                ? (usable ? "UNSUPPORTED_SHALLOW_FILL_DEPTH" : "UNSUPPORTED_SHALLOW_FILL_DEPTH_AND_FLOOR")
                : (usable ? "OK" : "UNSUPPORTED_SHALLOW_FLOOR");
        return new Endpoint(p, kind, c, basin, status, bed, surface, depth, usable);
    }

    private static boolean marked(boolean[] mask, int c) {
        return mask != null && c >= 0 && c < mask.length && mask[c];
    }

    private static boolean validCell(int c, int n) {
        return n > 0 && c >= 0 && (long) c < (long) n * n;
    }

    private static Endpoint unknown(int p, String kind, int c, int basin, String status) {
        return new Endpoint(p, kind, c, basin, status, Double.NaN, Double.NaN, Double.NaN, false);
    }

    /** One calculation per node: overlapping near-source/tail fades cannot overwrite each other. */
    static void blendEndpoints(double[] row, double[] arc, double head, double tail) {
        if (row.length == 0) return;
        List<double[]> pins = new ArrayList<>();
        if (Double.isFinite(head) && head > 0) pins.add(new double[]{0, 0, head});
        double end = arc[arc.length - 1];
        if (Double.isFinite(tail) && tail > 0) pins.add(new double[]{end, end, tail});
        applyDepthPlan(row, arc, pins);
    }
}
