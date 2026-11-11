package com.geogenesis.morph.parity;

import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.Locale;

/**
 * <b>范围守卫测试</b>：确认每个"坐标 → int 索引"的入口在越界时<b>抛错</b>而不是静默饱和，
 * 且正常范围内的产出与加守卫前逐位相同。
 *
 * <h3>为什么需要独立于对拍</h3>
 * <p>对拍只覆盖"正常坐标"。裸 {@code (int) Math.floor} 在越界时按 Java 语义<b>饱和</b>
 * 到 {@code Integer.MAX_VALUE} 而不抛错 —— 产出依然有限、平滑，图上看只是"远处重复"。
 * 这类缺陷对拍抓不到，必须显式喂越界值。</p>
 *
 * <h3>断言</h3>
 * <ol>
 *   <li>{@code MorphNoiseEval} 的 Perlin 路径在 {@code u} 超出 int 格点时抛
 *       {@link IllegalArgumentException}（不是返回一个数）；</li>
 *   <li>{@code MorphGeometryTransform.sampleValueAndGradient} 同样抛；</li>
 *   <li>{@code MorphLandField.tileIndex} 在 {@code |v| > 1024·Integer.MAX_VALUE} 时抛；</li>
 *   <li>正常坐标（含远场 3×10⁷）<b>不</b>抛，且与对拍基准一致（由 build.ps1 的其他门禁覆盖）。</li>
 * </ol>
 */
public final class GuardCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);

        // ---- 1. MorphNoiseEval: Perlin through the public sampler -------------------------
        // sampleE at a coordinate whose (x * frequency) lands outside int lattice range.
        // continentScale default 4000 => freq 2.5e-4; need |u| > 2.1e9 => |x| > 8.6e12.
        TerrainParams params = TerrainParams.defaults();
        com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler s =
                new com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler(12345L, params, -64, 320);
        expectThrows("sampler.sampleE beyond int lattice", () -> s.sampleE(1.0e13, 0.0));

        // ---- 2. Geometry warp primitive: direct lattice guard ------------------------------
        expectThrows("MorphGeometryTransform.sampleValueAndGradient beyond int lattice",
                () -> reflectGradient(1.0e13));
        expectThrows("MorphGeometryTransform.sampleValueAndGradient NaN",
                () -> reflectGradient(Double.NaN));

        // ---- 3. tileIndex guard ------------------------------------------------------------
        // TILE_BLOCKS = 1024; overflow needs |v| > 1024 * 2.1e9 ~ 2.2e12
        expectThrows("MorphLandField.tileIndex beyond int range",
                () -> reflectTileIndex(1.0e16));
        expectThrows("MorphLandField.tileIndex NaN",
                () -> reflectTileIndex(Double.NaN));

        // ---- 4. normal coordinates must NOT throw ------------------------------------------
        expectOk("sampler.sampleE normal", () -> s.sampleE(1234.5, -987.25));
        expectOk("sampler.sampleE far field 3e7", () -> s.sampleE(3.0e7, 3.0e7));
        expectOk("geometry gradient normal", () -> reflectGradient(1234.5));
        expectOk("tileIndex normal", () -> reflectTileIndex(1234.5));
        expectOk("tileIndex far field 3e7", () -> reflectTileIndex(3.0e7));

        System.out.println("VERDICT," + (failures == 0 ? "PASS" : "FAIL")
                + ",failures=" + failures);
        System.exit(failures == 0 ? 0 : 2);
    }

    // ============================================================ 反射入口（访问包私有成员）

    /** {@code MorphGeometryTransform.sampleValueAndGradient(seed, u, v)}，u = v = 给定值。 */
    private static Object reflectGradient(double u) throws Exception {
        Class<?> c = Class.forName("com.geogenesis.worldgen.terrain.morph.MorphGeometryTransform");
        java.lang.reflect.Method m = c.getDeclaredMethod("sampleValueAndGradient",
                int.class, double.class, double.class);
        m.setAccessible(true);
        try {
            return m.invoke(null, 4242, u, u);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw rethrow(e.getCause());
        }
    }

    /** {@code MorphLandField.tileIndex(double)}。 */
    private static Object reflectTileIndex(double v) throws Exception {
        Class<?> c = Class.forName("com.geogenesis.worldgen.terrain.morph.MorphLandField");
        java.lang.reflect.Method m = c.getDeclaredMethod("tileIndex", double.class);
        m.setAccessible(true);
        try {
            return m.invoke(null, v);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw rethrow(e.getCause());
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new IllegalStateException(t);
    }

    // ============================================================ 断言

    @FunctionalInterface
    private interface Body { Object run() throws Exception; }

    private static void expectThrows(String label, Body body) {
        try {
            Object v = body.run();
            failures++;
            System.out.println("FAIL " + label + " returned " + v + " (expected a throw)");
        } catch (IllegalArgumentException e) {
            System.out.println("ok   " + label + " -> IllegalArgumentException: " + e.getMessage());
        } catch (Exception e) {
            failures++;
            System.out.println("FAIL " + label + " threw " + e.getClass().getName()
                    + " (expected IllegalArgumentException): " + e.getMessage());
        }
    }

    private static void expectOk(String label, Body body) {
        try {
            Object v = body.run();
            if (v instanceof Double d && !Double.isFinite(d)) {
                failures++;
                System.out.println("FAIL " + label + " returned non-finite " + v);
            } else {
                System.out.println("ok   " + label + " -> " + v);
            }
        } catch (Exception e) {
            failures++;
            System.out.println("FAIL " + label + " threw " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private GuardCheck() { }
}
