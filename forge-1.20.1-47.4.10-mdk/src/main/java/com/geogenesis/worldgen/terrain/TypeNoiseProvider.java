package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

public final class TypeNoiseProvider {

    private final Noise plainNoise;

    // v6.0: HILLS — 参考 TF hills_1.json 双层 Warp + Perlin×Billow^0.5
    private final Noise hillsNoise;

    // v8c (2026-08-06)：山脉改丘陵式多频叠加配方（参考 HILLS），移除脊线网络+折叠细节
    // v8b 的 foldHills(1/50) 35% 在实机上产生规律性条纹/沙丘状图案（高频折叠线主导），且山体过小；
    // v8c 只用平滑多频主体（无折叠）→ 丘陵式大圆润山体，1/700 主频 = 山体远大于丘陵
    private final Noise mountNoise;

    private final Noise platNoise;
    private final Noise basinNoise;

    // 造山带起伏振幅（e 单位），由 TerrainParams.beltReliefAmp 注入；缩放山脉脊线相对高度，让山脉起伏可调（复活死参数）
    private final double beltReliefAmp;

    public TypeNoiseProvider(double beltReliefAmp) {
        this.beltReliefAmp = beltReliefAmp;
        // --- PLAIN: 1/800 单频 + 极弱 warp，真正平坦 ---
        Noise pSimplex = new Frequency(new Simplex(410), 1.0 / 800.0);
        Noise pWarpX = new Frequency(new Simplex(433), 1.0 / 500.0);
        Noise pWarpZ = new Frequency(new Simplex(434), 1.0 / 500.0);
        Noise pWarped = new Warp(pSimplex, pWarpX, pWarpZ, 60.0);
        this.plainNoise = new Map(pWarped, -1.0, 1.0, 0.42, 0.58);

        // --- HILLS v7.5: 两大主频 + 小 Warp，MC 正确尺度 ---
        // FIX 1: 删除 1/80+1/40 细节（太碎产生"粗糙地面"非丘陵）
        // 主频 1/400（25 chunks）：丘陵主体隆起
        // 次频 1/120（7.5 chunks）：次级起伏
        // 总振幅 ~1.5 确保 Map(-1,1,0,1) 输出接近全 [0,1] 范围
        Noise hMain      = new Frequency(new Simplex(412), 1.0 / 400.0);
        Noise hSub       = new Boost(new Frequency(new Simplex(413), 1.0 / 120.0), 0.5);
        Noise hBase      = new Add(hMain, hSub);       // [-1.5, 1.5], 典型 [-0.75, 0.75]
        // 小距离 Warp（distance=25，freq=1/80）— 仅做局部不规则，不做大距离扭曲
        // 参考 TF hills_1.json: Warp(distance=20) 打散圆润感但不产生"拉面"蜿蜒
        Noise hWX        = new Frequency(new Simplex(415), 1.0 / 80.0);
        Noise hWZ        = new Frequency(new Simplex(416), 1.0 / 80.0);
        Noise hWarped    = new Warp(hBase, hWX, hWZ, 25.0);
        this.hillsNoise  = new Map(hWarped, -1.0, 1.0, 0.0, 1.0);

        // --- MOUNTAINS v8（2026-08-06 用户决策：参考丘陵配方）---
        // 旧版（v7）：MountainRidgeNetwork 脊线乘数 + 三级形态（谷底/陡升/脊线目标）+ 方向扭曲。
        // 弃因：脊线网络仅覆盖 ±4000 块，且脊线段之间空白区 ridgeBoost=0 → 山体 ×(0.35+0.65×0)=×0.35
        // 塌矮 65% → 山脉被切割成条状、大片塌陷、网络边界突变（用户反馈"山脉地形不行"）。
        // 新版：丘陵式多频叠加（与 HILLS 同风格，自然连贯），尺度放大（主频 1/700 山体更大）+ 细节
        // 破碎度（1/80）+ warp(30)。beltReliefAmp 调制次/细节振幅。
        // 次/细节振幅 0.7/0.45：首版 0.5/0.25 localStd 0.0394 < HILLS 0.0410（山脉不再最崎岖）。
        double ampScale = 0.7 + beltReliefAmp * 0.85; // beltReliefAmp=0.35 → 1.0（默认行为）
        Noise mMain   = new Frequency(new Simplex(436), 1.0 / 700.0);
        Noise mSub    = new Boost(new Frequency(new Simplex(417), 1.0 / 220.0), 0.7 * ampScale);
        Noise mDet    = new Boost(new Frequency(new Simplex(442), 1.0 / 80.0), 0.45 * ampScale);
        Noise mBase   = new Add(new Add(mMain, mSub), mDet);   // 典型 [-2.15, 2.15]
        Noise mWX     = new Frequency(new Simplex(430), 1.0 / 150.0);
        Noise mWZ     = new Frequency(new Simplex(431), 1.0 / 150.0);
        Noise mWarped = new Warp(mBase, mWX, mWZ, 30.0);
        this.mountNoise = new Map(mWarped, -2.15, 2.15, 0.0, 1.0);
        // v8c：已移除 foldHills(1/50) 折叠细节——实机显示规律性条纹图案（高频折叠线主导），且山体过小

        // --- PLATEAU v8（2026-08-07 用户："你到底有没有认真看丘陵代码"）---
        // v7/v7.1/v7.2 迭代都是错层打转。真正参考丘陵：频率只比丘陵宽一点（1.25x），
        // 完全同结构（foldHills 全幅、Map(-1,1,0,1)），高原 vs 丘陵只在频率和 lo/hi。
        Noise pMain      = new Frequency(new Simplex(422), 1.0 / 500.0);
        Noise pSub       = new Boost(new Frequency(new Simplex(423), 1.0 / 150.0), 0.5);
        Noise pBase      = new Add(pMain, pSub);
        Noise pWX        = new Frequency(new Simplex(425), 1.0 / 100.0);
        Noise pWZ        = new Frequency(new Simplex(426), 1.0 / 100.0);
        Noise platWarped = new Warp(pBase, pWX, pWZ, 31.0);
        this.platNoise   = new Map(platWarped, -1.0, 1.0, 0.0, 1.0);

        // --- BASIN：修复与 shape 同 seed 问题 ---
        Noise bOct1 = new Frequency(new Simplex(428), 1.0 / 300.0);
        Noise bOct2 = new Boost(new Frequency(new Simplex(429), 1.0 / 100.0), 0.3);
        Noise bBase = new Add(bOct1, bOct2);
        Noise bInv = new Invert(bBase);
        this.basinNoise = new Map(bInv, -1.5, 1.5, 0.0, 0.6);
    }

