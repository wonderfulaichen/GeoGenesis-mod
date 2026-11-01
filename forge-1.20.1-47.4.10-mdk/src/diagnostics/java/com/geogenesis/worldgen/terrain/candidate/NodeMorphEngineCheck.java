package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** P2只扫局部节点/权重，不创建UnifiedWorld，不碰海岸。 */
public final class NodeMorphEngineCheck {
    private static int checks;
    private static double endpointMax, seaMax = -1, edgeMax;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        for (long seed : new long[]{9139912035078620160L, -419, 0, 12345}) {
            RecipeMorphCatalog land = new RecipeMorphCatalog(seed);
            for (boolean concept : new boolean[]{false, true}) run(seed, land, concept, args.length > 1);
        }
        signedAmplitude();
        exportProfiles(out);
        String result = "checks=" + checks + " endpointMax=" + endpointMax + " seaMax=" + seaMax
                + " edgeMax=" + edgeMax + " failures=" + failures.size() + "\n";
        Files.writeString(out.resolve("result.txt"), result + String.join("\n", failures));
        System.out.print(result);
        if (!failures.isEmpty()) throw new AssertionError(failures.get(0));
        System.out.println("NODE MORPH PASS");
    }

    private static void run(long seed, RecipeMorphCatalog land, boolean concept, boolean truncate) {
        OceanRecipeCatalog ocean = new OceanRecipeCatalog(seed + OceanRecipeCatalog.SEED_OFFSET, concept);
        MorphRecipe[] recipes = MorphCatalog.world(land, ocean);
        NodeMorphEngine engine = new NodeMorphEngine(recipes, seed);
        RecipeJunctionMorph oldLand = new RecipeJunctionMorph(land, seed);
        Random random = new Random(53);
        for (int p = 0; p < 120; p++) {
            double x = random.nextDouble() * 14000 - 7000, z = random.nextDouble() * 14000 - 7000;
            for (int i = 0; i < 23; i++) {
                double actual = engine.sampleE(x, z, weights(i, i, 0));
                if (truncate) actual = Math.max(actual, 0);
                double[] w = new double[18];
                if (i < 18) w[i] = 1;
                double expected = i < 18 ? Math.max(CombineCandidate.GROUND_E
                        + CombineCandidate.E_SPAN * oldLand.sample(x, z, w), 0) : ocean.height01(i - 18, x, z);
                double error = Math.abs(actual - expected);
                endpointMax = Math.max(endpointMax, error);
                check(error <= 2e-15, "endpoint id=" + i + " seed=" + seed + " error=" + error);
            }
            for (int a = 18; a < 23; a++) for (int b = a + 1; b < 23; b++) {
                for (int k = 0; k <= 40; k++) {
                    double t = k / 40.0;
                    double e = engine.sampleE(x, z, weights(a, b, t));
                    seaMax = Math.max(seaMax, e);
                    check(Double.isFinite(e) && e <= 0 && e >= -1, "sea range a=" + a + " b=" + b + " t=" + t + " e=" + e);
                }
            }
            double[] seaWeights = new double[23];
            double total = 0;
            for (int i = 18; i < 23; i++) total += seaWeights[i] = random.nextDouble();
            for (int i = 18; i < 23; i++) seaWeights[i] /= total;
            double sea = engine.sampleE(x, z, seaWeights);
            check(Double.isFinite(sea) && sea < 0 && sea >= -1, "five-way sea range");
            mixed(engine, x, z, p % 18, 18 + p % 5);
            double[] merged = engine.merge(new int[]{22, 17, 22}, new double[]{.125, .5, .375});
            double[] reversed = engine.merge(new int[]{22, 22, 17}, new double[]{.375, .125, .5});
            check(java.util.Arrays.equals(merged, reversed), "duplicate/order identity");
            check(Double.doubleToLongBits(engine.sampleE(x, z, merged))
                    == Double.doubleToLongBits(engine.sampleE(x, z, reversed)), "reorder output");
            double[] pair = weights(18, 21, .4);
            double base = engine.sampleE(x, z, pair);
            double previous = Double.POSITIVE_INFINITY;
            for (double epsilon : new double[]{1e-3, 1e-4, 1e-5, 1e-6}) {
                double[] triple = pair.clone();
                for (int i = 0; i < triple.length; i++) triple[i] *= 1 - epsilon;
                triple[22] += epsilon;
                double error = Math.abs(engine.sampleE(x, z, triple) - base);
                check(error <= previous * .12, "three-way edge convergence " + error + " previous=" + previous);
                previous = error;
            }
            edgeMax = Math.max(edgeMax, previous);
        }
        rejects(engine);
    }

    private static void mixed(NodeMorphEngine engine, double x, double z, int a, int b) {
        double first = engine.sampleE(x, z, weights(a, b, 0));
        double last = engine.sampleE(x, z, weights(a, b, 1));
        check(first >= 0 && last < 0, "mixed endpoints/sign");
        for (int k = 0; k <= 100; k++) {
            double t = k / 100.0;
            double e = engine.sampleE(x, z, weights(a, b, t));
            check(Double.isFinite(e), "mixed finite id=" + a);
            if (k > 0 && k < 100) {
                double e1 = engine.sampleE(x, z, weights(a, b, t - 1e-7));
                double e2 = engine.sampleE(x, z, weights(a, b, t + 1e-7));
                check(Math.abs(e2 - e1) < 1e-4, "mixed local jump id=" + a);
            }
        }
        double left = engine.sampleE(x, z, weights(a, b, 1e-9));
        check(Math.abs(left - first) <= 2e-6, "land endpoint limit id=" + a + " delta=" + (left - first));
        double right = engine.sampleE(x, z, weights(a, b, 1 - 1e-9));
        check(Math.abs(right - last) <= 2e-6, "ocean endpoint limit id=" + a + " delta=" + (right - last));
    }

    private static double[] weights(int a, int b, double t) {
        double[] w = new double[23];
        w[a] += 1 - t;
        w[b] += t;
        return w;
    }

    private static void rejects(NodeMorphEngine engine) {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1}) {
            double[] w = new double[23];
            w[0] = bad;
            boolean rejected = false;
            try { engine.sampleE(0, 0, w); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "invalid weight not rejected");
        }
    }

    private static void signedAmplitude() {
        MorphRecipe positive = constant(.7, .16, -.36);
        MorphRecipe negative = constant(.7, -.19, -.36);
        NodeMorphEngine engine = new NodeMorphEngine(new MorphRecipe[]{positive, negative}, 0);
        double zero = .16 / (.16 + .19);
        for (double t : new double[]{0, zero - 1e-8, zero, zero + 1e-8, 1}) {
            double actual = engine.sampleE(0, 0, new double[]{1 - t, t});
            double expected = -.36 + .7 * (.16 + (-.19 - .16) * t);
            check(Math.abs(actual - expected) < 1e-15, "signed amplitude through zero");
        }
        check(engine.sampleE(0, 0, new double[]{0, 1}) < -.36, "canyon polarity");
    }

    private static MorphRecipe constant(double raw, double amp, double bias) {
        return new MorphRecipe() {
            public double raw(double x, double z) { return raw; }
            public double amplitude(double x, double z) { return amp; }
            public double bias(double x, double z) { return bias; }
        };
    }

    private static void exportProfiles(Path out) throws Exception {
        long seed = 9139912035078620160L;
        RecipeMorphCatalog land = new RecipeMorphCatalog(seed);
        OceanRecipeCatalog ocean = new OceanRecipeCatalog(seed + OceanRecipeCatalog.SEED_OFFSET, true);
        NodeMorphEngine engine = new NodeMorphEngine(MorphCatalog.world(land, ocean), seed);
        List<String> csv = new ArrayList<>();
        csv.add("pair,t,e,node_finished_lerp");
        for (int[] pair : new int[][]{{20,22},{19,21},{3,18},{15,22},{17,19}}) {
            double left = engine.sampleE(-2000, -2175, weights(pair[0], pair[1], 0));
            double right = engine.sampleE(-2000, -2175, weights(pair[0], pair[1], 1));
            for (int k = 0; k <= 240; k++) {
                double t = k / 240.0;
                csv.add(pair[0] + "-" + pair[1] + "," + t + ","
                        + engine.sampleE(-2000, -2175, weights(pair[0], pair[1], t)) + ","
                        + (left + (right - left) * t));
            }
        }
        Files.write(out.resolve("local-weight-profiles.csv"), csv);
    }

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok && failures.size() < 30) failures.add(label);
    }
}
