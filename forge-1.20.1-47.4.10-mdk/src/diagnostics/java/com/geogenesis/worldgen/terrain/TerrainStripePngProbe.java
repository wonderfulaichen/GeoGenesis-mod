package com.geogenesis.worldgen.terrain;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 地形条纹「可视化导出」探针（★ 2026-09-12，第三次实测反馈后新增）。
 *
 * <h3>为什么必须做图像导出（方法论反思）</h3>
 * <p>同一条纹伪影已"定位→修复→仍存在"循环三次。根本教训：
 * <b>阈值型数值探针</b>只能证明"某个量超阈值"，无法回答"画面看起来是否正常"，
 * 而伪影的最终判据是<b>视觉</b>的。故本探针先在<b>自己的渲染里重现伪影</b>，再据此定位。
 *
 * <h3>输出（build/stripe/）</h3>
 * <ul>
 *   <li>{@code eLand_contrast.png} —— eLand 局部对比拉伸（弱纹理放大到可见）</li>
 *   <li>{@code gradmag.png} —— |∇eLand|（gamma 提亮），细线=梯度脊会极亮</li>
 *   <li>{@code type_dominant.png} —— 主导地形类型着色（定位伪影落在哪种类型）</li>
 *   <li>{@code type_*.png} —— 各类型噪声单独渲染（逐配方排查）</li>
 * </ul>
 *
 * <p>自动定位：未指定 origin 时自动扫描寻找<b>陆地 + 丘陵/高原主导</b>的区域，
 * 以复现用户截图（陆地丘陵）而非默认的海域。
 *
 * <p>用法：{@code gradlew runTerrainStripePngProbe [-PprobeArgs="seed size step originX originZ"]}
 */
public final class TerrainStripePngProbe {

    private static long seedUsed;

    /** 主导地形类型 ordinal（无数据 → -1）。 */
    private static int dominantOrdinal(Cell c) {
        if (c == null || c.typeWeights == null) return -1;
        double best = -1;
        int bi = -1;
        for (int i = 0; i < c.typeWeights.length; i++) {
            if (c.typeWeights[i] > best) { best = c.typeWeights[i]; bi = i; }
        }
        return bi;
    }

    private static int[] typeColors(int n) {
        int[] c = new int[n];
        for (int i = 0; i < n; i++) c[i] = 0x808080;
        c[TerrainClass.OCEAN.ordinal()] = 0x1E3A8A;
        c[TerrainClass.DEEP_OCEAN.ordinal()] = 0x0B1A3A;
        c[TerrainClass.PLAIN.ordinal()] = 0x7CB342;
        c[TerrainClass.HILLS.ordinal()] = 0xA1887F;
        c[TerrainClass.MOUNTAINS.ordinal()] = 0xD84315;
        c[TerrainClass.PLATEAU.ordinal()] = 0xC0A020;
        c[TerrainClass.BASIN.ordinal()] = 0x5C6BC0;
        return c;
    }

