package com.geogenesis.morph.parity;

import com.geogenesis.worldgen.terrain.TerrainParams;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * <b>P0 对拍器</b>：新包 {@code com.geogenesis.worldgen.terrain.morph} 与已验证候选
 * （{@code ...terrain.candidate}）逐点比较。
 *
 * <h3>模式</h3>
 * <ul>
 *   <li>{@code canonical} — 正典种子 + 1401² 网格，比对高度数组 SHA（BEA0 锚点）；</li>
 *   <li>{@code seed <n> [size]} — 任意种子的 N² 网格 + 6 个远场点，比 <b>e</b>；</li>
 *   <li>{@code land <n> [size]} — 只比陆地侧 e（隔离海洋装配）。</li>
 * </ul>
 *
 * <h3>★ 判定口径：不要求逐位相等</h3>
 * <p>新包把一批 {@code float} 节点改成<b>显式访问器 + 统一 double 求值路径</b>，
 * 末位差异是<b>预期</b>的（旧候选在 float 与 double 两条路径间混用）。所以门槛是
 * <b>物理量级</b>：{@code max|Δ| ≤ 0.05}（格或 e，见模式）且无非有限值。
 * 若 {@code max|Δ|} 突然变大，说明改动触及了公式而不是精度 —— 那才是要查的。</p>
 */
public final class ParityMain {

