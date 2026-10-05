package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>高海拔陡坡区的碎块成因 —— 为什么碎片集中在那里</h1>
 *
 * <h2>已确立的事实（ChannelDotProbe 两种 A/B 都不变）</h2>
 * <pre>
 *   小分量(&lt;20格) 平均高程 = 174.7~175.9 块   vs  全河道 114.1~115.6  （高 60 块）
 *   小分量(&lt;20格) 平均坡度 = 1.19~1.20        vs  全河道 0.75~0.78    （陡 1.6×）
 * </pre>
 * <p>⇒ 碎块<b>确定性</b>地集中在高海拔陡坡，不是随机噪声，
 * 也不是 {@code recoverNoInk}（A/B 实测 327→316，仅 3%）。</p>
 *
 * <h2>本探针要回答</h2>
 * <p>在那个区域，<b>流线到底发生了什么</b>？三个候选：</p>
 * <ol>
 *   <li><b>流线太短</b>：从源头出发很快到边界/海 ⇒ 只有少数格有墨水 ⇒ 碎；
 *       判据：碎块区的流线【平均步数】是否显著低于全局；</li>
 *   <li><b>流线稀疏</b>：陡坡面积大但撒点均匀 ⇒ 单位面积墨水少 ⇒ 门槛下的格多；
 *       判据：碎块区的【每格平均 crossings】是否显著低；</li>
 *   <li><b>流线跳跃</b>：陡坡上 velocity 很大（Manning ∝ √slope）⇒
 *       单位方向×1 格步长在陡坡上会【跨格】⇒ 中间格不记墨水 ⇒ 河道逐格断裂。
 *       判据：陡坡上流线的【相邻进入格距】是否 &gt; 1（跳跃）。</li>
 * </ol>
 * <p><b>候选 3 最可疑</b>：本项目 step = 0.5*cellSize（半格），
 * 但 velocity 的大小 ∝ √|∇s|，而陡坡 |∇s| 大 ⇒ 速度大。
 * 若代码用【归一化方向 × 固定步长】则不会跳跃；若用【未归一化 × 步长】则会。
 * <b>本探针直接量相邻进入格距，一次判死。</b></p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class SteepFragmentProbe {

    private SteepFragmentProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 150_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] ch = r.channel();
        double[] bed = r.bed(), flux = r.discharge();

        // ---- [2] 每格 crossings 与坡度分组对照 ----
        //   把河道格按坡度分档，看每档的平均 flux（= crossing 密度代理）
        double[] slopes = new double[]{0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0};
        long[] cnt = new long[slopes.length];
        double[] sumFlux = new double[slopes.length];
        double[] sumSlope = new double[slopes.length];
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (!ch[k]) continue;
                double dx = (bed[(i + 1) * n + j] - bed[(i - 1) * n + j]) / 2;
                double dz = (bed[i * n + j + 1] - bed[i * n + j - 1]) / 2;
                double g = Math.hypot(dx, dz);
                int b = slopes.length - 1;
                for (int t = 0; t < slopes.length - 1; t++) {
                    if (g < slopes[t + 1]) { b = t; break; }
                }
                cnt[b]++;
                sumFlux[b] += flux[k];
                sumSlope[b] += g;
            }
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 高海拔陡坡碎块成因 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d samples=%d%n%n", seed, cx, cz, radius, samples));
        log.append("【[2] 河道格按坡度分档：平均 flux（墨水密度代理）】\n");
        log.append(String.format("  %-16s %10s %14s %14s%n", "坡度档", "格数", "平均flux", "平均坡度"));
        for (int b = 0; b < slopes.length; b++) {
            if (cnt[b] == 0) continue;
            String label = (b == slopes.length - 1)
                    ? String.format(">=%.2f", slopes[b])
                    : String.format("[%.2f,%.2f)", slopes[b], slopes[b + 1]);
            log.append(String.format("  %-16s %10d %14.4f %14.4f%n",
                    label, cnt[b], sumFlux[b] / cnt[b], sumSlope[b] / cnt[b]));
        }
        log.append("  ⇒ 若陡坡档的【平均 flux】显著低于缓坡档 ⇒ 说明陡坡上墨水稀疏（候选 2）；\n");
        log.append("     若各档 flux 相近而陡坡碎块多 ⇒ 与 flux 无关，看候选 3（跳跃）。\n");

        java.nio.file.Path dir = java.nio.file.Path.of("build/dots");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("steep_fragment.txt"), log);
        System.out.println(log);
    }
}