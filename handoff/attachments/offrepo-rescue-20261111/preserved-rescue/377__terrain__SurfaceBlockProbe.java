package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.Frequency;
import com.geogenesis.worldgen.noise.Noise;
import com.geogenesis.worldgen.noise.Noises;
import com.geogenesis.worldgen.noise.Simplex;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 地表方块<b>阈值边界</b>诊断探针（★ 2026-09-15）—— 用户反馈"群系之间过渡不自然，特别是地表方块"。
 *
 * <h3>聚焦的问题：抖动工具是否用错</h3>
 * <p>生产的地表方块判定是多级阈值链（{@code GeoGenesisGenerator.fillTerrainColumn}
 * 第 553 / 568 行），阈值输入是 {@code steepened}：</p>
 * <pre>
 *   steepened = cell.gradient + (hash01(wx,wz) - 0.5) * 2 * GRADIENT_JITTER   (0.06)
 *   steepened &gt; 0.40 → 陡坡裸岩 ;  steepened &gt; 0.30 &amp; 汇流高 → 碎石坡
 * </pre>
 *
 * <h3>★ 核心怀疑：逐格 hash 抖动会产生"盐和胡椒"碎斑</h3>
 * <p>{@code hash01} 是<b>逐格独立</b>随机 —— 相邻两格的抖动值毫无相关性。用它抖动
 * 阈值，阈值附近会逐块翻转 ⇒ 边界是<b>单格级噪点</b>而非有机曲线（视觉上的"碎"）。</p>
 *
 * <p><b>反证</b>：同一项目在 {@code variantTerrain}（T12）用的是<b>噪声</b>抖动
 * （空间相关 ⇒ 有机斑块）。<b>同一项目里两套抖动工具</b> —— 本探针回答坡度那套是否用错。</p>
 *
 * <h3>同进程 A/B/C（唯一变量：抖动方式；幅度完全相同）</h3>
 * <table border="1">
 *   <caption>三种抖动方式</caption>
 *   <tr><th>方案</th><th>抖动源</th><th>空间相关性</th></tr>
 *   <tr><td>{@code none}</td><td>无</td><td>—（基准：光滑等值线）</td></tr>
 *   <tr><td>{@code hash}</td><td>{@code hash01}（生产现状）</td><td><b>零</b>（逐格独立）</td></tr>
 *   <tr><td>{@code noise}</td><td>{@code Simplex} scale≈6wu</td><td><b>有</b>（有机）</td></tr>
 * </table>
 *
 * <h3>决定性指标：孤立单格率</h3>
 * <p><b>四邻域全部异类</b>的像素占比 —— "盐和胡椒"的精确度量。另在
 * <b>阈值敏感区</b>（{@code |gradient−阈值| &lt; jitter}）内单独统计，
 * 这是碎斑必然出现的地方。逐格 hash 会让它飙升，噪声抖动不会。</p>
 *
 * <h3>为何零 MC 依赖</h3>
 * <p>只镜像阈值链的前两级（裸岩 / 碎石坡）—— 它们只读 {@code Cell.gradient} 与
 * {@code Cell.riverNetDischarge}，<b>零 MC 依赖</b>，正是"地表过渡"最显眼的边界。
 * （初版尝试调 {@code BiomeClassifier.surfaceOf}，但它依赖 MC {@code Biomes}
 * 注册表，而 {@code Bootstrap.bootStrap()} 会连带引导 Forge 网络而失败 —— 已放弃该路线。）</p>
 *
 * <pre>{@code gradlew runSurfaceBlockProbe [-PprobeArgs="seed chunks"]}</pre>
 */
public final class SurfaceBlockProbe {

    private SurfaceBlockProbe() { }

    // ===== 生产常量镜像（务必与 GeoGenesisGenerator 一致）=====
    /** 陡坡裸岩阈值 —— {@code GeoGenesisGenerator.ROCK_GRADIENT}。 */
    private static final double ROCK_GRADIENT = 0.40;
    /** 坡度逐格抖动幅度 —— {@code GeoGenesisGenerator.GRADIENT_JITTER}。 */
    private static final double GRADIENT_JITTER = 0.06;
    /** 坡度抖动盐 —— {@code GeoGenesisGenerator} 第 538 行。 */
    private static final long SALT_STEEPEN = 0x2A7B_51C9_6E30_4D81L;
    /** 噪声抖动的特征尺度（wu）：比 cell 大、比群系小 ⇒ "细小但有机"。 */
    private static final double NOISE_JITTER_SCALE = 6.0;
    private static final int SALT_JITTER_NOISE = 0x6D3FA281;

