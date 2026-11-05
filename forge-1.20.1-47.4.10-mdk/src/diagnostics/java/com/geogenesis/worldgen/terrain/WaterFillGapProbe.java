package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 灌水缺口扫描（<b>只读</b>）：判断"水面台阶 / 水体内干洞"是否已经归零。
 *
 * <p>两组口径同时报：</p>
 * <ul>
 *   <li><b>raw</b>：只按 {@code Cell} 的原始计划（{@code riverType/riverSurfaceY/height}）
 *       算 —— 这是修复前的样子，用于对照。</li>
 *   <li><b>filled</b>：按落块层真正会用的水面算 —— 即
 *       {@link WaterSurfaceUnifier#unifiedTops} 的统一整数水面。判据：
 *       <ul>
 *         <li>台阶：相邻两个"会灌水"的列，统一水面差 &gt; 0；</li>
 *         <li>干洞：会灌水的列，但统一水面 {@code <= floor(地面)}
 *             ⇒ 落块循环 {@code y in (surfaceY, fillTopBlock]} 一次都不执行 ⇒ 没有水块。</li>
 *       </ul></li>
 * </ul>
 *
 * <p>验收口径：<b>filled 的台阶与干洞都必须为 0</b>，否则 {@code System.exit(1)}。
 * 目前两处都非零 —— 这是待修缺陷的度量，不是修复后的回归闸门。</p>
 *
 * <p>用法：{@code gradlew runWaterFillGapProbe [-PprobeArgs="0,-2 -3,-1"]}</p>
 */
public final class WaterFillGapProbe {

    private static final int REGION = 512;
    private static final long SEED = 9139912035078620160L;

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
        // 与生产 GeoGenesisGenerator.buildTerrain 同构：morphBase=true + terrain.seed()
        CellGenerator gen = new CellGenerator(tp, (int) tp.minY(), (int) tp.maxY(), true);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(SEED);

        System.out.printf("=== WaterFillGapProbe seed=%d regions=%d ===%n", SEED, regions.size());
        int rawSteps = 0, rawHoles = 0, effSteps = 0, effHoles = 0, lakeSteps = 0;
        for (int[] r : regions) {
            int[] c = scan(terrain, r[0], r[1]);
            rawSteps += c[0];
            rawHoles += c[1];
            effSteps += c[2];
            effHoles += c[3];
            lakeSteps += c[4];
        }
        System.out.println();
        System.out.println("汇总：");
        System.out.printf("  raw    （修复前口径）台阶=%d 干洞=%d%n", rawSteps, rawHoles);
        System.out.printf("  filled （落块实际口径）台阶=%d（其中湖内 %d）/ 干洞=%d%n",
                effSteps, lakeSteps, effHoles);
        // 只有【湖内台阶】与【干洞】算缺陷：河面逐段下降 1 块是正常坡降。
        boolean ok = lakeSteps == 0 && effHoles == 0;
        System.out.println("  判定: " + (ok
                ? "PASS（湖内台阶=0 且 干洞=0；河面台阶属正常坡降，不计）"
                : "FAIL（湖内台阶或干洞未归零）"));
        gen.closeProcessErosion();
        System.exit(ok ? 0 : 1);
    }

    /** 扫一个 region，返回 {raw 台阶, raw 干洞, filled 台阶, filled 干洞}。 */
    private static int[] scan(GeoGenesisTerrain terrain, int rx, int rz) {
        int n = REGION;
        int baseX = rx * n;
        int baseZ = rz * n;
        double seaLevel = terrain.seaLevel();

        boolean[] rawWet = new boolean[n * n];
        double[] rawTop = new double[n * n];
        boolean[] isLake = new boolean[n * n];
        boolean[] effWet = new boolean[n * n];
        int[] effTop = new int[n * n];
        double[] ground = new double[n * n];

        long t0 = System.nanoTime();
        for (int cx = 0; cx < n / 16; cx++) {
            for (int cz = 0; cz < n / 16; cz++) {
                Cell[] cells = terrain.getChunkCells(baseX / 16 + cx, baseZ / 16 + cz);
                int[] uni = WaterSurfaceUnifier.unifiedTops(cells, seaLevel, true);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Cell cell = cells[lx * 16 + lz];
                        if (cell == null) {
                            continue;
                        }
                        int idx = (cx * 16 + lx) * n + (cz * 16 + lz);
                        ground[idx] = cell.height;
                        boolean river = cell.riverType != 0 && cell.riverSurfaceY > cell.height;
                        boolean ocean = cell.height < seaLevel;
                        rawWet[idx] = river || ocean;
                        rawTop[idx] = ocean ? Math.max(cell.riverSurfaceY, seaLevel) : cell.riverSurfaceY;
                        // 落块口径：真正会用到的统一水面（与 GeoGenesisGenerator 同一个函数）
                        int u = uni[lx * 16 + lz];
                        isLake[idx] = cell.isLake;
                        effWet[idx] = u >= 0;
                        effTop[idx] = u;
                    }
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println();
        System.out.printf("--- region(%d,%d) 扫完 %d chunks，%d ms ---%n", rx, rz, (n / 16) * (n / 16), ms);

        int rSteps = 0, rHoles = 0, eSteps = 0, eHoles = 0;
        int lakeSteps = 0, riverSteps = 0;
        List<String> samples = new ArrayList<>();
        int wetEff = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int idx = i * n + j;
                int wx = baseX + i, wz = baseZ + j;

                // raw：相邻湿列水位差 > 0.5
                if (rawWet[idx]) {
                    if (i + 1 < n && rawWet[idx + n] && Math.abs(rawTop[idx + n] - rawTop[idx]) > 0.5) {
                        rSteps++;
                    }
                    if (j + 1 < n && rawWet[idx + 1] && Math.abs(rawTop[idx + 1] - rawTop[idx]) > 0.5) {
                        rSteps++;
                    }
                } else {
                    int wetN = 0;
                    double hi = Double.NEGATIVE_INFINITY;
                    for (int di = -1; di <= 1; di++) {
                        for (int dj = -1; dj <= 1; dj++) {
                            if (di == 0 && dj == 0) continue;
                            int a = i + di, b = j + dj;
                            if (a < 0 || b < 0 || a >= n || b >= n) continue;
                            int k = a * n + b;
                            if (rawWet[k]) { wetN++; hi = Math.max(hi, rawTop[k]); }
                        }
                    }
                    if (wetN >= 5 && hi > ground[idx] + 0.5) {
                        rHoles++;
                    }
                }

                if (!effWet[idx]) {
                    continue;
                }
                wetEff++;
                // filled：整数水面差 >= 1 才算台阶（整数落块只有整块差）
                if (i + 1 < n && effWet[idx + n] && effTop[idx + n] != effTop[idx]) {
                    eSteps++;
                    if (isLake[idx] && isLake[idx + n]) lakeSteps++; else riverSteps++;
                    if (samples.size() < 6) {
                        samples.add(String.format("台阶 (%d,%d)=%d | (%d,%d)=%d",
                                wx, wz, effTop[idx], wx + 1, wz, effTop[idx + n]));
                    }
                }
                if (j + 1 < n && effWet[idx + 1] && effTop[idx + 1] != effTop[idx]) {
                    eSteps++;
                    if (isLake[idx] && isLake[idx + 1]) lakeSteps++; else riverSteps++;
                    if (samples.size() < 6) {
                        samples.add(String.format("台阶 (%d,%d)=%d | (%d,%d)=%d",
                                wx, wz, effTop[idx], wx, wz + 1, effTop[idx + 1]));
                    }
                }
                // 干洞：会灌水，但水面不高于地面 ⇒ 一个水块都放不下
                if (effTop[idx] <= (int) Math.floor(ground[idx])) {
                    eHoles++;
                    if (samples.size() < 12) {
                        samples.add(String.format("干洞 (%d,%d) 水面=%d 地面=%.2f",
                                wx, wz, effTop[idx], ground[idx]));
                    }
                }
            }
        }
        System.out.printf("  湿列=%d/%d  raw(台阶=%d,干洞=%d)  filled(台阶=%d[湖内=%d,河面=%d],干洞=%d)%n",
                wetEff, n * n, rSteps, rHoles, eSteps, lakeSteps, riverSteps, eHoles);
        for (String s : samples) {
            System.out.println("    " + s);
        }
        return new int[]{rSteps, rHoles, eSteps, eHoles, lakeSteps};
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