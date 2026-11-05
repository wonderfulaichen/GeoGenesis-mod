package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.generator.GeoGenesisGenerator;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 灌水缺口扫描（<b>只读</b>）：在指定 region 里找两类可疑格，并给出坐标。
 *
 * <ol>
 *   <li><b>水面台阶</b>：相邻两个「应该有水」的列，计划水位差 &gt; {@code 0.5} 块
 *       ⇒ 实机会看到水墙/错台（水柱高度不连续）。</li>
 *   <li><b>水体内的干洞</b>：某列干，但 8 邻里 ≥5 列有水且水位高于它的地面
 *       ⇒ 实机看到「水中间缺一块」。</li>
 * </ol>
 *
 * <p>判据全部来自生产 {@link Cell}（{@code riverType} / {@code riverSurfaceY} /
 * {@code height}），与 {@code GeoGenesisGenerator.fillTerrainColumn} 的灌水条件一致：
 * <pre>
 *   riverWater = cell.riverType != 0 &amp;&amp; cell.riverSurfaceY &gt; cell.height
 *   ocean      = cell.height &lt; seaLevel
 *   有水       = riverWater || ocean
 * </pre>
 * 湖列同样由 {@code riverType=1} 表示（见 {@code GeoGenesisTerrain:656}），
 * 所以本探针能直接覆盖"湖内部有没有缺列"。</p>
 *
 * <p><b>只读</b>：不写世界、不改配置；输出坐标供实机目检。
 * 用法：{@code gradlew runWaterFillGapProbe -PprobeArgs="0,-2 -3,-1"}</p>
 */
public final class WaterFillGapProbe {

    private static final int REGION = 512;
    private static final double EPS = 0.5;

    private WaterFillGapProbe() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of("build", "water-fill-gap");
        Files.createDirectories(out);

        List<int[]> regions = new ArrayList<>();
        for (String a : args) {
            String[] p = a.split(",");
            if (p.length == 2) {
                regions.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())});
            }
        }
        if (regions.isEmpty()) {
            regions.add(new int[]{0, -2});
            regions.add(new int[]{-3, -1});
        }

        TerrainParams tp = loadConfig(out);
        // 与生产 GeoGenesisGenerator.buildTerrain 同构：morphBase=true + 走 terrain.seed()
        // （只 gen.seed 会漏掉河网种子，见 buildTerrain 的注释）。
        CellGenerator gen = new CellGenerator(tp, (int) tp.minY(), (int) tp.maxY(), true);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(9139912035078620160L);

        System.out.printf("=== WaterFillGapProbe seed=9139912035078620160 regions=%d ===%n", regions.size());
        int totalSteps = 0;
        int totalHoles = 0;
        for (int[] r : regions) {
            int[] c = scan(terrain, r[0], r[1]);
            totalSteps += c[0];
            totalHoles += c[1];
        }
        System.out.println();
        System.out.printf("汇总：水面台阶邻接对=%d  体内干洞列=%d%n", totalSteps, totalHoles);
        gen.closeProcessErosion();
        System.exit(0);
    }

    /** 扫一个 region，返回 {台阶数, 干洞数}。 */
    private static int[] scan(GeoGenesisTerrain terrain, int rx, int rz) {
        int n = REGION;
        int baseX = rx * n;
        int baseZ = rz * n;
        double seaLevel = terrain.seaLevel();
        boolean[] wet = new boolean[n * n];
        double[] top = new double[n * n];
        double[] ground = new double[n * n];

        long t0 = System.nanoTime();
        int chunks = 0;
        for (int cx = 0; cx < n / 16; cx++) {
            for (int cz = 0; cz < n / 16; cz++) {
                Cell[] cells = terrain.getChunkCells(baseX / 16 + cx, baseZ / 16 + cz);
                chunks++;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Cell cell = cells[lx * 16 + lz];
                        int idx = (cx * 16 + lx) * n + (cz * 16 + lz);
                        if (cell == null) {
                            continue;
                        }
                        ground[idx] = cell.height;
                        boolean river = cell.riverType != 0 && cell.riverSurfaceY > cell.height;
                        boolean ocean = cell.height < seaLevel;
                        wet[idx] = river || ocean;
                        top[idx] = ocean ? Math.max(cell.riverSurfaceY, seaLevel) : cell.riverSurfaceY;
                    }
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println();
        System.out.printf("--- region(%d,%d) 扫完 %d chunks，%d ms ---%n", rx, rz, chunks, ms);

        int steps = 0;
        int holes = 0;
        List<String> stepSamples = new ArrayList<>();
        List<String> holeSamples = new ArrayList<>();
        int wetCount = 0;

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int idx = i * n + j;
                int wx = baseX + i;
                int wz = baseZ + j;
                if (wet[idx]) {
                    wetCount++;
                    // 台阶：右/下邻也有水但水位差 > 0.5
                    if (i + 1 < n && wet[idx + n] && Math.abs(top[idx + n] - top[idx]) > EPS) {
                        steps++;
                        if (stepSamples.size() < 8) {
                            stepSamples.add(String.format("(%d,%d) top=%.2f | (%d,%d) top=%.2f",
                                    wx, wz, top[idx], wx + 1, wz, top[idx + n]));
                        }
                    }
                    if (j + 1 < n && wet[idx + 1] && Math.abs(top[idx + 1] - top[idx]) > EPS) {
                        steps++;
                        if (stepSamples.size() < 8) {
                            stepSamples.add(String.format("(%d,%d) top=%.2f | (%d,%d) top=%.2f",
                                    wx, wz, top[idx], wx, wz + 1, top[idx + 1]));
                        }
                    }
                    continue;
                }
                // 干洞：8 邻里 >=5 有水，且邻水位高于本列地面
                int wetN = 0;
                double highest = Double.NEGATIVE_INFINITY;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) {
                            continue;
                        }
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) {
                            continue;
                        }
                        int k = a * n + b;
                        if (wet[k]) {
                            wetN++;
                            highest = Math.max(highest, top[k]);
                        }
                    }
                }
                if (wetN >= 5 && highest > ground[idx] + EPS) {
                    holes++;
                    if (holeSamples.size() < 8) {
                        holeSamples.add(String.format("(%d,%d) ground=%.2f 邻水位=%.2f 邻湿列=%d",
                                wx, wz, ground[idx], highest, wetN));
                    }
                }
            }
        }

        System.out.printf("  湿列=%d / %d  台阶对=%d  干洞=%d%n", wetCount, n * n, steps, holes);
        for (String s : stepSamples) {
            System.out.println("    台阶 " + s);
        }
        for (String s : holeSamples) {
            System.out.println("    干洞 " + s);
        }
        return new int[]{steps, holes};
    }

    private static TerrainParams loadConfig(Path out) {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.isRegularFile(src)) {
            throw new IllegalStateException("缺少生产配置：" + src.toAbsolutePath());
        }
        try {
            Path work = out.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return GeoGenesisConfig.INSTANCE.buildParams();
        } catch (Exception e) {
            throw new IllegalStateException("生产配置加载失败", e);
        }
    }
}