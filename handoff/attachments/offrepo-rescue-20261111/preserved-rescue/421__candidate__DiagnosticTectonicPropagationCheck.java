package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.TectonicDeformation;
import com.geogenesis.worldgen.terrain.TectonicField;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Finite R3 consumer/ordering checks and R4 evidence, not terrain or visual acceptance. */
public final class DiagnosticTectonicPropagationCheck {
    private static final long[] SEEDS = {9139912035078620160L, 0, -419, 12345};
    // Values channels: never confuse an adapted candidate Sample with original tf.sample().
    private static final String[] CHANNELS = {"stress", "distance", "envelope110",
            "convergent110", "divergent130", "offset"};
    private static final double[] ROUND = {2e-14, 2e-10, 3e-14, 5e-14, 5e-14, 2e-14};
    private static final double WARP_LIP = 1 + 130 * 7.5 / 400;
    private static final double SMOOTH_LIP = 1.75;
    private final StringWriter stacks = new StringWriter();
    private final Map<String, PrintWriter> outputs = new LinkedHashMap<>();
    private final long[] coverage = new long[8], detected = new long[5];
    private final List<Long> sampleNanos = new ArrayList<>();
    private long checks, failures, replayCenters;
    private Parts parts;

    private record Point(String id, double x, double z) { }
    private record Center(long seed, String id, double qx, double qz, double x, double z,
                          double oldStress, double oldDistance, double oldOffset) { }
    private record Components(double mask, double fold, double fault) { }
    private record Bounds(double foldMax, double faultMax, double foldLip, double faultLip) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: outDir frozenM1bDir");
        Path out = Path.of(args[0]);
        // CREATE_NEW throughout; even an empty pre-existing output directory is refused.
        Files.createDirectory(out);
        var check = new DiagnosticTectonicPropagationCheck();
        try {
            check.open(out);
            check.parts = new Parts();
            check.synthetic();
            check.fixedMatrix();
            check.replay(Path.of(args[1]));
            check.finishCoverage();
        } catch (Throwable t) { check.exception(0, "run", t); }
        finally { check.save(out); }
        System.out.println((check.failures == 0 ? "PASS" : "FAIL") + " checks=" + check.checks
                + " failures=" + check.failures + "; finite R3 checks only; R4 visual acceptance pending");
        if (check.failures != 0) System.exit(1);
    }

    private void open(Path out) throws Exception {
        openCsv(out, "assertions", "seed,case,check,status,detail");
        openCsv(out, "samples", "seed,case,x,z,arm,channel,name,value,belt,pairNanos");
        openCsv(out, "continuity", "seed,case,dx,dz,h,arm,channel,name,minus,center,plus,"
                + "worldDerivative,observedChange,localLipschitzBudget,inputTaylorError,inputTaylorBudget");
        openCsv(out, "negative-controls", "seed,case,mutation,channel,name,wrong,expected,tolerance,detected");
        openCsv(out, "coverage", "seed,positive015,negative015,nearNeutral015,convergentGate,"
                + "divergentGate,beltNonzeroOffset,beltZero,exactNeutralSynthetic");
        openCsv(out, "cost", "seed,stage,samples,nanos,nanosPerPair");
    }

    private void openCsv(Path out, String name, String header) throws Exception {
        PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out.resolve(name + ".csv"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW));
        outputs.put(name, writer);
        writer.println(header);
    }

    private void save(Path out) throws Exception {
        for (var entry : outputs.entrySet()) {
            entry.getValue().flush();
            if (entry.getValue().checkError()) {
                failures++;
                stacks.append("CSV write failure: ").append(entry.getKey()).append('\n');
            }
            entry.getValue().close();
        }
        Files.writeString(out.resolve("stack.txt"), stacks.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("summary.txt"), summary(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("check.txt"), summary(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private String summary() {
        List<Long> sorted = new ArrayList<>(sampleNanos);
        Collections.sort(sorted);
        long p95 = sorted.isEmpty() ? 0 : sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
        return (failures == 0 ? "PASS" : "FAIL") + "\nchecks=" + checks + "\nfailures=" + failures
                + "\noldCenters=" + replayCenters + "\nchannels=" + Arrays.toString(CHANNELS)
                + "\ncoverage=" + Arrays.toString(coverage) + "\nnegativeSignals=" + Arrays.toString(detected)
                + "\nfixedPairP95Nanos=" + p95 + "\nJVM=" + System.getProperty("java.version")
                + "\nJVMArguments=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                + "\ncache=NONE; eviction/rebuild=N/A; cold instances, warm reverse/shuffle and interleaved seeds checked."
                + "\nNear window: center(-140,137),13x13,step300; seeds=" + Arrays.toString(SEEDS)
                + "\nAfter means candidate-adapted Sample, NOT original tf.sample; before is original public path."
                + "\nNeutral real coverage means |S|<0.15; exact zero is tested separately with synthetic inputs."
                + "\nContinuity uses direct world offsets: 1/.1/.01/.001 and 2^-k,k=0..20, both axes and saved world direction."
                + "\nClamp promises C0, not global C1; directional derivatives at crossing clamps are observations only."
                + "\nGate Taylor budgets use actual S/D increments and global analytic input Hessian bounds."
                + "\nSpatial C0 budgets use analytic local driver bounds and original component Lipschitz bounds."
                + "\nsmoothPos(-.09,.18)=-.0140625 is preserved, observed, and NEVER treated as a probability."
                + "\nNo .75 shrink-ratio pass criterion. Finite evidence is not a global numerical proof."
                + "\nNo parameters, terrain chain, production, S3 or 0.30 changes; no geologic-realism claim."
                + "\nR1/R2 prerequisite must be checked separately by main agent."
                + "\nR4 images/gradient/highpass/grid-artifact review, asset SHA protection and integration are external/pending."
                + "\nCost is finite diagnostic pair timing including both drivers, not isolated driver speed or game FPS.\n";
    }

    private static String csv(Object... fields) {
        StringBuilder result = new StringBuilder();
        for (Object field : fields) {
            if (result.length() != 0) result.append(',');
            result.append('"').append(String.valueOf(field).replace("\"", "\"\"")).append('"');
        }
        return result.toString();
    }

    private void record(boolean ok, long seed, String id, String label, Object detail) {
        checks++;
        if (!ok) failures++;
        PrintWriter writer = outputs.get("assertions");
        if (writer != null) writer.println(csv(seed, id, label, ok ? "PASS" : "FAIL", detail));
    }

    private void exception(long seed, String id, Throwable t) {
        record(false, seed, id, "exception", t);
        t.printStackTrace(new PrintWriter(stacks));
    }

    private void eq(long seed, String id, String label, double actual, double expected, double tolerance) {
        record(close(actual, expected, tolerance), seed, id, label,
                "actual=" + actual + ";expected=" + expected + ";tolerance=" + tolerance);
    }

    private static boolean close(double a, double b, double tolerance) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= tolerance;
    }

    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static double[] channels(DiagnosticTectonicPropagation.Values value) {
        return new double[]{value.stress(), value.distance(), value.envelope110(), value.convergent110(),
                value.divergent130(), value.offset()};
    }

    // Independent smoothstep polynomial, not the consumer implementation under test.
    private static double strength(double stress) {
        double t = Math.min(1, Math.max(0, -stress / .15));
        return 3 * t * t - 2 * t * t * t;
    }

    private static double strengthDerivative(double stress) {
        double t = -stress / .15;
        return t <= 0 || t >= 1 ? 0 : -6 * t * (1 - t) / .15;
    }

    private static double positive(double stress) {
        double e = TectonicField.STRESS_POS_EPS;
        double t = Math.min(1, Math.max(0, (stress + e) / (2 * e)));
        return stress * (3 * t * t - 2 * t * t * t);
    }

    private static double positiveDerivative(double stress) {
        double e = TectonicField.STRESS_POS_EPS;
        if (stress <= -e) return 0;
        if (stress >= e) return 1;
        double t = (stress + e) / (2 * e);
        return t * t * (3 - 2 * t) + stress * 3 * t * (1 - t) / e;
    }

    private static double gaussian(double distance, double sigma) {
        double t = distance / sigma;
        return Math.exp(-.5 * t * t);
    }

    private static double offset(double stress, Components p) {
        double cw = positive(stress), dw = positive(-stress);
        return cw * p.fold + (.6 * cw + dw) * p.fault;
    }

    private static DiagnosticTectonicPropagation.Values reference(double stress, double distance, Components p) {
        double envelope = gaussian(distance, 110);
        return new DiagnosticTectonicPropagation.Values(stress, distance, envelope,
                strength(-stress) * envelope, strength(stress) * gaussian(distance, 130), offset(stress, p));
    }

    private void compare(long seed, String id, String label, DiagnosticTectonicPropagation.Values actual,
                         DiagnosticTectonicPropagation.Values expected) {
        double[] a = channels(actual), b = channels(expected);
        for (int i = 0; i < CHANNELS.length; i++) eq(seed, id, label + ":" + CHANNELS[i], a[i], b[i], ROUND[i]);
        record(a[0] >= -1 && a[0] <= 1 && a[1] >= 0, seed, id, label + ":driver-range", Arrays.toString(a));
        for (int i = 2; i <= 4; i++) record(a[i] >= 0 && a[i] <= 1, seed, id,
                label + ":gate-range:" + CHANNELS[i], a[i]);
        record(!(a[3] > 0 && a[4] > 0), seed, id, label + ":exclusive-gate-sign", "convergence vs divergence");
    }

    private void synthetic() throws Exception {
        eq(0, "constants", "stress saturation", OceanMorphConcepts.SAT_STRESS, .15, 0);
        eq(0, "constants", "ridge sigma", OceanMorphConcepts.RIDGE_BAND_SIGMA, 130, 0);
        eq(0, "constants", "smooth epsilon", TectonicField.STRESS_POS_EPS, .18, 0);
        eq(0, "smoothPos", "negative signature", TectonicField.smoothPos(-.09, .18), -.0140625, 1e-17);
        var deformation = new TectonicDeformation(SEEDS[0]);
        Components p = parts.at(deformation, -140, 137);
        for (double s : new double[]{-1, -.18, -.15, -.09, -1e-12, -0.0, 0, 1e-12, .09, .15, .18, 1}) {
            for (double d : new double[]{0, 110, 130, 320, 420, 1000}) {
                var metadata = new TectonicField.Sample(9, TectonicField.TRANSFORM, .7, .6, .8, .4, 27);
                var adapted = DiagnosticTectonicPropagation.adapt(metadata, s, d);
                var value = DiagnosticTectonicPropagation.consume(adapted, deformation, -140, 137);
                String id = "synthetic:" + s + ":" + d;
                checkMetadata(0, id, metadata, adapted, s, d);
                compare(0, id, "independent-consumer", value, reference(s, d, p));
                eq(0, id, "independent-discrete-strength", OceanMorphConcepts.discreteStrength(s), strength(s), 2e-15);
                if (s == 0) {
                    coverage[7]++;
                    record(value.convergent110() == 0 && value.divergent130() == 0 && value.offset() == 0,
                            0, id, "exact-neutral", "zero gates and zero offset; signed zero allowed");
                }
                if (d == 0 && Math.abs(s) >= .15) record(s > 0
                        ? value.convergent110() == 1 && value.divergent130() == 0
                        : value.divergent130() == 1 && value.convergent110() == 0,
                        0, id, "saturation-and-sign", value);
            }
        }
    }

    private static List<Point> points() {
        List<Point> result = new ArrayList<>();
        for (int z = -6; z <= 6; z++) for (int x = -6; x <= 6; x++)
            result.add(new Point("near:" + x + ":" + z, -140 + 300 * x, 137 + 300 * z));
        for (double v : new double[]{-4000, -2000, -DiagnosticTectonicDriver.H, 0}) {
            result.add(new Point("negative-axis-x:" + v, v, 0));
            result.add(new Point("negative-axis-z:" + v, 0, v));
            result.add(new Point("negative-corner:" + v, v, v));
            result.add(new Point("corner-minus-ulp:" + v, Math.nextDown(v), Math.nextDown(v)));
            result.add(new Point("corner-plus-ulp:" + v, Math.nextUp(v), Math.nextUp(v)));
        }
        for (double v : new double[]{1e5, 1e6, 29998000}) for (int sign : new int[]{-1, 1}) {
            result.add(new Point("far-diagonal:" + sign * v, sign * v, sign * v));
            result.add(new Point("far-opposite:" + sign * v, sign * v, -sign * v));
            result.add(new Point("far-axis:" + sign * v, sign * v, 137));
        }
        return result;
    }

    private void fixedMatrix() throws Exception {
        List<Point> points = points();
        Map<Long, DiagnosticTectonicPropagation> live = new LinkedHashMap<>();
        Map<Long, List<DiagnosticTectonicPropagation.Pair>> baselines = new LinkedHashMap<>();
        for (long seed : SEEDS) {
            var propagation = new DiagnosticTectonicPropagation(seed);
            live.put(seed, propagation);
            List<DiagnosticTectonicPropagation.Pair> baseline = new ArrayList<>();
            long[] counts = new long[8];
            var original = new TectonicField(seed);
            var driver = new DiagnosticTectonicDriver(seed);
            var deformation = new TectonicDeformation(seed);
            long start = System.nanoTime();
            for (Point p : points) {
                long before = System.nanoTime();
                var pair = propagation.sample(p.x, p.z);
                long nanos = System.nanoTime() - before;
                sampleNanos.add(nanos);
                baseline.add(pair);
                validatePoint(seed, p, pair, original, driver, deformation, counts, nanos);
            }
            cost(seed, "cold-forward-with-independent-checks", points.size(), start);
            baselines.put(seed, baseline);
            outputs.get("coverage").println(csv(seed, counts[0], counts[1], counts[2], counts[3], counts[4],
                    counts[5], counts[6], 0));
            for (int i = 0; i < counts.length; i++) coverage[i] += counts[i];
            ordering(seed, points, baseline, propagation);
        }
        long start = System.nanoTime();
        for (int i = points.size() - 1; i >= 0; i--) for (int j = 0; j < SEEDS.length; j++) {
            long seed = SEEDS[(j + i) % SEEDS.length];
            Point p = points.get(i);
            samePair(seed, p.id, "interleaved-same-instances", live.get(seed).sample(p.x, p.z), baselines.get(seed).get(i));
        }
        cost(0, "interleaved-with-bitwise-checks", points.size() * SEEDS.length, start);
    }

    private void ordering(long seed, List<Point> points, List<DiagnosticTectonicPropagation.Pair> baseline,
                          DiagnosticTectonicPropagation warm) {
        List<Integer> order = new ArrayList<>();
        for (int i = points.size() - 1; i >= 0; i--) order.add(i);
        for (int stage = 0; stage < 2; stage++) {
            if (stage == 1) Collections.shuffle(order, new Random(0x52A41L ^ seed));
            var cold = new DiagnosticTectonicPropagation(seed);
            String label = stage == 0 ? "reverse" : "shuffle";
            long start = System.nanoTime();
            for (int index : order) {
                Point p = points.get(index);
                samePair(seed, p.id, "cold-" + label, cold.sample(p.x, p.z), baseline.get(index));
                samePair(seed, p.id, "same-instance-" + label, warm.sample(p.x, p.z), baseline.get(index));
            }
            cost(seed, label + "-cold-and-warm-with-checks", 2 * points.size(), start);
        }
    }

    private void samePair(long seed, String id, String label, DiagnosticTectonicPropagation.Pair a,
                          DiagnosticTectonicPropagation.Pair b) {
        sameValues(seed, id, label + ":before", a.before(), b.before());
        sameValues(seed, id, label + ":after", a.after(), b.after());
    }

    private void sameValues(long seed, String id, String label, DiagnosticTectonicPropagation.Values a,
                            DiagnosticTectonicPropagation.Values b) {
        double[] av = channels(a), bv = channels(b);
        boolean identical = true;
        for (int i = 0; i < av.length; i++) identical &= bits(av[i], bv[i]);
        record(identical, seed, id, label + ":bitwise-six-channels", Arrays.toString(av) + ";" + Arrays.toString(bv));
    }

    private void cost(long seed, String stage, int samples, long start) {
        long elapsed = System.nanoTime() - start;
        outputs.get("cost").println(csv(seed, stage, samples, elapsed, (double) elapsed / samples));
    }

    private void validatePoint(long seed, Point p, DiagnosticTectonicPropagation.Pair pair, TectonicField original,
                               DiagnosticTectonicDriver driver, TectonicDeformation deformation,
                               long[] counts, long nanos) throws Exception {
        var old = original.sample(p.x, p.z);
        var repaired = driver.sample(p.x, p.z);
        Components component = parts.at(deformation, p.x, p.z);
        var adapted = DiagnosticTectonicPropagation.adapt(old, repaired.stress(), repaired.distance());
        checkMetadata(seed, p.id, old, adapted, repaired.stress(), repaired.distance());
        compare(seed, p.id, "before-independent", pair.before(), reference(old.stress(), old.dist(), component));
        compare(seed, p.id, "after-independent", pair.after(), reference(repaired.stress(), repaired.distance(), component));
        perturbMetadata(seed, p, old, adapted, deformation, pair.after());
        double belt = deformation.beltMaskAt(p.x, p.z);
        record(belt >= 0 && belt <= 1, seed, p.id, "original-belt-range", belt);
        if (belt == 0) record(pair.after().offset() == 0, seed, p.id, "outside-belt-zero-offset", pair.after().offset());
        tally(counts, pair.after(), belt);
        export(seed, p, "before-original", pair.before(), belt, nanos);
        export(seed, p, "after-adapted", pair.after(), belt, nanos);
        negativeSignals(seed, p, old, adapted, deformation, reference(repaired.stress(), repaired.distance(), component));
    }

    private void checkMetadata(long seed, String id, TectonicField.Sample old, TectonicField.Sample adapted,
                               double stress, double distance) {
        boolean metadata = old.btype() == adapted.btype() && bits(old.rate(), adapted.rate())
                && bits(old.tangentX(), adapted.tangentX()) && bits(old.tangentZ(), adapted.tangentZ())
                && bits(old.alongCoord(), adapted.alongCoord());
        record(metadata && bits(adapted.stress(), stress) && bits(adapted.dist(), distance), seed, id,
                "adapt-replaces-only-S-D", "all other original metadata preserved bitwise");
    }

    private void perturbMetadata(long seed, Point p, TectonicField.Sample old, TectonicField.Sample adapted,
                                 TectonicDeformation deformation, DiagnosticTectonicPropagation.Values expected) {
        for (int type = 0; type <= 3; type++) {
            var changed = new TectonicField.Sample(old.dist(), type, 7 + type, -17 - type, 31 + type,
                    old.stress(), -1e9 - type);
            var candidate = DiagnosticTectonicPropagation.adapt(changed, adapted.stress(), adapted.dist());
            checkMetadata(seed, p.id, changed, candidate, adapted.stress(), adapted.dist());
            sameValues(seed, p.id, "metadata-perturbation:" + type,
                    DiagnosticTectonicPropagation.consume(candidate, deformation, p.x, p.z), expected);
        }
        var distanceOnly = DiagnosticTectonicPropagation.adapt(old, adapted.stress(), adapted.dist() + 10000);
        record(bits(deformation.offset(distanceOnly, p.x, p.z), expected.offset()), seed, p.id,
                "offset-independent-of-distance", "original deformation consumes S/world, not D");
    }

    private static void tally(long[] counts, DiagnosticTectonicPropagation.Values v, double belt) {
        if (v.stress() >= .15) counts[0]++;
        if (v.stress() <= -.15) counts[1]++;
        if (Math.abs(v.stress()) < .15) counts[2]++;
        if (v.convergent110() > 0) counts[3]++;
        if (v.divergent130() > 0) counts[4]++;
        if (belt > 0 && Math.abs(v.offset()) > 1e-14) counts[5]++;
        if (belt == 0) counts[6]++;
    }

    private void export(long seed, Point p, String arm, DiagnosticTectonicPropagation.Values value, double belt, long nanos) {
        double[] a = channels(value);
        for (int i = 0; i < a.length; i++) outputs.get("samples").println(csv(seed, p.id, p.x, p.z,
                arm, i, CHANNELS[i], a[i], belt, nanos));
    }

    private void negativeSignals(long seed, Point p, TectonicField.Sample old, TectonicField.Sample adapted,
                                 TectonicDeformation deformation, DiagnosticTectonicPropagation.Values expected) {
        var onlyS = DiagnosticTectonicPropagation.adapt(old, adapted.stress(), old.dist());
        var onlyD = DiagnosticTectonicPropagation.adapt(old, old.stress(), adapted.dist());
        mutation(seed, p.id, 0, "half-wire-only-S", DiagnosticTectonicPropagation.consume(onlyS, deformation, p.x, p.z), expected);
        mutation(seed, p.id, 1, "half-wire-only-D", DiagnosticTectonicPropagation.consume(onlyD, deformation, p.x, p.z), expected);
        mutation(seed, p.id, 2, "overlap-old-plus-new-offset", withOffset(expected,
                expected.offset() + deformation.offset(old, p.x, p.z)), expected);
        mutation(seed, p.id, 3, "duplicate-new-offset", withOffset(expected, 2 * expected.offset()), expected);
        var flipped = DiagnosticTectonicPropagation.adapt(old, -adapted.stress(), adapted.dist());
        mutation(seed, p.id, 4, "reversed-sign", DiagnosticTectonicPropagation.consume(flipped, deformation, p.x, p.z), expected);
    }

    private static DiagnosticTectonicPropagation.Values withOffset(DiagnosticTectonicPropagation.Values v, double offset) {
        return new DiagnosticTectonicPropagation.Values(v.stress(), v.distance(), v.envelope110(),
                v.convergent110(), v.divergent130(), offset);
    }

    private void mutation(long seed, String id, int kind, String name, DiagnosticTectonicPropagation.Values wrong,
                          DiagnosticTectonicPropagation.Values expected) {
        double[] a = channels(wrong), b = channels(expected);
        // Only downstream signals count: raw S/D inequality alone cannot certify a consumer negative control.
        for (int i = 2; i < a.length; i++) {
            boolean rejects = !close(a[i], b[i], ROUND[i]);
            if (rejects) detected[kind]++;
            outputs.get("negative-controls").println(csv(seed, id, name, i, CHANNELS[i], a[i], b[i], ROUND[i], rejects));
        }
    }

    private void finishCoverage() {
        for (int i = 0; i < coverage.length; i++) record(coverage[i] > 0, 0, "coverage",
                "nonempty:" + i, coverage[i]);
        for (int i = 0; i < detected.length; i++) record(detected[i] > 0, 0, "negative-controls",
                "downstream-mutation-detected:" + i, detected[i]);
        record(replayCenters == 264, 0, "replay", "all-frozen-centers", replayCenters);
    }

    private void replay(Path frozen) throws Exception {
        Path world = frozen.resolve("driver-check/world.csv");
        if (!Files.isRegularFile(world)) world = frozen.resolve("world.csv");
        Map<String, Center> centers = new LinkedHashMap<>();
        Map<String, double[]> minus = new LinkedHashMap<>(), plus = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(world, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("seed,case,level,side,h,targetX,targetZ,x,z,"))
                throw new IllegalArgumentException("unexpected frozen world.csv schema: " + world);
            String line;
            while ((line = reader.readLine()) != null) readFrozen(line, centers, minus, plus);
        }
        record(centers.size() == 264, 0, "frozen", "264-unique-centers", centers.size());
        for (Center c : centers.values()) {
            try {
                replayCenters++;
                replayCenter(c, minus.get(c.seed + "/" + c.id), plus.get(c.seed + "/" + c.id));
            } catch (Throwable t) { exception(c.seed, c.id, t); }
        }
    }

    private void readFrozen(String line, Map<String, Center> centers, Map<String, double[]> minus,
                            Map<String, double[]> plus) {
        // Frozen numeric CSV has quoted numbers/ids, no embedded commas or escaped quotes.
        String[] r = line.replace("\"", "").split(",", -1);
        if (r.length != 37) throw new IllegalArgumentException("unexpected frozen columns=" + r.length);
        long seed = Long.parseLong(r[0]);
        if (Arrays.stream(SEEDS).noneMatch(s -> s == seed)) throw new IllegalArgumentException("unexpected seed=" + seed);
        String key = r[0] + "/" + r[1];
        if (r[2].equals("-1")) {
            Center c = new Center(seed, r[1], number(r[5]), number(r[6]), number(r[7]), number(r[8]),
                    number(r[13]), number(r[15]), number(r[35]));
            if (centers.putIfAbsent(key, c) != null) throw new IllegalArgumentException("duplicate center=" + key);
        } else if (r[2].equals("0")) {
            Map<String, double[]> target = r[3].equals("-1") ? minus : plus;
            if (!r[3].equals("-1") && !r[3].equals("1")) throw new IllegalArgumentException("unexpected side");
            if (target.putIfAbsent(key, new double[]{number(r[7]), number(r[8])}) != null)
                throw new IllegalArgumentException("duplicate direction=" + key);
        }
    }

    private static double number(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("nonfinite frozen value=" + text);
        return value;
    }

    private void replayCenter(Center c, double[] minus, double[] plus) throws Exception {
        var propagation = new DiagnosticTectonicPropagation(c.seed);
        var driver = new DiagnosticTectonicDriver(c.seed);
        var deformation = new TectonicDeformation(c.seed);
        var center = propagation.sample(c.x, c.z);
        var warped = driver.warp(c.x, c.z);
        eq(c.seed, c.id, "saved-center-warp-x", warped.x(), c.qx, 4e-12);
        eq(c.seed, c.id, "saved-center-warp-z", warped.z(), c.qz, 4e-12);
        eq(c.seed, c.id, "frozen-original-stress", center.before().stress(), c.oldStress, 1e-12);
        eq(c.seed, c.id, "frozen-original-distance", center.before().distance(), c.oldDistance, 2e-9);
        eq(c.seed, c.id, "frozen-original-offset", center.before().offset(), c.oldOffset, 2e-14);
        if (minus == null || plus == null) throw new IllegalArgumentException("missing frozen world direction=" + c.id);
        double dx = plus[0] - minus[0], dz = plus[1] - minus[1], length = Math.hypot(dx, dz);
        if (!(length > 0)) throw new IllegalArgumentException("zero saved world direction=" + c.id);
        // Site direction comes from actual saved world endpoints, never a guessed axis/plate velocity.
        double[][] directions = {{1, 0}, {0, 1}, {dx / length, dz / length}};
        Components components = parts.at(deformation, c.x, c.z);
        compare(c.seed, c.id, "center-consumer", center.after(),
                reference(driver.sample(c.x, c.z).stress(), driver.sample(c.x, c.z).distance(), components));
        double sl = stressLipschitz(driver, c.x, c.z, 1);
        double dl = distanceLipschitz(driver, c.x, c.z, 1);
        for (double[] direction : directions)
            series(c, propagation, driver, deformation, center, direction[0], direction[1], sl, dl);
    }

    private void series(Center c, DiagnosticTectonicPropagation propagation, DiagnosticTectonicDriver driver,
                        TectonicDeformation deformation, DiagnosticTectonicPropagation.Pair center,
                        double dx, double dz, double stressLip, double distanceLip) throws Exception {
        List<Double> steps = new ArrayList<>(List.of(1.0, .1, .01, .001));
        for (int k = 0; k <= 20; k++) steps.add(Math.scalb(1.0, -k));
        var snapshot = driver.sample(c.x, c.z);
        double[] derivative = derivatives(c, snapshot, deformation, dx, dz);
        for (double h : steps) {
            double xm = c.x - h * dx, zm = c.z - h * dz, xp = c.x + h * dx, zp = c.z + h * dz;
            var m = propagation.sample(xm, zm);
            var p = propagation.sample(xp, zp);
            Components cm = parts.at(deformation, xm, zm), cp = parts.at(deformation, xp, zp);
            compare(c.seed, c.id, "minus-consumer:" + h, m.after(), reference(m.after().stress(), m.after().distance(), cm));
            compare(c.seed, c.id, "plus-consumer:" + h, p.after(), reference(p.after().stress(), p.after().distance(), cp));
            continuityRow(c, dx, dz, h, "before-original-observation", m.before(), center.before(), p.before(),
                    derivative, null, null, null);
            double[] budget = spatialBudgets(stressLip, distanceLip, h);
            double[] errors = gateTaylor(m.after(), center.after(), p.after());
            double[] taylorBudget = gateTaylorBudgets(m.after(), center.after(), p.after());
            continuityRow(c, dx, dz, h, "after-adapted", m.after(), center.after(), p.after(),
                    derivative, budget, errors, taylorBudget);
        }
    }

    private double[] spatialBudgets(double sl, double dl, double h) {
        Bounds b = parts.bounds;
        double s = sl * h, d = dl * h;
        double offsetLip = SMOOTH_LIP * sl * (b.foldMax + 1.6 * b.faultMax) + b.foldLip + 1.6 * b.faultLip;
        return new double[]{s, d, Math.exp(-.5) / 110 * d,
                10 * s + Math.exp(-.5) / 110 * d, 10 * s + Math.exp(-.5) / 130 * d, offsetLip * h};
    }

    private void continuityRow(Center c, double dx, double dz, double h, String arm,
                               DiagnosticTectonicPropagation.Values m, DiagnosticTectonicPropagation.Values center,
                               DiagnosticTectonicPropagation.Values p, double[] derivative, double[] budgets,
                               double[] taylorErrors, double[] taylorBudgets) {
        double[] a = channels(m), b = channels(center), d = channels(p);
        for (int i = 0; i < a.length; i++) {
            double change = Math.max(Math.abs(a[i] - b[i]), Math.abs(d[i] - b[i]));
            double bound = budgets == null ? Double.NaN : budgets[i] + 4 * ROUND[i];
            double te = taylorErrors == null ? Double.NaN : taylorErrors[i];
            double tb = taylorBudgets == null ? Double.NaN : taylorBudgets[i] + 4 * ROUND[i];
            outputs.get("continuity").println(csv(c.seed, c.id, dx, dz, h, arm, i, CHANNELS[i], a[i], b[i], d[i],
                    budgets == null ? Double.NaN : derivative[i], change, bound, te, tb));
            if (budgets != null) {
                record(Double.isFinite(change) && change <= bound, c.seed, c.id, "C0-local-bound:" + i + ":" + h,
                        "change=" + change + ";bound=" + bound + ";direction=" + dx + ":" + dz);
                if (i >= 2 && i <= 4) record(te <= tb, c.seed, c.id, "gate-input-Taylor:" + i + ":" + h,
                        "error=" + te + ";bound=" + tb);
            }
        }
    }

    private static double[] gateTaylor(DiagnosticTectonicPropagation.Values m, DiagnosticTectonicPropagation.Values c,
                                       DiagnosticTectonicPropagation.Values p) {
        double[] result = new double[6], cv = channels(c);
        for (var v : new DiagnosticTectonicPropagation.Values[]{m, p}) {
            double ds = v.stress() - c.stress(), dd = v.distance() - c.distance();
            double[] actual = channels(v);
            for (int i = 2; i <= 4; i++) {
                double sigma = i == 4 ? 130 : 110;
                double sign = i == 3 ? -1 : 1;
                double a = i == 2 ? 1 : strength(sign * c.stress());
                double da = i == 2 ? 0 : sign * strengthDerivative(sign * c.stress());
                double g = gaussian(c.distance(), sigma), gd = -c.distance() * g / (sigma * sigma);
                result[i] = Math.max(result[i], Math.abs(actual[i] - cv[i] - da * g * ds - a * gd * dd));
            }
        }
        return result;
    }

    private static double[] gateTaylorBudgets(DiagnosticTectonicPropagation.Values m, DiagnosticTectonicPropagation.Values c,
                                              DiagnosticTectonicPropagation.Values p) {
        double[] result = new double[6];
        for (var v : new DiagnosticTectonicPropagation.Values[]{m, p}) {
            double s = Math.abs(v.stress() - c.stress()), d = Math.abs(v.distance() - c.distance());
            for (int i = 2; i <= 4; i++) {
                double sigma = i == 4 ? 130 : 110;
                // |a'|<=1.5/.15, Lip(a')<=6/.15^2; |g'|<=exp(-.5)/sigma; |g''|<=1/sigma^2.
                double error = .5 * d * d / (sigma * sigma);
                if (i != 2) error += 3 * s * s / (.15 * .15) + 10 * Math.exp(-.5) / sigma * s * d;
                result[i] = Math.max(result[i], error);
            }
        }
        return result;
    }

    private double[] derivatives(Center c, DiagnosticTectonicDriver.Snapshot s, TectonicDeformation deformation,
                                 double dx, double dz) throws Exception {
        double ds = Math.abs(s.stressRaw()) < 1 ? s.stressX() * dx + s.stressZ() * dz : 0;
        double dd = s.distanceX() * dx + s.distanceZ() * dz;
        double g110 = gaussian(s.distance(), 110), g130 = gaussian(s.distance(), 130);
        double dg110 = -s.distance() / (110 * 110) * g110 * dd;
        double dg130 = -s.distance() / (130 * 130) * g130 * dd;
        // Offset world derivative is an observation only; independent combination uses original private parts.
        double h = .001;
        double fm = offset(s.stress(), parts.at(deformation, c.x - h * dx, c.z - h * dz));
        double fp = offset(s.stress(), parts.at(deformation, c.x + h * dx, c.z + h * dz));
        Components p = parts.at(deformation, c.x, c.z);
        double offsetS = positiveDerivative(s.stress()) * p.fold
                + (.6 * positiveDerivative(s.stress()) - positiveDerivative(-s.stress())) * p.fault;
        return new double[]{ds, dd, dg110, -strengthDerivative(-s.stress()) * ds * g110 + strength(-s.stress()) * dg110,
                strengthDerivative(s.stress()) * ds * g130 + strength(s.stress()) * dg130,
                offsetS * ds + (fp - fm) / (2 * h)};
    }

    /** Analytic local quotient bound; clamp is 1-Lipschitz even across saturation interfaces. */
    private static double stressLipschitz(DiagnosticTectonicDriver driver, double x, double z, double radius) {
        var q = driver.warp(x, z);
        double rho = WARP_LIP * radius, l = DiagnosticTectonicDriver.L;
        int ax = (int) Math.floor((q.x() - rho) / l) - 2, bx = (int) Math.floor((q.x() + rho) / l) + 3;
        int az = (int) Math.floor((q.z() - rho) / l) - 2, bz = (int) Math.floor((q.z() + rho) / l) + 3;
        double wg = 0, pg = 0, sigma = DiagnosticTectonicDriver.SIGMA;
        for (int i = ax; i <= bx; i++) for (int j = az; j <= bz; j++) {
            var site = driver.site(i, j);
            double distance = Math.hypot(q.x() - site.sx(), q.z() - site.sz());
            double lo = Math.max(0, distance - rho), hi = distance + rho;
            double peak = Math.min(hi, Math.max(lo, sigma));
            double maxG = gaussian(lo, sigma), radial = peak * gaussian(peak, sigma) / (sigma * sigma);
            // Each effective POU site coefficient is a product of 1,H or 1-H; |grad|<=sqrt(2)*1.5/L.
            wg += maxG * Math.sqrt(2) * 1.5 / l + radial;
            double speed = Math.hypot(site.vx(), site.vz());
            pg += maxG * (lo >= DiagnosticTectonicDriver.R ? speed / lo : 1.5 * speed / DiagnosticTectonicDriver.R);
        }
        double denominator = Math.max(Math.exp(-2.89), driver.stressAt(q.x(), q.z()).denominator() - wg * rho);
        return DiagnosticTectonicDriver.GAIN * (2 * wg + pg) / denominator * WARP_LIP;
    }

    /** Fixed coefficients over the whole local ball; subtracting their midpoint uses sum B3'=0. */
    private static double distanceLipschitz(DiagnosticTectonicDriver driver, double x, double z, double radius) {
        double h = DiagnosticTectonicDriver.H, lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        int ax = (int) Math.floor((x - radius) / h) - 1, bx = (int) Math.floor((x + radius) / h) + 2;
        int az = (int) Math.floor((z - radius) / h) - 1, bz = (int) Math.floor((z + radius) / h) + 2;
        for (int i = ax; i <= bx; i++) for (int j = az; j <= bz; j++) {
            double coefficient = driver.rawDistance(i * h, j * h);
            lo = Math.min(lo, coefficient);
            hi = Math.max(hi, coefficient);
        }
        // In each axis sum|B3'|<=1.5, sum B3=1, weights nonnegative.
        return Math.sqrt(2) * 1.5 * (hi - lo) / (2 * h);
    }

    /** Reflection reuses original geometry; only the stress-weighted combination is independently assembled. */
    private static final class Parts {
        private final Method mask, fold, fault;
        private final Bounds bounds;

        private Parts() throws Exception {
            mask = method("segmentMask", double.class, double.class);
            fold = method("foldOffset", double.class, double.class, double.class);
            fault = method("faultOffsetUnit", double.class, double.class, double.class);
            bounds = bounds();
        }

        private Components at(TectonicDeformation deformation, double x, double z) throws Exception {
            double m = (double) mask.invoke(deformation, x, z);
            return new Components(m, (double) fold.invoke(deformation, x, z, m),
                    (double) fault.invoke(deformation, x, z, m));
        }

        private static Method method(String name, Class<?>... types) throws Exception {
            Method result = TectonicDeformation.class.getDeclaredMethod(name, types);
            result.setAccessible(true);
            return result;
        }

        private static double constant(String name) throws Exception {
            Field field = TectonicDeformation.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getDouble(null);
        }

        private static Bounds bounds() throws Exception {
            // Catmull-Rom: sum|weights|<=1.25, sum|derivative weights|<=3.
            // Thus |noise|<=1.25^2 and |grad noise|<=sqrt(2)*3*1.25 per unit lattice.
            double n = 1.25 * 1.25, ng = Math.sqrt(2) * 3 * 1.25;
            double maskMax = .625 + .375 * n, maskLip = .375 * ng / constant("MASK_SCALE");
            double beltLip = 1.5 / .40 * ng / constant("BELT_SCALE");
            double r = constant("FOLD_RIDGE_ROUND"), norm = constant("FOLD_RIDGE_NORM");
            double waveMax = Math.max(1, Math.abs(1 - 2 * (Math.sqrt(n * n + r * r) - r) / norm));
            double waveLip = 2 / norm * ng * (.7 + .3 / 2.1) / constant("FOLD_WAVELENGTH");
            double foldMax = constant("FOLD_AMP") * waveMax * maskMax;
            double foldLip = constant("FOLD_AMP") * (waveLip * maskMax
                    + waveMax * (beltLip * maskMax + maskLip));
            double mixMax = (n + 1) / 2;
            double scarpLip = 1.5 / (2 * constant("SCARP_HALF_WIDTH")) * ng
                    * constant("FAULT_BLOCK_FREQ") / constant("FAULT_SPACING");
            double mixLip = ng / (2 * constant("FAULT_SEGMENT")) + scarpLip;
            double faultMax = constant("FAULT_AMP") * mixMax * maskMax;
            double faultLip = constant("FAULT_AMP") * (mixLip * maskMax
                    + mixMax * (beltLip * maskMax + maskLip));
            return new Bounds(foldMax, faultMax, foldLip, faultLip);
        }
    }
}
