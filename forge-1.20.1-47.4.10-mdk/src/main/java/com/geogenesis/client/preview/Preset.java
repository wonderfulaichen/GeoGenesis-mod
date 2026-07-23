package com.geogenesis.client.preview;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Map;

/**
 * 地形配置预设：一份命名的内置/用户配置快照。
 *
 * <p>预设不直接持有全部 ~120 个配置字段，而是仅声明需要覆盖的「语义字段」覆盖表
 * （{@link #overrides}）。应用预设时由调用方先 {@code resetToDefault()} 回默认、
 * 再写入本表的覆盖值，从而得到一份完整且可复现的配置（未列出的字段保持默认）。
 */
public class Preset {

    /** 稳定 ID（用于持久化/去重） */
    public final String id;
    /** 展示名 */
    public final String name;
    /** 一句话描述（展示在卡片副标题） */
    public final String desc;
    /** 需要覆盖的配置字段 → 目标值（仅语义级参数：海陆偏置/海深/地形类型范围/起伏振幅/气候阈值等） */
    public final Map<ForgeConfigSpec.DoubleValue, Double> overrides;

    public Preset(String id, String name, String desc, Map<ForgeConfigSpec.DoubleValue, Double> overrides) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.overrides = overrides;
    }
}
