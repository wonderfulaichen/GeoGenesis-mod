package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 侵蚀量级诊断：同一批采样点，"无侵蚀高度"（sample，不含 tile delta）vs
 * "含侵蚀高度"（sampleWu，含 delta）→ 侵蚀到底改了多少格。
 *
 * <p>用法：gradlew runErosionDeltaProbe [-PprobeArgs="seed"]</p>
 *
 * <p>回答用户"打开地形侵蚀怎么实际地形没工作"：若 |Δ| 均值只有零点几格，
 * 打开/关闭侵蚀在视觉上确实几乎不可辨（量级问题，不是机制问题）。</p>
 */
public final class ErosionDeltaProbe {

    private ErosionDeltaProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);

        int n = 0, above1 = 0;
        double sumAbs = 0, sumSq = 0, maxAbs = 0;
        double sumH = 0, sumHBase = 0;
        for (int i = 0; i < 900; i++) {
            double wx = 100 + (i % 30) * 13.0;
            double wz = 100 + (i / 30) * 13.0;
            Cell base = terrain.sample(wx, wz);      // 不含侵蚀 delta
            Cell with = terrain.sampleWu(wx, wz);    // 含侵蚀 delta
            double d = with.height - base.height;
            double ad = Math.abs(d);
            sumAbs += ad;
            sumSq += d * d;
            maxAbs = Math.max(maxAbs, ad);
            if (ad > 1.0) above1++;
            sumH += with.height;
            sumHBase += base.height;
            n++;
        }
        double rms = Math.sqrt(sumSq / n);
        System.out.printf("采样点=%d  无侵蚀均高=%.2f  含侵蚀均高=%.2f%n",
                n, sumHBase / n, sumH / n);
        System.out.printf("侵蚀高度差: |Δ|均值=%.3f  RMS=%.3f  最大=%.2f  |Δ|>1格占比=%d/%d (%.1f%%)%n",
                sumAbs / n, rms, maxAbs, above1, n, 100.0 * above1 / n);
    }
}
