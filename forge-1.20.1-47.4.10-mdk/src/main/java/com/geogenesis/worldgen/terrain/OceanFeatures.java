package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;
import java.util.function.DoubleBinaryOperator;

/**
 * 海洋特征计算器 — 在深海区域叠加地质特征（洋中脊、海山等）。
 *
 * <p>在 CellGenerator.sample() 中 eOcean 计算后、eOcean+eLand 合并前调用。
 * 只返回 delta（≥0，仅抬升海床），由调用方叠加到 eOcean 并 clamp。
 * 仅在 eOcean < 0 时生效，不触及陆地。
 *
 * <p>海山中心水深检查（v2 新增）：每个潜在海山在生成前检查中心点的真实 eOcean
 * （不含海山增量）。若中心点水深不足（eOcean > -0.20），则跳过该海山。
 *
 * <p>海山形状（去圆化重构）：改用共享 VolcanicShape 库，含域扭曲 + 各向异性 +
 * 三种剖面（锥形 / 平顶 guyot / 环状 caldera）。修复旧 48 位哈希截断导致
 * radius 恒为 70、amp 与 chance 位重叠污染的 bug（形状 / 几何改用独立 64 位哈希）。
 */
public final class OceanFeatures {

    // ===== 洋中脊：Ridge(1/600) + Warp(200) =====
    private final Noise ridgeNoise;
    private final Noise ridgeMask; // 低频掩码 [0,1]，避免全球都有中脊

    // ===== 海山/海底火山：粗格点高斯鼓包 + 域扭曲去圆化 =====
    private final Noise seamountWarpX, seamountWarpZ; // 海山域扭曲 @1/200
    /** 粗格点间距（块）。每个格子约 40% 概率生成一座海山。 */
    private static final double SEAMOUNT_GRID = 500.0;
    /** 海山生成概率（0~65535 阈值） */
    private static final int SEAMOUNT_CHANCE = (int) (0.40 * 65536);
    /** 海山半径范围 [min, max) */
    private static final double SEAMOUNT_RADIUS_MIN = 70.0;
    private static final double SEAMOUNT_RADIUS_RANGE = 80.0; // 70~150
    /** 海山抬升幅度范围 [min, max)（e 单位） */
    private static final double SEAMOUNT_AMP_MIN = 0.08;
    private static final double SEAMOUNT_AMP_RANGE = 0.08; // 0.08~0.16
    private long seamountSeed;

    /**
     * 海山中心水深检查器。
     * 接受 (centerX, centerZ) → 该点的 eOcean（不含海山增量）。
     * 若返回的 eOcean > -0.20，海山中心不够深 -> 跳过。
     */
    private DoubleBinaryOperator seamountCenterDepthCheck;

    public OceanFeatures() {
        // 洋中脊脊线：Ridge(Simplex, 1/600, p=1.0)
        Noise simplex = new Simplex(701);
        Noise freq = new Frequency(simplex, 1.0 / 600.0);
        Noise ridge = new Ridge(freq, 1.0);
        // Warp 200 块使中脊蜿蜒
        Noise warpX = new Frequency(new Simplex(702), 1.0 / 300.0);
        Noise warpZ = new Frequency(new Simplex(703), 1.0 / 300.0);
        this.ridgeNoise = new Warp(ridge, warpX, warpZ, 200.0);
        // 低频掩码：Simplex(1/2000) → [0,1]
        Noise maskBase = new Frequency(new Simplex(704), 1.0 / 2000.0);
        this.ridgeMask = new Map(maskBase, -1.0, 1.0, 0.0, 1.0);
        // 海山域扭曲（去圆化）：低频 Simplex @1/200，幅度由每座 radius 缩放
        this.seamountWarpX = new Frequency(new Simplex(705), 1.0 / 200.0);
        this.seamountWarpZ = new Frequency(new Simplex(706), 1.0 / 200.0);
        // 默认：无检查（向后兼容）
        this.seamountCenterDepthCheck = null;
    }

    /**
     * 设置海山中心水深检查器。由 CellGenerator 注入。
     * @param checker (centerX, centerZ) → 该点的预海山 eOcean 值
     */
    public void setSeamountDepthChecker(DoubleBinaryOperator checker) {
        this.seamountCenterDepthCheck = checker;
    }

    /** 海洋特征计算结果（分离各分量，供 CellGenerator 分类用） */
    public static final class FeatureResult {
        public final double total;     // 总增量
        public final double ridge;     // 洋中脊增量
        public final double seamount;  // 海山增量
        public FeatureResult(double total, double ridge, double seamount) {
            this.total = total;
            this.ridge = ridge;
            this.seamount = seamount;
        }
    }

    /** 播种所有噪声节点 + 海山种子偏移 */
    public void seed(long worldSeed) {
        Noises.seedAll(ridgeNoise, worldSeed, 0);
        Noises.seedAll(ridgeMask, worldSeed, 0);
        Noises.seedAll(seamountWarpX, worldSeed, 0);
        Noises.seedAll(seamountWarpZ, worldSeed, 0);
        this.seamountSeed = worldSeed + 987654321L;
    }

