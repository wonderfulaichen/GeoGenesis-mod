package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 洼地分布诊断（湖泊功能阶段 0）：回答"本地形到底有没有洼地、多大、多深"，
 * 用来定湖的门槛（最小水深 / 最小格数）。
 *
 * <p>用法：gradlew runLakeBasinProbe -PprobeArgs="12345" </p>
 *
 * <p>口径必须与生产一致：填洼层用 {@link RiverLineNetwork#groundYAt}（真实地形，
 * 含侵蚀 delta），网格范围与 RiverLineNetwork.build 完全相同
 * （region ± margin = regionSize*0.5）。</p>
 */
public final class LakeBasinProbe {

    private LakeBasinProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int rr = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineNetwork net = engine.network();
        RiverLineParams P = RiverLineParams.defaults();
        double rs = P.regionSize(), cell = P.gridCell(), margin = rs * 0.5;

        int totalBasins = 0;
        int[] depthBuckets = new int[6];          // 0-0.5 / 0.5-1 / 1-2 / 2-4 / 4-8 / 8+
        int[] areaBuckets = new int[6];           // 1 / 2-3 / 4-7 / 8-15 / 16-63 / 64+
        List<String> top = new ArrayList<>();

        for (int rz = -rr; rz <= rr; rz++) {
            for (int rx = -rr; rx <= rr; rx++) {
                double minX = rx * rs - margin, maxX = rx * rs + rs + margin;
                double minZ = rz * rs - margin, maxZ = rz * rs + rs + margin;
                FlowField field = new FlowField(minX, minZ, maxX, maxZ, cell, net::groundYAt);
                field.computeFill(net::groundYAt);
                int nx = field.cols(), nz = field.rows(), n = nx * nz;
                boolean[] seen = new boolean[n];
                int regionBasins = 0;
                for (int idx = 0; idx < n; idx++) {
                    if (seen[idx] || !field.isBasinCell(idx)) continue;
                    // 8 邻连通洪泛，收集一个洼地
                    Deque<Integer> stack = new ArrayDeque<>();
                    stack.push(idx);
                    seen[idx] = true;
                    List<Integer> cells = new ArrayList<>();
                    while (!stack.isEmpty()) {
                        int c = stack.pop();
                        cells.add(c);
                        int ci = c % nx, cj = c / nx;
                        for (int dj = -1; dj <= 1; dj++) {
                            for (int di = -1; di <= 1; di++) {
                                if (di == 0 && dj == 0) continue;
                                int ni = ci + di, nj = cj + dj;
                                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                                int nIdx = nj * nx + ni;
                                if (seen[nIdx] || !field.isBasinCell(nIdx)) continue;
                                seen[nIdx] = true;
                                stack.push(nIdx);
                            }
                        }
                    }
                    regionBasins++;
                    double maxDepth = 0.0, spillMin = Double.POSITIVE_INFINITY,
                            spillMax = Double.NEGATIVE_INFINITY;
                    double cx = 0.0, cz = 0.0;
                    for (int c : cells) {
                        maxDepth = Math.max(maxDepth, field.basinDepthAt(c));
                        double f = field.filledAt(c);
                        spillMin = Math.min(spillMin, f);
                        spillMax = Math.max(spillMax, f);
                        cx += field.cellCenterX(c);
                        cz += field.cellCenterZ(c);
                    }
                    cx /= cells.size();
                    cz /= cells.size();
                    // 是否落在本 region 自有盒内（不在 margin 重叠区）——跨区归属判据
                    boolean own = cx >= rx * rs && cx <= rx * rs + rs
                            && cz >= rz * rs && cz <= rz * rs + rs;
                    depthBuckets[bucket(new double[]{0.5, 1, 2, 4, 8}, maxDepth)]++;
                    areaBuckets[bucket(new double[]{2, 4, 8, 16, 64}, cells.size())]++;
                    if (cells.size() >= 4) {
                        top.add(String.format("  region(%d,%d) cells=%d 最深=%.2f "
                                        + "spill[%.2f~%.2f] 中心(%.0f,%.0f) ownBox=%s",
                                rx, rz, cells.size(), maxDepth, spillMin, spillMax,
                                cx, cz, own));
                    }
                }
                totalBasins += regionBasins;
            }
        }

        System.out.println("洼地总数=" + totalBasins + "  （" + (2 * rr + 1) + "×"
                + (2 * rr + 1) + " region，含 margin 重叠区重复计数）");
        System.out.println("按最大水深分档(格): <0.5=" + depthBuckets[0]
                + "  0.5~1=" + depthBuckets[1] + "  1~2=" + depthBuckets[2]
                + "  2~4=" + depthBuckets[3] + "  4~8=" + depthBuckets[4]
                + "  >=8=" + depthBuckets[5]);
        System.out.println("按格数分档: 1=" + areaBuckets[0] + "  2~3=" + areaBuckets[1]
                + "  4~7=" + areaBuckets[2] + "  8~15=" + areaBuckets[3]
                + "  16~63=" + areaBuckets[4] + "  >=64=" + areaBuckets[5]);
        System.out.println("格数≥4 的洼地明细（前 20）:");
        top.stream().limit(20).forEach(System.out::println);
    }

    private static int bucket(double[] edges, double v) {
        for (int i = 0; i < edges.length; i++) if (v < edges[i]) return i;
        return edges.length;
    }
}
