package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.RockType;
import com.geogenesis.worldgen.terrain.TerrainClass;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.HashMap;
import java.util.Map;

/**
 * 零依赖群系分类（不持颜色，仅分类逻辑）。
 *
 * <p><b>v5（2026-09-10）改为 Whittaker 群区主导</b>：陆地群系由
 * <b>温度 × 降水</b> 经 {@link WhittakerType}（Whittaker 生物群区图）决定基础群系，
 * 再叠加<b>垂直带谱</b>（温度相关雪线：高山草甸 → 雪坡/石峰 → 冰峰），
 * 最后由地形形态（高原/丘陵/盆地）作变体修正；水体与海岸仍由地形特征直接决定。
 *
 * <p>取代 v4 的 Köppen 5 带 + 干湿二值：那套分类让雨林与草原只隔一个布尔阈值，
 * 会产出「丛林紧挨热带草原」这类生态上不可能的邻接；Whittaker 图保证
 * 草原 → 季雨林 → 雨林 必然依次过渡。
 *
 * <p>保留了 BiomeMapper 的 pickKey 委托模式。
 */
public final class BiomeClassifier {

    private BiomeClassifier() {}

    /**
     * 预览 BIOME 图层 / 图例使用的群系条目 —— <b>严格对应 {@link #pickKey} 可产出的真实 MC 群系</b>。
     *
     * <p>【2026-09-10】旧版是一套自造的粗粒度分类（PLAIN/HILLS/PLATEAU/PEAK…），
     * 与真实落块群系不一致，预览看到的不是实际群系。现直接绑定
     * {@code ResourceKey<Biome>}，由 {@link #classify(Cell)} 从 {@link #pickKey} 反查。
     *
     * <p>顺序决定 {@code GeoPalette} 中 T_BIOME 色表与图例索引，改动须同步。
     */
    public enum BiomeClass {
        // 海洋
        OCEAN(Biomes.OCEAN), DEEP_OCEAN(Biomes.DEEP_OCEAN),
        WARM_OCEAN(Biomes.WARM_OCEAN), LUKEWARM_OCEAN(Biomes.LUKEWARM_OCEAN),
        DEEP_LUKEWARM_OCEAN(Biomes.DEEP_LUKEWARM_OCEAN),
        COLD_OCEAN(Biomes.COLD_OCEAN), DEEP_COLD_OCEAN(Biomes.DEEP_COLD_OCEAN),
        FROZEN_OCEAN(Biomes.FROZEN_OCEAN), DEEP_FROZEN_OCEAN(Biomes.DEEP_FROZEN_OCEAN),
        // 河流 / 湖泊 / 海岸
        RIVER(Biomes.RIVER), FROZEN_RIVER(Biomes.FROZEN_RIVER), SWAMP(Biomes.SWAMP),
        BEACH(Biomes.BEACH), SNOWY_BEACH(Biomes.SNOWY_BEACH),
        // 温带
        PLAINS(Biomes.PLAINS), MEADOW(Biomes.MEADOW),
        FOREST(Biomes.FOREST), BIRCH_FOREST(Biomes.BIRCH_FOREST),
        WINDSWEPT_HILLS(Biomes.WINDSWEPT_HILLS), WINDSWEPT_FOREST(Biomes.WINDSWEPT_FOREST),
        WINDSWEPT_SAVANNA(Biomes.WINDSWEPT_SAVANNA),
        // 热带
        JUNGLE(Biomes.JUNGLE), SPARSE_JUNGLE(Biomes.SPARSE_JUNGLE),
        SAVANNA(Biomes.SAVANNA), SAVANNA_PLATEAU(Biomes.SAVANNA_PLATEAU),
        // 干旱
        DESERT(Biomes.DESERT), BADLANDS(Biomes.BADLANDS),
        // 寒温带 / 极地
        TAIGA(Biomes.TAIGA), SNOWY_TAIGA(Biomes.SNOWY_TAIGA), SNOWY_PLAINS(Biomes.SNOWY_PLAINS),
        GROVE(Biomes.GROVE), SNOWY_SLOPES(Biomes.SNOWY_SLOPES),
        // 山峰
        STONY_PEAKS(Biomes.STONY_PEAKS), JAGGED_PEAKS(Biomes.JAGGED_PEAKS),
        FROZEN_PEAKS(Biomes.FROZEN_PEAKS);

