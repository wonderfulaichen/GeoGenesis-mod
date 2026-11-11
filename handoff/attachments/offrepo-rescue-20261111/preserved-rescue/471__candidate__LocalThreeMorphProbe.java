package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** 真实局部几何上的受控三族实验，不接 Gallery、海岸或生产。 */
public final class LocalThreeMorphProbe {
    private static final long SEED = 9139912035078620160L;
    private static final double CX = -362.3561, CZ = -977.0970;
    private static final int SIZE = 601;
    private final LocalBoundaryGeometry geometry;
    private final LocalThreeMorphCandidate morph = new LocalThreeMorphCandidate(SEED);
    private final CombineCandidate original;
    private final HeightCurve curve;
    private long checks;

    private LocalThreeMorphProbe(Path toml, Path out) throws Exception {
        Files.createDirectories(out);
        Files.copy(toml, out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        try (CommentedFileConfig cfg = CommentedFileConfig.of(out.resolve("config-input.toml"), TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "config requires correction");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);
        }
        Method factory = LandCompositionGallery.class.getDeclaredMethod("buildCombineRegional", long.class, float.class);
        factory.setAccessible(true);
        original = (CombineCandidate) factory.invoke(null, SEED, 3600f);
        geometry = LocalBoundaryGeometry.of(original, CX, CZ, 450, 5, 0.02);
        geometry.setMaxSeg(2);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[1]);
        LocalThreeMorphProbe p = new LocalThreeMorphProbe(Path.of(args[0]), out);
        p.maps(out);
        p.profiles(out);
        p.normalProfile(out, Path.of(args[2]));
        p.verifySpatial(out);
        p.geometry.writeSegmentsCsv(out.resolve("segments.csv"));
        p.geometry.writeRegionsCsv(out.resolve("regions.csv"));
        Files.writeString(out.resolve("scene.json"), p.geometry.sceneJson());
        Files.writeString(out.resolve("geometry.json"), "{\"size\":601,\"step\":1,\"center\":[-362.3561,-977.0970],"
                + "\"seed\":\"" + SEED + "\",\"assignment\":\"(-1,-2)=M1; (0,-1)=HILLS1 controlled replacement of DALES; (-1,-1)=PLAIN\"}\n");
        Files.writeString(out.resolve("checks.txt"), "PASS checks=" + p.checks
                + "\nControlled PHM only; actual slots are M1/DALES/PLAIN.\n"
                + "World boundary polyline is numerical, not an analytic exact world SDF.\n");
        System.out.println("LOCAL MAPS PASS checks=" + p.checks);
    }

    private double[] weights(double x, double z) {
        LocalBoundaryGeometry.Sample s = geometry.query(x, z);
        double[] w = new double[3];
        for (int i = 0; i < s.weight.length; i++) {
            if (s.weight[i] == 0) continue;
            LocalBoundaryGeometry.Cell cell = geometry.sites().get(i);
            int k;
            if (cell.cellX == -1 && cell.cellZ == -2) k = 2;
            else if (cell.cellX == 0 && cell.cellZ == -1) k = 1;
            else if (cell.cellX == -1 && cell.cellZ == -1) k = 0;
            else throw new IllegalStateException("unsupported active cell " + cell);
            w[k] += s.weight[i];
        }
        require(Math.abs(w[0] + w[1] + w[2] - 1) < 1e-12, "weights sum");
        return w;
    }

    private double[] values(double x, double z, double[] w) {
        double value = morph.sample(x, z, w[0], w[1], w[2]);
        double[] raw = {morph.sample(x, z, 1, 0, 0), morph.sample(x, z, 0, 1, 0),
                morph.sample(x, z, 0, 0, 1)};
        int dominant = w[1] > w[0] ? 1 : 0;
        if (w[2] > w[dominant]) dominant = 2;
        double lerp = w[0] * raw[0] + w[1] * raw[1] + w[2] * raw[2];
        return new double[]{y(value), y(lerp), y(raw[dominant]), y(original.debugSelected(x, z)),
                y(original.debugRegionHeight(x, z)), curve.heightFromE(original.e(x, z))};
    }

    private void maps(Path out) throws Exception {
        String[] names = {"morph", "finished-lerp", "controlled-hard", "actual-selected", "actual-region", "actual-covered"};
        DataOutputStream[] streams = new DataOutputStream[names.length + 3];
        try {
            for (int i = 0; i < streams.length; i++) {
                String name = i < names.length ? names[i] : "weight-" + (i - names.length);
                streams[i] = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name + ".f64be"))));
            }
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = CX + i - SIZE / 2, z = CZ + j - SIZE / 2;
                    double[] w = weights(x, z), v = values(x, z, w);
                    for (int k = 0; k < v.length; k++) {
                        require(Double.isFinite(v[k]), "non-finite map");
                        streams[k].writeDouble(v[k]);
                    }
                    for (int k = 0; k < 3; k++) streams[names.length + k].writeDouble(w[k]);
                }
                if (j % 100 == 0) System.out.println("row=" + j);
            }
        } finally {
            for (DataOutputStream s : streams) if (s != null) s.close();
        }
    }

    private void profiles(Path out) throws Exception {
        StringBuilder csv = new StringBuilder("angle,distance,x,z,plain,hills,mountain,morph_y,lerp_y,hard_y,actual_selected_y,actual_region_y,actual_covered_y\n");
        for (int angle : new int[]{0, 60, 120}) {
            double r = Math.toRadians(angle);
            for (int i = -600; i <= 600; i++) {
                double d = i * 0.5, x = CX + d * Math.cos(r), z = CZ + d * Math.sin(r);
                double[] w = weights(x, z), v = values(x, z, w);
                csv.append(String.format("%d,%.3f,%.9f,%.9f", angle, d, x, z));
                for (double a : w) csv.append(String.format(",%.12g", a));
                for (double a : v) csv.append(String.format(",%.12g", a));
                csv.append('\n');
            }
        }
        Files.writeString(out.resolve("profiles.csv"), csv);
    }

    private void normalProfile(Path out, Path input) throws Exception {
        StringBuilder csv = new StringBuilder("distance,x,z,weight_hills,weight_plain,morph_y,lerp_y,hard_y\n");
        for (String row : Files.readAllLines(input).subList(1, Files.readAllLines(input).size())) {
            String[] fields = row.split(",");
            double x = Double.parseDouble(fields[0]), z = Double.parseDouble(fields[1]);
            double d = Double.parseDouble(fields[2]);
            double[] w = weights(x, z), v = values(x, z, w);
            require(w[2] == 0, "ordinary profile contains mountain participation");
            csv.append(String.format("%.3f,%.9f,%.9f,%.12g,%.12g,%.12g,%.12g,%.12g%n",
                    d, x, z, w[1], w[0], v[0], v[1], v[2]));
        }
        Files.writeString(out.resolve("normal-height-profile.csv"), csv);
    }

    private void verifySpatial(Path out) throws Exception {
        StringBuilder csv = new StringBuilder("epsilon,max_neighbor_delta_h01\n");
        double previous = Double.POSITIVE_INFINITY;
        for (double eps : new double[]{0.1, 0.01, 0.001}) {
            double max = 0;
            for (int j = -120; j <= 120; j += 12) {
                for (int i = -120; i <= 120; i += 6) {
                    double x = CX + i, z = CZ + j;
                    double[] a = weights(x - eps, z), b = weights(x + eps, z);
                    double av = morph.sample(x - eps, z, a[0], a[1], a[2]);
                    double bv = morph.sample(x + eps, z, b[0], b[1], b[2]);
                    max = Math.max(max, Math.abs(av - bv));
                }
            }
            require(max < previous * 0.2, "spatial neighbor difference not converging");
            if (eps == 0.001) require(max < 1e-4, "spatial residual jump");
            previous = max;
            csv.append(String.format("%.6f,%.12g%n", eps, max));
        }
        Files.writeString(out.resolve("spatial-limit.csv"), csv);
        double[] center = weights(CX, CZ);
        for (double w : center) require(Math.abs(w - 1.0 / 3) < 1e-4, "triple center weights");
    }

    private double y(double h) { return curve.heightFromE(Math.max(CombineCandidate.GROUND_E + h * CombineCandidate.E_SPAN, 0)); }
    private void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
