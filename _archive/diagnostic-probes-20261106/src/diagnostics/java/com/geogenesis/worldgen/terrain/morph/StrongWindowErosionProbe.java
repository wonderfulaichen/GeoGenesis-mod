package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion;
import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/** Approved offline strong visual experiment: bounded window, NOT S2 or production wiring. */
public final class StrongWindowErosionProbe {
    private static final int N = 769;
    private static final long SEED = 9139912035078620160L;
    private static final double LENGTH = 609.9999994318932;
    private static final double STEP = LENGTH / 256;
    private static final int OX = (int) Math.round(-3242.5 / STEP) - N / 2;
    private static final int OZ = (int) Math.round(-3365.5 / STEP) - N / 2;
    private static final double PITCH = 24;
    private static final Settings SETTINGS = new Settings(8, .08, 1, .0625, 160, .25);
    private static final Path FROZEN = Path.of("build/terminal-depth-repair-20261104/evaluated");
    private static final String[] FROZEN_NAMES = {
        "base-raw.f64be", "carrier-height.f64be", "new-height.f64be"
    };
    private final ThreadLocal<Source> sources = ThreadLocal.withInitial(Source::create);
    private final double[] raw, carrier, weak;
    private final String[] frozenHashes = new String[FROZEN_NAMES.length];
    private final int minCX, maxCX, minCZ, maxCZ, seedColumns, seedRows;
    private final Trace[] traces;
    private final int[] influence;
    private final Map<String, Integer> statuses = new TreeMap<>();
    private double traceMs, rasterMs, sourceMs;
    private int rasterComparisons, zeroChecks, replayChecks, influencedPixels;

    private StrongWindowErosionProbe() throws Exception {
        require(MorphCatalog.CANONICAL_SEED == SEED, "canonical seed drift");
        for (int i = 0; i < FROZEN_NAMES.length; i++) {
            frozenHashes[i] = sha256(FROZEN.resolve(FROZEN_NAMES[i]));
        }
        raw = read(FROZEN.resolve(FROZEN_NAMES[0]));
        carrier = read(FROZEN.resolve(FROZEN_NAMES[1]));
        weak = read(FROZEN.resolve(FROZEN_NAMES[2]));
        double halo = SETTINGS.maxLength() + SETTINGS.radius();
        minCX = (int) Math.ceil((x(0) - halo) / PITCH - .5);
        maxCX = (int) Math.floor((x(N - 1) + halo) / PITCH - .5);
        minCZ = (int) Math.ceil((z(0) - halo) / PITCH - .5);
        maxCZ = (int) Math.floor((z(N - 1) + halo) / PITCH - .5);
        seedColumns = maxCX - minCX + 1;
        seedRows = maxCZ - minCZ + 1;
        traces = new Trace[seedColumns * seedRows];
        influence = new int[traces.length];
    }

    /** Each worker owns an original probe; no rewritten H0, shared mutable callback or cut field. */
    private record Source(MethodHandle heightMethod, MethodHandle rawMethod) implements HeightField {
        private static Source create() {
            try {
                Constructor<NoiseFollowingProbe> ctor = NoiseFollowingProbe.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object probe = ctor.newInstance();
                return new Source(bind(probe, "height"), bind(probe, "raw"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot bind original private callbacks", e);
            }
        }

        private static MethodHandle bind(Object probe, String name) throws ReflectiveOperationException {
            Method method = NoiseFollowingProbe.class.getDeclaredMethod(name, double.class, double.class);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).bindTo(probe);
        }

        @Override
        public double height(double x, double z) {
            return invoke(heightMethod, x, z);
        }

        private double raw(double x, double z) {
            return invoke(rawMethod, x, z);
        }

        private static double invoke(MethodHandle method, double x, double z) {
            try {
                return (double) method.invokeExact(x, z);
            } catch (Throwable e) {
                throw new IllegalStateException("Original height/raw callback failed", e);
            }
        }
    }

    private void verifySources() {
        long started = System.nanoTime();
        IntStream.range(0, N).parallel().forEach(row -> {
            Source source = sources.get();
            for (int col = 0; col < N; col++) {
                int at = row * N + col;
                require(bits(source.height(x(col), z(row)), carrier[at]), "frozen H0 bits at " + at);
                require(bits(source.raw(x(col), z(row)), raw[at]), "frozen complete raw bits at " + at);
                require(weak[at] <= carrier[at], "frozen weak must be subtractive at " + at);
            }
        });
        sourceMs = ms(started);
        System.out.printf("SOURCE all %d raw/H0 samples bit-exact, %.3f ms%n", N * N, sourceMs);
    }

