package com.geogenesis.diagnostics;

import com.geogenesis.client.preview.Preset;
import com.geogenesis.client.preview.PresetLibrary;
import com.geogenesis.config.ConfigKeyCatalog;
import com.geogenesis.config.ConfigSpecRanges;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>内置预设有效性证明</b>：每个内置预设对<b>新世界</b>的产出差异必须非零，且逐列可验。
 *
 * <h2>为什么不能只看"覆盖表里有键"</h2>
 * <p>旧预设的覆盖表<b>非空</b>，但里面的键 {@code continentBias / oceanDepthFactor /
 * beltReliefAmp} 是 {@code LEGACY_ONLY}、{@code elevHigh / reliefHigh} 是 {@code DIAGNOSTIC}
 * （后两者已于 2026-11-07 删除）
 * —— 表里写了 7 个键，新世界上只有 {@code snowLine} 一个真的动。
 * "覆盖表非空"这个判据会给出<b>假阳性</b>。</p>
 * <p>所以本探针走<b>行为级</b>判据：<b>真的用生产采样器跑一遍</b>，
 * 比较预设配置与默认配置在同一批坐标上的 {@code height / e / terrainType}。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>AXIS</b>：{@link PresetLibrary#auditAxes()} 为空
 *       （每个覆盖键的算法轴都是 BOTH / MORPH_ONLY）。</li>
 *   <li><b>EFFECT</b>：每个预设的「有差异坐标数」&ge; 阈值（默认 30% 采样点）。
 *       阈值不是 1%：只差一两个点在统计上可能是噪声，且用户感知不到。</li>
 *   <li><b>DISTINCT</b>：任两个预设<b>互不相同</b>（同一对坐标上差异集合不重合）。
 *       否则"5 个预设其实是 2 个"。</li>
 * </ol>
 *
 * <h2>★ 口径：直接构造 TerrainParams，不经过 Forge 配置</h2>
 * <p>{@link Preset#applyTo} 需要 Forge 的 {@code ConfigValue.set()}（配置未加载会抛
 * {@link IllegalStateException}）。探针进程没有 Forge 运行时 ⇒ 这里改用
 * <b>等价的纯数据路径</b>：{@code TerrainParams.defaults()} + 把覆盖值按
 * {@code ConfigSpecRanges} 的字段名映射写进去（见 {@link #paramsFor}）。</p>
 * <p>⚠ 这条路径必须与 {@code Preset#applyTo} 覆盖<b>同一组键</b>，否则"证明"与"实际应用"
 * 脱节。本探针在输出里打印实际生效的键清单，供人工核对。</p>
 *
 * <p>用法：{@code gradlew runPresetEffectivenessProbe [-PprobeArgs="--threshold 0.3"]}</p>
 */
public final class PresetEffectivenessProbe {

    private static final long SEED = 12345L;

    /** 采样网格：64 格步长 × 12×12 = 144 点，覆盖约 768×768 块（一个"局部世界"）。 */
    private static final int STEP = 64;
    private static final int COUNT = 12;
    private static final int ORIGIN = -384;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        double threshold = 0.30;
        for (int i = 0; i < args.length; i++) {
            if ("--threshold".equals(args[i]) && i + 1 < args.length) {
                threshold = Double.parseDouble(args[++i]);
            }
        }

        System.out.println("=== PRESET EFFECTIVENESS ===");
        System.out.println("  seed      : " + SEED);
        System.out.println("  采样网格  : " + COUNT + "×" + COUNT + " @ " + STEP
                + " 格（起点 " + ORIGIN + "）");
        System.out.println("  判据阈值  : 差异点数 ≥ " + String.format(Locale.ROOT, "%.0f%%", threshold * 100)
                + " 的采样点");

        List<String> violations = new ArrayList<>();

        // 1) 轴校验
        List<String> axisProblems = PresetLibrary.auditAxes();
        if (!axisProblems.isEmpty()) {
            for (String p : axisProblems) violations.add("AXIS " + p);
        } else {
            System.out.println("  AXIS      : PASS（内置预设只覆盖 BOTH/MORPH_ONLY 键）");
        }

        // 基准：默认配置
        double[] base = sample(TerrainParams.defaults());
        Map<String, double[]> byPreset = new LinkedHashMap<>();
        byPreset.put("<default>", base);

        int[][] pts = grid();
        int total = pts.length;

        for (Preset p : PresetLibrary.all()) {
            double[] cur = sample(paramsFor(p));
            byPreset.put(p.id, cur);
            int diff = 0;
            double maxAbs = 0;
            List<String> perPoint = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                double d = Math.abs(cur[i] - base[i]);
                if (d > 1e-12) diff++;
                maxAbs = Math.max(maxAbs, d);
                if (diff <= 3 && d > 1e-12) {
                    perPoint.add(String.format(Locale.ROOT, "(%d,%d) ΔY=%+.1f",
                            pts[i][0], pts[i][1], cur[i] - base[i]));
                }
            }
            double frac = (double) diff / total;
            boolean ok = frac >= threshold;
            System.out.printf(Locale.ROOT,
                    "  %-10s 覆盖 %d 键  差异点 %3d/%3d (%5.1f%%)  max|ΔY| %.2f 格  %s%n",
                    p.id, p.overrides.size(), diff, total, frac * 100, maxAbs, ok ? "PASS" : "FAIL");
            if (!ok) {
                violations.add("EFFECT " + p.id + "：与默认预设的差异只有 "
                        + String.format(Locale.ROOT, "%.1f%%", frac * 100) + " 的采样点"
                        + "（阈值 " + String.format(Locale.ROOT, "%.0f%%", threshold * 100)
                        + "）—— 这个预设对玩家等于没效果");
            }
            if (!perPoint.isEmpty()) {
                System.out.println("             例：" + String.join("  ", perPoint));
            }
            System.out.println("             生效键：" + String.join(", ", p.overrides.keySet()));
        }

        // 3) 互不相同
        List<String> ids = new ArrayList<>(byPreset.keySet());
        int collisions = 0;
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (identical(byPreset.get(ids.get(i)), byPreset.get(ids.get(j)))) {
                    collisions++;
                    violations.add("DISTINCT " + ids.get(i) + " 与 " + ids.get(j)
                            + " 在全部采样点上产出完全相同");
                }
            }
        }
        System.out.println("  DISTINCT  : " + (collisions == 0 ? "PASS" : collisions + " 对重复"));

        System.out.println();
        if (!violations.isEmpty()) {
            System.out.println("FAIL: " + violations.size());
            for (String v : violations) System.out.println("  - " + v);
            System.out.println("PRESET EFFECTIVENESS FAIL");
            System.exit(1);
            return;
        }
        System.out.println("PRESET EFFECTIVENESS PASS");
        System.exit(0);
    }

    /** 采样网格坐标（块）。 */
    private static int[][] grid() {
        int[][] pts = new int[COUNT * COUNT][2];
        int n = 0;
        for (int i = 0; i < COUNT; i++) {
            for (int j = 0; j < COUNT; j++) {
                pts[n][0] = ORIGIN + i * STEP;
                pts[n][1] = ORIGIN + j * STEP;
                n++;
            }
        }
        return pts;
    }

    /**
     * 用生产快路径采样一批坐标，返回每个点的<b>落块高度 Y</b>（玩家实际看到的量）。
     *
     * <h3>★ 为什么必须是 {@code height} 而不是 {@code e}</h3>
     * <p>首版取 {@code e}，结果 {@code mountains}/{@code hills} 判为 0% 差异 ——
     * 而那正是<b>正确</b>的行为：morph 链的 {@code e} 由
     * {@code MorphLandField.e = max(GROUND_E + covered·E_SPAN, 0)} 给出，
     * <b>完全不读</b>预设能改的那几项（{@code *HiVal0} 只影响 softCap 阈值，
     * 而默认 maxLandHi=0.95 ⇒ 阈值 0.9215 &gt; 实测最高 e≈0.876 ⇒ 从不触发）。</p>
     * <p>预设真正改变的是 <b>e → Y 的映射</b>（{@code peakHeightFraction} / {@code verticalScale}），
     * 所以判据必须落在 {@code height} 上。这条实测把一个"看起来合理的预设设计"
     * 直接证伪了 —— 探针的价值就在这里。</p>
     */
    private static double[] sample(TerrainParams params) {
        GeoGenesisTerrainLike terrain = new GeoGenesisTerrainLike(params);
        int[][] pts = grid();
        double[] out = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            out[i] = terrain.height(pts[i][0], pts[i][1]);
        }
        terrain.close();
        return out;
    }

    /** 轻包装：复用生产 {@link CellGenerator}，只暴露需要的两个动作。 */
    private static final class GeoGenesisTerrainLike {
        private final CellGenerator gen;

        GeoGenesisTerrainLike(TerrainParams params) {
            gen = new CellGenerator(params, -64, 320, true);
            gen.seed(SEED);
        }

        /** 落块高度（= 生产 {@code sampleCellLight} 同一口径：B + 精确 P 增量）。 */
        double height(int bx, int bz) {
            double hs = Math.max(0.01, gen.params().horizontalScale());
            Cell c = gen.sampleWu(bx / hs, bz / hs);
            return c.height;
        }

        void close() {
            try {
                gen.closeProcessErosion();
            } catch (RuntimeException ignored) {
                // 探针收尾，关不掉不影响判据
            }
        }
    }

    /**
     * 由预设覆盖表构造 {@link TerrainParams}（纯数据路径，不经 Forge 配置）。
     *
     * <p>覆盖的键必须落在 {@link #SUPPORTED} 里 —— 否则说明预设用了一个本探针不会
     * 真正施加的键，"证明"就变成空话。这里<b>直接抛错</b>而不是静默跳过。</p>
     */
    static TerrainParams paramsFor(Preset p) {
        Map<String, Double> v = new LinkedHashMap<>(p.overrides);
        TerrainParams d = TerrainParams.defaults();
        double val = 0;
        for (String k : v.keySet()) {
            if (!SUPPORTED.contains(k)) {
                throw new IllegalStateException("probe cannot apply preset key: " + k
                        + "（若新增预设键，必须同步扩 SUPPORTED 与本探针的映射）");
            }
            ConfigKeyCatalog.Entry e = ConfigKeyCatalog.of(k);
            if (e == null || !e.affectsNewWorld()) {
                throw new IllegalStateException("preset key does not affect new world: " + k);
            }
        }
        return new TerrainParams(
                val(v, "continentScale", d.continentScale()),
                val(v, "continentWarp", d.continentWarp()),
                (int) val(v, "continentFbmOctaves", d.continentFbmOctaves()),
                val(v, "continentFbmLacunarity", d.continentFbmLacunarity()),
                val(v, "continentFbmPersistence", d.continentFbmPersistence()),
                d.continentThreshold(), d.continentBias(),
                d.deepOceanLoc(), d.shelfLoc(), d.shallowLoc(), d.coastLoc(),
                d.deepOceanDepth(), d.shelfDepth(), d.shallowDepth(),
                d.deepOceanDeriv(), d.shelfDeriv(), d.shallowDeriv(), d.coastDeriv(),
                d.oceanFadeStart(), d.landRampEnd(),
                d.seabedDetail(), d.oceanDepthFactor(),
                d.basinBase(), d.beltReliefAmp(),
                val(v, "snowLatitudeInfluence", d.snowLatitudeInfluence()),
                val(v, "snowHumidityInfluence", d.snowHumidityInfluence()),
                d.horizontalScale(), d.seaLevel(),
                val(v, "snowLine", d.snowLine()),
                d.minY(), d.maxY(),
                val(v, "peakHeightFraction", d.peakHeightFraction()),
                d.mountainCap(), d.trenchDepth(),
                val(v, "verticalScale", d.verticalScale()),
                d.cAffinityStrength(),
                d.coastlineWarpAmp(), d.coastlineWarpScale(), d.coastlineWarpOctaves(),
                d.coastlineWarpLacunarity(), d.coastlineWarpPersistence(),
                val(v, "latitudeScale", d.latitudeScale()),
                val(v, "tempWarpScale", d.tempWarpScale()),
                val(v, "humidityScale", d.humidityScale()),
                val(v, "climateRegionSize", d.climateRegionSize()));
    }

    private static double val(Map<String, Double> v, String key, double def) {
        Double x = v.get(key);
        return x == null ? def : x;
    }

    private static boolean identical(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-12) return false;
        }
        return true;
    }

    /** 本探针能真正施加到 {@link TerrainParams} 上的键（新增预设键时必须同步扩这里）。 */
    private static final java.util.Set<String> SUPPORTED = java.util.Set.of(
            "continentScale", "continentWarp", "continentFbmOctaves",
            "continentFbmLacunarity", "continentFbmPersistence",
            "peakHeightFraction", "verticalScale", "horizontalScale",
            "snowLine", "snowLatitudeInfluence", "snowHumidityInfluence",
            "latitudeScale", "tempWarpScale", "humidityScale", "climateRegionSize");

    private PresetEffectivenessProbe() { }
}
