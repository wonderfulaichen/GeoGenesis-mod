package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 峡谷（大峡谷式）剖面诊断探针（★ 2026-09-14）。
 *
 * <h3>要回答的问题（先实测，再定参 —— 项目铁律）</h3>
 * <p>用户要做**大峡谷式**地貌。在动任何参数之前，必须先量清<b>现状到底长什么样</b>：
 * 高原上的河谷现在有多深？谷壁有多陡？是否已经接近峡谷、还是完全不是？</p>
 * <p>{@code AGENTS.md} 的铁律：<b>「判定伪影/地貌的最终依据只能是渲染图」</b> +
 * 「先实测再定参」。故本探针既出<b>数字</b>（岸高 → 坡度分布）也出<b>图</b>。</p>
 *
 * <h3>大峡谷的判据（本探针的量化口径）</h3>
 * <ul>
 *   <li><b>岸高 h</b> = 原地形高度 − 计划水面高度（= 河谷深）。大峡谷要求 h 很大（数十格）。</li>
 *   <li><b>谷壁坡度</b> = 雕刻后地形在谷壁带的 |∇h|（块/块）。大峡谷要求坡度大（&gt;1，即 &gt;45°）。</li>
 *   <li><b>关键比值</b>：h 大 <b>且</b> 坡度大 ⇒ 已是峡谷；二者缺一即"不是峡谷"。</li>
 * </ul>
 *
 * <h3>为何用「岸高」而非「地形类型」做门控</h3>
 * <p>地形类型（PLATEAU）要额外调 {@code sample()}（含气候/分类）；而
 * {@code original − waterSurface} 在雕刻阶段<b>免费可得</b>，且物理上正是"峡谷深度"的定义。
 * 本探针据此验证：<b>岸高本身是否已足够区分"峡谷候选"</b>。</p>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>{@code build/canyon/orig_shade.png} —— 侵蚀前原地形山体阴影</li>
 *   <li>{@code build/canyon/carved_shade.png} —— 水文雕刻后地形山体阴影（人眼所见）</li>
 *   <li>{@code build/canyon/water.png} —— 水面/河道标记</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runCanyonProfileProbe [-PprobeArgs="seed hs chunksPerSide"]}</p>
 */
public final class CanyonProfileProbe {

    private CanyonProfileProbe() { }

    /**
     * 谷壁统计窗口（block）：只统计到河道边缘距离 ≤ 本值的列。
     *
     * <p>取 30：覆盖当前 {@code bankRunMax=24} 的谷壁跨度上限，并可容纳峡谷改造后的更宽谷壁。
     * 超过此距离的列属"谷外高原面"，不参与谷壁坡度统计。</p>
     */
    private static final double WALL_WINDOW = 30.0;

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        int side = args.length > 2 ? Integer.parseInt(args[2]) : 3;   // 每边 chunk 数