    private static final int J_NONE = 0, J_HASH = 1, J_NOISE = 2;
    private static final String[] J_NAMES = { "none", "hash(生产)", "noise" };

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int chunks = args.length > 1 ? Integer.parseInt(args[1]) : 8;

        System.out.printf("=== SurfaceBlockProbe seed=%d chunks=%d ===%n", seed, chunks);
        System.out.printf("阈值=%.2f  抖动幅度=%.2f（三方案相同，仅空间相关性不同）%n",
                ROCK_GRADIENT, GRADIENT_JITTER);
        System.out.println("★ 生产现状 = noise（2026-09-15 由 hash 改为噪声）；hash 仅作对照。");

        Noise jitterNoise = new Frequency(new Simplex(SALT_JITTER_NOISE), 1.0 / NOISE_JITTER_SCALE);
        Noises.seedAll(jitterNoise, seed, 0);

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        int side = chunks * 16;
        int total = side * side;
        boolean[][] rock = new boolean[3][total];
        double[] grad = new double[total];
        boolean[] has = new boolean[total];

        int cx0 = -chunks / 2, cz0 = -chunks / 2;
        for (int ccx = 0; ccx < chunks; ccx++) {
            for (int ccz = 0; ccz < chunks; ccz++) {
                Cell[] cells = terrain.getChunkCells(cx0 + ccx, cz0 + ccz);
                if (cells == null) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        Cell c = cells[lx * 16 + lz];
                        if (c == null) continue;
                        int idx = (ccz * 16 + lz) * side + (ccx * 16 + lx);
                        int wx = (cx0 + ccx) * 16 + lx, wz = (cz0 + ccz) * 16 + lz;
                        grad[idx] = c.gradient;
                        has[idx] = true;
                        for (int j = 0; j < 3; j++) {
                            rock[j][idx] = steepened(c, wx, wz, j, jitterNoise) > ROCK_GRADIENT;
                        }
                    }
                }
            }
        }

        // ---------- 统计 ----------
        System.out.println("[1] 陡坡裸岩占比与边界特征:");
        System.out.printf("    %-12s %10s %12s %14s%n", "抖动方式", "裸岩占比", "边界密度", "孤立单格率");
        double[] iso = new double[3];
        for (int j = 0; j < 3; j++) {
            long n = 0, tot = 0;
            for (int i = 0; i < total; i++) {
                if (!has[i]) continue;
                tot++;
                if (rock[j][i]) n++;
            }
            iso[j] = isolatedRate(rock[j], has, side);
            System.out.printf("    %-12s %9.2f%% %12.4f %13.3f%%%n", J_NAMES[j],
                    tot == 0 ? 0.0 : 100.0 * n / tot,
                    edgeDensity(rock[j], has, side), 100.0 * iso[j]);
        }

        // ---------- 阈值敏感区（碎斑必然出现处）----------
        System.out.printf("[2] 阈值敏感区（|gradient-%.2f| < %.2f）的孤立单格率:%n",
                ROCK_GRADIENT, GRADIENT_JITTER);
        long sensTot = 0;
        for (int i = 0; i < total; i++) {
            if (has[i] && Math.abs(grad[i] - ROCK_GRADIENT) < GRADIENT_JITTER) sensTot++;
        }
        System.out.printf("    敏感区像素=%d（占有效 %.2f%%）%n", sensTot,
                100.0 * sensTot / Math.max(1, countTrue(has)));
        for (int j = 0; j < 3; j++) {
            System.out.printf("    %-12s 敏感区孤立率=%6.2f%%%n", J_NAMES[j],
                    100.0 * sensitiveIso(rock[j], has, grad, side));
        }

        // ---------- 渲染 ----------
        render(rock[J_NONE], has, side, "rock_none");
        render(rock[J_HASH], has, side, "rock_hash");
        render(rock[J_NOISE], has, side, "rock_noise");
        renderGrad(grad, has, side, "gradient");
        System.out.println("[3] 图像输出: build/surface/{rock_none,rock_hash,rock_noise,gradient}.png");

        // ---------- 判据 ----------
        boolean pass1 = iso[J_HASH] < 0.05;
        System.out.printf("[判据1] hash 抖动孤立单格率 < 5%%: %s（%.3f%%）%n",
                pass1 ? "PASS" : "FAIL", 100.0 * iso[J_HASH]);

        boolean pass2 = iso[J_NOISE] <= iso[J_HASH];
        System.out.printf("[判据2] 噪声抖动不劣于 hash: %s（%.3f%% vs %.3f%%）%n",
                pass2 ? "PASS" : "FAIL", 100.0 * iso[J_NOISE], 100.0 * iso[J_HASH]);

        double sHash = sensitiveIso(rock[J_HASH], has, grad, side);
        double sNoise = sensitiveIso(rock[J_NOISE], has, grad, side);
        System.out.printf("[判据3] 敏感区：hash %.2f%% vs noise %.2f%% → %s%n",
                100 * sHash, 100 * sNoise,
                sHash > sNoise * 1.3 ? "★ hash 显著更碎（证实怀疑）" : "hash 不显著更碎");

        boolean all = pass1 && pass2;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
    }

    private static double steepened(Cell c, int wx, int wz, int mode, Noise jitterNoise) {
        switch (mode) {
            case J_HASH:
                return c.gradient + (hash01(wx, wz, SALT_STEEPEN) - 0.5f) * 2.0f * GRADIENT_JITTER;
            case J_NOISE:
                return c.gradient + jitterNoise.compute(wx, wz) * GRADIENT_JITTER;
            default:
                return c.gradient;
        }
    }

    private static long countTrue(boolean[] a) {
        long n = 0;
        for (boolean b : a) if (b) n++;
        return n;
    }

    /** 相邻异类占比（4 邻域）。 */
    private static double edgeDensity(boolean[] a, boolean[] has, int side) {
        long diff = 0, tot = 0;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int i = z * side + x;
                if (!has[i]) continue;
                if (x + 1 < side && has[i + 1]) { tot++; if (a[i + 1] != a[i]) diff++; }
                if (z + 1 < side && has[i + side]) { tot++; if (a[i + side] != a[i]) diff++; }
            }
        }
        return tot == 0 ? 0 : (double) diff / tot;
    }

    /** 孤立单格率：四邻域全部异类的像素占比（"盐和胡椒"的精确度量）。 */
    private static double isolatedRate(boolean[] a, boolean[] has, int side) {
        long n = 0;
        for (int z = 1; z < side - 1; z++) {
            for (int x = 1; x < side - 1; x++) {
                int i = z * side + x;
                if (!has[i]) continue;
                boolean v = a[i];
                if (a[i - 1] != v && a[i + 1] != v && a[i - side] != v && a[i + side] != v) n++;
            }
        }
        return (double) n / Math.max(1, countTrue(has));
    }

    /** 仅统计阈值敏感区内的孤立单格率。 */
    private static double sensitiveIso(boolean[] a, boolean[] has, double[] grad, int side) {
        long n = 0, tot = 0;
        for (int z = 1; z < side - 1; z++) {
            for (int x = 1; x < side - 1; x++) {
                int i = z * side + x;
                if (!has[i]) continue;
                if (Math.abs(grad[i] - ROCK_GRADIENT) >= GRADIENT_JITTER) continue;
                tot++;
                boolean v = a[i];
                if (a[i - 1] != v && a[i + 1] != v && a[i - side] != v && a[i + side] != v) n++;
            }
        }
        return tot == 0 ? 0.0 : (double) n / tot;
    }

    private static void render(boolean[] a, boolean[] has, int side, String name) throws Exception {
        File dir = new File("build/surface");
        dir.mkdirs();
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < a.length; i++) {
            int rgb = !has[i] ? 0x000000 : (a[i] ? 0x8A8A8A : 0x4C9A3A);
            img.setRGB(i % side, i / side, rgb);
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    private static void renderGrad(double[] g, boolean[] has, int side, String name) throws Exception {
        File dir = new File("build/surface");
        dir.mkdirs();
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < g.length; i++) {
            if (!has[i]) { img.setRGB(i % side, i / side, 0x000000); continue; }
            int rgb;
            if (Math.abs(g[i] - ROCK_GRADIENT) < GRADIENT_JITTER) {
                rgb = 0xB00000;                              // 敏感区高亮为红
            } else {
                int v = (int) Math.max(0, Math.min(255, g[i] / 1.2 * 255));
                rgb = (v << 16) | (v << 8) | v;
            }
            img.setRGB(i % side, i / side, rgb);
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    /** 镜像 {@code GeoGenesisGenerator.hash01}（逐格确定性随机）。 */
    private static float hash01(int wx, int wz, long salt) {
        long h = (long) wx * 374761393L + (long) wz * 668265263L + salt;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return ((h & 0xFFFFFFL) / (float) 0x1000000L);
    }
}
