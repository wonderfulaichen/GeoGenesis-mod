package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 大陆形态探针：把 {@code continentEdge} 场整张导出，并量化"像不像板块大陆"。
 *
 * <h3>为什么要量形态而不只是看图</h3>
 * <p>参考（{@code UpliftContinentGenerator}）的大陆形态来自 <b>Voronoi 板块 + 距离场</b>：
 * 每个大陆是一块 Voronoi 多边形，边缘按距离平滑落海 ⇒ 大陆边界有"板块感"——
 * 边界较直、转折较少、沿岸有离岸小岛（相邻板块的碎块）。
 * 我们的是 <b>6 倍频 FBM</b> ⇒ 等值线自相似、无板块结构、海岸线处处碎。</p>
 *
 * <h3>量化判据</h3>
 * <ol>
 *   <li><b>海岸线长度/面积比</b>（周长分形维数代理）：FBM 海岸的比值远高于板块海岸。</li>
 *   <li><b>陆块数量与大小分布</b>：板块大陆 = 少数大块；FBM = 大量中等块 + 无数碎块。</li>
 *   <li><b>离岸岛屿数</b>：距大陆 50–300 块的独立小陆块数量（群岛）。</li>
 * </ol>
 *
 * <p>用法：{@code <toml> <outDir> <size> <step> <cx> <cz>}</p>
 */
public final class ContinentShapeProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int size = Integer.parseInt(args[2]);
        int step = Integer.parseInt(args[3]);
        double cx = Double.parseDouble(args[4]);
        double cz = Double.parseDouble(args[5]);
        Files.createDirectories(out);

        TerrainParams params = ProbeSupport.loadParams(toml, out);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        ProvinceContinentField province = new ProvinceContinentField(SEED);

        // 双臂：FBM（现行）与 Voronoi 领地（候选）
        for (String[] arm : new String[][]{{"fbm", "FBM 等值线（现行）"}, {"province", "Voronoi 领地（候选）"}}) {
            String id = arm[0];
            double[] edge = new double[size * size];
            for (int z = 0; z < size; z++) {
                double wz = cz + (z - size / 2.0) * step;
                for (int x = 0; x < size; x++) {
                    double wx = cx + (x - size / 2.0) * step;
                    edge[z * size + x] = "fbm".equals(id)
                            ? (continent.sample(wx, wz) + 1.0) * 0.5
                            : (province.sample(wx, wz) + 1.0) * 0.5;
                }
            }
            writeRaw(out.resolve("continent-edge-" + id + ".f64be.raw"), edge);
            System.out.println();
            System.out.println("================ " + arm[1] + " ================");
            analyze(edge, size, step);
        }

        System.out.println("raw files in " + out.toAbsolutePath());
    }

    /** 对一张 edge 场做形态量化。 */
    private static void analyze(double[] edge, int size, int step) {
        double landThr = 0.302;   // 与 OceanAssemblyCandidate.CP_COAST_ANCHOR 一致
        boolean[] land = new boolean[edge.length];
        int landCnt = 0;
        for (int i = 0; i < edge.length; i++) {
            land[i] = edge[i] >= landThr;
            if (land[i]) landCnt++;
        }

        // 1) 岸线长度：陆/水相邻的像素对数（4-邻）
        long coastEdges = 0;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (x + 1 < size && land[i] != land[i + 1]) coastEdges++;
                if (z + 1 < size && land[i] != land[i + size]) coastEdges++;
            }
        }
        double coastPerLand = landCnt > 0 ? (double) coastEdges / landCnt : 0;

        // 2) 连通分量（4-邻）
        int[] comp = new int[edge.length];
        java.util.Arrays.fill(comp, -1);
        int[] stack = new int[edge.length];
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        for (int start = 0; start < edge.length; start++) {
            if (!land[start] || comp[start] >= 0) continue;
            int id = sizes.size();
            int sp = 0, cnt = 0;
            stack[sp++] = start;
            comp[start] = id;
            while (sp > 0) {
                int i = stack[--sp];
                cnt++;
                int x = i % size, z = i / size;
                if (x > 0 && land[i - 1] && comp[i - 1] < 0) { comp[i - 1] = id; stack[sp++] = i - 1; }
                if (x + 1 < size && land[i + 1] && comp[i + 1] < 0) { comp[i + 1] = id; stack[sp++] = i + 1; }
                if (z > 0 && land[i - size] && comp[i - size] < 0) { comp[i - size] = id; stack[sp++] = i - size; }
                if (z + 1 < size && land[i + size] && comp[i + size] < 0) { comp[i + size] = id; stack[sp++] = i + size; }
            }
            sizes.add(cnt);
        }
        java.util.Collections.sort(sizes, java.util.Collections.reverseOrder());

        System.out.printf(Locale.ROOT, "窗口 %d² × %d 块 = %d 块见方%n", size, step, size * step);
        System.out.printf(Locale.ROOT, "陆地 %.1f%%%n", 100.0 * landCnt / edge.length);
        System.out.printf(Locale.ROOT, "岸线长度/陆地面积 = %.4f（越小越整块；FBM 典型偏大）%n",
                coastPerLand);
        System.out.printf(Locale.ROOT, "陆块数 = %d%n", sizes.size());
        int biggest = sizes.isEmpty() ? 0 : sizes.get(0);
        System.out.printf(Locale.ROOT, "最大陆块占陆地 %.1f%%%n",
                landCnt > 0 ? 100.0 * biggest / landCnt : 0);
        System.out.println("--- 前 12 大陆块（占陆地 %）---");
        for (int i = 0; i < Math.min(12, sizes.size()); i++) {
            System.out.printf(Locale.ROOT, "  #%-2d %4d px  %5.1f%%%n",
                    i + 1, sizes.get(i), 100.0 * sizes.get(i) / landCnt);
        }
        int tiny = 0;
        for (int s : sizes) if (s < 20) tiny++;
        System.out.printf(Locale.ROOT, "碎块（<20 px，即 <%.0f 块²）数 = %d%n", 20.0 * step * step, tiny);

        // 3) 离岸岛屿：面积 < 最大陆块的 5% 且 ≥ 4 px
        //    ★ 2026-10-29 修正：原判据"不属于最大陆块"在多大陆场景下会把其余大陆
        //      全算成岛屿（实测 12 个占 85%）。正确的是按"相对最大块的面积"判。
        int offshore = 0;
        long offshoreArea = 0;
        double smallCut = biggest * 0.05;
        for (int i = 0; i < sizes.size(); i++) {
            if (sizes.get(i) >= 4 && sizes.get(i) < smallCut) {
                offshore++;
                offshoreArea += sizes.get(i);
            }
        }
        System.out.printf(Locale.ROOT,
                "离岸岛屿（≥4px 且 <最大块的 5%% = %d px）= %d 个，合计占陆地 %.1f%%%n",
                (int) smallCut, offshore, landCnt > 0 ? 100.0 * offshoreArea / landCnt : 0);
    }

    private static void writeRaw(Path path, double[] values) throws Exception {
        try (DataOutputStream s = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
            for (double v : values) s.writeDouble(v);
        }
    }
}