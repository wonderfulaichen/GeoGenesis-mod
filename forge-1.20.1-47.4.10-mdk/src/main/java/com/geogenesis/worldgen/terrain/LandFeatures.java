package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 陆地火山特征 — 在陆地侧叠加火山地貌（与海洋海山共用 VolcanicShape 形状数学）。
 *
 * <p>两级布点：
 * <ul>
 *   <li>单体火山 single：粗格点 (~800 块) 低概率 (~18%) 散布高锥，含真实下凹火口
 *       （环峰高、中心洼），可形成火山口湖。</li>
 *   <li>火山群 field：低频掩码 (~1/4000) 圈出火山区，区内细格点 (~200 块) 高概率
 *       散布许多小火山锥，即现实中的火山群 / 火山区（volcanic field）。</li>
 * </ul>
 *
 * <p>海陆门控由 CellGenerator 的 landMask 统一施加（本类只产出未门控增量），
 * 使火山不长进海里、海岸带平滑过渡。
 */
public final class LandFeatures {
    private final Noise singleWarpX, singleWarpZ;  // 单体火山域扭曲 @1/200
    private final Noise fieldMask;                  // 火山群区域掩码 @1/4000
    private final Noise fieldWarpX, fieldWarpZ;     // 火山群小锥域扭曲 @1/400
    private long singleSeed, fieldSeed;

    // ===== 单体火山（地标级） =====
    private static final double SINGLE_GRID = 800.0;
    private static final int SINGLE_CHANCE = (int) (0.03 * 65536);   // ~3% → 地标级稀有
    private static final double SINGLE_RADIUS_MIN = 120.0, SINGLE_RADIUS_RANGE = 140.0; // 120~260
    private static final double SINGLE_AMP_MIN = 0.12, SINGLE_AMP_RANGE = 0.18;          // 0.12~0.30
    private static final double SINGLE_CRATER_MIN = 0.02, SINGLE_CRATER_RANGE = 0.05;    // 0.02~0.07

    // ===== 火山群（极罕见） =====
    private static final double FIELD_MASK_FREQ = 1.0 / 4000.0;
    private static final double FIELD_MASK_THRESHOLD = 0.72; // 更高掩码 → 更小覆盖 (~1.4% 陆地)
    private static final double FIELD_GRID = 200.0;
    private static final int FIELD_CHANCE = (int) (0.12 * 65536);   // ~12% → 区内更稀疏
    private static final double FIELD_RADIUS_MIN = 40.0, FIELD_RADIUS_RANGE = 50.0;      // 40~90
    private static final double FIELD_AMP_MIN = 0.05, FIELD_AMP_RANGE = 0.07;            // 0.05~0.12（更像火山锥）

    public LandFeatures() {
        singleWarpX = new Frequency(new Simplex(801), 1.0 / 200.0);
        singleWarpZ = new Frequency(new Simplex(802), 1.0 / 200.0);
        fieldMask = new Frequency(new Simplex(803), FIELD_MASK_FREQ);
        fieldWarpX = new Frequency(new Simplex(804), 1.0 / 400.0);
        fieldWarpZ = new Frequency(new Simplex(805), 1.0 / 400.0);
    }

    public void seed(long worldSeed) {
        singleSeed = worldSeed + 111111111L;
        fieldSeed = worldSeed + 222222222L;
        Noises.seedAll(singleWarpX, worldSeed, 0);
        Noises.seedAll(singleWarpZ, worldSeed, 0);
        Noises.seedAll(fieldMask, worldSeed, 0);
        Noises.seedAll(fieldWarpX, worldSeed, 0);
        Noises.seedAll(fieldWarpZ, worldSeed, 0);
    }

    /** 陆地火山计算结果（分离单体 / 群分量，供 CellGenerator 分类用） */
    public static final class FeatureResult {
        public final double total, single, field;
        FeatureResult(double t, double s, double f) { total = t; single = s; field = f; }
    }

    public FeatureResult compute(double wx, double wz) {
        double single = singleCompute(wx, wz);
        double field = fieldCompute(wx, wz);
        return new FeatureResult(single + field, single, field);
    }