        private final ResourceKey<Biome> key;

        BiomeClass(ResourceKey<Biome> key) { this.key = key; }

        /** 对应的原版群系键 */
        public ResourceKey<Biome> key() { return key; }
    }

    /**
     * 根据 Cell 的地形类型和气候选择原版群系 ResourceKey。
     *
     * <p>v5（2026-09-10）：陆地群系先由 {@link WhittakerType}（温度×降水）取基础群系，
     * 再叠加垂直带谱与地形形态修正。水体/海岸/火山分支由地形特征直接决定（强于气候）。
     */
    public static ResourceKey<Biome> pickKey(Cell cell) {
        TerrainClass terrain = cell.terrainType;
        Climate climate = cell.climate;

        return switch (terrain) {
            case DEEP_OCEAN -> {
                if (climate.isFrozen()) yield Biomes.DEEP_FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.DEEP_COLD_OCEAN;
                if (climate.isHot()) yield Biomes.LUKEWARM_OCEAN;
                yield Biomes.DEEP_LUKEWARM_OCEAN;
            }
            case OCEAN -> {
                if (climate.isFrozen()) yield Biomes.FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.COLD_OCEAN;
                if (climate.isHot()) yield Biomes.WARM_OCEAN;
                yield Biomes.LUKEWARM_OCEAN;
            }
            case CONTINENTAL_SHELF -> {
                if (climate.isFrozen()) yield Biomes.FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.COLD_OCEAN;
                if (climate.isHot()) yield Biomes.WARM_OCEAN;
                yield Biomes.LUKEWARM_OCEAN;
            }
            case SUBMARINE_RIDGE -> {
                if (climate.isFrozen()) yield Biomes.DEEP_FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.DEEP_COLD_OCEAN;
                if (climate.isHot()) yield Biomes.LUKEWARM_OCEAN;
                yield Biomes.DEEP_LUKEWARM_OCEAN;
            }
            case SEAMOUNT -> {
                if (climate.isFrozen()) yield Biomes.DEEP_FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.DEEP_COLD_OCEAN;
                if (climate.isHot()) yield Biomes.LUKEWARM_OCEAN;
                yield Biomes.DEEP_LUKEWARM_OCEAN;
            }
            case LAKE  -> climate.isFrozen() ? Biomes.FROZEN_RIVER : Biomes.SWAMP;
            // 河流不按 terrainType 分类：由 GeoGenesisGenerator 按 riverSurfaceY/riverType 灌水表现，
            // TerrainClass.RIVER 全工程从未赋值 → 此分支为死代码，已移除（2026-09-11）。
            case BEACH -> climate.isCold()
                ? Biomes.SNOWY_BEACH : Biomes.BEACH;

            // === 陆地地形：气候主导（Köppen 气候带选基群系 + 地形形态变体） ===

            case VOLCANO, VOLCANIC_FIELD -> pickVolcanicKey(cell, climate);
            case PEAK -> Biomes.JAGGED_PEAKS; // 不应命中（2026-08-05 分类不再产出），保持 switch 穷举
            case SNOW -> Biomes.SNOWY_PLAINS;
            // 注：TerrainClass.RIVER 永不命中，落入此 default 走陆地分类；因其从不赋值，无副作用。
            default -> pickLandKey(cell, climate);
        };
    }

    /** 垂直带谱：山麓 → 亚高山 → 高山 → 峰顶 */
    public enum ElevationBand { LOWLAND, SUBALPINE, ALPINE, PEAK }

