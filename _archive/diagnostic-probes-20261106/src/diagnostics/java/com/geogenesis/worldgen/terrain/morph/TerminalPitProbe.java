package com.geogenesis.worldgen.terrain.morph;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-only terminal diagnostics; run from mdk with the original probes on the classpath.
 * Full original world H0 and SharedDepthField callbacks, never a quasi-inverse or copied field.
 * Requests own the controls: same-center half>=1/step=.0625, half-step-shifted centers,
 * and original-endpoint basin grids half=12/step=.125. No implicit extra sampling.
 */
public final class TerminalPitProbe {
    private static final String REQUEST_HEADER = "id,trace,cx,cz,half,step";
    private static final String SAMPLE_HEADER = "id,trace,ix,iz,world_x,world_z,H0,Dold,W,Dnew,Hnew";
    private final Path out;
    private final MethodHandle h0, oldDepth, oldHeight, weight, newDepth, newHeight;
    private final double[] modifiedBounds;
    private final String frozenTraceHash, rebuiltTraceHash;
    private long actualSamples, outsideOldSamples, outsideModifiedSamples;

    public record Sample(double H0, double Dold, double W, double Dnew, double Hnew) {}
    private record Request(String id, String trace, double cx, double cz,
                           double half, double step, int intervals) {}

    /** Creates an exclusively NEW output directory, then invokes the original private rebuild.
     * Construction rebuilds four traces; it does not execute a grid or discovery sweep.
     */
    public TerminalPitProbe(Path weights, Path newOut) throws Exception {
        require(Files.isRegularFile(weights), "weights.csv required");
        Class<?> bridge = Class.forName("com.geogenesis.worldgen.terrain.morph.RemainingDropProbe");
        Path frozen = ((Path) value(bridge, null, "FROZEN")).toAbsolutePath().normalize();
        out = fresh(newOut, frozen);
        Constructor<?> constructor = bridge.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object original = constructor.newInstance();
        Method rebuild = bridge.getDeclaredMethod("rebuild", Path.class);
        rebuild.setAccessible(true);
        rebuild.invoke(original, out); // Empty out is required; original writes traces.json here.
        require(((List<?>) value(bridge, original, "traces")).size() == 4, "four original traces");
        Path rebuilt = out.resolve("traces.json"), baseline = frozen.resolve("traces.json");
        frozenTraceHash = sha(baseline);
        rebuiltTraceHash = sha(rebuilt);
        require(Files.mismatch(rebuilt, baseline) == -1
                && frozenTraceHash.equals(rebuiltTraceHash), "original trace bytes/hash changed");
        Object carrier = value(bridge, original, "h0"), old = value(bridge, original, "old");
        Class<?> heightType = Class.forName("com.geogenesis.standalone.NoiseFollowingErosion$HeightField");
        Class<?> cutType = Class.forName("com.geogenesis.standalone.NoiseFollowingErosion$Cut");
        Class<?> sharedType = Class.forName("com.geogenesis.standalone.SharedDepthField");
        Object coefficients = sharedType.getMethod("readWeights", Path.class).invoke(null, weights);
        Object field = sharedType.getConstructor(heightType, cutType, Map.class)
                .newInstance(carrier, old, coefficients);
        h0 = bind(heightType, carrier, "height");
        oldDepth = bind(cutType, old, "displacement");
        oldHeight = bind(cutType, old, "height");
        weight = bind(sharedType, field, "weight");
        newDepth = bind(sharedType, field, "displacement");
        newHeight = bind(sharedType, field, "height");
        modifiedBounds = bounds(sharedType.getMethod("modifiedBounds").invoke(field));
    }

    /** Public callable sampler: all five values are actual original callbacks, without clipping.
     * Counters are sequential; this diagnostic instance is not intended for concurrent use.
     */
    public Sample sample(double x, double z) {
        require(Double.isFinite(x) && Double.isFinite(z), "finite world coordinates");
        double baseline = query(h0, x, z), d = query(oldDepth, x, z);
        double w = query(weight, x, z), dn = query(newDepth, x, z), hn = query(newHeight, x, z);
        require(Double.isFinite(baseline) && Double.isFinite(d) && Double.isFinite(w)
                && Double.isFinite(dn) && Double.isFinite(hn), "nonfinite sample at " + x + "," + z);
        require(d >= 0 && dn >= 0 && dn <= d && w >= 0 && w <= 1, "actual depth/weight budget");
        require(hn <= baseline, "actual Hnew > H0");
        require(bits(dn, d == 0 ? 0.0 : d * w), "unclipped original depth composition");
        require(bits(hn, dn == 0 ? baseline : baseline - dn), "unclipped original H composition");
        if (d == 0) {
            require(bits(hn, baseline) && bits(dn, 0.0), "outside old cut must preserve H0 bits");
            outsideOldSamples++;
        }
        if (outsideModified(x, z)) {
            require(bits(w, 1.0) && bits(dn, d) && bits(hn, query(oldHeight, x, z)),
                    "outside modified support must preserve old field bits");
            outsideModifiedSamples++;
        }
        actualSamples++;
        return new Sample(baseline, d, w, dn, hn);
    }

    private boolean outsideModified(double x, double z) {
        return modifiedBounds == null || x <= modifiedBounds[0] || z <= modifiedBounds[1]
                || x >= modifiedBounds[2] || z >= modifiedBounds[3];
    }

