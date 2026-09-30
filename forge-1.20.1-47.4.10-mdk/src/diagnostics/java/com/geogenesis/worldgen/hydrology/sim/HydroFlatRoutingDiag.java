package com.geogenesis.worldgen.hydrology.sim;

/**
 * 诊断：<b>平坦地形上的 D8 流向分布</b>（验证"ε 阶梯 → 网格直线"假设与修复效果）。
 *
 * <p><b>要回答的问题</b>：生产出图里出现"网格状笔直长直线"（实测最长 74 格纯竖直、
 * 水平 26 格、并有直角交叉）。根因是 {@code HydroTileTopology} 的 priority-flood
 * 以 <b>window 边界</b>作种子 ⇒ 平坦区 fill = "到人工边界的距离" ⇒ D8 垂直于边界。</p>
 *
 * <p><b>三个场景</b>：
 * <ol>
 *   <li><b>A 完全平坦</b>：信息论上无方向信息 ⇒ 任何确定性解法都会退化为单一方向
 *       （这是必然，不是缺陷）；保留作对照。</li>
 *   <li><b>B 微起伏平原</b>：模拟真实平原（起伏 ±0.05 块，远小于湖深但远大于 FILL_EPS）
 *       ⇒ 检验修复中的"原始地形扰动"项能否打破规则等值线。</li>
 *   <li><b>C 碗形洼地</b>：rim 在窗内的已解析盆地，检验"朝溢口收敛"是否正确。</li>
 * </ol>
 *
 * <p><b>判据</b>：修复目标是 B/C 的流向<b>不再高度集中</b>（分布应显著分散于多方向）；
 * 且 C 的路径应<b>收敛到溢口</b>而非笔直走到底。</p>
 *
 * <p>运行：{@code gradlew runHydroFlatRoutingDiag}</p>
 */
public final class HydroFlatRoutingDiag {

    public static void main(String[] args) {
        HydroConfig cfg = HydroConfig.defaults();
        HydroTileKey key = HydroTileKey.of(0L, 0, 0, 0);
        final double base = 100.0;

        runCase("A 完全平坦（h ≡ 100，信息论上必然退化）", key, cfg,
                (x, z) -> base);

        runCase("B 微起伏平原（±0.05 块，模拟真实平原）", key, cfg,
                (x, z) -> base + 0.05 * Math.sin(x * 0.13) * Math.cos(z * 0.11)
                        + 0.02 * Math.sin(x * 0.31 + z * 0.27));

        final double cx = 72.0, cz = 72.0;   // 近似 window 中心（方块）
        runCase("C 碗形洼地（rim 在窗内）", key, cfg,
                (x, z) -> {
                    double dx = x - cx, dz = z - cz;
                    return base + 0.004 * (dx * dx + dz * dz);
                });
    }

    private static void runCase(String title, HydroTileKey key, HydroConfig cfg,
                                HydroSampler sampler) {
        HydroTileField f = HydroTileField.sample(key, cfg, sampler);
        HydroTileTopology topo = HydroTileTopology.build(f);
        int w = f.w;
        int halo = cfg.haloCells(), core = cfg.coreCells();
        double eps = HydroContract.FILL_EPS;

        System.out.printf("%n================ %s ================%n", title);
        System.out.println("  " + topo.summary());

        // ① core 区 down 方向直方图
        int[] hist = new int[8];
        int noDown = 0, n = 0;
        for (int lx = halo; lx < halo + core; lx++) {
            for (int lz = halo; lz < halo + core; lz++) {
                int idx = lx * w + lz;
                int d = topo.downDir[idx];
                n++;
                if (d < 0) noDown++;
                else hist[d]++;
            }
        }
        StringBuilder hb = new StringBuilder("  [1] 流向分布：");
        for (int d = 0; d < 8; d++) {
            if (hist[d] > 0) hb.append(String.format("%s=%.1f%% ", HydroContract.DIR_NAME[d],
                    100.0 * hist[d] / n));
        }
        hb.append("| down=-1: ").append(noDown);
        System.out.println(hb);

        int mx = 0, used = 0;
        for (int v : hist) { mx = Math.max(mx, v); if (v > 0) used++; }
        System.out.printf("  ⇒ 最大方向占比 %.1f%%（均匀 12.5%%）；出现方向数 %d/8%n",
                100.0 * mx / Math.max(1, n), used);
        System.out.println("  ⇒ 判读：" + (100.0 * mx / n > 40 ? "★ 高度集中（仍退化）"
                : (100.0 * mx / n > 25 ? "部分集中" : "✓ 分散（形态已自然化）")));

        // ② 跟随下游链
        int cur = (halo + core / 2) * w + (halo + core / 2);
        StringBuilder path = new StringBuilder();
        int steps = 0;
        while (cur >= 0 && steps < 300) {
            path.append(HydroContract.DIR_NAME[Math.max(0, topo.downDir[cur])]).append(' ');
            cur = topo.down[cur];
            steps++;
        }
        System.out.printf("  [2] 中心格下游链（%d 步）：%s%n", steps,
                path.length() > 150 ? path.substring(0, 150) + "..." : path);
    }
}