    /**
     * 按有效雪线（{@link Cell#snowLineE}）与<b>侵蚀前</b>高度（{@link Cell#eClimate}）判定垂直带。
     * 群系选择与诊断探针共用此判定，保证只有一套阈值。
     */
    public static ElevationBand bandOf(Cell cell) {
        double sl = cell.snowLineE > 0.0 ? cell.snowLineE : 0.70;
        if (cell.eClimate > sl) return ElevationBand.PEAK;
        if (cell.eClimate > sl - 0.10) return ElevationBand.ALPINE;
        if (cell.eClimate > sl - 0.25) return ElevationBand.SUBALPINE;
        return ElevationBand.LOWLAND;
    }

    /** 地表方块类型（零依赖，供落块层选择顶块/填充块） */
    public enum SurfaceType { GRASS, SAND, GRAVEL, STONE, PODZOL }

    /**
     * 由<b>群系</b>决定地表方块。
     *
     * <p>【2026-09-10 修复】落块层此前只按 {@code terrainType} 选顶块（BEACH→沙、其余→草），
     * <b>完全不看群系</b> → 沙漠群系也铺草方块（用户实测）。现改为群系驱动：
     * 沙漠/恶地→沙、山峰→石、针叶林→灰化土、风袭丘陵→砾石，其余→草。
     */
    public static SurfaceType surfaceOf(Cell cell) {
        if (cell.terrainType == TerrainClass.BEACH) return SurfaceType.SAND;
        ResourceKey<Biome> k = pickKey(cell);
        if (k.equals(Biomes.DESERT) || k.equals(Biomes.BADLANDS)
                || k.equals(Biomes.SNOWY_BEACH)) {
            return SurfaceType.SAND;
        }
        if (k.equals(Biomes.STONY_PEAKS) || k.equals(Biomes.JAGGED_PEAKS)
                || k.equals(Biomes.FROZEN_PEAKS) || k.equals(Biomes.SNOWY_SLOPES)) {
            return SurfaceType.STONE;
        }
        if (k.equals(Biomes.TAIGA) || k.equals(Biomes.SNOWY_TAIGA) || k.equals(Biomes.GROVE)) {
            return SurfaceType.PODZOL;
        }
        if (k.equals(Biomes.WINDSWEPT_HILLS)) return SurfaceType.GRAVEL;
        return SurfaceType.GRASS;
    }

    /**
     * 陆地群系：Whittaker 群区 → 基础群系，再叠加<b>垂直带谱</b>（海拔）与地形形态变体。
     *
     * <p>带谱由<b>温度相关的雪线</b>驱动（热带 ≈0.77、寒带 ≈0.45）：
     * 同一座山在赤道是「雨林 → 草甸 → 石峰」，在寒带是「针叶林 → 雪坡 → 冰峰」。
     * 这是群系真正"看地形"的关键。
     */
    private static ResourceKey<Biome> pickLandKey(Cell cell, Climate climate) {
        WhittakerType wt = cell.biomeType;   // 区域层群区（区内恒定）
        // 雪线与垂直带谱统一取自 Cell（CellGenerator 按配置算），高度用侵蚀前 eClimate：
        // 否则预览（含侵蚀）与实机（不含侵蚀）不一致，且垂直带边界会沿 48wu 侵蚀 tile 缝成直线。
        return switch (bandOf(cell)) {
            case PEAK      -> cell.isSnow ? Biomes.FROZEN_PEAKS : Biomes.JAGGED_PEAKS;
            case ALPINE    -> wt.isCold() ? Biomes.SNOWY_SLOPES : Biomes.STONY_PEAKS;
            case SUBALPINE -> wt.isCold() ? Biomes.GROVE : Biomes.MEADOW;
            // 变体用「抖动后的地形类型」：地形类型边界是 Voronoi 直线段，
            // 直接用 terrainType 会让群系边界沿直线走（用户实测的长直线）。
            //
            // ★ 2026-09-14 Phase T12：管道末端追加【成土母质变体】（地质 → 群系）。
            //   管道顺序 = 基础气候群系 → 河流绿洲 → 地形形态变体 → 岩性变体
            //   （岩性放最后：它是"在既定气候与形态之上的母质修正"，语义上最外层）。
            case LOWLAND   -> soilVariant(cell,
                landVariant(riverOasis(cell, lowlandBiome(wt)),
                    cell.variantTerrain != null ? cell.variantTerrain : cell.terrainType));
        };
    }