    private void buildTraces() {
        long started = System.nanoTime();
        IntStream.range(0, seedRows).parallel().forEach(row -> {
            Source source = sources.get();
            int cz = minCZ + row;
            for (int col = 0; col < seedColumns; col++) {
                int cx = minCX + col, at = row * seedColumns + col;
                String id = SEED + ":strong-window:" + cx + ":" + cz;
                traces[at] = NoiseFollowingErosion.trace(source, id,
                        (cx + .5) * PITCH, (cz + .5) * PITCH, SETTINGS);
            }
            System.out.printf("TRACE ROW cz=%d complete (%d seeds)%n", cz, seedColumns);
        });
        traceMs = ms(started);
        for (Trace trace : traces) statuses.merge(trace.status(), 1, Integer::sum);
        System.out.printf("TRACES %d (all statuses/lengths retained), %.3f ms%n", traces.length, traceMs);
    }

    /** Per-trace minimum distance first, then the original whole-trace kernel and spatial fades. */
    private static Map<Integer, Double> nearestGrid(Trace trace) {
        Map<Integer, Double> nearest = new HashMap<>();
        if (trace.length() == 0) return nearest;
        double radius = SETTINGS.radius(), radius2 = radius * radius;
        for (int i = 1; i < trace.points().size(); i++) {
            Point a = trace.points().get(i - 1), b = trace.points().get(i);
            // One conservative cell guards quotient rounding; coordinates themselves use integer * STEP.
            int c0 = lower(Math.min(a.x(), b.x()) - radius, OX);
            int c1 = upper(Math.max(a.x(), b.x()) + radius, OX);
            int r0 = lower(Math.min(a.z(), b.z()) - radius, OZ);
            int r1 = upper(Math.max(a.z(), b.z()) + radius, OZ);
            for (int row = r0; row <= r1; row++) for (int col = c0; col <= c1; col++) {
                double d2 = NoiseFollowingErosion.distance2(new Point(x(col), z(row)), a, b);
                if (d2 < radius2) nearest.merge(row * N + col, d2, Math::min);
            }
        }
        return nearest;
    }

    private static int lower(double coordinate, int origin) {
        return Math.max(0, (int) Math.ceil(coordinate / STEP - origin) - 1);
    }

    private static int upper(double coordinate, int origin) {
        return Math.min(N - 1, (int) Math.floor(coordinate / STEP - origin) + 1);
    }

    private static double traceWeight(Trace trace, int at, double nearest2) {
        Point start = trace.points().get(0), end = trace.points().get(trace.points().size() - 1);
        double px = x(at % N), pz = z(at / N), radius = SETTINGS.radius();
        double ends = smooth5(Math.hypot(px - start.x(), pz - start.z()) / radius)
                * smooth5(Math.hypot(px - end.x(), pz - end.z()) / radius);
        double u = Math.max(0, 1 - nearest2 / (radius * radius));
        return u * u * u * ends;
    }

    private static int addTrace(Trace trace, double[] weights) {
        int pixels = 0;
        for (Map.Entry<Integer, Double> entry : nearestGrid(trace).entrySet()) {
            int at = entry.getKey();
            double w = traceWeight(trace, at, entry.getValue());
            if (w > 0) pixels++;
            weights[at] = Math.max(weights[at], w);
        }
        return pixels;
    }

    private double[] raster() {
        long started = System.nanoTime();
        double[] weights = new double[N * N];
        // Trace array order is world-grid row-major, regardless of parallel completion order.
        for (int i = 0; i < traces.length; i++) influence[i] = addTrace(traces[i], weights);
        for (int i = 0; i < weights.length; i++) {
            // Same operation order as Cut.displacement, including the final depth multiplication.
            weights[i] = SETTINGS.depth() * Math.min(1, weights[i]);
            if (weights[i] > 0) influencedPixels++;
        }
        rasterMs = ms(started);
        System.out.printf("RASTER %d influenced pixels, %.3f ms%n", influencedPixels, rasterMs);
        return weights;
    }

