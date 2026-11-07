package com.geogenesis.client.preview;

import com.geogenesis.config.ConfigKeyCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置地形配置预设库。
 *
 * <h2>★ 2026-11-07 重写：旧预设对新世界<b>零效果</b>，这是真缺陷而不是"已知限制"</h2>
 * <p>旧 5 个预设的差异字段是 {@code continentBias / oceanDepthFactor / beltReliefAmp /
 * elevHigh / reliefHigh / snowLine}：</p>
 * <ul>
 *   <li>{@code continentBias} / {@code oceanDepthFactor} / {@code beltReliefAmp} → <b>LEGACY_ONLY</b>
 *       （只在新世界<b>不</b>走的那条链上生效）；</li>
 *   <li>{@code elevHigh} / {@code reliefHigh} → <b>DIAGNOSTIC</b>
 *       （{@code src/main} 内唯一读者是一个带 main 的诊断类，生产地形链根本不读；<b>已于 2026-11-07 删除</b>）；</li>
 *   <li>{@code snowLine} → BOTH（唯一真生效的那一个）。</li>
 * </ul>
 * <p>⇒ 用户点「高山峻岭」，新世界<b>只有雪线动了一点</b>，山一点都不高。</p>
 *
 * <h2>★★ 实测结论（先说清限制，否则预设又会撒谎）</h2>
 * <p>写这版时我原以为"把 {@code mountHiVal0} 拉到 1.0 就能让山更高"。用
 * {@code runPresetEffectivenessProbe} 实测<b>证伪</b>：</p>
 * <ul>
 *   <li>morph 链的 {@code e} 由 {@code MorphLandField.e = max(GROUND_E + covered·E_SPAN, 0)}
 *       给出，<b>完全不读</b>内层样条的 {@code *HiVal0}；</li>
 *   <li>{@code *HiVal0} 只经 {@code SplineConfig.maxLandHi()} 影响侵蚀/装配的 softCap 阈值
 *       （{@code 0.97·maxLandHi}）。默认 maxLandHi=0.95 ⇒ 阈值 0.9215，
 *       而实测 morph 最高 e ≈ 0.876 ⇒ <b>默认配置下 softCap 从不触发</b>；
 *       把 {@code mountHiVal0} 抬到 1.0 只把阈值推到 0.97，仍然不触发 ⇒ <b>e 一位都不变</b>。</li>
 * </ul>
 * <p>⇒ 在 morph 链上，<b>真正能改"同一个世界画多高"的只有 {@code peakHeightFraction}</b>
 * （陆地侧 Y 的乘数，{@code HeightCurve.heightFromE} 直读）。
 * {@code verticalScale} 也在映射里，但它先乘再截顶（{@code clamp(e·VS, 0, 1)}），
 * 一旦 {@code e·VS > 1} 就把山峰削平，所以预设里只敢小幅使用。</p>
 * <p>因此本库的预设差异集中在四处，且每一处都有探针实测支撑：
 * {@code peakHeightFraction}（整体高矮）、{@code verticalScale}（小幅提神/削顶）、
 * {@code continentScale + continentFbmPersistence}（陆块大小与海岸破碎度）、
 * {@code snowLine}（雪覆盖量）。</p>
 *
 * <p>⚠ <b>不用</b> {@code horizontalScale} 做预设差异：它同时缩放侵蚀笔刷与河流雕刻，
 * 语义上是"换个尺度看同一地形"，不是风格预设。</p>
 *
 * <h2>维护</h2>
 * <p>{@link Preset#of} 在构造期逐键校验轴与范围；{@code PresetEffectivenessProbe} 另用
 * <b>真实生产采样</b>证明每个预设的<b>可见高度</b>与默认预设不同（逐点可验）。</p>
 */
public final class PresetLibrary {

    private PresetLibrary() {}

    /** 构造覆盖表：{@code kv} 为 {@code "键名", 值, "键名", 值, ...}。 */
    private static Map<String, Double> overrides(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }

    public static List<Preset> all() {
        return List.of(
            // 群岛世界：大量海洋、破碎海岸、陆地低矮
            Preset.of("islands", "群岛世界",
                "广阔海洋与破碎海岸，陆地低矮、丘陵为主，少有高山。",
                overrides(
                    "continentScale", 2400,          // 大陆特征更小 ⇒ 更多碎岛
                    "continentFbmPersistence", 0.80, // 高频细节更强 ⇒ 海岸破碎
                    "peakHeightFraction", 0.60,      // ★ 陆地侧整体压低 ⇒ 山显著变矮
                    "snowLine", 0.84)),              // 雪线偏高 ⇒ 少雪

            // 辽阔大陆：陆块更大、海岸平缓、中等高度
            Preset.of("continent", "辽阔大陆",
                "陆地广袤、平原辽阔，海岸平缓，山脉中等起伏。",
                overrides(
                    "continentScale", 6400,          // 大陆特征更大 ⇒ 连续陆块
                    "continentFbmPersistence", 0.42, // 高频细节弱 ⇒ 海岸平缓
                    "peakHeightFraction", 0.80,
                    "snowLine", 0.74)),

            // 高山峻岭：山显著更高、雪线偏低
            Preset.of("mountains", "高山峻岭",
                "连绵高山，整体高度显著抬升、雪线偏低，平原稀少。",
                overrides(
                    "peakHeightFraction", 1.0,       // ★ 吃满高度余量 ⇒ 山显著更高（主旋钮）
                    "verticalScale", 1.15,           // 再抬 15%（未越过削顶窗口即可）
                    "snowLine", 0.55)),              // 雪线压低 ⇒ 雪覆盖更多

            // 温和丘陵：整体低矮平缓
            Preset.of("hills", "温和丘陵",
                "整体低起伏、圆润丘陵为主，地形平缓宜居。",
                overrides(
                    "peakHeightFraction", 0.50,      // ★ 压到最低 ⇒ 山不再突兀
                    "snowLine", 0.90)),

            // 原始荒野：高差极大 + 海岸破碎 + 气候对比强
            Preset.of("wilderness", "原始荒野",
                "极端多样化的地形：高山深谷并存、海岸破碎、气候对比强烈。",
                overrides(
                    "continentScale", 3200,
                    "continentFbmPersistence", 0.82,
                    "peakHeightFraction", 0.98,
                    "verticalScale", 1.10,
                    "snowLine", 0.62))
        );
    }

    /**
     * 自检：每个预设的每个覆盖键都必须真的影响新世界。
     *
     * <p>由 {@code PresetEffectivenessProbe} 调用；面板构造 {@link Preset#of} 时已经强制过一遍，
     * 这里保留一个<b>可被外部调用</b>的入口，便于探针把"校验逻辑本身"也纳入门禁。</p>
     *
     * @return 问题清单，空 = 全部通过
     */
    public static List<String> auditAxes() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (Preset p : all()) {
            for (String key : p.overrides.keySet()) {
                ConfigKeyCatalog.Entry e = ConfigKeyCatalog.of(key);
                if (e == null) {
                    problems.add(p.id + ": 未知键 " + key);
                } else if (!e.affectsNewWorld()) {
                    problems.add(p.id + ": " + key + " 轴为 " + e.axis() + "（对新世界无效）");
                }
            }
        }
        return problems;
    }
}