    /** 河流绿洲走廊半宽（wu）：超出此距离的干旱地不受河流补水影响。 */
    private static final double OASIS_REACH = 24.0;

    /**
     * 河流绿洲（RTG {@code SurfaceRiverOasis} 范式）：干旱群区里高流量河段沿岸长出草地
     * —— 尼罗河 / 尼日尔河式的"沙漠绿线"。
     *
     * <p>三个关键细节，缺一就退化成不自然的连续绿带：
     * <ol>
     *   <li><b>只在走廊内</b>：{@link Cell#riverDistance} 超出 {@link #OASIS_REACH} 即失效；</li>
     *   <li><b>大尺度噪声门控</b>（{@link Cell#oasisNoise}）：绿洲成断续斑块，不是整条河都绿；</li>
     *   <li><b>高度截断</b>：海平面以上越高越难成绿洲（高处留不住水）。</li>
     * </ol>
     *
     * <p>结果只可能是 Whittaker 图上 DESERT 的合法邻居 SAVANNA，不破坏邻接合法性。
     *
     * <p>★ {@code riverDistance} 由两条路径（完整管线 / 群系快速路径）用同一条件填充，
     * 否则本规则只在预览生效 —— 见 {@code GeoGenesisTerrain.fillRiverDistance}。
     */
    private static ResourceKey<Biome> riverOasis(Cell cell, ResourceKey<Biome> base) {
        if (!base.equals(Biomes.DESERT) || !(cell.riverDistance < OASIS_REACH)) return base;
        double near = 1.0 - cell.riverDistance / OASIS_REACH;      // 河心 1 → 走廊外缘 0
        double alt = Math.max(0.0, cell.eClimate);                 // 海平面以上高度（e 单位）
        double gate = near + cell.oasisNoise * 0.35 - alt * 0.6;   // 斑块门控 + 高处截断
        return gate > 0.55 ? Biomes.SAVANNA : base;
    }

    /** Whittaker 群区 → 低地带基础群系 */
    private static ResourceKey<Biome> lowlandBiome(WhittakerType wt) {
        return switch (wt) {
            case ICE, TUNDRA          -> Biomes.SNOWY_PLAINS;
            case TAIGA                -> Biomes.TAIGA;
            case GRASSLAND            -> Biomes.PLAINS;
            case TEMPERATE_FOREST     -> Biomes.FOREST;
            case TEMPERATE_RAINFOREST -> Biomes.BIRCH_FOREST;
            case DESERT               -> Biomes.DESERT;
            case SAVANNA              -> Biomes.SAVANNA;
            case SEASONAL_FOREST      -> Biomes.SPARSE_JUNGLE;   // 季雨林：雨林↔草原之间的过渡带
            case TROPICAL_RAINFOREST  -> Biomes.JUNGLE;
        };
    }

    /**
     * 地形形态对基础群系的变体修正（高原 / 丘陵 / 盆地）。
     *
     * <p><b>铁律</b>：变体只能映射到「气候上等价或更接近基础群系」的群系，
     * 不得跨气候跳变。曾有一条 {@code SPARSE_JUNGLE → JUNGLE}（高原季雨林"升级"成雨林），
     * 使雨林与相邻的稀树草原直接贴边（用户实测「热带草原旁边就是丛林」），已删除。
     */
    private static ResourceKey<Biome> landVariant(ResourceKey<Biome> base, TerrainClass terrain) {
        return switch (terrain) {
            case PLATEAU -> {
                if (base.equals(Biomes.SAVANNA)) yield Biomes.SAVANNA_PLATEAU; // 东非高原
                if (base.equals(Biomes.DESERT))  yield Biomes.BADLANDS;         // 恶地台地（科罗拉多高原）
                if (base.equals(Biomes.FOREST))  yield Biomes.BIRCH_FOREST;     // 云贵高原 / 阿巴拉契亚
                if (base.equals(Biomes.PLAINS))  yield Biomes.MEADOW;           // 高山草甸（青藏高原）
                yield base;
            }
            case HILLS -> {
                if (base.equals(Biomes.SAVANNA)) yield Biomes.WINDSWEPT_SAVANNA;
                if (base.equals(Biomes.PLAINS))  yield Biomes.WINDSWEPT_HILLS;
                yield base;
            }
            // 盆地只对干旱区做"沙漠"强调，其余保留气候基础群系
            // （曾无条件 yield MEADOW → 雨林/草原旁边凭空出现草甸）
            case BASIN -> base.equals(Biomes.DESERT) ? Biomes.DESERT : base;
            default -> base;
        };
    }

