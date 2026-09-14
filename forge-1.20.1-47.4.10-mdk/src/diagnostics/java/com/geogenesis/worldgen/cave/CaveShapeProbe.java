package com.geogenesis.worldgen.cave;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.RockType;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 洞穴几何诊断探针（★ 2026-09-15，配套 {@link CaveShape}）。
 *
 * <h3>要回答的问题</h3>
 * <p>洞穴是<b>3D</b>结构，2D 预览看不出，必须专门验证：</p>
 * <ol>
 *   <li><b>洞穴到底有没有出来</b>（体积占比是否在合理区间）；</li>
 *   <li><b>是否挖穿地表</b>（安全判据：必须 <b>0</b>）；</li>
 *   <li><b>岩性耦合是否生效</b>（石灰岩区洞穴应显著多于花岗岩区）；</li>
 *   <li><b>形态是否合理</b>（垂直剖面图目视）。</li>
 * </ol>
 *
 * <h3>为何本探针能跑</h3>
 * <p>洞穴几何被刻意抽成<b>零 MC 依赖</b>的 {@link CaveShape}（见其类注释），
 * 故本探针无需 MC 环境即可复用生产同一套纯函数 —— 绝不复刻第二份实现
 * （复刻会漂移，本项目吃过亏）。</p>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>{@code build/cave/slice_z*.png} —— X-Y 垂直剖面（黑=实心，白=洞穴空洞）</li>
 *   <li>{@code build/cave/hist_*.png} —— 洞穴高度分布直方图</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runCaveShapeProbe [-PprobeArgs="seed chunksPerSide"]}</p>
 */
public final class CaveShapeProbe {

    private CaveShapeProbe() { }

    /** 世界最低 Y（与 {@code GeoGenesisGenerator.WORLD_MIN_Y} 一致）。 */
    private static final int WORLD_MIN_Y = -64;

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int side = args.length > 1 ? Integer.parseInt(args[1]) : 4;   // 每边 chunk 数

        System.out.printf("=== CaveShapeProbe seed=%d side=%d ===%n", seed, side);

        CaveShape.setSeed(seed);
        System.out.printf("[0] 播种: %s%n", CaveShape.isSeeded() ? "OK" : "FAIL");

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        double seaLevel = terrain.heightCurve().seaLevelY();

        // ---------- 逐 chunk 统计 ----------
        // 统计键 = 岩性名；值 = {地下总块数, 洞穴块数}
        Map<String, long[]> byRock = new LinkedHashMap<>();
        long underTotal = 0, caveTotal = 0;
        long breach = 0;                 // 洞顶触及地表（必须 0）
        long columnsWithCave = 0, landColumns = 0;
        int[] caveHeightHist = new int[40];   // 洞穴"中心高度 − 海平面"的分布（16 块一档）

        // 供剖面图使用的缓存
        int px = side * 16;
        int[][] sliceGround = new int[4][px];      // 4 条 z 剖面的地表高
        boolean[][][] sliceCave = new boolean[4][px][512];   // [slice][x][yIndex]
        final int Y_SPAN = 512;                     // y ∈ [WORLD_MIN_Y, WORLD_MIN_Y+512)

        int cx0 = -side / 2, cz0 = -side / 2;

