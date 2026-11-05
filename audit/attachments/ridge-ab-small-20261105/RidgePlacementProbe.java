package com.geogenesis.worldgen.terrain.morph;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.erosion.RidgeValleyErosion;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.DoubleBinaryOperator;

/** Isolated placement experiment. A is a bias-shell proxy, NOT a validated recipe operator. */
public final class RidgePlacementProbe {
    private static final long SEED = 9139912035078620160L;
    private final MorphLandField land;
    private final MorphKernel kernel;
    private final MorphKernel recipeKernel;
    private final RidgeValleyErosion.RidgeConfig config;
    private final HeightCurve curve;
    private final MorphOceanAssembler[] oceans;
    private final DoubleBinaryOperator[] recipeFields;
    private final double[][] onehot;
    private final double hs;
    private double queryX, queryZ;
    private double[] queryValues;

    private RidgePlacementProbe(TerrainParams p, RidgeValleyErosion.RidgeConfig config) {
        MorphCatalog catalog = new MorphCatalog(SEED);
        MorphAssembly assembly = MorphAssembly.of(SEED);
        land = new MorphLandField(catalog, assembly, 1200f);
        kernel = new MorphKernel(MorphLandRecipe.all(catalog), SEED);
        curve = new HeightCurve(p, -64, 320);
        this.config = config;
        hs = p.horizontalScale();
        ContinentField continent = new ContinentField(p);
        continent.seed(SEED);
        onehot = new double[18][18];
        recipeFields = new DoubleBinaryOperator[18];
        for (int i = 0; i < 18; i++) {
            onehot[i][i] = 1;
            final int id = i;
            recipeFields[i] = (x, z) -> toE(kernel.sampleLand(x, z, onehot[id]));
        }
        MorphRecipe[] wrapped = MorphLandRecipe.all(catalog);
        for (int i = 0; i < wrapped.length; i++) {
            final MorphRecipe original = wrapped[i];
            final DoubleBinaryOperator input = recipeFields[i];
            wrapped[i] = new MorphRecipe() {
                @Override public double raw(double x, double z) { return original.raw(x, z); }
                @Override public double amplitude(double x, double z) { return original.amplitude(x, z); }
                @Override public double bias(double x, double z) {
                    // Recipe-local output correction is a shell offset, before the kernel's soft clamp.
                    return original.bias(x, z) + delta(input, x, z) / MorphScale.E_SPAN;
                }
                @Override public Role role() { return original.role(); }
                @Override public boolean clamped() { return original.clamped(); }
            };
        }
        recipeKernel = new MorphKernel(wrapped, SEED);
        oceans = new MorphOceanAssembler[3];
        for (int i = 0; i < 3; i++) {
            final int arm = i;
            oceans[i] = new MorphOceanAssembler((x, z) -> query(x, z)[arm], continent::sample, SEED + 3)
                    .enableCoastalDetail(SEED);
        }
    }

    private static double toE(double height) {
        return Math.max(MorphScale.GROUND_E + height * MorphScale.E_SPAN, 0);
    }

    /** Cache only one immutable result. No earlier query participates in numerical decisions. */
    private double[] query(double x, double z) {
        if (queryValues != null && x == queryX && z == queryZ) return queryValues;
        MorphLandField.Sample s = land.sample(x, z);
        double base = s.e();
        double region = recipeKernel.sampleLand(x, z, s.weight());
        double select = s.mask();
        double coverage = select < land.stats().blendLow() ? Double.NaN : land.oldCoverageHeight(x, z);
        if (select >= land.stats().blendLow()) {
            DoubleBinaryOperator field = (a, b) -> toE(land.oldCoverageHeight(a, b));
            coverage += delta(field, x, z) / MorphScale.E_SPAN;
        }
        double a = toE(land.overlay(region, select, coverage));
        double b = base + delta(land::e, x, z);
        queryX = x; queryZ = z;
        return queryValues = new double[]{base, a, b};
    }

    /** Existing grid adapter uses float samples and +/-2wu; preserve its gradient arithmetic. */
    private float delta(DoubleBinaryOperator f, double x, double z) {
        float center = (float) f.applyAsDouble(x, z);
        float left = (float) f.applyAsDouble(x - 2, z), right = (float) f.applyAsDouble(x + 2, z);
        float back = (float) f.applyAsDouble(x, z - 2), front = (float) f.applyAsDouble(x, z + 2);
        float denominator = 4f;
        if (hs > .01 && hs != 1) denominator *= (float) hs;
        return RidgeValleyErosion.evaluatePoint((float) x, (float) z, center,
                (right - left) / denominator, (front - back) / denominator, 0f, config);
    }