    /**
     * ★ 2026-09-14 Phase T12：<b>成土母质对群系的变体修正</b>（岩性 → 群系耦合）。
     *
     * <h3>地学依据（真实的岩性 → 土壤 → 植被链）</h3>
     * <p>岩性经风化形成土壤，土壤的<b>化学性质与养分供给</b>决定植被类型：</p>
     * <ul>
     *   <li><b>钙质土</b>（石灰岩）与<b>火山土</b>（玄武岩/安山岩）：肥沃、排水好
     *       ⇒ 高生产力草本/疏林。真实对应：欧洲钙质草地、爪哇火山土农业带。</li>
     *   <li><b>酸性土</b>（花岗岩/片麻岩/片岩）：硅铝质难风化、养分贫
     *       ⇒ 抑制多数阔叶树，利于<b>先锋树种</b>（桦木是贫瘠酸性土的经典先锋）。</li>
     *   <li><b>中性</b>（砂岩粗粒透水、页岩黏重）：无明确单向倾向 ⇒ 不变（保持基础群系）。</li>
     * </ul>
     *
     * <h3>★★★ 铁律：变体不得引入【新的】气候邻接对 ★★★</h3>
     * <p>本项目已有血的教训：曾有一条 {@code SPARSE_JUNGLE → JUNGLE}（高原季雨林"升级"），
     * 使雨林与稀树草原直接贴边 → 用户实测「热带草原旁边就是丛林」。
     *
     * <p>故本方法<b>只使用 {@link #landVariant} 已经确立的合法映射</b>，不新增任何邻接对：</p>
     * <table border="1">
     *   <caption>岩性 → 群系映射表（全部复用既有合法对）</caption>
     *   <tr><th>成土性质</th><th>基础群系</th><th>目标群系</th><th>来源</th></tr>
     *   <tr><td>CALCAREOUS（石灰岩/火山岩）</td><td>PLAINS</td><td>MEADOW</td>
     *       <td>= {@code landVariant} 的 "PLAINS→MEADOW（高原草甸）"</td></tr>
     *   <tr><td>ACIDIC（花岗岩/片麻岩/片岩）</td><td>FOREST</td><td>BIRCH_FOREST</td>
     *       <td>= {@code landVariant} 的 "FOREST→BIRCH_FOREST"</td></tr>
     * </table>
     *
     * <p>两对均为<b>气候等价</b>（同一 Whittaker 群区内的植被变体）⇒
     * 结构上不可能产生生态上不可能的相邻。这也是本方法只有两条规则、
     * 而非把 8 种岩性 × 10 种群区全铺满的原因。</p>
     *
     * <h3>为何用 PLATEAU 语义的 MEADOW / BIRCH_FOREST 表达岩性</h3>
     * <p>原版 MC 群系集里没有"钙质草地"专属群系，只能复用<b>最接近的现成群系</b>。
     * MEADOW（草甸）与 BIRCH_FOREST（桦木林）在观感上恰好对应
     * "开敞草花 + 稀疏桦树"——正是钙质土与酸性土的典型植被。
     * 宁可复用也不新增自定义群系（后者会大幅扩大改动面并需注册资源）。</p>
     *
     * @param cell 已含 {@code rockTypeId} 与 {@code oasisNoise}（{@code sampleCore} 中填充）
     * @param base 已过"绿洲 + 地形形态"修正的群系
     * @return 施加母质修正后的群系（未触发 / 无映射时原样返回）
     */
    private static ResourceKey<Biome> soilVariant(Cell cell, ResourceKey<Biome> base) {
        int id = cell.rockTypeId;
        if (id < 0 || id >= RockType.values().length) return base;   // 越界（STRATA 未启用/退化）→ 不改
        SoilInfluence.SoilCharacter ch = SoilInfluence.of(RockType.values()[id]);
        // 噪声门控：使切换成"有机斑块"，且强度 0 时永不触发（可回滚）
        if (!SoilInfluence.triggers(ch, cell.oasisNoise, SoilInfluence.STRENGTH)) return base;
        return switch (ch) {
            // 钙质/肥沃 → 开敞草甸（PLAINS → MEADOW，既有合法对）
            case CALCAREOUS -> base.equals(Biomes.PLAINS) ? Biomes.MEADOW : base;
            // 酸性/贫瘠 → 先锋桦木林（FOREST → BIRCH_FOREST，既有合法对）
            case ACIDIC     -> base.equals(Biomes.FOREST) ? Biomes.BIRCH_FOREST : base;
            case NEUTRAL    -> base;
        };
    }

