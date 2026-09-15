package com.geogenesis.worldgen.generator;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原版群系装饰过滤器 —— 注入给 {@code ChunkGenerator} 的
 * {@code generationSettingsGetter}，用于剔除会破坏本地层结构的原版特征。
 *
 * <h2>为什么需要（问题来源）</h2>
 * <p>{@code GeoGenesisGenerator} 直接继承原版 {@code ChunkGenerator}，其
 * {@code applyBiomeDecoration} 委托 {@code super}（原版实现）。原版实现按
 * {@code BiomeGenerationSettings} 放置<b>全部</b>特征，其中包括
 * {@code GenerationStep.Decoration.UNDERGROUND_ORES} 里的"岩块团块"特征
 * （往地下随机塞花岗岩/闪长岩/安山岩/凝灰岩/泥土/砾石块）。</p>
 *
 * <p>而本项目的地下是 {@code StratumField} 的<b>水平岩层</b>（并经
 * {@code ROCK_BLOCKS} 映射成可见的原版方块，构成"岩性 → 地形 → 方块"的因果闭环）。
 * 原版团块的替换目标是 {@code STONE_ORE_REPLACEABLES}（含 stone/granite/andesite…），
 * 正好命中我们的岩层 ⇒ <b>精心构造的水平岩层被随机团块打散</b>。</p>
 *
 * <h3>取证（2026-09-16，实机日志 {@code [DECOR-AUDIT]}）</h3>
 * <pre>
 * biome=minecraft:jungle  steps=11  features=47  oreFeatures=24
 *   [6] UNDERGROUND_ORES = 28
 *   ore: ore_dirt ore_gravel ore_granite_upper/lower ore_diorite_upper/lower
 *        ore_andesite_upper/lower ore_tuff ore_coal… ore_iron… ore_diamond…
 * </pre>
 *
 * <h2>剔除清单（= 会打散岩层的"岩块团块"，共 9 项）</h2>
 * <table border="1">
 *   <caption>被剔除项与理由</caption>
 *   <tr><th>placed_feature</th><th>它会往岩层里注入</th><th>与本项目岩层的冲突</th></tr>
 *   <tr><td>{@code ore_granite_upper/lower}</td><td>花岗岩团块</td>
 *       <td>本项目 GRANITE 是<b>岩层层位</b>，团块破坏层界</td></tr>
 *   <tr><td>{@code ore_diorite_upper/lower}</td><td>闪长岩团块</td>
 *       <td>本项目无闪长岩岩性 ⇒ 纯外来污染</td></tr>
 *   <tr><td>{@code ore_andesite_upper/lower}</td><td>安山岩团块</td>
 *       <td>本项目 ANDESITE 是<b>岩层层位</b>，同上</td></tr>
 *   <tr><td>{@code ore_tuff}</td><td>凝灰岩团块</td>
 *       <td>⚠ 本项目<b>片岩(SCHIST) 即映射为 TUFF</b> ⇒ 团块会被误认成片岩层</td></tr>
 *   <tr><td>{@code ore_dirt}</td><td>泥土团块</td><td>地下岩层中不应有土壤</td></tr>
 *   <tr><td>{@code ore_gravel}</td><td>砾石团块</td><td>同上</td></tr>
 * </table>
 *
 * <p><b>刻意不剔除</b>：金属矿（{@code ore_coal/iron/copper/gold/redstone/lapis/emerald/diamond}）
 * 与水成细节（{@code disk_sand/gravel/clay}）保持原样 ⇒ <b>矿量与本改动前完全一致</b>，
 * 不引入平衡风险。本改动只修"岩层被团块打散"这一个确定的 bug；
 * "金属矿是否应由自研系统独占"是独立的平衡决策，另行处理。</p>
 *
 * <h2>为什么用"注入 getter"而不是重写装饰循环</h2>
 * <p>原版 {@code ChunkGenerator} 有构造器
 * {@code ChunkGenerator(BiomeSource, Function<Holder<Biome>, BiomeGenerationSettings>)}，
 * 该 getter 同时用于 ① 构建 {@code featuresPerStep}（装饰调度表）、
 * ② {@code applyBiomeDecoration} 内取特征列表。故<b>只要在注入时过滤，
 * 原版管线会自动跳过被剔除项</b> —— 无需复制原版约 100 行的装饰逻辑，
 * 也就不存在"照抄抄错"的风险（对比本项目 §2.7「凭记忆引用」的教训）。</p>
 */
