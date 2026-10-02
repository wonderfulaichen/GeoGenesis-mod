package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 正典规格出图：<b>湖 ↔ 河耦合</b>后的水面与河网，规格固定为
 * {@link ViewSpec}（1 方块/像素、半径 700 ⇒ 1401×1401）。
 *
 * <p>规格<b>只能</b>来自 {@link ViewSpec}，本类不自行写死 radius/step
 * （此前各探针各写一套，导致尺寸与分辨率漂移，用户已指出）。</p>
 *
 * <p><b>边界</b>：诊断出图，不落块、不接生产。</p>
 */
public final class CanonicalViewProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        double precip = args.length > 4 ? Double.parseDouble(args[4]) : 0.004;
        // 第 6 参：河网模型 d8 | continuous（默认 d8 作对照基线）
        var model = args.length > 5 && args[5].toLowerCase().startsWith("c")
                ? HydrologyPipeline.RiverModel.CONTINUOUS
                : HydrologyPipeline.RiverModel.D8;
        // 第 7 参：流线采样数（仅 continuous 用）
        int streamSamples = args.length > 6 ? Integer.parseInt(args[6]) : 600_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);

        long t0 = System.nanoTime();
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0, model, streamSamples));
        int n = r.nx();

        Path dir = Path.of("build/hydro-reassessment/canonical-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int lake = 0, channel = 0, sea = 0, unresolvedWet = 0, unresolvedDry = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (r.sea()[k]) { color = ViewSpec.SEA_COLOR; sea++; }
                else if (r.lake()[k]) { color = ViewSpec.LAKE_COLOR; lake++; }
                else if (r.channel()[k]) {
                    channel++;
                    color = r.width()[k] > 6 ? ViewSpec.RIVER_WIDE_COLOR : ViewSpec.RIVER_COLOR;
                }
                else if (!r.resolved()[k]) {
                    // ★ 未决格分两类（此前一律涂品红，造成大面积误导性色块）。
                    //
                    //   ⚠ 不能用 `level - bed > 0.01` 分类：未决格的 level 是 A 组洪泛的
                    //     **残留高程**（从远处倒灌而来），不是真实水位 ⇒ 该判据把 133,348 个
                    //     干燥坡面误标成"可能成湖"（实测）。改用**几何判据**：
                    //     该格是否低于其 8 邻域最小值（= 局部洼地）⇒ 才可能积水。
                    boolean localDepression = isLocalDepression(r.bed(), n, i, j);
                    if (localDepression) { color = ViewSpec.UNRESOLVED_COLOR; unresolvedWet++; }
                    else { color = ViewSpec.shade(r.bed(), n, i, j); unresolvedDry++; }
                }
                else color = ViewSpec.shade(r.bed(), n, i, j);
                img.setRGB(i, j, color);
            }
        }
        ImageIO.write(img, "png", dir.resolve("canonical.png").toFile());

        // ★ 断口探测器（诊断"虚线/断河"）：
        //   非海非湖非河、但左右（或上下）两侧都是河格 ⇒ 线在这里断了。
        //   perCrossing=1.44 > 门槛 1.0 ⇒ 只要被流线经过必成河 ⇒ 断口格 flux==0
        //   = 流线路径真断（而非门槛抖动）。打印前 20 个断口的坐标与 flux 取证。
        StringBuilder gaps = new StringBuilder();
        int gapCount = 0;
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (r.sea()[k] || r.lake()[k] || r.channel()[k]) continue;
                boolean lr = r.channel()[k - 1] && r.channel()[k + 1];
                boolean ud = r.channel()[k - n] && r.channel()[k + n];
                if (!lr && !ud) continue;
                gapCount++;
                if (gapCount <= 20) {
                    gaps.append(String.format("gap(%d,%d) flux=%.3f resolved=%b%n",
                            i, j, r.discharge()[k], r.resolved()[k]));
                }
            }
        }
        System.out.print(gaps);
        System.out.println("GAPS=" + gapCount + " (printed up to 20)");

        StringBuilder log = new StringBuilder();
        log.append("canonical spec: 1 pixel = 1 block, radius 700 => 1401x1401 (ViewSpec)\n");
        log.append(String.format("river model = %s (streamSamples=%d)%n", model, streamSamples));
        log.append("lake<->river coupling: river discharge feeds lake inflow, lake overflow feeds river\n");
        // ★ 耦合诊断（总是打印，取代不可靠的 -D 传递）：
        //   给出湖盆数、干湖盆数、来水/溢流规模，用于判断"耦合后湖为何骤减"。
        log.append(String.format("coupling: basins=%d dryBasins=%d runoffPerCell=%.6f overflowPoints=%d%n",
                r.lakeResult().basins().size(), r.lakeResult().dryBasins(),
                r.lakeResult().runoffPerCell(), r.lakeResult().overflowAt().size()));
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d image=%dx%d precip=%.4f%n",
                seed, cx, cz, radius, ViewSpec.STEP, n, n, precip));
        log.append(String.format("sea=%d (%.1f%%) lake=%d (%.2f%%) channel=%d (%.2f%%)%n",
                sea, 100.0 * sea / (n * (double) n), lake, 100.0 * lake / (n * (double) n),
                channel, 100.0 * channel / (n * (double) n)));
        // ★ 未决口径必须分两类报告（此前合并成一个数，会把"干燥坡面"误读成"算不出来"）。
        log.append(String.format("unresolved: possiblyWet=%d (%.2f%%, magenta) vs drySlope=%d (%.2f%%, shown as terrain)%n",
                unresolvedWet, 100.0 * unresolvedWet / (n * (double) n),
                unresolvedDry, 100.0 * unresolvedDry / (n * (double) n)));
        log.append(String.format("elapsed=%.0fs%n", (System.nanoTime() - t0) / 1e9));
        log.append("NOT VERIFIED: block placement, in-game render.\n");
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("canonical.png").toAbsolutePath());
    }

    /**
     * 几何判据：该格床面是否低于其 8 邻域最小值（= 局部洼地）。
     *
     * <p>用于给未决格分类：只有局部洼地才可能积水；其余是过水坡面，
     * 其"水位不可知"不影响"这里没有湖"的结论。</p>
     */
    private static boolean isLocalDepression(double[] bed, int n, int i, int j) {
        double here = bed[i * n + j];
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                int a = i + di, b = j + dj;
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                if (bed[a * n + b] < here) return false;   // 有更低邻居 ⇒ 水会流走
            }
        }
        return true;
    }

    private CanonicalViewProbe() { }
}