    /** 火山 / 火山群：岩石地貌优先，Whittaker 群区决定植被归属 */
    private static ResourceKey<Biome> pickVolcanicKey(Cell cell, Climate climate) {
        WhittakerType wt = cell.biomeType;
        double sl = cell.snowLineE > 0.0 ? cell.snowLineE : 0.70;
        if (cell.terrainType == TerrainClass.VOLCANO) {
            if (cell.eClimate > sl) return cell.isSnow ? Biomes.FROZEN_PEAKS : Biomes.JAGGED_PEAKS;
            if (cell.isSnow) return Biomes.GROVE;
            if (wt == WhittakerType.TROPICAL_RAINFOREST || wt == WhittakerType.SEASONAL_FOREST) {
                return Biomes.JUNGLE;
            }
            if (wt.isCold()) return Biomes.TAIGA;
            return Biomes.JAGGED_PEAKS;
        }
        if (cell.isSnow) return Biomes.WINDSWEPT_HILLS;
        return switch (wt) {
            case ICE, TUNDRA, TAIGA -> Biomes.WINDSWEPT_HILLS;
            case GRASSLAND, SAVANNA, DESERT -> Biomes.WINDSWEPT_SAVANNA;
            case TEMPERATE_FOREST, TEMPERATE_RAINFOREST -> Biomes.WINDSWEPT_FOREST;
            case SEASONAL_FOREST, TROPICAL_RAINFOREST -> Biomes.JUNGLE;
        };
    }

    /** 将 ResourceKey 转为 ResourceLocation（用于 BiomeResolver） */
    public static ResourceLocation location(Cell cell) {
        return pickKey(cell).location();
    }


    /**
     * Cell → 真实 MC 群系条目（预览 BIOME 图层 / 图例用）。
     * 直接由 {@link #pickKey} 反查，保证预览所见即实际落块群系。
     */
    public static BiomeClass classify(Cell cell) {
        return KeyIndex.MAP.getOrDefault(pickKey(cell), BiomeClass.PLAINS);
    }

    /**
     * 群系键反查索引。用嵌套类做<b>懒加载</b>：避免一加载 BiomeClassifier 就触发
     * {@code Biomes} 的静态初始化（它需要 Forge 的 Bootstrap，无引导的进程里会崩）。
     */
    private static final class KeyIndex {
        static final Map<ResourceKey<Biome>, BiomeClass> MAP = build();

        private static Map<ResourceKey<Biome>, BiomeClass> build() {
            Map<ResourceKey<Biome>, BiomeClass> m = new HashMap<>();
            for (BiomeClass b : BiomeClass.values()) m.put(b.key(), b);
            return m;
        }
    }
}