    private double[] compose(double[] depth) {
        double[] strong = new double[N * N];
        for (int at = 0; at < strong.length; at++) {
            double d = depth[at];
            require(Double.isFinite(d) && d >= 0 && d <= SETTINGS.depth(), "finite depth budget");
            strong[at] = d == 0 ? carrier[at] : carrier[at] - d;
            require(Double.isFinite(strong[at]) && strong[at] <= carrier[at], "subtractive composition");
            require(bits(strong[at], d == 0 ? carrier[at] : carrier[at] - d), "H=H0-D bits");
        }
        return strong;
    }

    private void checkRaster(double[] depth) {
        int middle = (seedRows / 2) * seedColumns + seedColumns / 2;
        List<Trace> subset = List.of(traces[0], traces[traces.length - 1], traces[middle],
                traces[middle + seedColumns / 3], traces[middle - seedColumns / 3]);
        double[] subsetWeights = new double[N * N];
        for (Trace trace : subset) addTrace(trace, subsetWeights);
        HeightField source = sources.get();
        Cut original = new Cut(source, subset, SETTINGS);
        Cut all = new Cut(source, Arrays.asList(traces), SETTINGS);
        Settings zero = new Settings(8, 0, 1, .0625, 160, .25);
        Cut zeroCut = new Cut(source, subset, zero);
        for (int at = 0; at < subsetWeights.length; at++) {
            if (subsetWeights[at] > 0 || at % 149 == 31) {
                double px = x(at % N), pz = z(at / N);
                double expected = SETTINGS.depth() * Math.min(1, subsetWeights[at]);
                require(bits(expected, original.displacement(px, pz)), "subset original Cut raster bits");
                require(bits(zeroCut.height(px, pz), carrier[at]), "zero-depth source identity");
                require(bits(original.height(px, pz, 0), carrier[at]), "zero multiplier source identity");
                rasterComparisons++;
                zeroChecks++;
            }
        }
        // Full-trace point queries are validation only, never the raster implementation.
        for (int i = 0; i < 128; i++) {
            int at = Math.floorMod(i * 7919 + 31, N * N);
            require(bits(depth[at], all.displacement(x(at % N), z(at / N))), "full Cut grid bits");
            rasterComparisons++;
        }
        double farX = x(0) - 2 * (SETTINGS.maxLength() + SETTINGS.radius() + PITCH);
        require(bits(all.displacement(farX, z(0)), 0), "disjoint support zero depth");
        require(bits(all.height(farX, z(0)), source.height(farX, z(0))), "disjoint source identity");
        for (int at : new int[]{0, middle, traces.length - 1}) {
            Trace trace = traces[at];
            Point seed = trace.points().get(0);
            Trace replay = NoiseFollowingErosion.trace(Source.create(), trace.id(), seed.x(), seed.z(), SETTINGS);
            require(trace.equals(replay), "independent source cold replay");
            replayChecks++;
        }
    }

    private static void checkSyntheticRaster() {
        Point a = new Point(x(N / 2), z(N / 2));
        Point b = new Point(a.x() + 10, a.z());
        Point c = new Point(a.x() + 10, a.z() + 10);
        Point d = new Point(a.x(), a.z() + 10);
        Trace bend = new Trace("synthetic-bend", List.of(a, b, c, d),
                List.of(0.0, 10.0, 20.0, 30.0), "TEST", 30);
        Trace point = new Trace("synthetic-zero-length", List.of(a), List.of(0.0), "TEST", 0);
        double[] weights = new double[N * N];
        require(addTrace(point, weights) == 0, "zero-length trace has no cut");
        addTrace(bend, weights);
        Cut original = new Cut((px, pz) -> .5, List.of(bend, point), SETTINGS);
        for (int row = N / 2 - 8; row <= N / 2 + 13; row++) {
            for (int col = N / 2 - 8; col <= N / 2 + 13; col++) {
                double actual = SETTINGS.depth() * Math.min(1, weights[row * N + col]);
                require(bits(actual, original.displacement(x(col), z(row))), "bent Cut exact raster");
            }
        }
    }

