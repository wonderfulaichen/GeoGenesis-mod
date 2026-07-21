package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.terrain.SplineUtil;

/**
 * 气候样条：将原始气候值 [-1,1] 通过 Cubic Hermite 样条映射为连续区域索引。
 *
 * <p>设计思路：
 * <ul>
 *   <li>每个条件维度（温度/湿度/大陆性）有 N 个阈值，将值域分为 N+1 个区域</li>
 *   <li>阈值作为样条控制点的 x 坐标，y 坐标为区域索引 [0, N]</li>
 *   <li>样条在阈值处平滑过渡，避免硬边界跳变</li>
 *   <li>输出的连续区域索引可通过 tent 函数产生各区域的连续权重</li>
 * </ul>
 *
 * <p>示例（温度，5 个区域，4 个阈值）：
 * <pre>
 *   区域索引: 0(极寒)  1(寒冷)  2(温和)  3(温暖)  4(炎热)
 *   阈值:          -0.6     -0.2      0.2      0.5
 *   样条: (-1,0) → (-0.6,0) → (-0.2,1) → (0.2,2) → (0.5,3) → (1,4)
 * </pre>
 *
 * <p>用法：
 * <pre>
 *   ClimateSpline tempSpline = ClimateSpline.fromThresholds(
 *       new double[]{-0.6, -0.2, 0.2, 0.5});
 *   double zoneIndex = tempSpline.sample(0.1); // ≈ 1.75 (介于温和和温暖之间)
 *   double[] weights = tempSpline.zoneWeights(0.1); // [0, 0, 0.25, 0.75, 0]
 * </pre>
 */
public final class ClimateSpline {

    private static final double TRANSITION_WIDTH = 0.15; // 过渡带半宽（样条导数控制）

    private final double[] locations;   // 控制点 x（阈值 + 边界）
    private final double[] values;      // 控制点 y（区域索引）
    private final double[] derivatives; // 控制点导数（控制过渡平滑度）
    private final int numZones;         // 区域数 = 阈值数 + 1

    /**
     * @param thresholds 阈值数组（必须升序，如 {-0.6, -0.2, 0.2, 0.5}）
     */
    private ClimateSpline(double[] thresholds) {
        this.numZones = thresholds.length + 1;
        int n = thresholds.length + 2; // +2 for boundary points [-1, 1]
        this.locations = new double[n];
        this.values = new double[n];
        this.derivatives = new double[n];

        // 左边界
        locations[0] = -1.0;
        values[0] = 0.0;
        derivatives[0] = 0.0;

        // 阈值控制点：每个阈值对应左右两个区域的交界
        for (int i = 0; i < thresholds.length; i++) {
            locations[i + 1] = thresholds[i];
            values[i + 1] = i + 0.5; // 阈值处取两个区域索引的中点（平滑过渡）
            // 导数控制过渡宽度：导数越大，过渡越陡
            double segWidth = (i + 1 < thresholds.length)
                ? thresholds[i + 1] - thresholds[i]
                : 1.0 - thresholds[i];
            if (i > 0) segWidth = Math.max(segWidth, thresholds[i] - thresholds[i - 1]);
            derivatives[i + 1] = segWidth > 0 ? 1.0 / segWidth : 1.0;
        }

        // 右边界
        locations[n - 1] = 1.0;
        values[n - 1] = numZones - 1.0;
        derivatives[n - 1] = 0.0;
    }

    /**
     * 从阈值数组构建气候样条。
     * @param thresholds 升序阈值数组（如 {-0.6, -0.2, 0.2, 0.5}）
     */
    public static ClimateSpline fromThresholds(double[] thresholds) {
        return new ClimateSpline(thresholds);
    }

    /**
     * 采样样条，返回连续区域索引。
     * @param raw 原始气候值 [-1, 1]
     * @return 连续区域索引 [0, numZones-1]
     */
    public double sample(double raw) {
        return SplineUtil.splint(locations, values, derivatives, raw);
    }

