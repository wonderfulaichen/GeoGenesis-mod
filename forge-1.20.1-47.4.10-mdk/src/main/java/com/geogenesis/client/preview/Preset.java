package com.geogenesis.client.preview;

import com.geogenesis.config.ConfigKeyCatalog;
import com.geogenesis.config.ConfigSpecRanges;
import com.geogenesis.config.GeoGenesisConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地形配置预设 = <b>一份命名键值覆盖表 + 一条可复现的应用路径</b>。
 *
 * <h2>★ 2026-11-07 重构：预设原来是"零效果"的</h2>
 * <p>旧 {@code PresetLibrary} 的 5 个内置预设，差异字段<b>全部</b>是
 * {@code continentBias / oceanDepthFactor / beltReliefAmp / elevHigh / reliefHigh / snowLine}，
 * 其中前三个是 {@code LEGACY_ONLY}、后两个是 {@code DIAGNOSTIC}（只被诊断类读，已于 2026-11-07 删除）。
 * ⇒ 用户点「高山峻岭」，新世界<b>一点都不变</b>。类注释当时也如实写了"不会产生地形差异"，
 * 但那只是一种<b>记录失败</b>，不是修复。</p>
 *
 * <h2>★ 口径：覆盖表 + 轴校验（不改"完整快照"）</h2>
 * <p>两种方案的真实取舍：</p>
 * <table border="1">
 *   <caption>覆盖表 vs 完整快照</caption>
 *   <tr><th></th><th>覆盖表（保留）</th><th>完整快照</th></tr>
 *   <tr><td>语义</td>
 *       <td>「在默认之上改这几项」</td>
 *       <td>「把这 168 项全设成这些值」</td></tr>
 *   <tr><td>新增配置键时</td>
 *       <td><b>自动继承默认值</b>（预设不腐烂）</td>
 *       <td>旧预设缺该键 ⇒ 要么落默认（等价覆盖表），要么落 0（静默毁世界）</td></tr>
 *   <tr><td>能否表达"刻意的极端组合"</td>
 *       <td>能（覆盖表本身任意）</td>
 *       <td>能</td></tr>
 *   <tr><td>文件体积 / 可读性</td>
 *       <td>小，一眼看懂"这个预设改了什么"</td>
 *       <td>168 行，看不出重点</td></tr>
 * </table>
 * <p><b>结论：保留覆盖表</b>。理由是第一条：本项目每轮都在增删配置键
 * （2026-11-07 一轮删了 46 个），完整快照会让每个内置预设<b>在每次增键时变成"缺字段"</b>，
 * 而"缺字段怎么办"只有两个答案，都很糟（落默认 = 退化成覆盖表；落 0 = 静默毁世界）。
 * 用户自定义预设（{@code UserPresetsStore}）走的本来就是<b>完整快照</b>
 * （{@code captureAllValues}），且加载时先 {@code resetToDefault()} 再逐键覆盖
 * —— 缺字段自动落默认，<b>不静默丢用户改动</b>。两条路径各取所长，互不干扰。</p>
 *
 * <h2>★ 新增的不变量：覆盖的键必须真的影响新世界</h2>
 * <p>{@link #of} 会逐键查 {@link ConfigKeyCatalog}，凡 {@code axis} 不是
 * {@code BOTH}/{@code MORPH_ONLY} 的键<b>直接抛异常</b>（不是警告）——
 * 因为"预设里放一个对新世界无效的键"正是本类要根除的缺陷，
 * 允许它存在就等于允许缺陷复发。{@code PresetEffectivenessProbe} 另有行为级证明。</p>
 */
public class Preset {

    /** 稳定 ID（用于持久化/去重）。 */
    public final String id;
    /** 展示名。 */
    public final String name;
    /** 一句话描述（卡片副标题）。 */
    public final String desc;
    /** 覆盖表：键名 → 目标值（只允许 {@code BOTH}/{@code MORPH_ONLY} 键）。 */
    public final Map<String, Double> overrides;

    private Preset(String id, String name, String desc, Map<String, Double> overrides) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }

    /**
     * 构造预设并校验每个覆盖键。
     *
     * @throws IllegalArgumentException 键不存在，或该键不影响新世界（{@code LEGACY_ONLY} /
     *                                  {@code DIAGNOSTIC} / {@code PREVIEW_ONLY}）
     */
    public static Preset of(String id, String name, String desc, Map<String, Double> overrides) {
        for (String key : overrides.keySet()) {
            ConfigKeyCatalog.Entry e = ConfigKeyCatalog.of(key);
            if (e == null) {
                throw new IllegalArgumentException(
                        "preset '" + id + "' overrides unknown key: " + key);
            }
            if (!e.affectsNewWorld()) {
                throw new IllegalArgumentException(
                        "preset '" + id + "' overrides " + key + " whose axis is " + e.axis()
                        + " — it does NOT affect a new world (morph-base-p1). "
                        + "Only BOTH/MORPH_ONLY keys are allowed in built-in presets.");
            }
            ConfigSpecRanges.Meta meta = ConfigSpecRanges.metaOf(key);
            if (meta == null || !meta.numeric()) {
                throw new IllegalArgumentException(
                        "preset '" + id + "' overrides non-numeric key: " + key);
            }
            double v = overrides.get(key);
            if (v < meta.min() || v > meta.max()) {
                throw new IllegalArgumentException(
                        "preset '" + id + "' sets " + key + "=" + v
                        + " outside its defineInRange [" + meta.min() + ", " + meta.max() + "]");
            }
        }
        return new Preset(id, name, desc, overrides);
    }

    /**
     * 应用本预设：<b>先回默认，再写覆盖表</b>。
     *
     * <p>顺序不可换：只写覆盖表会让上一次预设的残留值留在没被覆盖的键上，
     * 于是"连续点两个预设"的结果取决于点击顺序（不可复现）。</p>
     */
    public void applyTo(GeoGenesisConfig config) {
        config.resetToDefault();
        for (Map.Entry<String, Double> e : overrides.entrySet()) {
            ConfigSpecRanges.Meta meta = ConfigSpecRanges.metaOf(e.getKey());
            if (meta == null) continue;
            if (meta.kind() == ConfigSpecRanges.Kind.INT) {
                ConfigSpecRanges.setInt(e.getKey(), (int) Math.round(e.getValue()));
            } else {
                ConfigSpecRanges.setDouble(e.getKey(), e.getValue());
            }
        }
    }

    /** 「应用预设」确认框里那句话（把覆盖项的可读名列出来，让用户知道将改什么）。 */
    public String applyHint() {
        StringBuilder sb = new StringBuilder();
        for (String key : overrides.keySet()) {
            if (sb.length() > 0) sb.append('、');
            sb.append(KeyText.label(key));
        }
        return sb.length() == 0 ? "（本预设不修改任何参数）" : sb.toString();
    }
}
