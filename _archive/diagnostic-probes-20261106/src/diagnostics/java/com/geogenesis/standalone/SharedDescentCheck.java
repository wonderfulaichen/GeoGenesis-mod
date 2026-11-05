package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import com.geogenesis.standalone.SharedDescentGraph.Node;
import com.geogenesis.standalone.SharedDescentGraph.State;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Zero-MC phase-A contracts. Finite evidence is explicitly not a basin/continuous proof. */
public final class SharedDescentCheck {
    private SharedDescentCheck() {}
    public record Report(int nodes, int edges, int fineChecked, int fineValidationFail,
                         int reverseCold, int parallelCold) {}
    public record StopEvidence(String classification, double witnessLengthWu, int witnessFinalRises,
                               boolean finerDescent, boolean finiteCircleCandidate) {}

    public static Report check(SharedDescentGraph graph, Supplier<HeightField> cold) {
        int[] indegrees = new int[graph.size()];
        int edges = 0, fine = 0, failed = 0;
        HeightField fullSource = cold.get();
        for (int id = 0; id < graph.size(); id++) {
            Node n = graph.node(id), next = graph.next(n), root = graph.node(n.rootId());
            require(n.id() == id && graph.idAt(n.ix(), n.iz()) == id, "row-major id/coordinates");
            require(n.x() == n.ix() * 4.0 && n.z() == n.iz() * 4.0, "exact world grid");
            require(root.nextId() == -1, "terminal root");
            if (next == null) {
                require(n.state() != State.DESCENT && n.rootId() == id
                        && n.downstreamLengthWu() == 0, "terminal semantics");
            } else {
                edges++; indegrees[next.id()]++;
                require(n.state() == State.DESCENT && next.h0() < n.h0(), "strict descent/no cycles");
                require(next == graph.node(n.nextId()), "shared suffix canonical reference");
                require(n.rootId() == next.rootId(), "root recurrence");
                require(bits(n.downstreamLengthWu(), Math.hypot(next.x() - n.x(), next.z() - n.z())
                        + next.downstreamLengthWu()), "actual untruncated grid length");
                require(n.samples() >= 2 && n.minDrop() >= 0, "edge sampled evidence");
                SharedDescentGraph.Validation v = SharedDescentGraph.validate(fullSource,
                        n.x(), n.z(), next.x(), next.z(), .25, 0);
                require(v.accepted() && v.samples() == n.samples() && bits(v.minDrop(), n.minDrop()),
                        "all edges full H0 sampled replay exact");
            }
        }
        for (int id = 0; id < graph.size(); id++) {
            require(graph.node(id).indegree() == indegrees[id], "deterministic indegree");
        }
        HeightField fineSource = cold.get();
        // Evenly spaced EDGE ORDINALS, not successful results; at most 512 distinct edges.
        int wanted = Math.min(512, edges), ordinal = 0;
        for (int id = 0; id < graph.size() && fine < wanted; id++) {
            Node n = graph.node(id), next = graph.next(n);
            if (next == null) continue;
            if (ordinal++ != (int) ((long) fine * edges / wanted)) continue;
            if (!SharedDescentGraph.validate(fineSource, n.x(), n.z(), next.x(), next.z(),
                    .137, .37).accepted()) failed++;
            fine++;
        }
        int count = Math.min(64, graph.size());
        for (int i = count - 1; i >= 0; i--) replay(graph, cold.get(), replayId(i, graph.size()));
        IntStream.range(0, count).parallel().forEach(i ->
                replay(graph, cold.get(), replayId(i, graph.size())));
        return new Report(graph.size(), edges, fine, failed, count, count);
    }

    private static int replayId(int i, int size) { return (int) ((long) i * size / Math.min(64, size)); }
    private static void replay(SharedDescentGraph graph, HeightField field, int id) {
        Node n = graph.node(id);
        SharedDescentGraph.Choice c = graph.choose(field, id);
        require(c.nextId() == n.nextId() && c.state() == n.state(), "cold next/state exact");
        require(c.samples() == n.samples() && bits(c.minDrop(), n.minDrop()), "cold sample evidence exact");
        require(bits(SharedDescentGraph.sample(field, n.x(), n.z()), n.h0()), "cold H0 exact");
    }

    /** All old truncations retain computation-cutoff classification, even with a finer witness. */
    public static StopEvidence auditStop(HeightField field, String id, String status, double x, double z) {
        Trace witness = NoiseFollowingErosion.trace(field, id, x, z,
                new Settings(8, 0, .25, .015625, 8, .0625));
        int rises = traceRises(field, witness, .0073);
        boolean descent = witness.length() > .125 && rises == 0;
        boolean circle = !descent && finiteCircle(field, x, z);
        String classification = status.equals("TRUNCATED") ? "COMPUTATION_TRUNCATED"
                : descent ? "MISSED_DESCENT_WITNESS"
                : circle ? "LOCAL_BASIN_CANDIDATE_FINITE_ONLY" : "UNRESOLVED_FINITE_SAMPLES";
        return new StopEvidence(classification, witness.length(), rises, descent, circle);
    }

    private static boolean finiteCircle(HeightField field, double x, double z) {
        double center = SharedDescentGraph.sample(field, x, z);
        for (double radius : new double[]{.03125, .0625, .125}) {
            for (int i = 0; i < 64; i++) {
                double angle = i * Math.PI / 32;
                if (SharedDescentGraph.sample(field, x + radius * Math.cos(angle),
                        z + radius * Math.sin(angle)) < center) return false;
            }
        }
        return true;
    }

