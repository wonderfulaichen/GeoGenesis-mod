package com.geogenesis.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;
import java.util.List;

/**
 * <b>从 {@link GeoGenesisConfig} 的真实定义读取每个键的类型 / 默认值 / 上下限</b>。
 *
 * <h2>为什么必须走 spec，而不是在面板里写字面量</h2>
 * <p>面板此前把范围和"默认 x"直接抄进 tooltip 字符串，于是产生 5 处真实漂移
 * （写 1.5 实际 0.0、写 -0.15 实际 -0.13、写 0.08 实际 0.05、写 5 实际 6、写 0.5 实际 0.6）。
 * 这类漂移<b>改一次默认值就会新增一处</b>，靠 review 抓不住。</p>
 * <p>本类把「默认值 + 范围」全部从 {@code defineInRange} 声明处取回，
 * 面板只负责写<b>语义说明</b>（"越大越破碎"，不带数字）。
 * ⇒ <b>数字漂移在结构上不可能再发生</b>。</p>
 *
 * <h2>★ 为什么能读到范围</h2>
 * <p>{@code ForgeConfigSpec.Builder.defineInRange} 会把 {@code Range} 存进 {@code ValueSpec}，
 * 而 {@code SPEC.getSpec().getRaw(path)} 能把它取回来（2026-11-07 在无 Forge 运行时的探针进程
 * 里实测通过：{@code 0.0 ~ 4.0}）。不需要反射私有字段。</p>
 *
 * <p>⚠ 唯一不能做的事：{@code ConfigValue.get()} 在配置未加载时抛 {@link IllegalStateException}。
 * 因此读取<b>当前值</b>一律走 {@link ConfigSafe}；读取<b>默认值/范围</b>走本类（spec 自带，不受影响）。</p>
 */
public final class ConfigSpecRanges {

    /** 数值键的类别（决定滑块写入时是否取整）。 */
    public enum Kind { DOUBLE, INT, BOOL, ENUM, OTHER }

    /** 一个键的静态元信息（与运行时值无关）。 */
    public record Meta(String key, Kind kind, double min, double max, String defaultText) {
        public boolean numeric() { return kind == Kind.DOUBLE || kind == Kind.INT; }
    }

    /** 范围读不出来时的兜底（只在 spec 未构建的极端情况下发生，不用于正常路径）。 */
    private static final double FALLBACK_MIN = 0.0;
    private static final double FALLBACK_MAX = 1.0;

    /** 字段名 → {@link ForgeConfigSpec.ConfigValue}（字段名与键名一致，见 ConfigKeyGuardProbe 的同名校验）。 */
    public static ForgeConfigSpec.ConfigValue<?> valueOf(String field) {
        try {
            Field f = GeoGenesisConfig.class.getField(field);
            Object v = f.get(GeoGenesisConfig.INSTANCE);
            return v instanceof ForgeConfigSpec.ConfigValue<?> cv ? cv : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * 读静态元信息：类别 + 真实上下限 + 默认值文本。
     *
     * @return 未知键返回 {@code null}（调用方须显式报告，不要静默造一个范围出来）
     */
    public static Meta metaOf(String field) {
        ForgeConfigSpec.ConfigValue<?> cv = valueOf(field);
        if (cv == null) return null;
        Kind kind;
        if (cv instanceof ForgeConfigSpec.DoubleValue) {
            kind = Kind.DOUBLE;
        } else if (cv instanceof ForgeConfigSpec.IntValue) {
            kind = Kind.INT;
        } else if (cv instanceof ForgeConfigSpec.BooleanValue) {
            kind = Kind.BOOL;
        } else if (cv instanceof ForgeConfigSpec.EnumValue) {
            kind = Kind.ENUM;
        } else {
            kind = Kind.OTHER;
        }
        double min = FALLBACK_MIN, max = FALLBACK_MAX;
        List<String> path = cv.getPath();
        try {
            Object raw = GeoGenesisConfig.SPEC.getSpec().getRaw(path);
            if (raw instanceof ForgeConfigSpec.ValueSpec vs && vs.getRange() != null) {
                Object lo = vs.getRange().getMin();
                Object hi = vs.getRange().getMax();
                if (lo instanceof Number n) min = n.doubleValue();
                if (hi instanceof Number n) max = n.doubleValue();
            }
        } catch (RuntimeException ignored) {
            // spec 未构建（理论上不会）→ 保留兜底，meta 仍可用来渲染
        }
        return new Meta(field, kind, min, max, defaultTextOf(cv));
    }

    /** 默认值的展示文本（枚举取 {@code name()}，其余 {@code toString()}）。 */
    public static String defaultTextOf(ForgeConfigSpec.ConfigValue<?> cv) {
        try {
            Object d = cv.getDefault();
            return d == null ? "-" : String.valueOf(d);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 拼「默认 x，范围 [a, b]」后缀 —— 面板 tooltip 的数字<b>只能</b>来自这里。 */
    public static String rangeSuffix(String field) {
        Meta m = metaOf(field);
        if (m == null) return "";
        if (!m.numeric()) {
            return "默认 " + m.defaultText();
        }
        String minS = trim(m.min());
        String maxS = trim(m.max());
        return "默认 " + m.defaultText() + "，范围 [" + minS + ", " + maxS + "]";
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return String.valueOf((long) v);
        }
        String s = String.format(java.util.Locale.ROOT, "%.4f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    // ---------------------------------------------------------------- 读写

    public static double getDouble(String field, double fallback) {
        ForgeConfigSpec.ConfigValue<?> cv = valueOf(field);
        return cv instanceof ForgeConfigSpec.DoubleValue dv ? ConfigSafe.dbl(dv, fallback) : fallback;
    }

    public static int getInt(String field, int fallback) {
        ForgeConfigSpec.ConfigValue<?> cv = valueOf(field);
        return cv instanceof ForgeConfigSpec.IntValue iv ? ConfigSafe.i32(iv, fallback) : fallback;
    }

    public static boolean getBool(String field, boolean fallback) {
        ForgeConfigSpec.ConfigValue<?> cv = valueOf(field);
        return cv instanceof ForgeConfigSpec.BooleanValue bv ? ConfigSafe.bool(bv, fallback) : fallback;
    }

    public static void setDouble(String field, double v) {
        ForgeConfigSpec.ConfigValue<?> cv = valueOf(field);
        if (cv instanceof ForgeConfigSpec.DoubleValue dv) dv.set(v);
    }

    public static void setInt(String field, int v) {
        ForgeConfigSpec.ConfigValue<?> cv = valueOf(field);
        if (cv instanceof ForgeConfigSpec.IntValue iv) iv.set(v);
    }

    public static void setBool(String field, boolean v) {
        ForgeConfigSpec.ConfigValue<?> cv = valueOf(field);
        if (cv instanceof ForgeConfigSpec.BooleanValue bv) bv.set(v);
    }

    private ConfigSpecRanges() { }
}