        for (int czz = 0; czz < side; czz++) {
            for (int cxx = 0; cxx < side; cxx++) {
                int cx = cx0 + cxx, cz = cz0 + czz;
                Cell[] cells = terrain.getChunkCells(cx, cz);
                if (cells == null) continue;

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        Cell cell = cells[lx * 16 + lz];
                        if (cell == null) continue;

                        int wx = cx * 16 + lx, wz = cz * 16 + lz;
                        int surface = (int) Math.floor(cell.height);
                        if (surface < seaLevel) continue;    // 海底列跳过（与生产一致）
                        landColumns++;

                        double litho = CaveShape.lithoFactor(cell.rockTypeId);
                        String rockName = rockName(cell.rockTypeId);
                        long[] bucket = byRock.computeIfAbsent(rockName, k -> new long[2]);

                        int underHere = Math.max(0, surface - WORLD_MIN_Y - CaveShape.SURFACE_LID);
                        long under = underHere;
                        long cave = 0;

                        for (int fam = 0; fam < CaveShape.FAMILY_COUNT; fam++) {
                            long span = CaveShape.span(fam, wx, wz, surface, WORLD_MIN_Y, litho);
                            if (span == CaveShape.NO_SPAN) continue;
                            int b = CaveShape.spanBottom(span), t = CaveShape.spanTop(span);
                            cave += (t - b + 1);
                            // 安全判据：洞顶绝不允许进入地表保护带
                            if (t > surface - CaveShape.SURFACE_LID) breach++;
                            // 直方图（用洞顶相对海平面的高度）
                            int bin = (int) ((t - seaLevel + WORLD_MIN_Y) / 16);
                            bin = Math.max(0, Math.min(caveHeightHist.length - 1,
                                    (int) ((t - seaLevel + 128) / 16)));
                            caveHeightHist[bin]++;

                            // 记录剖面（取 4 个 **chunk 行** 整行切片）
                            //   ⚠ 必须按 chunk 行取整行：若按"固定 z 值"匹配，则每张图
                            //     只有 1 列有数据、其余 15/16 列是"无数据"暗带
                            //     （实测踩过：图上出现规则黑条带，误判为伪影）。
                            int rowStride = Math.max(1, side / 4);
                            for (int sl = 0; sl < 4; sl++) {
                                if (czz != sl * rowStride) continue;
                                int gx = cxx * 16 + lx;
                                if (gx < 0 || gx >= px) continue;
                                sliceGround[sl][gx] = surface;
                                for (int y = b; y <= t; y++) {
                                    int yi = y - WORLD_MIN_Y;
                                    if (yi >= 0 && yi < Y_SPAN) sliceCave[sl][gx][yi] = true;
                                }
                            }
                        }

                        underTotal += under;
                        caveTotal += Math.min(cave, under);      // 与上方钳到地下区间（防跨族重叠虚高）
                        bucket[0] += under;
                        bucket[1] += Math.min(cave, under);
                        if (cave > 0) columnsWithCave++;
                    }
                }
            }
        }

        // ---------- 洞穴场密度：大范围【合成扫描】（无偏、种子稳健）----------
        //
        //   ★ 为何不能用真实地形窗口统计密度：洞穴噪声特征尺度为 350 块，而 chunk 窗口
        //     只能覆盖数十~百余块（**不足一个特征**）⇒ 统计会剧烈波动且换种子可能整窗
        //     落在海里。实测：seed=12345 → 7.11%，seed=7 → 0.40%，seed=42 → 陆地列 0
        //     （窗口整片海洋 ⇒ 判据直接 FAIL，属**探针口径错误**而非功能缺陷）。
        //   故密度/直方图改用合成扫描：固定地表高度、跨多个特征尺度、中性岩性。
        final int SYN_SURFACE = (int) (seaLevel + 50);
        long synUnder = 0, synCave = 0;
        int[] synHist = new int[caveHeightHist.length];
        for (int i = 0; i < 6000; i++) {
            int wx = (i % 200) * 17 - 1700;
            int wz = (i / 200) * 19 - 380;
            long cv = 0;
            int topMax = Integer.MIN_VALUE;
            for (int fam = 0; fam < CaveShape.FAMILY_COUNT; fam++) {
                long sp = CaveShape.span(fam, wx, wz, SYN_SURFACE, WORLD_MIN_Y, 1.0);
                if (sp == CaveShape.NO_SPAN) continue;
                int b = CaveShape.spanBottom(sp), t = CaveShape.spanTop(sp);
                cv += (t - b + 1);
                topMax = Math.max(topMax, t);
            }
            long un = Math.max(0, SYN_SURFACE - WORLD_MIN_Y - CaveShape.SURFACE_LID);
            synUnder += un;
            synCave += Math.min(cv, un);
            if (topMax != Integer.MIN_VALUE) {
                int bin = Math.max(0, Math.min(synHist.length - 1,
                        (int) ((topMax - seaLevel + 128) / 16)));
                synHist[bin]++;
            }
        }
        double synPct = synUnder == 0 ? 0.0 : 100.0 * synCave / synUnder;
        System.out.printf("[1b] 合成扫描（大范围无偏，地表固定 %d）: 地下块=%d 洞穴块=%d 密度=%.2f%%%n",
                SYN_SURFACE, synUnder, synCave, synPct);

        System.out.printf("[1] 真实地形窗口：陆地列=%d（其中含洞列=%d，%.1f%%）—— 仅供剖面图与破地表检查%n",
                landColumns, columnsWithCave,
                landColumns == 0 ? 0.0 : 100.0 * columnsWithCave / landColumns);
        System.out.printf("[2] 地下块=%d  洞穴块=%d  洞穴体积占比=%.2f%%%n",
                underTotal, caveTotal,
                underTotal == 0 ? 0.0 : 100.0 * caveTotal / underTotal);

        System.out.println("[3] 按岩性分组（验证岩性耦合是否生效）:");
        System.out.printf("    %-12s %12s %12s %10s%n", "岩性", "地下块", "洞穴块", "洞穴占比");
        for (Map.Entry<String, long[]> e : byRock.entrySet()) {
            long u = e.getValue()[0], c = e.getValue()[1];
            System.out.printf("    %-12s %12d %12d %9.2f%%%n", e.getKey(), u, c,
                    u == 0 ? 0.0 : 100.0 * c / u);
        }

        System.out.println("[4] 洞穴顶高分布（相对海平面，16 块一档；合成扫描）:");
        StringBuilder sb = new StringBuilder("    ");
        for (int i = 0; i < synHist.length; i++) {
            if (synHist[i] == 0) continue;
            sb.append(String.format("%d:%d ", (i - 8) * 16, synHist[i]));
        }
        System.out.println(sb.length() > 8 ? sb.toString() : "    （无洞穴）");

        // ---------- 判据 ----------
        //   ★ 判据 1/2 用【合成扫描】口径（见 [1b] 的理由）：真实地形窗口不足一个
        //     噪声特征尺度，统计无意义且换种子会空转。
        boolean pass1 = synCave > 0;
        System.out.printf("[判据1] 洞穴确实生成（合成扫描体积 > 0）: %s%n", pass1 ? "PASS" : "FAIL");

        boolean pass2 = synPct >= 0.5 && synPct <= 8.0;
        System.out.printf("[判据2] 洞穴密度 ∈ [0.5%%, 8%%]（合成扫描；可见且不过量）: %s（实测 %.2f%%）%n",
                pass2 ? "PASS" : "FAIL", synPct);

        boolean pass3 = breach == 0;
        System.out.printf("[判据3] 未挖穿地表（洞顶越界次数必须 0）: %s（实测 %d；样本列 %d）%n",
                pass3 ? "PASS" : "FAIL", breach, landColumns);
        // ⚠ 判据3 的属性：`span()` 内 `top = min(top, surface − LID)` 使越界在构造上
        //   不可能发生 ⇒ 本判据实为"钳制仍在"的**回归守卫**（非探索性测量）。
        //   另外：若样本窗口整片是海（landColumns=0），本判据会**平凡通过**（实测 seed=42）。

        // 岩性耦合：**受控合成对照**（同点、只换岩性系数）
        //
        //   ★ 为何不用"采样区按岩性分组"：岩性在 900wu 尺度成片，而本探针窗口只有
        //     数十 wu ⇒ 往往只含单一岩性（实测 6×6 chunks 时 100% 是 ANDESITE），
        //     判据会因"缺样本"而形同虚设。改为在同一点上分别用石灰岩/花岗岩系数
        //     求 span ⇒ **直接隔离出耦合机制**（无混杂、无需采样区碰巧含两种岩性）。
        double lsLitho = CaveShape.lithoFactor(RockType.LIMESTONE.ordinal());
        double grLitho = CaveShape.lithoFactor(RockType.GRANITE.ordinal());
        long lsVol = 0, grVol = 0;
        for (int i = 0; i < 512; i++) {
            int wx = i * 7 - 1500, wz = i * 13 - 900;
            int surf = 120;
            for (int fam = 0; fam < CaveShape.FAMILY_COUNT; fam++) {
                long a = CaveShape.span(fam, wx, wz, surf, WORLD_MIN_Y, lsLitho);
                if (a != CaveShape.NO_SPAN) lsVol += CaveShape.spanTop(a) - CaveShape.spanBottom(a) + 1;
                long b = CaveShape.span(fam, wx, wz, surf, WORLD_MIN_Y, grLitho);
                if (b != CaveShape.NO_SPAN) grVol += CaveShape.spanTop(b) - CaveShape.spanBottom(b) + 1;
            }
        }
        double ratio = grVol > 0 ? (double) lsVol / grVol : Double.POSITIVE_INFINITY;
        boolean pass4 = lsVol > 0 && grVol > 0 && ratio > 1.3;
        System.out.printf("[判据4] 岩性耦合生效（受控合成：石灰岩体积 > 花岗岩 ×1.3）: %s%n",
                pass4 ? "PASS" : "FAIL");
        System.out.printf("    石灰岩(×%.2f) 体积=%d  花岗岩(×%.2f) 体积=%d  比值=%.2f×%n",
                lsLitho, lsVol, grLitho, grVol, ratio);

        renderSlices(sliceGround, sliceCave, px, WORLD_MIN_Y, Y_SPAN);

        boolean all = pass1 && pass2 && pass3 && pass4;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }

    private static String rockName(int id) {
        if (id < 0 || id >= RockType.values().length) return "UNKNOWN";
        return RockType.values()[id].name();
    }

    /**
     * 渲染 X-Y 垂直剖面（黑=实心岩体，白=洞穴空洞，蓝=地表以上）。
     *
     * <p>★ 只渲染 {@code y ∈ [RENDER_Y0, RENDER_Y1]} 的<b>可判读窗口</b>：
     * 全高 512 块会让图变成极窄长的条带、洞穴被压成"细斜纹"看不出形态
     * （实测踩过）。裁到 200 块高后纵横比正常，洞穴形态可直接目视判读。</p>
     */
    private static void renderSlices(int[][] sliceGround, boolean[][][] sliceCave,
                                     int px, int yMin, int ySpan) throws Exception {
        final int RENDER_Y0 = -20, RENDER_Y1 = 180;
        int h = RENDER_Y1 - RENDER_Y0;
        File dir = new File("build/cave");
        dir.mkdirs();
        for (int sl = 0; sl < 4; sl++) {
            BufferedImage img = new BufferedImage(px, h, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < px; x++) {
                int surface = sliceGround[sl][x];
                for (int y = RENDER_Y0; y < RENDER_Y1; y++) {
                    int yi = y - yMin;
                    int rgb;
                    if (surface == 0) {
                        rgb = 0x202020;                       // 无数据
                    } else if (y > surface) {
                        rgb = 0x3355AA;                       // 地表以上
                    } else if (yi >= 0 && yi < ySpan && sliceCave[sl][x][yi]) {
                        rgb = 0xFFFFFF;                       // 洞穴空洞
                    } else {
                        rgb = 0x101010;                       // 实心岩体
                    }
                    img.setRGB(x, h - 1 - (y - RENDER_Y0), rgb);   // y 向上
                }
            }
            ImageIO.write(img, "png", new File(dir, "slice_z" + sl + ".png"));
        }
        System.out.printf("[5] 剖面图输出: %s（%d×%d，白=洞穴，y窗口=[%d,%d]）%n",
                dir.getAbsolutePath(), px, h, RENDER_Y0, RENDER_Y1);
    }
}
