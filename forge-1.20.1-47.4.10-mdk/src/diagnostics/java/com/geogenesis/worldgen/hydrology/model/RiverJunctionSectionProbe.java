package com.geogenesis.worldgen.hydrology.model;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.End;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.River;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.RiverLink;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Shaped;
import com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider.SegmentIndex;

/** Synthetic integration, not production terrain/render acceptance. No Gradle or config writes.
 * Forks explicit OFF/ON JVMs. The real builder/chainEdges/projection index are exercised;
 * reflection substitutes fixed half/Hc after null-pipe construction and rebuilds the index.
 * Translating the geometry lets integer nearest(0,0) test arbitrary epsilon positions.
 * Any exception/assertion/child timeout exits nonzero. */
public final class RiverJunctionSectionProbe {
    private static int checks;
    private static boolean on;

    private RiverJunctionSectionProbe() { }

    public static void main(String[] args) {
        try {
            if (args.length == 0) { fork(); return; }
            on = Boolean.parseBoolean(args[0]);
            require(Boolean.toString(on).equals(System.getProperty("geogenesis.riverCrossSection")),
                    "explicit switch required");
            require("0".equals(System.getProperty("geogenesis.bankJitter")), "explicit jitter=0 required");
            require("0".equals(System.getProperty("geogenesis.sewGap")), "explicit sewGap=0 required");
            require(ModelHydrologyProvider.RIVER_CROSS_SECTION == on, "switch initialized");
            continuity();
            isolation();
            connectedGap();
            surfacesAndShallows();
            directEdgeIsolation();
            patchBoundary();
            invariants();
            System.out.printf("RiverJunctionSectionProbe ON=%s PASS=%d FAIL=0%n", on, checks);
        } catch (Throwable ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    private static void fork() throws Exception {
        String cp = String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(p -> Path.of(p).toAbsolutePath().toString()).toList());
        for (boolean enabled : new boolean[]{false, true}) {
            Path work = Files.createTempDirectory("geogenesis-junction-");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dgeogenesis.riverCrossSection=" + enabled, "-Dgeogenesis.bankJitter=0",
                    "-Dgeogenesis.sewGap=0", "-cp", cp, RiverJunctionSectionProbe.class.getName(),
                    Boolean.toString(enabled)).directory(work.toFile()).inheritIO().start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) {
                child.destroyForcibly().waitFor();
                throw new AssertionError("child timeout");
            }
            require(child.exitValue() == 0, "child exit=0 ON=" + enabled);
        }
    }

    private record Fixture(SegmentIndex index, List<Shaped> polys, List<River> rivers) { }
    private record Sample(double depth, double half, double dist, double surface) { }

    /** T: tributary x=0,z=[0,80], trunk z=0,x=[-80,80], connected at (0,0). */
    private static Fixture fixture(double x, double z, boolean connected, double gap) throws Exception {
        List<Shaped> shapes = List.of(new Shaped(new double[]{-x, -x}, new double[]{80-z, gap-z}),
                new Shaped(new double[]{-80-x, 80-x}, new double[]{-z, -z}));
        List<River> rivers = List.of(
                new River(new int[]{0}, new int[]{1, 0}, new int[]{0, 0}, End.JOIN, false, 100,
                        connected ? 1 : -1, connected ? 0 : -1,
                        connected ? RiverLink.CONNECTED : RiverLink.TERMINAL),
                new River(new int[]{1}, new int[]{0, 2}, new int[]{1, 1}, End.DEAD_END, false, 100,
                        -1, -1, RiverLink.TERMINAL));
        SegmentIndex index = SegmentIndex.buildFromNodes(shapes, null, 0, 0, 100, 1,
                null, rivers, Map.of());
        double[] seg = (double[]) field(index, "seg");
        seg[4] = seg[5] = 2; seg[12] = seg[13] = 6;
        if (on) seg[6] = seg[7] = seg[14] = seg[15] = 4;
        Fixture fixture = new Fixture(index, shapes, rivers);
        reindex(fixture);
        return fixture;
    }

    private static void reindex(Fixture f) throws Exception {
        Method m = SegmentIndex.class.getDeclaredMethod("indexJunctionSections", List.class, List.class, List.class);
        m.setAccessible(true);
        m.invoke(f.index(), f.polys(), List.of(new double[]{2, 2}, new double[]{6, 6}), f.rivers());
    }

    private static Sample sample(SegmentIndex index) throws Exception {
        Object h = index.nearest(0, 0);
        if (h == null) return null;
        return new Sample(component(h, "depth"), component(h, "half"), component(h, "dist"),
                component(h, "surface"));
    }

    private static double depth(double x, double z, boolean connected) throws Exception {
        return sample(fixture(x, z, connected, 0).index()).depth();
    }

    private static void continuity() throws Exception {
        double previous = Double.POSITIVE_INFINITY;
        for (double eps : new double[]{1e-2, 1e-4, 1e-6}) {
            double a = depth(1-eps, 1, true), b = depth(1+eps, 1, true);
            double diff = Math.abs(a-b);
            require(diff <= previous && diff < 10*eps || !on && diff == 0,
                    "winner switch tends to C0 eps=" + eps + " diff=" + diff);
            previous = diff;
            double c = depth(2-eps, 3, true), d = depth(2+eps, 3, true);
            require(Math.abs(c-d) < 10*eps, "tributary r support tends to zero eps=" + eps);
            System.out.printf("EPS ON=%s eps=%g winnerDiff=%.12g supportDiff=%.12g%n",
                    on, eps, diff, Math.abs(c-d));
        }
        near(depth(1, 1, true), on ? RiverCrossSection.targetDepth(4, 1, 6) : 1,
                "core H=1+max(extra_up,extra_down)");
        if (on) require(Math.abs(depth(1-1e-6, 1, false)-depth(1+1e-6, 1, false)) > 1,
                "unconnected control really has old winner discontinuity");
    }

    private static void isolation() throws Exception {
        Fixture disconnected = fixture(1, 1, false, 0);
        require(((List<?>) field(disconnected.index(), "junctionSections")).isEmpty(), "no spatial joins");
        near(sample(disconnected.index()).depth(), on ? RiverCrossSection.targetDepth(4, 1, 2) : 1,
                "unconnected nearest unchanged");
        Fixture gapped = fixture(1, 1, true, 0.25);
        List<?> gapJoins = (List<?>) field(gapped.index(), "junctionSections");
        require(gapJoins.size() == (on ? 1 : 0), "CONNECTED with .25 shaped gap retains patch");
        near(sample(gapped.index()).depth(), on ? RiverCrossSection.targetDepth(4, 1, 6) : 1,
                "CONNECTED .25 gap mixes actual up/down sections");
        if (on) near(component(gapJoins.get(0), "gap"), .25, "nonzero measured gap recorded");
        Fixture connected = fixture(1, 1, true, 0);
        List<?> joins = (List<?>) field(connected.index(), "junctionSections");
        require(joins.size() == (on ? 1 : 0), "ON-only direct chain edge");
        if (on) {
            near(component(joins.get(0), "gap"), 0, "projection gap evidence");
            near(component(joins.get(0), "x"), -1, "world join x not receiver head");
            near(component(joins.get(0), "receiverX"), -1, "actual receiver internal projection");
        }
        // A third nearby poly is NOT in either direct join; it must not influence this patch.
        Fixture f = fixture(1, 1, true, 0);
        int[] ids = (int[]) field(f.index(), "segmentPoly");
        require(ids.length == (on ? 2 : 0), "OFF skips segment-poly allocation/mapping");
        if (on) {
            require(ids[0] == 0 && ids[1] == 1, "each segment carries its poly id");
            ids[1] = 2;
        }
        near(sample(f.index()).depth(), on ? RiverCrossSection.targetDepth(4, 1, 2) : 1,
                "non-direct poly excluded, no component expansion");
    }

    /** Topology, not exact shape contact, admits a pair; gap also enlarges core and exit radius. */
    private static void connectedGap() throws Exception {
        for (double gap : new double[]{.25, .4667}) {
            for (double eps : new double[]{1e-2, 1e-4, 1e-6}) {
                double a = sample(fixture(1-eps, 1, true, gap).index()).depth();
                double b = sample(fixture(1+eps, 1, true, gap).index()).depth();
                require(Math.abs(a-b) < 10*eps, "gapped CONNECTED core C0 gap=" + gap);
            }
            double core = 12+gap, radius = 22+gap;
            double shared = on ? RiverCrossSection.targetDepth(4, 1.5, 6) : 1;
            // Halfway through the added core: must be full strength, not the old fade.
            double distance = 12+gap*.5;
            near(returningDepth(gap+Math.sqrt(distance*distance-1), gap), shared,
                    "measured gap extends full core gap=" + gap);
            for (double eps : new double[]{1e-2, 1e-4, 1e-6}) {
                double inside = returningDepth(gap+Math.sqrt((radius-eps)*(radius-eps)-1), gap);
                double outside = returningDepth(gap+Math.sqrt((radius+eps)*(radius+eps)-1), gap);
                require(Math.abs(inside-outside) < eps*eps, "gap-adjusted patch correction exits smoothly");
                near(outside, on ? RiverCrossSection.targetDepth(4, 1, 2) : 1,
                        "gap-adjusted outside exact original");
            }
            require(core < radius, "gap preserves positive fade span");
        }
    }

    private static void surfacesAndShallows() throws Exception {
        for (double[] levels : new double[][]{{70, 70}, {70, 71}, {70, Double.NaN},
                {Double.NaN, 70}, {Double.NaN, Double.NaN}, {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}}) {
            Fixture f = fixture(1, 1, true, 0);
            double[] surfaces = (double[]) field(f.index(), "surface");
            surfaces[0] = levels[0]; surfaces[1] = levels[1];
            boolean compatible = levels[0] == 70 && levels[1] == 70
                    || Double.isNaN(levels[0]) && Double.isNaN(levels[1]);
            near(sample(f.index()).depth(), !on ? 1 : RiverCrossSection.targetDepth(4, 1, compatible ? 6 : 2),
                    "water compatibility " + Arrays.toString(levels));
        }
        if (!on) return;
        for (double hc : new double[]{0, 0.6, 0.999}) {
            Fixture f = fixture(1, 1, true, 0);
            double[] seg = (double[]) field(f.index(), "seg");
            seg[6] = seg[7] = hc;
            near(sample(f.index()).depth(), hc, "winner shallow/dry preserved " + hc);
        }
    }

    /** A->B->C is one component, but C returning near A's join must not deepen A. */
    private static void directEdgeIsolation() throws Exception {
        List<Shaped> polys = List.of(
                new Shaped(new double[]{-1, -1}, new double[]{78, -2}),
                new Shaped(new double[]{-1, 79}, new double[]{-2, -2}),
                new Shaped(new double[]{79, 79, -81}, new double[]{-2, -1, -1}));
        List<River> rivers = List.of(
                new River(new int[]{0}, new int[]{1, 0}, new int[]{0, 0}, End.JOIN, false, 100,
                        1, 0, RiverLink.CONNECTED),
                new River(new int[]{1}, new int[]{0, 2}, new int[]{1, 1}, End.JOIN, false, 100,
                        2, 0, RiverLink.CONNECTED),
                new River(new int[]{2}, new int[]{2, 3}, new int[]{2, 2}, End.DEAD_END, false, 100,
                        -1, -1, RiverLink.TERMINAL));
        SegmentIndex index = SegmentIndex.buildFromNodes(polys, null, 0, 0, 100, 1,
                null, rivers, Map.of());
        double[] seg = (double[]) field(index, "seg");
        for (int si = 0; si < seg.length/8; si++) {
            int o = si*8;
            seg[o+4] = seg[o+5] = si < 2 ? 2 : 6;
            seg[o+6] = seg[o+7] = on && si >= 2 ? 4 : 1;
        }
        Method m = SegmentIndex.class.getDeclaredMethod("indexJunctionSections", List.class, List.class, List.class);
        m.setAccessible(true);
        m.invoke(index, polys, List.of(new double[]{2, 2}, new double[]{2, 2},
                new double[]{6, 6, 6}), rivers);
        require(((List<?>) field(index, "junctionSections")).size() == (on ? 2 : 0),
                "two direct joins, not a component patch");
        Sample h = sample(index);
        near(h.dist(), 1, "A winner distance retained beside returning C");
        near(h.half(), 2, "A winner half retained beside returning C");
        near(h.depth(), 1, "transitive C cannot deepen A at A-B join");
        // A bridge-like winner with no poly identity is never a patch participant.
        if (on) ((int[]) field(index, "segmentPoly"))[0] = -1;
        near(sample(index).depth(), 1, "unattributed sew winner unchanged");
        checkPolyIndex(index, polys.size());
    }

    private static void checkPolyIndex(SegmentIndex index, int count) throws Exception {
        List<?> all = (List<?>) field(index, "junctionSections");
        List<?> byPoly = (List<?>) field(index, "junctionsByPoly");
        require(byPoly.size() == (on ? count : 0), "OFF skips byPoly construction");
        if (!on) return;
        for (int p = 0; p < count; p++) {
            List<?> local = (List<?>) byPoly.get(p);
            int k = 0;
            for (Object join : all) {
                if (component(join, "up") != p && component(join, "down") != p) continue;
                require(k < local.size() && local.get(k++) == join, "byPoly preserves original reference/order");
            }
            require(k == local.size(), "byPoly contains only direct incident joins");
        }
    }

    private static void patchBoundary() throws Exception {
        // x=.1 on tributary: trunk's EXTRA is identically zero beyond z=6.
        // Whole-H max would wrongly contribute 1 even outside that support for shallow cases.
        for (double eps : new double[]{1e-2, 1e-4, 1e-6}) {
            double inside = depth(0.1, 22-eps, true), outside = depth(0.1, 22+eps, true);
            require(Math.abs(inside-outside) < 10*eps, "patch exit C0 eps=" + eps);
            near(outside, depth(0.1, 22+eps, false), "outside radius exact old depth");
            require(Math.abs(depth(.1, 12-eps, true)-depth(.1, 12+eps, true)) < 10*eps,
                    "full-strength core to fade no jump");
        }
        // A real returning trunk remains near the narrow winner at the patch radius.
        for (double radius : new double[]{12, 16, 20, 22, 23}) {
            double z = Math.sqrt(radius*radius-1);
            double base = on ? RiverCrossSection.targetDepth(4, 1, 2) : 1;
            double shared = on ? RiverCrossSection.targetDepth(4, 1.5, 6) : 1;
            double expected = !on ? 1 : base + Math.max(0,shared-base)
                    * (1-RiverWidthProfile.smoothstep01((radius-12)/10));
            near(returningDepth(z), expected, "actual nonzero fade radius=" + radius);
        }
        for (double eps : new double[]{1e-2, 1e-4, 1e-6}) {
            double a = returningDepth(Math.sqrt((22-eps)*(22-eps)-1));
            double b = returningDepth(Math.sqrt((22+eps)*(22+eps)-1));
            require(Math.abs(a-b) < eps*eps, "nonzero correction exits smoothly eps=" + eps);
        }
    }

    private static double returningDepth(double z) throws Exception {
        return returningDepth(z, 0);
    }

    private static double returningDepth(double z, double gap) throws Exception {
        Fixture base = fixture(1, z, true, gap);
        List<Shaped> polys = List.of(base.polys().get(0), new Shaped(
                new double[]{-1, -41, -41, 1.5, 1.5}, new double[]{-z, -z, 40-z, 40-z, -z}));
        SegmentIndex index = SegmentIndex.buildFromNodes(polys, null, 0, 0, 100, 1,
                null, base.rivers(), Map.of());
        double[] seg = (double[]) field(index, "seg");
        for (int si = 0; si < seg.length/8; si++) {
            int o = si*8;
            seg[o+4] = seg[o+5] = si == 0 ? 2 : 6;
            if (on) seg[o+6] = seg[o+7] = 4;
        }
        Method m = SegmentIndex.class.getDeclaredMethod("indexJunctionSections", List.class, List.class, List.class);
        m.setAccessible(true);
        m.invoke(index, polys, List.of(new double[]{2, 2}, new double[]{6, 6, 6, 6, 6}), base.rivers());
        return sample(index).depth();
    }

    private static void invariants() throws Exception {
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 24; z++) {
            Fixture f = fixture(x, z, true, 0);
            Sample before = sample(f.index());
            checkPolyIndex(f.index(), 2);
            if (on) {
                Field lookup = SegmentIndex.class.getDeclaredField("junctionsByPoly");
                lookup.setAccessible(true);
                Object saved = lookup.get(f.index());
                List<?> all = (List<?>) field(f.index(), "junctionSections");
                lookup.set(f.index(), List.of(all, all));
                require(java.util.Objects.equals(before, sample(f.index())), "byPoly query bitwise equals full scan");
                lookup.set(f.index(), saved);
            }
            Field field = SegmentIndex.class.getDeclaredField("junctionSections");
            field.setAccessible(true); field.set(f.index(), List.of());
            Sample after = sample(f.index());
            require((before == null) == (after == null), "covered unchanged");
            if (before == null) continue;
            require(before.half() == after.half() && before.dist() == after.dist(), "winner half/dist unchanged");
            require(Double.doubleToLongBits(before.surface()) == Double.doubleToLongBits(after.surface()),
                    "winner surface unchanged");
            if (!on) near(before.depth(), after.depth(), "OFF exact old depth");
            else require(before.depth() >= after.depth(), "patch only additional depth");
        }
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }

    private static double component(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name); m.setAccessible(true);
        return ((Number) m.invoke(o)).doubleValue();
    }

    private static void near(double actual, double expected, String label) {
        require(Double.isFinite(actual) && Math.abs(actual-expected) < 1e-9,
                label + " actual=" + actual + " expected=" + expected);
    }

    private static void require(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
}
