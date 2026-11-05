package com.geogenesis.standalone;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;

/**
 * Route-only S1 evidence for the approved four paths and R=8wu budget, not a worldgen gate.
 * All heights are direct callback samples, including the actual combined Cut at intersections.
 * Finite samples cannot certify an outlet, a true sink, or monotonicity between samples.
 * The caller owns whole-window profiles, fixed seeds, rendering and execution/exit status.
 */
public final class NoiseFollowingEvidence {
    private static final double SPACING = .037;
    private static final double TANGENT_DELTA = .02;
    private static final double FADE_RADIUS = Settings.defaults().radius();
    private static final String SCOPE = "LOCAL_FINITE_ROUTE_SAMPLES_ONLY; globalPassed=false; "
            + "completeChannelProven=false; no terminal/outlet/sink proof is supplied by Trace; "
            + "FLAT/NO_DESCENT_AT_RESOLUTION are finite stops, not true sinks; "
            + "approved R=8wu spatial fades; no whole-window or between-sample proof; "
            + "crossCount includes corners/degeneracies, which fail acceptance; "
            + "blank CSV heights and null minima mean rejected nonfinite/undefined evidence";

    private NoiseFollowingEvidence() {}

    /**
     * Returns only local sampled acceptance, never global or complete-channel acceptance.
     * Even a locally passing finite stop has no terminal proof. TRUNCATED cannot pass.
     * Nonfinite callback values reject their trace; all other traces are still written.
     */
    public static boolean write(Path out, HeightField carrier, HeightField old, Cut cut,
                                List<Trace> traces) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(old, "old");
        Objects.requireNonNull(cut, "cut");
        List<Trace> fixed = List.copyOf(traces);
        Files.createDirectories(out);
        List<Result> results = new ArrayList<>();
        try (BufferedWriter profiles = writer(out, "profiles.csv");
             BufferedWriter crosses = writer(out, "cross-sections.csv");
             BufferedWriter normals = writer(out, "normal-checks.csv");
             BufferedWriter longitudinal = writer(out, "longitudinal-checks.csv")) {
            row(profiles, "profile", "s_wu", "world_x", "world_z", "carrier_H", "old_H", "cut_H");
            row(crosses, "trace", "s_wu", "offset_wu", "world_x", "world_z",
                    "carrier_H", "old_H", "cut_H");
            row(longitudinal, "trace", "s_wu", "world_x", "world_z", "carrier_H", "old_H",
                    "cut_H", "h0Rise", "finalRise", "tailInterval", "nearTailPitOrRise");
            row(normals, "trace", "s_wu", "normal_kind", "normal_x", "normal_z", "eligible",
                    "h0_label", "final_label", "h0_min_offset_wu", "final_min_offset_wu",
                    "h0_left_bank_rise", "h0_right_bank_rise", "h0_left_025_rise",
                    "h0_right_025_rise", "h0_left_05_rise", "h0_right_05_rise",
                    "final_left_bank_rise", "final_right_bank_rise", "final_left_025_rise",
                    "final_right_025_rise", "final_left_05_rise", "final_right_05_rise");
            for (Trace trace : fixed) {
                Result result = new Result(trace);
                longitudinal(trace, carrier, old, cut, profiles, longitudinal, result);
                crossSections(trace, carrier, old, cut, crosses, normals, result);
                results.add(result);
            }
        }
        boolean passed = !results.isEmpty() && results.stream().allMatch(Result::passed);
        writeJson(out, results, passed);
        return passed;
    }

    private static BufferedWriter writer(Path out, String name) throws IOException {
        return Files.newBufferedWriter(out.resolve(name), StandardCharsets.UTF_8);
    }

    /** Shifted fine grid, every real vertex, both ends and all spatial fade boundaries. */
    private static TreeSet<Double> along(Trace trace) {
        TreeSet<Double> positions = new TreeSet<>(trace.arc());
        positions.add(0.0);
        positions.add(trace.length());
        for (long i = 0; ; i++) {
            double s = (i + .37) * SPACING;
            if (s >= trace.length()) break;
            positions.add(s);
        }
        positions.add(Math.min(FADE_RADIUS, trace.length()));
        positions.add(Math.max(0, trace.length() - FADE_RADIUS));
        fadeBoundaries(trace, trace.points().get(0), positions);
        fadeBoundaries(trace, trace.points().get(trace.points().size() - 1), positions);
        return positions;
    }

    /** Core fades use endpoint Euclidean distance, NOT nearest-point arc distance. */
    private static void fadeBoundaries(Trace trace, Point end, TreeSet<Double> positions) {
        for (int i = 1; i < trace.points().size(); i++) {
            Point a = trace.points().get(i - 1);
            Point b = trace.points().get(i);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double ex = a.x() - end.x();
            double ez = a.z() - end.z();
            double aa = dx * dx + dz * dz;
            double bb = 2 * (ex * dx + ez * dz);
            double cc = ex * ex + ez * ez - FADE_RADIUS * FADE_RADIUS;
            double discriminant = bb * bb - 4 * aa * cc;
            if (!(aa > 0) || discriminant < 0) continue;
            double root = Math.sqrt(discriminant);
            addRoot(trace, positions, i, (-bb - root) / (2 * aa));
            addRoot(trace, positions, i, (-bb + root) / (2 * aa));
        }
    }

    private static void addRoot(Trace trace, TreeSet<Double> positions, int i, double t) {
        if (Double.isFinite(t) && t >= 0 && t <= 1) {
            double s = trace.arc().get(i - 1)
                    + t * (trace.arc().get(i) - trace.arc().get(i - 1));
            positions.add(Math.max(0, Math.min(trace.length(), s)));
        }
    }

    private static void longitudinal(Trace trace, HeightField carrier, HeightField old, Cut cut,
                                     BufferedWriter profiles, BufferedWriter checks,
                                     Result result) throws IOException {
        Heights previous = null;
        double previousS = 0;
        Point end = trace.points().get(trace.points().size() - 1);
        for (double s : along(trace)) {
            Point p = NoiseFollowingErosion.pointAt(trace, s);
            Heights h = heights(carrier, old, cut, p, result);
            boolean h0Rise = previous != null && rises(previous.h0(), h.h0());
            boolean finalRise = previous != null && rises(previous.fin(), h.fin());
            boolean tail = s >= Math.max(0, trace.length() - FADE_RADIUS)
                    || distance(p, end) <= FADE_RADIUS
                    || distance(NoiseFollowingErosion.pointAt(trace, previousS), end) <= FADE_RADIUS;
            if (h0Rise) result.h0Rises++;
            if (finalRise) result.finalRises++;
            if (finalRise && tail) result.finalTailRises++;
            row(profiles, trace.id(), s, p.x(), p.z(), h.h0(), h.old(), h.fin());
            row(checks, trace.id(), s, p.x(), p.z(), h.h0(), h.old(), h.fin(),
                    h0Rise, finalRise, tail, finalRise && tail);
            previous = h;
            previousS = s;
        }
    }

    private static boolean rises(Double a, Double b) {
        return a != null && b != null && b > a;
    }

    private static TreeSet<Double> stations(Trace trace) {
        TreeSet<Double> positions = new TreeSet<>();
        for (double fraction : new double[] {.1, .25, .5, .75, .9}) {
            positions.add(fraction * trace.length());
        }
        positions.add(Math.max(0, trace.length() - 4));
        return positions;
    }

    private static TreeSet<Double> offsets() {
        TreeSet<Double> offsets = new TreeSet<>();
        for (int i = -96; i <= 96; i++) offsets.add(i * .125);
        Collections.addAll(offsets, -.5, -.25, 0.0, .25, .5);
        return offsets;
    }

    private static void crossSections(Trace trace, HeightField carrier, HeightField old, Cut cut,
                                      BufferedWriter crosses, BufferedWriter checks,
                                      Result result) throws IOException {
        for (double s : stations(trace)) {
            Frame frame = frame(trace, s);
            Point center = NoiseFollowingErosion.pointAt(trace, s);
            List<Across> samples = new ArrayList<>();
            for (double offset : frame.defined() ? offsets() : List.of(0.0)) {
                Point p = new Point(center.x() + offset * frame.nx(),
                        center.z() + offset * frame.nz());
                Heights h = heights(carrier, old, cut, p, result);
                samples.add(new Across(offset, h));
                row(crosses, trace.id(), s, offset, p.x(), p.z(), h.h0(), h.old(), h.fin());
            }
            Valley original = valley(samples, false);
            Valley fin = valley(samples, true);
            result.crossCount++;
            if (frame.eligible() && original.valley()) result.originalValleySections++;
            if (frame.eligible() && fin.valley()) result.finalValleySections++;
            else result.finalCrossFailures++;
            if (fin.minOffset() != null && frame.defined()) {
                double offset = Math.abs(fin.minOffset());
                result.maxMinOffsetWu = result.maxMinOffsetWu == null ? offset
                        : Math.max(result.maxMinOffsetWu, offset);
            }
            normalRow(checks, trace, s, frame, original, fin);
        }
    }

    /** Secant of the REAL polyline at arc +/- .02; a corner is never called smooth. */
    private static Frame frame(Trace trace, double s) {
        if (trace.length() == 0) return new Frame(0, 0, false, false, "ZERO_LENGTH_NO_NORMAL");
        Point a = NoiseFollowingErosion.pointAt(trace, Math.max(0, s - TANGENT_DELTA));
        Point b = NoiseFollowingErosion.pointAt(trace, Math.min(trace.length(), s + TANGENT_DELTA));
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double norm = Math.hypot(dx, dz);
        if (!(norm > 0) || !Double.isFinite(norm)) {
            return new Frame(0, 0, false, false, "DEGENERATE_SECANT_NO_NORMAL");
        }
        boolean interior = s > 0 && s < trace.length();
        String kind = interior ? "POLYLINE_ARC_SECANT" : "ENDPOINT_ONE_SIDED";
        boolean corner = false;
        for (int i = 1; i + 1 < trace.points().size(); i++) {
            double v = trace.arc().get(i);
            if (Math.abs(v - s) > TANGENT_DELTA || !turns(trace, i)) continue;
            boolean atVertex = Math.abs(v - s) <= 8 * Math.ulp(Math.max(1, s));
            if (atVertex) corner = true;
            kind = corner ? "CORNER_SAMPLE_NONUNIQUE_NORMAL" : "SECANT_SPANS_CORNER";
        }
        return new Frame(-dz / norm, dx / norm, true, interior && !corner, kind);
    }

    private static boolean turns(Trace trace, int i) {
        Point a = trace.points().get(i - 1);
        Point b = trace.points().get(i);
        Point c = trace.points().get(i + 1);
        double left = trace.arc().get(i) - trace.arc().get(i - 1);
        double right = trace.arc().get(i + 1) - trace.arc().get(i);
        double dx = (b.x() - a.x()) / left - (c.x() - b.x()) / right;
        double dz = (b.z() - a.z()) / left - (c.z() - b.z()) / right;
        return Math.hypot(dx, dz) > 1e-10;
    }

    /** Actual sampled two-bank AND near-side rises, not Hessian/curvature inference. */
    private static Valley valley(List<Across> samples, boolean fin) {
        Double center = valueAt(samples, 0, fin);
        Double minimum = null;
        Double minOffset = null;
        boolean valid = true;
        for (Across sample : samples) {
            Double h = value(sample, fin);
            if (h == null) { valid = false; continue; }
            if (minimum == null || h < minimum
                    || (h.equals(minimum) && Math.abs(sample.offset()) < Math.abs(minOffset))) {
                minimum = h;
                minOffset = sample.offset();
            }
        }
        double[] offsets = {-12, 12, -.25, .25, -.5, .5};
        List<Double> rises = new ArrayList<>();
        boolean banksAndNear = valid && center != null && minOffset != null;
        for (double offset : offsets) {
            Double h = valueAt(samples, offset, fin);
            Double rise = center == null || h == null ? null : finite(h - center);
            rises.add(rise);
            banksAndNear &= rise != null && rise > 0;
        }
        boolean valley = banksAndNear && Math.abs(minOffset) <= .25;
        return new Valley(valley, valid ? minOffset : null, rises,
                !valid ? "NONFINITE_REJECTED" : valley ? "VALLEY" : "SLOPE_SHOULDER");
    }

    private static Double valueAt(List<Across> samples, double offset, boolean fin) {
        for (Across sample : samples) {
            if (sample.offset() == offset) return value(sample, fin);
        }
        return null;
    }

    private static Double value(Across sample, boolean fin) {
        return fin ? sample.h().fin() : sample.h().h0();
    }

    private static void normalRow(BufferedWriter checks, Trace trace, double s, Frame frame,
                                  Valley original, Valley fin) throws IOException {
        List<Object> cells = new ArrayList<>();
        Collections.addAll(cells, trace.id(), s, frame.kind(),
                frame.defined() ? frame.nx() : null, frame.defined() ? frame.nz() : null,
                frame.eligible(), "RAW_" + original.label(), "FINAL_" + fin.label(),
                original.minOffset(), fin.minOffset());
        cells.addAll(original.rises());
        cells.addAll(fin.rises());
        row(checks, cells.toArray());
    }

    private static Heights heights(HeightField carrier, HeightField old, Cut cut,
                                   Point p, Result result) {
        Double h0 = sample(carrier, p);
        Double previous = sample(old, p);
        Double fin = sample(cut::height, p);
        if (h0 == null || previous == null || fin == null) result.finite = false;
        return new Heights(h0, previous, fin);
    }

    private static Double sample(HeightField field, Point p) {
        try {
            return finite(field.height(p.x(), p.z()));
        } catch (IllegalArgumentException failure) {
            // Cut rejects a nonfinite full-H callback before it can return a value.
            if (failure.getMessage() != null && failure.getMessage().startsWith("Nonfinite")) {
                return null;
            }
            throw failure;
        }
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private static void row(BufferedWriter out, Object... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i != 0) out.write(',');
            if (cells[i] == null) continue;
            String text = cells[i].toString();
            if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                    || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                out.write('"');
                out.write(text.replace("\"", "\"\""));
                out.write('"');
            } else out.write(text);
        }
        out.newLine();
    }

    private static void writeJson(Path out, List<Result> results, boolean passed) throws IOException {
        try (BufferedWriter json = writer(out, "bed-validation.json")) {
            json.write("{\n  \"passed\": " + passed + ",\n  \"scope\": " + quote(SCOPE)
                    + ",\n  \"traces\": [\n");
            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                json.write("    {\"id\":" + quote(r.trace.id())
                        + ",\"h0Rises\":" + r.h0Rises + ",\"finalRises\":" + r.finalRises
                        + ",\"finalTailRises\":" + r.finalTailRises + ",\"crossCount\":" + r.crossCount
                        + ",\"originalValleySections\":" + r.originalValleySections
                        + ",\"finalValleySections\":" + r.finalValleySections
                        + ",\"finalCrossFailures\":" + r.finalCrossFailures
                        + ",\"maxMinOffsetWu\":" + r.maxMinOffsetWu
                        + ",\"status\":" + quote(r.status()) + ",\"completeChannelProven\":false}");
                json.write(i + 1 == results.size() ? "\n" : ",\n");
            }
            json.write("  ]\n}\n");
        }
    }

    private static String quote(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c)) {
                        String hex = Integer.toHexString(c);
                        json.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else json.append(c);
                }
            }
        }
        return json.append('"').toString();
    }

    private record Heights(Double h0, Double old, Double fin) {}
    private record Across(double offset, Heights h) {}
    private record Frame(double nx, double nz, boolean defined, boolean eligible, String kind) {}
    private record Valley(boolean valley, Double minOffset, List<Double> rises, String label) {}

    private static final class Result {
        private final Trace trace;
        private boolean finite = true;
        private int h0Rises;
        private int finalRises;
        private int finalTailRises;
        private int crossCount;
        private int originalValleySections;
        private int finalValleySections;
        private int finalCrossFailures;
        private Double maxMinOffsetWu;

        private Result(Trace trace) { this.trace = trace; }

        private boolean finiteStop() {
            return trace.status().equals("FLAT") || trace.status().equals("NO_DESCENT_AT_RESOLUTION");
        }

        private boolean passed() {
            return finite && trace.length() > 0 && finiteStop() && h0Rises == 0
                    && finalRises == 0 && finalTailRises == 0 && crossCount > 0
                    && finalCrossFailures == 0;
        }

        private String status() {
            StringBuilder status = new StringBuilder(trace.status());
            if (!finite) status.append(";NONFINITE_REJECTED");
            if (trace.length() == 0) status.append(";ZERO_LENGTH");
            if (h0Rises > 0) status.append(";H0_ADVERSE_SLOPE");
            if (finalRises > 0) status.append(";FINAL_ADVERSE_SLOPE");
            if (finalTailRises > 0) status.append(";NEAR_TAIL_PIT_OR_RISE");
            if (finalCrossFailures > 0) status.append(";CROSS_SECTION_FAILURE");
            status.append(passed() ? ";LOCAL_SAMPLES_PASS" : ";LOCAL_SAMPLES_FAIL");
            return status.append(";TERMINAL_UNPROVEN;GLOBAL_UNPROVEN").toString();
        }
    }
}
