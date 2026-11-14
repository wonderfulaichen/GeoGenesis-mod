package com.geogenesis.worldgen.terrain.tectonicsand;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 沙箱地表探针：<b>俯视图 + 剖面图</b>，并对比"侵蚀前 / 侵蚀后"。
 *
 * <p>用户 2026-11-13 的方法论要求：不能只靠俯视图调参数，要
 * <b>俯视（平面格局）+ 剖面（高程结构）</b>两张图对照设计。
 * 本探针一次产出三张：</p>
 * <ul>
 *   <li>{@code raw.png}    —— 构造+噪声直接出图（无侵蚀）</li>
 *   <li>{@code eroded.png} —— 同一网格经 {@link SandLandscape}（水流功率+坡面扩散）演化后</li>
 *   <li>{@code sections.png} —— 两条正交剖面的高程曲线（侵蚀前后叠画）</li>
 * </ul>
 *
 * <p>用法：{@code SandSurfaceProbe <outDir> [size=1000] [cx] [cz] [radius=1750]}</p>
 */
public final class SandSurfaceProbe {

    private static final long SEED = 9139912035078620160L;

    /** 山影求梯度的半窗（格）。见 {@code shade()} 的说明。 */
    private static final int SHADE_HALF = 4;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/sand-tectonic");
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        double cx = args.length > 2 ? Double.parseDouble(args[2]) : 0.0;
        double cz = args.length > 3 ? Double.parseDouble(args[3]) : 0.0;
        double radius = args.length > 4 ? Double.parseDouble(args[4]) : 1750.0;
        // 可选：内陆起伏档位 0/1/2（参考 cfg.interior），默认 1
        if (args.length > 5) {
            SandTerrain.setInteriorStyle(Integer.parseInt(args[5]));
        }
        System.out.printf(Locale.ROOT, "[diag] interior=%d  mScale=%.3f%n",
                SandTerrain.INTERIOR_STYLE, SandTerrain.MSCALE);
        Files.createDirectories(out);
        long tStart = System.currentTimeMillis();

        // ==================== ① 陆壳分布<b>体检</b>（不再用于标定）====================
        // ★ 2026-11-14 用户裁定："要做无限生成，地球是有限地形" ⇒ 运行期<b>不能</b>
        //   依赖全图分位。壳值→高程的映射已冻结为 SandTerrain.CRUST_ANCHOR_FIXED
        //   （离线标定一次）。这里只<b>量一遍</b>，用来核对冻结常量是否还准；
        //   若偏差大，说明生成函数的分布变了 ⇒ 重新标定并改常量，而不是每张图现算。
        java.util.List<Double> lv = new java.util.ArrayList<>();
        for (int j = 0; j < n; j += 2) {
            double wz = cz - radius + 2 * radius * j / (n - 1);
            for (int i = 0; i < n; i += 2) {
                double wx = cx - radius + 2 * radius * i / (n - 1);
                // 陆/洋判据来自独立大陆层 SandContinent（地壳值），
                // 不是"板块壳标志加权"——后者是平顶板块+台阶海岸的根源。
                SandPlateField.Sample sp = SandPlateField.sample(SEED, wx, wz);
                lv.add(SandContinent.crustAt(SEED, sp.warpedX(), sp.warpedZ()));
            }
        }
        lv.sort(Double::compare);
        {
            double[] a = SandTerrain.CRUST_ANCHOR_FIXED;
            System.out.printf(Locale.ROOT,
                    "[diag] 壳值分布体检：实测分位 v(%.3f)=%.3f v(%.3f)=%.3f v(%.3f)=%.3f"
                            + " v(%.3f)=%.3f v(0.999)=%.3f%n",
                    1.0 - TARGET_LAND - SHELF_FRAC - SLOPE_FRAC,
                    quantile(lv, 1.0 - TARGET_LAND - SHELF_FRAC - SLOPE_FRAC),
                    1.0 - TARGET_LAND - SHELF_FRAC,
                    quantile(lv, 1.0 - TARGET_LAND - SHELF_FRAC),
                    1.0 - TARGET_LAND, quantile(lv, 1.0 - TARGET_LAND),
                    0.0, a[0], quantile(lv, 0.999));
            System.out.printf(Locale.ROOT,
                    "        冻结锚点 v1/v2/v3/v4 = %.3f/%.3f/%.3f/%.3f%n",
                    a[1], a[2], a[3], a[4]);
        }

        double[][] raw = new double[n][n];
        double[][] upliftW = new double[n][n];
        int isletCells = 0;
        double isletMax = 0;
        double[][] isletGrid = new double[n][n];
        for (int j = 0; j < n; j++) {
            double wz = cz - radius + 2 * radius * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - radius + 2 * radius * i / (n - 1);
                SandTerrain.Parts parts = SandTerrain.parts(SEED, wx, wz);
                raw[j][i] = parts.sum();
                isletGrid[j][i] = parts.islet();
                if (parts.islet() > 1e-5) {
                    isletCells++;
                    isletMax = Math.max(isletMax, parts.islet());
                }
                // ★ 抬升加权：造山带内抬升快 ⇒ 侵蚀下切深 ⇒ 山带变成真正的"山区"。
                //   平原保底 0.25，避免被均匀侵蚀削平。
                double oro = Math.max(0.0, parts.orogen());
                upliftW[j][i] = 0.25 + 0.75 * Math.min(1.0, oro / 0.35);
            }
        }
        System.out.printf(Locale.ROOT, "[diag] 岛链覆盖 %.3f%% 格，单点最高 %.1f 格%n",
                100.0 * isletCells / (n * n), isletMax * 236.0);
        double[][] er = new double[n][n];
        for (int j = 0; j < n; j++) {
            System.arraycopy(raw[j], 0, er[j], 0, n);
        }

        double roughRaw = roughness(er, radius);
        long t0 = System.currentTimeMillis();
        System.out.printf(Locale.ROOT, "[diag] 采样+分量 %.1f s（%d 点）%n",
                (t0 - tStart) / 1000.0, (long) n * n);

