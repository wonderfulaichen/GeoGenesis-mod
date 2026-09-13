package com.geogenesis.worldgen.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * 地形「密集条纹 / 平行细线」探针（★ 2026-09-12，第三次实测反馈后新增）。
 *
 * <h3>用户反馈</h3>
 * <p>高程预览出现<b>密集波浪状平行细线</b>（沿等高线走向、间距均匀、随地形弯曲），
 * 集中在中高海拔、低地平滑。这是<b>第三种</b>形态的伪影
 * （前两种：111wu 同心环带、岩石类型平直边界，均已修）。
 *
 * <h3>为何前序探针全部漏报</h3>
 * <p>已有的 {@code TectonicWaveProbe} 测的是<b>构造场</b>的"法向/切向各向异性"，
 * 而本条纹出现在<b>地形类型噪声本身</b>（{@link TypeNoiseProvider}），
 * 与板块边界毫无关系 → 探针覆盖不到。
 * <b>教训</b>：定位伪影必须先确定它属于哪个子系统，而不是只看"像不像上次那个"。
 *
 * <h3>本探针的判据：等值线聚集度（"细线"的数学特征）</h3>
 * <p>"密集平行细线"在地形图上的数学本质 = <b>高度的等值线在空间上极度密集</b>
 * （即某一小段高度范围内挤满了大量等值线）。
 * 度量方式：沿扫描线统计单步增量 |Δv| 的分布——正常噪声是平滑单峰，
 * 而<b>细线</b>会造成 |Δv| 分布的<b>极端长尾</b>（大量接近 0 的平段 + 少量极大的陡变）。
 *
 * <p>判据：{@code P99.9(|Δv|) / P50(|Δv|)} —— 长尾比。
 * 平滑噪声该比值约 5~15；<b>条纹/奇点</b>则远超（因为平段|Δv|→0、陡段 |Δv| 激增）。
 *
 * <h3>同时做「按地形类型分桶」</h3>
 * <p>以便直接定位是哪种类型配方出问题（PLATEAU / BASIN / HILLS / MOUNTAINS / PLAIN）。
 *
 * <p>用法：{@code gradlew runTerrainStripeProbe [seed]}
 */
public final class TerrainStripeProbe {

    /** 单步步长（wu）。取 1.0 以捕捉最锐利的奇点。 */
    private static final double STEP = 1.0;
    /** 该比值以上判为"存在细线条纹"。 */
    private static final double MAX_TAIL_RATIO = 200.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        System.out.printf("=== TerrainStripeProbe seed=%d ===%n", seed);
        TerrainParams tp = TerrainParams.defaults();
        TypeNoiseProvider provider = new TypeNoiseProvider(tp.beltReliefAmp(), tp.basinBase());
        provider.seed(seed);

        TerrainClass[] types = {
            TerrainClass.PLAIN, TerrainClass.HILLS, TerrainClass.MOUNTAINS,
            TerrainClass.PLATEAU, TerrainClass.BASIN
        };

        System.out.printf("%-10s %8s %8s %8s %10s %10s%n",
            "type", "P50|dv|", "P99|dv|", "P99.9|dv|", "max|dv|", "tailRatio");
        System.out.println("-".repeat(62));

        boolean anyFail = false;
        for (TerrainClass tc : types) {
            double[] stats = scan(provider, tc);
            double p50 = stats[0], p99 = stats[1], p999 = stats[2], mx = stats[3];
            double ratio = p50 > 1e-12 ? p999 / p50 : Double.POSITIVE_INFINITY;
            boolean bad = ratio > MAX_TAIL_RATIO;
            anyFail |= bad;
            System.out.printf("%-10s %8.5f %8.5f %8.5f %10.5f %10.1f %s%n",
                tc, p50, p99, p999, mx, ratio, bad ? "<== 条纹!" : "");
        }

        System.out.printf("%n判据: P99.9/P50 < %.0f（平滑噪声约 5~15；细线奇点远超）%n", MAX_TAIL_RATIO);
        System.out.println(anyFail ? "结果: FAIL（存在密集细线条纹）" : "=== ALL PASS ===");
        if (anyFail) System.exit(1);
    }

    /** 返回 {P50, P99, P99.9, max} 的单步增量统计。 */
    private static double[] scan(TypeNoiseProvider provider, TerrainClass tc) {
        List<Double> vals = new ArrayList<>(200000);
        for (double z = -6000; z <= 6000; z += 997.0) {
            double prev = Double.NaN;
            for (double x = -6000; x <= 6000; x += STEP) {
                double v = provider.computeNoise(tc, x, z);
                if (!Double.isNaN(prev)) vals.add(Math.abs(v - prev));
                prev = v;
            }
        }
        vals.sort(Double::compare);
        int n = vals.size();
        return new double[]{
            vals.get((int) (n * 0.50)),
            vals.get((int) (n * 0.99)),
            vals.get((int) (n * 0.999)),
            vals.get(n - 1)
        };
    }
}
