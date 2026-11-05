package com.geogenesis.worldgen.terrain.morph;

import java.io.BufferedWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** T0 attribution only: frozen L0 routes/H0/Cut; no tracing or production changes.
 * All non-grid requests are retained (including every supplied tail/vertex/endpoint).
 * Grid means kind equals "grid", case-insensitively; retain ordinal 0,256,512,...
 * in input order across grid rows. No selection depends on depth or rendered effects.
 * The caller supplies tail points at least every .25wu plus vertices and endpoints;
 * this helper never creates, moves, interpolates, or removes non-grid request points.
 */
public final class OldRouteT0Contributors {
    private static final Class<?> L0 = OldRouteL0Probe.class;
    private final Object probe;
    private final List<?> traces;
    private final Map<?, ?> index;
    private final double cellSize;
    private final Constructor<?> cell;
    private final Method sample;
    private final Method weight;
    private final Method depth;
    private final Method traceId;
    private final Method pointX;
    private final Method pointZ;
    private final Method requestCsv;
    private final Method requestPoint;
    private final Method requestSource;
    private final Method requestTarget;
    private final Method csvFields;
    private final Method combined;
    private final Method contributors;

    private OldRouteT0Contributors() throws ReflectiveOperationException {
        Constructor<?> constructor = L0.getDeclaredConstructor();
        constructor.setAccessible(true);
        probe = constructor.newInstance();
        traces = (List<?>) field("traces").get(probe);
        index = (Map<?, ?>) field("index").get(probe);
        cellSize = field("CELL").getDouble(null);
        require(traces.size() == 8100, "expected all 8100 frozen traces");
        require(field("source").get(probe) != null && field("original").get(probe) != null,
                "original L0 H0/Cut not bound");
        Class<?> pointType = Class.forName("com.geogenesis.standalone.NoiseFollowingErosion$Point");
        Class<?> traceType = Class.forName("com.geogenesis.standalone.NoiseFollowingErosion$Trace");
        Class<?> requestType = Class.forName(L0.getName() + "$Request");
        Class<?> csvType = Class.forName(L0.getName() + "$Csv");
        Class<?> sampleType = Class.forName(L0.getName() + "$Sample");
        Class<?> cellType = Class.forName(L0.getName() + "$Cell");
        cell = cellType.getDeclaredConstructor(double.class, double.class);
        cell.setAccessible(true);
        sample = method(L0, "sample", pointType, int.class, int.class);
        weight = method(L0, "weight", traceType, pointType);
        depth = method(L0, "depth", double.class);
        traceId = method(traceType, "id");
        pointX = method(pointType, "x");
        pointZ = method(pointType, "z");
        requestCsv = method(requestType, "csv");
        requestPoint = method(requestType, "point");
        requestSource = method(requestType, "source");
        requestTarget = method(requestType, "target");
        csvFields = method(csvType, "fields");
        combined = method(sampleType, "combined");
        contributors = method(sampleType, "contributors");
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field result = L0.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static String quote(Object value) {
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }

    private long writePoint(Object request, List<?> fields, BufferedWriter rows,
                            BufferedWriter checks) throws Exception {
        Object point = requestPoint.invoke(request);
        Object actual = sample.invoke(probe, point, requestSource.invoke(request),
                requestTarget.invoke(request));
        double x = ((Number) pointX.invoke(point)).doubleValue();
        double z = ((Number) pointZ.invoke(point)).doubleValue();
        Object key = cell.newInstance(Math.floor(x / cellSize), Math.floor(z / cellSize));
        BitSet candidates = (BitSet) index.get(key);
        int count = 0;
        double maximum = 0;
        if (candidates != null) {
            // Read-only original segment-expanded index: all 8100 routes are represented.
            // Iterate frozen order; do not scan full polylines for non-candidate routes.
            for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
                Object trace = traces.get(i);
                double w = ((Number) weight.invoke(null, trace, point)).doubleValue();
                double d = ((Number) depth.invoke(null, w)).doubleValue();
                require(Double.isFinite(d) && d >= 0, "invalid original depth");
                if (d > 0) {
                    rows.write(quote(fields.get(0)) + "," + quote(traceId.invoke(trace)) + "," + d + "\n");
                    count++;
                    maximum = Math.max(maximum, d);
                }
            }
        }
        double expected = ((Number) combined.invoke(actual)).doubleValue();
        int expectedCount = ((Number) contributors.invoke(actual)).intValue();
        require(Double.doubleToRawLongBits(maximum) == Double.doubleToRawLongBits(expected),
                "attribution max != sample.combined at " + fields.get(0));
        require(count == expectedCount, "attribution count != sample.contributors at " + fields.get(0));
        checks.write(quote(fields.get(0)) + "," + maximum + "," + expected + "," + count
                + "," + expectedCount + ",true\n");
        return count;
    }

