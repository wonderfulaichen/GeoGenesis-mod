package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

public final class TypeNoiseProvider {

    private final Noise plainNoise;

    // v6.0: HILLS — 参考 TF hills_1.json 双层 Warp + Perlin×Billow^0.5
    private final Noise hillsNoise;

    private final Noise mountShape;
    private final Noise mountRidge;
    private final Noise mountValleyFloor;
    private final Noise mountDetail;
    private final Noise mountDirAngle;
    private final Noise mountDirJitterX;
    private final Noise mountDirJitterZ;

    private final Noise platNoise;
    private final Noise basinNoise;

    private MountainRidgeNetwork ridgeNetwork;
    private long lastSeed = 0L;

    public TypeNoiseProvider() {
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

        // --- MOUNTAINS: 浅山脊骨架(1/1500) + 主脊线(1/480) + 次脊线(1/180) ---
        Noise mRSkel = new Boost(new Ridge(new Frequency(new Simplex(436), 1.0 / 1500.0), 1.0), 0.25);
        Noise mR1 = new Ridge(new Frequency(new Simplex(416), 1.0 / 480.0), 1.0);
        Noise mR2 = new Boost(new Ridge(new Frequency(new Simplex(417), 1.0 / 180.0), 1.0), 0.4);
        Noise mRSum = new Add(new Add(mRSkel, mR1), mR2);
        Noise mRCombined = new Map(mRSum, 0.0, 1.8, 0.0, 1.0);
        this.mountRidge = mRCombined;

        // Shape mask（蒙山形状）
        Noise sOct1 = new Frequency(new Simplex(425), 1.0 / 800.0);
        Noise sOct2 = new Boost(new Frequency(new Simplex(426), 1.0 / 400.0), 0.4);
        Noise sOct3 = new Boost(new Frequency(new Simplex(427), 1.0 / 150.0), 0.2);
        this.mountShape = new Map(new Add(new Add(sOct1, sOct2), sOct3), -1.6, 1.6, 0.20, 0.95);

        // Valley 谷底基线
        Noise mValley = new Frequency(new Simplex(420), 1.0 / 250.0);
        this.mountValleyFloor = new Map(mValley, -1.0, 1.0, 0.12, 0.40);

        // 高频细节
        Noise mDetR = new Ridge(new Frequency(new Simplex(421), 1.0 / 80.0), 1.0);
        this.mountDetail = new Boost(mDetR, 0.2);

        // 方向扭曲
        this.mountDirAngle = new Frequency(new Simplex(430), 1.0 / 600.0);
        this.mountDirJitterX = new Frequency(new Simplex(431), 1.0 / 400.0);
        this.mountDirJitterZ = new Frequency(new Simplex(432), 1.0 / 400.0);

        // --- PLATEAU v6.0: 3 层 Simplex + shape mask 边缘渐变 ---
        // 参考 TF: ramp_height=0.35 — 高原边缘 35% 平滑过渡
        Noise platOct1 = new Frequency(new Simplex(422), 1.0 / 500.0);
        Noise platOct2 = new Boost(new Frequency(new Simplex(423), 1.0 / 200.0), 0.25);
        Noise platOct3 = new Boost(new Frequency(new Simplex(424), 1.0 / 60.0), 0.08);
        this.platNoise = new Map(new Add(new Add(platOct1, platOct2), platOct3), -1.5, 1.5, 0.30, 0.70);

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
        Noises.seedAll(mountShape, worldSeed, 2);
        Noises.seedAll(mountRidge, worldSeed, 2);
        Noises.seedAll(mountValleyFloor, worldSeed, 2);
        Noises.seedAll(mountDetail, worldSeed, 2);
        Noises.seedAll(mountDirAngle, worldSeed, 2);
        Noises.seedAll(mountDirJitterX, worldSeed, 2);
        Noises.seedAll(mountDirJitterZ, worldSeed, 2);
        Noises.seedAll(platNoise,  worldSeed, 3);
        Noises.seedAll(basinNoise, worldSeed, 4);

        if (ridgeNetwork == null || lastSeed != worldSeed) {
            ridgeNetwork = new MountainRidgeNetwork(worldSeed);
            lastSeed = worldSeed;
        }
    }