// ==================== ★ 网格级架构（参考 planet.js 第 6-7 段）====================
        // 这些步骤<b>只能在网格上做</b>（需要全图分布）：
        //   ① 海平面用<b>按纬度面积加权的分位数</b> ⇒ 这张图的陆地占比达标
        //      （⚠ 这是<b>出图用</b>的有限图幅技巧；无限世界的海平面固定 e=0，
        //        壳值→c 的映射则是冻结常量，不依赖全图）
        //   ② 液滴侵蚀（参考的温和修饰）
        //   ③ 陆地高程<b>分位映射到目标分布表</b> ⇒ 低平原/丘陵/山地的面积比类地
        //   ④ <b>河谷下切（LEM）</b>——见下方 ②b
        //   ⑤ 网格后处理（碎岛 / 内陆海 / 海岸地貌 / 近岸陆架 / 极端值）
        double seaShift = quantileSeaLevel(er, radius, TARGET_LAND);
        System.out.printf(Locale.ROOT, "[diag] 1st seaShift=%.0f m%n", seaShift * 11000);
        shift(er, -seaShift);
        // ② 液滴侵蚀（参考第 7 段；<b>1:1 复刻</b>，含 S=1/40000 归一）
        //    液滴数 = min(560000, W·H·0.17)（参考原式）
        int drops = SandErode.erode(er, n, Math.min(560000, (int) (n * n * 0.17)), SEED ^ 0x5DEECE66DL);
        seaShift = quantileSeaLevel(er, radius, TARGET_LAND);
        System.out.printf(Locale.ROOT, "[diag] 侵蚀 %d 滴；2nd seaShift=%.0f m%n",
                drops, seaShift * 11000);
        shift(er, -seaShift);

        // ==================== ②b 河谷下切（LEM）====================
        // ★ 2026-11-14：把 {@link SandLandscape} 接进管线。<b>为什么必须有</b>：
        //   液滴侵蚀是"随机撒点、均匀稀释"的温和修饰，不产生<b>河谷网络</b>；
        //   真实地貌的沟谷来自"水沿最陡方向集中下切"，即水流功率侵蚀
        //   {@code E = K·A^m·S^n}——汇水面积 A 让支流汇入主干，
        //   形成树枝状水系。这也是把海岸线分形维数从 0.97 推向 1.2 的
        //   <b>结构化</b>过程（噪声抖动到不了）。
        double roughBeforeLem = roughness(er, radius);
        SandLandscape.evolve(er, LEM_ITERS, 0.0,
                new SandLandscape.Params(0.06, 0.42, 1.0, 0.04, 1.6e-4, 0.12,
                        0.05, 1e-4, 30.0, 0.5),
                upliftW);
        System.out.printf(Locale.ROOT, "[diag] LEM %d 步：平均相邻高差 %.4f → %.4f%n",
                LEM_ITERS, roughBeforeLem, roughness(er, radius));
        seaShift = quantileSeaLevel(er, radius, TARGET_LAND);
        shift(er, -seaShift);
        mapLandHypsometry(er, 1);
        seaShift = quantileSeaLevel(er, radius, TARGET_LAND);
        System.out.printf(Locale.ROOT, "[diag] 3rd seaShift=%.0f m%n", seaShift * 11000);
        shift(er, -seaShift);

        // ==================== ⑤ 网格后处理（参考 planet.js 第 7 段末）====================
        // 这三步都是"看图才知道要补"的形态修正，参考里逐条有注释：
        int removedIslands = removeSmallIslands(er);
        int filledSeas = fillInlandSeas(er);
        // ★ 海岸地貌（岬湾/峡湾/溺谷）：<b>1:1 复刻参考的幅度与波长</b>。
        //   ⚠ 幅度只有 16~48 m（我们 236 格/e ⇒ 0.3~1 格），本来看不见——
        //   用户 2026-11-14 裁定："先对齐参考，后续再转化到合适的游戏比例。"
        int[] coastStat = applyCoastalLandforms(er, n, radius);
        int shelfBlobs = smoothShelfBlobs(er);
        applyNearShoreShelf(er);
        compressExtremes(er);
        System.out.printf(Locale.ROOT,
                "[diag] 清碎岛 %d 格；填内陆小海 %d 处；岬湾 %d 格、峡湾 %d 条、溺谷 %d 条；"
                        + "陆架碎块填平 %d 格%n",
                removedIslands, filledSeas, coastStat[0], coastStat[1], coastStat[2],
                shelfBlobs);
        seaShift = quantileSeaLevel(er, radius, TARGET_LAND);
        shift(er, -seaShift);
        long ms = System.currentTimeMillis() - t0;

        writeShaded(raw, radius, null, out.resolve("raw.png"));
        // 岛链层单独出一张（灰度：越高越亮）——判断"没有岛"是"没生成"还是"没冒出水面"
        writeShaded(isletGrid, radius, null, out.resolve("islets.png"));
        // ★ 湖/水位：用填洼面算水深（GOSPL 的 wfill/sfill 同一用途）
        double[][] lake = SandLandscape.filledSurface(er, 1e-5);
        double[][] water = new double[n][n];
        int lakeCells = 0;
        double lakeMax = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                // ⚠ 只把<b>陆上</b>洼地当湖：洋底本身处处低于填洼面，
                //   若不分海陆，整个洋盆会被当成"深湖"涂暗（实测整片洋变黑）。
                double d = er[j][i] > 0 ? Math.max(0.0, lake[j][i] - er[j][i]) : 0.0;
                water[j][i] = d;
                if (d > 0.004) {
                    lakeCells++;
                    lakeMax = Math.max(lakeMax, d);
                }
            }
        }
        writeShaded(er, radius, water, out.resolve("eroded.png"));
        // 不叠湖泊的版本：只看最终地表（后处理之后），用于判断形态本身
        writeShaded(er, radius, null, out.resolve("surface.png"));
        System.out.printf(Locale.ROOT, "湖面格数 %d（%.2f%%）  最深湖 %.1f 格%n",
                lakeCells, 100.0 * lakeCells / (n * n), lakeMax * 236.0);
        writeGradient(raw, radius, cx, cz, out.resolve("grad.png"));
        writeSections(raw, er, n, radius, out.resolve("sections.png"));

        System.out.printf(Locale.ROOT,
                "=== SandSurface size=%d radius=%.0f (%.0f,%.0f) (%d ms) ===%n",
                n, radius, cx, cz, ms);
        System.out.printf(Locale.ROOT, "平均 |∇h| (格/格):  原始 %.4f   后处理后 %.4f%n",
                roughRaw, roughness(er, radius));
        hypsometry(er, radius);
        System.out.println("raw/surface/eroded/grad/sections -> " + out);
    }

    /** 平均相邻高差（格/格），衡量地表粗糙度。 */
    private static double roughness(double[][] h, double radius) {
        int n = h.length;
        double cellW = 2 * radius / (n - 1) * 2.0;   // wu→格（1wu=2格）
        double s = 0;
        long c = 0;
        for (int j = 0; j < n - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                s += Math.abs(h[j][i + 1] - h[j][i]) * 236.0 / cellW;
                s += Math.abs(h[j + 1][i] - h[j][i]) * 236.0 / cellW;
                c += 2;
            }
        }
        return s / Math.max(1, c);
    }

    // ============================================================ 俯视图

    /**
     * 山影着色。<b>可传入水面深度</b>（非 null 时，水深&gt;0 的格画成水面色——湖/海）。
     */
    private static void writeShaded(double[][] f, double radius, double[][] water, Path p)
            throws Exception {
        int n = f.length;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double h = f[j][i];
                boolean isWater = (water != null && water[j][i] > 0.004) || h <= 0;
                int r, g, b;
                if (isWater) {
                    // 水面：浅水偏青、深水偏蓝。深度 = max(湖深, 海深)
                    double depth = Math.max(water != null ? water[j][i] : 0.0,
                            Math.max(0.0, -h));
                    double t = Math.min(1.0, depth / 0.45);
                    // 浅水也要比陆地暗（否则海岸出现一圈亮描边）
                    r = (int) (40 + 45 * (1 - t));
                    g = (int) (78 + 52 * (1 - t));
                    b = (int) (110 + 60 * (1 - t));
                } else {
                    double t = Math.min(1.0, h / 0.30);
                    // 低地深绿 → 高地黄褐 → 山顶灰白
                    r = (int) (58 + 150 * t);
                    g = (int) (112 + 70 * t);
                    b = (int) (52 + 90 * t);
                }
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(shade(img, f, radius, water), "png", p.toFile());
        System.out.println("image -> " + p);
    }

    private static BufferedImage shade(BufferedImage src, double[][] h, double radius, double[][] water) {
        int n = h.length;
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        // 光照强度按"每格高差"算，跟分辨率无关：先用格数/像素归一
        double cellBlocks = 2 * radius / (n - 1) * 2.0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int c = src.getRGB(i, j);
                // 水面不参与山影（水面是平的）；只按水深略调亮度让湖有层次
                if (water != null && water[j][i] > 0.004) {
                    double t = Math.min(1.0, Math.max(water[j][i],
                            Math.max(0.0, -h[j][i])) / 0.45);
                    double sh = 1.0 - 0.18 * t;
                    out.setRGB(i, j, (clamp((int) (((c >> 16) & 0xFF) * sh)) << 16)
                            | (clamp((int) (((c >> 8) & 0xFF) * sh)) << 8)
                            | clamp((int) ((c & 0xFF) * sh)));
                    continue;
                }
                int i0 = Math.max(0, i - SHADE_HALF), i1 = Math.min(n - 1, i + SHADE_HALF);
                int j0 = Math.max(0, j - SHADE_HALF), j1 = Math.min(n - 1, j + SHADE_HALF);
                double gx = (h[j][i1] - h[j][i0]) * 236.0 / ((i1 - i0) * cellBlocks);
                double gy = (h[j1][i] - h[j0][i]) * 236.0 / ((j1 - j0) * cellBlocks);
                double nx = -gx, ny = -gy, nz = 1.0;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double d = (-nx * 0.5 - ny * 0.5 + nz * 0.7071) / len;
                // ⚠ 海面下山影必须<b>弱</b>：陆坡是图上最陡处，用陆地的 0.35~1.35 对比度
                //   会把整圈陆坡压成"黑描边"（用户多轮反馈的观感问题）。
                //   大陆坡本身是对的（坡度图确实最陡），错的是把它渲成黑边。
                boolean sea = h[j][i] <= 0;
                double sh = sea ? 0.80 + 0.35 * Math.max(0, Math.min(1, d))
                        : 0.35 + 1.0 * Math.max(0, Math.min(1, d));
                int r = clamp((int) (((c >> 16) & 0xFF) * sh));
                int g = clamp((int) (((c >> 8) & 0xFF) * sh));
                int b = clamp((int) ((c & 0xFF) * sh));
                out.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ============================================================ 网格后处理（参考 planet.js 第 7 段末）

    /**
     * <b>清理碎屑小岛</b>（参考："完整陆缘清掉 ≤8 格的碎岛"）。
     *
     * <p>取 4 连通分量，面积小于 {@link #MIN_ISLAND_CELLS} 的陆块填回海面下
     * （参考设 −15 m）。理由：碎岛来自噪声在阈值附近抖动，视觉上是噪点。</p>
     *
     * @return 被清掉的格数
     */
    private static int removeSmallIslands(double[][] h) {
        int n = h.length;
        boolean[] seen = new boolean[n * n];
        int[] stack = new int[n * n];
        int cleared = 0;
        for (int s0 = 0; s0 < n * n; s0++) {
            if (h[s0 / n][s0 % n] <= 0 || seen[s0]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = s0;
            seen[s0] = true;
            int count = 0;
            // 先收集
            int start = 0;
            while (start < sp) {
                int c = stack[start++];
                count++;
                int y = c / n, x = c % n;
                for (int k = 0; k < 4; k++) {
                    int xx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
                    int yy = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    int j = yy * n + xx;
                    if (!seen[j] && h[yy][xx] > 0) {
                        seen[j] = true;
                        stack[sp++] = j;
                    }
                }
            }
            if (count < MIN_ISLAND_CELLS) {
                for (int k = 0; k < sp; k++) {
                    int c = stack[k];
                    h[c / n][c % n] = -15.0 / 11000.0;
                }
                cleared += sp;
            }
        }
        return cleared;
    }

    /**
     * 碎岛门限（格）。
     *
     * <p>⚠ 2026-11-14 核对修正：参考是 {@code minKeep = [9,4,1][coast]} 格，
     * 在他们的 W×H（≤1024×512 ≈ 52 万格）上占 <b>0.0017%</b>。
     * 我之前"按 1000² 放大到 120 格"把比例放大到 0.012%（6.5 倍）——
     * 而岛链里小岛的**露出水面部分**只有 20~40 格，会被这一步直接抹掉
     * （岛链层有 5.5% 覆盖，图上却只剩几点，一半原因在这里）。
     * 改按<b>比例</b>取：1000² × 0.0017% ≈ 17 格。</p>
     */
    private static final int MIN_ISLAND_CELLS = 17;

    /**
     * <b>填平陆间小海</b>（参考："被陆地围住的小块'海'：用周围陆地高度平滑填平并留浅盆"）。
     *
     * <p>参考注释：<i>"避免平底台地与环形湖"</i>。做法：
     * ① 4 连通分出水体；② 最大的那个是海洋，保留；
     * ③ 面积 ≥ {@link #INLAND_SEA_KEEP} 的保留为内陆海（里海规模）；
     * ④ 其余：边界高度均值 → 向内扩散 120 次 → 中心比边界低 25 m（浅盆）。</p>
     *
     * <p>★ 这是用户圈出的"细长人工湖"的根治手段：那些湖是离散边界上的裂谷谷底，
     * 一长条、平底、边缘笔直——正是"平底台地与环形湖"。</p>
     *
     * @return 被填的海域个数
     */
    private static int fillInlandSeas(double[][] h) {
        int n = h.length;
        int[] comp = new int[n * n];
        java.util.Arrays.fill(comp, -1);
        java.util.List<int[]> comps = new java.util.ArrayList<>();
        int[] stack = new int[n * n];
        for (int s0 = 0; s0 < n * n; s0++) {
            if (h[s0 / n][s0 % n] > 0 || comp[s0] >= 0) {
                continue;
            }
            int id = comps.size();
            int sp = 0;
            stack[sp++] = s0;
            comp[s0] = id;
            int start = 0;
            while (start < sp) {
                int c = stack[start++];
                int y = c / n, x = c % n;
                for (int k = 0; k < 4; k++) {
                    int xx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
                    int yy = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    int j = yy * n + xx;
                    if (comp[j] < 0 && h[yy][xx] <= 0) {
                        comp[j] = id;
                        stack[sp++] = j;
                    }
                }
            }
            int[] list = new int[sp];
            System.arraycopy(stack, 0, list, 0, sp);
            comps.add(list);
        }
        if (comps.isEmpty()) {
            return 0;
        }
        int main = 0;
        for (int k = 1; k < comps.size(); k++) {
            if (comps.get(k).length > comps.get(main).length) {
                main = k;
            }
        }
        int filled = 0;
        for (int k = 0; k < comps.size(); k++) {
            if (k == main) {
                continue;
            }
            int[] list = comps.get(k);
            if (list.length >= INLAND_SEA_KEEP) {
                continue;   // 里海规模：保留
            }
            // 边界高度均值
            double sum = 0;
            int cnt = 0;
            for (int c : list) {
                int y = c / n, x = c % n;
                for (int d = 0; d < 4; d++) {
                    int xx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int yy = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    if (h[yy][xx] > 0) {
                        sum += h[yy][xx];
                        cnt++;
                    }
                }
            }
            double edgeMean = cnt > 0 ? sum / cnt : 5.0 / 11000.0;
            // 扩散 120 次（把边界高度抹进内部）
            int m = list.length;
            double[] val = new double[m];
            java.util.Arrays.fill(val, edgeMean);
            for (int it = 0; it < 120; it++) {
                for (int q = 0; q < m; q++) {
                    int c = list[q];
                    int y = c / n, x = c % n;
                    double s = 0;
                    int cc = 0;
                    for (int d = 0; d < 4; d++) {
                        int xx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int yy = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                            continue;
                        }
                        s += h[yy][xx] > 0 ? h[yy][xx] : val[q];
                        cc++;
                    }
                    val[q] = cc > 0 ? s / cc : edgeMean;
                }
            }
            // 到岸距离 → 浅盆（参考：中央比岸缘再低 25 m，用 sqrt 剖面）
            int[] dd = new int[m];
            java.util.Arrays.fill(dd, -1);
            int[] q = new int[m];
            int qh = 0, qt = 0;
            for (int k2 = 0; k2 < m; k2++) {
                int c = list[k2];
                int y = c / n, x = c % n;
                boolean edge = false;
                for (int d = 0; d < 4 && !edge; d++) {
                    int xx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int yy = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    if (h[yy][xx] > 0) {
                        edge = true;
                    }
                }
                if (edge) {
                    dd[k2] = 1;
                    q[qt++] = k2;
                }
            }
            // 4 连通 BFS（分量内部）
            java.util.Map<Integer, Integer> idx = new java.util.HashMap<>();
            for (int k2 = 0; k2 < m; k2++) {
                idx.put(list[k2], k2);
            }
            while (qh < qt) {
                int cur = q[qh++];
                int c = list[cur];
                int y = c / n, x = c % n;
                for (int d = 0; d < 4; d++) {
                    int xx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int yy = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    Integer nb = idx.get(yy * n + xx);
                    if (nb != null && dd[nb] < 0) {
                        dd[nb] = dd[cur] + 1;
                        q[qt++] = nb;
                    }
                }
            }
            int dm = 1;
            for (int k2 = 0; k2 < m; k2++) {
                dm = Math.max(dm, dd[k2]);
            }
            // 写回：略低于边界，中心再低 25 m（浅盆）
            for (int k3 = 0; k3 < m; k3++) {
                int c = list[k3];
                double drop = 25.0 / 11000.0 * Math.sqrt(Math.max(0, dd[k3]) / (double) dm);
                h[c / n][c % n] = Math.max(SHELF_KEEP_E, val[k3] - drop);
            }
            filled++;
        }
        return filled;
    }

    /** 保留为内陆海的最小面积（格）：参考 0.12% 图幅。 */
    private static final int INLAND_SEA_KEEP = (int) (1000 * 1000 * 0.0012);

    // ============================================================ 海岸地貌（参考第 7 段，1:1 复刻）

    /** 海岸分档：参考 {@code [coast]} 取中档。振幅 30 m、密度乘数 1.0。 */
    private static final double HEADLAND_AMP_M = 30.0;
    private static final double COAST_DENSITY = 1.0;
    private static final long SALT_HEAD = 0x51B7C3A9E24D6081L;
    private static final long SALT_FJORD = 0x3D8A6CF17B29E540L;
    private static final long SALT_RIA = 0x6F24B93D51C7A80EL;

    /**
     * <b>海岸地貌</b>：岬角/海湾、峡湾、溺谷（沙坝潟湖不做——参考注明"远小于一格，
     * 不在格网上硬造，以免沿岸出现一排方齿"）。
     *
     * <p>数值全部照参考：岬湾振幅 {@code [16,30,48]} m 取 30；噪声
     * {@code 0.55·n(0.06/格) + 0.30·n(0.17/格) + 0.15·n(0.45/格)}；
     * 峡湾 {@code carve(26 格内陆, 320 m 深, 25 m 浅, 半径 1.6→0.7)}；
     * 溺谷 {@code carve(9, 70, 6, 2.2→0.8)}。</p>
     *
     * <p>⚠ 两处<b>沙箱适配</b>（不是改设计）：
     * ① <b>纬度</b>：参考用真实 {@code lat}（峡湾 |lat| 48~78°、溺谷 18~52°）。
     *    沙箱是一个平面窗口、没有纬度 ⇒ 按"窗口纵向 ±R ↔ ±90°"的预览约定给一个
     *    伪纬度（落地到模组时换成真实纬度即可）。
     * ② <b>随机数</b>：参考用顺序 RNG（{@code mulberry32}），这里换成<b>逐格哈希</b>
     *    （同分布、与遍历顺序无关，便于将来逐 tile 复现）。</p>
     *
     * @return {岬湾作用格数, 峡湾条数, 溺谷条数}
     */
    private static int[] applyCoastalLandforms(double[][] h, int n, double radius) {
        int nn = n * n;
        boolean[] isLand = new boolean[nn];
        boolean[] isSea = new boolean[nn];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                boolean l = h[j][i] > 0;
                isLand[j * n + i] = l;
                isSea[j * n + i] = !l;
            }
        }
        // 源=海、向陆扩散 ⇒ 陆格"到海的距离"；源=陆、向海扩散 ⇒ 海格"到陆的距离"
        int[] dSea = bfsDist(isSea, isLand, n, 30);
        int[] dLand = bfsDist(isLand, isSea, n, 8);

        // ---- 1) 岬角与海湾：沿岸软硬岩性交替，软岩后退成湾、硬岩突出成岬 ----
        int touched = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                boolean l = isLand[k];
                int d = l ? dSea[k] : dLand[k];
                if (l ? d > 4 : d > 3) {
                    continue;
                }
                double nz = 0.55 * SandPlateField.valueNoise(i * 0.06, j * 0.06, SALT_HEAD)
                        + 0.30 * SandPlateField.valueNoise(i * 0.17 + 40, j * 0.17, SALT_HEAD)
                        + 0.15 * SandPlateField.valueNoise(i * 0.45, j * 0.45, SALT_HEAD + 7919L);
                h[j][i] += HEADLAND_AMP_M / SandTerrain.METERS_PER_E * nz * (1.0 - d / 5.0);
                touched++;
            }
        }
        // 参考在这里<b>重算</b>一次 dSea（岬湾已经改了海岸线）
        dSea = bfsDist(curSea(h, n), curLand(h, n), n, 30);

        // ---- 2) 峡湾 / 3) 溺谷 ----
        java.util.List<Integer> carved = new java.util.ArrayList<>();
        int fjords = 0;
        int rias = 0;
        for (int j = 2; j < n - 2; j++) {
            double lat = Math.abs(90.0 - (j + 0.5) / n * 180.0);   // 伪纬度（见方法注释）
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                if (h[j][i] <= 0) {
                    continue;
                }
                int d = dSea[k];
                double elevM = h[j][i] * SandTerrain.METERS_PER_E;
                if (lat > 48 && lat < 78 && d >= 8 && d <= 22 && elevM > 450) {
                    if (unit(SEED ^ SALT_FJORD, i, j) > 0.012 * COAST_DENSITY || !spaced(carved, k, n, 5)) {
                        continue;
                    }
                    int[] path = descend(h, n, k, 60);
                    if (path != null && path.length > 6) {
                        carve(h, n, path, 26, 320.0, 25.0, 1.6, 0.7);
                        carved.add(path[path.length - 1]);
                        fjords++;
                    }
                } else if (lat > 18 && lat < 52 && d >= 4 && d <= 10
                        && elevM > 110 && elevM < 1300) {
                    if (unit(SEED ^ SALT_RIA, i, j) > 0.006 * COAST_DENSITY || !spaced(carved, k, n, 4)) {
                        continue;
                    }
                    int[] path = descend(h, n, k, 30);
                    if (path != null && path.length > 4) {
                        carve(h, n, path, 9, 70.0, 6.0, 2.2, 0.8);
                        carved.add(path[path.length - 1]);
                        rias++;
                    }
                }
            }
        }
        return new int[]{touched, fjords, rias};
    }

    private static boolean[] curLand(double[][] h, int n) {
        boolean[] a = new boolean[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                a[j * n + i] = h[j][i] > 0;
            }
        }
        return a;
    }

    private static boolean[] curSea(double[][] h, int n) {
        boolean[] a = curLand(h, n);
        for (int k = 0; k < a.length; k++) {
            a[k] = !a[k];
        }
        return a;
    }

    /** 多源 4 连通 BFS：{@code src} 格距离 0，只向 {@code pass} 格扩散，上限 {@code maxD}。 */
    private static int[] bfsDist(boolean[] src, boolean[] pass, int n, int maxD) {
        int nn = n * n;
        int[] d = new int[nn];
        java.util.Arrays.fill(d, 255);
        int[] q = new int[nn];
        int hd = 0, tl = 0;
        for (int i = 0; i < nn; i++) {
            if (src[i]) {
                d[i] = 0;
                q[tl++] = i;
            }
        }
        while (hd < tl) {
            int c = q[hd++];
            if (d[c] >= maxD) {
                continue;
            }
            int y = c / n, x = c % n;
            for (int k = 0; k < 4; k++) {
                int xx = k == 0 ? x + 1 : k == 1 ? x - 1 : x;
                int yy = k == 2 ? y + 1 : k == 3 ? y - 1 : y;
                if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                    continue;
                }
                int j = yy * n + xx;
                if (d[j] == 255 && pass[j]) {
                    d[j] = d[c] + 1;
                    q[tl++] = j;
                }
            }
        }
        return d;
    }

    /** 沿最陡下降走到海；返回路径（格索引，起点→入海点），未在 {@code maxLen} 步内入海返回 null。 */
    private static int[] descend(double[][] h, int n, int start, int maxLen) {
        int[] path = new int[maxLen + 2];
        int len = 0;
        path[len++] = start;
        int c = start;
        for (int step = 0; step < maxLen; step++) {
            int y = c / n, x = c % n;
            int best = -1;
            double bv = h[y][x];
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int yy = y + dy, xx = x + dx;
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    if (h[yy][xx] < bv) {
                        bv = h[yy][xx];
                        best = yy * n + xx;
                    }
                }
            }
            if (best < 0) {
                return null;
            }
            if (len >= path.length) {
                return null;
            }
            path[len++] = best;
            c = best;
            if (h[c / n][c % n] <= 0) {
                int[] out = new int[len];
                System.arraycopy(path, 0, out, 0, len);
                return out;
            }
        }
        return null;
    }

    /**
     * 沿路径<b>从海向陆</b>下切成楔形湾（参考 {@code carve}）。
     *
     * <p>{@code f} 从湾口 0 到内陆 1：半径 {@code r0→r1}，深度 {@code deep→shallow}，
     * 横剖面 {@code (1 − 0.75·(d/r)²)} ⇒ U/V 形；只在比现有地表更低时才写。</p>
     */
    private static void carve(double[][] h, int n, int[] path, int inland,
                              double deepM, double shallowM, double r0, double r1) {
        int cnt = Math.min(path.length - 1, inland);
        for (int k = 0; k < cnt; k++) {
            int c = path[path.length - 2 - k];
            double f = k / (double) Math.max(1, cnt - 1);
            double r = r0 * (1 - f) + r1 * f;
            int cy = c / n, cx = c % n;
            int R = (int) Math.ceil(r);
            for (int dy = -R; dy <= R; dy++) {
                int yy = cy + dy;
                if (yy < 0 || yy >= n) {
                    continue;
                }
                for (int dx = -R; dx <= R; dx++) {
                    int xx = cx + dx;
                    if (xx < 0 || xx >= n) {
                        continue;
                    }
                    double dd = Math.hypot(dx, dy);
                    if (dd > r) {
                        continue;
                    }
                    double targetM = -(shallowM + (deepM - shallowM) * (1 - f))
                            * (1 - 0.75 * (dd / r) * (dd / r));
                    double target = targetM / SandTerrain.METERS_PER_E;
                    if (target < h[yy][xx]) {
                        h[yy][xx] = target;
                    }
                }
            }
        }
    }

    /** 已下切湾口之间的最小间距（格），避免挤成一排。 */
    private static boolean spaced(java.util.List<Integer> carved, int c, int n, int r) {
        int y = c / n, x = c % n;
        for (int o : carved) {
            int oy = o / n, ox = o % n;
            if (Math.hypot(ox - x, oy - y) < r) {
                return false;
            }
        }
        return true;
    }

    /** 逐格哈希 0..1（与遍历顺序无关的"随机数"）。 */
    private static double unit(long salt, int x, int y) {
        long z = (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xC2B2AE3D27D4EB4FL ^ salt;
        z ^= z >>> 30;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27;
        z *= 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
    /** 填平后的最低保留高度（e）：避免填成 0 造成新的海岸台阶。 */
    private static final double SHELF_KEEP_E = 2.0 / 11000.0;

    /**
     * <b>清理陆架上的孤立浅滩</b>（2026-11-14，用户指出"陆架边缘很多破碎的地方"）。
     *
     * <p>地球的大陆架是一块<b>连续</b>的浅水台地（宽度 50~300 km），
     * 边缘只有少量浅滩/沙洲。实测我方陆架带被切成 <b>561 个连通分量</b>、
     * 最大只占 20%——那不是陆架，是"碎玻璃"。</p>
     *
     * <p>成因：细/中频海岸噪声 + 岛链浅滩在陆架带（0~200 m）里留下大量
     * 几个格到几十格的孤立块。修法：取 4 连通分量，面积 &lt;
     * {@link #SHELF_BLOB_MIN} 的填到相邻陆架的中位深度（保持浅水、不造陆）。</p>
     *
     * @return 被填平的格数
     */
    private static int smoothShelfBlobs(double[][] h) {
        int n = h.length;
        boolean[] inShelf = new boolean[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double m = h[j][i] * 11000.0;
                inShelf[j * n + i] = m <= 0 && m > -200;
            }
        }
        boolean[] seen = new boolean[n * n];
        int[] stack = new int[n * n];
        int cleared = 0;
        for (int k0 = 0; k0 < n * n; k0++) {
            if (!inShelf[k0] || seen[k0]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = k0;
            seen[k0] = true;
            int start = 0;
            while (start < sp) {
                int c = stack[start++];
                int y = c / n, x = c % n;
                for (int d = 0; d < 4; d++) {
                    int xx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int yy = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    int nb = yy * n + xx;
                    if (inShelf[nb] && !seen[nb]) {
                        seen[nb] = true;
                        if (sp < stack.length) {
                            stack[sp++] = nb;
                        }
                    }
                }
            }
            if (sp >= SHELF_BLOB_MIN) {
                continue;   // 够大：是陆架本体，保留
            }
            // 小碎块：填到"周围更深水"的深度（不造陆，也不留孤岛）
            for (int s = 0; s < sp; s++) {
                int c = stack[s];
                int y = c / n, x = c % n;
                double sum = 0;
                int cnt = 0;
                for (int d = 0; d < 8; d++) {
                    int xx = x + (d == 0 ? 1 : d == 1 ? -1 : d == 4 ? 1 : d == 5 ? -1
                            : d == 6 ? 1 : d == 7 ? -1 : 0);
                    int yy = y + (d == 2 ? 1 : d == 3 ? -1 : d == 4 ? 1 : d == 5 ? 1
                            : d == 6 ? -1 : d == 7 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    if (h[yy][xx] * 11000.0 <= -200) {
                        sum += h[yy][xx];   // 只参考"坡折外的深水邻居"
                        cnt++;
                    }
                }
                if (cnt > 0) {
                    h[y][x] = sum / cnt;
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /** 陆架连通分量小于此值视为"碎块"（格）。地球陆架是连续台地，不该有大量小块。 */
    private static final int SHELF_BLOB_MIN = 120;

    /**
     * <b>近岸陆架</b>（参考："紧邻陆地的海格水深与相邻陆地高度相当（上限 60 m），
     * 第二圈不超过 120 m"）。
     *
     * <p>参考注释：<i>"这样海陆零线落在两格之间（不贴着陆地格边走出台阶）"</i>
     * ——即用连续地表代替"陆地格/海洋格"的二值台阶。这是海岸看起来自然的最后一环。</p>
     */
    private static void applyNearShoreShelf(double[][] h) {
        int n = h.length;
        boolean[] ring1 = new boolean[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] > 0) {
                    continue;
                }
                double sum = 0;
                int cnt = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int jj = j + dj, ii = i + di;
                        if (ii < 0 || jj < 0 || ii >= n || jj >= n) {
                            continue;
                        }
                        if (h[jj][ii] > 0) {
                            sum += h[jj][ii];
                            cnt++;
                        }
                    }
                }
                if (cnt > 0) {
                    ring1[j * n + i] = true;
                    double cap = Math.min(60.0, sum / cnt * 11000.0) / 11000.0;
                    h[j][i] = -Math.min(-h[j][i], Math.max(SHELF_KEEP_E, cap));
                }
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] > 0 || ring1[j * n + i]) {
                    continue;
                }
                boolean near = false;
                for (int dj = -1; dj <= 1 && !near; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int jj = j + dj, ii = i + di;
                        if (ii < 0 || jj < 0 || ii >= n || jj >= n) {
                            continue;
                        }
                        if (ring1[jj * n + ii]) {
                            near = true;
                            break;
                        }
                    }
                }
                if (near) {
                    h[j][i] = -Math.min(-h[j][i], 120.0 / 11000.0);
                }
            }
        }
    }

    /** 极端值压缩（参考："高山略作压缩，避免极端值"）。 */
    private static void compressExtremes(double[][] h) {
        for (double[] row : h) {
            for (int i = 0; i < row.length; i++) {
                double e = row[i];
                double hi = 5500.0 / 11000.0;
                double lo = -7000.0 / 11000.0;
                if (e > hi) {
                    row[i] = hi + (e - hi) * 0.35;
                } else if (e < lo) {
                    row[i] = lo + (e - lo) * 0.4;
                }
            }
        }
    }

    // ============================================================ 网格级架构步骤

    /**
     * <b>生产管线的完整网格</b>（供其它诊断探针复用，保证"看到的即生产的"）。
     *
     * <p>与 {@link #main} 走同一条链：采样 → 液滴侵蚀 → LEM 河谷下切 →
     * 陆地分位标定 → 碎岛/内陆海/海岸地貌/近岸陆架/极端值 → 海平面。</p>
     */
    static double[][] buildFinalGrid(int n, double cx, double cz, double radius, int interior) {
        SandTerrain.setInteriorStyle(interior);
        double[][] h = new double[n][n];
        double[][] upliftW = new double[n][n];
        for (int j = 0; j < n; j++) {
            double wz = cz - radius + 2 * radius * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - radius + 2 * radius * i / (n - 1);
                SandTerrain.Parts p = SandTerrain.parts(SEED, wx, wz);
                h[j][i] = p.sum();
                double oro = Math.max(0.0, p.orogen());
                upliftW[j][i] = 0.25 + 0.75 * Math.min(1.0, oro / 0.35);
            }
        }
        double seaShift = quantileSeaLevel(h, radius, TARGET_LAND);
        shift(h, -seaShift);
        SandErode.erode(h, n, Math.min(560000, (int) (n * n * 0.17)), SEED ^ 0x5DEECE66DL);
        seaShift = quantileSeaLevel(h, radius, TARGET_LAND);
        shift(h, -seaShift);
        SandLandscape.evolve(h, LEM_ITERS, 0.0,
                new SandLandscape.Params(0.06, 0.42, 1.0, 0.04, 1.6e-4, 0.12,
                        0.05, 1e-4, 30.0, 0.5), upliftW);
        seaShift = quantileSeaLevel(h, radius, TARGET_LAND);
        shift(h, -seaShift);
        mapLandHypsometry(h, 1);
        seaShift = quantileSeaLevel(h, radius, TARGET_LAND);
        shift(h, -seaShift);
        removeSmallIslands(h);
        fillInlandSeas(h);
        applyCoastalLandforms(h, n, radius);
        smoothShelfBlobs(h);
        applyNearShoreShelf(h);
        compressExtremes(h);
        seaShift = quantileSeaLevel(h, radius, TARGET_LAND);
        shift(h, -seaShift);
        return h;
    }

    /** 目标陆地占比（地球 0.339，30 Ma 实测）。 */
    private static final double TARGET_LAND = 0.339;

    /**
     * LEM（河谷下切）迭代步数。
     *
     * <p>每次迭代 = 一个地质时间步（抬升 + 填洼 + 流向 + 汇水 + 水流功率下切 + 坡面扩散）。
     * 30 步足以切出主干—支流的树枝状水系；再多会让山带被削平
     * （沙箱实测：60 步时山顶高程下降 ~15%）。</p>
     */
    static final int LEM_ITERS = 30;

    // ==== 洋底形态的<b>面积</b>目标（占全图面积的比例；地球实测量级）====
    // 地球（占全图）：陆架 <200 m ≈ 5.7%；陆坡+陆隆 200~3900 m ≈ 12%；
    // 其余 48% 是深海平原（p75=p90 都 ≈ 4880 m，即一个平台）。
    // ⚠ 2026-11-14 核对：原 SLOPE_FRAC=0.035 只算"陆坡"（3.5%），漏了
    //   <b>大陆隆起</b>（continental rise, 2200~3900 m）这一阶 ⇒ 洋面 10~25% 的
    //   深度分布与地球对不上（实测 p90 深度 219 m vs 地球 2200 m）。
    //   把陆坡带面积加到 8.0%，让 c 0.30→0.45 那段覆盖"坡+隆"两阶。
    private static final double SHELF_FRAC = 0.057;
    private static final double SLOPE_FRAC = 0.140;

    /** 有序表的分位取值（q ∈ 0..1）。 */
    private static double quantile(java.util.List<Double> sorted, double q) {
        int i = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /**
     * <b>按纬度面积加权的分位数</b>求海平面（参考 planet.js {@code seaLevelShift()}）。
     *
     * <p>为什么必须这样：靠"壳类型阈值"猜陆地占比，在低频聚集场下窗口与窗口差很多
     * （实测同一阈值在不同窗口给出 0.27~0.50）。按面积分位求海平面 ⇒ 陆地占比
     * <b>精确</b>等于目标。参考注释：'否则地壳不足时海平面会被压进大陆坡，
     * 整块大陆被抬高数千米，变成全球高原'——这正是我方"高原感"的病根。</p>
     *
     * @return 应<b>减去</b>的海平面高程（e）
     */
    /**
     * 按面积取分位，返回"高于此高度面积占比 = landFrac"的海平面。
     *
     * <p>⚠ 2026-11-14 核对修正：参考在<b>球面</b>上按 {@code cos(lat)} 加权
     * （极区格子面积小）。我方是<b>平面窗口</b>，没有纬度 ⇒ 权重必须恒为 1。
     * 之前照抄了一个 {@code cos(latFrac·…)} 的假权重，导致"加权分位"与
     * {@link #hypsometry} 的"数格子"两套口径不一致：实测陆地占比 0.366 却报目标 0.339。</p>
     */
    private static double quantileSeaLevel(double[][] h, double radius, double landFrac) {
        int n = h.length;
        // 直方图（0.0002e = 2.2 m 一档，覆盖 [-1.2, +1.2]）
        // ⚠ 2026-11-14：原来 1200 档（22 m 一档）⇒ 分位量化误差让陆地占比偏 0.006
        //   （目标 0.339，实测 0.333）。加密到 12000 档后误差 < 0.001。
        final int bins = 12000;
        final double lo = -1.2, hi = 1.2;
        double[] hist = new double[bins];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = (int) ((h[j][i] - lo) / (hi - lo) * bins);
                k = k < 0 ? 0 : (k >= bins ? bins - 1 : k);
                hist[k] += 1.0;
            }
        }
        double tot = (double) n * n;
        // 从高往低累加，直到累计面积 = 目标陆地占比
        double acc = 0;
        for (int k = bins - 1; k >= 0; k--) {
            acc += hist[k];
            if (acc >= tot * landFrac) {
                return lo + (k + 0.5) * (hi - lo) / bins;
            }
        }
        return 0.0;
    }

    /** 整体平移高度场。 */
    private static void shift(double[][] h, double d) {
        for (double[] row : h) {
            for (int i = 0; i < row.length; i++) {
                row[i] += d;
            }
        }
    }

    /**
     * <b>陆地高程分位映射</b>（参考 planet.js 第 7 段"陆地高程分布标定"）。
     *
     * <p>把陆地高程按<b>分位数</b>单调映射到目标分布表——保持地形相对高低不变，
     * 但面积分布精确变成类地星球的比例。参考的表（interior style 1）：</p>
     * <pre>
     *   分位  0.10  0.25  0.50  0.75  0.90  0.97  0.995
     *   高程  40   150   420   950   1800  3000  4500 m
     * </pre>
     * <p>⚠ 这一步是我此前缺的<b>结构性</b>环节：靠物理参数永远调不出
     * "p50 只有最大值的 3.5%"（地球陆地是低平原 + 稀有高峰）。</p>
     *
     * @param style 0=低缓 1=类地 2=多山（参考的三档）
     */
    private static void mapLandHypsometry(double[][] h, int style) {
        int n = h.length;
        // 平铺的 {分位, 高程m} 对：3 档 × 8 点 × 2 = 48 个数
        // 档 1 = <b>地球实测</b>（p10=80 p25=160 p50=360 p75=720 p90=1240 p99=2760）
        // ⚠ 2026-11-14 核对修正：原先档 1 抄的是参考的风格表（p90=1800 p99≈3600），
        //   但探针打印的"目标"是地球实测值 ⇒ 目标与实际映射不一致，读数永远"超标"。
        //   现在档 1 就是地球实测，档 0/2 保留参考的平滑/崎岖两档作对比。
        double[] qv = {
                0.00, 0, 0.10, 25, 0.25, 90, 0.50, 260, 0.75, 650, 0.90, 1300, 0.97, 2400, 0.995, 3800,
                0.00, 0, 0.10, 80, 0.25, 160, 0.50, 360, 0.75, 720, 0.90, 1240, 0.99, 2760, 0.995, 4500,
                0.00, 0, 0.10, 70, 0.25, 230, 0.50, 620, 0.75, 1300, 0.90, 2300, 0.97, 3600, 0.995, 5000};
        int off = Math.max(0, Math.min(2, style)) * 16;

        // 收集陆地高度的分位（面积加权）
        java.util.List<double[]> land = new java.util.ArrayList<>();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] > 0) {
                    land.add(new double[]{h[j][i], 1.0});
                }
            }
        }
        if (land.size() < 50) {
            return;
        }
        land.sort((a, b) -> Double.compare(a[0], b[0]));
        int m = land.size();
        // 分位 → 当前高程
        double[] qs = new double[8];
        double[] cur = new double[8];
        for (int k = 0; k < 8; k++) {
            qs[k] = qv[off + k * 2];
            cur[k] = land.get(Math.min(m - 1, (int) (m * qs[k])))[0];
        }
        double top = land.get(m - 1)[0];
        // 分段线性映射（单调）
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double e = h[j][i];
                if (e <= 0) {
                    continue;
                }
                int k = 0;
                while (k < 7 && e > cur[k + 1]) {
                    k++;
                }
                double t = (k >= 7) ? 1.0 : (e - cur[k]) / Math.max(1e-9, cur[k + 1] - cur[k]);
                double e0 = qv[off + k * 2 + 1] / SandTerrain.METERS_PER_E;
                double e1 = (k >= 7 ? Math.max(top, 4500.0 / SandTerrain.METERS_PER_E)
                        : qv[off + (k + 1) * 2 + 1] / SandTerrain.METERS_PER_E);
                h[j][i] = Math.max(1e-4, e0 + (e1 - e0) * t);
            }
        }
    }

    /**
     * 高程分布（hypsometry）——逐百分位与地球实测对数字。
     *
     * <p>标定目标（30 Ma 真实地球，65341 格点；2026-11-14 之前从古 DEM 数据实测所得，
     * 该参考项目已按用户裁定删除，<b>原始数据不在库内</b>，数值作为历史标定结果保留）：</p>
     * <pre>
     *   陆地占比 0.339
     *   陆地(m): p10=80  p25=160  p50=360  p75=720  p90=1240  p99=2760  max=10200
     *   海洋(m): p10=280 p25=2200 p50=3880 p75=4880 p90=4880  p99=5640  max=8000
     * </pre>
     * <p>关键结论：地球陆地是<b>低平原</b>（p50 仅 = 最大值的 3.5%），高山只占 1%；
     * 海洋有<b>平坦深海平原</b>（p75=p90=4880m 是同一个平台），且 25% 的洋面浅于 2200m。
     * 换算用统一口径 11000 m/e（= 生产链洋深压缩口径）。</p>
     */
    private static void hypsometry(double[][] h, double radius) {
        int n = h.length;
        java.util.List<Double> land = new java.util.ArrayList<>();
        java.util.List<Double> sea = new java.util.ArrayList<>();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double m = h[j][i] * 11000.0;
                (m > 0 ? land : sea).add(m);
            }
        }
        land.sort(Double::compare);
        sea.sort(Double::compare);
        System.out.printf(Locale.ROOT, "陆地占比 %.3f   （地球目标 0.339）%n",
                land.size() / (double) (n * n));
        System.out.printf(Locale.ROOT, "陆地(m): %s   （目标 p10=80 p25=160 p50=360 p75=720 p90=1240 p99=2760）%n",
                pct(land, new double[]{0.10, 0.25, 0.50, 0.75, 0.90, 0.99}));
        System.out.printf(Locale.ROOT, "海洋(m): %s   （目标 p10=280 p25=2200 p50=3880 p75=4880 p90=4880 p99=5640）%n",
                pct(sea, new double[]{0.10, 0.25, 0.50, 0.75, 0.90, 0.99}));

        // ---- 地质学体检：洋底深度分带（1000 m 一档，占洋面 %）----
        // 地球：0~200 m 陆架 8%、200~3000 坡/隆 17%、3000~4000 上洋盆 8%、
        //       4000~5000 深海平原 45%、5000~6000 深海平原下部 20%、>6000 海沟 2%
        int[] bands = new int[9];
        for (double v : sea) {
            int k = (int) Math.min(8, Math.floor(-v / 1000.0));
            bands[Math.max(0, k)]++;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 9; k++) {
            if (bands[k] == 0) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%d-%dkm %.1f%%  ",
                    k, k + 1, 100.0 * bands[k] / sea.size()));
        }
        System.out.println("洋底深度分带: " + sb);

        // ---- 地质学体检：洋底粗糙度（双尺度）----
        // 地球洋底：1 km 尺度 |∇| 约 30~120 m/km（深海丘陵）；
        //           100 km 尺度约 1~5 m/km（盆地缓倾）。
        double sum1 = 0, sum10 = 0;
        int c1 = 0, c10 = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                if (h[j][i] > 0) {
                    continue;
                }
                sum1 += Math.abs(h[j][i + 1] - h[j][i]);
                c1++;
                if (i + 10 < n) {
                    sum10 += Math.abs(h[j][i + 10] - h[j][i]) / 10.0;
                    c10++;
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "洋底粗糙度: 1格 %.0f m/格   10格 %.0f m/格（比值 %.1f）%n",
                sum1 / Math.max(1, c1) * 11000, sum10 / Math.max(1, c10) * 11000,
                (sum1 / Math.max(1, c1)) / Math.max(1e-9, sum10 / Math.max(1, c10)));

        // ---- 地质学体检：<b>海岸线分形维数</b> + 形状紧凑度 ----
        // 「大陆偏圆」的定量判据（2026-11-14 加）。
        //
        // 算法：用<b>盒计数法</b>（box counting）——这正是分形维数的标准定义：
        //   D = −log(N(ε)) / log(ε)，N(ε) = 边长 ε 的盒子里"含海岸线的盒子数"。
        //   ⚠ 早先用"p1/p2 边数比"估计，得到恒等于 1.0 的假读数：
        //     那种做法把海岸看成"格子边界"，边界数在平滑与锯齿两种情况下
        //     都约等于周长/格宽 ⇒ 无法区分。盒计数才是对的。
        // 真实大陆海岸 D ≈ 1.20~1.35；圆 D → 1.00。
        // 盒计数：ε 从 2 格起（1×1 盒子不可能同时含陆和海，没有意义）
        int[] boxSizes = {2, 4, 8, 16, 32};
        int[] boxCount = new int[boxSizes.length];
        for (int b = 0; b < boxSizes.length; b++) {
            int e = boxSizes[b];
            int cnt = 0;
            for (int j = 0; j + e <= n; j += e) {
                for (int i = 0; i + e <= n; i += e) {
                    boolean hasLand = false, hasSea = false;
                    for (int jj = j; jj < j + e && !(hasLand && hasSea); jj++) {
                        for (int ii = i; ii < i + e; ii++) {
                            if (h[jj][ii] > 0) {
                                hasLand = true;
                            } else {
                                hasSea = true;
                            }
                            if (hasLand && hasSea) {
                                break;
                            }
                        }
                    }
                    if (hasLand && hasSea) {
                        cnt++;   // 这个盒子里有海岸线穿过
                    }
                }
            }
            boxCount[b] = cnt;
        }
        // 最小二乘拟合 log N = −D·log ε + C（用 ε=2..16 四档，避开 32 的边界效应）
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int mfit = 0;
        for (int b = 0; b < boxSizes.length - 1; b++) {
            if (boxCount[b] <= 0) {
                continue;
            }
            double lx = Math.log(boxSizes[b]);
            double ly = Math.log(boxCount[b]);
            sx += lx;
            sy += ly;
            sxx += lx * lx;
            sxy += lx * ly;
            mfit++;
        }
        double frac = 1.0;
        if (mfit >= 2) {
            double slope = (mfit * sxy - sx * sy) / Math.max(1e-12, mfit * sxx - sx * sx);
            frac = -slope;
        }
        StringBuilder bd = new StringBuilder();
        for (int b = 0; b < boxSizes.length; b++) {
            bd.append(String.format(Locale.ROOT, "e=%d:%d ", boxSizes[b], boxCount[b]));
        }
        long landN = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] > 0) {
                    landN++;
                }
            }
        }
        double cellW = 2 * radius / n;
        double perim = boxCount[0] * boxSizes[0] * cellW;
        double area = landN * cellW * cellW;
        double compact = area > 1e-9 ? perim * perim / area : 0;
        System.out.printf(Locale.ROOT,
                "海岸线盒计数: %s%n", bd);
        System.out.printf(Locale.ROOT,
                "海岸线: D=%.3f（地球 1.20~1.35，圆=1.00）  紧凑度 P2/A=%.1f（圆=12.6，地球 30~200）%n",
                frac, compact);
    }

    private static String pct(java.util.List<Double> a, double[] ps) {
        if (a.isEmpty()) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (double p : ps) {
            sb.append(String.format(Locale.ROOT, "p%d=%.0f ",
                    Math.round(p * 100), a.get(Math.min(a.size() - 1, (int) (a.size() * p)))));
        }
        return sb.toString().trim();
    }

    /**
     * <b>真实坡度图</b>（|∇h|，格/格）。
     *
     * <p>用途：把"图上那条线"量化成坡度值。亮度 = 坡度 ×10（坡度 0.25 = 全白）。
     * 若某条"尖线"在这里只有 0.05 的亮度，它就是着色假象；
     * 若接近全白（坡度 &gt; 0.2），那是真实陡坡。</p>
     */
    private static void writeGradient(double[][] h, double radius, double cx, double cz, Path p)
            throws Exception {
        int n = h.length;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double cellBlocks = 2 * radius / (n - 1) * 2.0;
        double maxSlope = 0;
        int mi = 0, mj = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int i0 = Math.max(0, i - SHADE_HALF), i1 = Math.min(n - 1, i + SHADE_HALF);
                int j0 = Math.max(0, j - SHADE_HALF), j1 = Math.min(n - 1, j + SHADE_HALF);
                double gx = (h[j][i1] - h[j][i0]) * 236.0 / ((i1 - i0) * cellBlocks);
                double gy = (h[j1][i] - h[j0][i]) * 236.0 / ((j1 - j0) * cellBlocks);
                double slope = Math.sqrt(gx * gx + gy * gy);
                if (h[j][i] < 0 && slope > maxSlope) {
                    maxSlope = slope;
                    mi = i;
                    mj = j;
                }
                int v = clamp((int) (Math.min(1.0, slope * 4.0) * 255));
                img.setRGB(i, j, (v << 16) | (v << 8) | v);
            }
        }
        ImageIO.write(img, "png", p.toFile());
        double wx = cx - radius + 2 * radius * mi / (n - 1.0);
        double wz = cz - radius + 2 * radius * mj / (n - 1.0);
        System.out.printf(Locale.ROOT,
                "洋区最大坡度 %.3f 格/格 @ (%.1f, %.1f)  -> %s%n", maxSlope, wx, wz, p);
    }

    // ============================================================ 剖面图

    private static void writeSections(double[][] raw, double[][] er, int n, double radius, Path p)
            throws Exception {
        int w = 1400, hgt = 820;
        int ml = 90, mr = 40, mt = 60, mb = 70;
        BufferedImage img = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, hgt);

        // 两条剖面：水平中线 + 竖直中线（都换算成格）
        double[][] lines = new double[4][n];
        int jm = n / 2, im = n / 2;
        for (int i = 0; i < n; i++) {
            lines[0][i] = raw[jm][i] * 236.0;      // 横行 侵蚀前
            lines[1][i] = er[jm][i] * 236.0;       // 横行 侵蚀后
            lines[2][i] = raw[i][im] * 236.0;      // 竖列 侵蚀前
            lines[3][i] = er[i][im] * 236.0;       // 竖列 侵蚀后
        }
        double lo = 1e9, hi = -1e9;
        for (double[] l : lines) {
            for (double v : l) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        double pad = Math.max(8, (hi - lo) * 0.08);
        lo -= pad;
        hi += pad;
        final double loF = lo, hiF = hi;
        int pw = w - ml - mr, ph = hgt - mt - mb;
        java.util.function.DoubleUnaryOperator X = i -> ml + i / (double) (n - 1) * pw;
        java.util.function.DoubleUnaryOperator Y = v -> mt + (hiF - v) / (hiF - loF) * ph;

        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        double yStep = niceStep(hiF - loF, 8);
        for (double v = Math.ceil(loF / yStep) * yStep; v <= hiF; v += yStep) {
            int yy = (int) Y.applyAsDouble(v);
            g.setColor(new Color(232, 232, 232));
            g.drawLine(ml, yy, ml + pw, yy);
            g.setColor(new Color(80, 80, 80));
            g.drawString(String.format(Locale.ROOT, "%.0f", v), 26, yy + 5);
        }
        if (loF < 0 && hiF > 0) {
            g.setColor(new Color(120, 160, 220));
            int y0 = (int) Y.applyAsDouble(0);
            g.setStroke(new BasicStroke(1.4f));
            g.drawLine(ml, y0, ml + pw, y0);
            g.drawString("海平面", ml + pw - 70, y0 - 6);
        }
        Color[] cols = {new Color(190, 190, 190), new Color(20, 90, 20),
                new Color(230, 190, 190), new Color(170, 20, 20)};
        String[] names = {"横行·侵蚀前", "横行·侵蚀后", "竖列·侵蚀前", "竖列·侵蚀后"};
        for (int k = 0; k < 4; k++) {
            g.setColor(cols[k]);
            g.setStroke(new BasicStroke(k % 2 == 1 ? 1.9f : 1.1f));
            int px = -1, py = -1;
            for (int i = 0; i < n; i++) {
                int xx = (int) X.applyAsDouble(i);
                int yy = (int) Math.max(mt - 30, Math.min(mt + ph + 30, Y.applyAsDouble(lines[k][i])));
                if (px >= 0) {
                    g.drawLine(px, py, xx, yy);
                }
                px = xx;
                py = yy;
            }
        }
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.drawString("剖面（穿过图幅中心，单位：格）", ml, 32);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        int lx = ml + 14, ly = mt + 16;
        for (int k = 0; k < 4; k++) {
            g.setColor(cols[k]);
            g.fillRect(lx, ly - 9, 22, 4);
            g.setColor(Color.DARK_GRAY);
            g.drawString(names[k], lx + 28, ly);
            ly += 17;
        }
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
        System.out.println("image -> " + p);
    }

    private static double niceStep(double range, int target) {
        double raw = range / target;
        double mag = Math.pow(10, Math.floor(Math.log10(Math.max(1e-9, raw))));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) {
            if (raw <= m * mag) {
                return m * mag;
            }
        }
        return 10 * mag;
    }
}