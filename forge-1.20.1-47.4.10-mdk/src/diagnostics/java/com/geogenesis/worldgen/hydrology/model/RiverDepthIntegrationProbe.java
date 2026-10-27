package com.geogenesis.worldgen.hydrology.model;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.End;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Shaped;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Trace;
import com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider.SegmentIndex;

/**
 * Production SegmentIndex.buildFromNodes -> nearest integration, using synthetic geometry.
 * No provider instance, terrain sampling, region solves, columns or Minecraft startup.
 *
 * <p>Run main with no arguments to fork OFF/ON x bankJitter=0/0.25 in separate JVMs.
 * Each child explicitly sets the candidate switch before Provider initialization and uses
 * a temporary working directory (the production builder writes width-counter diagnostics).
 * Direct child invocation requires --case OFF|ON 0|0.25 and matching -D settings;
 * an absent/mismatched property is a FAIL, not an inferred expectation.</p>
 *
 * <p>The null-pipe width/depth baseline is first verified as 0.5/1. Only the isolated
 * nearest fixtures then replace private seg endpoint half/depth values. OFF retains its
 * builder-produced depth=1. The bridge fixture instead exercises the real width pins,
 * depth-profile builder and sew endpoint projection without injecting any segment data.
 * Reflection reads private Hit components and seg; private API drift must fail loudly.</p>
 *
 * <p>Use javac against current main classes and runtime dependencies, then java -cp
 * "isolated classes;main runtimeClasspath" with this main class. No Gradle task required.
 * FAIL/exception/child timeout exits nonzero. This is NOT columns, wet-fill or render acceptance.</p>
 */
public final class RiverDepthIntegrationProbe {
    private static int pass, fail;
    private static final double EPS = 1e-10;
    private static final String SWITCH = "geogenesis.riverCrossSection";

