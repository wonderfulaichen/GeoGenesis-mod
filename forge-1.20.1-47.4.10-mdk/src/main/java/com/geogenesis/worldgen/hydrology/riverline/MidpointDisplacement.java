package com.geogenesis.worldgen.hydrology.riverline;

import com.geogenesis.worldgen.noise.NoiseUtil;

/**
 * 确定性中点位移分形折线 + 节点贴谷偏置。
 *
 * <p>参照 DynamicWaters MidpointFractal：起点/终点固定，中点沿法向
 * 随机偏移（幅度 = 段长 × jitter），递归二分 levels 次。
 * 偏移量由 (worldSeed, region, 节点索引) 整数哈希导出 → 同 seed 同线。</p>
 *
 * <p>贴谷适配：每个节点生成后向局部 e 最低方向偏移
 * （采样 3×3 邻域，梯度下降一步），使河线贴着噪声地形低谷走。</p>
 */
public final class MidpointDisplacement {
    private MidpointDisplacement() { }

    /** 海洋 e 阈值：贴谷偏置不向低于此值的邻域下降（避免河线被拉入海）。 */
    private static final double OCEAN_E = -0.02;

    /** 折线节点。 */
    public record Node(double x, double z) { }

    /**
     * 生成一条分形蜿蜒折线（含端点，节点数 = 2^levels + 1）。
     *
     * @param seed    世界种子
     * @param salt    区域盐（region 坐标哈希），同 region 内不同边用不同 salt
     * @param ax/az   起点
     * @param bx/bz   终点
     * @param params  参数（jitter/levels/valleyBiasAmp）
     * @param eSampler 地形 e 采样器（wu 坐标）→ 贴谷偏置
     */
    public static Node[] generate(long seed, long salt,
                                  double ax, double az,
                                  double bx, double bz,
                                  RiverLineParams params,
                                  ElevationSampler eSampler) {
        int n = (1 << params.fractalLevels()) + 1;
        double[] xs = new double[n];
        double[] zs = new double[n];
        xs[0] = ax; zs[0] = az;
        xs[n - 1] = bx; zs[n - 1] = bz;
        double segmentAmp = distance(ax, az, bx, bz) * params.jitter();
        int span = n - 1;
        int layer = 0;
        while (span > 1) {
            int half = span / 2;
            for (int i = half; i < n - 1; i += span) {
                if (isSet(xs, zs, i)) continue;
                double mx = (xs[i - half] + xs[i + half]) * 0.5;
                double mz = (zs[i - half] + zs[i + half]) * 0.5;
                // 法向偏移（确定性哈希 → [-1,1]；hashLong01 返回 [0,1)，×2−1 映射）
                double h = NoiseUtil.hashLong01(seed,
                        salt ^ ((long) i << 20) ^ ((long) layer << 44)) * 2.0 - 1.0;
                double dx = bx - ax, dz = bz - az;
                double len = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
                double nx = -dz / len, nz = dx / len;
                double offset = h * segmentAmp;
                mx += nx * offset;
                mz += nz * offset;
                xs[i] = mx; zs[i] = mz;
            }
            span = half;
            segmentAmp *= 0.55;
            layer++;
        }
        return biasToValley(xs, zs, params, eSampler);
    }

    private static boolean isSet(double[] xs, double[] zs, int i) {
        // 中点尚未赋值的标记：初始全 0 数组无法区分"真 0 坐标"与"未赋值"，
        // 但坐标为精确 0 的概率为零（世界坐标远离原点），可接受。
        return xs[i] != 0.0 || zs[i] != 0.0;
    }

    /** 节点贴谷偏置：多步梯度下降把节点拉进局部谷底（总位移 ≤ valleyBiasAmp）。 */
    private static Node[] biasToValley(double[] xs, double[] zs,
                                       RiverLineParams params,
                                       ElevationSampler eSampler) {
        int n = xs.length;
        Node[] out = new Node[n];
        double step = params.anchorSnapStep();
        double maxMove = params.valleyBiasAmp();
        for (int i = 0; i < n; i++) {
            double x = xs[i], z = zs[i];
            if (i == 0 || i == n - 1) {
                out[i] = new Node(x, z);   // 端点=锚点已吸附，不再动
                continue;
            }
            // 多步朝最低邻域下降：把节点拉进谷底，同时保留分形蜿蜒形态
            double moved = 0.0;
            for (int s = 0; s < 5 && moved < maxMove; s++) {
                double eHere = eSampler.eAt(x, z);
                double bestDx = 0, bestDz = 0, bestE = eHere;
                for (int d = 0; d < 8; d++) {
                    double ang = d * Math.PI / 4.0;
                    double sx = x + Math.cos(ang) * step;
                    double sz = z + Math.sin(ang) * step;
                    double e = eSampler.eAt(sx, sz);
                    if (e < bestE) { bestE = e; bestDx = sx - x; bestDz = sz - z; }
                }
                // ★ 不向海洋下降：到达海岸（e<oceanE）即停止，避免河线被拉入海
                if (bestE < OCEAN_E) break;
                if (bestDx == 0.0 && bestDz == 0.0) break;
                double len = Math.hypot(bestDx, bestDz);
                double move = Math.min(step * 0.5, maxMove - moved);
                x += bestDx / len * move;
                z += bestDz / len * move;
                moved += move;
            }
            out[i] = new Node(x, z);
        }
        return out;
    }

    private static double distance(double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 地形 e 采样抽象（CellGenerator::terrainEQuick 注入）。 */
    public interface ElevationSampler {
        double eAt(double wx, double wz);
    }
}
