package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion;
import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** L0 only: immutable frozen routes, original full H0, old strong Cut; no tracing or shaping. */
public final class OldRouteL0Probe {
    private static final Path FROZEN = Path.of("build/strong-window-erosion-20261104-final/data");
    private static final String HEADER = "id,group,kind,t,x,z,source,target";
    private static final Settings SETTINGS = new Settings(8, .08, 1, .0625, 160, .25);
    private static final int N = 769;
    private static final double STEP = 609.9999994318932 / 256;
    private static final int OX = (int) Math.round(-3242.5 / STEP) - N / 2;
    private static final int OZ = (int) Math.round(-3365.5 / STEP) - N / 2;
    private static final double CELL = 16;
    private final List<Trace> traces;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<Cell, BitSet> index = new HashMap<>();
    private final HeightField source;
    private final Cut original;
    private int requestChecks;
    private int frozenChecks;

    private record Cell(double x, double z) {}
    private record Csv(String raw, List<String> fields) {}
    private record Request(Csv csv, Point point, int source, int target) {}
    private record Sample(double h0, double ds, double dt, double combined,
                          double strong, String winner, int contributors) {}

    private OldRouteL0Probe() throws Exception {
        traces = loadTraces();
        require(traces.size() == 8100, "expected all 8100 frozen routes");
        source = bindHeight();
        for (int i = 0; i < traces.size(); i++) {
            require(ids.put(traces.get(i).id(), i) == null, "duplicate route id");
            addRoute(i);
        }
        original = new Cut(source, traces, SETTINGS);
    }

    private static HeightField bindHeight() throws ReflectiveOperationException {
        Constructor<NoiseFollowingProbe> ctor = NoiseFollowingProbe.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object probe = ctor.newInstance();
        Method height = NoiseFollowingProbe.class.getDeclaredMethod("height", double.class, double.class);
        height.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(height).bindTo(probe);
        return (x, z) -> invokeHeight(handle, x, z);
    }

    private static double invokeHeight(MethodHandle handle, double x, double z) {
        try {
            double h = (double) handle.invokeExact(x, z);
            require(Double.isFinite(h), "nonfinite original H0");
            return h;
        } catch (Throwable failure) {
            throw new IllegalStateException("original NoiseFollowingProbe.height failed", failure);
        }
    }

    private static Map<String, String> loadStatuses() throws IOException {
        Map<String, String> statuses = new LinkedHashMap<>();
        try (PushbackReader reader = open(FROZEN.resolve("traces.csv"))) {
            Csv header = readCsv(reader);
            require(header != null && header.fields().size() == 7
                    && header.fields().get(0).equals("id")
                    && header.fields().get(3).equals("status"), "traces.csv header");
            for (Csv row; (row = readCsv(reader)) != null;) {
                require(row.fields().size() == 7, "traces.csv width");
                require(statuses.put(row.fields().get(0), row.fields().get(3)) == null,
                        "duplicate traces.csv id");
            }
        }
        return statuses;
    }

    private static List<Trace> loadTraces() throws IOException {
        Map<String, String> statuses = loadStatuses();
        List<Trace> result = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        String current = null;
        try (PushbackReader reader = open(FROZEN.resolve("routes.csv"))) {
            Csv header = readCsv(reader);
            require(header != null && header.raw().equals("trace,vertex,x,z"), "routes.csv header");
            for (Csv row; (row = readCsv(reader)) != null;) {
                List<String> f = row.fields();
                require(f.size() == 4, "routes.csv width");
                String id = f.get(0);
                if (!id.equals(current)) {
                    if (current != null) result.add(makeTrace(current, points, statuses));
                    points = new ArrayList<>();
                    current = id;
                }
                require(Integer.parseInt(f.get(1)) == points.size(), "original vertex order: " + id);
                points.add(new Point(Double.parseDouble(f.get(2)), Double.parseDouble(f.get(3))));
            }
        }
        if (current != null) result.add(makeTrace(current, points, statuses));
        require(result.size() == statuses.size(), "routes/status count mismatch");
        List<String> statusOrder = new ArrayList<>(statuses.keySet());
        for (int i = 0; i < result.size(); i++) {
            require(result.get(i).id().equals(statusOrder.get(i)),
                    "routes.csv and traces.csv order mismatch");
        }
        return List.copyOf(result);
    }

