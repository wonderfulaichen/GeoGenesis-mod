package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * 浅海带（{@code cpShelf → cpShore}）的<b>实测几何</b>：这段"纯斜面"到底有多少块宽。
 *
 * <p>存在的理由：浅海段补形态需要选噪声尺度，而尺度必须按<b>带的实际块宽</b>定。
 * {@code edge = (c+1)/2} 是 c 的线性函数，但 c 是 6 octave FBM（continentScale=4000、
 * persistence 0.6）⇒ {@code |∇edge|} 的分布不能靠公式口算，必须实测。</p>
 *
 * <h3>输出的三个量</h3>
 * <ul>
 *   <li><b>带内 |∇edge| 中位/P10/P90</b>：换算成"这段在方向上约多少块"。</li>
 *   <li><b>带内像素占比</b>：浅海带在 1401² 窗口里占多大面积（判"改这段值不值"）。</li>
 *   <li><b>水深分布</b>：带内高度从 −7 格到 0，看中位深度（判 detail 振幅该多大）。</li>
 * </ul>
 *
 * <p>只读：不改任何生产对象，只采 {@link ContinentField} 与 {@link OceanAssemblyCandidate}。</p>
 */
public final class ShallowShelfProbe {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        var params = loadParams(Path.of(args.length > 0 ? args[0]
                : "build/terrain-upgrade/baseline/config-input.toml"));
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        var catalog = new RecipeMorphCatalog(SEED);
        var land = catalog.original();
        OceanAssemblyCandidate ocean = new OceanAssemblyCandidate(land, continent, SEED + 3)
                .enableCoastalDetail(SEED);

        double[] grad = new double[SIZE * SIZE];
        double[] depth = new double[SIZE * SIZE];
        int inBand = 0, n = 0;
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                double x = CX + (i - SIZE / 2.0) * STEP;
                double z = CZ + (j - SIZE / 2.0) * STEP;
                double e0 = edge(continent, x, z);
                double gx = (edge(continent, x + 4, z) - edge(continent, x - 4, z)) / 8.0;
                double gz = (edge(continent, x, z + 4) - edge(continent, x, z - 4)) / 8.0;
                double gm = Math.hypot(gx, gz);
                n++;
                if (e0 >= OceanAssemblyCandidate.cpShelfOf() && e0 < OceanAssemblyCandidate.cpShoreOf()) {
                    inBand++;
                    grad[inBand - 1] = gm;
                    depth[inBand - 1] = ocean.heightBlocks(x, z);
                }
            }
        }
        double[] g = Arrays.copyOf(grad, inBand);
        double[] d = Arrays.copyOf(depth, inBand);
        Arrays.sort(g);
        Arrays.sort(d);
        double band = OceanAssemblyCandidate.cpShoreOf() - OceanAssemblyCandidate.cpShelfOf();
        System.out.printf(Locale.ROOT, "band edge width      = %.4f  (cpShelf=%.4f cpShore=%.4f)%n",
                band, OceanAssemblyCandidate.cpShelfOf(), OceanAssemblyCandidate.cpShoreOf());
        System.out.printf(Locale.ROOT, "pixels in band       = %d / %d  = %.2f%%%n",
                inBand, n, 100.0 * inBand / n);
        System.out.printf(Locale.ROOT, "height range in band = %.2f .. %.2f blocks (median %.2f)%n",
                d[0], d[inBand - 1], d[inBand / 2]);
        System.out.printf(Locale.ROOT, "|grad edge| P10      = %.3e  -> band ~ %.0f blocks%n",
                q(g, 0.10), band / q(g, 0.10));
        System.out.printf(Locale.ROOT, "|grad edge| median   = %.3e  -> band ~ %.0f blocks%n",
                q(g, 0.50), band / q(g, 0.50));
        System.out.printf(Locale.ROOT, "|grad edge| P90      = %.3e  -> band ~ %.0f blocks%n",
                q(g, 0.90), band / q(g, 0.90));
    }

    private static double edge(ContinentField c, double x, double z) {
        return (c.sample(x, z) + 1.0) * 0.5;
    }

    private static double q(double[] sorted, double p) {
        int i = (int) Math.round(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    static com.geogenesis.worldgen.terrain.TerrainParams loadParams(Path toml) {
        try (CommentedFileConfig cfg = CommentedFileConfig.of(toml, TomlFormat.instance())) {
            cfg.load();
            if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) {
                throw new IllegalStateException("frozen config requires correction: " + toml);
            }
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return GeoGenesisConfig.INSTANCE.buildParams();
        }
    }

    private ShallowShelfProbe() { }
}
