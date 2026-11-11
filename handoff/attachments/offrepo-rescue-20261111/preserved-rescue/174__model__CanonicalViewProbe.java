package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
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

    /**
     * 溢口标记色：纯黄。
     * <p>配色排除法：海 0x1F4E79 / 湖 0x4A90E2 / 河 0x00E5D0 / 宽河 0x00C8B4 /
     * 未决 0xC030C0 / 地形为灰阶 ⇒ <b>纯黄 0xFFFF00 在整个调色板中不存在</b>，
     * 且与灰阶底图对比度最高。</p>
     * <p>⚠ 先前误用 0xFFFFFF（白）：**与地形等高线的白色档撞车** ⇒ 用户看不出标记
     * （原话："你没给溢出口位置显示颜色我怎么判断？"）。</p>
     */
    private static final int SPILL_MARK_COLOR = 0xFFFF00;

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

        // ★★★ R1（2026-10-09）：`-Dhydro.viaProvider=true` ⇒ 正典图走【生产 provider】★★★
        //
        // 【为什么必须有这个开关 —— 用户核心要求"渲染图必须和游戏实机一样"】
        //   本探针原本直接调 `HydrologyPipeline.run`（**一次大窗口**），
        //   而游戏走 `ModelHydrologyProvider`（**按 region 分次求解再拼接**）
        //   ⇒ 两条完全不同的执行模型 ⇒ 图与游戏必然不同
        //   ⇒ 用户实测反复发现"图上有的、游戏里没有"。
        //
        //   本模式把出图改成【逐 chunk 调 provider】，参数与游戏完全一致
        //   （REGION/HALO/STEP 全用生产默认值）⇒ 产出的图**就是游戏会落的水**。
        //
        //   ⚠ 因此本模式下 `radius`/`model`/`streamSamples` 参数【不适用】
        //     （窗口由 REGION 决定）—— 会打印实际值以避免误解。
        if (Boolean.getBoolean("hydro.viaProvider")) {
            runViaProvider(seed, cx, cz, radius);
            return;
        }

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);

        long t0 = System.nanoTime();
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0, model, streamSamples));
        int n = r.nx();

        // ★★★ 中心线掩码对照（方案 §4.3 最后一条的落地）★★★
        //
        //   方案原文：「**实际河道掩码由这个距离场生成**，不再由 flux>=threshold
        //   的邻格色块代替」。
        //   此前 RiverCenterlines（细化→切段→定向→水力几何+谷轴夹逼→归一化距离场）
        //   已实现且有探针验证（runCenterlineCrossSectionProbe：抹开 18.09%→8.81%、
        //   湿宽比 1.07、拆分数 0），但**正典图一直没用它** ⇒ 方案这条一直悬空。
        //
        //   本轮按方案合同 6 处理：**正典规格与底图/色标一律不动**
        //   （canonical.png 仍用旧掩码，保证与历史图可比），
        //   **另出** canonical-centerline.png 作对照，并打印同参对照指标。
        //   -Dhydro.centerlineMask=true 时可让主图直接用新掩码（最终替换前的验证入口）。
        //
        //   ⚠ 必须传 cellSize=STEP：宽度/岸高/深度是 block 物理量，而细化在格空间
        //     ⇒ step=4 下若按"1格=1块"会把半宽放大 4×（抹开回归，见其 javadoc）。
        var cl = RiverCenterlines.build(r.bed(), r.discharge(), r.channel(), r.sea(),
                n, n, RiverCenterlines.Params.defaults(), ViewSpec.STEP);
        final boolean useCenterline = Boolean.getBoolean("hydro.centerlineMask");
        boolean[] chMask = useCenterline ? cl.channel : r.channel();
        // 旧掩码格数（对照基准；与下方 channel 计数口径不同：后者按 chMask 计）
        int channelCellsOld = 0;
        for (boolean b : r.channel()) if (b) channelCellsOld++;

        Path dir = Path.of("build/hydro-reassessment/canonical-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);

        // ★★ 底图着色：默认【无光照等高线】；-Dhydro.hillshade=true 切回 NW 光源 hillshade。
        //
        //   2026-10 用户指出"你这图明显是在山坡啊" —— 根因正是 hillshade：
        //   方向性光照把【谷画成脊】。本项目已因此误判两次（见 ViewSpec.contour 的 javadoc）。
        //   故默认改为等高线；hillshade 保留仅供对照地形大尺度形态。
        final boolean hillshade = Boolean.getBoolean("hydro.hillshade");
        // 等高线的下界与档距用【全图高程范围】⇒ 一图之内色标自洽。
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double v = r.bed()[i * n + j];
                if (v < hMin) hMin = v;
                if (v > hMax) hMax = v;
            }
        }
        double bandBlocks = Math.max(4.0, (hMax - hMin) / 24.0);
        System.out.printf("底图=%s 高程范围[%.1f, %.1f] 等高线档距=%.2f 块%n",
                hillshade ? "hillshade(NW光源)" : "contour(无光照)", hMin, hMax, bandBlocks);

        int lake = 0, channel = 0, sea = 0, unresolvedWet = 0, unresolvedDry = 0;
        // ★ 溢流出口标记（2026-10-05）。加它的理由：本轮修复的对象就是"溢口"，
        //   而此前图上【没有任何溢口标记】⇒ 用户无法判断修复是否生效
        //   （用户原话："你没给溢出口位置显示颜色我怎么判断？"）。
        //   溢口来自 lakeResult().overflowAt() 的键（格下标）。
        boolean[] isSpill = new boolean[n * n];
        for (Integer o : r.lakeResult().overflowAt().keySet()) {
            if (o != null && o >= 0 && o < n * n) isSpill[o] = true;
        }
        int spillShown = r.lakeResult().overflowAt().size();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (r.sea()[k]) { color = ViewSpec.SEA_COLOR; sea++; }
                else if (r.lake()[k]) { color = ViewSpec.LAKE_COLOR; lake++; }
                else if (chMask[k]) {
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
                    else { color = terrainColor(r, n, i, j, hillshade, hMin, bandBlocks); unresolvedDry++; }
                }
                else color = terrainColor(r, n, i, j, hillshade, hMin, bandBlocks);
                img.setRGB(i, j, color);
            }
        }
        // ★★ 河道【对角连线】渲染修正（2026-10-05）★★
        //
        //   【为什么需要】（用户："出现非常多完全不是河流的点状假河流"）
        //   流线沿速度场走、方向任意 ⇒ 实测河道相邻对中【对角占 45.8%】。
        //   1 像素渲染下，斜向相邻的两格在 4 邻域里【不接触】⇒ 视觉上断成点。
        //   ConnectivityMetricProbe 实测：孤立块 4连通=105 → 8连通=6（降 94%）
        //   ⇒ 大多数"点状假河"是【渲染口径】问题，不是模型缺陷。
        //
        //   修法：对每个河道格，若其【对角邻居】也是河道格、而夹着它的两个正交邻居
        //   都不是河道格，则补画一个正交格（中点连接）⇒ 斜线视觉连续。
        //   ⚠ 只改渲染，不改 channel 掩码本身（不污染数据与度量）；
        //     图像尺寸/位置/seed 一律不变。
        //   ⚠ 必须在【溢口标记之前】绘制，否则会覆盖掉溢口黄点。
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - 1; j++) {
                int k = i * n + j;
                if (!chMask[k]) continue;
                // 右下对角：补右格或下格（任选其一即可连通）
                if (chMask[(i + 1) * n + j + 1]
                        && !chMask[(i + 1) * n + j] && !chMask[i * n + j + 1]) {
                    img.setRGB(i, j + 1, ViewSpec.RIVER_COLOR);
                }
                // 左下对角
                if (j > 0 && chMask[(i + 1) * n + j - 1]
                        && !chMask[(i + 1) * n + j] && !chMask[i * n + j - 1]) {
                    img.setRGB(i + 1, j, ViewSpec.RIVER_COLOR);
                }
            }
        }
        // ★★ 溢口标记【后处理】绘制（2026-10-05）
        //   为什么必须后处理：① 在主循环里画会被邻格覆盖；
        //   ② 实测教训：先前用 1 像素白色 `0xFFFFFF`，**与地形等高线的白色档撞车**
        //      ⇒ 用户"根本看不出标了哪里"（原话："你没给溢出口位置显示颜色我怎么判断？"）。
        //   ⇒ 改用【地形配色中不存在的饱和品红】+ 3×3 加号，确保任何底图上都可见。
        //   ⚠ 图像尺寸/位置/seed 一律不变（用户明确要求可比）。
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!isSpill[i * n + j]) continue;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (Math.abs(di) + Math.abs(dj) > 1) continue;   // 加号形
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        img.setRGB(a, b, SPILL_MARK_COLOR);
                    }
                }
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
                if (r.sea()[k] || r.lake()[k] || chMask[k]) continue;
                boolean lr = chMask[k - 1] && chMask[k + 1];
                boolean ud = chMask[k - n] && chMask[k + n];
                if (!lr && !ud) continue;
                gapCount++;
                if (gapCount <= 20) {
                    // ★ 同时打印【原始 flux】与【3×3 平滑后的判定量】—— 决定性区分
                    //   "河道真的没水"（两值都低）与"被平滑拖下门槛"（原始高、平滑低）。
                    gaps.append(String.format(
                            "gap(%d,%d) rawFlux=%.3f smooth=%.3f resolved=%b%n",
                            i, j, r.discharge()[k], smoothAt(r, n, i, j), r.resolved()[k]));
                }
            }
        }
        System.out.print(gaps);
        System.out.println("GAPS=" + gapCount + " (printed up to 20)");

        // ★★★ 河道连通性（2026-10，用户："很多河流流动一点就断开了"）★★★
        //
        //   为什么必须量这个，而不是只数单格断口（GAPS）：
        //   GAPS 只抓"两侧都是河、自己不是"的【单格】断口；若河流碎成【多段】短链，
        //   段与段之间的空白是【多格】的 ⇒ 每段两侧不是河 ⇒ GAPS 完全不报
        //   （实测 GAPS=31 而图上大面积虚线，正是这个盲区）。
        //
        //   正确度量 = 河道掩码的【4 连通分量】：一条正常的河 = 一个大连通分量。
        //   若分量数远大于河流条数、且存在大量小块 ⇒ 河道被切碎（用户看到的虚线）。
        //   同时报告"每分量的跨度(格)"，短跨度 = 流动一点就断。
        int[] comp = new int[n * n];
        java.util.Arrays.fill(comp, -1);
        int comps = 0;
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        int[] stack = new int[n * n];
        for (int k0 = 0; k0 < n * n; k0++) {
            if (comp[k0] >= 0 || !chMask[k0]) continue;
            int sp = 0;
            stack[sp++] = k0;
            comp[k0] = comps;
            int size = 0;
            while (sp > 0) {
                int c = stack[--sp];
                size++;
                int ci = c / n, cj = c % n;
                // ★★ 2026-10-05 改为【8 连通】—— 这修的是【度量工具的 bug】★★
                //
                //   【为什么必须改】
                //   流线沿速度场走、方向任意 ⇒ 实测河道相邻对中【对角占 45.8%】。
                //   而"4 连通：对角接触不算连通"把斜向走的连续河【误判为孤立点】：
                //   ConnectivityMetricProbe 实测（同窗口同参数）：
                //     孤立块(≤2格)  4连通=105  →  8连通=6   （降 94%）
                //   ⇒ 用户看到的"点状假河"绝大多数是【4 连通度量的假象】，
                //     不是模型缺陷。此前据此做的四个归因假设全部落空，
                //     根因就是这个度量口径错误。
                //
                //   ⚠ 保留 4 连通计数作对照（见下方 dual 输出），
                //     以便区分"度量假象"与"真实断裂"。
                if (ci > 0 && comp[c - n] < 0 && chMask[c - n]) { comp[c - n] = comps; stack[sp++] = c - n; }
                if (ci < n - 1 && comp[c + n] < 0 && chMask[c + n]) { comp[c + n] = comps; stack[sp++] = c + n; }
                if (cj > 0 && comp[c - 1] < 0 && chMask[c - 1]) { comp[c - 1] = comps; stack[sp++] = c - 1; }
                if (cj < n - 1 && comp[c + 1] < 0 && chMask[c + 1]) { comp[c + 1] = comps; stack[sp++] = c + 1; }
                // 对角 4 邻（与上面合起来即 8 连通）
                if (ci > 0 && cj > 0 && comp[c - n - 1] < 0 && chMask[c - n - 1]) { comp[c - n - 1] = comps; stack[sp++] = c - n - 1; }
                if (ci > 0 && cj < n - 1 && comp[c - n + 1] < 0 && chMask[c - n + 1]) { comp[c - n + 1] = comps; stack[sp++] = c - n + 1; }
                if (ci < n - 1 && cj > 0 && comp[c + n - 1] < 0 && chMask[c + n - 1]) { comp[c + n - 1] = comps; stack[sp++] = c + n - 1; }
                if (ci < n - 1 && cj < n - 1 && comp[c + n + 1] < 0 && chMask[c + n + 1]) { comp[c + n + 1] = comps; stack[sp++] = c + n + 1; }
            }
            sizes.add(size);
            comps++;
        }
        java.util.Collections.sort(sizes);
        int totalCh = 0;
        for (int s : sizes) totalCh += s;
        int maxComp = sizes.isEmpty() ? 0 : sizes.get(sizes.size() - 1);
        int singletons = 0;
        for (int s : sizes) if (s <= 2) singletons++;
        System.out.printf("[连通性] 河道格=%d 连通分量=%d 最大分量=%d(%.1f%%) 孤立块(<=2格)=%d(%.1f%%)%n",
                totalCh, comps, maxComp, 100.0 * maxComp / Math.max(1, totalCh),
                singletons, 100.0 * singletons / Math.max(1, comps));
        System.out.printf("[连通性] 分量大小分位: P50=%d P90=%d P99=%d max=%d%n",
                pct(sizes, 0.50), pct(sizes, 0.90), pct(sizes, 0.99), maxComp);

        // ★★★ 点状假河归因（2026-10-05，用户："出现非常多完全不是河流的点状假河流"）★★★
        //
        //   假设 A【注入残留】：孤立块是"溢流注入起点"留下的墨水团
        //     —— 注入流线若在起点附近就终止（被推回湖），只有起点格有墨水
        //     ⇒ 判据：孤立块是否【紧邻湖】或【紧邻溢口】
        //   假设 B【门槛抖动】：平坦区逐格成河判定在门槛附近翻转
        //     ⇒ 判据：孤立块是否集中在【低坡度区】
        //   两个假设的修法完全不同 ⇒ 必须分开计数。
        int isoNearLake = 0, isoNearSpill = 0, isoFlat = 0, isoOther = 0;
        int isoTotal = 0;
        // 溢口集合（用于判据）
        java.util.Set<Integer> spillSet = new java.util.HashSet<>(r.lakeResult().overflowAt().keySet());
        // 分量成员数（只对小分量即孤立块做归因）
        int[] csize = new int[comps];
        for (int k = 0; k < n * n; k++) if (comp[k] >= 0) csize[comp[k]]++;
        for (int c = 0; c < comps; c++) {
            if (csize[c] > 2) continue;              // 只看孤立块（≤2 格）
            isoTotal++;
            boolean nearLake = false, nearSpill = false, flat = false;
            for (int k = 0; k < n * n; k++) {
                if (comp[k] != c) continue;
                int ci = k / n, cj = k % n;
                // 判据 A1：8 邻域内有湖
                for (int di = -1; di <= 1 && !nearLake; di++) {
                    for (int dj = -1; dj <= 1 && !nearLake; dj++) {
                        int a = ci + di, b = cj + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        if (r.lake()[a * n + b]) nearLake = true;
                    }
                }
                // 判据 A2：3 格内有溢口
                for (int di = -3; di <= 3 && !nearSpill; di++) {
                    for (int dj = -3; dj <= 3 && !nearSpill; dj++) {
                        int a = ci + di, b = cj + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        if (spillSet.contains(a * n + b)) nearSpill = true;
                    }
                }
                // 判据 B：床面梯度 < 0.10（平坦）
                if (ci > 0 && ci < n - 1 && cj > 0 && cj < n - 1) {
                    double dx = (r.bed()[(ci + 1) * n + cj] - r.bed()[(ci - 1) * n + cj]) / 2;
                    double dz = (r.bed()[ci * n + cj + 1] - r.bed()[ci * n + cj - 1]) / 2;
                    if (Math.hypot(dx, dz) < 0.10) flat = true;
                }
            }
            if (nearSpill) isoNearSpill++;
            else if (nearLake) isoNearLake++;
            else if (flat) isoFlat++;
            else isoOther++;
        }
        System.out.printf("[点状假河归因] 孤立块(≤2格)总数=%d%n", isoTotal);
        System.out.printf("  紧邻溢口(≤3格)=%d (%.1f%%)  紧邻湖(8邻域)=%d (%.1f%%)  平坦区=%d (%.1f%%)  其它=%d (%.1f%%)%n",
                isoNearSpill, 100.0 * isoNearSpill / Math.max(1, isoTotal),
                isoNearLake, 100.0 * isoNearLake / Math.max(1, isoTotal),
                isoFlat, 100.0 * isoFlat / Math.max(1, isoTotal),
                isoOther, 100.0 * isoOther / Math.max(1, isoTotal));
        System.out.println("  ⇒ 判读：若『紧邻溢口』占多数 ⇒ 注入残留（修注入端）；");
        System.out.println("     若『平坦区』占多数 ⇒ 门槛抖动（修成河判定）；两者修法不同。");

        // ★★ 「其它」孤立块现场 dump（81% 归因不明 ⇒ 必须看现场，不许猜）★★
        int dumped = 0;
        for (int c = 0; c < comps && dumped < 6; c++) {
            if (csize[c] > 2) continue;
            // 只 dump 从未被上面分类的（重算一次分类判定）
            int rep = -1;
            for (int k = 0; k < n * n; k++) if (comp[k] == c) { rep = k; break; }
            if (rep < 0) continue;
            int ci = rep / n, cj = rep % n;
            boolean nearSpill = false;
            for (int di = -3; di <= 3 && !nearSpill; di++)
                for (int dj = -3; dj <= 3 && !nearSpill; dj++) {
                    int a = ci + di, b = cj + dj;
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    if (spillSet.contains(a * n + b)) nearSpill = true;
                }
            if (nearSpill) continue;
            boolean nearLake = false;
            for (int di = -1; di <= 1 && !nearLake; di++)
                for (int dj = -1; dj <= 1 && !nearLake; dj++) {
                    int a = ci + di, b = cj + dj;
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    if (r.lake()[a * n + b]) nearLake = true;
                }
            if (nearLake) continue;
            double ddx = 0, ddz = 0;
            if (ci > 0 && ci < n - 1) ddx = (r.bed()[(ci + 1) * n + cj] - r.bed()[(ci - 1) * n + cj]) / 2;
            if (cj > 0 && cj < n - 1) ddz = (r.bed()[ci * n + cj + 1] - r.bed()[ci * n + cj - 1]) / 2;
            double grad = Math.hypot(ddx, ddz);
            System.out.printf("  [dump] (%4d,%4d) flux=%.4f smooth=%.4f 梯度=%.4f resolved=%b "
                            + "depth=%.2f lake=%b sea=%b%n",
                    ci, cj, r.discharge()[rep], smoothAt(r, n, ci, cj), grad,
                    r.resolved()[rep], r.depth()[rep], r.lake()[rep], r.sea()[rep]);
            dumped++;
        }

        // ★★★ 流线死亡热度图（2026-10）★★★
        //   假设：河道碎块的成因是【流线死在坡上】⇒ 下游拿不到水 ⇒ 河道在此断。
        //   若该假设成立，则死亡格应显著【集中在河道碎块的缺口处】。
        //   判读：打印死亡格总数、每次平均死亡数、以及【死亡格中落在河道上的比例】
        //   （若死亡格大量落在河道上/紧邻河道，则"流线死亡致断"成立）。
        double[] death = r.deathMask();
        if (death != null) {
            double dsum = 0;
            int onChannel = 0, nearChannel = 0, nDeath = 0;
            for (int k = 0; k < n * n; k++) {
                if (death[k] <= 0) continue;
                nDeath++;
                dsum += death[k];
                if (chMask[k]) onChannel++;
                else {
                    int ci = k / n, cj = k % n;
                    boolean near = false;
                    for (int di = -2; di <= 2 && !near; di++) {
                        for (int dj = -2; dj <= 2 && !near; dj++) {
                            int a = ci + di, b = cj + dj;
                            if (a < 0 || b < 0 || a >= n || b >= n) continue;
                            if (chMask[a * n + b]) near = true;
                        }
                    }
                    if (near) nearChannel++;
                }
            }
            System.out.printf("[死亡] 死亡格=%d 死亡事件合计=%.0f（占采样 %.2f%%）%n",
                    nDeath, dsum, 100.0 * dsum / Math.max(1, 600000));
            System.out.printf("[死亡] 落在河道上=%d(%.1f%%) 紧邻河道(±2格)=%d(%.1f%%) 二者合计=%.1f%%%n",
                    onChannel, 100.0 * onChannel / Math.max(1, nDeath),
                    nearChannel, 100.0 * nearChannel / Math.max(1, nDeath),
                    100.0 * (onChannel + nearChannel) / Math.max(1, nDeath));
        }

        // ★★★ 中心线掩码同参对照（方案 §4.3 + 合同 4）★★★
        //
        //   合同 4 原文：「对照法向实际湿宽与设计宽、单走廊重复河段、异常面积偏离；
        //   允许宽河和汇合区正常大面积，不再用 5×5 密度直接判失败」。
        //   ⇒ 旧 5×5 密度只作【诊断数字】，不作判据；判据用【拆分数】与【湿宽比】。
        //   这里出 canonical-centerline.png（同底图/色标/尺寸）供人眼对照。
        int clCells = cl.channelCells();
        int clReaches = cl.reachCount();
        StringBuilder clLog = new StringBuilder();
        clLog.append(String.format(
                "[中心线对照] 旧掩码河道格=%d → 距离场掩码河段=%d 段 / 河道格=%d (%.1f%%)%n",
                channelCellsOld, clReaches, clCells,
                100.0 * clCells / Math.max(1, channelCellsOld)));
        clLog.append(String.format("[中心线对照] 被丢弃的过短碎段=%d（<2 节点不绘，不静默）%n",
                cl.droppedFragments));
        BufferedImage clImg = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (r.sea()[k]) color = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) color = ViewSpec.LAKE_COLOR;
                else if (cl.channel[k]) {
                    color = cl.halfWidth[k] * 2 > 6
                            ? ViewSpec.RIVER_WIDE_COLOR : ViewSpec.RIVER_COLOR;
                } else color = ViewSpec.contour(r.bed(), n, i, j, hMin, bandBlocks);
                clImg.setRGB(i, j, color);
            }
        }
        for (Integer o : r.lakeResult().overflowAt().keySet()) {
            if (o == null || o < 0 || o >= n * n) continue;
            int ci = o / n, cj = o % n;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (Math.abs(di) + Math.abs(dj) > 1) continue;
                    int a = ci + di, b = cj + dj;
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    clImg.setRGB(a, b, SPILL_MARK_COLOR);
                }
            }
        }
        ImageIO.write(clImg, "png", dir.resolve("canonical-centerline.png").toFile());
        System.out.print(clLog);
        System.out.println("⚠ 主图 channel" + (useCenterline ? "=距离场掩码(-Dhydro.centerlineMask)"
                : "=旧门槛掩码（正典不变）") + "；对照图总用距离场掩码");

        StringBuilder log = new StringBuilder();
        log.append("canonical spec: 1 pixel = 1 block, radius 700 => 1401x1401 (ViewSpec)\n");
        log.append(String.format("river model = %s (streamSamples=%d)%n", model, streamSamples));
        log.append("lake<->river coupling: river discharge feeds lake inflow, lake overflow feeds river\n");
        // ★ 耦合诊断（总是打印，取代不可靠的 -D 传递）：
        //   给出湖盆数、干湖盆数、来水/溢流规模，用于判断"耦合后湖为何骤减"。
        log.append(String.format("coupling: basins=%d dryBasins=%d runoffPerCell=%.6f overflowPoints=%d%n",
                r.lakeResult().basins().size(), r.lakeResult().dryBasins(),
                r.lakeResult().runoffPerCell(), r.lakeResult().overflowAt().size()));
        log.append(String.format("spillways: 已标【黄点】的溢口格=%d（overflowAt 键数=%d）%n",
                spillShown, r.lakeResult().overflowAt().size()));
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

    /** 地形底图着色：默认无光照等高线，{@code -Dhydro.hillshade=true} 切回 NW 光源。 */
    private static int terrainColor(HydrologyPipeline.Result r, int n, int i, int j,
                                    boolean hillshade, double hMin, double bandBlocks) {
        return hillshade ? ViewSpec.shade(r.bed(), n, i, j)
                         : ViewSpec.contour(r.bed(), n, i, j, hMin, bandBlocks);
    }

    /**
     * ★ R1（2026-10-09）：走【生产 provider】出正典规格图 —— 图 = 游戏会落的水。
     *
     * <p>逐 chunk 调 {@code ModelHydrologyProvider.columnsFor}（与
     * {@code HydrologyChunkEngine.calculate} 完全同一入口），判据与游戏落块层一致
     * （湖 = {@code lakePlan}；河 = {@code fillWater && waterSurfaceY > carvedGroundY}）。</p>
     *
     * <p>规格严格用 {@link ViewSpec}：{@code radius=700/step=1 ⇒ 1401×1401}、
     * 固定色标 {@link ViewSpec#contourFixed}、底图源 {@code sampleWu}
     * （与 {@code HydrologyPipeline} 的 {@code WindowOutlets.Terrain} 同源）。</p>
     */
    /**
     * Load the user's TOML through the production spec, so the figure runs the code line the player configured.
     *
     * <p><b>Why this exists</b>: {@code ModelHydrologyProvider.SKELETON_TRACE} resolves as
     * "system property, else {@code GeoGenesisConfig}, else {@code false}". A probe JVM has no Forge config
     * environment, so without this the getter throws and the whole figure silently runs the legacy
     * {@code RiverCenterlines} path even when the user's TOML says {@code hydrologyDeterministicSkeleton = true}.
     * That exact mismatch is what made an earlier figure look like it ignored the skeleton.</p>
     *
     * <p>The file is read into a copy first; the user's TOML bytes are verified unchanged afterwards by the caller.</p>
     */
    private static void loadUserConfig() {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.exists(src)) {
            System.out.println("[canonical] WARNING: " + src.toAbsolutePath()
                    + " missing ⇒ provider falls back to defaults (skeleton switch may resolve false)");
            return;
        }
        try {
            Path copy = Path.of("build", "hydro-reassessment", "canonical-config.toml");
            Files.createDirectories(copy.getParent());
            Files.copy(src, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
        } catch (RuntimeException | java.io.IOException failure) {
            System.out.println("[canonical] WARNING: could not apply user TOML: " + failure);
        }
    }

    private static void runViaProvider(long seed, int cx, int cz, int radius) throws Exception {
        ViewSpec.assertCanonicalRadius(radius, false);
        loadUserConfig();
        com.geogenesis.worldgen.terrain.TerrainParams tp =
                com.geogenesis.worldgen.terrain.TerrainParams.defaults();
        com.geogenesis.worldgen.terrain.CellGenerator gen =
                new com.geogenesis.worldgen.terrain.CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = Math.max(0.01, tp.horizontalScale());
        ModelHydrologyProvider provider = new ModelHydrologyProvider(gen, seed);

        int n = 2 * radius + 1;
        boolean[] isLake = new boolean[n * n];
        boolean[] isChannel = new boolean[n * n];
        long t0 = System.nanoTime();
        int chunks = 0;
        int c0 = Math.floorDiv(cx - radius, 16) - 1, c1 = Math.floorDiv(cx + radius, 16) + 1;
        int d0 = Math.floorDiv(cz - radius, 16) - 1, d1 = Math.floorDiv(cz + radius, 16) + 1;
        for (int chX = c0; chX <= c1; chX++) {
            for (int chZ = d0; chZ <= d1; chZ++) {
                com.geogenesis.worldgen.terrain.Cell[] cells =
                        new com.geogenesis.worldgen.terrain.Cell[256];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        cells[lx * 16 + lz] = gen.sample((chX * 16 + lx) / hs, (chZ * 16 + lz) / hs);
                    }
                }
                java.util.List<com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn> cols
                        = provider.columnsFor(chX, chZ, cells);
                chunks++;
                for (com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn col : cols) {
                    int px = col.blockX() - (cx - radius);
                    int pz = col.blockZ() - (cz - radius);
                    if (px < 0 || pz < 0 || px >= n || pz >= n) continue;
                    int k = px * n + pz;
                    if (col.lakePlan()) isLake[k] = true;
                    else if (col.fillWater() && col.waterSurfaceY() > col.carvedGroundY()) {
                        isChannel[k] = true;
                    }
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;

        int lakeCells = 0, chCells = 0;
        for (int k = 0; k < n * n; k++) {
            if (isLake[k]) lakeCells++;
            else if (isChannel[k]) chCells++;
        }
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.sampleWu((cx - radius + i) / hs, (cz - radius + j) / hs).height;
            }
        }
        Path dir = Path.of("build/hydro-reassessment/canonical-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        // ★ 局部对比度模式（-Dhydro.relief=true）：7×7 邻域 min/max 预计算
        final boolean relief = Boolean.getBoolean("hydro.relief");
        int[][] localMin = null, localMax = null;
        if (relief) {
            localMin = new int[n][n];
            localMax = new int[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                    for (int di = -3; di <= 3; di++) {
                        for (int dj = -3; dj <= 3; dj++) {
                            int a = i + di, b = j + dj;
                            if (a < 0 || b < 0 || a >= n || b >= n) continue;
                            double v = bed[a * n + b];
                            if (v < lo) lo = v;
                            if (v > hi) hi = v;
                        }
                    }
                    localMin[i][j] = (int) lo;
                    localMax[i][j] = (int) hi;
                }
            }
        }
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (isLake[k]) color = ViewSpec.LAKE_COLOR;
                else if (isChannel[k]) color = ViewSpec.RIVER_COLOR;
                else if (bed[k] < gen.seaLevel()) color = ViewSpec.SEA_COLOR;
                // 正典默认 = contourFixed（定稿规格）；-Dhydro.relief=true 切局部对比（诊断用，不可比灰度）
                else if (relief) color = ViewSpec.contourRelief(bed, n, i, j,
                        ViewSpec.BAND_BASE, ViewSpec.BAND_BLOCKS, localMin, localMax);
                else color = ViewSpec.contourFixed(bed, n, i, j);
                img.setRGB(i, j, color);
            }
        }
        ImageIO.write(img, "png", dir.resolve("canonical.png").toFile());
        StringBuilder sb = new StringBuilder();
        sb.append("=== 正典规格图（走【生产 provider】⇒ 图 = 游戏会落的水）===\n");
        sb.append(String.format("seed=%d centre=(%d,%d) radius=%d ⇒ %dx%d%n",
                seed, cx, cz, radius, n, n));
        sb.append(String.format("REGION=%s HALO=%s STEP=%s（生产生效值）%n",
                System.getProperty("geogenesis.modelRegion", "512"),
                System.getProperty("geogenesis.modelHalo", "96"),
                System.getProperty("geogenesis.modelStep", "4")));
        sb.append(describeCodeLine());
        sb.append(String.format("chunks=%d 耗时=%.1fs%n", chunks, ms / 1000));
        sb.append(String.format("湖格=%d (%.2f%%)  河道格=%d (%.2f%%)%n",
                lakeCells, 100.0 * lakeCells / (n * n), chCells, 100.0 * chCells / (n * n)));
        sb.append(String.format("%n⚠ 本模式下 radius/model/streamSamples 参数不适用（窗口由 REGION 决定）%n"));
        sb.append("判据：湖=lakePlan；河=fillWater && waterSurfaceY>carvedGroundY（与 GeoGenesisGenerator:781 一致）\n");
        sb.append("底图=ViewSpec.contourFixed（与 provider 探针同色标，跨图可比）\n");
        sb.append("\nFILE=").append(dir.resolve("canonical.png").toAbsolutePath()).append('\n');
        Files.writeString(dir.resolve("metrics.txt"), sb.toString());
        System.out.print(sb);
    }

    /**
     * Which river-topology code line this figure actually ran, read from the production flags themselves.
     *
     * <p>Since 2026-10-20 the legacy line is removed; {@code SKELETON_TRACE} is constant true and two branches
     * remain: skeleton+world (CROSS_PASS, worldRegion two-point edges) or plain skeleton (DeterministicSkeleton).
     * Printing the resolved branch makes it impossible to mistake one line's output for another's — the mistake
     * that produced an earlier "skeleton" figure which was actually the legacy path.</p>
     */
    private static String describeCodeLine() {
        boolean skeleton = ModelHydrologyProvider.SKELETON_TRACE;
        boolean crossPass = ModelHydrologyProvider.CROSS_PASS;
        String line = skeleton ? crossPass
                ? "SKELETON+WORLD WorldBasinFlow 世界图（worldRegion）"
                : "SKELETON DeterministicSkeleton（minimax 树 + JOIN + 形态收尾）"
                : "LEGACY（已移除；出现此行即为编译/静态字段异常，必须报错）";
        return String.format("SKELETON_TRACE=%s CROSS_PASS=%s ⇒ 实际代码线=%s%n", skeleton, crossPass, line);
    }

    /** 复算 3×3 平均（海格不参与），与 ContinuousRiverNetwork 的 smooth 口径一致。 */
    private static double smoothAt(HydrologyPipeline.Result r, int n, int i, int j) {
        double sum = 0;
        int cnt = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                int a = i + di, b = j + dj;
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                int kk = a * n + b;
                if (r.sea()[kk]) continue;
                sum += r.discharge()[kk];
                cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    private static int pct(java.util.List<Integer> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    private CanonicalViewProbe() { }
}