    private void run(Path requestsFile, Path out) throws Exception {
        List<?> requests = (List<?>) method(L0, "readRequests", Path.class).invoke(probe, requestsFile);
        long gridOrdinal = 0;
        long selected = 0;
        long positiveRows = 0;
        try (BufferedWriter rows = Files.newBufferedWriter(out.resolve("contributors.csv"));
             BufferedWriter checks = Files.newBufferedWriter(out.resolve("attribution-checks.csv"))) {
            rows.write("id,trace,depth\n");
            checks.write("id,max,samplecombined,count,contributors,passed\n");
            for (Object request : requests) {
                List<?> fields = (List<?>) csvFields.invoke(requestCsv.invoke(request));
                boolean grid = fields.get(2).toString().toLowerCase(Locale.ROOT).equals("grid");
                if (grid && gridOrdinal++ % 256 != 0) continue;
                positiveRows += writePoint(request, fields, rows, checks);
                selected++;
            }
        }
        Files.writeString(out.resolve("contributors-checks.json"), "{\"passed\":true,\"stage\":\"T0\","
                + "\"routeCount\":8100,\"requestCount\":" + requests.size()
                + ",\"selectedPoints\":" + selected + ",\"positiveRows\":" + positiveRows
                + ",\"attributionMaxChecks\":" + selected + ",\"attributionCountChecks\":" + selected
                + ",\"selection\":\"all non-grid rows; kind equals grid case-insensitively: "
                + "zero-based grid ordinal modulo 256 equals 0; independent of results\","
                + "\"tailCoverage\":\"all supplied tail points, vertices and endpoints retained; "
                + "caller supplies at least .25wu tail spacing; no generated points\","
                + "\"candidateFilter\":\"original L0 private segment-expanded cell index, unchanged\","
                + "\"calculations\":\"original reflected weight/depth/sample; original L0 main first\","
                + "\"routesRebuilt\":false,\"pointsMoved\":false,\"productionWiring\":false}\n");
        System.out.println("T0 PASS: " + selected + " attributed points, " + positiveRows + " positive rows");
    }

    public static void main(String[] args) {
        Path out = null;
        boolean l0Passed = false;
        try {
            require(args.length == 2, "usage: OldRouteT0Contributors requests.csv NEWout (run from mdk)");
            out = Path.of(args[1]).toAbsolutePath();
            require(!Files.exists(out), "refuse existing output: " + out);
            // L0 alone owns output creation and its unchanged samples.csv/checks.json.
            method(L0, "main", String[].class).invoke(null, (Object) args);
            l0Passed = true;
            new OldRouteT0Contributors().run(Path.of(args[0]), out);
        } catch (Throwable failure) {
            if (l0Passed) {
                try {
                    Files.writeString(out.resolve("contributors-checks.json"),
                            "{\"passed\":false,\"stage\":\"T0\"}\n");
                } catch (Exception secondary) {
                    failure.addSuppressed(secondary);
                }
            }
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