    private void grids(List<Request> requests) throws IOException {
        try (BufferedWriter csv = writer(out.resolve("samples.csv"))) {
            csv.write(SAMPLE_HEADER);
            csv.newLine();
            for (Request r : requests) {
                for (int iz = 0; iz <= r.intervals(); iz++) {
                    double z = coordinate(r.cz(), r, iz);
                    for (int ix = 0; ix <= r.intervals(); ix++) {
                        double x = coordinate(r.cx(), r, ix);
                        Sample p = sample(x, z);
                        row(csv, r.id(), r.trace(), ix, iz, x, z,
                                p.H0(), p.Dold(), p.W(), p.Dnew(), p.Hnew());
                    }
                }
            }
        }
    }

    private static double coordinate(double center, Request r, int index) {
        // Center-relative regular grid, including both boundaries; no clamping or remapping.
        return center + (index * r.step() - r.half());
    }

    private static List<Request> requests(Path path) throws IOException {
        List<Request> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            require(REQUEST_HEADER.equals(in.readLine()), "exact requests header: " + REQUEST_HEADER);
            String line;
            while ((line = in.readLine()) != null) {
                String[] c = line.split(",", -1);
                require(c.length == 6, "request must have six unquoted CSV cells: " + line);
                require(!c[0].isBlank() && !c[1].isBlank() && ids.add(c[0]), "nonempty unique id/trace");
                require(c[0].indexOf('"') < 0 && c[1].indexOf('"') < 0, "unquoted id/trace required");
                double cx = number(c[2]), cz = number(c[3]), half = number(c[4]), step = number(c[5]);
                require(half >= 0 && step > 0, "half >= 0 and step > 0 required");
                double n = 2 * half / step, rounded = Math.rint(n);
                require(Double.isFinite(n) && n >= 0 && rounded < Integer.MAX_VALUE - 1
                        && Math.abs(n - rounded) <= 8 * Math.ulp(n), "2half/step must be an integer");
                require(Double.isFinite(cx - half) && Double.isFinite(cx + half)
                        && Double.isFinite(cz - half) && Double.isFinite(cz + half), "finite grid bounds");
                require(half == 0 || (cx - half + step > cx - half && cz - half + step > cz - half
                        && cx + half - step < cx + half && cz + half - step < cz + half),
                        "step must resolve at world coordinates");
                result.add(new Request(c[0], c[1], cx, cz, half, step, (int) rounded));
            }
        }
        require(!result.isEmpty(), "at least one request required");
        return List.copyOf(result);
    }

    private void checks(int requestCount, long expectedSamples) throws IOException {
        require(actualSamples == expectedSamples, "actual sample count differs from request budget");
        String body = "{\"contractPassed\":true,\"requestCount\":" + requestCount
                + ",\"expectedSamples\":" + expectedSamples + ",\"actualSamples\":" + actualSamples
                + ",\"actualDepthBudgetPassed\":true,\"heightNotAboveH0Passed\":true"
                + ",\"depthBudget\":\"0<=Dnew<=Dold\",\"compositionBitsExact\":true"
                + ",\"outsideOldCutBitChecks\":" + outsideOldSamples
                + ",\"outsideModifiedSupportBitChecks\":" + outsideModifiedSamples
                + ",\"outsideDomainBitsPassed\":true,\"probeClipping\":false"
                + ",\"originalFieldUnmodified\":true,\"fullH0WorldCallback\":true"
                + ",\"traceCount\":4,\"traceJsonBytesExact\":true,\"traceJsonSha256\":\""
                + rebuiltTraceHash + "\",\"frozenTraceJsonSha256\":\"" + frozenTraceHash
                + "\",\"gridArithmetic\":\"center+(index*step-half); ix,iz start at zero\""
                + ",\"finiteSamplesOnly\":true,\"globalPassed\":false,\"effectAccepted\":false}\n";
        try (BufferedWriter json = writer(out.resolve("checks.json"))) { json.write(body); }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "usage: TerminalPitProbe weights.csv requests.csv NEWout (run from mdk)");
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        List<Request> requests = requests(input);
        long expected = 0;
        for (Request r : requests) {
            long side = (long) r.intervals() + 1;
            expected = Math.addExact(expected, Math.multiplyExact(side, side));
        }
        TerminalPitProbe probe = new TerminalPitProbe(Path.of(args[0]), Path.of(args[2]));
        Files.copy(input, probe.out.resolve("requests.csv")); // Preserve original bytes verbatim.
        probe.grids(requests);
        probe.checks(requests.size(), expected);
        System.out.println("TERMINAL PIT DIAGNOSTICS " + probe.out + "; actualSamples="
                + probe.actualSamples + "; sampled contracts only; no effect acceptance");
    }

    private static Object value(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static MethodHandle bind(Class<?> type, Object target, String name) throws Exception {
        return MethodHandles.lookup().unreflect(type.getMethod(name, double.class, double.class))
                .bindTo(target);
    }

    private static double query(MethodHandle handle, double x, double z) {
        try { return (double) handle.invokeExact(x, z); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Original field callback failed", failure); }
    }

    private static double[] bounds(Object bounds) throws Exception {
        if (bounds == null) return null;
        double[] result = new double[4];
        String[] names = {"minX", "minZ", "maxX", "maxZ"};
        for (int i = 0; i < names.length; i++) {
            result[i] = (double) bounds.getClass().getMethod(names[i]).invoke(bounds);
        }
        return result;
    }

    private static Path fresh(Path requested, Path frozen) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        require(!path.startsWith(frozen), "frozen baseline is read only");
        Files.createDirectories(path.getParent());
        Files.createDirectory(path); // Atomic refusal of any existing output directory.
        return path;
    }

    private static BufferedWriter writer(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void row(BufferedWriter out, Object... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i != 0) out.write(',');
            out.write(String.valueOf(cells[i]));
        }
        out.newLine();
    }

    private static String sha(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static double number(String text) {
        double value = Double.parseDouble(text);
        require(Double.isFinite(value), "finite request number required");
        return value;
    }

    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
