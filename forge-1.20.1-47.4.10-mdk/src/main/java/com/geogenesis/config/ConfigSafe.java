package com.geogenesis.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Forge 配置值安全读取门面。
 *
 * <p>背景：{@code ForgeConfigSpec.ConfigValue.get()} 在配置<b>尚未加载</b>时会抛
 * {@link IllegalStateException}（"Cannot get config value before config is loaded"）。
 * 游戏内配置已加载不受影响，但<b>独立预览（Swing TerrainPreview）、src/diagnostics 探针
 * 等无 Forge 生命周期的进程</b>里 {@code GeoGenesisConfig.INSTANCE} 非 null 却读不出值 → 崩溃。
 *
 * <p>本项目此前在 {@code CellGenerator} 内私有实现过该兜底；此处抽为公共门面，
 * 供 {@code Climate} / {@code ClimateZone} 等零依赖气候模块复用，避免各写一份。
 *
 * <p>语义：仅在配置不可用时返回 fallback，游戏内行为完全不变。
 */
public final class ConfigSafe {

    private ConfigSafe() {}

    /** 安全读取 double 配置（未加载/未注册 → fallback） */
    public static double dbl(ForgeConfigSpec.DoubleValue v, double fallback) {
        if (v == null) return fallback;
        try {
            return v.get();
        } catch (IllegalStateException ex) {
            return fallback;
        }
    }

    /** 安全读取 boolean 配置（未加载/未注册 → fallback） */
    public static boolean bool(ForgeConfigSpec.BooleanValue v, boolean fallback) {
        if (v == null) return fallback;
        try {
            return v.get();
        } catch (IllegalStateException ex) {
            return fallback;
        }
    }

    /** 安全读取 int 配置（未加载/未注册 → fallback） */
    public static int i32(ForgeConfigSpec.IntValue v, int fallback) {
        if (v == null) return fallback;
        try {
            return v.get();
        } catch (IllegalStateException ex) {
            return fallback;
        }
    }
}
