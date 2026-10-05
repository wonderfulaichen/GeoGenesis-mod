package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 真实地形上对比【连续速度场河网】与【D8 汇流图】。
 *
 * <p>两者使用<b>同一</b>平衡水位与同一气候，只有汇流求法不同：
 * 左=地形，中=D8（连通图累积），右=连续速度场（流线积分）。
 * 目的：检验"换成连续速度场后，均匀坡面上的平行直纹是否消失"。</p>
 *
 * <p><b>边界</b>：本探针只出图与量化，不作真值判定；连续速度场估计器有 Poisson 方差，
 * 低汇流格的精度受样本数限制（解析检验已确认）。不含河槽下切与动量。</p>
 */
public final class ContinuousRiverViewProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -500;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -1000;
        int step = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int radius = args.length > 4 ? Integer.parseInt(args[4]) : 320;
        int samples = args.length > 5 ? Integer.parseInt(args[5]) : 300_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        // 同一平衡水面 + 同一水量平衡，只换汇流求法
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, step, climate));
        int n = r.nx();
        int buffer = 20;   // 边界带不输出河（上游被截断）

        var continuous = ContinuousRiverNetwork.build(
                r.bed(), r.level(), r.resolved(), n, n, step,
                ContinuousRiverNetwork.Params.forRunoff(r.lakeResult().runoffPerCell()),
                samples, seed ^ 0x9E37L, buffer);

        Path dir = Path.of("build/hydro-reassessment/contRiver-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n * 3 + 16, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int shadeVal = shade(r.bed(), n, i, j);
                img.setRGB(i, j, shadeVal);
                img.setRGB(n + 8 + i, j, compose(shadeVal, r.channel()[k], r.width()[k],
                        r.sea()[k], r.lake()[k], r.resolved()[k]));
                img.setRGB(2 * n + 16 + i, j, compose(shadeVal, continuous.channel()[k],
                        continuous.width()[k], r.sea()[k], r.lake()[k], r.resolved()[k]));
            }
        }
        ImageIO.write(img, "png", dir.resolve("compare.png").toFile());

        StringBuilder log = new StringBuilder();
        log.append("left=terrain, middle=D8 accumulation, right=continuous velocity field (streamline integration)\n");
        log.append(String.format("seed=%d centre=(%d,%d) step=%d radius=%d grid=%d samples=%d buffer=%d%n",
                seed, cx, cz, step, radius, n, samples, buffer));
        log.append(String.format("D8 channelCells=%d  maxFlux=%.1f%n",
                r.riverResult().channelCells(), r.riverResult().maxDischarge()));
        log.append(String.format("continuous channelCells=%d  maxFlux=%.1f  streamSteps=%d%n",
                continuous.channelCells(), continuous.maxFlux(), continuous.streamSteps()));
        log.append("NOTE: continuous estimator carries Poisson variance at low accumulation; no incision/momentum.\n");
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("compare.png").toAbsolutePath());
    }

    /** 在给定地形阴影色上叠加水体；优先顺序 = 海 > 湖 > 未决 > 河 > 地形。 */
    private static int compose(int shadeVal, boolean channel, double width,
                               boolean sea, boolean lake, boolean resolved) {
        if (sea) return 0x1F4E79;
        if (lake) return 0x4A90E2;
        if (!resolved) return 0xC030C0;
        if (channel) return width > 6 ? 0x00C8B4 : width > 3 ? 0x00E5D0 : 0x64E8D8;
        return shadeVal;
    }

    private static int shade(double[] bed, int n, int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (bed[i1 * n + j] - bed[i0 * n + j]) / (i1 - i0);
        double dz = (bed[i * n + j1] - bed[i * n + j0]) / (j1 - j0);
        double light = (0.5 * dx + 0.7 - 0.51 * dz) / Math.sqrt((dx * dx + 1 + dz * dz) * 1.0001);
        int g = (int) (60 + 175 * Math.max(0, light));
        return (g << 16) | (g << 8) | g;
    }

    private ContinuousRiverViewProbe() { }
}
