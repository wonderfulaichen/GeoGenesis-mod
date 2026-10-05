package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 【河网走在哪种地形上】三联对照图（2026-10）。
 *
 * <h2>为什么用图定案，而不用 RiverBasisProbe 的数字</h2>
 * <p>{@code RiverBasisProbe} 报"99.0% 的河列更接近无侵蚀谷底"，但该判据<b>有缺陷</b>：
 * 河床是<b>下切</b>的，本就应低于两侧谷底；"更接近无侵蚀谷底 vs 含侵蚀谷底"在两者都
 * 低于河床时几乎是恒真的比较，<b>不能区分"河走在错的地形上"与"河正常下切"</b>。
 * 项目铁律：<b>判定伪影/形态问题的最终依据只能是渲染图</b>。</p>
 *
 * <h2>三幅图的区别（唯一变量 = 地形基准）</h2>
 * <ol>
 *   <li><b>玩家看到的</b> {@code cell.height} —— 侵蚀 + 河道雕刻之后，最终落地地形；</li>
 *   <li><b>含侵蚀</b> {@code gen.sampleWu()} —— 玩家看到扣除河道雕刻的地面（玩家看到的地面 − 河床下切）；</li>
 *   <li><b>无侵蚀</b> {@code gen.sample()} —— <b>河网建网用的就是这个</b>（terrainEQuick 派生）。</li>
 * </ol>
 *
 * <p><b>★ 共用同一色标</b>：三幅的 mn / mx / 分级步长<b>完全相同</b>（取并集范围），
 * 否则"颜色深浅"跨图不可比 ⇒ 会把色标差异误读成地形差异。</p>
 *
 * <p><b>★ 无光照等高线着色</b>（沿用 {@code RiverPlainViewProbe} 的正确做法）：
 * 山体光照会把<b>谷画成脊</b>，曾导致本项目误判"河流走在山脊上"。
 * 这里只用高程分级 ⇒ 亮暗即真实高低。</p>
 *
 * <p><b>判读方法</b>：看橙线（河网，两图完全相同）落在哪幅图的<b>暗色低地</b>里。
 * 若它落在"无侵蚀"图的低地、而在"含侵蚀"图上位于高地/斜坡 ⇒ 河网跟错了地形。
 * 反之（它在含侵蚀图上也走在低地）⇒ 河网是对的，那 {@code RiverBasisProbe} 的
 * 99.0% 只是<b>判据缺陷</b>，不是缺陷本身。</p>
 *
 * <p>{@code gradlew runRiverBasisCompareViewProbe [-PprobeArgs="seed bx bz radius zoom"]}</p>
 */
