package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>「洼地未蓄水」探针 —— 为什么河流停在看起来能蓄水、却没有填色的区域</h1>
 *
 * <h2>用户观察</h2>
 * <p>「为什么河流会停在没有蓄水填色的区域？但从图上看似乎该区域是洼地可蓄水的？」</p>
 *
 * <h2>代码上的两套判定（可能不一致 —— 这是本探针要量的）</h2>
 * <table border="1">
 *   <tr><th>量</th><th>来源</th><th>含义</th></tr>
 *   <tr><td>{@code level - bed}</td><td>{@link EquilibriumWater}（minimax 洪泛）</td>
 *       <td><b>几何上</b>该蓄多深（"水蓄到最低鞍口"）</td></tr>
 *   <tr><td>{@code depth} / {@code lake}</td><td>{@link LakeWaterBalance}（水量平衡）</td>
 *       <td><b>实际</b>有多少水（来水=蒸发）</td></tr>
 * </table>
 *
 * <p>流线的终止条件是 {@code lake[k]==true}。若某格<b>几何上应蓄水</b>
 * （{@code level-bed > 0}）却<b>未被标为湖</b>，则：</p>
 * <ul>
 *   <li>流线到那里<b>不会终止</b>（不是合法终端）；</li>
 *   <li>而 minimax 已把该洼地水位抹平 ⇒ {@code ∇s≈0} ⇒ 速度归零 ⇒ 静水；
 *       {@code recover()} 沿 parent 逃生失败 ⇒ <b>河流断在此处</b>。</li>
 * </ul>
 * <p>⇒ 正是用户看到的现象。<b>本探针量这个不一致的规模与原因。</b></p>
 *
 * <h2>分类（决定修法）</h2>
 * <pre>
 *   A 几何湿(level-bed>0.5) 但 !lake
 *     A1 !resolved      —— 未决（水位依赖窗口切割）
 *     A2  已排除 sea
 *     A3 resolved 且 depth>0 —— 部分湿（湖缘）
 *     A4 resolved 且 depth==0 —— ★完全干★ ← 用户观察目标
 *   B  lake=true            —— 正常湖
 * </pre>
 * <p><b>关键判据</b>：A4 的几何深度 {@code level-bed} 若<b>普遍 &lt; 1.0</b>
 * ⇒ 属"浅洼地"（游戏里放不下水块，按项目定义丢弃是对的）；
 * 若<b>普遍 &gt; 1.0</b> ⇒ 是<b>真缺陷</b>（该有水却判干）。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class LakeGapProbe {

    /** 判定"几何上算湖"的最小水深（块）。 */
    private static final double GEO_WET_T = 0.5;

    private LakeGapProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 600_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] lake = r.lake(), sea = r.sea(), resolved = r.resolved(), ch = r.channel();
        double[] level = r.level(), bed = r.bed(), depth = r.depth();

        int a1 = 0, a3 = 0, a4 = 0, b = 0;
        boolean[] a1Mask = new boolean[cells];
        double sumGeoA4 = 0, minGeoA4 = Double.MAX_VALUE, maxGeoA4 = -Double.MAX_VALUE;
        int a4Shallow = 0, a4Deep = 0;              // 以 1.0 块为界
        int a4OnChannel = 0;                        // 河流是否经过这些格
        boolean[] a4Mask = new boolean[cells];
        boolean[] a3Mask = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            if (lake[k]) { b++; continue; }
            if (sea[k]) continue;
            double geo = level[k] - bed[k];
            if (!(geo > GEO_WET_T)) continue;
            if (!resolved[k]) { a1++; a1Mask[k] = true; continue; }
            if (depth[k] > 0) { a3++; a3Mask[k] = true; continue; }
            // ★ A4：几何上是湖，水量平衡判干
            a4++;
            a4Mask[k] = true;
            sumGeoA4 += geo;
            if (geo < minGeoA4) minGeoA4 = geo;
            if (geo > maxGeoA4) maxGeoA4 = geo;
            if (geo < LakeWaterBalance.MIN_VISIBLE_DEPTH) a4Shallow++;
            else a4Deep++;
            if (ch[k]) a4OnChannel++;
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 「洼地未蓄水」探针 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d samples=%d  %dx%d%n%n",
                seed, cx, cz, radius, samples, n, n));
        log.append(String.format("正常湖(lake=true)              = %d 格%n", b));
        log.append(String.format("A  几何湿(level-bed>%.1f)但 !lake:%n", GEO_WET_T));
        log.append(String.format("   A1 未决(!resolved)           = %d%n", a1));
        log.append(String.format("   A3 部分湿(depth>0)           = %d%n", a3));
        log.append(String.format("   A4 ★完全干(depth==0)         = %d  ← 用户的观察目标%n", a4));
        log.append(String.format("%n[A4 的几何深度 level-bed]%n"));
        log.append(String.format("   均值=%.3f 最小=%.3f 最大=%.3f 块%n",
                sumGeoA4 / Math.max(1, a4), minGeoA4, maxGeoA4));
        log.append(String.format("   < %.1f 块（浅洼地，丢弃合理）= %d (%.1f%%)%n",
                LakeWaterBalance.MIN_VISIBLE_DEPTH, a4Shallow,
                100.0 * a4Shallow / Math.max(1, a4)));
        log.append(String.format("   >= %.1f 块（★该有水却判干）  = %d (%.1f%%)%n",
                LakeWaterBalance.MIN_VISIBLE_DEPTH, a4Deep,
                100.0 * a4Deep / Math.max(1, a4)));
        log.append(String.format("   A4 中【有河道经过】= %d（河流确实流到这些格）%n", a4OnChannel));
        log.append(String.format("%n判读：%s%n", a4Deep > a4Shallow
                ? "★ 多数 A4 的几何深度 >= 1 块 ⇒ 【真缺陷】：该蓄水却判干，须修湖判定"
                : "多数 A4 是【浅洼地】(几何深度<1块) ⇒ 按项目定义丢弃合理；"
                        + "但若用户圈的区域是这块，需改渲染/或放宽浅洼地门槛"));
        log.append(String.format("%n[湖盆统计] basins=%d dryBasins=%d overflow出流点=%d%n",
                r.lakeResult().basins().size(), r.lakeResult().dryBasins(),
                r.lakeResult().overflowAt().size()));

        // ★★ 决定性检验：河流的【死格】落在哪一类？ ★★
        //   流线终止条件 = lake[k]。死格用 deathMask（流线非正常终止处）定位，
        //   再看它们落在 A1(未决几何湿) / A4(判干几何湿) / 其它。
        //   这直接回答用户："为什么河流停在没填色的洼地"。
        double[] death = r.deathMask();
        if (death != null) {
            int dTot = 0, dA1 = 0, dA4 = 0, dLake = 0, dOther = 0;
            int dGeoWet = 0;   // 死格中 geometry 上是湿的（任一类别）
            for (int k = 0; k < cells; k++) {
                if (death[k] <= 0) continue;
                dTot++;
                if (a1Mask[k]) { dA1++; dGeoWet++; }
                else if (a4Mask[k]) { dA4++; dGeoWet++; }
                else if (lake[k]) dLake++;
                else dOther++;
            }
            log.append(String.format("%n★★ 河流死格归因（deathMask）★★%n"));
            log.append(String.format("  死格总数=%d%n", dTot));
            log.append(String.format("    落在 A1(几何湿·未决)   = %d (%.1f%%)%n", dA1, 100.0 * dA1 / Math.max(1, dTot)));
            log.append(String.format("    落在 A4(几何湿·判干)   = %d (%.1f%%)%n", dA4, 100.0 * dA4 / Math.max(1, dTot)));
            log.append(String.format("    落在 湖(lake=true)     = %d (%.1f%%)%n", dLake, 100.0 * dLake / Math.max(1, dTot)));
            log.append(String.format("    其它                  = %d (%.1f%%)%n", dOther, 100.0 * dOther / Math.max(1, dTot)));
            log.append(String.format("  ⇒ 几何上是湿的合计 = %d (%.1f%%)%n",
                    dGeoWet, 100.0 * dGeoWet / Math.max(1, dTot)));
            log.append("     ⇒ 若【几何湿】占比高 ⇒ 用户观察成立：\n");
            log.append("        河流死在【本该是水】的格上，因该格未被标 lake ⇒ 流线不终止、又遇静水 ⇒ 停滞。\n");
        }

        // ---- 出图：A4 标红、A3 标橙、湖蓝、河青 ----
        Path dir = Path.of("build/lakegap");
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int col;
                if (a4Mask[k]) col = 0xFF2020;                 // ★ 几何湿但判干
                else if (a3Mask[k]) col = 0xFF9020;            // 部分湿
                else if (sea[k]) col = ViewSpec.SEA_COLOR;
                else if (lake[k]) col = ViewSpec.LAKE_COLOR;
                else if (ch[k]) col = ViewSpec.RIVER_COLOR;
                else col = ViewSpec.contourFixed(r.bed(), n, i, j);
                img.setRGB(i, j, col);
            }
        }
        ImageIO.write(img, "png", dir.resolve("lakegap.png").toFile());
        Files.writeString(dir.resolve("lakegap.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("lakegap.png").toAbsolutePath());
        System.out.println("图例：红=A4(几何湿却判干) 橙=部分湿 蓝=湖 青=河 深蓝=海 灰=地形");
    }
}