    private RiverDepthIntegrationProbe() { }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                isolatedMatrix();
            } else {
                require(args.length == 3 && args[0].equals("--case"), "usage: --case OFF|ON 0|0.25");
                require(args[1].equals("ON") || args[1].equals("OFF"), "unknown mode");
                boolean on = args[1].equals("ON");
                double jitter = Double.parseDouble(args[2]);
                verifySettings(on, jitter);
                nearestSection(on, jitter);
                shallowAndDryNearest(on);
                bankJitterKeepsCenter(on, jitter);
                bridgeUsesActualEndpoint(on);
                System.out.printf("CASE %s bankJitter=%s PASS=%d FAIL=%d%n", args[1], args[2], pass, fail);
            }
        } catch (Throwable ex) {
            fail++;
            ex.printStackTrace(System.err);
        }
        System.out.printf("RiverDepthIntegrationProbe PASS=%d FAIL=%d (near query only)%n", pass, fail);
        if (fail != 0) System.exit(1);
    }

    private static void isolatedMatrix() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = absoluteClasspath();
        for (String mode : List.of("OFF", "ON")) for (String jitter : List.of("0", "0.25")) {
            Path work = Files.createTempDirectory("geogenesis-depth-integration-");
            List<String> command = new ArrayList<>(List.of(java, "-Dfile.encoding=UTF-8",
                    "-D" + SWITCH + "=" + mode.equals("ON"), "-Dgeogenesis.bankJitter=" + jitter,
                    "-Dgeogenesis.sewGap=3", "-Dgeogenesis.widthSmooth=4",
                    "-Dgeogenesis.widthMonotone=true", "-cp", cp,
                    RiverDepthIntegrationProbe.class.getName(), "--case", mode, jitter));
            System.out.printf("FORK mode=%s jitter=%s work=%s%n", mode, jitter, work);
            Process child = new ProcessBuilder(command).directory(work.toFile()).inheritIO().start();
            if (!child.waitFor(60, TimeUnit.SECONDS)) {
                child.destroyForcibly().waitFor();
                check("child timeout " + mode + "/" + jitter, false);
            } else {
                check("isolated child exit=0 " + mode + "/" + jitter, child.exitValue() == 0);
            }
        }
    }

    private static String absoluteClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(s -> Path.of(s.isEmpty() ? "." : s).toAbsolutePath().toString()).toList());
    }

    private static void verifySettings(boolean on, double jitter) {
        require(Boolean.toString(on).equals(System.getProperty(SWITCH)), "explicit candidate switch required");
        require(jitter == 0 || jitter == 0.25, "matrix jitter must be 0 or 0.25");
        require(System.getProperty("geogenesis.bankJitter") != null, "explicit bankJitter required");
        require(Double.parseDouble(System.getProperty("geogenesis.bankJitter")) == jitter, "jitter mismatch");
        require("3".equals(System.getProperty("geogenesis.sewGap")), "explicit sewGap=3 required");
        check("Provider initialized with requested candidate switch", ModelHydrologyProvider.RIVER_CROSS_SECTION == on);
        near("Provider initialized with requested jitter", jitter, ModelHydrologyProvider.BANK_JITTER);
    }

    /** Exact 40-block horizontal segment. Positive-z jitter expands half then clamps at 6. */
    private static void nearestSection(boolean on, double jitter) throws Exception {
        SegmentIndex index = SegmentIndex.buildFromNodes(List.of(line(-20, 0, 20, 0)));
        double[] seg = segments(index);
        require(seg.length == 8, "single line must produce one segment");
        near("null-pipe nominal h0", 0.5, seg[4]);
        near("null-pipe nominal h1", 0.5, seg[5]);
        near("null-pipe baseline d0", 1, seg[6]);
        near("null-pipe baseline d1", 1, seg[7]);
        seg[4] = seg[5] = 6;
        if (on) seg[6] = seg[7] = 4;
        // Exact symmetric anchors require jitter=0; jitter-on uses only the clamped positive bank.
        int[] offsets = jitter == 0 ? new int[]{0, 3, -3, 6, -6} : new int[]{0, 3, 6};
        for (int z : offsets) {
            Sample hit = hit(index, 0, z);
            near("section dist z=" + z, Math.abs(z), hit.dist());
            near("section effective half z=" + z, 6, hit.half());
            double expected = !on ? 1 : z == 0 ? 4 : Math.abs(z) == 3 ? 2.6875 : 1;
            near("nearest consumes section mode=" + on + " z=" + z, expected, hit.depth());
        }
        check("outside coverage stays absent", index.nearest(0, 7) == null);
        System.out.printf("SECTION on=%s jitter=%s center=%s rHalf=%s edge=%s%n", on, jitter,
                hit(index, 0, 0).depth(), hit(index, 0, 3).depth(), hit(index, 0, 6).depth());
        if (on) {
            seg[6] = 2; seg[7] = 4;
            near("actual projection midpoint Hc interpolation", 3, hit(index, 0, 0).depth());
            near("actual projection quarter Hc interpolation", 2.5, hit(index, -10, 0).depth());
        }
    }

    private static void shallowAndDryNearest(boolean on) throws Exception {
        SegmentIndex index = SegmentIndex.buildFromNodes(List.of(line(-20, 0, 20, 0)));
        double[] seg = segments(index);
        seg[4] = seg[5] = 6;
        for (double hc : new double[]{0.25, 0.4, 0, -0.25}) {
            if (on) seg[6] = seg[7] = hc;
            for (int z : new int[]{0, 3, 6}) {
                Sample sample = hit(index, 0, z);
                double expected = on ? Math.max(0, hc) : 1;
                near("shallow/dry nearest Hc=" + hc + " z=" + z, expected, sample.depth());
                if (on && hc <= 0) check("Hit.depth explicitly marks no water", sample.depth() == 0);
            }
        }
    }

    /** Check only d=0 depth across jitter variants; bank jitter MAY change the lateral depth. */
    private static void bankJitterKeepsCenter(boolean on, double jitter) throws Exception {
        SegmentIndex index = SegmentIndex.buildFromNodes(List.of(line(8, 8, 48, 8)));
        double[] seg = segments(index);
        seg[4] = seg[5] = 3;
        if (on) seg[6] = seg[7] = 2.6607499349512365;
        for (int x : new int[]{16, 32}) {
            Sample sample = hit(index, x, 8);
            near("jitter sample really on centerline x=" + x, 0, sample.dist());
            near("jitter does not recompute Hc from effective width x=" + x,
                    on ? 2.6607499349512365 : 1, sample.depth());
            near("Hit half consumes actual bankHalf x=" + x, SegmentIndex.bankHalf(3, x, 8), sample.half());
            check("jitter effective width change is actually exercised x=" + x,
                    jitter == 0 ? sample.half() == 3 : Math.abs(sample.half() - 3) > 1e-3);
            near("query leaves nominal half unchanged", 3, seg[4]);
            near("query leaves stored center depth unchanged", on ? 2.6607499349512365 : 1, seg[6]);
            System.out.printf("JITTER on=%s jitter=%s x=%d effectiveHalf=%.12f Hc=%.12f%n",
                    on, jitter, x, sample.half(), sample.depth());
        }
    }

    /** Real sew bridge: sender tail (40,24) -> receiver INTERNAL node (42,24), not its head. */
    private static void bridgeUsesActualEndpoint(boolean on) throws Exception {
        int n = 96, pin = 42 * n + 24;
        Shaped sender = line(0, 24, 40, 24);
        Shaped receiver = new Shaped(new double[]{42, 42, 42}, new double[]{4, 24, 44});
        List<Trace> traces = List.of(new Trace(new int[]{24, 40 * n + 24}, End.DEAD_END, false),
                new Trace(new int[]{42 * n + 4, pin, 42 * n + 44}, End.DEAD_END, false));
        SegmentIndex index = SegmentIndex.buildFromNodes(List.of(sender, receiver), null,
                0, 0, n, 1, traces, List.of(), Map.of(pin, 6.0));
        double[] seg = segments(index);
        require(seg.length == 32, "three ordinary segments and one real sew bridge required");
        near("receiver head not the actual bridge endpoint", 1, seg[14]);
        near("receiver internal node gets finalized profile Hc", on ? 4 : 1, seg[15]);
        near("bridge x0", 40, seg[24]); near("bridge z0", 24, seg[25]);
        near("bridge x1", 42, seg[26]); near("bridge z1", 24, seg[27]);
        near("bridge start from sender actual tail", 1, seg[30]);
        near("bridge end from receiver actual internal node, not hardcoded 1", on ? 4 : 1, seg[31]);
        near("bridge nearest midpoint consumes projected endpoint depths", on ? 2.5 : 1,
                hit(index, 41, 24).depth());
        near("bridge nearest sender endpoint", 1, hit(index, 40, 24).depth());
        near("bridge nearest receiver internal endpoint", on ? 4 : 1, hit(index, 42, 24).depth());
        // Exercise the SAME private projection helper with nonconstant rows at an interior t=0.5.
        Method project = SegmentIndex.class.getDeclaredMethod("projectedDepth", List.class, List.class,
                double.class, double.class);
        project.setAccessible(true);
        double projected = (double) project.invoke(null, List.of(sender, receiver),
                List.of(new double[]{1, 2}, new double[]{2, 4, 3}), 42.0, 14.0);
        near("bridge projection uses actual t, not nearest node or head", 3, projected);
        System.out.printf("BRIDGE on=%s endpointDepths=%.6f/%.6f nearestMid=%.6f%n",
                on, seg[30], seg[31], hit(index, 41, 24).depth());
    }

    private static Shaped line(double x0, double z0, double x1, double z1) {
        return new Shaped(new double[]{x0, x1}, new double[]{z0, z1});
    }

    private static double[] segments(SegmentIndex index) throws Exception {
        Field field = SegmentIndex.class.getDeclaredField("seg");
        field.setAccessible(true);
        return (double[]) field.get(index);
    }

    private record Sample(double depth, double half, double dist) { }

    private static Sample hit(SegmentIndex index, int x, int z) throws Exception {
        Object hit = index.nearest(x, z);
        require(hit != null, "expected covered nearest Hit at " + x + "," + z);
        return new Sample(component(hit, "depth"), component(hit, "half"), component(hit, "dist"));
    }

    private static double component(Object record, String name) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return (double) accessor.invoke(record);
    }

    private static void near(String name, double expected, double actual) {
        check(name + " expected=" + expected + " actual=" + actual,
                Double.isFinite(actual) && Math.abs(expected - actual) <= EPS);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }
}
