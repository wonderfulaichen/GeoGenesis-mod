package com.geogenesis.worldgen.terrain;

/**
 * 构造形变探针（★ 2026-09-12，地质系统 Phase T5 配套）：验证褶皱与断层的<b>形态特征</b>。
 *
 * <p>验证项：
 * <ol>
 *   <li><b>环境限定</b>：只有汇聚（褶皱+逆断层）/ 离散（正断层）有形变；
 *       走滑与内部必须恒为 0（与 T1 判定一致）</li>
 *   <li><b>褶皱周期性</b>：沿垂直边界方向应有周期性脊谷（符号交替）</li>
 *   <li><b>断层阶跃</b>：断层位移在块边界处应有<b>骤变</b>（崖线），
 *       且块内沿走向平滑——这是"断层崖"与"平滑坡"的本质差别</li>
 *   <li><b>近零均值</b>：全域均值应接近 0（不自造整体抬升/沉降）</li>
 *   <li>确定性、作用范围有限（超出 reach 必须为 0）</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runTectonicDeformProbe [seed]}</p>
 */
public final class TectonicDeformProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== TectonicDeformProbe seed=%d ===%n", seed);
        TectonicDeformation td = new TectonicDeformation(seed);

        // ================= [1] 环境限定 =================
        //   构造各环境的 Sample（dist 取 0 与中途各值），逐点求 offset。
        int leak = 0, checked = 0;
        for (double d : new double[]{0, 50, 150, 300, 500, 700, 900, 1200}) {
            for (int bt : new int[]{TectonicField.TRANSFORM, TectonicField.INTERIOR}) {
                double off = td.offset(new TectonicField.Sample(d, bt, 1.0, 0.0, 1.0), 1234.0, 5678.0);
                checked++;
                if (off != 0.0) leak++;
            }
        }
        boolean pass1 = leak == 0;
        System.out.printf("[1] 走滑/内部 必须无形变(n=%d): 泄漏=%d %s%n",
            checked, leak, pass1 ? "PASS" : "FAIL");

        // ================= [2] 褶皱周期性：符号交替 =================
        //   沿 dist 均匀采样（同一沿走向坐标），统计符号变化次数。
        //   波长 300wu、作用距离 900wu → 至少应有 2~3 次过零。
        int signChanges = 0;
        double prev = 0;
        double ampMax = 0;
        for (double d = 0; d < TectonicDeformation.FOLD_REACH; d += 10.0) {
            TectonicField.Sample s = new TectonicField.Sample(d, TectonicField.CONVERGENT, 1.0, 0.0, 1.0);
            // 只看褶皱分量：把断层分量减掉（用同为汇聚但无褶皱的等价方式不可行，
            // 故此处用"沿走向固定 → 断层分量在块内近似常数"的特性，
            // 直接统计总位移的过零次数——褶皱是主周期源）
            double v = td.offset(s, 4321.0, 8765.0);
            ampMax = Math.max(ampMax, Math.abs(v));
            if (prev != 0 && Math.signum(v) != Math.signum(prev)) signChanges++;
            prev = v;
        }
        boolean pass2 = signChanges >= 2;
        System.out.printf("[2] 褶皱周期性(汇聚, 沿dist扫描): 过零次数=%d (要求>=2) 最大幅值=%.4f e %s%n",
            signChanges, ampMax, pass2 ? "PASS" : "FAIL");

        // ================= [3] 断层阶跃：块边界处应有骤变 =================
        //   在块边界（dist = k·FAULT_SPACING）两侧微小偏移处取值，差值应显著大于
        //   块内同距离的差值（即"崖线"而非"平滑坡"）。
        double maxJump = 0, maxSmooth = 0;
        for (double k = 1; k <= 2; k++) {
            double edge = k * TectonicDeformation.FAULT_SPACING;
            // 跨边界跳变（±2wu）
            double a = td.offset(new TectonicField.Sample(edge - 2, TectonicField.DIVERGENT, 1.0, 0.0, 1.0), 999.0, 777.0);
            double b = td.offset(new TectonicField.Sample(edge + 2, TectonicField.DIVERGENT, 1.0, 0.0, 1.0), 999.0, 777.0);
            maxJump = Math.max(maxJump, Math.abs(b - a));
            // 块内平滑（同 4wu 跨度，但取块中心附近）
            double mid = edge + TectonicDeformation.FAULT_SPACING * 0.5;
            double c = td.offset(new TectonicField.Sample(mid - 2, TectonicField.DIVERGENT, 1.0, 0.0, 1.0), 999.0, 777.0);
            double e = td.offset(new TectonicField.Sample(mid + 2, TectonicField.DIVERGENT, 1.0, 0.0, 1.0), 999.0, 777.0);
            maxSmooth = Math.max(maxSmooth, Math.abs(e - c));
        }
        boolean pass3 = maxJump > maxSmooth * 3.0;
        System.out.printf("[3] 断层崖(离散): 跨块边界跳变=%.4f 块内同跨度变化=%.4f 比值=%.1f× %s%n",
            maxJump, maxSmooth, maxSmooth > 0 ? maxJump / maxSmooth : 0, pass3 ? "PASS" : "FAIL");
        System.out.println("    要求: 跨边界跳变 >3× 块内变化（陡崖 ≠ 平滑坡）");

        // ================= [4] 近零均值（真实世界分布） =================
        //   ★ 判据两处修正：
        //   ① 采样分布：初版沿 dist 均匀采样，但距离场的【面积权重 ∝ dist】
        //      （等距环的周长大）→ 均匀采 dist 会过度加权近边界区，均值估计有偏。
        //      改为按【真实世界坐标网格】采样（真实 Voronoi 分布）。
        //   ② 阈值：初版用"均值/平均幅值 <0.25"这类任意比值；改换算成【块数】，
        //      物理意义明确（地形 1 e 单位 ≈ 192 块）。
        TectonicField tf = new TectonicField(seed);
        TerrainParams tp4 = TerrainParams.defaults();
        double blocksPerE = (tp4.maxY() - tp4.minY()) / 2.0;   // e ∈[-1,1] → 高度跨度
        double sum = 0, sumAbs = 0, mn = 1e9, mx = -1e9;
        int n = 0, active = 0;
        for (double z = -12000; z <= 12000; z += 149) {
            for (double x = -12000; x <= 12000; x += 151) {
                double v = td.offset(tf.sample(x, z), x, z);
                sum += v; sumAbs += Math.abs(v);
                if (v != 0.0) active++;
                mn = Math.min(mn, v); mx = Math.max(mx, v);
                n++;
            }
        }
        double mean = sum / n, meanAbs = sumAbs / n;
        double meanBlocks = mean * blocksPerE;
        // 判据：全域平均偏移 < 0.5 块（远小于地形起伏，不构成"整体抬升/沉降"）
        boolean pass4 = Math.abs(meanBlocks) < 0.5;
        System.out.printf("[4] 近零均值(真实网格 n=%d, 有形变 %d=%.0f%%): 均值=%+.5f e (%.2f 块) 平均幅值=%.5f e 范围=[%.4f, %.4f] e %s%n",
            n, active, 100.0 * active / n, mean, meanBlocks, meanAbs, mn, mx, pass4 ? "PASS" : "FAIL");
        System.out.printf("    要求: 全域平均偏移 <0.5 块（1 e ≈ %.0f 块）→ 有起伏但无整体升降 %n", blocksPerE);

        // ================= [5] 作用范围有限 =================
        double far = td.offset(new TectonicField.Sample(TectonicDeformation.FOLD_REACH + 1,
            TectonicField.CONVERGENT, 1.0, 0.0, 1.0), 100.0, 200.0);
        double farF = td.offset(new TectonicField.Sample(TectonicDeformation.FAULT_REACH + 1,
            TectonicField.DIVERGENT, 1.0, 0.0, 1.0), 100.0, 200.0);
        boolean pass5 = far == 0.0 && farF == 0.0;
        System.out.printf("[5] 作用范围有限: 超出reach 褶皱=%.6f 断层=%.6f (均应为0) %s%n",
            far, farF, pass5 ? "PASS" : "FAIL");

        // ================= [6] 确定性 =================
        TectonicDeformation td2 = new TectonicDeformation(seed);
        int mism = 0;
        for (int i = 0; i < 300; i++) {
            double x = i * 311.0 - 20000, z = i * 197.0 - 15000;
            TectonicField.Sample s = new TectonicField.Sample(i % 300, TectonicField.CONVERGENT, 1.2, 0.3, 0.8);
            if (Double.compare(td.offset(s, x, z), td2.offset(s, x, z)) != 0) mism++;
        }
        boolean pass6 = mism == 0;
        System.out.printf("[6] 确定性(n=300): 不一致=%d %s%n", mism, pass6 ? "PASS" : "FAIL");

        // ================= [7] 性能 =================
        final int iters = 1_000_000;
        TectonicField.Sample s = new TectonicField.Sample(150, TectonicField.CONVERGENT, 1.5, 0.6, 0.8);
        long t0 = System.nanoTime();
        double acc = 0;
        for (int i = 0; i < iters; i++) acc += td.offset(s, i * 1.7, i * 2.3);
        long t1 = System.nanoTime();
        double us = (t1 - t0) / 1000.0 / iters;
        boolean pass7 = acc != 0 && us < 2.0;
        System.out.printf("[7] 性能: offset %.3f µs/次 (阈值 2µs) %s%n", us, pass7 ? "PASS" : "FAIL");

        boolean all = pass1 && pass2 && pass3 && pass4 && pass5 && pass6 && pass7;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
