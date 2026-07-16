package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

public final class TypeNoiseProvider {

    private final Noise plainNoise;
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

        // --- HILLS: 3 八度 Billow（1/700 + 1/420 + 1/140）+ 弱 warp，宽大起伏 ---
        Noise hBillow1 = new Billow(new Frequency(new Simplex(412), 1.0 / 420.0));
        Noise hBillow2 = new Boost(new Billow(new Frequency(new Simplex(413), 1.0 / 140.0)), 0.35);
        Noise hBillow3 = new Boost(new Billow(new Frequency(new Simplex(435), 1.0 / 700.0)), 0.5);
        Noise hBase = new Add(new Add(hBillow1, hBillow2), hBillow3);
        Noise hWarpX = new Frequency(new Simplex(414), 1.0 / 700.0);
        Noise hWarpZ = new Frequency(new Simplex(415), 1.0 / 700.0);
        Noise hWarped = new Warp(hBase, hWarpX, hWarpZ, 120.0);
        this.hillsNoise = new Map(hWarped, -2.5, 2.5, 0.0, 1.0);

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

        // --- PLATEAU ---
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
            case PLATEAU   -> platNoise.compute(wx, wz);
            case BASIN     -> basinNoise.compute(wx, wz);
            default        -> 0.5;
        };
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
