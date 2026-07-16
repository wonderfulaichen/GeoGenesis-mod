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
    // v6.0: PLATEAU shape mask — 大尺度噪声控制高原分布 + 边缘渐变
    private final Noise platShapeNoise;
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

        // --- HILLS v6.3: 2 层低频 Simplex 叠加（高频会放大断裂） ---
        // v6.0 根因：Multiply(Perlin, Power(Billow, 0.5)) 在 Billow 零交叉点产生尖刺（89.7 块）
        // v6.1 修复：纯 Add + Warp 但仍有 51 块
        // v6.2 修复：3 层 Simplex（600/250/100）→ 4.8 块（最高频 1/100 仍是元凶）
        // v6.3 修复：2 层低频（800/300）→ 理论 max Δe ≈ 0.005（2 块）
        Noise hLow       = new Boost(new Frequency(new Simplex(412), 1.0 / 800.0), 0.7);
        Noise hMid       = new Boost(new Frequency(new Simplex(413), 1.0 / 300.0), 0.3);
        Noise hBase      = new Add(hLow, hMid);                              // [-1, 1] 平滑
        // 单次小距离 Warp（distance=40，freq=1/250）让边缘略不规则
        Noise hWX        = new Frequency(new Simplex(415), 1.0 / 250.0);
        Noise hWZ        = new Frequency(new Simplex(416), 1.0 / 250.0);
        Noise hWarped    = new Warp(hBase, hWX, hWZ, 40.0);
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
        // 大尺度 shape mask：控制高原分布区域 + 边缘渐变
        this.platShapeNoise = new Map(
            new Frequency(new Simplex(437), 1.0 / 1000.0), -1.0, 1.0, 0.0, 1.0);

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
        Noises.seedAll(platShapeNoise, worldSeed, 5);
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
     * v6.0: PLATEAU 边缘渐变（参考 TF ramp_height=0.35）。
     * shape ∈ [0,1]：低值=无高原、高值=全高原。
     * edge = smoothstep(0.15, 0.40, shape)：在边缘 0.15–0.40 之间平滑过渡。
     * 输出：noise×edge + 0.08×(1−edge)，边缘降至 0.08（近平原）。
     */
    private double computePlateau(double wx, double wz) {
        double noise = platNoise.compute(wx, wz);
        double shape = platShapeNoise.compute(wx, wz);    // [0, 1]
        // smooth edge ramp: shape<0.15→0, shape>0.40→1
        double t = (shape - 0.15) / 0.25;                   // [−0.6, 3.4]
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        double edge = t * t * (3.0 - 2.0 * t);              // smoothstep
        // 混合：边缘低地 0.08，中心全高原高度
        double result = noise * edge + 0.08 * (1.0 - edge);
        return result < 0 ? 0 : (result > 1 ? 1 : result);
    }

    private double computeMountain(double wx, double wz) {
        double angle = mountDirAngle.compute(wx, wz) * Math.PI;
        double jx = mountDirJitterX.compute(wx, wz) * 40.0;
        double jz = mountDirJitterZ.compute(wx, wz) * 40.0;
        double rwx = wx + Math.cos(angle) * 120.0 + jx;
        double rwz = wz + Math.sin(angle) * 120.0 + jz;

        double shape = mountShape.compute(rwx, rwz);
        double ridgeRaw = mountRidge.compute(rwx, rwz);
        double ridge = Math.max(0, (ridgeRaw - 0.4) / 0.6);
        double v = shape * 0.7 + shape * ridge * 0.25;

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