public final class RiverBasisCompareViewProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        int zoom = args.length > 4 ? Math.max(1, Integer.parseInt(args[4])) : 2;

        String sp = System.getProperty("geogenesis.skeletonRouting");
        if (sp != null) RiverLineNetwork.flowSkeletonRouting = Boolean.parseBoolean(sp);
        System.out.printf("=== RiverBasisCompareViewProbe seed=%d centre=(%d,%d) r=%d zoom=%d "
                        + "skeleton=%s ===%n", seed, bx, bz, radius, zoom,
                RiverLineNetwork.flowSkeletonRouting);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();

        int w = 2 * radius + 1;
        double[][] placed = new double[w][w];   // 玩家看到的
        double[][] eroded = new double[w][w];   // 含侵蚀（未雕刻）
        double[][] raw = new double[w][w];      // 无侵蚀（建网基准）
        boolean[] riverMask = new boolean[w * w];
        boolean[] lakeMask = new boolean[w * w];

        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int x = bx - radius + i, z = bz - radius + j;
                Cell c = cellAt(gt, x, z);
                placed[j][i] = c.height;
                eroded[j][i] = gen.sampleWu(x / hs, z / hs).height;
                raw[j][i] = gen.sample(x / hs, z / hs).height;
                riverMask[j * w + i] = c.riverType != 0 && !c.isLake;
                lakeMask[j * w + i] = c.isLake;
                for (double v : new double[]{placed[j][i], eroded[j][i], raw[j][i]}) {
                    mn = Math.min(mn, v);
                    mx = Math.max(mx, v);
                }
            }
        }
        System.out.printf("三基准并集高程范围 [%.1f, %.1f]（三图共用此色标）%n", mn, mx);

        int[][] a = render(placed, riverMask, lakeMask, mn, 8.0);
        int[][] b = render(eroded, riverMask, lakeMask, mn, 8.0);
        int[][] c = render(raw, riverMask, lakeMask, mn, 8.0);

        int imgW = w * zoom * 3 + 16;
        int imgH = w * zoom;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                int panel = x / (w * zoom);
                int lx = x - panel * w * zoom;
                img.setRGB(x, y, 0x202020);
            }
        }
        blit(img, a, 0, zoom);
        blit(img, b, w * zoom + 8, zoom);
        blit(img, c, (w * zoom + 8) * 2, zoom);

        File dir = new File("build/river-basis-compare");
        dir.mkdirs();
        File out = new File(dir, "compare_" + seed + "_" + bx + "_" + bz + ".png");
        ImageIO.write(img, "png", out);

        int riverN = 0;
        for (boolean r : riverMask) if (r) riverN++;

        // ★★ 定量判据：河格在其邻域内的【高度排名】（0 = 局部最低）★★
        //   为什么用排名而不是"更接近哪个谷底"（RiverBasisProbe 的旧判据）：
        //   旧判据在"两种基准都低于河床"时几乎恒真，无法区分"河错位"与"河正常下切"。
        //   排名是尺度无关的：河在谷底 ⇒ 邻域内几乎无更低者 ⇒ 排名 ≈ 0。
        //   同一个河格在【含侵蚀】与【无侵蚀】地形上各算一次：
        //     · 两者都低 ⇒ 河网虽然建在无侵蚀地形上，但落点恰好也是侵蚀后的谷底（无缺陷）；
        //     · 无侵蚀低、含侵蚀高 ⇒ 河确实被侵蚀后的地形"甩开"了（真缺陷）。
        rankStats("含侵蚀(玩家地面)", eroded, riverMask, w);
        rankStats("无侵蚀(建网基准)", raw, riverMask, w);
        rankStats("雕刻后(玩家看到)", placed, riverMask, w);
        rankStatsAllLand("【对照】全部陆格-含侵蚀", eroded, riverMask, lakeMask, w);

        System.out.printf("河流列=%d%n", riverN);
        System.out.println("左=玩家看到的(雕刻后)  中=含侵蚀(未雕刻)  右=无侵蚀(河网建网基准)");
        System.out.println("图例：橙=河网 亮蓝=湖 深蓝=海  灰=等高线地形（无光照，三图同色标）");
        System.out.printf("IMAGE=%s (%d×%d)%n", out.getAbsolutePath(), imgW, imgH);
    }

    /**
     * 河格在邻域（±3 格）内的【高度排名分位】。
     *
     * <p>定义：{@code rank = 邻域内比它低的格数 / 邻域格数}（0 = 局部最低）。
     * 河走谷底 ⇒ 排名应接近 0。若某基准下排名显著升高 ⇒ 河在该地形上不在谷底。</p>
     *
     * <p>同时输出"排名 &lt; 0.25"（即处于邻域最低四分之一）的占比，
     * 这是"河确实在谷里"的直观量化。</p>
     */
    private static void rankStats(String label, double[][] h, boolean[] riverMask, int w) {
        int n = 0, quarter = 0;
        double sum = 0;
        int[] hist = new int[4];
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                if (!riverMask[j * w + i]) continue;
                int lo = 0, tot = 0;
                for (int dj = -3; dj <= 3; dj++) {
                    for (int di = -3; di <= 3; di++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || a >= w || b < 0 || b >= w) continue;
                        if (di == 0 && dj == 0) continue;
                        tot++;
                        if (h[b][a] < h[j][i]) lo++;
                    }
                }
                if (tot == 0) continue;
                double rank = lo / (double) tot;
                n++;
                sum += rank;
                if (rank < 0.25) quarter++;
                hist[Math.min(3, (int) (rank * 4))]++;
            }
        }
        System.out.printf("  [%s] 河格=%d 平均排名=%.3f（0=局部最低） 处于最低25%%的占比=%.1f%%%n",
                label, n, sum / Math.max(1, n), 100.0 * quarter / Math.max(1, n));
        System.out.printf("      排名分布 <0.25:%.1f%% 0.25-0.5:%.1f%% 0.5-0.75:%.1f%% >0.75:%.1f%%%n",
                100.0 * hist[0] / Math.max(1, n), 100.0 * hist[1] / Math.max(1, n),
                100.0 * hist[2] / Math.max(1, n), 100.0 * hist[3] / Math.max(1, n));
    }

    /** 对照：全部陆地格（非河非湖）在含侵蚀地形上的排名 —— 河应显著优于随机陆格。 */
    private static void rankStatsAllLand(String label, double[][] h, boolean[] riverMask,
                                        boolean[] lakeMask, int w) {
        int n = 0, quarter = 0;
        double sum = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                if (riverMask[j * w + i] || lakeMask[j * w + i]) continue;
                int lo = 0, tot = 0;
                for (int dj = -3; dj <= 3; dj++) {
                    for (int di = -3; di <= 3; di++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || a >= w || b < 0 || b >= w) continue;
                        if (di == 0 && dj == 0) continue;
                        tot++;
                        if (h[b][a] < h[j][i]) lo++;
                    }
                }
                if (tot == 0) continue;
                double rank = lo / (double) tot;
                n++;
                sum += rank;
                if (rank < 0.25) quarter++;
            }
        }
        System.out.printf("  [%s] 陆格=%d 平均排名=%.3f 处于最低25%%的占比=%.1f%%%n",
                label, n, sum / Math.max(1, n), 100.0 * quarter / Math.max(1, n));
    }

    private static int[][] render(double[][] h, boolean[] river, boolean[] lake,
                                 double mn, double step) {
        int w = h.length;
        int[][] out = new int[w][w];
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int band = (int) ((h[j][i] - mn) / step) & 7;
                int g = 40 + band * 26;
                int rgb = (g << 16) | (g << 8) | g;
                int k = j * w + i;
                if (lake[k]) rgb = 0x1E78D2;
                else if (river[k]) rgb = 0xE07010;
                out[j][i] = rgb;
            }
        }
        return out;
    }

    private static void blit(BufferedImage img, int[][] map, int ox, int zoom) {
        int w = map.length;
        for (int y = 0; y < w * zoom; y++)
            for (int x = 0; x < w * zoom; x++)
                img.setRGB(ox + x, y, map[y / zoom][x / zoom]);
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverBasisCompareViewProbe() { }
}