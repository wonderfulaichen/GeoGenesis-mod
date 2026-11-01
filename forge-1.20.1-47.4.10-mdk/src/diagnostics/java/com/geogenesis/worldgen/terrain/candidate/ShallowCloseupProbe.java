package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 浅海段的 <b>1:1 特写</b>导出：step=1（逐块），用于判断形态项"读起来像不像自然海底"。
 *
 * <p>存在的理由：1401² 大图是 step=5（1 像素 = 5 块），带内形态在那种比例下只剩几个像素，
 * 无法判断是"自然起伏"还是"紧贴岸线的等值线包络"。本项目历史上三次栽在平行带伪影上，
 * 判据只能是人眼看 1:1 图（{@code AGENTS.md}：「最终依据只能是渲染图」）。</p>
 *
 * <h3>窗口</h3>
 * <p>中心与 1401² 大图同种子同窗口，只把 step 换成 1、边长换成 601（= 约 601 块见方）。
 * 中心点默认落在一条较宽的浅海带上（由 {@code --auto} 搜索，避免挑一个带宽只有 20 块的
 * 位置，那样看不出任何东西）。</p>
 *
 * <h3>输出</h3>
 * <pre>
 *   base.f64be   baseline 臂高度（格，Y）
 *   shelf.f64be  shelf 臂高度（格，Y）
 *   delta.f64be  两者的差（格）
 *   meta.json    窗口几何（供渲染脚本严格校验，schema 与 render-full-morph.py 不同）
 * </pre>
 */
public final class ShallowCloseupProbe {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 601;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/shallow-closeup");
        Path toml = Path.of(args.length > 1 ? args[1]
                : "build/terrain-upgrade/baseline/config-input.toml");
        Files.createDirectories(out);

        var params = ShallowShelfProbe.loadParams(toml);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        var catalog = new RecipeMorphCatalog(SEED);
        var land = catalog.original();
        HeightCurve curve = new HeightCurve(params, -64, 320);

        OceanAssemblyCandidate base = new OceanAssemblyCandidate(land, continent, SEED + 3)
                .enableCoastalDetail(SEED);
        OceanAssemblyCandidate shelf = new OceanAssemblyCandidate(land, continent, SEED + 3)
                .enableCoastalDetail(SEED).enableShallowDetail(SEED);

        double[] c = pickCenter(continent, base);
        System.out.printf(Locale.ROOT, "closeup center = (%.1f, %.1f)%n", c[0], c[1]);

        try (DataOutputStream b = stream(out.resolve("base.f64be"));
             DataOutputStream s = stream(out.resolve("shelf.f64be"));
             DataOutputStream d = stream(out.resolve("delta.f64be"))) {
            double maxAbs = 0.0;
            int changed = 0, water = 0, landChanged = 0;
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = c[0] + (i - SIZE / 2.0);
                    double z = c[1] + (j - SIZE / 2.0);
                    double yb = curve.heightFromE(base.e(x, z));
                    double ys = curve.heightFromE(shelf.e(x, z));
                    b.writeDouble(yb);
                    s.writeDouble(ys);
                    d.writeDouble(ys - yb);
                    if (yb < 63.0) water++;
                    if (ys != yb) {
                        changed++;
                        maxAbs = Math.max(maxAbs, Math.abs(ys - yb));
                        if (yb >= 63.0) landChanged++;
                    }
                }
            }
            Files.writeString(out.resolve("meta.json"), String.format(Locale.ROOT,
                    "{\"seed\":\"%d\",\"size\":%d,\"step\":1,\"center\":[%.1f,%.1f],"
                            + "\"sampleOrigin\":[%.1f,%.1f],\"jvmProperties\":[\"file.encoding=UTF-8\"]}%n",
                    SEED, SIZE, c[0], c[1], c[0] - SIZE / 2.0, c[1] - SIZE / 2.0));
            System.out.printf(Locale.ROOT,
                    "CLOSEUP PASS changed=%d water=%d landChanged=%d maxAbs=%.3f blocks%n",
                    changed, water, landChanged, maxAbs);
            if (landChanged != 0) {
                throw new AssertionError("shallow detail touched land in closeup: " + landChanged);
            }
        }
    }

    /**
     * 自动挑中心：在 1401² 的候选格点里找"带内像素最多 + 带宽最长"的位置。
     *
     * <p>带宽用沿 x 的连续带内像素计数度量（临时的、只用于选址，不参与产出）。</p>
     */
    private static double[] pickCenter(ContinentField continent, OceanAssemblyCandidate base) {
        double bestScore = -1;
        double[] best = {-140, 137};
        for (double cz = -2200; cz <= 2200; cz += 220) {
            for (double cx = -2200; cx <= 2200; cx += 220) {
                int inBand = 0, longestRun = 0, run = 0;
                for (int i = -260; i <= 260; i += 8) {
                    double e = (continent.sample(cx + i, cz) + 1.0) * 0.5;
                    boolean band = e >= OceanAssemblyCandidate.cpShelfOf()
                            && e < OceanAssemblyCandidate.cpShoreOf();
                    if (band) {
                        inBand++;
                        run++;
                        longestRun = Math.max(longestRun, run);
                    } else {
                        run = 0;
                    }
                }
                double score = longestRun * 8.0 + inBand;
                if (score > bestScore) {
                    bestScore = score;
                    best = new double[]{cx, cz};
                }
            }
        }
        return best;
    }

    private static DataOutputStream stream(Path file) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
    }

    private ShallowCloseupProbe() { }
}