    /** 正典种子（候选包 {@code LandCompositionGallery.SEED}）。 */
    private static final long CANONICAL_SEED = 9139912035078620160L;
    /** 已认可的正典高度数组 SHA-256（double 臂）。仅作参考打印，不作为门槛。 */
    private static final String CANONICAL_DOUBLE_SHA =
            "BEA0A47C14D32FA9E2654C77CC6984999004E137E9E6BDE8A05D3E2B2A393965";
    /** 1401² 渲染窗口中心与步长（与候选 Gallery 完全一致）。 */
    private static final double CX = -140, CZ = 137;
    private static final int CANON_SIZE = 1401, CANON_STEP = 5;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 0) {
            System.out.println("usage: canonical | full <seed> | seed <n> [size] | land <n> [size]");
            System.exit(2);
        }
        switch (args[0]) {
            case "canonical" -> canonical();
            case "full" -> fullImage(Long.parseLong(args[1]));
            case "seed" -> grid(Long.parseLong(args[1]),
                    args.length > 2 ? Integer.parseInt(args[2]) : 241, true);
            case "land" -> grid(Long.parseLong(args[1]),
                    args.length > 2 ? Integer.parseInt(args[2]) : 241, false);
            default -> throw new IllegalArgumentException("mode: canonical | full | seed | land");
        }
    }

    // ============================================================ 全幅 1401²（任意种子）

    /**
     * 任意种子的 1401² 全幅高度数组：与 {@code MultiSeedChain full} 同窗口/同网格，
     * 用来核对种子 12345 的 {@code 340224...} 锚点。
     */
    private static void fullImage(long seed) throws Exception {
        TerrainParams params = TerrainParams.defaults();
        com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler mine =
                new com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler(seed, params, -64, 320);
        com.geogenesis.worldgen.terrain.candidate.ParityBridge ref =
                new com.geogenesis.worldgen.terrain.candidate.ParityBridge(seed, params, -64, 320);

        int n = CANON_SIZE * CANON_SIZE, nonFinite = 0, diff = 0;
        double[] mineH = new double[n];
        double[] refH = new double[n];
        double maxAbs = 0;
        for (int j = 0; j < CANON_SIZE; j++) {
            for (int i = 0; i < CANON_SIZE; i++) {
                double x = CX + (i - CANON_SIZE / 2.0) * CANON_STEP;
                double z = CZ + (j - CANON_SIZE / 2.0) * CANON_STEP;
                double h = mine.height(x, z);
                double th = ref.height(x, z);
                mineH[j * CANON_SIZE + i] = h;
                refH[j * CANON_SIZE + i] = th;
                if (!Double.isFinite(h)) nonFinite++;
                if (Double.doubleToLongBits(h) != Double.doubleToLongBits(th)) diff++;
                maxAbs = Math.max(maxAbs, Math.abs(h - th));
            }
            if (j % 400 == 0) System.out.println("row=" + j + " diff=" + diff);
        }
        String shaMine = sha(mineH), shaRef = sha(refH);
        System.out.println("=== full seed " + seed + " (1401^2) ===");
        System.out.printf(Locale.ROOT, "nonFinite=%d bitDifferent=%d/%d maxAbsDY=%.6g%n",
                nonFinite, diff, n, maxAbs);
        System.out.println("mineSha=" + shaMine);
        System.out.println("refSha =" + shaRef);
        System.out.println("shaEqual=" + shaMine.equals(shaRef));
        boolean pass = nonFinite == 0 && shaMine.equals(shaRef);
        System.out.println("VERDICT," + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 2);
    }

    // ============================================================ 正典 1401²

    private static void canonical() throws Exception {
        TerrainParams params = TerrainParams.defaults();
        Mira mi = new Mira(CANONICAL_SEED, params, -64, 320);
        com.geogenesis.worldgen.terrain.candidate.ParityBridge ref =
                new com.geogenesis.worldgen.terrain.candidate.ParityBridge(CANONICAL_SEED, params, -64, 320);

        int n = CANON_SIZE * CANON_SIZE;
        double[] mineH = new double[n];
        int nonFinite = 0, diff = 0;
        double maxAbs = 0, sumAbs = 0;
        double maxAtX = 0, maxAtZ = 0;
        for (int j = 0; j < CANON_SIZE; j++) {
            for (int i = 0; i < CANON_SIZE; i++) {
                double x = CX + (i - CANON_SIZE / 2.0) * CANON_STEP;
                double z = CZ + (j - CANON_SIZE / 2.0) * CANON_STEP;
                double h = mi.height(x, z);
                double th = ref.height(x, z);
                mineH[j * CANON_SIZE + i] = h;
                if (!Double.isFinite(h)) nonFinite++;
                if (Double.doubleToLongBits(h) != Double.doubleToLongBits(th)) diff++;
                if (Double.isFinite(h) && Double.isFinite(th)) {
                    double d = Math.abs(h - th);
                    if (d > maxAbs) { maxAbs = d; maxAtX = x; maxAtZ = z; }
                    sumAbs += d;
                }
            }
            if (j % 200 == 0) {
                System.out.printf(Locale.ROOT, "row=%d diff=%d maxAbsDY=%.6g%n", j, diff, maxAbs);
            }
        }

        String shaMine = sha(mineH);
        System.out.println("=== canonical seed " + CANONICAL_SEED + " (1401^2, step "
                + CANON_STEP + ") ===");
        System.out.printf(Locale.ROOT, "pixels=%d nonFinite=%d%n", n, nonFinite);
        System.out.printf(Locale.ROOT, "heightBitDifferent=%d/%d (%.2f%%)%n",
                diff, n, 100.0 * diff / n);
        System.out.printf(Locale.ROOT, "maxAbsDY=%.6g meanAbsDY=%.6g at x=%.1f z=%.1f%n",
                maxAbs, sumAbs / n, maxAtX, maxAtZ);
        System.out.println("heightSha=" + shaMine);
        System.out.println("heightShaMatchesAnchor=" + shaMine.equals(CANONICAL_DOUBLE_SHA));
        boolean pass = nonFinite == 0 && maxAbs <= 0.05;
        System.out.println("VERDICT," + (pass ? "PASS" : "FAIL")
                + ",maxAbsDY=" + String.format(Locale.ROOT, "%.6g", maxAbs)
                + ",nonFinite=" + nonFinite);
        System.exit(pass ? 0 : 2);
    }

    // ============================================================ 任意种子 N×N

    private static void grid(long seed, int size, boolean ocean) throws Exception {
        TerrainParams params = TerrainParams.defaults();
        Mira mine = new Mira(seed, params, -64, 320);
        com.geogenesis.worldgen.terrain.candidate.ParityBridge ref =
                new com.geogenesis.worldgen.terrain.candidate.ParityBridge(seed, params, -64, 320);

        int n = size * size, nonFinite = 0, diff = 0;
        double maxAbs = 0, sumAbs = 0;
        double step = 25.0;
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double x = (i - size / 2) * step + 0.25, z = (j - size / 2) * step + 0.75;
                double a = ocean ? mine.sampleE(x, z) : mine.landE(x, z);
                double b = ocean ? ref.e(x, z) : ref.landE(x, z);
                if (!Double.isFinite(a) || !Double.isFinite(b)) { nonFinite++; continue; }
                if (Double.doubleToLongBits(a) != Double.doubleToLongBits(b)) diff++;
                double d = Math.abs(a - b);
                maxAbs = Math.max(maxAbs, d);
                sumAbs += d;
            }
        }

        double[][] far = {
                {1_875_000.5, 1_875_000.5}, {65_536.5, 65_536.5}, {262_144.5, 262_144.5},
                {1_048_576.5, 1_048_576.5}, {-1_875_000.5, 1_875_000.5}, {30_000_000.5, 30_000_000.5}
        };
        int farFail = 0;
        StringBuilder farLog = new StringBuilder();
        for (double[] p : far) {
            String line;
            try {
                double a = ocean ? mine.sampleE(p[0], p[1]) : mine.landE(p[0], p[1]);
                double b = ocean ? ref.e(p[0], p[1]) : ref.landE(p[0], p[1]);
                double d = Double.isFinite(a) && Double.isFinite(b) ? Math.abs(a - b) : Double.NaN;
                boolean ok = Double.isFinite(d) && d <= 0.05;
                if (!ok) farFail++;
                line = String.format(Locale.ROOT, "  far %.1f,%.1f mine=%.9g theirs=%.9g d=%s %s%n",
                        p[0], p[1], a, b,
                        Double.isFinite(d) ? String.format(Locale.ROOT, "%.3g", d) : "NaN",
                        ok ? "ok" : "FAIL");
            } catch (RuntimeException ex) {
                farFail++;
                line = String.format(Locale.ROOT, "  far %.1f,%.1f THROWS %s%n", p[0], p[1], ex);
            }
            farLog.append(line);
        }

        System.out.println("=== " + (ocean ? "worldE" : "landE") + " seed=" + seed
                + " grid=" + size + "x" + size + " ===");
        System.out.printf(Locale.ROOT,
                "nonFinite=%d bitDifferent=%d/%d (%.2f%%) maxAbs=%.6g meanAbs=%.6g%n",
                nonFinite, diff, n, 100.0 * diff / n, maxAbs, sumAbs / n);
        System.out.print(farLog);
        boolean pass = nonFinite == 0 && farFail == 0 && maxAbs <= 0.05;
        System.out.println("VERDICT," + (pass ? "PASS" : "FAIL")
                + ",maxAbs=" + String.format(Locale.ROOT, "%.6g", maxAbs)
                + ",farFailures=" + farFail);
        System.exit(pass ? 0 : 2);
    }

    // ============================================================ 新包适配

    /** 新包 {@code MorphTerrainSampler} 的薄包装（只转发，便于两个实现并排写）。 */
    private static final class Mira {
        private final com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler s;

        Mira(long seed, TerrainParams params, int minY, int maxY) {
            this.s = new com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler(
                    seed, params, minY, maxY);
        }

        double sampleE(double x, double z) { return s.sampleE(x, z); }

        double landE(double x, double z) { return s.landE(x, z); }

        double height(double x, double z) { return s.height(x, z); }
    }

    private static String sha(double[] values) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(values.length * 8);
        for (double v : values) buf.putDouble(v);
        return java.util.HexFormat.of().formatHex(md.digest(buf.array())).toUpperCase(Locale.ROOT);
    }

    private ParityMain() { }
}