    public void seed(long worldSeed) {
        Noises.seedAll(plainNoise, worldSeed, 0);
        Noises.seedAll(hillsNoise, worldSeed, 1);
        Noises.seedAll(mountNoise, worldSeed, 2);
        Noises.seedAll(platNoise,  worldSeed, 3);
        Noises.seedAll(basinNoise, worldSeed, 4);
    }

    /**
     * 绝对值折叠（用户方案 2026-08-01，修正版）：|2n-1| —— 原谷底（n→0）翻成峰、原峰顶
     * （n→1）保持峰、原中位（0.5）折叠成窄 V 沟 → 密集圆丘夹细沟，频率翻倍。
     * 注意：初版误用 1-|2n-1|（反相）把原峰顶翻成谷底（用户指出"包反成谷"），已修正。
     * 线性折叠保持均匀分布（高度分布不变），连续无断裂。
     */
    private static double foldHills(double n) {
        return Math.abs(2.0 * n - 1.0);
    }

    public double computeNoise(TerrainClass type, double wx, double wz) {
        return switch (type) {
            case PLAIN     -> plainNoise.compute(wx, wz);
            case HILLS     -> foldHills(hillsNoise.compute(wx, wz));
            case MOUNTAINS -> computeMountain(wx, wz);
            case PLATEAU   -> computePlateau(wx, wz);
            case BASIN     -> basinNoise.compute(wx, wz);
            default        -> 0.5;
        };
    }

    /**
     * v7（2026-08-06 用户方案，严格对照 HILLS）：PLATEAU = foldHills 丘链 + 顶部削平。
     * <p>
     * 与 HILLS 同链路：foldHills(|2n-1|) 产生丘谷交替（密集圆丘夹细沟），倍频放宽
     * （主频 1/1000 vs 丘陵 1/400）→ 丘体开阔；压缩 80%（×0.2 收向中位 0.50）→ 顶部削平，
     * 台地内部 ±0.1e 微起伏 = "丘陵倍频放宽 + 顶部平" 的高原特征。
     */
    // v7.2（2026-08-07 用户反馈"丘陵比高原自然"，要求"参考丘陵写法"）：
    // 直接返回 foldHills 全幅输出（与 HILLS 完全同结构），不压缩——丘沟结构完整保留。
    // 高原 vs 丘陵的差异只在频率（1/1000 vs 1/400 → 波长更长 → 视觉上丘沟更"缓"）+
    // platMod 轻微收敛（1.5 倍，比丘陵的无收敛略平）。"顶部平一点"由倍频放宽实现
    //（丘沟波长 200 块 vs 丘陵 80 块 → 相同振幅但斜率更低 = 更平缓）。
    private double computePlateau(double wx, double wz) {
        return foldHills(platNoise.compute(wx, wz)); // 全幅丘沟，不压缩——与 HILLS 同链路
    }

    /**
     * v8（2026-08-06 用户决策：参考丘陵配方）：山脉 = 多频叠加 + warp + foldHills 折叠。
     * 与 HILLS 同风格（自然连贯），尺度更大（主频 1/700）+ 细节破碎度（1/80）。
     * 已移除旧 MountainRidgeNetwork 脊线乘数（脊线外塌矮 65%、条状切割、±4000 边界突变）。
     */
    private double computeMountain(double wx, double wz) {
        // v8c：直接使用平滑多频噪声（无折叠）——丘陵式大圆润山体，1/700 主频 >> 丘陵 1/400
        return mountNoise.compute(wx, wz);
    }

    public static final TerrainClass[] LAND_TYPES = {
        TerrainClass.PLAIN, TerrainClass.HILLS,
        TerrainClass.MOUNTAINS, TerrainClass.PLATEAU,
        TerrainClass.BASIN
    };
}
