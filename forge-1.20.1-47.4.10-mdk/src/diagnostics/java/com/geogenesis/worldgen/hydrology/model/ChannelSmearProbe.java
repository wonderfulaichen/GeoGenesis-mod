package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>「河道抹开」（成块不成条）归因</h1>
 *
 * <h2>用户观察</h2>
 * <p>「出现一些像河流抹开的现象。不成条状」（图：左上与左下各有一片青色块）。</p>
 *
 * <h2>★ 两个可判定的候选（本探针直接区分）</h2>
 * <table border="1">
 *   <tr><th>候选</th><th>判据</th><th>修法</th></tr>
 *   <tr><td><b>A 平滑涂宽</b></td>
 *       <td>该格 <b>原始 flux &lt; 门槛</b> 但 <b>平滑 flux ≥ 门槛</b>
 *           ⇒ 是 3×3 平滑把它"涂"成了河</td>
 *       <td>改判定量（如用原始流量做最终确认 / 或用脊化）</td></tr>
 *   <tr><td><b>B 传播涂宽</b></td>
 *       <td>该格是被 {@code propagateDownstream} 沿 parent 点亮的
 *           （原始/平滑都不到门槛，但上游是河 ⇒ 被传播扩到）</td>
 *       <td>收紧传播（${@code absFloor}）或改传播方向</td></tr>
 * </table>
 *
 * <h2>为什么必须分开量</h2>
 * <p>两者修法完全不同：A 是"判定量口径"，B 是"传播闸门"。
 * 本项目已多次栽在"用局部读数推全局"，故本次<b>全图分类计数 + 出图</b>。</p>
 *
 * <h2>另一个结构性度量：局部河道密度</h2>
 * <p>"条状"的河道格，其 5×5 邻域内河道占比应较低（约 1/5 ~ 2/5）；
 * "抹开"的块则该比例 → 1。⇒ 用【5×5 河道密度 ≥ 0.7】定义"块"。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class ChannelSmearProbe {

    /** "抹开"判据：5×5 邻域内河道占比 ≥ 此值。 */
    private static final double SMEAR_DENSITY = 0.7;

    private ChannelSmearProbe() { }

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
        boolean[] ch = r.channel(), sea = r.sea(), lake = r.lake();
        double[] rawFlux = r.discharge();
        double[] bed = r.bed(), level = r.level();

        final double threshold = 1.0;   // Params.forRunoff 的 channelThreshold

        // ---- 复算 smooth（与生产同口径：3×3、海格不参与）----
        double[] smooth = new double[cells];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (sea[k]) { smooth[k] = rawFlux[k]; continue; }
                double sum = 0; int cnt = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        int kk = a * n + b;
                        if (sea[kk]) continue;
                        sum += rawFlux[kk]; cnt++;
                    }
                }
                smooth[k] = cnt > 0 ? sum / cnt : rawFlux[k];
            }
        }

        // ---- 分类：河道格被分成 条状 / 抹开 ----
        int chTotal = 0, smearCnt = 0;
        int smearBySmooth = 0;      // A：raw<thr 但 smooth>=thr
        int smearByPropagate = 0;   // B：raw<thr 且 smooth<thr（被传播点亮）
        int smearNormal = 0;        // raw>=thr（本来就够，只是邻域密）
        int lineCnt = 0;
        boolean[] smearMask = new boolean[cells];
        // 抹开区的地形/水文归因累计
        double sumSlopeSmear = 0, sumSlopeLine = 0;
        int cntSlopeSmear = 0, cntSlopeLine = 0;
        int smearResolvedNo = 0, smearNearLake = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (!ch[k]) continue;
                chTotal++;
                // 5×5 河道密度
                int c5 = 0, t5 = 0;
                for (int di = -2; di <= 2; di++) {
                    for (int dj = -2; dj <= 2; dj++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        t5++;
                        if (ch[a * n + b]) c5++;
                    }
                }
                double dens = t5 > 0 ? c5 / (double) t5 : 0;
                double dx = 0, dz = 0;
                if (i > 0 && i < n - 1) dx = (bed[(i + 1) * n + j] - bed[(i - 1) * n + j]) / 2;
                if (j > 0 && j < n - 1) dz = (bed[i * n + j + 1] - bed[i * n + j - 1]) / 2;
                double slope = Math.hypot(dx, dz);

                if (dens >= SMEAR_DENSITY) {
                    smearCnt++;
                    smearMask[k] = true;
                    sumSlopeSmear += slope; cntSlopeSmear++;
                    if (!r.resolved()[k]) smearResolvedNo++;
                    boolean nl = false;
                    for (int di = -1; di <= 1 && !nl; di++)
                        for (int dj = -1; dj <= 1 && !nl; dj++) {
                            int a = i + di, b = j + dj;
                            if (a < 0 || b < 0 || a >= n || b >= n) continue;
                            if (lake[a * n + b]) nl = true;
                        }
                    if (nl) smearNearLake++;
                    // 归因 A/B/C
                    if (rawFlux[k] >= threshold) smearNormal++;
                    else if (smooth[k] >= threshold) smearBySmooth++;
                    else smearByPropagate++;
                } else {
                    lineCnt++;
                    sumSlopeLine += slope; cntSlopeLine++;
                }
            }
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 河道「抹开」（成块不成条）归因 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d samples=%d  %dx%d%n%n",
                seed, cx, cz, radius, samples, n, n));
        log.append(String.format("河道格=%d  「抹开」块格=%d (%.2f%%)  条状格=%d (%.2f%%)%n",
                chTotal, smearCnt, 100.0 * smearCnt / Math.max(1, chTotal),
                lineCnt, 100.0 * lineCnt / Math.max(1, chTotal)));
        log.append(String.format("（判据：5×5 邻域河道占比 ≥ %.1f 记为「块」）%n%n", SMEAR_DENSITY));
        log.append("★ 抹开格的三类归因（决定修法）\n");
        log.append(String.format("  A 平滑涂宽（raw<门槛 但 smooth≥门槛） = %d (%.1f%%)%n",
                smearBySmooth, 100.0 * smearBySmooth / Math.max(1, smearCnt)));
        log.append(String.format("  B 传播涂宽（raw<门槛 且 smooth<门槛） = %d (%.1f%%)%n",
                smearByPropagate, 100.0 * smearByPropagate / Math.max(1, smearCnt)));
        log.append(String.format("  C 本就达标（raw≥门槛，只是邻域密）   = %d (%.1f%%)%n",
                smearNormal, 100.0 * smearNormal / Math.max(1, smearCnt)));
        log.append(String.format("%n[地形归因]%n"));
        log.append(String.format("  抹开格 平均坡度=%.4f   条状格 平均坡度=%.4f%n",
                sumSlopeSmear / Math.max(1, cntSlopeSmear),
                sumSlopeLine / Math.max(1, cntSlopeLine)));
        log.append(String.format("  抹开格 未决(!resolved)占比=%.1f%%   紧邻湖占比=%.1f%%%n",
                100.0 * smearResolvedNo / Math.max(1, smearCnt),
                100.0 * smearNearLake / Math.max(1, smearCnt)));
        // ★ 判读按实际计数三选一（初版只比 A/B 且忽略 C —— C 常占 70%，
        //   那样会输出"以A为主"的错判，已据实测数据修正）。
        String verdict;
        if (smearNormal >= smearBySmooth && smearNormal >= smearByPropagate) {
            verdict = "C（本就达标）为主 ⇒ 抹开不是「涂」出来的，是"
                    + "【大面积流量普遍过门槛】：均匀坡面流线不汇聚 ⇒ 水文分布本身如此。"
                    + "对应修法是动量 α（流线收敛性）而非判定口径；"
                    + "传播(B)只是放大器 —— α 过大时未决区被点亮成片";
        } else if (smearBySmooth >= smearByPropagate) {
            verdict = "A（平滑涂宽）为主 ⇒ 修【判定量口径】：平滑只该用于\"抗噪\"，"
                    + "不应把河道涂宽 ⇒ 参考做法 = 用平滑判定\"是否有河\"，"
                    + "但宽度/边界用原始值收紧";
        } else {
            verdict = "B（传播涂宽）为主 ⇒ 修【传播闸门】：传播沿 parent（平原上是 EDT 发散网）"
                    + "⇒ 必须收紧 absFloor，或限制传播只在\"确有成河证据\"处";
        }
        log.append(String.format("%n判读：%s%n", verdict));

        // ---- 出图：抹开格标红 ----
        Path dir = Path.of("build/smear");
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int col;
                if (smearMask[k]) col = 0xFF2020;              // ★ 抹开块
                else if (sea[k]) col = ViewSpec.SEA_COLOR;
                else if (lake[k]) col = ViewSpec.LAKE_COLOR;
                else if (ch[k]) col = ViewSpec.RIVER_COLOR;
                else col = ViewSpec.contourFixed(r.bed(), n, i, j);
                img.setRGB(i, j, col);
            }
        }
        ImageIO.write(img, "png", dir.resolve("smear.png").toFile());
        Files.writeString(dir.resolve("smear.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("smear.png").toAbsolutePath());
        System.out.println("图例：红=「抹开」块 青=条状河道 蓝=湖 深蓝=海 灰=地形");
    }
}