    private void render(Path out, int size, double step, double ox, double oz) throws Exception {
        Files.createDirectories(out);
        DataOutputStream[] streams = new DataOutputStream[6];
        String[] names = {"control", "recipe", "post", "pre-control", "pre-recipe", "pre-post"};
        StringBuilder weights = new StringBuilder("xWu,zWu,active,dominant,alpha,weights\n");
        long start = System.nanoTime();
        try {
            for (int k = 0; k < streams.length; k++) streams[k] = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(out.resolve(names[k] + ".f64be"))));
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    double x = ox + i * step, z = oz + j * step;
                    double[] pre = query(x, z).clone();
                    for (int k = 0; k < 3; k++) streams[k + 3].writeDouble(pre[k]);
                    if (i % 16 == 0 && j % 16 == 0) {
                        var sample = land.sample(x, z);
                        weights.append(x).append(',').append(z).append(',').append(sample.activeEntries())
                                .append(',').append(sample.dominantEntry()).append(',').append(sample.alpha())
                                .append(',').append('"').append(Arrays.toString(sample.weight())).append('"').append('\n');
                    }
                    for (int k = 0; k < 3; k++) {
                        double y = curve.heightFromE(oceans[k].e(x, z));
                        require(Double.isFinite(y), "nonfinite arm " + k);
                        streams[k].writeDouble(y);
                    }
                }
                if (j % 200 == 0) System.out.println(out.getFileName() + " row=" + j);
            }
        } finally { for (var s : streams) if (s != null) s.close(); }
        Files.writeString(out.resolve("weights-grid.csv"), weights.toString());
        System.out.println(out.getFileName() + " elapsedMs=" + (System.nanoTime() - start) / 1e6);
        for (String name : names) System.out.println(name + "Sha=" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(out.resolve(name + ".f64be")))));
        Files.writeString(out.resolve("window.json"), String.format(Locale.ROOT,
                "{\"size\":%d,\"stepWu\":%.3f,\"originWu\":[%.3f,%.3f],\"hs\":%.1f}\n", size, step, ox, oz, hs));
    }

    private void checks() {
        float[][] grid = {{.2f, .25f, .3f}, {.31f, .36f, .43f}, {.4f, .45f, .5f}};
        float a = RidgeValleyErosion.evaluateCell(grid, 1, 1, 3, 2, -2, -2, 0, config);
        float b = RidgeValleyErosion.evaluatePoint(0, 0, grid[1][1],
                (grid[1][2] - grid[1][0]) / (4f * (float) hs),
                (grid[2][1] - grid[0][1]) / (4f * (float) hs), 0, config);
        require(Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b), "point/grid formula mismatch");
        // LOW entry has no mountain domain warp: the onehot result must be its filtered shell.
        DoubleBinaryOperator plain = recipeFields[0];
        double directPlain = plain.applyAsDouble(57.5, 89.5) + delta(plain, 57.5, 89.5);
        double one = toE(recipeKernel.sampleLand(57.5, 89.5, onehot[0]));
        require(Math.abs(directPlain - one) < 1e-12, "pure recipe endpoint");
        for (double[] p : new double[][]{{-1024, -22.5}, {1024, 211.5}, {1657.5, 2289.5}}) {
            double[] before = query(p[0], p[1]).clone();
            query(-33.5, 44.5);
            require(Arrays.equals(before, query(p[0], p[1])), "query-order drift");
        }
        System.out.println("POINT/GRID + ENDPOINT + QUERY ORDER PASS");
    }

    public static void main(String[] args) throws Exception {
        try (CommentedFileConfig cfg = CommentedFileConfig.of(Path.of(args[0]), TomlFormat.instance())) {
            cfg.load(); require(GeoGenesisConfig.SPEC.isCorrect(cfg), "config invalid");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
            var ridge = com.geogenesis.config.RidgeConfigAdapter.fromConfig(GeoGenesisConfig.INSTANCE);
            ridge.horizontalScale = (float) params.horizontalScale();
            RidgePlacementProbe probe = new RidgePlacementProbe(params, ridge);
            probe.checks();
            Path out = Path.of(args[1]);
            Files.createDirectories(out);
            Files.copy(Path.of(args[0]), out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
            String manifest = "seed=" + SEED + "\nhs=" + params.horizontalScale()
                    + "\nstrength=" + ridge.strength + "\nscale=" + ridge.cellWorldSize
                    + "\nstripeFreq=" + ridge.stripeFreq + "\noctaves=" + ridge.octaves
                    + "\ngullyWeight=" + ridge.gullyWeight + "\ndetail=" + ridge.detail
                    + "\nseedFilter=" + ridge.seed + "\nrounding=" + Arrays.toString(ridge.rounding)
                    + "\nonset=" + Arrays.toString(ridge.onset) + "\nsampling=pointwise float, central +/-2wu"
                    + "\nA=recipe-local filter correction in recipe bias before existing node-shell morph and clamp; coverage filtered separately"
                    + "\nB=filter covered continuous land; no coast in filter input"
                    + "\nBoth retain existing MorphNoise.Erosion; no replacement; no particle or hydrology"
                    + "\nJVM properties=file.encoding=UTF-8; no geogenesis -D flags\n";
            Files.writeString(out.resolve("provenance.txt"), manifest);
            if (args.length > 2 && args[2].equals("small")) {
                probe.render(out.resolve(args[3]), Integer.parseInt(args[4]), Double.parseDouble(args[5]),
                        Double.parseDouble(args[6]), Double.parseDouble(args[7]));
            } else if (args.length > 2 && args[2].equals("select")) {
                probe.select(out);
            } else if (args.length > 2 && args[2].equals("local")) {
                probe.render(out.resolve("local"), 401, 1, -3102.5, 2999.5);
                probe.performance(out);
            } else {
                probe.render(out.resolve("wide"), 1401, 5, -3642.5, -3365.5);
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(out.resolve("wide/control.f64be"))));
                require(sha.equals("bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965"), "control drift");
                probe.render(out.resolve("local"), 401, 1, -3102.5, 2999.5);
                probe.performance(out);
            }
            System.out.println("RIDGE PLACEMENT COMPARISON PASS");
        }
    }

    private void select(Path out) throws Exception {
        StringBuilder csv = new StringBuilder("x,z,active,dominant,alpha,weights\n");
        for (double z = 3072.5; z < 4096; z += 64) {
            for (double x = -3071.5; x < -2048; x += 64) {
                var s = land.sample(x, z);
                csv.append(x).append(',').append(z).append(',').append(s.activeEntries()).append(',')
                        .append(s.dominantEntry()).append(',').append(s.alpha()).append(',')
                        .append('"').append(Arrays.toString(s.weight())).append('"').append('\n');
            }
        }
        Files.writeString(out.resolve("selection.csv"), csv.toString());
    }

    private void performance(Path out) throws Exception {
        double[][] points = new double[512][2];
        Random r = new Random(17);
        for (double[] p : points) { p[0] = -3102.5 + r.nextDouble() * 400; p[1] = 2999.5 + r.nextDouble() * 400; }
        DoubleBinaryOperator control = land::e;
        DoubleBinaryOperator recipe = (x, z) -> {
            var s = land.sample(x, z);
            double coverage = s.mask() < land.stats().blendLow() ? Double.NaN : land.oldCoverageHeight(x, z);
            if (s.mask() >= land.stats().blendLow()) coverage += delta((a, b) -> toE(land.oldCoverageHeight(a, b)), x, z) / MorphScale.E_SPAN;
            return toE(land.overlay(recipeKernel.sampleLand(x, z, s.weight()), s.mask(), coverage));
        };
        DoubleBinaryOperator post = (x, z) -> land.e(x, z) + delta(land::e, x, z);
        DoubleBinaryOperator[] arms = {control, recipe, post};
        double[][] times = new double[3][7];
        double sink = 0;
        for (int round = -3; round < 7; round++) {
            for (int order = 0; order < 3; order++) {
                int arm = Math.floorMod(round + order, 3);
                long t = System.nanoTime();
                for (double[] p : points) sink += arms[arm].applyAsDouble(p[0], p[1]);
                if (round >= 0) times[arm][round] = (System.nanoTime() - t) / 1e6;
            }
        }
        StringBuilder csv = new StringBuilder("arm,points,warmupBatches,measuredBatches,p50ms,p95ms\n");
        for (int a = 0; a < 3; a++) {
            Arrays.sort(times[a]);
            csv.append(new String[]{"control", "recipe", "post"}[a]).append(",512,3,7,")
                    .append(times[a][3]).append(',').append(times[a][6]).append('\n');
        }
        Files.writeString(out.resolve("performance.csv"), csv.toString());
        System.out.println(csv + "sink=" + sink);
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