    public double computeNoise(TerrainClass type, double wx, double wz) {
        return switch (type) {
            case PLAIN     -> plainNoise.compute(wx, wz);
            case HILLS     -> hillsNoise.compute(wx, wz);
            case MOUNTAINS -> computeMountain(wx, wz);
            case PLATEAU   -> computePlateau(wx, wz);
            case BASIN     -> basinNoise.compute(wx, wz);
            default        -> 0.5;
        };
    }

    /**
     * v6.0→fix: PLATEAU 形状直接由 platNoise 决定，与 typeWeights 同源
     * （Voronoi 类型场，频率 1/500）驱动。
     * <p>
     * 移除旧的大尺度 shape mask（platShapeNoise，频率 1/1000）后，高原主体高度由
     * typeWeights[PLATEAU] 在类型场中心自动取全值、在 Voronoi 边界向 PLAIN/HILLS 平滑过渡，
     * 不再出现「分类中心恰好落在 shape 低谷 → 中间凹陷、周围雪峰环绕」的环形山伪形。
     * platNoise 自身为 [0.30, 0.70] 的中频起伏，保留高原台地应有的内部起伏。
     */
    private double computePlateau(double wx, double wz) {
        double noise = platNoise.compute(wx, wz);    // [0.30, 0.70] 高原台地起伏
        return noise < 0 ? 0 : (noise > 1 ? 1 : noise);
    }

    /**
     * v7 (Phase 1.5)：三级形态山地公式。
     * <p>
     * 旧公式（v6）：v = shape*0.5 + shape*ridge*0.5，shape 为平滑 blob 直接做高度主体，
     * 导致"快升到中→慢爬到顶"的曲线（中等 shape 区域占比最大，峰顶变化缓慢）。
     * <p>
     * 新公式：shape→山脉存在度(presence)，三级形态自动涌现：
     * <pre>
     *   1. 谷底（presence→0）：valleyFloor（0.12–0.40），缓坡
     *   2. 陡升（presence 0.3→0.7）：presence² 加速上升
     *   3. 脊线（presence→1）：ridge+detail 主导，ridgeBoost 调制
     * </pre>
     */
    private double computeMountain(double wx, double wz) {
        double angle = mountDirAngle.compute(wx, wz) * Math.PI;
        double jx = mountDirJitterX.compute(wx, wz) * 15.0;
        double jz = mountDirJitterZ.compute(wx, wz) * 15.0;
        double rwx = wx + Math.cos(angle) * 50.0 + jx;
        double rwz = wz + Math.sin(angle) * 50.0 + jz;

        double shape = mountShape.compute(rwx, rwz);               // [0.20, 0.95] blob
        double ridgeRaw = mountRidge.compute(rwx, rwz);
        double ridge = Math.max(0, (ridgeRaw - 0.4) / 0.6);        // [0, 1]
        double detail = mountDetail.compute(rwx, rwz);             // [0, 0.2]
        double valleyFloor = mountValleyFloor.compute(rwx, rwz);   // [0.12, 0.40]

        // shape → presence（smoothstep from [0.20, 0.95] to [0, 1]）
        double t = (shape - 0.20) / 0.75;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        double presence = t * t * (3.0 - 2.0 * t);                 // smoothstep

        // 三级形态：谷底 + presence² 加速上升 → 脊线目标
        double rise = presence * presence;                         // 二次加速
        double ridgeTarget = 0.30 + Math.min(0.70, ridge * 0.7 + detail * 0.3);
        double v = valleyFloor * (1.0 - rise) + rise * ridgeTarget;

        if (ridgeNetwork != null) {
            double boost = ridgeNetwork.ridgeBoost(wx, wz);
            v *= (0.35 + 0.65 * boost);
        }
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public static final TerrainClass[] LAND_TYPES = {
        TerrainClass.PLAIN, TerrainClass.HILLS,
        TerrainClass.MOUNTAINS, TerrainClass.PLATEAU,
        TerrainClass.BASIN
    };
}