    private double singleCompute(double wx, double wz) {
        long cx = (long) Math.floor(wx / SINGLE_GRID);
        long cz = (long) Math.floor(wz / SINGLE_GRID);
        double total = 0.0;
        for (long dx = -1; dx <= 1; dx++) {
            for (long dz = -1; dz <= 1; dz++) {
                long h = hashCell(cx + dx, cz + dz, singleSeed);
                if ((h & 0xFFFF) >= SINGLE_CHANCE) continue;
                double centerX = cellCenter(cx + dx, (h >>> 16) & 0xFFFF, SINGLE_GRID);
                double centerZ = cellCenter(cz + dz, (h >>> 32) & 0xFFFF, SINGLE_GRID);
                // 形状 / 几何：独立 salt 的 64 位哈希，避免与 chance/center 位重叠
                long hg = hashCell(cx + dx, cz + dz, singleSeed ^ 0x9E3779B1L);
                int shapeType = (int) (hg & 0x3) % 2; // 0 CONE(strato), 1 GUYOT(shield)
                double ang = ((hg >>> 2) & 0x3FF) / 1024.0 * Math.PI;
                double asx = 0.6 + ((hg >>> 12) & 0xFF) / 255.0 * 0.8;
                double asz = 0.6 + ((hg >>> 20) & 0xFF) / 255.0 * 0.8;
                double radius = SINGLE_RADIUS_MIN + ((hg >>> 28) & 0xFFFF) / 65536.0 * SINGLE_RADIUS_RANGE;
                double amp = SINGLE_AMP_MIN + ((hg >>> 44) & 0xFF) / 255.0 * SINGLE_AMP_RANGE;
                double crater = SINGLE_CRATER_MIN + ((hg >>> 52) & 0xFF) / 255.0 * SINGLE_CRATER_RANGE;
                // 域扭曲（幅度正比 radius，频率固定 1/200 防 Jacobian 折叠）
                double wamp = radius * 0.4;
                double wx2 = wx + singleWarpX.compute(wx, wz) * wamp;
                double wz2 = wz + singleWarpZ.compute(wx, wz) * wamp;
                double[] local = VolcanicShape.anisoRotate(wx2 - centerX, wz2 - centerZ, ang, asx, asz);
                double d = Math.hypot(local[0], local[1]) / radius;
                if (d >= 1.0) continue;
                double contrib = VolcanicShape.profile(shapeType, d, amp);
                contrib -= VolcanicShape.crater(d, 0.25, crater); // 顶部下凹火口
                if (contrib > 0.0) total += contrib;
            }
        }
        return total;
    }

    private double fieldCompute(double wx, double wz) {
        // 先判断是否落在火山区内（低频掩码圈域）
        double mask = fieldMask.compute(wx, wz) * 0.5 + 0.5; // [-1,1] → [0,1]
        if (mask < FIELD_MASK_THRESHOLD) return 0.0;
        long cx = (long) Math.floor(wx / FIELD_GRID);
        long cz = (long) Math.floor(wz / FIELD_GRID);
        double total = 0.0;
        for (long dx = -1; dx <= 1; dx++) {
            for (long dz = -1; dz <= 1; dz++) {
                long h = hashCell(cx + dx, cz + dz, fieldSeed);
                if ((h & 0xFFFF) >= FIELD_CHANCE) continue;
                double centerX = cellCenter(cx + dx, (h >>> 16) & 0xFFFF, FIELD_GRID);
                double centerZ = cellCenter(cz + dz, (h >>> 32) & 0xFFFF, FIELD_GRID);
                long hg = hashCell(cx + dx, cz + dz, fieldSeed ^ 0x9E3779B1L);
                double ang = ((hg >>> 2) & 0x3FF) / 1024.0 * Math.PI;
                double asx = 0.7 + ((hg >>> 12) & 0xFF) / 255.0 * 0.6;
                double asz = 0.7 + ((hg >>> 20) & 0xFF) / 255.0 * 0.6;
                double radius = FIELD_RADIUS_MIN + ((hg >>> 28) & 0xFFFF) / 65536.0 * FIELD_RADIUS_RANGE;
                double amp = FIELD_AMP_MIN + ((hg >>> 44) & 0xFF) / 255.0 * FIELD_AMP_RANGE;
                double wamp = radius * 0.4;
                double wx2 = wx + fieldWarpX.compute(wx, wz) * wamp;
                double wz2 = wz + fieldWarpZ.compute(wx, wz) * wamp;
                double[] local = VolcanicShape.anisoRotate(wx2 - centerX, wz2 - centerZ, ang, asx, asz);
                double d = Math.hypot(local[0], local[1]) / radius;
                if (d >= 1.0) continue;
                double contrib = VolcanicShape.profile(VolcanicShape.CONE, d, amp);
                contrib -= VolcanicShape.crater(d, 0.3, amp * 0.4); // 小火口
                if (contrib > 0.0) total += contrib;
            }
        }
        return total;
    }

    /** 格子内中心位置抖动（±30% 格子范围） */
    private static double cellCenter(long cell, long bits, double grid) {
        return cell * grid + (bits / 65536.0 - 0.5) * grid * 0.6;
    }

    /** 确定性 64 位哈希（混合 salt，不同 salt 取不同随机维度，避免位重叠） */
    private static long hashCell(long cx, long cz, long salt) {
        long h = salt + cx * 374761393L + cz * 668265263L;
        h = h ^ (h >>> 13);
        h = h * 1274126177L;
        h = h ^ (h >>> 16);
        return h;
    }
}
