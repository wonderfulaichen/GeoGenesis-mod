package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 河流源头【空间分布】取证（2026-10-29）。
 *
 * <p>用户报告三件事：源头在相近坐标轴附近成排（横/竖）、密度偏高、类型单一。
 * 本探针只做量化，不改产出：从<b>生产</b> {@code regionFor} 取每条 Trace 的首格
 * （{@code t.cell[0]}），换算成世界 block 坐标，然后回答：</p>
 *
 * <ol>
 *   <li><b>成排</b>：把"两源头在 x 或 z 方向相差 ≤ {@code tol} 格"记为一次轴对齐配对，
 *       与<b>同数量均匀随机点</b>的期望值对比。比值 &gt; 1.5 即存在网格/轴偏好。</li>
 *   <li><b>密度</b>：每 1000×1000 block 的源头数，以及最近邻距离分布的 P10/P50/P90。</li>
 *   <li><b>分布形态</b>：按 32 格网格打印 ASCII 占用图，肉眼看聚集带。</li>
 * </ol>
 *
 * <p>⚠ 必须解析真实 TOML（与 ProductionViewProbe 同路），否则不是游戏口径。</p>
 *
 * <pre>
 * gradlew runSourceDistributionProbe -PprobeArgs="&lt;toml路径&gt; [seed] [半径block]"
 * </pre>
 */
public final class SourceDistributionProbe {

    private static final int MIN_Y = -64, MAX_Y = 320;