        System.out.printf("=== CanyonProfileProbe seed=%d hs=%.1f side=%d ===%n", seed, hs, side);

        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // ---------- 收集含河的 chunk（+ 邻域），避免扫无河区 ----------
        Set<Long> riverChunks = new LinkedHashSet<>();
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                RiverLineRegion region = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline river : region.rivers) {
                    for (int i = 0; i < river.nodes.length; i++) {
                        int bx = (int) Math.floor(river.nodes[i].x() * hs);
                        int bz = (int) Math.floor(river.nodes[i].z() * hs);
                        int ccx = Math.floorDiv(bx, 16), ccz = Math.floorDiv(bz, 16);
                        for (int dz = -side; dz <= side; dz++) {
                            for (int dx = -side; dx <= side; dx++) {
                                riverChunks.add((((long) (ccx + dx)) << 32) | ((ccz + dz) & 0xffffffffL));
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("[1] 含河 chunk 数 = %d%n", riverChunks.size());
        if (riverChunks.isEmpty()) {
            System.out.println("    无河 —— 换 seed 重试。");
            return;
        }

        // ---------- 逐 chunk 雕刻，累积统计 + 拼图 ----------
        //   统计桶：按「岸高 h」分组，看各组的谷壁坡度
        final double[] H_EDGES = {0, 5, 10, 15, 20, 30, 40, 60, 200};
        int nBuckets = H_EDGES.length - 1;
        int[] cnt = new int[nBuckets];
        double[] slopeSum = new double[nBuckets];
        double[] slopeMax = new double[nBuckets];
        int[] steepCnt = new int[nBuckets];        // 坡度 > 1.0（>45°）
        int[] flatTopCnt = new int[nBuckets];      // 高原面：谷壁外的原地形与水面差 > h

        // 记录代表性剖面（岸高最大的若干条）
        List<double[]> bestProfiles = new ArrayList<>();   // {h, slope, bx, bz}
        double bestH = 0;

        int carvedCols = 0, channelCols = 0, landCols = 0;
        double hMax = 0, slopeMaxAll = 0;
        int bestBlockX = 0, bestBlockZ = 0;   // 最深谷所在块（渲染窗口对准它）

        for (long key : riverChunks) {
            int cx = (int) (key >> 32), cz = (int) key;
            double[] ground = new double[256];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    ground[lx * 16 + lz] = terrain.sample(
                            (cx * 16 + lx) / hs, (cz * 16 + lz) / hs).height;
                }
            }
            List<HydrologyBlockCarvedColumn> cols =
                    HydrologyBlockCarver.carveChunk(engine, cx, cz, hs, ground);
            // ★ 建「雕刻后高度」2D 网格 —— 局部坡度必须用**邻列**算，不能靠
            //   cut/(dist−width)（那在远离河道处恒为 0，测不到谷壁）。
            double[][] cg = new double[16][16];
            boolean[][] has = new boolean[16][16];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    cg[lz][lx] = ground[lx * 16 + lz];       // 默认=原地形（未雕刻列）
                }
            }
            for (HydrologyBlockCarvedColumn c : cols) {
                int lx = c.blockX() - cx * 16, lz = c.blockZ() - cz * 16;
                if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;
                cg[lz][lx] = c.carvedGroundY();
                has[lz][lx] = true;
            }
            for (HydrologyBlockCarvedColumn c : cols) {
                carvedCols++;
                List<HydrologyBlockSample> sm =
                        engine.sampleBlockAll(c.blockX(), c.blockZ(), hs);
                if (sm.isEmpty()) continue;
                HydrologyBlockSample s0 = sm.get(0);
                if (s0.frozen() || s0.fallDrop() > 0.0) continue;   // 瀑布段排除（合法大落差）
                if (s0.isLake()) continue;                          // 湖不参与
                boolean inChannel = s0.distToCenter() <= Math.max(s0.width(), 1.0);
                if (inChannel) { channelCols++; continue; }         // 只统计谷壁/岸坡列
                landCols++;

                int lx = c.blockX() - cx * 16, lz = c.blockZ() - cz * 16;
                if (lx < 1 || lx > 14 || lz < 1 || lz > 14) continue;   // 跳过 chunk 边缘（差分不准）

                // ★ 距离门控（关键）：只统计【真正的谷壁带】。
                //   否则远处高原面（dist 可达 100+，cut≈0）会以"大 h + 零坡度"大量混入，
                //   把平均坡度稀释掉（实测未加门控时 h∈[20,30) 平均坡度仅 0.47，
                //   而其中 88.9% 是"台面残留"= 未被雕刻的列）。
                double dd = s0.distToCenter() - Math.max(s0.width(), 1.0);
                if (dd < 0.0 || dd > WALL_WINDOW) continue;

                // 岸高 h：本列原始地形 − 水面（谷壁列处即"谷深"的局部值）
                double h = c.originalGroundY() - c.waterSurfaceY();
                if (h <= 0) continue;
                hMax = Math.max(hMax, h);

                // ★ 谷壁坡度 = 雕刻后地形的【局部梯度幅值】（块/块，中心差分）
                double dhx = (cg[lz][lx + 1] - cg[lz][lx - 1]) * 0.5;
                double dhz = (cg[lz + 1][lx] - cg[lz - 1][lx]) * 0.5;
                double slope = Math.sqrt(dhx * dhx + dhz * dhz);
                slopeMaxAll = Math.max(slopeMaxAll, slope);

                double cutHere = c.originalGroundY() - c.carvedGroundY();
                for (int b = 0; b < nBuckets; b++) {
                    if (h >= H_EDGES[b] && h < H_EDGES[b + 1]) {
                        cnt[b]++;
                        slopeSum[b] += slope;
                        slopeMax[b] = Math.max(slopeMax[b], slope);
                        if (slope > 1.0) steepCnt[b]++;
                        // "高原面残留"：岸高很大而本列几乎未被雕（cut 小）⇒ 台地面
                        if (cutHere < 1.0 && h > 15) flatTopCnt[b]++;
                        break;
                    }
                }
                if (h > bestH) {
                    bestH = h;
                    bestBlockX = c.blockX();
                    bestBlockZ = c.blockZ();
                    bestProfiles.add(new double[]{h, slope,
                            c.originalGroundY(), c.carvedGroundY(), c.waterSurfaceY(),
                            s0.distToCenter(), s0.width()});
                    if (bestProfiles.size() > 40) bestProfiles.remove(0);
                }
            }
        }

        System.out.printf("[2] 雕刻列=%d（河道内=%d 谷壁/岸坡=%d）%n",
                carvedCols, channelCols, landCols);
        System.out.printf("    最大岸高 h=%.1f 格   最大谷壁坡度=%.2f 块/块%n", hMax, slopeMaxAll);

        System.out.println("[3] 岸高 → 谷壁坡度 分布（判据：大峡谷需 h 大 **且** 坡度 >1）:");
        System.out.printf("    %-12s %8s %10s %10s %10s %12s%n",
                "岸高区间", "列数", "平均坡度", "最大坡度", "陡坡占比", "台面残留");
        for (int b = 0; b < nBuckets; b++) {
            if (cnt[b] == 0) continue;
            System.out.printf("    [%3.0f,%3.0f)    %8d %10.2f %10.2f %9.1f%% %11.1f%%%n",
                    H_EDGES[b], H_EDGES[b + 1], cnt[b],
                    slopeSum[b] / cnt[b], slopeMax[b],
                    100.0 * steepCnt[b] / cnt[b],
                    100.0 * flatTopCnt[b] / cnt[b]);
        }

        // ---------- 判据 ----------
        //   找"深且陡"的桶：h ≥ 20 且平均坡度 ≥ 1.0
        boolean hasDeep = false, hasSteepAtDepth = false;
        for (int b = 0; b < nBuckets; b++) {
            if (H_EDGES[b] >= 20 && cnt[b] > 0) {
                hasDeep = true;
                if (slopeSum[b] / cnt[b] >= 1.0) hasSteepAtDepth = true;
            }
        }
        System.out.println();
        System.out.printf("[判据1] 存在岸高 ≥20 格的河谷（深谷存在）: %s%n", hasDeep ? "PASS" : "FAIL");
        System.out.printf("[判据2] 深谷（h≥20）平均坡度 ≥1.0（≈45° 陡壁）: %s%n",
                hasSteepAtDepth ? "PASS（已是峡谷）" : "FAIL（谷壁偏缓 → 需做峡谷）");

        System.out.println("[4] 岸高最大的代表列（h, 坡度, orig, carved, water, dist, width）:");
        int shown = 0;
        for (int i = bestProfiles.size() - 1; i >= 0 && shown < 5; i--, shown++) {
            double[] p = bestProfiles.get(i);
            System.out.printf("    h=%5.1f 坡度=%5.2f  orig=%6.1f carved=%6.1f water=%6.1f dist=%5.1f w=%5.1f%n",
                    p[0], p[1], p[2], p[3], p[4], p[5], p[6]);
        }

        renderImages(engine, terrain, riverChunks, hs, bestBlockX, bestBlockZ);

        boolean all = hasDeep && hasSteepAtDepth;
        System.out.println();
        System.out.println(all
                ? "=== 现状：已具备峡谷特征（深 + 陡）==="
                : "=== 现状：尚不构成大峡谷（见上方判据）===");
    }

    /**
     * 渲染原地形 / 雕刻后地形 / 水面 三张图（同一窗口、同一色阶）。
     *
     * <p>★ 窗口<b>对准最深谷</b>（{@code bestBlockX/Z}）—— 否则会渲染到 h&lt;8 的浅河谷，
     * 那里不触发峡谷模式，图上看不出任何差异（实测踩过：窗口取"含河 chunk 的几何中心"
     * 时渲染出的浅谷与改造前一模一样，属<b>测错对象</b>）。</p>
     */
    private static void renderImages(HydrologyExperimentEngine engine, CellGenerator terrain,
                                     Set<Long> chunks, double hs,
                                     int bestBlockX, int bestBlockZ) throws Exception {
        final int sideC = 6;   // 96×96 块：足以容纳一条完整峡谷断面
        int cx0 = Math.floorDiv(bestBlockX, 16) - sideC / 2;
        int cz0 = Math.floorDiv(bestBlockZ, 16) - sideC / 2;
        int px = sideC * 16;

        double[][] orig = new double[px][px];
        double[][] carv = new double[px][px];
        double[][] water = new double[px][px];

        for (int cxx = 0; cxx < sideC; cxx++) {
            for (int czz = 0; czz < sideC; czz++) {
                int cx = cx0 + cxx, cz = cz0 + czz;
                double[] ground = new double[256];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        ground[lx * 16 + lz] = terrain.sample(
                                (cx * 16 + lx) / hs, (cz * 16 + lz) / hs).height;
                    }
                }
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int gx = cxx * 16 + lx, gz = czz * 16 + lz;
                        orig[gz][gx] = ground[lx * 16 + lz];
                        carv[gz][gx] = ground[lx * 16 + lz];
                        water[gz][gx] = ground[lx * 16 + lz];
                    }
                }
                for (HydrologyBlockCarvedColumn c
                        : HydrologyBlockCarver.carveChunk(engine, cx, cz, hs, ground)) {
                    int lx = Math.floorMod(c.blockX() - cx * 16, 16);
                    int lz = Math.floorMod(c.blockZ() - cz * 16, 16);
                    int gx = cxx * 16 + lx, gz = czz * 16 + lz;
                    carv[gz][gx] = c.carvedGroundY();
                    water[gz][gx] = c.fillWater() ? c.waterSurfaceY() : c.carvedGroundY();
                }
            }
        }

        File dir = new File("build/canyon");
        dir.mkdirs();
        ImageIO.write(shade(orig, px), "png", new File(dir, "orig_shade.png"));
        ImageIO.write(shade(carv, px), "png", new File(dir, "carved_shade.png"));
        // 水面图：有水处染蓝，其余灰（按高度）
        BufferedImage wi = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        double mn = 1e9, mx = -1e9;
        for (double[] r : water) for (double v : r) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int g = (int) (255 * (water[y][x] - mn) / Math.max(1, mx - mn));
                wi.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        ImageIO.write(wi, "png", new File(dir, "water.png"));
        System.out.printf("[5] 图像输出: %s（窗口 %d×%d 块，origin chunk=(%d,%d)）%n",
                dir.getAbsolutePath(), px, px, cx0, cz0);
    }

    /** 山体阴影（真实比例，NW 光）。 */
    private static BufferedImage shade(double[][] h, int px) {
        double mn = 1e9, mx = -1e9;
        for (double[] r : h) for (double v : r) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        final double lx = -0.5, ly = 0.7, lz = 0.51;
        for (int y = 1; y < px - 1; y++) {
            for (int x = 1; x < px - 1; x++) {
                double dhx = (h[y][x + 1] - h[y][x - 1]) / 2.0;
                double dhz = (h[y + 1][x] - h[y - 1][x]) / 2.0;
                double nx = -dhx, ny = 1.0, nz = -dhz;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double lam = (nx * lx + ny * ly + nz * lz) / Math.max(1e-9, nl);
                int g = (int) Math.round(255.0 * Math.max(0.0, Math.min(1.0, 0.30 + 0.80 * lam)));
                int[] base = ramp((h[y][x] - mn) / Math.max(1e-9, mx - mn));
                img.setRGB(x, y, (Math.min(255, base[0] * g / 255 + g / 4) << 16)
                        | (Math.min(255, base[1] * g / 255 + g / 4) << 8)
                        | Math.min(255, base[2] * g / 255 + g / 4));
            }
        }
        return img;
    }

    private static int[] ramp(double p) {
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
}