    private void writeTraces(Path out) throws IOException {
        try (BufferedWriter csv = Files.newBufferedWriter(out.resolve("traces.csv"));
             BufferedWriter routes = Files.newBufferedWriter(out.resolve("routes.csv"))) {
            csv.write("id,start_x,start_z,status,length_wu,vertices,inWindowInfluencePixels\n");
            routes.write("trace,vertex,x,z\n");
            for (int i = 0; i < traces.length; i++) {
                Trace trace = traces[i];
                Point seed = trace.points().get(0);
                csv.write(trace.id() + "," + seed.x() + "," + seed.z() + "," + trace.status()
                        + "," + trace.length() + "," + trace.points().size() + "," + influence[i] + "\n");
                for (int v = 0; v < trace.points().size(); v++) {
                    Point p = trace.points().get(v);
                    routes.write(trace.id() + "," + v + "," + p.x() + "," + p.z() + "\n");
                }
            }
        }
    }

    private void profiles(Path out, double[] strong) throws IOException {
        // Original two long effective seeds; all four lines snap to the nearest frozen raster row/col.
        int[][] seeds = {{-3648, -3648}, {-3648, -3008}};
        try (BufferedWriter csv = Files.newBufferedWriter(out.resolve("profiles.csv"))) {
            csv.write("profile,offset_wu,world_x,world_z,H0,weak,strong\n");
            profile(csv, "fixed-center-x", true, N / 2, strong);
            profile(csv, "fixed-center-z", false, N / 2, strong);
            for (int i = 0; i < seeds.length; i++) {
                int col = (int) Math.round(seeds[i][0] / STEP) - OX;
                int row = (int) Math.round(seeds[i][1] / STEP) - OZ;
                profile(csv, "original-effective-" + (i + 1) + "-x", true, row, strong);
                profile(csv, "original-effective-" + (i + 1) + "-z", false, col, strong);
            }
        }
    }

    private void profile(BufferedWriter csv, String name, boolean alongX, int fixed,
                         double[] strong) throws IOException {
        require(fixed >= 0 && fixed < N, "fixed profile inside frozen grid");
        for (int i = 0; i < N; i++) {
            int col = alongX ? i : fixed, row = alongX ? fixed : i, at = row * N + col;
            csv.write(name + "," + ((i - N / 2) * STEP) + "," + x(col) + "," + z(row)
                    + "," + carrier[at] + "," + weak[at] + "," + strong[at] + "\n");
        }
    }

    private void writeMetadata(Path out) throws IOException {
        String parameters = "\"radiusWu\":8,\"depthCapH\":0.08,\"pitchWu\":24,\"maxLengthWu\":160,"
                + "\"maxStepWu\":1,\"minStepWu\":0.0625,\"gradientStepWu\":0.25,\"noQP\":true";
        json(out, "sampling.json", "\"size\":" + N + ",\"seed\":" + SEED + ",\"HS\":2,"
                + "\"originWu\":[" + x(0) + "," + z(0) + "],\"gridIntegerOrigin\":[" + OX + "," + OZ + "],"
                + "\"stepWu\":" + STEP + ",\"spanWu\":" + ((N - 1) * STEP) + ",\"typeLengthWu\":" + LENGTH + ","
                + "\"gridArithmetic\":\"(integerOrigin+index)*step\",\"dtype\":\">f8\",\"arrayOrder\":\"row=z,col=x\","
                + "\"actualMinecraftWorld\":false,\"stage\":\"offline bounded experiment NOT S2\","
                + "\"S2Executed\":false,\"productionWiring\":false,\"baselineFrozen\":true,"
                + "\"profileSampling\":\"raster samples NOT continuous; nearest grid row/column to fixed seed; offset from window midpoint\","
                + "\"originalEffectiveSeedsWu\":[[-3648,-3648],[-3648,-3008]]," + parameters);
        json(out, "parameters.json", parameters + ",\"haloWu\":168,\"seedCount\":" + traces.length
                + ",\"seedCellBoundsInclusive\":[" + minCX + "," + minCZ + "," + maxCX + "," + maxCZ + "],"
                + "\"selection\":\"all pitch24 cell centers within closed window+168 AABB; row-major z then x; no result filtering\","
                + "\"composition\":\"Hstrong=H0-D; D=depth*max(whole-trace kernel*spatial endpoint fades), never sum\","
                + "\"baselineFrozen\":true,\"preserveAllStatusesAndLengths\":true,\"trimNaturalEnds\":false,"
                + "\"finalBedSlopeOrPitConstraint\":false,\"old4PathRestriction\":false,\"worldCoverageProven\":false");
        json(out, "checks.json", "\"passed\":true,\"rawBitChecks\":" + (N * N)
                + ",\"actualH0BitChecks\":" + (N * N) + ",\"compositionChecks\":" + (N * N)
                + ",\"originalCutBitComparisons\":" + rasterComparisons + ",\"zeroDepthChecks\":" + zeroChecks
                + ",\"coldTraceReplays\":" + replayChecks + ",\"syntheticBentAndZeroLength\":true,"
                + "\"disjointSourceIdentity\":true,\"baselineFrozen\":true,\"morphologyAcceptanceClaimed\":false");
        json(out, "performance.json", "\"traceTimeMs\":" + traceMs + ",\"rasterTimeMs\":" + rasterMs
                + ",\"sourceBitcheckTimeMs\":" + sourceMs + ",\"traceCount\":" + traces.length
                + ",\"influencedPixels\":" + influencedPixels + ",\"totalPixels\":" + (N * N)
                + ",\"traceParallelism\":\"independent per-worker original callbacks, parallel seed rows, ordered array\","
                + "\"raster\":\"exact segment distance; per-trace sparse nearest map; ordered max weights\","
                + "\"statusCounts\":" + statusJson());
        writeFrozenMetadata(out);
    }