public final class VanillaDecorationFilter {

    private VanillaDecorationFilter() { }

    /** 需剔除的原版 placed_feature（按其 {@code ResourceLocation} 的 path 匹配）。 */
    private static final Set<String> STRATA_BREAKING = Set.of(
            "ore_granite_upper", "ore_granite_lower",
            "ore_diorite_upper", "ore_diorite_lower",
            "ore_andesite_upper", "ore_andesite_lower",
            "ore_tuff",
            "ore_dirt",
            "ore_gravel");

    /**
     * 按群系缓存过滤结果。
     *
     * <p>该 getter 在热路径上会被反复调用（每个 chunk 的 {@code applyBiomeDecoration}
     * 会对该 chunk 涉及的每个群系调用一次），而群系集合是固定注册表 ⇒ 缓存安全且有效。
     * 用 {@link ConcurrentHashMap} 因为装饰在多个 worker 线程上并行执行。</p>
     */
    private static final ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings> CACHE =
            new ConcurrentHashMap<>();

    /** 注入给 {@code ChunkGenerator} 的 getter（带缓存）。 */
    public static BiomeGenerationSettings filter(Holder<Biome> biome) {
        return CACHE.computeIfAbsent(biome, VanillaDecorationFilter::build);
    }

    /** 构建过滤后的设置（<b>不做缓存</b>，供审计/测试对比原始值）。 */
    public static BiomeGenerationSettings build(Holder<Biome> biome) {
        BiomeGenerationSettings base = biome.value().getGenerationSettings();
        List<HolderSet<PlacedFeature>> steps = base.features();

        List<List<Holder<PlacedFeature>>> newSteps = new ArrayList<>(steps.size());
        boolean changed = false;
        for (HolderSet<PlacedFeature> step : steps) {
            List<Holder<PlacedFeature>> slot = new ArrayList<>(step.size());
            for (Holder<PlacedFeature> holder : step) {
                if (isStrataBreaking(holder)) {
                    changed = true;
                } else {
                    slot.add(holder);
                }
            }
            newSteps.add(slot);
        }
        if (!changed) {
            return base;   // 无改动 ⇒ 原样返回，避免无谓分配
        }

        StepBuilder builder = new StepBuilder();
        builder.setSteps(newSteps);
        // carvers 原样复制（本项目是自研洞穴、不消费该字段；保留以免影响其它原版逻辑）。
        for (GenerationStep.Carving stage : base.getCarvingStages()) {
            for (Holder<ConfiguredWorldCarver<?>> carver : base.getCarvers(stage)) {
                builder.addCarver(stage, carver);
            }
        }
        return builder.build();
    }

    /**
     * {@code PlainBuilder} 的子类 —— 用于<b>逐位保留</b>原版的装饰步结构。
     *
     * <p>⚠ 为什么需要子类：1.20.1 的 {@code BiomeGenerationSettings} 构造器
     * <b>不是 public</b>（实测编译报"不是公共的"），而其公开的 {@code PlainBuilder}
     * 只有 {@code addFeature} —— 无法表达"空装饰步"，会导致 features 列表长度
     * 与原版不一致（原版由数据包 codec 保留了全部 11 个步，含空步）。
     * 子类可访问 {@code PlainBuilder} 的 {@code protected features} 字段，
     * 从而原样重建含空步的完整结构。</p>
     */
    private static final class StepBuilder extends BiomeGenerationSettings.PlainBuilder {
        void setSteps(List<List<Holder<PlacedFeature>>> steps) {
            this.features.clear();
            this.features.addAll(steps);
        }
    }

    /**
     * 判定一个 placed_feature 是否属于"会打散岩层"的类别。
     *
     * <p>用 {@link Holder#unwrapKey()} 取 key —— <b>不需要注册表</b>，
     * 因为注入 getter 时（构造期）拿不到 {@code RegistryAccess}。</p>
     */
    private static boolean isStrataBreaking(Holder<PlacedFeature> holder) {
        return holder.unwrapKey()
                .map(ResourceKey::location)
                .map(ResourceLocation::getPath)
                .map(STRATA_BREAKING::contains)
                .orElse(false);
    }

    /** 给定特征名是否在剔除清单内（供文档/审计对照）。 */
    public static boolean isFiltered(String featurePath) {
        return STRATA_BREAKING.contains(featurePath);
    }
}