    private static Trace makeTrace(String id, List<Point> points, Map<String, String> statuses) {
        require(statuses.containsKey(id), "missing frozen status: " + id);
        List<Double> arc = new ArrayList<>();
        double length = 0;
        arc.add(length);
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            length += Math.hypot(b.x() - a.x(), b.z() - a.z());
            arc.add(length);
        }
        return new Trace(id, points, arc, statuses.get(id), length);
    }

    /** Segment expanded AABBs plus a whole hash-cell guard: no distance/fade-based exclusion. */
    private void addRoute(int route) {
        Trace trace = traces.get(route);
        if (trace.length() == 0) return;
        for (int i = 1; i < trace.points().size(); i++) {
            Point a = trace.points().get(i - 1), b = trace.points().get(i);
            long x0 = (long) Math.floor((Math.min(a.x(), b.x()) - SETTINGS.radius()) / CELL) - 1;
            long x1 = (long) Math.floor((Math.max(a.x(), b.x()) + SETTINGS.radius()) / CELL) + 1;
            long z0 = (long) Math.floor((Math.min(a.z(), b.z()) - SETTINGS.radius()) / CELL) - 1;
            long z1 = (long) Math.floor((Math.max(a.z(), b.z()) + SETTINGS.radius()) / CELL) + 1;
            for (long z = z0; z <= z1; z++) for (long x = x0; x <= x1; x++) {
                index.computeIfAbsent(new Cell(x, z), unused -> new BitSet()).set(route);
            }
        }
    }

    /** Exact original operation order: entire polyline minimum, Euclidean ends, then kernel. */
    private static double weight(Trace trace, Point p) {
        if (trace.length() == 0) return 0;
        double nearest2 = Double.POSITIVE_INFINITY;
        for (int i = 1; i < trace.points().size(); i++) {
            nearest2 = Math.min(nearest2, NoiseFollowingErosion.distance2(p,
                    trace.points().get(i - 1), trace.points().get(i)));
        }
        double radius = SETTINGS.radius(), radius2 = radius * radius;
        if (nearest2 >= radius2) return 0;
        double u = Math.max(0, 1 - nearest2 / radius2);
        Point start = trace.points().get(0), end = trace.points().get(trace.points().size() - 1);
        double ends = smooth5(Math.hypot(start.x() - p.x(), start.z() - p.z()) / radius)
                * smooth5(Math.hypot(end.x() - p.x(), end.z() - p.z()) / radius);
        return u * u * u * ends;
    }

    private Sample sample(Point p, int sourceId, int targetId) {
        double best = 0;
        String winner = "";
        int contributors = 0;
        BitSet candidates = index.get(new Cell(Math.floor(p.x() / CELL), Math.floor(p.z() / CELL)));
        if (candidates != null) {
            // BitSet iteration is original routes.csv order, including ties; never mutate buckets.
            for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
                double w = weight(traces.get(i), p);
                if (depth(w) > 0) contributors++;
                if (w > best) {
                    best = w;
                    winner = traces.get(i).id();
                }
            }
        }
        double h0 = source.height(p.x(), p.z()), d = depth(best);
        double ds = sourceId < 0 ? 0 : depth(weight(traces.get(sourceId), p));
        double dt = targetId < 0 ? 0 : depth(weight(traces.get(targetId), p));
        return new Sample(h0, ds, dt, d, d == 0 ? h0 : h0 - d, winner, contributors);
    }

    private List<Request> readRequests(Path file) throws IOException {
        List<Request> result = new ArrayList<>();
        try (PushbackReader reader = open(file)) {
            Csv header = readCsv(reader);
            require(header != null && header.raw().equals(HEADER), "expected request header " + HEADER);
            for (Csv csv; (csv = readCsv(reader)) != null;) {
                List<String> f = csv.fields();
                require(f.size() == 8, "request width at row " + (result.size() + 2));
                Point p = new Point(Double.parseDouble(f.get(4)), Double.parseDouble(f.get(5)));
                int target = f.get(7).isEmpty() ? -1 : routeId(f.get(7));
                result.add(new Request(csv, p, routeId(f.get(6)), target));
            }
        }
        require(result.size() >= 64, "at least 64 request rows required for fixed original Cut checks");
        return result;
    }

    private int routeId(String id) {
        Integer at = ids.get(id);
        require(at != null, "unknown frozen source/target route id: " + id);
        return at;
    }

    private void checkRequests(List<Request> requests) {
        for (int i = 0; i < 64; i++) {
            int at = (int) ((long) i * (requests.size() - 1) / 63);
            Request r = requests.get(at);
            Sample actual = sample(r.point(), r.source(), r.target());
            double x = r.point().x(), z = r.point().z();
            exact(actual.combined(), original.displacement(x, z), "all-trace Cut D request " + at);
            exact(actual.strong(), original.height(x, z), "all-trace Cut H request " + at);
            exact(actual.ds(), single(r.source()).displacement(x, z), "source Cut request " + at);
            exact(actual.dt(), single(r.target()).displacement(x, z), "target Cut request " + at);
            checkAttribution(r.point(), actual);
            requestChecks++;
        }
    }

    private Cut single(int id) {
        return new Cut(source, id < 0 ? List.of() : List.of(traces.get(id)), SETTINGS);
    }

    /** Independent full enumeration also detects dropped non-winning positive contributors. */
    private void checkAttribution(Point p, Sample actual) {
        int count = 0;
        double best = 0;
        String winner = "";
        for (Trace trace : traces) {
            double w = weight(trace, p);
            if (depth(w) > 0) count++;
            if (w > best) {
                best = w;
                winner = trace.id();
            }
        }
        require(count == actual.contributors(), "all-positive contributor completeness");
        require(winner.equals(actual.winner()), "stable original-order winner");
        exact(depth(best), actual.combined(), "unindexed full enumeration D");
    }

    private void checkFrozen() throws IOException {
        try (RandomAccessFile h0 = frozenFile("carrier-height.f64be");
             RandomAccessFile strong = frozenFile("strong-height.f64be");
             RandomAccessFile d = frozenFile("strong-depth.f64be")) {
            for (int i = 0; i < 128; i++) {
                int at = Math.floorMod(i * 7919 + 31, N * N);
                Point p = new Point((OX + at % N) * STEP, (OZ + at / N) * STEP);
                Sample actual = sample(p, -1, -1);
                exact(actual.h0(), readAt(h0, at), "frozen H0 bits " + at);
                exact(actual.strong(), readAt(strong, at), "frozen strong bits " + at);
                exact(actual.combined(), readAt(d, at), "frozen D bits " + at);
                frozenChecks++;
            }
        }
    }

    private static RandomAccessFile frozenFile(String name) throws IOException {
        Path file = FROZEN.resolve(name);
        require(Files.size(file) == N * N * 8L, "frozen 769-square file size: " + name);
        return new RandomAccessFile(file.toFile(), "r");
    }

    private static double readAt(RandomAccessFile file, int at) throws IOException {
        file.seek(at * 8L);
        return file.readDouble();
    }

    private void writeSamples(List<Request> requests, Path out) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(out.resolve("samples.csv"))) {
            writer.write(HEADER + ",H0,Dsource,Dtarget,Dcombined,Hstrong,winner,contributors\n");
            for (Request r : requests) {
                Sample s = sample(r.point(), r.source(), r.target());
                writer.write(r.csv().raw() + "," + s.h0() + "," + s.ds() + "," + s.dt()
                        + "," + s.combined() + "," + s.strong() + "," + csvField(s.winner())
                        + "," + s.contributors() + "\n");
            }
        }
    }

    private void run(Path requestsFile, Path out) throws IOException {
        List<Request> requests = readRequests(requestsFile);
        checkFrozen();
        checkRequests(requests);
        writeSamples(requests, out);
        Files.writeString(out.resolve("checks.json"), "{\"passed\":true,\"stage\":\"L0\","
                + "\"routeCount\":" + traces.size() + ",\"requestCount\":" + requests.size()
                + ",\"originalCutRequestPoints\":" + requestChecks
                + ",\"sourceTargetCutChecks\":" + (2 * requestChecks)
                + ",\"fullEnumerationAttributionChecks\":" + requestChecks
                + ",\"frozenStrongBitChecks\":" + frozenChecks
                + ",\"frozenH0BitChecks\":" + frozenChecks + ",\"frozenDepthBitChecks\":" + frozenChecks
                + ",\"requestSelection\":\"floor(i*(count-1)/63), i=0..63\","
                + "\"frozenSelection\":\"(i*7919+31) mod (769*769), i=0..127\","
                + "\"radiusWu\":8,\"depthCapH\":0.08,\"routesRebuilt\":false,"
                + "\"pointsMoved\":false,\"productionWiring\":false,\"morphologyAcceptanceClaimed\":false,"
                + "\"winnerTieRule\":\"first route in routes.csv at equal maximum weight; empty if D=0\"}\n");
        System.out.println("L0 PASS: " + requests.size() + " requests, 8100 frozen routes, "
                + requestChecks + " original Cut points, " + frozenChecks + " frozen grid points");
    }

    public static void main(String[] args) {
        Path out = null;
        boolean created = false;
        try {
            require(args.length == 2, "usage: OldRouteL0Probe requests.csv NEWout (fresh; run from mdk)");
            out = Path.of(args[1]).toAbsolutePath();
            require(!Files.exists(out), "refuse existing output: " + out);
            require(Files.isRegularFile(Path.of(args[0])), "missing requests.csv");
            Files.createDirectories(out.getParent());
            Files.createDirectory(out);
            created = true;
            new OldRouteL0Probe().run(Path.of(args[0]), out);
        } catch (Throwable failure) {
            if (created) writeFailure(out, failure);
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void writeFailure(Path out, Throwable failure) {
        try {
            Files.writeString(out.resolve("checks.json"), "{\"passed\":false,\"stage\":\"L0\"}\n");
        } catch (IOException secondary) {
            failure.addSuppressed(secondary);
        }
    }

    private static PushbackReader open(Path path) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path);
        return new PushbackReader(reader, 1);
    }

    /** RFC4180 records, retaining the exact original request text (including quoted metadata). */
    private static Csv readCsv(PushbackReader reader) throws IOException {
        StringBuilder raw = new StringBuilder();
        boolean quoted = false;
        for (int c; (c = reader.read()) != -1;) {
            if (c == '"') {
                raw.append((char) c);
                if (quoted) {
                    int next = reader.read();
                    if (next == '"') raw.append('"');
                    else {
                        quoted = false;
                        if (next != -1) reader.unread(next);
                    }
                } else quoted = true;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                if (c == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) reader.unread(next);
                }
                return parseCsv(raw.toString());
            } else raw.append((char) c);
        }
        require(!quoted, "unterminated quoted CSV field");
        return raw.length() == 0 ? null : parseCsv(raw.toString());
    }

    private static Csv parseCsv(String raw) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else field.append(c);
        }
        require(!quoted, "unclosed CSV quote");
        fields.add(field.toString());
        return new Csv(raw, List.copyOf(fields));
    }

    private static String csvField(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static double smooth5(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return Math.max(0, Math.min(1, t * t * t * (10 + t * (-15 + 6 * t))));
    }

    private static double depth(double weight) {
        return SETTINGS.depth() * Math.min(1, weight);
    }

    private static void exact(double actual, double expected, String message) {
        require(Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(expected), message);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
