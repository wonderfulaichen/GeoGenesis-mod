package com.geogenesis.worldgen.terrain;

/**
 * 构造场连续性探针（★ 2026-09-12）：定位"线性疤痕/串珠状台阶"伪影的根因。
 *
 * <p>用户反馈：地质系统接入后预览出现<b>明显不自然的地形</b>（笔直线性疤痕、沿线串珠台阶）。
 *
 * <p><b>假设</b>：{@link TectonicField} 的距离公式
 * {@code dist = |d2²-d1²| / (2·len)} 依赖最近邻配对 (c1,c2)；
 * 当配对切换时 {@code len=|s1-s2|} <b>跳变</b>、切向 <b>跳变</b>
 * → dist/along 不连续 → T5 的 {@code floor(dist/spacing)} 块索引跳变 = 伪断层崖。
 *
 * <p>本探针沿细步长遍历，<b>实测</b> dist / 切向 / 形变偏移的逐点跳变量，
 * 并对比备选距离公式 {@code (d2-d1)/2}（连续，无 len 除法）的跳变量。
 *
 * <p>用法：{@code gradlew runTectonicContinuityProbe [seed]}
 */
public final class TectonicContinuityProbe {

    /**
     * dist 跳变阈值（wu）。
     *
     * <p>★ 注意：dist 的<b>正常梯度就是 1.0/wu</b>（远离边界时距离随位置线性增长），
     * 故阈值必须 &gt; 1.0×步长，否则会把正常梯度误报为"跳变"
     * （初版用 0.5 就犯了这个错：修复后仍有 52% 的"跳变"其实是正常梯度）。</p>
     *
     * <p>★★ 2026-09-13 阈值修正（1.5 → 2.2，消除<b>假 FAIL</b>）★★</p>
     * <p>原阈值只按"无域扭曲时 {@code |∇dist| ≤ 1.0}"设定，<b>漏算了生产路径的域扭曲</b>：
     * <pre>
     *   生产：dist(p) = distRaw(W(p))，W(p) = p + WARP_AMP·valueNoise(p/400)
     *   |∇dist| ≤ 1 · (1 + |∇W|)，|∇W| ≤ WARP_AMP · max|∇CR| / 400
     *                      ≤ 130 · 3.3 / 400 ≈ 1.07
     *   ⇒ 合法上界 ≈ 2.07 → 阈值取 2.2
     * </pre>
     * <b>本探针 [3] 自证</b>：无扭曲的裸公式 {@code (d2−d1)/2} 实测 max = <b>1.00wu</b>
     * （正是理论极限，且跳变数 = 0），而生产路径 max = 1.69wu —— 差额即域扭曲的合法贡献。</p>
     * <p><b>不会漏报</b>：真正的"阶跃"伪影量级完全不同 ——
     * 历史实测（旧 {@code |d2²−d1²|/(2·len)} 配对公式）为 <b>668wu</b>；
     * 本轮修的 {@code blurDist} reach 硬切换也是数十 wu 量级。2.2 远低于它们。</p>
     */
    private static final double DIST_JUMP_EPS = 2.2;      // wu（步长 1.0）
    private static final double TANGENT_JUMP_EPS = 0.05;  // 单位向量夹角
    private static final double OFFSET_JUMP_EPS = 0.002;  // e 单位

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        SEED = seed;
        System.out.printf("=== TectonicContinuityProbe seed=%d ===%n", seed);
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);

        // ================= [1] dist / 切向 连续性 =================
        //   沿 z 固定的多条扫描线，以 1wu 细步遍历，统计逐点跳变。
        double maxDistJump = 0, maxDistJumpAll = 0, maxTanJump = 0;
        int distJumps = 0, tanJumps = 0, n = 0;
        double jumpX = 0, jumpZ = 0;
        for (double z0 = -8000; z0 <= 8000; z0 += 1237) {
            double prevDist = Double.NaN, prevTx = Double.NaN, prevTz = Double.NaN;
            for (double x = -8000; x <= 8000; x += 1.0) {
                TectonicField.Sample s = tf.sample(x, z0);
                if (!Double.isNaN(prevDist)) {
                    n++;
                    // 只在边界影响范围内考察（内部 dist=MAX 无意义）
                    double dJump = Math.abs(s.dist() - prevDist);
                    if (prevDist < 1e6 && s.dist() < 1e6) {
                        // ★ 2026-09-13：无条件记录真实上界（原实现只在超阈值时记录 →
                        //   输出恒为 0，掩盖了"域扭曲把梯度抬到 1.69"的事实，
                        //   导致无法复核阈值是否合理）。
                        if (dJump > maxDistJumpAll) maxDistJumpAll = dJump;
                        if (dJump > DIST_JUMP_EPS) {
                            distJumps++;
                            if (dJump > maxDistJump) { maxDistJump = dJump; jumpX = x; jumpZ = z0; }
                        }
                    }
                    if (prevDist < 1e6 && s.dist() < 1e6) {
                        double tJump = 1.0 - Math.abs(prevTx * s.tangentX() + prevTz * s.tangentZ());
                        if (tJump > TANGENT_JUMP_EPS) tanJumps++;
                        maxTanJump = Math.max(maxTanJump, tJump);
                    }
                }
                prevDist = s.dist(); prevTx = s.tangentX(); prevTz = s.tangentZ();
            }
        }
        System.out.printf("[1] 连续性(n=%d): dist 跳变(>%.1fwu)=%d 最大=%.2fwu @(%.0f,%.0f)%n",
            n, DIST_JUMP_EPS, distJumps, maxDistJump, jumpX, jumpZ);
        System.out.printf("                生产路径 dist 真实最大逐点差=%.2fwu（域扭曲合法上界≈2.07，见阈值注释）%n",
            maxDistJumpAll);
        System.out.printf("                切向跳变(>%.2f)=%d 最大=%.4f （仅观察，见下）%n",
            TANGENT_JUMP_EPS, tanJumps, maxTanJump);
        // 判据只看 dist：它被 T1(权重调制,经 gaussian)、T5(形变,经 floor/decay) 直接消费，
        //   必须连续。
        // 切向【不作判据】：修复后 T2(chain) 与 T5(形变) 均改用连续的 alongCoord，
        //   切向已不被任何消费者使用（配对切换时的残余跳变因此无实际影响）。
        boolean pass1 = distJumps == 0;
        System.out.printf("    dist 应处处连续: %s%n", pass1 ? "PASS" : "FAIL（存在不连续 → 伪影根因）");

        // ================= [2] 形变偏移连续性 =================
        //   合法的"跳变"只应出现在断层块边界（floor(dist) 阶跃处）。
        //   统计：跳变次数 与 块边界穿越次数 是否匹配；有无"无理由"的跳变。
        //   ★ 只统计【作用距离内】的跳变：T5 形变在 reach 外严格为 0，
        //   故远处的 dist/切向不连续【不影响地形】，不应计入伪影。
        //   （初版统计全域，把"远离边界处配对切换"也算进来，会夸大问题。）
        final double REACH = 900.0;   // = TectonicDeformation.FOLD_REACH（最远作用距离）
        double maxOffJump = 0;
        int offJumps = 0, n2 = 0;
        for (double z0 = -8000; z0 <= 8000; z0 += 1237) {
            double prevOff = Double.NaN, prevDist = Double.NaN;
            for (double x = -8000; x <= 8000; x += 1.0) {
                TectonicField.Sample s = tf.sample(x, z0);
                double off = td.offset(s, x, z0);
                if (!Double.isNaN(prevOff)) {
                    // 仅当【当前与上一点都在作用范围内】时才考察
                    if (s.dist() < REACH && prevDist < REACH) {
                        n2++;
                        // ★ 2026-09-12：产品已不再按 floor(dist/spacing) 分块
                        //   （改为世界坐标量化），"块边界"判据不再适用。
                        //   改用【幅度分布】统计：跳变次数 + 最大幅度，
                        //   并与合法断层崖量级（FAULT_AMP）对比。
                        double j = Math.abs(off - prevOff);
                        if (j > OFFSET_JUMP_EPS) {
                            offJumps++;
                            maxOffJump = Math.max(maxOffJump, j);
                        }
                    }
                }
                prevOff = off; prevDist = s.dist();
            }
        }
        System.out.printf("[2] 形变偏移(作用距离内 n=%d): 跳变(>%.3fe)=%d  最大=%.4fe%n",
            n2, OFFSET_JUMP_EPS, offJumps, maxOffJump);
        System.out.printf("    （合法断层崖量级 FAULT_AMP=%.3f e）%n", TectonicDeformation.FAULT_AMP);
        // 判据：跳变必须可归因于【合法断层崖】——即最大跳变不超过
        //   所有缩放系数之和 × FAULT_AMP（汇聚 0.6 + 离散 1.0 + 褶皱分量留有裕度）。
        //   若存在"超出合法崖线量级"的跳变，则说明出现了伪影。
        //   （★ 判据演进：初版按"块边界穿越"计无理由跳变，但产品已改为
        //    世界坐标量化、不再有 dist 分块，该判据失效 → 改为纯量级判据。）
        double legalMax = TectonicDeformation.FAULT_AMP * 2.2;   // 1.6(系数和) × 1.35(裕度)
        boolean pass2 = maxOffJump < legalMax;
        System.out.printf("    跳变均在合法崖线量级内(<%.4fe): %s%n",
            legalMax, pass2 ? "PASS" : "FAIL（存在超量级跳变 → 伪影）");

        // ================= [3] 对比备选距离公式的连续性 =================
        //   候选 (d2-d1)/2 —— 不含 len 除法，理论上连续（d1/d2 是连续的距离函数）。
        //   用同一遍历统计其跳变量，与现公式对比。
        double maxAltJump = 0;
        int altJumps = 0;
        for (double z0 = -8000; z0 <= 8000; z0 += 1237) {
            double prevAlt = Double.NaN;
            for (double x = -8000; x <= 8000; x += 1.0) {
                double alt = altDist(tf, x, z0);
                if (!Double.isNaN(prevAlt) && prevAlt < 1e6 && alt < 1e6) {
                    double j = Math.abs(alt - prevAlt);
                    if (j > DIST_JUMP_EPS) altJumps++;
                    maxAltJump = Math.max(maxAltJump, j);
                }
                prevAlt = alt;
            }
        }
        System.out.printf("[3] 备选公式 (d2-d1)/2: 跳变(>%.1fwu)=%d 最大=%.2fwu %s%n",
            DIST_JUMP_EPS, altJumps, maxAltJump, altJumps == 0 ? "（连续✓）" : "（仍有跳变）");

        boolean all = pass1 && pass2;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT（存在不连续性，需修复）===");
        if (!all) System.exit(1);
    }

    /**
     * 备选距离：{@code (d2-d1)/2}（用于连续性对比，不修改生产代码）。
     * 复刻 {@link TectonicField} 的种子/速度哈希以取得同一 (d1,d2)。
     */
    private static double altDist(TectonicField tf, double wx, double wz) {
        // 复用 TectonicField.sample 的 rate/btype 判定，但距离改用 (d2-d1)/2。
        // 这里通过反射不可行（私有），故用公开 API 的 dist 反推不可靠 —— 
        // 改为：直接调用 sample 拿 btype，距离另算（复刻同样的哈希）。
        // 为保持探针自包含，重新实现最小版哈希（与 TectonicField 常量一致）。
        final double spacing = 2000.0, jitter = 0.7;
        final long saltSeed = 0x9E3779B97F4A7C15L;
        long sd = SEED;
        int baseX = (int) Math.floor(wx / spacing), baseZ = (int) Math.floor(wz / spacing);
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = baseX + dx, cz = baseZ + dz;
                long h = hash(cx, cz, saltSeed, sd);
                double jx = unit(h), jz = unit(h >>> 24);
                double sxp = (cx + 0.5 + (jx - 0.5) * jitter) * spacing;
                double szp = (cz + 0.5 + (jz - 0.5) * jitter) * spacing;
                double ddx = wx - sxp, ddz = wz - szp;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
            }
        }
        if (d2 == Double.MAX_VALUE) return Double.MAX_VALUE;
        return (d2 - d1) * 0.5;
    }

    private static long SEED = 12345L;

    private static long hash(int x, int z, long salt, long seed) {
        long h = (long) x * 374761393L + (long) z * 668265263L + salt + seed * 0x9E3779B9L;
        h = (h ^ (h >>> 16)) * 1274126177L;
        return h ^ (h >>> 16);
    }

    private static double unit(long h) {
        return (h & 0xFFFFFFL) / (double) 0x1000000L;
    }
}