    private String statusJson() {
        List<String> entries = new ArrayList<>();
        statuses.forEach((status, count) -> entries.add("\"" + status + "\":" + count));
        return "{" + String.join(",", entries) + "}";
    }

    private void writeFrozenMetadata(Path out) throws IOException {
        String[] outputs = {"base-raw.f64be", "carrier-height.f64be", "weak-height.f64be"};
        List<String> entries = new ArrayList<>();
        String frozenDirectory = FROZEN.toString().replace('\\', '/');
        for (int i = 0; i < FROZEN_NAMES.length; i++) {
            entries.add("{\"source\":\"" + frozenDirectory + "/" + FROZEN_NAMES[i] + "\",\"output\":\""
                    + outputs[i] + "\",\"sha256\":\"" + frozenHashes[i] + "\"}");
        }
        json(out, "baseline.json", "\"baselineFrozen\":true,\"files\":[" + String.join(",", entries) + "]");
    }

    private void copyFrozen(Path out) throws Exception {
        String[] outputs = {"base-raw.f64be", "carrier-height.f64be", "weak-height.f64be"};
        for (int i = 0; i < FROZEN_NAMES.length; i++) {
            Path source = FROZEN.resolve(FROZEN_NAMES[i]);
            require(frozenHashes[i].equals(sha256(source)), "frozen input changed during experiment");
            Path target = out.resolve(outputs[i]);
            Files.copy(source, target);
            require(frozenHashes[i].equals(sha256(target)), "baseline copy bit identity");
        }
    }

    private void run(Path out) throws Exception {
        verifySources();
        checkSyntheticRaster();
        buildTraces();
        double[] depth = raster(), strong = compose(depth);
        checkRaster(depth);
        copyFrozen(out);
        write(out.resolve("strong-depth.f64be"), depth);
        write(out.resolve("strong-height.f64be"), strong);
        writeTraces(out);
        profiles(out, strong);
        writeMetadata(out);
        System.out.println("STRONG WINDOW CONTRACT PASS; offline visual data only; NOT S2; no morphology gate");
    }

    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            require(args.length == 1, "usage: StrongWindowErosionProbe NEWout/data (fresh; run from mdk)");
            Path out = Path.of(args[0]).toAbsolutePath();
            require(!Files.exists(out), "refuse existing output: " + out);
            Files.createDirectories(out.getParent());
            Files.createDirectory(out);
            new StrongWindowErosionProbe().run(out);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static double[] read(Path file) throws IOException {
        require(Files.size(file) == N * N * 8L, "frozen file size: " + file);
        double[] values = new double[N * N];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            for (int i = 0; i < values.length; i++) {
                values[i] = input.readDouble();
                require(Double.isFinite(values[i]), "finite frozen sample");
            }
        }
        return values;
    }

    private static void write(Path file, double[] values) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (double value : values) {
                require(Double.isFinite(value), "finite output");
                output.writeDouble(value);
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static double smooth5(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return Math.max(0, Math.min(1, t * t * t * (10 + t * (-15 + 6 * t))));
    }

    private static double x(int col) { return (OX + col) * STEP; }
    private static double z(int row) { return (OZ + row) * STEP; }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private static double ms(long started) { return (System.nanoTime() - started) / 1e6; }
    private static void json(Path out, String name, String body) throws IOException {
        Files.writeString(out.resolve(name), "{" + body + "}\n");
    }
    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
