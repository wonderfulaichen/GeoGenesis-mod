package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 每种地形类型独立噪声配方（v6 重写）。
 * <p>
 * 参考 TerraForged / ReTerraForged / RTG 的关键设计：
 * <ul>
 *   <li>PLAIN — 极低频 Simplex，真正平坦（TF: scale 0.08-0.15 Perlin）</li>
 *   <li>HILLS — 低频 Billow + 弱 Warp，圆润起伏（TF: Billow+Warp+Perlin）</li>
 *   <li>MOUNTAINS — shape×peaks + SCA 网络（TF: Ridge+Valley+DomainWarp）</li>
 *   <li>PLATEAU — Terrace 4级台阶 + 极低振幅顶部（TF: Terrace 4steps ramp=0.9）</li>
 *   <li>BASIN — Invert 噪声，天然凹陷（TF: Invert(Perlin)）</li>
 * </ul>
 */
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
        // =====================================================================
        // PLAIN: 极低频 1/1000 + 极弱 Warp(30)，真正平坦
        // 参考 TF: scale 0.08-0.15 Perlin → 微小起伏
        // =====================================================================
        Noise pSim = new Frequency(new Simplex(410), 1.0 / 1000.0);
        Noise pWarpX = new Frequency(new Simplex(433), 1.0 / 600.0);
        Noise pWarpZ = new Frequency(new Simplex(434), 1.0 / 600.0);
        Noise pWarped = new Warp(pSim, pWarpX, pWarpZ, 30.0);
        this.plainNoise = new Map(pWarped, -1.0, 1.0, 0.45, 0.55);

        // =====================================================================
        // HILLS: 低频 Billow(1/800) + 中频 Billow(1/500) + 弱 Warp(80)
        // 参考 TF: Billow+Warp 圆润起伏，频率 1/500-1/800
        // 关键：Billow 比 Simplex 更圆润（|x| 取绝对值）
        // =====================================================================
        Noise hBillow1 = new Billow(new Frequency(new Simplex(412), 1.0 / 800.0));
        Noise hBillow2 = new Boost(new Billow(new Frequency(new Simplex(413), 1.0 / 500.0)), 0.4);
        Noise hBase = new Add(hBillow1, hBillow2);
        Noise hWarpX = new Frequency(new Simplex(414), 1.0 / 900.0);
        Noise hWarpZ = new Frequency(new Simplex(415), 1.0 / 900.0);
        Noise hWarped = new Warp(hBase, hWarpX, hWarpZ, 80.0);
        // 范围 [-2,2] → [0,1]，Billow 输出偏高所以 map 范围宽
        this.hillsNoise = new Map(hWarped, -2.0, 2.0, 0.0, 1.0);

        // =====================================================================
        // MOUNTAINS: shape×peaks 模式（TF dolomites 风格）
        // =====================================================================
        // Ridge 主脊线（3 频叠加：1/1500 骨架 + 1/480 主 + 1/180 细节）
        Noise mRSkel = new Boost(new Ridge(new Frequency(new Simplex(436), 1.0 / 1500.0), 1.0), 0.25);
        Noise mR1 = new Ridge(new Frequency(new Simplex(416), 1.0 / 480.0), 1.0);
        Noise mR2 = new Boost(new Ridge(new Frequency(new Simplex(417), 1.0 / 180.0), 1.0), 0.4);
        Noise mRSum = new Add(new Add(mRSkel, mR1), mR2);
        Noise mRCombined = new Map(mRSum, 0.0, 1.8, 0.0, 1.0);
        this.mountRidge = mRCombined;

        // Shape mask（极低频 3 八度 FBM，决定"哪里有山"）
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

        // =====================================================================
        // PLATEAU: Terrace 4级台阶（TF/RTF 风格）+ 极低振幅顶部
        // 参考 TF: Terrace(steps=4, blend_range=0.4, ramp=0.9, cliff=0.15)
        // 关键：ramp=0.9 → 90% 台阶面平坦，cliff=0.15 → 10% 陡坡
        //       blend_range=0.4 → 台阶间极宽过渡
        // =====================================================================
        // 底层形状：极低频 Simplex + Warp（高原的大尺度起伏）
        Noise platShape = new Frequency(new Simplex(422), 1.0 / 700.0);
        Noise platWarpX = new Frequency(new Simplex(450), 1.0 / 500.0);
        Noise platWarpZ = new Frequency(new Simplex(451), 1.0 / 500.0);
        Noise platWarped = new Warp(platShape, platWarpX, platWarpZ, 120.0);

        // Terrace 4级台阶（strength=0.5 = 适度台阶，非硬切）
        Noise platTerrace = new Terrace(platWarped, 4, 0.5);

        // 顶部微扰：极低振幅（参考 RTF: 0.0275 amplitude）
        Noise platTopDet = new Boost(new Frequency(new Simplex(452), 1.0 / 150.0), 0.04);
        // 合成：Terrace + 顶部微扰
        Noise platCombined = new Add(platTerrace, platTopDet);
        // 映射到 [0.35, 0.65]（中高区域，低于山脉）
        this.platNoise = new Map(platCombined, -1.5, 1.5, 0.35, 0.65);

        // =====================================================================
        // BASIN: Invert 噪声（TF 风格）
        // =====================================================================
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
        // 方向扭曲（让山脊沿特定方向延伸）
        double angle = mountDirAngle.compute(wx, wz) * Math.PI;
        double jx = mountDirJitterX.compute(wx, wz) * 40.0;
        double jz = mountDirJitterZ.compute(wx, wz) * 40.0;
        double rwx = wx + Math.cos(angle) * 120.0 + jx;
        double rwz = wz + Math.sin(angle) * 120.0 + jz;

        // Shape mask（决定哪里有山）
        double shape = mountShape.compute(rwx, rwz);  // [0.20, 0.95]

        // Ridge step function（让山脊线有锐利过渡）
        double ridgeRaw = mountRidge.compute(rwx, rwz);  // [0, 1]
        double ridge = Math.max(0, (ridgeRaw - 0.4) / 0.6);  // [0.4, 1.0] → [0, 1]

        // base 主导 + 小幅 peak
        double v = shape * 0.7 + shape * ridge * 0.25;

        // SCA 山脊网络 boost（乘法放大器）
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
