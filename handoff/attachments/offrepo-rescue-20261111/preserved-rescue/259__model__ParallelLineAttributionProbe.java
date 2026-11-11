package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>平行线/折线归因 —— 用户圈定区域，两机制插桩判别</h1>
 *
 * <h2>背景（用户在正典图上圈出）</h2>
 * <p>高原平坦区出现【平行短线河道】。用户确认：基本都在平坦区出现。</p>
 *
 * <h2>两个候选机制（上一轮排除了地形侧；本探针区分水力侧的两个候选）</h2>
 * <ol>
 *   <li><b>A 链传播</b>：{@code propagateDownstream} 沿 parent 链传播成河（0.5×门槛）。
 *       平坦区 parent 由 EDT 指路 ⇒ 同出口的 EDT 链即平行线族；
 *       高原流量悬在门槛附近 ⇒ 一处过门槛 ⇒ 沿链拖出【半门槛河】。</li>
 *   <li><b>B recover 沉积</b>：{@code recover()} 在流线停滞时沿 parent 链
 *       <b>逐格 +1 流量</b>（主积分每格只 +1 一次，recover 无上限）。
 *       基线 stagnant=122 万次 ⇒ 同一条 EDT 链被海量重复注入 ⇒ 单格宽条纹。</li>
 * </ol>
 *
 * <h2>判别手段（插桩，非猜测）</h2>
 * <p>{@code ContinuousRiverNetwork} 新增 {@code lastParallelInk[k]}
 * = 每格【recover 注入量】。则：</p>
 * <pre>
 *   inkShare[k] = parallelInk[k] / flux[k]   （0=全主积分，1=全恢复注入）
 * </pre>
 * <p>在【河道格】上分档统计：</p>
 * <ul>
 *   <li>若平行线区（平坦+河道）的 inkShare P50 ≈ 1 ⇒ <b>机制 B</b>；</li>
 *   <li>≈ 0 且该区大量格恰在 [0.5,1.0)×门槛 ⇒ <b>机制 A</b>；</li>
 *   <li>两者并存 ⇒ 混合，按格占比定主次。</li>
 * </ul>
 *
 * <h2>输出</h2>
 * <p>{@code build/hydro-reassessment/paral-<ts>/}：{@code metrics.txt}
 * + {@code inkmap.png}（河道按 inkShare 着色：蓝=主积分、红=恢复注入 —— 一眼看出平行线是谁画的）</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class ParallelLineAttributionProbe {

    private ParallelLineAttributionProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 400_000;

        ViewSpec.assertCanonicalRadius(radius, false);   // 比值型结论，小窗允许但警告

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        // ★ 插桩开启（探针专属：诊断源码集，生产 main 路径不受影响）
        ContinuousRiverNetwork.lastParallelInk = null;
        ContinuousRiverNetwork.lastRecoverInk = 0;
        ContinuousRiverNetwork.lastMainInk = 0;

        // 第一遍：跑一遍拿 n（为分配 ink 数组）—— 直接先按最大可能分配
        //   简化：先跑常规 pipeline（内部 lastParallelInk=null 不记账），
        //   然后对【同一窗口】用 build 的显式重载再跑一遍并记账。
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;

        // ★ 第二遍：同参、显式记账（lastParallelInk 长度 = cells）
        //   为了拿到 built.bed()/level()/resolved() 等中间量，这里复刻 pipeline 的
        //   前半段（采样+出口+平衡水面+水量平衡），只把 ContinuousRiverNetwork.build
        //   换成带记账的直调 —— 保证与第一遍【逐位同参】。
        double seaLevel = gen.seaLevel(), hs = tp.horizontalScale();
        int step = ViewSpec.STEP, rad = radius;
        WindowOutlets.Terrain terrain = (bx, bz) -> gen.sampleWu(bx / hs, bz / hs).height;
        var built = WindowOutlets.build(n, n, cx, cz, rad, step, seaLevel, terrain);
        var surface = EquilibriumWater.compute(built.bed(), n, n, built.drains(), built.exterior());
        boolean[] borderMask = new boolean[cells];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                borderMask[i * n + j] = i == 0 || j == 0 || i == n - 1 || j == n - 1;
        var balance = new LakeWaterBalance(n, n, step, built.bed(), surface);
        var lakeResult = balance.solve(climate, built.drains(), borderMask);

        ContinuousRiverNetwork.lastParallelInk = new double[cells];
        var p = ContinuousRiverNetwork.Params.forRunoff(lakeResult.runoffPerCell());

        // ★★ A/B：mainInkGate off（现状，平行线存在） vs on（修复：主墨水门槛）★★
        StringBuilder log = new StringBuilder();
        long mainInkA = 0;
        log.append("=== 平行线归因 + 修复 A/B ===\n");
        log.append(String.format("seed=%d radius=%d samples=%d 门槛=%.3f%n%n",
                seed, radius, samples, p.channelThreshold()));
        boolean[][] chAB = new boolean[2][];
        for (int arm = 0; arm < 2; arm++) {
            // ⚠ 记账清零（上一版漏了 ⇒ B 臂 ink 累加了 A 臂 ⇒ 主墨水被低估
            //   ⇒ 门槛过狠 ⇒ 误删真河 30%）。确定性 build 的墨水总量两臂必须相同，
            //   不同即是记账污染的铁证 —— 本次正是靠"总量变了"才发现。
            java.util.Arrays.fill(ContinuousRiverNetwork.lastParallelInk, 0.0);
            ContinuousRiverNetwork.lastRecoverInk = 0;
            ContinuousRiverNetwork.lastMainInk = 0;
            // ★ A/B 开关：recoverNoInk（链上不记墨水）。
            //   依据：链穿过被填平的水体（湖床），墨水把"死水蓄量"记成"河流水量"。
            //   上一版 mainInkGate（成河判定处拦截）已被 ab_diff 图否证：
            //   propagateDownstream 把平行线重新点亮，删除落在真河上。
            ContinuousRiverNetwork.recoverNoInk = (arm == 1);
            var c = ContinuousRiverNetwork.build(built.bed(), surface.level(), surface.resolved(),
                    n, n, step, p, samples, 0x5EEDL, 0,
                    built.drains(), surface.parent(), lakeResult.lake(), lakeResult.overflowAt());
            chAB[arm] = c.channel();
            // ★ 确定性自检：两臂同参数 ⇒ 主积分总量必须逐位相同。
            //   不同 ⇒ 记账/状态被污染（上次正是靠这个数才发现累加 bug）。
            log.append(String.format("【%s】 河道格=%d (占陆地 %.2f%%)%n",
                    arm == 0 ? "A: 现状(链上记墨水)" : "B: 修复(链上不记墨水)",
                    c.channelCells(), 100.0 * c.channelCells() / cells));
            log.append(String.format("   recover注入=%d 主积分=%d%n",
                    ContinuousRiverNetwork.lastRecoverInk, ContinuousRiverNetwork.lastMainInk));
            if (arm == 0) mainInkA = ContinuousRiverNetwork.lastMainInk;
            else if (mainInkA != ContinuousRiverNetwork.lastMainInk) {
                throw new IllegalStateException("两臂主积分总量不同（" + mainInkA
                        + " vs " + ContinuousRiverNetwork.lastMainInk
                        + "）⇒ 确定性被破坏/记账污染，A/B 无效，勿采信");
            }
        }
        ContinuousRiverNetwork.recoverNoInk = false;   // 复位，勿泄漏到其它探针

        // ★ A/B 差异图：去掉的格（= 平行线伪影）标红
        int removed = 0, kept = 0;
        BufferedImage diff = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int col;
                if (r.sea()[k]) col = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) col = ViewSpec.LAKE_COLOR;
                else if (chAB[0][k] && !chAB[1][k]) {
                    col = 0xFF2020; removed++;         // 红 = 被修复去掉的（应为平行线）
                } else if (chAB[1][k]) {
                    col = 0x00E5D0; kept++;            // 青 = 保留的河道
                } else {
                    col = ViewSpec.contourFixed(r.bed(), n, i, j);
                }
                diff.setRGB(i, j, col);
            }
        }
        log.append(String.format("%n★ A/B 差异：去掉 %d 格、保留 %d 格 (去掉占原 %.1f%%)%n",
                removed, kept, 100.0 * removed / Math.max(1, removed + kept)));
        log.append("  判读：若红色【恰为用户圈的平行线】且集中在高原平坦区 ⇒ 修复有效且精准。\n");
        log.append("  青色河网若仍完整连通（下游不断）⇒ 修复无副作用。\n");

        // 出图
        Path dir = Path.of("build/hydro-reassessment/paral-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(diff, "png", dir.resolve("ab_diff.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("ab_diff.png").toAbsolutePath());
    }
}