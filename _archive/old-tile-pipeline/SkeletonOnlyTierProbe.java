package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「骨架单开 = 独立低配档」验收探针（★ 2026-11-04）。
 *
 * <h3>要回答什么</h3>
 * <p>用户口径：<b>骨架侵蚀就是为了作为粒子侵蚀的低配替代品</b>——关掉粒子侵蚀后
 * 仍要有近似沟壑，目的是降算力，不是加细节。「减粒子（erosionDropsMul）」不是这条线。</p>
 *
 * <p>交接文档 {@code handoff/GeoGenesis-20261104-preeorder.md} §2.2 第 4 条如实记着：
 * <b>「{@code erosionRidgeEnabled} 单开的真实观感没有出过图，没有任何日志真的以 drops=0 跑过」</b>。
 * 本探针补上这唯一缺失的证据：走<b>生产管线</b>出图 + 实测每 tile 耗时。</p>
 *
 * <h3>为什么必须走生产管线（AGENTS.md 用户规则 6）</h3>
 * <p>取样入口是 {@link CellGenerator#erodedHeight}：
 * {@code terrainEQuick + erosionDeltaE → getOrGenTile → generateErosionTile + blendTileDelta → softCapLandE → heightFromE}，
 * 与 chunk 生成同源。所有 tile 走 {@link CellGenerator#getErosionTileResultForProbe}
 * （不发起 8 邻居预热、不污染生产的 {@code hydro.tile.solve} 统计）。</p>
 *
 * <h3>三臂</h3>
 * <ul>
 *   <li>{@code full}    —— {@code erosionEnabled=true}  + {@code erosionRidgeEnabled=true}（生产默认）</li>
 *   <li>{@code skel}    —— {@code erosionEnabled=false} + {@code erosionRidgeEnabled=true}（本轮的「低配档」）</li>
 *   <li>{@code none}    —— 两者皆 false（纯基础地形，作对照下界）</li>
 * </ul>
 * <p>开关通过<b>两份 TOML 副本</b>驱动（{@code SPEC.setConfig}），而非只有探针能改的静态字段
 * ⇒ 证明玩家在配置屏上真正能选到这一档。</p>
 *
 * <h3>输出（{@code build/skeleton-only-tier-<ts>/}）</h3>
 * <ul>
 *   <li>{@code height-<arm>.f64be} —— 生产路径高度场（float64 小端，行主序）</li>
 *   <li>{@code delta-<arm>.f64be}  —— 生产路径侵蚀增量场（e 单位）</li>
 *   <li>{@code hillshade-<arm>.png} / {@code <arm>.png} —— 人眼所见</li>
 *   <li>{@code ab-full-vs-skel.png} —— 同窗口并排 A/B</li>
 *   <li>{@code tier-report.txt} / {@code tier-report.json} —— 耗时与形态指标</li>
 * </ul>
 *
 * <p>用法（在真工程目录下）：</p>
 * <pre>
 *   gradlew.bat runSkeletonOnlyTierProbe -PprobeArgs="seed size step originX originZ"
 *   例：gradlew.bat runSkeletonOnlyTierProbe -PprobeArgs="12345 512 1 1200 1200"
 * </pre>
 * <p>不传 origin 时自动扫描一个<b>陆地占比高且含丘陵/山地</b>的窗口——纯平原或纯海洋
 * 会让骨架条纹（坡度门控 combiMask）几乎不触发，得出「骨架没用」的假结论。</p>
 */
public final class SkeletonOnlyTierProbe {

    /** 无侵蚀基础地形的 soft cap 下限，与 generateErosionTile 的 base 同口径。 */
    private static final float E_FLOOR = -0.05f;

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        Integer ox = args.length > 3 ? Integer.parseInt(args[3]) : null;
        Integer oz = args.length > 4 ? Integer.parseInt(args[4]) : null;

        Path outDir = Path.of("build", "skeleton-only-tier-" + timestamp());
        Files.createDirectories(outDir);

        System.out.println("=== SkeletonOnlyTierProbe ===");
        System.out.printf("seed=%d size=%d step=%d out=%s%n", seed, size, step, outDir);

        TerrainParams tp = TerrainParams.defaults();
        double hs = tp.horizontalScale();

        // ---------- 0) 选窗口：必须在陆地丘陵/山地，否则骨架坡度过不了 combiMask ----------
        if (ox == null || oz == null) {
            int[] hit = findRuggedLandWindow(tp, seed, size, step);
            ox = hit[0];
            oz = hit[1];
            System.out.printf("自动定位 origin=(%d,%d)%n", ox, oz);
        }

        // ---------- 1) 三臂：各自一份 TOML 副本 ----------
        Map<String, Arm> arms = new LinkedHashMap<>();
        arms.put("none", new Arm("none", false, false));
        arms.put("skel", new Arm("skel", false, true));
        arms.put("full", new Arm("full", true, true));

        for (Arm arm : arms.values()) {
            arm.configCopy = writeArmConfig(outDir, arm);
        }

        List<ArmRun> runs = new ArrayList<>();
        for (Arm arm : arms.values()) {
            loadConfig(arm.configCopy);
            System.out.printf("%n--- arm=%s erosionEnabled=%s erosionRidgeEnabled=%s ---%n",
                    arm.name, arm.erosion, arm.ridge);
            verifyConfig(arm);

            CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
            gen.seed(seed);
            ArmRun run = runArm(gen, arm, ox, oz, size, step, outDir);
            runs.add(run);
            System.out.printf("  tile 数=%d 生成中位=%.1fms 总=%.1fms%n",
                    run.tileCount, run.tileMsMedian(), run.tileMsTotal);
        }

        // ---------- 2) 形态指标 + 相对完整粒子的偏离 ----------
        ArmRun full = runs.get(runs.size() - 1);
        Path report = outDir.resolve("tier-report.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("骨架单开（低配档）生产路径实测\n");
        sb.append("seed=").append(seed).append(" size=").append(size).append(" step=").append(step)
                .append(" origin=(").append(ox).append(',').append(oz).append(")\n");
        sb.append("hs=").append(hs).append('\n');
        sb.append("取样入口 CellGenerator.erodedHeight（生产管线），tile 入口 getErosionTileResultForProbe\n\n");

        sb.append(String.format(Locale.ROOT, "%-6s %10s %10s %10s | %8s %8s %8s | %10s %10s%n",
                "arm", "tiles", "genMed_ms", "genTot_ms", "meanH", "stdH", "slopeP90", "vsFullP50", "vsFullP90"));
        sb.append("-".repeat(104)).append('\n');
        for (ArmRun r : runs) {
            double p50 = Double.NaN, p90 = Double.NaN;
            if (r != full) {
                double[] d = absDiff(r.height, full.height);
                Arrays.sort(d);
                p50 = d[(int) (d.length * 0.50)];
                p90 = d[(int) (Math.min(d.length - 1, d.length * 0.90))];
            }
            sb.append(String.format(Locale.ROOT, "%-6s %10d %10.1f %10.1f | %8.2f %8.2f %8.4f | %10s %10s%n",
                    r.arm.name, r.tileCount, r.tileMsMedian(), r.tileMsTotal,
                    r.meanH, r.stdH, r.slopeP90,
                    Double.isNaN(p50) ? "—" : String.format(Locale.ROOT, "%.2f", p50),
                    Double.isNaN(p90) ? "—" : String.format(Locale.ROOT, "%.2f", p90)));
        }
        sb.append('\n');

        // 差异分布：骨架相对完整粒子，究竟是不是「同一批沟、只是更浅」
        ArmRun skel = find(runs, "skel");
        if (skel != null && skel.delta != null && full.delta != null) {
            appendDeltaComparison(sb, skel, full);
        }
        sb.append("\n每 tile 生成耗时明细（ms，按生成顺序）:\n");
        for (ArmRun r : runs) {
            sb.append("  ").append(r.arm.name).append(": ").append(r.tileMs).append('\n');
        }

        Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.print(sb);
        System.out.println("写出 " + report);

        Files.writeString(outDir.resolve("tier-report.json"),
                runs.stream().map(ArmRun::toJson).reduce((a, b) -> a + ",\n" + b)
                        .map(s -> "[\n" + s + "\n]\n").orElse("[]"),
                StandardCharsets.UTF_8);
    }

    // ===================== 单臂执行 =====================

    private static ArmRun runArm(CellGenerator gen, Arm arm, int ox, int oz, int size, int step,
                                 Path outDir) throws IOException {
        ArmRun r = new ArmRun();
        r.arm = arm;
        int span = size * step;
        int tiles = (int) Math.ceil((double) span / TILE_CENTER) + 2;

        // 预热：先按 48wu 网格把窗口覆盖到的 tile 全部显式生成并计时。
        // 网格锚点与生产一致：floorDiv(wu,48)*48（见 CellGenerator.getErosionTile）。
        int anchorX = Math.floorDiv(ox, TILE_CENTER) * TILE_CENTER;
        int anchorZ = Math.floorDiv(oz, TILE_CENTER) * TILE_CENTER;
        int cacheBefore = gen.erosionTileCacheSize();
        for (int tz = 0; tz < tiles; tz++) {
            for (int tx = 0; tx < tiles; tx++) {
                int cx = anchorX + tx * TILE_CENTER;
                int cz = anchorZ + tz * TILE_CENTER;
                long t0 = System.nanoTime();
                CellGenerator.ErosionTileResult res = gen.getErosionTileResultForProbe(cx, cz);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (res != null) {
                    r.tileMs.add(ms);
                    r.tileMsTotal += ms;
                }
            }
        }
        r.tileCount = r.tileMs.size();
        System.out.printf("  预热 tiles=%d 缓存 %d→%d%n", r.tileCount, cacheBefore, gen.erosionTileCacheSize());

        // 采样：高度走生产 erodedHeight；增量场单独取 erosionDeltaE（tile 已在缓存 ⇒ 只是 blend 读取）
        float[][] h = new float[size][size];
        float[][] d = new float[size][size];
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double wx = ox + px * (double) step;
                double wz = oz + py * (double) step;
                h[py][px] = (float) gen.erodedHeight(wx, wz);
                d[py][px] = (float) gen.erosionDeltaE(wx, wz);
            }
        }
        r.height = h;
        r.delta = d;

        Stats st = stats(h);
        r.meanH = st.mean;
        r.stdH = st.std;
        r.slopeP90 = st.slopeP90;

        writeF64(outDir.resolve("height-" + arm.name + ".f64be"), h);
        writeF64(outDir.resolve("delta-" + arm.name + ".f64be"), d);
        ImageIO.write(toPng(h, st), "png", outDir.resolve("hillshade-" + arm.name + ".png").toFile());
        ImageIO.write(toShaded(h, st), "png", outDir.resolve(arm.name + ".png").toFile());
        System.out.printf("  写出 height/delta/hillshade/%s.png%n", arm.name);
        return r;
    }

    // ===================== 配置副本 =====================

    private static Path writeArmConfig(Path outDir, Arm arm) throws IOException {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        String text = Files.exists(src)
                ? Files.readString(src, StandardCharsets.UTF_8)
                : defaultConfigText();
        text = setBool(text, "erosionEnabled", arm.erosion);
        text = setBool(text, "erosionRidgeEnabled", arm.ridge);
        Path copy = outDir.resolve("config-" + arm.name + ".toml");
        Files.writeString(copy, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return copy;
    }

    /** 在 TOML 文本里改一个布尔键；不存在则追加。 */
    private static String setBool(String text, String key, boolean value) {
        String line = key + " = " + value;
        String re = "(?m)^\\s*" + key + "\\s*=\\s*(true|false).*$";
        if (text.matches("(?s).*" + re + ".*")) {
            return text.replaceAll(re, line);
        }
        return text + (text.endsWith("\n") ? "" : "\n") + line + "\n";
    }

    private static String defaultConfigText() {
        return "[erosion]\nerosionEnabled = true\nerosionRidgeEnabled = true\n";
    }

    private static void loadConfig(Path copy) throws IOException {
        CommentedFileConfig config = CommentedFileConfig.of(copy, TomlFormat.instance());
        config.load();
        // 与 ErosionScratchProbe.loadConfig 同款流程：SPEC.setConfig 必须早于任何 .get()
        GeoGenesisConfig.SPEC.setConfig(config);
        if (!GeoGenesisConfig.SPEC.isLoaded()) {
            throw new IllegalStateException("TOML 未真实加载: " + copy);
        }
        if (GeoGenesisConfig.configFingerprint() == 0L) {
            throw new IllegalStateException("配置指纹不可用（INSTANCE 未读到真值）: " + copy);
        }
    }

    /**
     * 装配自检：<b>否则三臂会静默退化成同一个配置</b>（历史事故：观测口径与配置不一致）。
     * 断言每臂读到的开关就是我们要的，不一致直接抛错而不是继续出图。
     */
    private static void verifyConfig(Arm arm) {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        boolean e = c.erosionEnabled.get();
        boolean r = c.erosionRidgeEnabled.get();
        System.out.printf("  自检: erosionEnabled=%s erosionRidgeEnabled=%s fingerprint=%d%n",
                e, r, GeoGenesisConfig.configFingerprint());
        if (e != arm.erosion || r != arm.ridge) {
            throw new IllegalStateException(String.format(
                    "配置自检失败：臂 %s 期望 erosion=%s ridge=%s，实际 erosion=%s ridge=%s",
                    arm.name, arm.erosion, arm.ridge, e, r));
        }
    }

    // ===================== 窗口定位 =====================

    /**
     * 扫描若干候选窗口，选「陆地占比 ≥55% 且坡度 P90 最大」的一个。
     * 骨架条纹由坡度门控 combiMask 触发 ⇒ 必须在丘陵/山地才看得出形态。
     */
    private static int[] findRuggedLandWindow(TerrainParams tp, long seed, int size, int step) {
        CellGenerator probe = new CellGenerator(tp, tp.minY(), tp.maxY());
        probe.seed(seed);
        double seaY = probe.heightCurve.heightFromE(probe.heightCurve.seaE());
        int g = 16;
        int spanUnit = Math.max(1, size * step / g);
        double bestScore = -1;
        int[] best = {0, 0};
        for (int cz = -3; cz <= 3; cz++) {
            for (int cx = -3; cx <= 3; cx++) {
                int ox = cx * 2000, oz = cz * 2000;
                float[][] h = new float[g][g];
                int land = 0;
                for (int py = 0; py < g; py++) {
                    for (int px = 0; px < g; px++) {
                        double y = probe.heightCurve.heightFromE(
                                Math.max(probe.terrainE(ox + (long) px * spanUnit,
                                        oz + (long) py * spanUnit), E_FLOOR));
                        h[py][px] = (float) y;
                        if (y > seaY) land++;
                    }
                }
                double landFrac = land / (double) (g * g);
                if (landFrac < 0.55) continue;
                Stats st = stats(h);
                double score = st.slopeP90 * landFrac;
                if (score > bestScore) {
                    bestScore = score;
                    best = new int[]{ox, oz};
                }
            }
        }
        System.out.printf("自动定位: 选坡度×陆地占比最高窗口 (score=%.4f)%n", bestScore);
        return best;
    }

    // ===================== 统计与出图 =====================

    private static final class Stats {
        double mean, std, slopeP90;
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
    }

    private static Stats stats(float[][] h) {
        Stats s = new Stats();
        int n = h.length * h[0].length;
        double sum = 0;
        for (float[] row : h) {
            for (float v : row) {
                sum += v;
                if (v < s.min) s.min = v;
                if (v > s.max) s.max = v;
            }
        }
        s.mean = sum / n;
        double vs = 0;
        for (float[] row : h) {
            for (float v : row) vs += (v - s.mean) * (v - s.mean);
        }
        s.std = Math.sqrt(vs / n);
        double[] g = new double[n];
        int k = 0;
        for (int y = 0; y < h.length; y++) {
            for (int x = 0; x < h[0].length; x++) {
                int xm = Math.max(0, x - 1), xp = Math.min(h[0].length - 1, x + 1);
                int ym = Math.max(0, y - 1), yp = Math.min(h.length - 1, y + 1);
                double gx = (h[y][xp] - h[y][xm]) / Math.max(1, xp - xm);
                double gy = (h[yp][x] - h[ym][x]) / Math.max(1, yp - ym);
                g[k++] = Math.hypot(gx, gy);
            }
        }
        Arrays.sort(g);
        s.slopeP90 = g[(int) (g.length * 0.90)];
        return s;
    }

    private static double[] absDiff(float[][] a, float[][] b) {
        double[] d = new double[a.length * a[0].length];
        int k = 0;
        for (int y = 0; y < a.length; y++) {
            for (int x = 0; x < a[0].length; x++) d[k++] = Math.abs(a[y][x] - b[y][x]);
        }
        return d;
    }

    private static BufferedImage toPng(float[][] h, Stats s) {
        int w = h[0].length, hh = h.length;
        BufferedImage img = new BufferedImage(w, hh, BufferedImage.TYPE_INT_RGB);
        double span = Math.max(1e-6, s.max - s.min);
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                int g = (int) Math.round(255.0 * (h[y][x] - s.min) / span);
                g = Math.max(0, Math.min(255, g));
                img.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        return img;
    }

    /** 山体阴影：西北光，标准 Lambert 着色（人眼读数用）。 */
    private static BufferedImage toShaded(float[][] h, Stats s) {
        int w = h[0].length, hh = h.length;
        BufferedImage img = new BufferedImage(w, hh, BufferedImage.TYPE_INT_RGB);
        double span = Math.max(1e-6, s.max - s.min);
        double[] lx = {-0.5, -0.5, 0.7071};
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                int xm = Math.max(0, x - 1), xp = Math.min(w - 1, x + 1);
                int ym = Math.max(0, y - 1), yp = Math.min(hh - 1, y + 1);
                double gx = (h[y][xp] - h[y][xm]) / Math.max(1, xp - xm);
                double gy = (h[yp][x] - h[ym][x]) / Math.max(1, yp - ym);
                double nx = -gx, ny = -gy, nz = 1.0;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double shade = (nx * lx[0] + ny * lx[1] + nz * lx[2]) / len;
                shade = Math.max(0.12, Math.min(1.0, 0.55 + 0.45 * shade));
                double t = (h[y][x] - s.min) / span;
                int r = (int) (255 * shade * (0.55 + 0.35 * t));
                int gg = (int) (255 * shade * (0.62 + 0.30 * t));
                int b = (int) (255 * shade * (0.60 + 0.25 * t));
                img.setRGB(x, y, (clamp8(r) << 16) | (clamp8(gg) << 8) | clamp8(b));
            }
        }
        return img;
    }

    private static int clamp8(int v) { return Math.max(0, Math.min(255, v)); }

    private static void writeF64(Path p, float[][] a) throws IOException {
        int w = a[0].length;
        byte[] buf = new byte[a.length * w * 8];
        int k = 0;
        for (float[] row : a) {
            for (float v : row) {
                long bits = Double.doubleToLongBits(v);
                for (int b = 0; b < 8; b++) buf[k++] = (byte) (bits >>> (8 * b));
            }
        }
        Files.write(p, buf);
    }

    // ===================== 报告辅助 =====================

    private static void appendDeltaComparison(StringBuilder sb, ArmRun skel, ArmRun full) {
        sb.append("骨架 vs 完整粒子（同窗口，e 单位增量场）:\n");
        double[] sd = flatten(skel.delta), fd = flatten(full.delta);
        double corr = corr(sd, fd);
        double sAbs = meanAbs(sd), fAbs = meanAbs(fd);
        sb.append(String.format(Locale.ROOT,
                "  增量场相关系数 r=%.4f；平均|增量| 骨架=%.5f 完整=%.5f（比值 %.2f）%n",
                corr, sAbs, fAbs, fAbs == 0 ? 0 : sAbs / fAbs));
        double[] dh = absDiff(skel.height, full.height);
        Arrays.sort(dh);
        sb.append(String.format(Locale.ROOT,
                "  高度差 |Δh|（格）P50=%.2f P90=%.2f max=%.2f%n",
                dh[dh.length / 2], dh[(int) (dh.length * 0.90)], dh[dh.length - 1]));
        sb.append("  判读：r 高且 |增量| 比值≈0.3~0.7 ⇒ 同一批沟、幅度更浅（真正的低配替代品）；\n");
        sb.append("        r 低 ⇒ 骨架切的是另一批沟，不构成替代关系。\n");
    }

    private static double[] flatten(float[][] a) {
        double[] r = new double[a.length * a[0].length];
        int k = 0;
        for (float[] row : a) for (float v : row) r[k++] = v;
        return r;
    }

    private static double meanAbs(double[] a) {
        double s = 0;
        for (double v : a) s += Math.abs(v);
        return s / Math.max(1, a.length);
    }

    private static double corr(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;
        double sa = 0, sb2 = 0, sab = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            sa += da * da; sb2 += db * db; sab += da * db;
        }
        double den = Math.sqrt(sa * sb2);
        return den < 1e-12 ? 0 : sab / den;
    }

    private static ArmRun find(List<ArmRun> runs, String name) {
        for (ArmRun r : runs) if (r.arm.name.equals(name)) return r;
        return null;
    }

    private static String timestamp() {
        java.time.LocalDateTime t = java.time.LocalDateTime.now();
        return String.format("%04d%02d%02d-%02d%02d%02d",
                t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond());
    }

    private static final class Arm {
        final String name;
        final boolean erosion, ridge;
        Path configCopy;

        Arm(String name, boolean erosion, boolean ridge) {
            this.name = name;
            this.erosion = erosion;
            this.ridge = ridge;
        }
    }

    private static final class ArmRun {
        Arm arm;
        float[][] height, delta;
        int tileCount;
        double tileMsTotal;
        final List<Long> tileMs = new ArrayList<>();
        double meanH, stdH, slopeP90;

        double tileMsMedian() {
            if (tileMs.isEmpty()) return 0;
            List<Long> c = new ArrayList<>(tileMs);
            c.sort(null);
            return c.get(c.size() / 2);
        }

        String toJson() {
            return String.format(Locale.ROOT,
                    "  {\"arm\":\"%s\",\"erosionEnabled\":%s,\"erosionRidgeEnabled\":%s,"
                            + "\"tiles\":%d,\"genMedianMs\":%.1f,\"genTotalMs\":%.1f,"
                            + "\"meanH\":%.3f,\"stdH\":%.3f,\"slopeP90\":%.5f}",
                    arm.name, arm.erosion, arm.ridge, tileCount, tileMsMedian(), tileMsTotal,
                    meanH, stdH, slopeP90);
        }
    }
}
