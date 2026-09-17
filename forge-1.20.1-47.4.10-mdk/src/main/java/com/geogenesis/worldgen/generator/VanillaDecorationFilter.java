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
     * ★ 2026-09-18：<b>原版金属矿特征全集（16 项）</b> —— 供
     * {@link com.geogenesis.config.GeoGenesisConfig#oreOverrideVanilla} = {@code OVERRIDE} 时剔除。
     *
     * <h4>为什么必须【精确枚举】而不是"名字含 ore"</h4>
     * <p>1.20.1 每个矿种有 <b>1~3 个形态变体</b>，与"8 个矿种"是两回事。</p>
     *
     * <p>★★ <b>取证方法（务必照做）：穷举【全部 64 个群系】取并集</b>，<b>不可抽样</b>。
     * 本项目踩过两次同类坑：</p>
     * <ul>
     *   <li>只读 {@code jungle} ⇒ 漏 {@code ore_gold_extra}（<b>仅 badlands 有</b>）；</li>
     *   <li>只读 6 个<b>非山地</b>群系 ⇒ 漏 <code>ore_emerald</code> / {@code ore_infested}
     *       （<b>仅山地群系有</b>）。</li>
     * </ul>
     * <p>最终并集 <b>19 项</b>：</p>
     * <pre>
     * coal     : upper, lower                          (2)
     * iron     : upper, middle, small                  (3)
     * gold     : gold, gold_lower, gold_extra          (3)
     * redstone : redstone, redstone_lower              (2)
     * diamond  : diamond, diamond_large, buried        (3)
     * lapis    : lapis, lapis_buried                   (2)
     * copper   : copper, copper_large                  (2)
     * emerald  : emerald                               (1)  ★ 仅山地
     * infested : infested                              (1)  ★ 仅山地
     * </pre>
     * <p>⚠ <b>必须排除下界/末地同名矿</b>：并集中还有 {@code ore_gold_nether} /
     * {@code ore_quartz_nether} / {@code ore_quartz_deltas} / {@code ore_gold_deltas} /
     * {@code ore_ancient_debris_large} / {@code ore_debris_small} / {@code ore_blackstone} /
     * {@code ore_magma} / {@code ore_soul_sand} / {@code ore_gravel_nether} 等
     * —— 它们<b>不是主世界资源矿</b>，<b>不得</b>进本清单
     * （"名字含 ore/gold 就删"式匹配会全部误伤）。</p>
     * <p>另需保留（非金属）：{@code ore_clay} · {@code disk_grass} ·
     * {@code disk_sand/clay/gravel} · {@code underwater_magma}。</p>
     *
     * <h4>⚠ 刻意排除 {@code ore_infested}（虫蚀石）—— 2026-09-18 语义校正</h4>
     * <p>它在穷举并集里出现（仅山地群系），但<b>不是"地质资源矿"</b>：
     * 它是与<b>要塞/刷怪机制</b>关联的特殊方块，而非玩家采掘的资源。</p>
     * <p>而 {@code OreVeins} <b>没有对应矿种</b> ⇒ 若剔除，虫蚀石将<b>永久消失</b>
     * （无任何系统补位）= 白白丢失一个原版特性。</p>
     * <p><b>取舍：保留</b>。理由：本项目的接管目标是<b>"地质资源矿"</b>，
     * 虫蚀石不属于该范畴；保留它使改动面更小、更保守。</p>
     * <p>⇒ 本清单实际为 <b>18 项</b>（金属资源矿），非并集里的 19 项。</p>
     * <p>而"名字含 {@code ore}"的模糊匹配会误伤：{@code disk_sand/clay/gravel}、
     * {@code underwater_magma}，以及<b>模组自建的 {@code ore_*} 命名</b>
     * ⇒ 必须精确枚举（并叠加 {@link #isVanillaNamespace} 命名空间门控）。</p>
     *
     * <p>⚠ <b>本表刻意不做不可变集合</b>（不用 {@code Set.of}）：它要参与"模板集合 ⊆ 白名单"
     * 的**枚举校验**（见 {@code OreOverrideProbe}），用 {@code HashSet} 更直白。
     * 内容仍是 1.20.1 原版内容快照。</p>
     */
    private static final Set<String> METAL_ORES = new java.util.HashSet<>(java.util.Arrays.asList(
            // 煤 2
            "ore_coal_upper", "ore_coal_lower",
            // 铁 3
            "ore_iron_upper", "ore_iron_middle", "ore_iron_small",
            // 金 3（gold_extra 仅 badlands 有）
            "ore_gold", "ore_gold_lower", "ore_gold_extra",
            // 红石 2
            "ore_redstone", "ore_redstone_lower",
            // 钻石 3
            "ore_diamond", "ore_diamond_large", "ore_diamond_buried",
            // 青金石 2
            "ore_lapis", "ore_lapis_buried",
            // 铜 2（copper_large 易漏）
            "ore_copper", "ore_copper_large",
            // 绿宝石 1（★ 仅山地群系；只读非山地群系会整项漏掉）
            "ore_emerald"
            // ⚠ 刻意【不】含 ore_infested（虫蚀石）—— 见下方说明
            ));

    /**
     * 按群系缓存过滤结果。
     *
     * <p>该 getter 在热路径上会被反复调用（每个 chunk 的 {@code applyBiomeDecoration}
     * 会对该 chunk 涉及的每个群系调用一次），而群系集合是固定注册表 ⇒ 缓存安全且有效。
     * 用 {@link ConcurrentHashMap} 因为装饰在多个 worker 线程上并行执行。</p>
     */
    private static final ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings> CACHE =
            new ConcurrentHashMap<>();

    /**
     * ★ 2026-09-18：矿脉接管模式（{@code true} = 额外剔除 {@link #METAL_ORES}）。
     *
     * <p>由 {@code GeoGenesisGenerator.setWorldSeed} 从配置注入。⚠ <b>本字段参与缓存键</b>：
     * 模式变化时必须 {@link #invalidateCache()}，否则会命中按旧模式构建的缓存
     * ⇒ 表现为"改了配置没生效"（本项目已多次踩过"注释说改了、缓存没失效"的坑）。</p>
     */
    private static volatile boolean overrideVanillaOre = false;

    /**
     * 设置接管模式；<b>仅在模式真正变化时清缓存</b>（避免每次进世界都清空）。
     *
     * @return 是否发生了模式变化
     */
    public static boolean setOverrideVanillaOre(boolean override) {
        boolean changed = (overrideVanillaOre != override);
        overrideVanillaOre = override;
        if (changed) invalidateCache();
        return changed;
    }

    /** 当前是否为接管模式（供审计/探针读取）。 */
    public static boolean isOverrideVanillaOre() {
        return overrideVanillaOre;
    }

    /** 清空按群系缓存（模式变化后必须调用）。 */
    public static void invalidateCache() {
        CACHE.clear();
    }

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
     * 判定一个 placed_feature 是否应被剔除。
     *
     * <p>用 {@link Holder#unwrapKey()} 取 key —— <b>不需要注册表</b>，
     * 因为注入 getter 时（构造期）拿不到 {@code RegistryAccess}。</p>
     *
     * <p>两类剔除：① {@link #STRATA_BREAKING}（始终剔除，打散岩层）；
     * ② {@link #METAL_ORES}（仅 {@link #overrideVanillaOre} 时剔除）。</p>
     */
    private static boolean isStrataBreaking(Holder<PlacedFeature> holder) {
        return holder.unwrapKey()
                .map(ResourceKey::location)
                .map(VanillaDecorationFilter::wouldRemove)
                .orElse(false);
    }

    /**
     * ★★ <b>剔除判定的【纯函数】入口</b>（2026-09-18 新增）——
     * 把"是否剔除"从 {@code Holder} 依赖中剥离，使<b>诊断探针可在无 MC 环境下完整验证</b>
     * （对照用户要求"实机看不出变化 ⇒ 判断只能由 AI 承担"）。
     *
     * <p><b>判定真值表</b>（{@code override} = {@link #overrideVanillaOre}）：</p>
     * <table border="1">
     *   <caption>三档行为</caption>
     *   <tr><th>特征 id</th><th>{@code override=false}</th><th>{@code override=true}</th></tr>
     *   <tr><td>{@code minecraft:ore_dirt} 等岩块团块</td><td>剔除</td><td>剔除</td></tr>
     *   <tr><td>{@code minecraft:ore_coal_upper} 等金属矿</td><td><b>保留</b></td><td><b>剔除</b></td></tr>
     *   <tr><td>{@code minecraft:disk_sand} 等水成细节</td><td>保留</td><td><b>保留</b></td></tr>
     *   <tr><td>{@code 任意模组:ore_xxx}</td><td><b>保留</b></td><td><b>保留</b> ★多模组兼容</td></tr>
     * </table>
     *
     * <p>⚠ 命名空间闸门<b>最先</b>判（短路）：非 {@code minecraft} 一律保留，
     * 故即使某模组自建名为 {@code ore_coal_upper} 的特征，也<b>不会</b>被误删。</p>
     */
    public static boolean wouldRemove(ResourceLocation id) {
        if (!isVanillaNamespace(id)) return false;          // ★ 多模组闸门（最先判）
        String path = id.getPath();
        return STRATA_BREAKING.contains(path)
                || (overrideVanillaOre && METAL_ORES.contains(path));
    }

    /**
     * ★ <b>多模组兼容的关键闸门</b>：只处理 {@code minecraft} 命名空间。
     *
     * <p>原版矿的 id 必然是 {@code minecraft:ore_*}；而<b>任何模组</b>的矿无论怎么命名，
     * 命名空间都不会是 {@code minecraft} ⇒ 天然不被触碰。
     * （对照参考项目 FreeTerraForged `OreContractClassifier` 的"读不懂就不动"原则：
     * 这里用命名空间做更简单的一道等价闸门。）</p>
     *
     * <h4>★ 前提已核实（2026-09-18，Forge 源码级证据）—— 勿再重新怀疑</h4>
     * <p>本闸门成立的前提是：{@link Biome#getGenerationSettings()} 返回的必须是
     * <b>「已应用全部 biome modifier」之后的最终态</b>，否则我们会看不到模组加进来的矿，
     * 过滤就失去意义（甚至误删）。<b>已查证为真</b>：</p>
     * <pre>
     * // forge-1.20.1-47.4.10-sources.jar → net/minecraft/world/level/biome/Biome.java
     * public BiomeGenerationSettings getGenerationSettings() {
     *     return this.modifiableBiomeInfo().get().generationSettings();   // ← FORGE 改写
     * }
     * </pre>
     * <p>{@code modifiableBiomeInfo()} 即 Forge 的群系修饰结果 ⇒ 返回的是<b>最终态</b>。</p>
     * <p>（旁证：参考项目 FreeTerraForged 亦在同一层做特征路由，
     * 其 {@code DynamicOrePlanner} 同样从 {@code generator.getBiomeGenerationSettings(biome)} 读取。）</p>
     */
    private static boolean isVanillaNamespace(ResourceLocation id) {
        return "minecraft".equals(id.getNamespace());
    }

    /** 给定特征名是否在【始终剔除】清单内（供文档/审计对照）。 */
    public static boolean isFiltered(String featurePath) {
        return STRATA_BREAKING.contains(featurePath);
    }

    /** 给定特征名是否为【接管模式下】会剔除的原版金属矿（供审计/探针对照）。 */
    public static boolean isMetalOre(String featurePath) {
        return METAL_ORES.contains(featurePath);
    }

    /** 原版金属矿清单大小（= 16；供审计断言）。 */
    public static int metalOreCount() {
        return METAL_ORES.size();
    }
}