    /**
     * 计算海洋特征增量 delta。
     *
     * @param wx      世界 X 坐标
     * @param wz      世界 Z 坐标
     * @param eOcean  当前海洋基面 e ∈ [-1, 0]
     * @param cBiased 偏置大陆性（仅保留签名兼容，未用）
     * @return FeatureResult 包含 total / ridge / seamount 分量
     */
    public FeatureResult compute(double wx, double wz, double eOcean, double cBiased) {
        if (eOcean >= 0) return new FeatureResult(0, 0, 0);

        double ridgeDelta = 0.0;
        double seamountDelta = 0.0;

        // 1. 洋中脊：smoothstep 平滑淡入（eOcean ≥ -0.08 无脊，≤ -0.25 全幅）
        double ridgeFade = eOcean < -0.08 ? smoothstep(-0.08, -0.25, eOcean) : 0.0;
        if (ridgeFade > 0) {
            double mask = ridgeMask.compute(wx, wz); // [0, 1]
            if (mask > 0.3) {
                double ridge = ridgeNoise.compute(wx, wz); // [0, 1]
                double strength = (mask - 0.3) / 0.7; // 0.3→0, 1.0→1.0
                ridgeDelta = 0.18 * strength * ridge * ridgeFade;
            }
        }

        // 2. 海山/海底火山（域扭曲去圆化 + 共享 VolcanicShape 形状）
        seamountDelta = seamountCompute(wx, wz);

        return new FeatureResult(ridgeDelta + seamountDelta, ridgeDelta, seamountDelta);
    }

    /**
     * 海山场：粗格点确定性鼓包，域扭曲去圆化 + 各向异性 + 三剖面。
     * 搜索邻域 3×3 格子叠加贡献；海山中心深水检查（仅深水中心才生成）。
     */
    private double seamountCompute(double wx, double wz) {
        long cx = (long) Math.floor(wx / SEAMOUNT_GRID);
        long cz = (long) Math.floor(wz / SEAMOUNT_GRID);

        double total = 0.0;
        for (long dx = -1; dx <= 1; dx++) {
            for (long dz = -1; dz <= 1; dz++) {
                long h = hashCell(cx + dx, cz + dz);
                if ((h & 0xFFFF) >= SEAMOUNT_CHANCE) continue;
                double centerX = cellCenter(cx + dx, (h >>> 16) & 0xFFFF);
                double centerZ = cellCenter(cz + dz, (h >>> 32) & 0xFFFF);

                // 海山中心水深检查：只有深水中心才允许生成
                if (seamountCenterDepthCheck != null) {
                    double eOceanAtCenter = seamountCenterDepthCheck.applyAsDouble(centerX, centerZ);
                    if (eOceanAtCenter > -0.20) continue;
                }

                // 形状 / 几何：独立 64 位哈希（修复旧 48 位截断 radius 恒 70 的 bug）
                long hg = hashCell64(cx + dx, cz + dz, seamountSeed + 9001L);
                int shapeType = (int) (hg & 0x3) % 3; // 0 CONE, 1 GUYOT, 2 CALDERA
                double ang = ((hg >>> 2) & 0x3FF) / 1024.0 * Math.PI;
                double asx = 0.6 + ((hg >>> 12) & 0xFF) / 255.0 * 0.8;
                double asz = 0.6 + ((hg >>> 20) & 0xFF) / 255.0 * 0.8;
                double radius = SEAMOUNT_RADIUS_MIN + ((hg >>> 28) & 0xFFFF) / 65536.0 * SEAMOUNT_RADIUS_RANGE;
                double amp = SEAMOUNT_AMP_MIN + ((hg >>> 44) & 0xFF) / 255.0 * SEAMOUNT_AMP_RANGE;

                // 域扭曲（幅度正比 radius，频率固定 1/200 防 Jacobian 折叠）
                double wamp = radius * 0.4;
                double wx2 = wx + seamountWarpX.compute(wx, wz) * wamp;
                double wz2 = wz + seamountWarpZ.compute(wx, wz) * wamp;
                double[] local = VolcanicShape.anisoRotate(wx2 - centerX, wz2 - centerZ, ang, asx, asz);
                double d = Math.hypot(local[0], local[1]) / radius;
                if (d >= 1.0) continue;
                double contrib = VolcanicShape.profile(shapeType, d, amp); // caldera 已 clamp≥0
                if (contrib > 0.0) total += contrib;
            }
        }
        return total;
    }

    /** 格子内中心位置抖动（±30% 格子范围） */
    private static double cellCenter(long cell, long bits) {
        return cell * SEAMOUNT_GRID + (bits / 65536.0 - 0.5) * SEAMOUNT_GRID * 0.6;
    }

    /** smoothstep 平滑过渡：x 在 [edge0, edge1] 间做 Hermite 插值 */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = x <= edge0 ? 0.0 : (x >= edge1 ? 1.0 : (x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    /** 确定性哈希（48 位，混合世界种子；仅用于 chance/center，行为保持旧分布） */
    private long hashCell(long cx, long cz) {
        long h = seamountSeed + cx * 374761393L + cz * 668265263L;
        h = h ^ (h >>> 13);
        h = h * 1274126177L;
        h = h ^ (h >>> 16);
        return h & 0x0000FFFFFFFFFFFFL;
    }

    /** 确定性 64 位哈希（独立 salt，供形状 / 几何，无截断） */
    private static long hashCell64(long cx, long cz, long salt) {
        long h = salt + cx * 374761393L + cz * 668265263L;
        h = h ^ (h >>> 13);
        h = h * 1274126177L;
        h = h ^ (h >>> 16);
        return h;
    }
}