    public static void main(String[] args) throws Exception {
        Path toml = Path.of(args.length > 0 ? args[0] : "run/config/geogenesis-common.toml");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : ViewSpec.SEED;
        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 700;
        int tol = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        // args[4] 起支持 "key=value" 形式的系统属性，必须在触碰 ModelHydrologyProvider 之前设置
        // （其开关是 static final，类初始化时定值）。例：geogenesis.sourceSpacing=0
        for (int i = 4; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) System.setProperty(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        if (args.length > 4) {
            System.out.println("覆盖的系统属性：");
            for (int i = 4; i < args.length; i++) System.out.println("  " + args[i]);
        }

        TerrainParams params = loadParams(toml);
        CellGenerator gen = new CellGenerator(params, MIN_Y, MAX_Y);
        gen.seed(seed);
        ModelHydrologyProvider provider = new ModelHydrologyProvider(gen, seed);

        List<int[]> pts = collect(provider, radius);
        System.out.println("=== SourceDistributionProbe ===");
        System.out.printf("seed=%d radius=%d tol=%d%n", seed, radius, tol);
        System.out.printf("SOURCE_COUNT=%d  span=%d block%n", pts.size(), radius * 2);

        if (pts.isEmpty()) {
            System.out.println("no sources, exit");
            return;
        }

        density(pts, radius);
        alignment(pts, radius, tol);
        dumpCsv(pts);
        asciiMap(pts, radius);
    }

    /** 把源头坐标写成 CSV，供离线叠图（不参与任何判据）。 */
    private static void dumpCsv(List<int[]> pts) {
        try {
            Path dir = Path.of("build/source-dist");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("x,z\n");
            for (int[] p : pts) sb.append(p[0]).append(',').append(p[1]).append('\n');
            // 文件名带上当前关键旋钮值，便于多档并列对照
            String tag = "s" + System.getProperty("geogenesis.sourceSpacing", "24")
                    + "-c" + System.getProperty("geogenesis.skeletonSourceCells", "40");
            Files.writeString(dir.resolve("sources-" + tag + ".csv"), sb.toString());
            Files.writeString(dir.resolve("sources.csv"), sb.toString());
            System.out.printf("%nCSV_WRITTEN=build/source-dist/sources-%s.csv rows=%d%n", tag, pts.size());
        } catch (Exception e) {
            System.out.println("CSV write failed: " + e);
        }
    }

    private static TerrainParams loadParams(Path toml) throws Exception {
        Path copy = Files.createTempFile("geogenesis-src-probe", ".toml");
        Files.copy(toml, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        CommentedFileConfig cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
        cfg.load();
        GeoGenesisConfig.SPEC.setConfig(cfg);
        if (!GeoGenesisConfig.SPEC.isLoaded()) throw new IllegalStateException("TOML not loaded");
        return GeoGenesisConfig.INSTANCE.buildParams();
    }

    /** 按 region 网格扫一遍，收集所有生产 Trace 的源头世界坐标（去重：pass1/pass2 会重复同一源头）。 */
    private static List<int[]> collect(ModelHydrologyProvider provider, int radius) {
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        int step = 512;                       // REGION
        int n = radius * 2 / step + 2;
        for (int rx = -n; rx <= n; rx++) {
            for (int rz = -n; rz <= n; rz++) {
                int cx = rx * step + step / 2;
                int cz = rz * step + step / 2;
                if (Math.abs(cx) > radius || Math.abs(cz) > radius) continue;
                for (int[] p : provider.skeletonSourcesForProbe(cx, cz)) {
                    if (p == null || p.length < 2) continue;
                    if (p[0] == Integer.MIN_VALUE) continue;
                    if (Math.abs(p[0]) > radius || Math.abs(p[1]) > radius) continue;
                    seen.add(((long) p[0] << 32) ^ (p[1] & 0xffffffffL));
                }
            }
        }
        List<int[]> out = new ArrayList<>(seen.size());
        for (long k : seen) out.add(new int[]{(int) (k >> 32), (int) k});
        return out;
    }

    private static void density(List<int[]> pts, int radius) {
        double areaM = (double) radius * 2 * radius * 2 / 1_000_000.0;
        System.out.printf("%n--- density ---%n");
        System.out.printf("PER_MILLION_BLOCK2=%.2f%n", pts.size() / areaM);

        double[] nn = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            double best = Double.MAX_VALUE;
            for (int j = 0; j < pts.size(); j++) {
                if (i == j) continue;
                double dx = pts.get(i)[0] - pts.get(j)[0];
                double dz = pts.get(i)[1] - pts.get(j)[1];
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < best) best = d;
            }
            nn[i] = best == Double.MAX_VALUE ? 0 : best;
        }
        java.util.Arrays.sort(nn);
        if (nn.length > 0) {
            System.out.printf("NN_P10=%.1f NN_P50=%.1f NN_P90=%.1f NN_MAX=%.1f%n",
                    pct(nn, 0.10), pct(nn, 0.50), pct(nn, 0.90), nn[nn.length - 1]);
        }
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /**
     * 轴对齐倾向：统计"x 差或 z 差 ≤ tol"的配对数，
     * 与同数量均匀随机点在同样窗口内的期望值比。
     */
    private static void alignment(List<int[]> pts, int radius, int tol) {
        int limit = 120;                      // 只统计 120 block 内的配对
        int observed = countAligned(pts, tol, limit);

        Random rnd = new Random(20261029L);
        int trials = 20;
        double sum = 0;
        int n = pts.size();
        for (int t = 0; t < trials; t++) {
            List<int[]> fake = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                fake.add(new int[]{rnd.nextInt(radius * 2 + 1) - radius,
                        rnd.nextInt(radius * 2 + 1) - radius});
            }
            sum += countAligned(fake, tol, limit);
        }
        double expected = sum / trials;
        double ratio = expected > 0 ? observed / expected : 0;

        System.out.printf("%n--- axis alignment (rows/columns) ---%n");
        System.out.printf("ALIGNED_OBSERVED=%d  UNIFORM_EXPECTED=%.1f  ALIGN_RATIO=%.2f%n",
                observed, expected, ratio);
        System.out.println(ratio > 1.50 ? "VERDICT=ALIGNED (ratio > 1.50)" : "VERDICT=NO_ALIGNMENT");

        // 分别看纯 x 相同 / 纯 z 相同
        int sameX = 0, sameZ = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int dx = Math.abs(pts.get(i)[0] - pts.get(j)[0]);
                int dz = Math.abs(pts.get(i)[1] - pts.get(j)[1]);
                if (dx > limit && dz > limit) continue;
                if (dx <= tol && dz > tol) sameX++;
                if (dz <= tol && dx > tol) sameZ++;
            }
        }
        System.out.printf("SAME_COLUMN_dx_le_%d=%d  SAME_ROW_dz_le_%d=%d%n", tol, sameX, tol, sameZ);
        residue(pts);
    }

    /**
     * 网格量化检测：把坐标按 STEP=2 换算成格，再看 {@code mod 16}（=32 block）的余数分布。
     * 若某些余数显著偏多 ⇒ 源头被钉在固定相位的格线上（即用户看到的"成排"）。
     */
    private static void residue(List<int[]> pts) {
        int[] hx = new int[16], hz = new int[16];
        for (int[] p : pts) {
            int cx = Math.floorDiv(p[0], 2);
            int cz = Math.floorDiv(p[1], 2);
            hx[Math.floorMod(cx, 16)]++;
            hz[Math.floorMod(cz, 16)]++;
        }
        double ex = pts.size() / 16.0;
        System.out.printf("%n--- phase histogram (cellCoord mod 16, expected %.1f each) ---%n", ex);
        System.out.print("x%16:");
        for (int i = 0; i < 16; i++) System.out.printf(" %4d", hx[i]);
        System.out.println();
        System.out.print("z%16:");
        for (int i = 0; i < 16; i++) System.out.printf(" %4d", hz[i]);
        System.out.println();
        double mx = 0, mz = 0;
        for (int i = 0; i < 16; i++) {
            mx = Math.max(mx, Math.abs(hx[i] - ex));
            mz = Math.max(mz, Math.abs(hz[i] - ex));
        }
        System.out.printf("PHASE_DEV_X=%.0f%%  PHASE_DEV_Z=%.0f%%%n",
                ex > 0 ? mx / ex * 100 : 0, ex > 0 ? mz / ex * 100 : 0);
    }

    private static int countAligned(List<int[]> pts, int tol, int limit) {
        int c = 0;
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                int dx = Math.abs(pts.get(i)[0] - pts.get(j)[0]);
                int dz = Math.abs(pts.get(i)[1] - pts.get(j)[1]);
                if (dx > limit || dz > limit) continue;
                if (dx <= tol || dz <= tol) c++;
            }
        }
        return c;
    }

    /** 32 格网格占用图（每格字符数=该格源头数，0 为 '.'）。 */
    private static void asciiMap(List<int[]> pts, int radius) {
        int cell = 32;
        int dim = radius * 2 / cell + 1;
        int[][] grid = new int[dim][dim];
        for (int[] p : pts) {
            int gx = (p[0] + radius) / cell;
            int gz = (p[1] + radius) / cell;
            if (gx < 0 || gz < 0 || gx >= dim || gz >= dim) continue;
            grid[gx][gz]++;
        }
        System.out.printf("%n--- occupancy map (each char = one 32x32 block cell) ---%n");
        StringBuilder head = new StringBuilder("     ");
        for (int x = 0; x < dim; x += 4) head.append(String.format("%-4d", x * cell - radius));
        System.out.println(head);
        for (int z = 0; z < dim; z++) {
            StringBuilder sb = new StringBuilder(String.format("%5d", z * cell - radius));
            for (int x = 0; x < dim; x++) {
                int v = grid[x][z];
                sb.append(v == 0 ? '.' : (v > 9 ? '*' : (char) ('0' + v)));
            }
            System.out.println(sb);
        }
    }

    private SourceDistributionProbe() { }
}