    private static int traceRises(HeightField field, Trace trace, double spacing) {
        double previous = SharedDescentGraph.sample(field, trace.points().get(0).x(),
                trace.points().get(0).z());
        int rises = 0, vertex = 1;
        double grid = spacing * .37;
        while (grid < trace.length() || vertex < trace.arc().size()) {
            double s = vertex < trace.arc().size() ? trace.arc().get(vertex) : Double.POSITIVE_INFINITY;
            if (grid < trace.length() && grid < s) { s = grid; grid += spacing; }
            else vertex++;
            Point p = NoiseFollowingErosion.pointAt(trace, s);
            double h = SharedDescentGraph.sample(field, p.x(), p.z());
            if (h > previous) rises++;
            previous = h;
        }
        return rises;
    }

    private static SharedDescentGraph tested(HeightField field) {
        SharedDescentGraph graph = new SharedDescentGraph(field, -5, -5, 5, 5);
        Report report = check(graph, () -> field);
        require(report.fineValidationFail() == 0, "simple analytic field fine validation");
        return graph;
    }

    private static void analyticChecks() {
        SharedDescentGraph slope = tested((x, z) -> -x);
        require(slope.node(slope.idAt(0, 0)).nextId() == slope.idAt(1, 0), "steepest slope");
        SharedDescentGraph saddle = tested((x, z) -> z * z - x * x);
        require(saddle.node(saddle.idAt(0, 0)).nextId() >= 0, "saddle is not basin");
        SharedDescentGraph bowl = tested((x, z) -> x * x + z * z);
        require(bowl.node(bowl.idAt(0, 0)).state() == State.BOUND_NO_DESCENT, "bowl bounded terminal");
        SharedDescentGraph flat = tested((x, z) -> 7);
        require(flat.node(flat.idAt(0, 0)).state() == State.FLAT_SAMPLES, "finite equal targets");
        HeightField ridge = (x, z) -> -x + 20 * Math.exp(-square((x - 2) / .3));
        require(ridge.height(4, 0) < ridge.height(0, 0), "lower far endpoint");
        require(!SharedDescentGraph.validate(ridge, 0, 0, 4, 0, .25, 0).accepted(), "inner ridge rejected");
        SharedDescentGraph ridgeGraph = new SharedDescentGraph(ridge, -3, -3, 3, 3);
        require(ridgeGraph.rejectedEdges() > 0 && ridgeGraph.rejectedSamples() > 0, "reject counts");
        HeightField fallback = (x, z) -> ridge.height(x, z) - .1 * z;
        SharedDescentGraph fallbackGraph = new SharedDescentGraph(fallback, -3, -3, 3, 3);
        require(fallbackGraph.node(fallbackGraph.idAt(0, 0)).nextId() == fallbackGraph.idAt(0, 1),
                "failed steeper edges try next lower candidate");
        HeightField microRise = (x, z) -> x <= 2 ? -x : x <= 2.25 ? -2 + (x-2)*1e-10 : -x;
        require(!SharedDescentGraph.validate(microRise, 0, 0, 4, 0, .25, 0).accepted(),
                "even microscopic rise is rejected without tolerance or clamp");
        HeightField narrow = (x, z) -> -x + Math.exp(-square((x - .125) / .001));
        require(SharedDescentGraph.validate(narrow, 0, 0, 4, 0, .25, 0).accepted(), "aliased control");
        require(!SharedDescentGraph.validate(narrow, 0, 0, 4, 0, .001, .37).accepted(),
                "finer sampling can fail: no false universal PASS");
    }

    private static void mergeChecks() {
        HeightField valley = (x, z) -> -x + .1 * z * z;
        SharedDescentGraph graph = tested(valley);
        boolean merge = false;
        for (int id = graph.size() - 1; id >= 0; id--) {
            Node junction = graph.node(id);
            if (junction.indegree() < 2) continue;
            Node reference = null;
            for (int source = 0; source < graph.size(); source++) {
                if (graph.node(source).nextId() != id) continue;
                Node next = graph.next(graph.node(source));
                if (reference != null) require(next == reference, "merged suffix stored once");
                reference = next;
            }
            merge = true;
        }
        require(merge, "synthetic valley contains merge");
        SharedDescentGraph rebuilt = new SharedDescentGraph(valley, -5, -5, 5, 5);
        for (int id = graph.size() - 1; id >= 0; id--) {
            require(graph.node(id).equals(rebuilt.node(id)), "reverse query order identical graph");
        }
        require(auditStop((x, z) -> -x, "s", "NO_DESCENT_AT_RESOLUTION", 0, 0)
                .classification().equals("MISSED_DESCENT_WITNESS"), "missed descent witness");
        StopEvidence truncated = auditStop((x, z) -> -x, "t", "TRUNCATED", 0, 0);
        require(truncated.classification().equals("COMPUTATION_TRUNCATED") && truncated.finerDescent(),
                "truncated classification kept");
        require(auditStop((x, z) -> x*x+z*z, "b", "NO_DESCENT_AT_RESOLUTION", 0, 0)
                .finiteCircleCandidate(), "finite circle only");
        SharedDescentGraph longSlope = new SharedDescentGraph((x, z) -> -x, 0, 0, 100, 2);
        require(longSlope.node(longSlope.idAt(1, 1)).downstreamLengthWu() > 160, "not tracer cutoff");
    }

    private static double square(double x) { return x * x; }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    public static void main(String[] args) {
        try {
            require(Arrays.stream(args).allMatch(String::isEmpty), "selftest takes no arguments");
            analyticChecks(); mergeChecks();
            System.out.println("STAGE A SYNTHETIC CONTRACT PASS; no carve; no flow accumulation; "
                    + "finite samples only, NOT morphology/basin/complete-halo acceptance");
        } catch (RuntimeException | AssertionError failure) {
            failure.printStackTrace(System.err); System.exit(1);
        }
    }
}