    public static void main(String[] args) throws Exception {
        long seed = 5436529513624899584L;
        int size = 1000;
        int step = 4;
        Integer ox = null, oz = null;
        if (args.length > 0) seed = Long.parseLong(args[0]);
        if (args.length > 1) size = Integer.parseInt(args[1]);
        if (args.length > 2) step = Integer.parseInt(args[2]);
        if (args.length > 3) ox = Integer.parseInt(args[3]);
        if (args.length > 4) oz = Integer.parseInt(args[4]);

        File outDir = new File("build/stripe");
        outDir.mkdirs();
        seedUsed = seed;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        // ---------- 自动定位：地质活跃（|T5 形变| 最大）且为陆地 ----------
        //   用户明确"是地质系统问题"，故必须落在构造影响区内才能复现，
        //   否则会像第一轮那样在无地质影响的区域渲染出"一切正常"的假象。
        if (ox == null || oz == null) {
            int[] hit = findGeologyActive(terrain, seed);
            ox = hit[0]; oz = hit[1];
            System.out.printf("自动定位(地质活跃) origin=(%d,%d)%n", ox, oz);
        }
        System.out.printf("=== TerrainStripePngProbe seed=%d size=%d step=%d origin=(%d,%d) ===%n",
                seed, size, step, ox, oz);

        // ---------- [A] 各类型噪声单独渲染（供逐配方排查）----------
        TypeNoiseProvider provider = new TypeNoiseProvider(tp.beltReliefAmp(), tp.basinBase());
        provider.seed(seed);
        TerrainClass[] types = {
            TerrainClass.PLAIN, TerrainClass.HILLS, TerrainClass.MOUNTAINS,
            TerrainClass.PLATEAU, TerrainClass.BASIN
        };
        // ★ 2026-09-12 关键诊断：类型配方噪声【同时】输出原图与【高通图】。
        //   伪影是"细小幅度、沿等高线"的纹路，原图看不清，高通后必然极醒目。
        //   若某个配方的【高通图】出现与用户截图一致的花纹 ⇒ 该配方即根因。
        for (TerrainClass tc : types) {
            double[][] t = new double[size][size];
            double tmn = 1e9, tmx = -1e9;
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    double v = provider.computeNoise(tc, ox + px * (double) step,
                                                          oz + py * (double) step);
                    t[py][px] = v;
                    tmn = Math.min(tmn, v); tmx = Math.max(tmx, v);
                }
            }
            ImageIO.write(toGray(t, tmn, tmx), "png", new File(outDir, "type_" + tc + ".png"));
            // 高通：减 12wu 窗口均值（细纹尺度）；对比拉伸到满量程
            double[][] hpt = new double[size][size];
            double hpmax = 1e-12;
            int R = 12;
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    double s = 0; int c = 0;
                    for (int dy = -R; dy <= R; dy += 4) {
                        int yy = py + dy; if (yy < 0 || yy >= size) continue;
                        for (int dx = -R; dx <= R; dx += 4) {
                            int xx = px + dx; if (xx < 0 || xx >= size) continue;
                            s += t[yy][xx]; c++;
                        }
                    }
                    hpt[py][px] = t[py][px] - (c > 0 ? s / c : t[py][px]);
                    hpmax = Math.max(hpmax, Math.abs(hpt[py][px]));
                }
            }
            BufferedImage hpi = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    int g = (int) Math.round(127.5 + 127.5 * hpt[py][px] / hpmax);
                    g = Math.max(0, Math.min(255, g));
                    hpi.setRGB(px, py, (g << 16) | (g << 8) | g);
                }
            }
            ImageIO.write(hpi, "png", new File(outDir, "typeHP_" + tc + ".png"));
            System.out.printf("  type_%s: 值域=[%.4f,%.4f] 高通max=%.5f%n", tc, tmn, tmx, hpmax);
        }
        System.out.println("  写出 type_*.png / typeHP_*.png");

        // ---------- [B] 完整合成场（不含侵蚀 = "大范围预览"同路径）----------
        render(terrain, outDir, ox, oz, size, step, "", 40);
        System.out.println("输出目录: " + outDir.getAbsolutePath());

        // ---------- [C2] 完整管线（含侵蚀/雕刻）—— 用户坐标 (-82,-65) ----------
        //   用户实机截图显示【同心阶梯等高带】；而粗管线(不含侵蚀)在同一坐标是平滑的
        //   ⇒ 阶梯必然来自粗管线跳过的那一段。故必须渲染完整管线的真实落块高度。
        renderFullHeight(terrain, outDir, -200, -250, 19, "_full");

        // ---------- [D] 构造分量单独渲染（定位"细线"落在哪个分量）----------
        renderComponents(outDir, seed, ox, oz, size, step);

        // ---------- [F] 分量梯度排查：谁的 |∇| 里有"直线折痕"（★ 第七轮排查）----------
        //   用户 gradmag.png 显示【笔直细线段 + Y 形交汇】= Voronoi 边/顶点签名，
        //   与折叠/量化产生的"波浪等值线"几何完全不同。
        //   本节对 eLand 的每个输入分量单独求梯度幅值并统计长尾比，
        //   带"折痕线"的分量会有极端 P99.9/P50 ⇒ 一次运行直接锁定元凶。
        componentGradients(outDir, seed, ox, oz, size, step);

        // ---------- [E] 数值剖面：直接判定"细线"是台阶(量化)还是连续起伏 ----------
        //   在暗区（低梯度、细线密集处）沿 x 以 1wu 步长打印 eLand，
        //   并统计相邻差的取值集合——若收敛到离散倍数 ⇒ 值域量化（台阶）⇒ 细线即等高线台阶。
        System.out.println();
        System.out.println("--- 剖面 A: z=700, x∈[1900,2500] ---");
        dumpProfile(terrain, 1900, 2500, 700);
        System.out.println();
        System.out.println("--- 剖面 B: z=0, x∈[-200,200] ---");
        dumpProfile(terrain, -200, 200, 0);
    }

    /**
     * ★ 2026-09-12 第七轮排查：逐分量 |∇| 排查 —— 定位"直线折痕"属于哪个场。
     *
     * <p>用户 gradmag.png 的细线呈<b>笔直长段 + Y 形交汇</b>，这是 Voronoi 边/顶点的
     * 几何签名（折叠/量化产生的是波浪状等值线，不会笔直）。候选携带 Voronoi 的场：
     * 类型权重（400wu 细胞竞争）、LandFeatures（800/200wu 特征格点）、TectonicField（2000wu）。
     *
     * <p>判据：对每个分量求梯度幅值 |∇|，统计 {@code P99.9 / P50} 长尾比。
     * 平滑场该比值 ~3~8；带<b>折痕线</b>的场，线上的 |∇| 远高于线外 ⇒ 长尾比极大。
     * 比值最高者即元凶，其 {@code gmagC_<name>.png} 应与用户截图同构。</p>
     */
    private static void componentGradients(File outDir, long seed, int ox, int oz, int size, int step) throws Exception {
        TerrainParams tp = TerrainParams.defaults();
        ContinentField cont = new ContinentField(tp); cont.seed(seed);
        TypeLandShape tls = new TypeLandShape(tp); tls.seed(seed);
        CoastlineField cf = new CoastlineField(tp); cf.seed(seed);
        LandFeatures lf = new LandFeatures(); lf.seed(seed);
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);
        double bias = tp.continentBias();

        record Comp(String name, java.util.function.DoubleBinaryOperator fn) {}
        java.util.List<Comp> comps = new java.util.ArrayList<>();
        comps.add(new Comp("continent_c", cont::sample));
        comps.add(new Comp("coast_warp", (x, z) -> cf.warpDisplacement(x, z, cont.sample(x, z) - bias)));
        for (TerrainClass tc : TerrainClass.values()) {
            final TerrainClass t = tc;
            comps.add(new Comp("w_" + t, (x, z) -> tls.sampleBlend(x, z).typeWeights[t.ordinal()]));
        }
        comps.add(new Comp("shared_noise", tls.typeGenerators()::computeSharedNoise));
        comps.add(new Comp("landfeat", (x, z) -> lf.compute(x, z).total));
        comps.add(new Comp("tect_dist", (x, z) -> tf.sample(x, z).dist()));
        comps.add(new Comp("tect_stress", (x, z) -> tf.sample(x, z).stress()));
        comps.add(new Comp("tect_chain", (x, z) -> tf.boundaryStrengthChained(tf.sample(x, z), x, z)));
        comps.add(new Comp("tect_deform", (x, z) -> td.offset(tf.sample(x, z), x, z)));

        record Stat(String name, double p50, double p999, double max, double ratio) {}
        java.util.List<Stat> stats = new java.util.ArrayList<>();
        for (Comp comp : comps) {
            double[][] v = new double[size][size];
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    v[py][px] = comp.fn().applyAsDouble(ox + px * (double) step, oz + py * (double) step);
                }
            }
            double[][] gm = new double[size][size];
            java.util.List<Double> vals = new java.util.ArrayList<>(size * size);
            for (int py = 1; py < size - 1; py++) {
                for (int px = 1; px < size - 1; px++) {
                    double dx = (v[py][px + 1] - v[py][px - 1]) / (2.0 * step);
                    double dz = (v[py + 1][px] - v[py - 1][px]) / (2.0 * step);
                    double g = Math.hypot(dx, dz);
                    gm[py][px] = g;
                    vals.add(g);
                }
            }
            vals.sort(Double::compare);
            int n = vals.size();
            double p50 = vals.get(n / 2), p999 = vals.get((int) (n * 0.999)), mx = vals.get(n - 1);
            double ratio = p50 > 1e-12 ? p999 / p50 : Double.POSITIVE_INFINITY;
            stats.add(new Stat(comp.name(), p50, p999, mx, ratio));
            ImageIO.write(toGray(gm, 0, mx), "png", new File(outDir, "gmagC_" + comp.name() + ".png"));
        }
        stats.sort((a, b) -> Double.compare(b.ratio(), a.ratio()));
        System.out.println("--- [F] 分量梯度长尾比（P99.9/P50，高 = 含折痕线）---");
        for (Stat s : stats) {
            System.out.printf("  %-14s P50=%.6g P99.9=%.6g max=%.6g ratio=%8.1f%n",
                    s.name(), s.p50(), s.p999(), s.max(), s.ratio());
        }
    }

    /**
     * 渲染<b>侵蚀增量</b>（{@code erosionDeltaE}，e 单位）—— 用于判定条纹是否来自侵蚀层。
     *
     * <p>对比逻辑：基础场（粗管线）已证明几乎无此纹理。
     * 若本图出现同样的密集平行条纹 ⇒ 伪影确定来自侵蚀阶段。</p>
     */
    private static void renderErosionDelta(CellGenerator gen, File outDir,
                                           int ox, int oz, int size, int step, String tag) throws Exception {
        int n = size / step;
        double[][] d = new double[n][n];
        double mn = 1e9, mx = -1e9;
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                double v = gen.erosionDeltaE(ox + px * (double) step, oz + py * (double) step);
                d[py][px] = v;
                mn = Math.min(mn, v);
                mx = Math.max(mx, v);
            }
        }
        ImageIO.write(toGray(d, mn, mx), "png", new File(outDir, "erosion_delta" + tag + ".png"));
        // 有符号可视化（0 = 中灰，负=暗、正=亮）便于看正负交替的脊-谷条纹
        BufferedImage signed = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double amp = Math.max(Math.abs(mn), Math.abs(mx));
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int g = (int) Math.round(127.5 + 127.5 * d[y][x] / Math.max(1e-9, amp));
                g = Math.max(0, Math.min(255, g));
                signed.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        ImageIO.write(signed, "png", new File(outDir, "erosion_delta_signed" + tag + ".png"));
        System.out.printf("  [erosion delta] %d wu @%dwu 范围=[%.5f,%.5f] e -> 写出 2 图%n",
                size, step, mn, mx);
    }

    /**
     * 完整管线（{@code getChunkCells}，含侵蚀 tile 与水文雕刻）渲染最终 {@code height}。
     *
     * <p>与粗管线（{@link GeoGenesisTerrain#sampleCellCoarse}）的差别就是"是否含侵蚀"。
     * 若伪影来自 {@code RidgeValleyErosion}（脊-谷条纹滤镜，按坡度门控 →
     * 低地无、山地有），则<b>只有本路径能复现</b>。</p>
     */
    private static void renderFullHeight(GeoGenesisTerrain terrain, File outDir,
                                         int ox, int oz, int chunks, String tag) throws Exception {
        int px = chunks * 16;
        int baseCX = Math.floorDiv(ox, 16), baseCZ = Math.floorDiv(oz, 16);
        double[][] h = new double[px][px];
        for (int cx = 0; cx < chunks; cx++) {
            for (int cz = 0; cz < chunks; cz++) {
                Cell[] cells = terrain.getChunkCells(baseCX + cx, baseCZ + cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        // X 主序：cells[lx*16+lz]（与 generateChunk 一致）
                        Cell c = cells[lx * 16 + lz];
                        h[cz * 16 + lz][cx * 16 + lx] = (c == null) ? 0 : c.height;
                    }
                }
            }
        }
        double mn = 1e9, mx = -1e9, gMax = 1e-9;
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                mn = Math.min(mn, h[y][x]);
                mx = Math.max(mx, h[y][x]);
            }
        }
        double[][] gm = new double[px][px];
        for (int y = 1; y < px - 1; y++) {
            for (int x = 1; x < px - 1; x++) {
                double dx = (h[y][x + 1] - h[y][x - 1]) / 2.0;   // 块/wu
                double dz = (h[y + 1][x] - h[y - 1][x]) / 2.0;
                gm[y][x] = Math.hypot(dx, dz);
                gMax = Math.max(gMax, gm[y][x]);
            }
        }
        // ★ 连续高度
        ImageIO.write(toGray(h, mn, mx), "png", new File(outDir, "height_full" + tag + ".png"));
        // ★★★ 取整高度（= 游戏落块 surfaceY = floor(height)）★★★
        //   这是"人眼在游戏里真正看到的表面"。任何连续地形取整后都会产生
        //   1 格等高台阶 —— 本图用于判定"同心阶梯带"是【取整的必然结果】
        //   还是【某个边界/量化缺陷】（前者下不会比后者更严重）。
        double[][] hf = new double[px][px];
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) hf[y][x] = Math.floor(h[y][x]);
        }
        ImageIO.write(toGray(hf, Math.floor(mn), Math.floor(mx)),
                "png", new File(outDir, "height_blocky" + tag + ".png"));

        // ★★★ 1 格等高台阶（黑线白底）——判定"同心阶梯带"性质的直接证据 ★★★
        //   游戏落块 surfaceY = floor(height)，凡 floor 值变化处就是 1 格台阶（等高线）。
        //   若本图与用户截图的分层形状一致 ⇒ 阶梯是【方块取整的必然结果】，
        //   根因是"该处地形过于平滑"（缺少高频细节），而不是某个边界/量化缺陷。
        BufferedImage cont = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                boolean edge = false;
                if (x > 0 && hf[y][x] != hf[y][x - 1]) edge = true;
                if (y > 0 && hf[y][x] != hf[y - 1][x]) edge = true;
                cont.setRGB(x, y, edge ? 0x000000 : 0xFFFFFF);
            }
        }
        ImageIO.write(cont, "png", new File(outDir, "contours_1block" + tag + ".png"));

        // ★★★ 边界叠加图：把"所有已知边界"画成不同颜色，与 1 格等高线（浅灰）对照 ★★★
        //   若用户看到的"断裂线"与某条彩色边界重合 ⇒ 找到元凶；
        //   若只与浅灰等高线重合 ⇒ 是方块取整的必然结果（地形过于平滑）。
        //   颜色表：
        //     浅灰  = 1 格等高台阶（游戏 surfaceY = floor(height) 的必然产物）
        //     红    = 板块 Voronoi 边界（dist<3）
        //     白点  = 板块种子
        //     蓝    = 地形类型主导边界（argmax 变化）
        //     橙    = chain 噪声格点 900wu   绿 = warp 噪声格点 1600wu
        //     品红  = segmentMask 格点 1500wu 青 = 褶皱噪声格点 600wu
        //     黄    = 断块噪声格点 96wu      深灰 = 侵蚀 tile 48wu / chunk 16wu
        BufferedImage ov = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        TectonicField tfb = new TectonicField(seedUsed);
        TectonicDeformation tdb = new TectonicDeformation(seedUsed);
        int[] prevType = new int[px];
        java.util.Arrays.fill(prevType, -1);
        for (int y = 0; y < px; y++) {
            int prevTypeRow = -1;
            for (int x = 0; x < px; x++) {
                int wx = ox + x, wz = oz + y;
                int c = 0xFFFFFF;
                if (x > 0 && hf[y][x] != hf[y][x - 1]) c = 0xC8C8C8;      // 1格等高
                if (y > 0 && hf[y][x] != hf[y - 1][x]) c = 0xC8C8C8;
                TectonicField.Sample s = tfb.sample(wx, wz);
                if (s.dist() < 3.0) c = 0xFF0000;                          // 板块边界
                if ((wx % 900) == 0 || (wz % 900) == 0) c = 0xFFA500;      // chain 格点
                if ((wx % 1600) == 0 || (wz % 1600) == 0) c = 0x00B000;    // warp 格点
                if ((wx % 1500) == 0 || (wz % 1500) == 0) c = 0xFF00FF;    // mask 格点
                if ((wx % 600) == 0 || (wz % 600) == 0) c = 0x00C8C8;      // 褶皱格点
                if ((wx % 96) == 0 || (wz % 96) == 0) c = 0xC8C800;        // 断块格点
                if ((wx % 48) == 0 || (wz % 48) == 0) c = 0x606060;        // 侵蚀tile
                if ((wx % 16) == 0 || (wz % 16) == 0) c = 0x303030;        // chunk
                Cell cell = terrain.sampleCellCoarse(wx, wz);
                int t = dominantOrdinal(cell);
                if ((prevTypeRow >= 0 && t != prevTypeRow) || (prevType[x] >= 0 && t != prevType[x])) {
                    c = 0x0000FF;                                          // 类型边界
                }
                prevTypeRow = t;
                prevType[x] = t;
                ov.setRGB(x, y, c);
            }
        }
        ImageIO.write(ov, "png", new File(outDir, "boundaries" + tag + ".png"));
        BufferedImage gimg = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                double v = gm[y][x] / gMax;
                int g = (int) Math.round(255.0 * Math.pow(v, 0.35));
                g = Math.max(0, Math.min(255, g));
                gimg.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        ImageIO.write(gimg, "png", new File(outDir, "gradmag_full" + tag + ".png"));
        System.out.printf("  [full] %d chunks (%d wu) height=[%.1f,%.1f] max|grad|=%.4f 块/wu -> 写出 2 图%n",
                chunks * chunks, px, mn, mx, gMax);

        // 阶梯检测：沿中间行统计"平台段"(相邻高度相同)占比与平台长度。
        //   "同心阶梯等高带"在数值上 = 大量等值平台 + 陡边 ⇒ 平台占比会显著偏高。
        int run = 1, plateaus = 0, longest = 1;
        StringBuilder sb = new StringBuilder();
        int row = px / 2;
        for (int x = 1; x < px; x++) {
            if (sb.length() < 150) sb.append(String.format("%.0f ", h[row][x]));
            if (Math.abs(h[row][x] - h[row][x - 1]) < 1e-9) {
                run++;
                longest = Math.max(longest, run);
            } else {
                if (run >= 2) plateaus += run;
                run = 1;
            }
        }
        System.out.printf("      中间行高度: %s%n", sb);
        System.out.printf("      平台段(相邻等高)像素占比=%.1f%%  最长平台=%d 像素%n",
                100.0 * plateaus / px, longest);
    }

    /**
     * 把构造分量各自渲染成图，用于定位"细线"落在哪个分量。
     *
     * <p>候选怀疑：T5 的 {@code slipQ = floor(blockN*3)/3} —— {@code Math.floor}
     * 会沿 {@code blockN} 的整数等值线产生<b>阶跃</b>，而等值线是一族平滑曲线
     * ⇒ 表现为"沿等高线的细线"。T1/T2 一并渲染以排除。</p>
     */
    private static void renderComponents(File outDir, long seed,
                                         int ox, int oz, int size, int step) throws Exception {
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);

        double[][] deform = new double[size][size];
        double[][] stress = new double[size][size];
        double[][] chain = new double[size][size];
        double dMn = 1e9, dMx = -1e9, cMn = 1e9, cMx = -1e9;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double x = ox + px * (double) step, z = oz + py * (double) step;
                TectonicField.Sample s = tf.sample(x, z);
                deform[py][px] = td.offset(s, x, z);
                stress[py][px] = s.stress();
                chain[py][px] = tf.boundaryStrengthChained(s, x, z);
                dMn = Math.min(dMn, deform[py][px]);
                dMx = Math.max(dMx, deform[py][px]);
                cMn = Math.min(cMn, chain[py][px]);
                cMx = Math.max(cMx, chain[py][px]);
            }
        }
        ImageIO.write(toGray(deform, dMn, dMx), "png", new File(outDir, "comp_deform.png"));
        // stress ∈ [-1,1] 固定映射，便于看"值域结构"
        ImageIO.write(toGray(stress, -1.0, 1.0), "png", new File(outDir, "comp_stress.png"));
        ImageIO.write(toGray(chain, cMn, cMx), "png", new File(outDir, "comp_chain.png"));
        System.out.printf("  [components] deform=[%.5f,%.5f] chain=[%.5f,%.5f] -> 写出 3 图%n",
                dMn, dMx, cMn, cMx);

        // 断层阶跃统计：沿 x 逐 1wu 找 |Δ| 突跳（floor 量化的直接证据）
        int jumps = 0, n = 0;
        double maxJump = 0;
        for (int ky = 0; ky < size; ky += 37) {
            double prev = Double.NaN;
            for (int kx = 0; kx < size; kx++) {
                double x = ox + kx * (double) step, z = oz + ky * (double) step;
                double v = td.offset(tf.sample(x, z), x, z);
                if (!Double.isNaN(prev)) {
                    double j = Math.abs(v - prev);
                    double base = 0.002 * step;   // 该尺度下"平滑"的参考步长
                    if (j > base * 4) { jumps++; maxJump = Math.max(maxJump, j); }
                    n++;
                }
                prev = v;
            }
        }
        System.out.printf("      断层阶跃: 突跳次数=%d/%d(%.3f%%) 最大=%.5f e (步长%dwu)%n",
                jumps, n, 100.0 * jumps / Math.max(1, n), maxJump, step);
    }

    /** 极简高程配色（蓝→绿→黄→白），模仿预览的高程图层观感。 */
    private static int[] elevRamp(double p) {
        p = Math.max(0.0, Math.min(1.0, p));
        int[][] stops = {{20, 60, 130}, {60, 140, 150}, {70, 150, 70}, {170, 180, 100}, {255, 255, 255}};
        double t = p * (stops.length - 1);
        int i = (int) Math.min(stops.length - 2, Math.floor(t));
        double f = t - i;
        return new int[]{
            (int) (stops[i][0] + (stops[i + 1][0] - stops[i][0]) * f),
            (int) (stops[i][1] + (stops[i + 1][1] - stops[i][1]) * f),
            (int) (stops[i][2] + (stops[i + 1][2] - stops[i][2]) * f)};
    }

    private static BufferedImage toGray(double[][] v, double mn, double mx) {
        int h = v.length, w = v[0].length;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double span = Math.max(1e-12, mx - mn);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = (int) Math.round(255.0 * (v[y][x] - mn) / span);
                g = Math.max(0, Math.min(255, g));
                img.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        return img;
    }

    /** 打印 1wu 步长的 eLand 剖面 + 相邻差统计（判定是否量化台阶）。 */
    private static void dumpProfile(GeoGenesisTerrain terrain, int x0, int x1, int z) {
        double prev = Double.NaN;
        java.util.TreeMap<Long, Integer> dHist = new java.util.TreeMap<>();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int x = x0; x <= x1; x++) {
            double v = terrain.sampleCellCoarse(x, z).eLand;
            if (!Double.isNaN(prev)) {
                double d = v - prev;
                // 量化后的差（×1e6 取整）做直方图
                long key = Math.round(d * 1e6);
                dHist.merge(key, 1, Integer::sum);
            }
            if (shown < 60) { sb.append(String.format("%.6f ", v)); shown++; }
            prev = v;
        }
        System.out.println("  值: " + sb);
        System.out.println("  相邻差(×1e6) 取值分布（前 20 个）: ");
        int c = 0;
        for (var e : dHist.entrySet()) {
            if (c++ >= 20) break;
            System.out.printf("    d=%d 次 出现 %d 次%n", e.getKey(), e.getValue());
        }
        System.out.println("  唯一差值个数 = " + dHist.size() + "（连续场应很多；量化台阶会很少）");
    }

    /**
     * 扫描寻找<b>地质活跃</b>的陆地采样点：在陆地前提下取 |T5 形变| 最大者。
     *
     * <p>为何必须这样做：T1~T5 的影响集中在板块边界带内。若在无地质影响的
     * 区域渲染，会得到"一切正常"的假象（第一轮就吃过这个亏）。</p>
     */
    private static int[] findGeologyActive(GeoGenesisTerrain terrain, long seed) {
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);
        int bx = 0, bz = 0;
        double best = -1;
        for (int r = 0; r <= 24000; r += 400) {
            for (int a = 0; a < 360; a += 20) {
                double rad = Math.toRadians(a);
                int x = (int) (r * Math.cos(rad)), z = (int) (r * Math.sin(rad));
                Cell c = terrain.sampleCellCoarse(x, z);
                if (c == null || c.eLand < 0.15) continue;
                double o = Math.abs(td.offset(tf.sample(x, z), x, z));
                if (o > best) { best = o; bx = x; bz = z; }
            }
        }
        System.out.printf("  地质活跃点 |deform|=%.5f e (%.2f 块)%n", best, best * 192.0);
        return new int[]{bx, bz};
    }

    private static void render(GeoGenesisTerrain terrain, File outDir,
                               int ox, int oz, int size, int step, String tag,
                               int hpRadius) throws Exception {
        double[][] eLand = new double[size][size];
        int[][] dom = new int[size][size];
        int[] colors = typeColors(TerrainClass.values().length);
        double mn = 1e9, mx = -1e9;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                Cell c = terrain.sampleCellCoarse(ox + px * step, oz + py * step);
                eLand[py][px] = c.eLand;
                mn = Math.min(mn, c.eLand);
                mx = Math.max(mx, c.eLand);
                double best = -1; int bi = -1;
                for (int i = 0; i < c.typeWeights.length; i++) {
                    if (c.typeWeights[i] > best) { best = c.typeWeights[i]; bi = i; }
                }
                dom[py][px] = bi;
            }
        }
        double span = Math.max(1e-6, mx - mn);
        BufferedImage contrast = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        BufferedImage grad = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        BufferedImage typeImg = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        // ★ 高通（去趋势）：减去大窗口均值 → 只留"局部纹理"。
        //   规律性条纹/台阶在整体地形里很弱，但在高通图里会【极醒目】。
        BufferedImage highpass = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int R = hpRadius;
        double[][] hp = new double[size][size];
        double hpMax = 1e-12;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double s = 0; int c = 0;
                for (int dy = -R; dy <= R; dy += Math.max(1, R / 6)) {
                    int yy = py + dy;
                    if (yy < 0 || yy >= size) continue;
                    for (int dx = -R; dx <= R; dx += Math.max(1, R / 6)) {
                        int xx = px + dx;
                        if (xx < 0 || xx >= size) continue;
                        s += eLand[yy][xx]; c++;
                    }
                }
                hp[py][px] = eLand[py][px] - (c > 0 ? s / c : eLand[py][px]);
                hpMax = Math.max(hpMax, Math.abs(hp[py][px]));
            }
        }
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int g = (int) Math.round(127.5 + 127.5 * hp[py][px] / hpMax);
                g = Math.max(0, Math.min(255, g));
                highpass.setRGB(px, py, (g << 16) | (g << 8) | g);
            }
        }
        ImageIO.write(highpass, "png", new File(outDir, "eLand_highpass" + tag + ".png"));
        System.out.printf("  [%s] 高通幅值 max=%.5f e (%.2f 块)%n",
                tag.isEmpty() ? "wide" : tag, hpMax, hpMax * 192.0);

        // ★ hillshade：最接近"人眼所见"（高程配色 × 坡度阴影），用于最终视觉判定。
        //   伪影的最终判据是视觉，故必须以它为准（高通图会放大一切合法细节）。
        BufferedImage shade = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double p = (eLand[py][px] - mn) / span;
                int[] rgb = elevRamp(p);
                // 法线 ≈ (−dh/dx, −dh/dz, 1)；光源左上
                int x1 = Math.max(0, px - 1), x2 = Math.min(size - 1, px + 1);
                int y1 = Math.max(0, py - 1), y2 = Math.min(size - 1, py + 1);
                double gx2 = (eLand[py][x2] - eLand[py][x1]) / ((x2 - x1) * (double) step);
                double gz2 = (eLand[y2][px] - eLand[y1][px]) / ((y2 - y1) * (double) step);
                double nx = -gx2 * 192.0, ny = -gz2 * 192.0, nz = 1.0;
                double nl = Math.sqrt(nx * nx + ny * ny + 1.0);
                double lx = -0.5, lz = -0.3, ly = 1.0;
                double ll = Math.sqrt(lx * lx + lz * lz + ly * ly);
                double dot = (nx * lx + ny * lz + nz * ly) / (nl * ll);
                double f = 0.65 + 0.35 * Math.max(0.0, dot);
                int r = (int) Math.min(255, rgb[0] * f);
                int g = (int) Math.min(255, rgb[1] * f);
                int b = (int) Math.min(255, rgb[2] * f);
                shade.setRGB(px, py, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(shade, "png", new File(outDir, "hillshade" + tag + ".png"));
        double[][] gm = new double[size][size];
        double gMax = 1e-9;
        for (int py = 1; py < size - 1; py++) {
            for (int px = 1; px < size - 1; px++) {
                double dx = (eLand[py][px + 1] - eLand[py][px - 1]) / (2.0 * step);
                double dz = (eLand[py + 1][px] - eLand[py - 1][px]) / (2.0 * step);
                gm[py][px] = Math.hypot(dx, dz);
                gMax = Math.max(gMax, gm[py][px]);
            }
        }
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int g = (int) Math.round(255.0 * (eLand[py][px] - mn) / span);
                g = Math.max(0, Math.min(255, g));
                contrast.setRGB(px, py, (g << 16) | (g << 8) | g);
                double v = gm[py][px] / gMax;
                int gg = (int) Math.round(255.0 * Math.pow(v, 0.35));
                gg = Math.max(0, Math.min(255, gg));
                grad.setRGB(px, py, (gg << 16) | (gg << 8) | gg);
                int i = dom[py][px];
                typeImg.setRGB(px, py, (i >= 0 && i < colors.length) ? colors[i] : 0xFF00FF);
            }
        }
        ImageIO.write(contrast, "png", new File(outDir, "eLand_contrast" + tag + ".png"));
        ImageIO.write(grad, "png", new File(outDir, "gradmag" + tag + ".png"));
        ImageIO.write(typeImg, "png", new File(outDir, "type_dominant" + tag + ".png"));
        System.out.printf("  [%s] eLand=[%.4f, %.4f] max|grad|=%.6f e/wu (%.2f 块/块) -> 写出 3 图%n",
                tag.isEmpty() ? "wide" : tag, mn, mx, gMax, gMax * 192.0);
    }
}
