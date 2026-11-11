package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.hydrology.HydrologyChunkEngine;
import com.geogenesis.worldgen.hydrology.HydrologyChunkResult;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <h1>【生产真实路径】出图 —— legacy 链 vs model 链，同规格并排</h1>
 *
 * <h2>为什么必须建它（2026-10-08，用户困惑驱动）</h2>
 * <p>用户原话：「**为什么会出现渲染图和游戏实机不一致的情况？我都搞混乱了。到底有多少套？**」</p>
 *
 * <p>混乱的真实来源：探针族分属不同链 ——</p>
 * <ul>
 *   <li>{@code RiverLine*}/{@code Lake*}/多数 {@code Hydrology*} 测的是 <b>legacy 链</b>；</li>
 *   <li>{@code model.*} 的 {@code Canonical*}/{@code Pipeline*} 测的是 <b>model 链</b>；</li>
 *   <li>而 model 链内部还有 {@code RiverModel.D8} 与 {@code CONTINUOUS} 两个模型。</li>
 * </ul>
 * <p>⇒ 拿任一探针的图去代表"游戏里的水"都可能错。唯一可靠的做法：
 * <b>走生产入口 {@link HydrologyChunkEngine#calculate}（它内部按开关二选一），
 * 用同一规格出图</b>。本探针即此。</p>
 *
 * <h2>与 {@link ProductionViewProbe} 的分工</h2>
 * <ul>
 *   <li>{@code ProductionViewProbe}：强制走 model 的 {@code ModelHydrologyProvider}
 *       （用于诊断 model 链本身，可调 region/step 旋钮）；</li>
 *   <li>本类：走 {@link HydrologyChunkEngine} —— <b>与游戏完全同一个入口</b>，
 *       开关值即游戏实际值。跑两次（开关 on/off）即得两条链的同规格对比。</li>
 * </ul>
 *
 * <h2>规格</h2>
 * <p>严格复用 {@link ViewSpec}：{@code radius=700 / step=1 ⇒ 1401×1401}、
 * 固定色标 {@link ViewSpec#contourFixed}（跨图可比）、底图源 {@code sampleWu}。</p>
 *
 * <p><b>NOT VERIFIED</b>：离线探针（不起 MC）；方块铺设与 MC 侧写回未覆盖。</p>
 */
public final class ProductionChainCompareProbe {

    private ProductionChainCompareProbe() { }

    /** 链标识：LEGACY = `HydrologyBlockCarver`；MODEL = `ModelHydrologyProvider`。 */
    private enum Chain { LEGACY, MODEL }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        ViewSpec.assertCanonicalRadius(radius, false);
        // 第 5 参：跑哪条链（legacy | model | both，默认 both）
        String which = args.length > 4 ? args[4].toLowerCase() : "both";

        Path dir = Path.of("build/chain-compare/cc-" + System.currentTimeMillis());
        Files.createDirectories(dir);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 生产真实路径出图（HydrologyChunkEngine，与游戏同一入口）===\n");
        sb.append(String.format("seed=%d centre=(%d,%d) radius=%d ⇒ %dx%d（正典 %d）%n",
                seed, cx, cz, radius, 2 * radius + 1, 2 * radius + 1, ViewSpec.size()));
        sb.append("底图=ViewSpec.contourFixed（固定色标，跨图可比）；数据源=sampleWu\n\n");

        if (which.equals("legacy") || which.equals("both")) {
            runChain(Chain.LEGACY, seed, cx, cz, radius, dir, sb);
        }
        if (which.equals("model") || which.equals("both")) {
            runChain(Chain.MODEL, seed, cx, cz, radius, dir, sb);
        }
        sb.append("\n判读：两张图同为 1401² + 同固定色标 ⇒ **可逐格对比**。\n");
        sb.append("  legacy = 用户说「湖正常、河位置奇怪」的那条链；\n");
        sb.append("  model  = 我启用过、湖因窗口截断而丢失的那条链。\n");
        sb.append("  ⚠ 若两图差异大 ⇒ 说明「渲染图 ≠ 实机」的根因就是链不同。\n");
        Files.writeString(dir.resolve("summary.txt"), sb.toString());
        System.out.print(sb);
    }

    /** 走生产入口跑一条链并出图。 */
    private static void runChain(Chain chain, long seed, int cx, int cz, int radius,
                                 Path dir, StringBuilder sb) throws Exception {
        // ★ 切换链：生产入口读【配置】；探针进程无 Forge 配置 ⇒ 回落系统属性
        //   `-Dgeogenesis.modelPipeline`（HydrologyChunkEngine 内已有该回落，见其构造器）。
        System.setProperty("geogenesis.modelPipeline", chain == Chain.MODEL ? "true" : "false");

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        HydrologyChunkEngine engine = new HydrologyChunkEngine(gen, seed);

        int n = 2 * radius + 1;
        boolean[] isLake = new boolean[n * n];
        boolean[] isChannel = new boolean[n * n];

        long t0 = System.nanoTime();
        int chunks = 0;
        int c0 = Math.floorDiv(cx - radius, 16) - 1, c1 = Math.floorDiv(cx + radius, 16) + 1;
        int d0 = Math.floorDiv(cz - radius, 16) - 1, d1 = Math.floorDiv(cz + radius, 16) + 1;
        for (int chX = c0; chX <= c1; chX++) {
            for (int chZ = d0; chZ <= d1; chZ++) {
                HydrologyChunkResult r = engine.calculate(chX, chZ);
                chunks++;
                for (HydrologyBlockCarvedColumn col : r.carvedColumns()) {
                    int px = col.blockX() - (cx - radius);
                    int pz = col.blockZ() - (cz - radius);
                    if (px < 0 || pz < 0 || px >= n || pz >= n) continue;
                    int k = px * n + pz;
                    // ★★ 判据必须与游戏落块层【逐字一致】（2026-10-08 修正）★★
                    //
                    //   游戏 {@code GeoGenesisGenerator:781} 的判据是：
                    //     {@code cell.riverType != 0 && cell.riverSurfaceY > groundY}
                    //   其中 {@code cell.riverType} 由 {@code applyHydrologyValley}
                    //   从列的 {@code fillWater()} 写回、{@code riverSurfaceY} = 列的水面。
                    //   ⇒ 本处等价复现：fillWater 且 水面高于地面。
                    //
                    //   ⚠ 此前用 {@code erosion() > 0} 判河 —— 【错】。legacy 链的
                    //   {@code erosion()} 包含【整个河谷】（河床 + 岸坡 + 谷壁）
                    //   ⇒ 画出一大片"河"（实测 9.86%），正是用户看到的"青色大色块"。
                    if (col.lakePlan()) {
                        // 湖列：游戏按湖水位洪泛（本探针取 lakePlan 与其水面）
                        isLake[k] = true;
                    } else if (col.fillWater() && col.waterSurfaceY() > col.carvedGroundY()) {
                        isChannel[k] = true;
                    }
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        System.clearProperty("geogenesis.modelPipeline");

        int lakeCells = 0, chCells = 0;
        for (int k = 0; k < n * n; k++) {
            if (isLake[k]) lakeCells++;
            else if (isChannel[k]) chCells++;
        }

        // 底图：与 Pipeline 的 WindowOutlets.Terrain 同源（sampleWu）
        double hs = Math.max(0.01, tp.horizontalScale());
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.sampleWu((cx - radius + i) / hs, (cz - radius + j) / hs).height;
            }
        }

        String name = chain.name().toLowerCase();
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (isLake[k]) color = ViewSpec.LAKE_COLOR;
                else if (isChannel[k]) color = ViewSpec.RIVER_COLOR;
                else if (bed[k] < gen.seaLevel()) color = ViewSpec.SEA_COLOR;
                else color = ViewSpec.contourFixed(bed, n, i, j);
                img.setRGB(i, j, color);
            }
        }
        ImageIO.write(img, "png", dir.resolve(name + ".png").toFile());

        sb.append(String.format("【%s 链】%n", name.toUpperCase()));
        sb.append(String.format("  chunks=%d 耗时=%.1fs%n", chunks, ms / 1000));
        sb.append(String.format("  湖格=%d (%.2f%%)  河道格=%d (%.2f%%)%n",
                lakeCells, 100.0 * lakeCells / (n * n), chCells, 100.0 * chCells / (n * n)));
        sb.append(String.format("  FILE=%s%n%n", dir.resolve(name + ".png").toAbsolutePath()));
    }
}