    /**
     * 计算各区域的连续权重（tent 函数）。
     *
     * <p>权重计算：weight[i] = max(0, 1 - |zoneIndex - i|)
     * 每个区域有一个以其中心索引为峰值的三角形权重函数，
     * 相邻区域的权重在阈值处各为 0.5，实现平滑过渡。
     *
     * @param raw 原始气候值 [-1, 1]
     * @return 长度为 numZones 的权重数组，和 ≈ 1.0
     */
    public double[] zoneWeights(double raw) {
        double zoneIndex = sample(raw);
        double[] weights = new double[numZones];
        double sum = 0;
        for (int i = 0; i < numZones; i++) {
            double w = Math.max(0, 1.0 - Math.abs(zoneIndex - i));
            weights[i] = w;
            sum += w;
        }
        // 归一化确保和 = 1
        if (sum > 0) {
            for (int i = 0; i < numZones; i++) weights[i] /= sum;
        }
        return weights;
    }

    /**
     * 获取指定区域的连续权重。
     * @param raw 原始气候值 [-1, 1]
     * @param zoneIndex 区域索引 [0, numZones-1]
     * @return 该区域的连续权重 [0, 1]
     */
    public double zoneWeight(double raw, int zoneIndex) {
        double zi = sample(raw);
        double w = Math.max(0, 1.0 - Math.abs(zi - zoneIndex));
        // 简单归一化：最多只有两个相邻区域有非零权重
        return w; // 不归一化，保持绝对权重
    }

    /** 区域数 */
    public int numZones() { return numZones; }

    // ===== 便捷工厂方法 =====

    /**
     * 创建温度样条（5 个区域：极寒/寒冷/温和/温暖/炎热）。
     * @param frozen 极寒/寒冷阈值
     * @param cold 寒冷/温和阈值
     * @param warm 温和/温暖阈值
     * @param hot 温暖/炎热阈值
     */
    public static ClimateSpline temperature(double frozen, double cold, double warm, double hot) {
        return fromThresholds(new double[]{frozen, cold, warm, hot});
    }

    /**
     * 创建湿度样条（4 个区域：干旱/半干旱/湿润/潮湿）。
     * @param dry 干旱/半干旱阈值
     * @param semi 半干旱/湿润阈值
     * @param wet 湿润/潮湿阈值
     */
    public static ClimateSpline humidity(double dry, double semi, double wet) {
        return fromThresholds(new double[]{dry, semi, wet});
    }

    /**
     * 创建大陆性样条（7 个区域：深海/近海/沿海/过渡/近内陆/内陆/深内陆）。
     */
    public static ClimateSpline continentality(double deepOcean, double nearOcean,
                                                double coast, double transitional,
                                                double nearInland, double inland) {
        return fromThresholds(new double[]{deepOcean, nearOcean, coast, transitional, nearInland, inland});
    }

    // ===== 区域常量 =====

    /** 温度区域索引 */
    public static final int TEMP_FROZEN = 0;
    public static final int TEMP_COLD = 1;
    public static final int TEMP_MILD = 2;
    public static final int TEMP_WARM = 3;
    public static final int TEMP_HOT = 4;

    /** 湿度区域索引 */
    public static final int HUM_DRY = 0;
    public static final int HUM_SEMI = 1;
    public static final int HUM_WET = 2;
    public static final int HUM_HUMID = 3;

    /** 大陆性区域索引 */
    public static final int CONT_DEEP_OCEAN = 0;
    public static final int CONT_NEAR_OCEAN = 1;
    public static final int CONT_COASTAL = 2;
    public static final int CONT_TRANSITIONAL = 3;
    public static final int CONT_NEAR_INLAND = 4;
    public static final int CONT_INLAND = 5;
    public static final int CONT_DEEP_INLAND = 6;
}
