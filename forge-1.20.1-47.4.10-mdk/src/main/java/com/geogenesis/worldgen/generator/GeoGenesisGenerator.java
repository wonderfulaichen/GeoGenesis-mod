package com.geogenesis.worldgen.generator;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.hydrology.HydrologyChunkResult;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.StratumField;
import com.geogenesis.worldgen.terrain.TerrainClass;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * GeoGenesis 地形生成器（Forge 1.20.1 ChunkGenerator）。
 *
 * 大地形管线：GeoGenesisTerrain（缓存 Cell 网格）→ fillFromNoise（逐格填方块）。
 * CODEC 仅序列化 biome_source；地形参数走按存档级 {@code GeoGenesisWorldData}（不进 level.dat 生成器 Codec），
 * 运行时由 resolveParams() 解析（存档优先，缺失回退全局 toml 默认模板）。
 */
public class GeoGenesisGenerator extends ChunkGenerator {

    public static final String CODEC_ID = "geogenesis:generator";
    private static final Logger LOGGER = LogManager.getLogger(CODEC_ID);

    // CODEC: RecordCodecBuilder with biome_source only (settings come from global config)
    public static final Codec<GeoGenesisGenerator> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
        ).apply(instance, GeoGenesisGenerator::new)
    );

    // 硬编码世界高度（可后续改为配置驱动）
    static final int WORLD_MIN_Y = -64;
    static final int WORLD_MAX_Y = 320;
    static final int SEA_LEVEL = 63;
    /**
     * 陡坡裸岩的坡度阈值（无量纲 tan 坡角，按 2wu≈4 块中心差分测得）：>0.40 地表出露岩石。
     *
     * <p>RTF 范式：{@code Steepness} tile filter 算 gradient，{@code ErodeFeature} 用
     * 0.3 起、0.65+ 为重岩分档。此处取单档 0.40 —— 介于 RTF 起始 0.3 与保守值之间：
     * 实测（384 wu、含海陆）>0.30 覆盖 6.3%、>0.45 覆盖 2.7%，0.40 落在真实陡坡上，
     * 平缓丘陵不会误判成裸岩。
     *
     * <p>注意：山地群系本身已由 {@code surfaceOf} 返回 STONE，本阈值主要让
     * <b>森林/草原里的陡崖与台地边缘</b>也出露岩石。
     */
    static final float ROCK_GRADIENT = 0.40f;

    // 方块状态缓存
    private static final BlockState AIR       = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE     = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState WATER     = Blocks.WATER.defaultBlockState();
    // 表层流动水观感（方案 B）：WATER 的 LEVEL=1 即流动态（本版无 Blocks.FLOWING_WATER 映射）
    private static final BlockState FLOWING_WATER = Blocks.WATER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL, 1);
    private static final BlockState BEDROCK   = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState DIRT      = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS     = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND      = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL    = Blocks.GRAVEL.defaultBlockState();
    /** 雪层（1/8 层，原版 Blocks.SNOW）—— 雪线以上地表覆盖 */
    private static final BlockState SNOW      = Blocks.SNOW.defaultBlockState();
    private static final BlockState PODZOL    = Blocks.PODZOL.defaultBlockState();

    // ===== ★ 2026-09-14 Phase T11：碎石坡（scree）与恶地色带（参考 RTF ErodeFeature）=====
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState ANDESITE    = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState TUFF_BLK    = Blocks.TUFF.defaultBlockState();
    /** 恶地色带（MC 恶地群系即以此表现地层）：由深到浅的暖色陶瓦序列。 */
    private static final BlockState ORANGE_TERRACOTTA = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
    private static final BlockState RED_TERRACOTTA    = Blocks.RED_TERRACOTTA.defaultBlockState();
    private static final BlockState BROWN_TERRACOTTA  = Blocks.BROWN_TERRACOTTA.defaultBlockState();
    private static final BlockState YELLOW_TERRACOTTA = Blocks.YELLOW_TERRACOTTA.defaultBlockState();

    /**
     * 碎石坡（scree / talus）的坡度下限。
     *
     * <p>参考 RTF {@code ErodeFeature} 的三档坡度：{@code rockSteepness / screeSteepness / dirtSteepness}。
     * 本项目原只有一档（{@link #ROCK_GRADIENT}=0.40 ⇒ 直接裸岩），
     * 中间坡度（0.25~0.40）仍是草/土 ⇒ 山地"草→裸岩"突变，不自然。</p>
     *
     * <p>取 0.25：实测 {@code gradient>0.30} 覆盖 6.3%、{@code >0.45} 覆盖 2.7%，
     * 0.25 落在"丘陵向山地过渡"的坡段 —— 真实山区此段正是<b>岩屑坡（talus）</b>发育处
     * （基岩风化碎屑堆积、植被稀疏）✓</p>
     */
    private static final float SCREE_GRADIENT = 0.30f;

    /**
     * 坡度判定的<b>随机抖动</b>幅度（RTF {@code slopeModifier} 同款思路）。
     *
     * <p>若直接按 {@code gradient > 阈值} 判定，边界会沿"等坡度线"形成
     * <b>绝对光滑的曲线</b>（本项目反复强调的"等值线成线"问题）。
     * 加逐格确定性抖动 ⇒ 边界呈<b>有机锯齿/渐变斑块</b>，与噪声地形自然融合。</p>
     */
    private static final float GRADIENT_JITTER = 0.06f;

    /**
     * ★ 2026-09-14：碎石坡的<b>汇流下限</b>（液滴汇聚计数，已按 {@code erosionDropsMul} 归一化）。
     *
     * <h3>为何需要（用户反馈："碎石堆好像都在山脊上，正常不应该在山谷、山沟壑处吗？"）</h3>
     * <p>该观察在物理上成立：真实 talus（岩屑坡）是重力碎屑在<b>凹坡坡脚</b>堆积；
     * 脊线是凸地形，碎屑会滚落。</p>
     *
     * <h3>根因</h3>
     * <p>原判据只有坡度幅值 {@code √(dhx²+dhz²)}（{@link CellGenerator} 的中心差分），
     * 该量<b>恒为正</b> ⇒ 山脊坡面与沟谷侧壁数值相同 ⇒ 一起被判为碎石坡。
     * <b>判据缺"凹凸"维度</b>。</p>
     *
     * <h3>为何用 discharge 作"凹度"代理</h3>
     * <p>{@link com.geogenesis.worldgen.terrain.Cell#riverNetDischarge} = 液滴路径汇聚累积，
     * 即<b>汇流累积</b> —— 沟壑/汇水区高、脊线低，正是所需的正交判据。</p>
     * <ul>
     *   <li><b>物理正确</b>：收敛度直接反映凹凸，无需二阶差分。</li>
     *   <li><b>零额外采样</b>：字段已存在。</li>
     *   <li><b>不产生路径分歧</b>：discharge 与 gradient <b>同源</b>（都只在完整管线
     *       {@code applyTileDelta} 中填充）⇒ "轻量路径不判碎石"是一致行为，
     *       不会引入"预览≠游戏"（本项目已为此踩坑多次）。</li>
     * </ul>
     *
     * <h3>取值依据（{@code ScreePlacementProbe}，seed=12345，14×14 chunks）</h3>
     * <pre>
     *   陡坡像素 7747（占陆地 23.3%）：脊 811(10.5%) / 沟 679(8.8%) / 平坦 6257
     *   discharge 中位数：脊 1.7  vs  沟 4.1（2.4×）
     *   AUC(discharge 区分"沟&gt;脊") = 0.845   ← 强判别力
     *   门控 D=2.31 ⇒ 脊保留 24.9%、沟保留 77.9%   ← 目标效果
     * </pre>
     *
     * <p>⚠ <b>必须按 {@code erosionDropsMul} 缩放</b>：discharge 是液滴计数，
     * 总量随配置的液滴倍率线性变化（{@code ErosionEngine.DROPS_*} × dropsMul）。
     * 若用绝对常数，改配置后门控会整体偏移（倍率 2× 时门控形同失效）。</p>
     */
    private static final double SCREE_DISCHARGE_MIN = 2.3;

    /**
     * 读取当前世界的侵蚀液滴倍率（{@code erosionDropsMul}），用于归一化
     * {@link #SCREE_DISCHARGE_MIN}。
     *
     * <p>与 {@code ErosionEngine} 读同一配置项 ⇒ 两边口径一致。
     * 配置不可用时回退 1.0（与 {@code ErosionEngine} 的默认一致）。</p>
     */
    private static double erosionDropsMul() {
        try {
            GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
            if (cfg != null && cfg.erosionDropsMul != null) {
                double v = cfg.erosionDropsMul.get();
                return v > 0 ? v : 1.0;
            }
        } catch (Throwable ignored) {
            // 服务器/测试环境无配置 → 回退默认
        }
        return 1.0;
    }

    /**
     * ★ 2026-09-14 Phase T9/T9b：<b>岩性 → 方块映射</b>（让地质岩性在游戏里可见）。
     *
     * <h3>为何需要（用户提问："这个岩石是虚拟岩石吗？"）</h3>
     * <p>P3 之前岩性只参与<b>地形形成</b>（抗蚀性耦合），但 {@code fillTerrainColumn}
     * 地下一律铺 {@code STONE} ⇒ 玩家挖下去看不到任何岩性差异。
     * 本表把 8 种地质岩性映射到原版方块，使<b>岩性 → 地形 → 方块</b>形成可见的因果闭环。</p>
     *
     * <h3>★ 选择依据：只用【深度无关】的原版岩石方块</h3>
     * <p>用户指出"注意看这些方块在 MC 的设定"。首版误用 {@code DEEPSLATE} 表示
     * 片麻岩/片岩 —— 但 MC 的深板岩<b>只在 Y=−64~0 取代石头</b>（wiki 原文），
     * 而多数山地的地表 &gt; Y=0 ⇒ 实测 <b>29.8% 的列</b>被迫回退 {@code STONE}
     * （大片石头，岩层白做）。</p>
     * <p>正解：<b>弃用深板岩</b>，只选<b>深度无关</b>（MC 中在地上地下均可自然出现、
     * 无高度硬限制）的岩石方块 ⇒ 岩层在任何海拔都能正常显示，且不违反任何设定。</p>
     *
     * <table border="1">
     *   <caption>映射表（Δ = T9b 修订）</caption>
     *   <tr><th>岩性</th><th>方块</th><th>MC 设定依据</th></tr>
     *   <tr><td>GNEISS 片麻岩</td><td>DIORITE 闪长岩</td>
     *       <td>Δ弃深板岩（Y&lt;0 限制）。闪长岩是中性深成岩、灰白色带斑，
     *           与片麻岩（灰白+条带）观感接近 ✓</td></tr>
     *   <tr><td>SCHIST 片岩</td><td>TUFF 凝灰岩</td>
     *       <td>Δ弃深板岩。凝灰岩为细粒火山碎屑岩（致密、灰绿），
     *           与片岩（细粒、片理、灰绿）观感接近 ✓</td></tr>
     *   <tr><td>GRANITE 花岗岩</td><td><b>GRANITE</b></td><td>原版同名，精确对应 ✓</td></tr>
     *   <tr><td>SANDSTONE 砂岩</td><td><b>SANDSTONE</b></td><td>原版同名，精确对应 ✓</td></tr>
     *   <tr><td>SHALE 页岩</td><td>TERRACOTTA 陶瓦（橙）</td>
     *       <td>Δ弃 CLAY：<b>陶瓦 = 硬化的黏土</b>（烧制后成岩），
     *           正是页岩（黏土固结成岩）的对应物，且是<b>坚硬岩石</b>而非松软土 ✓
     *           参考 MC <b>恶地（Badlands）</b>群系：原版即用陶瓦表现地层 ✓</td></tr>
     *   <tr><td>LIMESTONE 石灰岩</td><td>WHITE_TERRACOTTA 白色陶瓦</td>
     *       <td>Δ弃 CALCITE：方解石仅生成于紫水晶洞（Y≤30，深部），浅部违和。
     *           白色陶瓦 —— 浅色沉积岩层，无高度限制 ✓</td></tr>
     *   <tr><td>BASALT 玄武岩</td><td><b>BASALT</b></td><td>原版同名，精确对应 ✓</td></tr>
     *   <tr><td>ANDESITE 安山岩</td><td><b>ANDESITE</b></td><td>原版同名，精确对应 ✓</td></tr>
     * </table>
     *
     * <h3>★ 设计原则：不严格按现实名称，优先【MC 观感 + 无生成限制】</h3>
     * <p>用户建议："不一定完全按照现实的名称去使用岩石方块，可以用安山岩、石头、
     * 砂岩、红砂岩、各色陶瓦（MC 恶地群系就在用）"。据此定三条优先级：</p>
     * <ol>
     *   <li><b>有同名方块</b>（花岗岩/砂岩/玄武岩/安山岩）→ 直接用（精确且无争议）；</li>
     *   <li><b>无同名方块</b> → 按<b>成因+观感</b>就近，且必须是
     *       <b>无生成高度限制</b>的自然方块（陶瓦系/闪长岩/凝灰岩/石头）；</li>
     *   <li><b>禁止</b>使用带高度/群系硬限制的方块做地层
     *       （深板岩 Y&lt;0、方解石紫水晶洞、黏土偏水下）。</li>
     * </ol>
     *
     * <p><b>为何用陶瓦做沉积岩</b>：MC 的恶地群系本身就用红砂岩 + 各色陶瓦
     * 表现地层 ⇒ 陶瓦在本项目中读起来就是"地层"，与砂岩系搭配层理分明。
     * 若日后要更丰富的配色，可按<b>层位</b>再选不同颜色陶瓦
     * （当前是"岩性→方块"一对一；二维映射需改表结构）。</p>
     *
     * <p><b>为何不用 STONE 兜底</b>：{@code STONE} 保留给"无岩性信息"的列
     * （{@code STRATA_ENABLED=false} / 海洋列 / 打包值退化）⇒ 可区分
     * "地质上确实没数据"与"岩性是片麻岩"。</p>
     *
     * <p>索引与 {@link com.geogenesis.worldgen.terrain.RockType#ordinal()} <b>严格对齐</b>
     * （顺序：GNEISS, SCHIST, GRANITE, SANDSTONE, SHALE, LIMESTONE, BASALT, ANDESITE）。
     * 若 {@code RockType} 增删成员，本表必须同步 —— 越界时回退 STONE（安全）。</p>
     */
    private static final BlockState[] ROCK_BLOCKS = {
            Blocks.DIORITE.defaultBlockState(),            // 0 GNEISS    片麻岩 → 闪长岩
            Blocks.TUFF.defaultBlockState(),               // 1 SCHIST    片岩   → 凝灰岩
            Blocks.GRANITE.defaultBlockState(),            // 2 GRANITE   花岗岩 → 花岗岩（同名）
            Blocks.SANDSTONE.defaultBlockState(),          // 3 SANDSTONE 砂岩   → 砂岩（同名）
            Blocks.TERRACOTTA.defaultBlockState(),         // 4 SHALE     页岩   → 陶瓦（Δ 原 CLAY）
            Blocks.WHITE_TERRACOTTA.defaultBlockState(),   // 5 LIMESTONE 石灰岩 → 白色陶瓦（Δ 原 CALCITE）
            Blocks.BASALT.defaultBlockState(),             // 6 BASALT    玄武岩 → 玄武岩（同名）
            Blocks.ANDESITE.defaultBlockState(),           // 7 ANDESITE  安山岩 → 安山岩（同名）
    };

    /**
     * ★ 2026-09-14 Phase T11：逐格确定性随机 [0,1)（用于坡度抖动与碎石选材）。
     *
     * <p><b>为何用 hash 而非噪声实例</b>：方块层（本类）不持有噪声场实例，且碎石
     * 斑块属<b>逐格细节</b>（非大尺度结构）⇒ 确定性 hash 足够：零状态、
     * 不依赖播种、跨 chunk 无缝（同坐标恒同值）。</p>
     */
    private static float hash01(int wx, int wz, long salt) {
        long h = (long) wx * 374761393L + (long) wz * 668265263L + salt;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return ((h & 0xFFFFFFL) / (float) 0x1000000L);
    }

    /**
     * ★ 2026-09-14 Phase T11：<b>碎石坡材质</b>（加权混合，参考 RTF {@code placeScree}）。
     *
     * <p>RTF 用 {@code WeightedBlockSelector}：gravel×1, coarse_dirt×1,
     * andesite×2, tuff×2, moss×1 —— 即<b>岩块与土混杂</b>，而非纯岩石。</p>
     *
     * <p><b>为何必须混杂</b>：若中等坡度全铺岩石，会形成大片突兀的"石海"
     * （当前观感尚可，不宜大面积改变）。掺入粗泥土 ⇒ 视觉上是
     * "植被稀疏的岩屑坡"，与周围草地<b>自然过渡</b> ✓</p>
     *
     * <p>权重：安山岩 2、凝灰岩 2、砾石 1、粗泥 1（总 6）—— 岩石为主、土为辅。</p>
     */
    private static BlockState screeBlock(int wx, int wz) {
        float r = hash01(wx, wz, 0x5C2E_E91B_37A4_D6F0L);
        if (r < 2.0f / 6.0f) return ANDESITE;      // 0.000 ~ 0.333
        if (r < 4.0f / 6.0f) return TUFF_BLK;      // 0.333 ~ 0.667
        if (r < 5.0f / 6.0f) return GRAVEL;        // 0.667 ~ 0.833
        return COARSE_DIRT;                        // 0.833 ~ 1.000
    }

    /**
     * ★ 2026-09-14 Phase T11：<b>恶地色带</b>（参考 RTF {@code erodeDesert}）。
     *
     * <p>按坡度分档选陶瓦颜色 —— MC 恶地（Badlands）群系本身即用
     * 红砂岩 + 各色陶瓦表现地层 ⇒ 陶瓦在本项目中天然读作"地层"。</p>
     *
     * <p>坡度越大 → 露出越深/越鲜艳的层（模拟崖壁自上而下揭穿更多地层）。</p>
     */
    private static BlockState badlandsBlock(float steep) {
        if (steep > 0.975f) return ORANGE_TERRACOTTA;
        if (steep > 0.850f) return BROWN_TERRACOTTA;
        if (steep > 0.750f) return RED_TERRACOTTA;
        if (steep > 0.650f) return Blocks.TERRACOTTA.defaultBlockState();
        return YELLOW_TERRACOTTA;
    }

    /**
     * ★ 2026-09-14 Phase T9c：<b>地表出露岩性</b>（最浅层）的 ordinal。
     *
     * <p>用于<b>陡坡裸岩</b>与<b>群系判定为裸岩</b>的地表方块（T9/T9b 只改了地下，
     * 地表仍硬编码 STONE ⇒ 用户反馈"陡峭坡的裸露岩石还是石头，并不是岩层的方块"）。
     * 取地表出露层 {@link Cell#rockLayer} 对应的岩性，与地下岩层的<b>最上一层同源</b>
     * ⇒ 陡崖露出的岩石与紧邻地下岩性一致（挖下去即同一岩性）。</p>
     *
     * @return 岩性 ordinal；无数据/越界时返回 {@code -1} ⇒ 调用方回退 {@code STONE}
     */
    private static int surfaceRockOrd(Cell cell, int surfaceY, double seaLevel) {
        if (cell.rockSeqPacked == 0) return -1;              // STRATA 未启用 / 退化
        // ★ T10：改为【水平层】后，地表裸岩应取"地表下方第一个岩层"所在的层
        //   （与地下同一套水平层逻辑）⇒ 陡坡不同高度会露出不同岩层（崖壁彩条），
        //   且与紧邻地下岩性连续。
        final int nLay = StratumField.LAYER_COUNT;    // = 4（与 fillTerrainColumn 同）
        int period = 0;
        for (int i = 0; i < nLay; i++) {
            period += StratumField.thicknessOf(
                    StratumField.thickLevelAt(cell.rockSeqPacked, cell.rockLayer + i));
        }
        if (period <= 0) return -1;
        // 与 fillTerrainColumn 同基准：seaLevel + 区域倾斜
        int base = (int) Math.round(seaLevel + cell.rockTilt);
        int local = Math.floorMod((surfaceY - 3) - base, period);
        int acc = 0;
        for (int i = 0; i < nLay; i++) {
            int th = StratumField.thicknessOf(
                    StratumField.thickLevelAt(cell.rockSeqPacked, cell.rockLayer + i));
            if (local < acc + th) {
                int id = StratumField.seqAt(cell.rockSeqPacked, cell.rockLayer + i);
                return (id >= 0 && id < ROCK_BLOCKS.length) ? id : -1;
            }
            acc += th;
        }
        return -1;
    }

    /**
     * ★ 2026-09-14 Phase T9b：<b>垂直岩层</b>（可变层厚）。
     *
     * <p>每层厚度由 {@link StratumField#thickLevelAt} 给出（5 bit 噪声级别 → 5~42 块，
     * 随空间变化）—— 用户指出"现实里面的岩层不可能固定厚度"。层序严格"老岩层在下"
     * （{@code seqAt} 按深度下标递增）。</p>
     *
     * <p>全部映射方块均为<b>深度无关</b>（无 MC 生成高度硬限制）⇒ 任何海拔都能正常显示
     * （Δ 首版用深板岩导致 29.8% 的列被迫回退 STONE）。</p>
     */

    // 地形引擎（每生成器实例一份）。参数来自当前世界存档，种子来自 LevelEvent.Load。
    private GeoGenesisTerrain terrain;

    /**
     * 当前世界的地形参数（按存档级）。由 {@link com.geogenesis.GeoGenesisServerEvents} 在主世界加载时注入；
     * 为 null 时运行时回退到全局 toml 默认模板（{@link GeoGenesisConfig#INSTANCE}）。
     * <p>
     * 用静态持有：单 JVM 同一时刻只有一个主世界，与现有 {@code worldSeed} 静态方案一致。
     */
    private static volatile TerrainParams currentWorldParams = null;

    /** 注入当前世界参数（来自 WorldSavedData；null = 用全局 toml 默认）。 */
    public static void setCurrentWorldParams(TerrainParams p) {
        currentWorldParams = p;
    }

    /** 解析当前世界参数：存档优先，缺失回退全局 toml。 */
    public static TerrainParams resolveParams() {
        return currentWorldParams != null ? currentWorldParams : GeoGenesisConfig.INSTANCE.buildParams();
    }

    /**
     * 用给定参数 + 当前世界种子构建（已播种）地形引擎。
     *
     * ★ 2026-08-14 卡死修复：**共享单例**——河网构建（RiverBuilder.mountainPath）触发
     *   discharge 采样 → 侵蚀 tile 同步生成（670ms/个），20 个 Worker 线程各 new 一套
     *   = 并发重复构建 + 重复生成 → 世界生成卡死（日志 37% 后无输出）。单例后只构建
     *   一次，跨线程共享（内部 ConcurrentHashMap 线程安全）。
     */
    private static volatile GeoGenesisTerrain sharedTerrain;

    public static GeoGenesisTerrain buildTerrain(TerrainParams params) {
        GeoGenesisTerrain t = sharedTerrain;
        if (t == null) {
            synchronized (GeoGenesisGenerator.class) {
                t = sharedTerrain;
                if (t == null) {
                    CellGenerator gen = new CellGenerator(params, WORLD_MIN_Y, WORLD_MAX_Y);
                    GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
                    // ★ 2026-08-26 修复：必须走 terrain.seed() —— 它内部调 generator.seed +
                    //   rivers.setWorldSeed。只 gen.seed 会漏掉河网种子（所有世界同一河网）。
                    terrain.seed(worldSeed);
                    sharedTerrain = terrain;
                    t = terrain;
                }
            }
        }
        return t;
    }

    public GeoGenesisGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    // ===== 初始化 =====

    /**
     * 惰性初始化地形引擎并注入 BiomeSource，保证 biome 采样与方块填充基于同一地形场。
     */
    private void ensureEngine(long seed) {
        if (terrain == null) {
            terrain = buildTerrain(resolveParams());
            // inject terrain into BiomeSource so it can classify biomes by Cell data
            if (biomeSource instanceof GeoGenesisBiomeSource gbs) {
                gbs.setTerrain(terrain);
                LOGGER.info("GeoGenesis terrain injected into BiomeSource (seed={})", seed);
            }
        }
    }

    // ===== 核心：fillFromNoise =====

    // 当前世界种子（由 GeoGenesisServerEvents.LevelEvent.Load 注入）
    private static volatile long worldSeed = 12345L;

    public static void setWorldSeed(long seed) {
        worldSeed = seed;
        // ★ 2026-08-14 单例失效：新世界 seed 变化 → 下次 buildTerrain 重建河网/地形
        sharedTerrain = null;
        LOGGER.info("GeoGenesis world seed set to {} (terrain singleton invalidated)", seed);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            java.util.concurrent.Executor executor,
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        long t0 = System.nanoTime();
        ensureEngine(worldSeed);
        // ★ 2026-08-09 优化：出生点周边异步预热（首次 fillFromNoise 触发一次，后台池）
        terrain.preloadSpawnAsync();
        long t1 = System.nanoTime();

        ChunkPos pos = chunk.getPos();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        long t2 = System.nanoTime();
        // ★ 2026-08-29：旧 RTF 河网已下线，水文模型是唯一的河流实现，故河流开关
        //   统一为配置面板 riverEnabled（原 HydrologyExperimentSwitch 的 LEGACY_RTF
        //   模式已无对应实现，保留会导致"地形被水文雕刻却不灌水"的干河谷）。
        // ★ 2026-09-08 侵蚀修复终版（管线顺序：河流建网在前、侵蚀在后）：
        //   统一走 getChunkCells（sample + extractFromTile 侵蚀 + applyHydrologyValley
        //   雕刻减法 + delta 移位）。旧 applyHydrologyChunk 的 carvedGroundY 直接覆盖
        //   会把侵蚀整条丢掉（用户实测："侵蚀只在预览工作，游戏里不工作"）；
        //   而给水文采样改 sampleWu 的首修又让河网构建期触发侵蚀 tile 冷生成
        //   （预览开窗即卡）。终版：建网无侵蚀（快），侵蚀在落块合成时叠加（生效）。
        boolean hydrologyOn = terrain.riversEnabled();
        Cell[] cells = terrain.getChunkCells(pos.x, pos.z);
        long t3 = System.nanoTime();

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];
                fillTerrainColumn(chunk, mPos, baseX + lx, baseZ + lz, cell, hydrologyOn);
            }
        }
        long t4 = System.nanoTime();

        if ((t3 - t2) > 100000000L || (t4 - t3) > 100000000L) {
            LOGGER.info("[PERF] fillFromNoise chunk({},{}): ensure={}ms cells={}ms place={}ms total={}ms",
                pos.x, pos.z,
                (t1-t0)/1000000, (t3-t2)/1000000, (t4-t3)/1000000, (t4-t0)/1000000);
        }

        // ★ 2026-09-14：本 chunk 已生成完毕 → 释放【出生点预热】的等待。
        //   预热在此之前一直等待（见 GeoGenesisTerrain.preloadSpawnAsync）：
        //   实测并发预热会把首 chunk 从 893ms 拖到 1086ms，正好加长"进度条不动"的窗口。
        terrain.noteChunkGenerated();

        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * 地形列填充：按 cell.height 铺 基岩/深板岩/石/土/表层/水柱。
     * 河谷雕刻与水柱判定均由水文雕刻计划预先写入 cell（cell.height 已含雕刻量，
     * riverType/riverSurfaceY 给出灌水判据），本方法只负责落块，不再二次采样河网。
     *
     * 关键修复（旧版 bug）：
     * - 地表用 {@code y == surfaceY}（surfaceY = floor(height)），不再用 {@code y == (int)height}
     *   整等——小数高度下草层永远铺不上（大面积裸石）。
     * - 海洋列补齐水柱（surfaceY+1 .. SEA_LEVEL 填水），旧版 else-if 链使海洋无水。
     * - 表层方块随 terrainType/水陆决定：陆地 GRASS+DIRT，海滩/浅水 SAND，深水 GRAVEL。
     */
    private void fillTerrainColumn(ChunkAccess chunk, BlockPos.MutableBlockPos mPos,
                                   int wx, int wz, Cell cell,
                                   boolean hydrologyOn) {
        // ★ RTF 范式（2026-08-26）：河谷已由 generateChunk 雕刻回写 cell.height（Zone1-4
        //   平滑谷），此处不再二次雕刻，仅按 waterTable 水面做灌水判定（Streams isStreamBed：
        //   雕刻后地面 < 水面 − 0.5 才灌水；岸坡/漫滩只塑形不上水）。
        double groundY = cell.height;
        boolean riverWater = false;
        boolean riverWall = false;
        double waterTop = SEA_LEVEL;
        if (hydrologyOn) {
            // ★ 水文模型：灌水判定完全由水文雕刻计划决定（fillWater 已含河道中心门控）
            if (cell.riverType != 0 && cell.riverSurfaceY > groundY) {
                riverWater = true;
                waterTop = cell.riverSurfaceY;
            }
        }
        int surfaceY = (int) Math.floor(groundY);
        // 海洋补灌必须与 riverType 正交：入海河列仍是海洋列，不能因被河计划命中就失去
        // sea-level 水柱。只依据原始地形分类识别海洋，避免把内陆雕刻河床抬到海平面。
        // 海洋补灌：地面低于海平面的列即为海域，不再要求 cell.isWater()（e<0），
        // 否则入海口两岸 e≥0 但 groundY<seaLevel 的列既无河水也无海水→出现缺角。
        double seaLevel = terrain.seaLevel();
        boolean ocean = hydrologyOn && groundY < seaLevel;
        if (ocean) waterTop = Math.max(waterTop, seaLevel);
        // 水文模式：灌水 = 河计划 ∪ 海洋；入海重叠列取两者较高水面（即海平面）。
        boolean water = hydrologyOn ? (riverWater || ocean) : (cell.isWater() || riverWater);
        boolean beach = cell.terrainType == TerrainClass.BEACH;

        // 表层与填充块选择（R9：墙顶草皮、墙壁土、河心砾石——DW top/filler/base 语义）
        // ★ T11：坡度加【逐格确定性抖动】—— 直接用 gradient 比阈值会让边界沿
        //   "等坡度线"形成光滑曲线（本项目反复强调的等值线问题）；抖动后边界呈
        //   有机斑块/锯齿，与噪声地形自然融合（参考 RTF slopeModifier 思路）。
        float steepened = cell.gradient
                + (hash01(wx, wz, 0x2A7B_51C9_6E30_4D81L) - 0.5f) * 2.0f * GRADIENT_JITTER;
        BlockState top, fill;
        if (riverWall) {
            top  = GRASS;  // 墙顶 = 岸顶草皮（DW：y==repairTopY && originalY<=top+2 → 草）
            fill = DIRT;
        } else if (riverWater) {
            if (groundY < waterTop - 3) { top = GRAVEL; fill = GRAVEL; }   // 河床
            else if (groundY < waterTop) { top = SAND; fill = SAND; }      // 浅岸
            else { top = DIRT; fill = DIRT; }                              // 露出河岸裸土
        } else if (water) {
            top  = (groundY <= SEA_LEVEL - 3) ? GRAVEL : SAND;  // 深水砾石 / 浅水沙
            fill = top;
        } else if (beach) {
            top  = SAND;
            fill = SAND;
        } else if (steepened > ROCK_GRADIENT) {
            // 陡坡裸岩（RTF Steepness + ErodeFeature 范式）：陡崖不长植被、积不住沙。
            // ★ 2026-09-14 T9c：按该列【最浅层岩性】出露（原硬编码 STONE 已修）。
            // ★ 2026-09-14 T11：干旱/沙漠群系的陡崖改用【恶地色带】陶瓦
            //   （参考 RTF erodeDesert）—— MC 恶地本身即用陶瓦表现地层，
            //   且崖壁按坡度分色 ⇒ 天然形成"地层被切割露出"的彩条观感。
            //   非干旱区仍按岩性出露（保留地质信息）。
            if (BiomeClassifier.surfaceOf(cell) == BiomeClassifier.SurfaceType.SAND) {
                top  = badlandsBlock(cell.gradient);
                fill = top;
            } else {
                int surfOrd = surfaceRockOrd(cell, surfaceY, seaLevel);
                top  = surfOrd >= 0 ? ROCK_BLOCKS[surfOrd] : STONE;
                fill = top;
            }
        } else if (steepened > SCREE_GRADIENT
                && cell.riverNetDischarge >= SCREE_DISCHARGE_MIN * erosionDropsMul()) {
            // ★ 2026-09-14 T11b：碎石坡（scree / talus，参考 RTF placeScree）
            //   中等坡度段：基岩风化碎屑堆积、植被稀疏。
            //   用【安山岩/凝灰岩/砾石/粗泥】加权混合 ⇒ 与周围草地自然过渡，
            //   不会形成突兀的大片"石海"（此前该坡段直接是草/土，山地过渡生硬）。
            //
            //   ★ 2026-09-14 修复（用户反馈"碎石堆好像都在山脊上"）：
            //     原判据【只有坡度】⇒ √(dhx²+dhz²) 恒为正，山脊坡面与沟谷侧壁
            //     数值相同 ⇒ 脊上也出碎石（物理错误：碎屑会从凸脊滚落）。
            //     现加【汇流门控】riverNetDischarge ≥ 阈值：
            //       discharge = 液滴汇流累积 ⇒ 沟壑/坡脚高、脊线低，即"凹凸"代理。
            //     实测 AUC=0.845，D=2.31 时脊保留 24.9%、沟保留 77.9%
            //     （见 SCREE_DISCHARGE_MIN 的完整依据）。
            //     未达汇流阈值的陡坡（脊线）落入下方 else 分支 ⇒ 走群系/岩性常规材质。
            top  = screeBlock(wx, wz);
            fill = top;
        } else {
            // 地表方块由【群系】决定（BiomeClassifier.surfaceOf）——此前只看地形类型，
            // 导致沙漠群系也铺草方块。BEACH 已在上面单独处理。
            switch (BiomeClassifier.surfaceOf(cell)) {
                case SAND   -> { top = SAND;   fill = SAND; }
                // ★ 2026-09-14 T9c：群系判定为"裸岩"（山地/石质群系）同样按【岩性】
                //   出露 —— 与陡坡裸岩、地下岩层同源（此前一律 STONE，与地层脱节）。
                case STONE  -> {
                    int o = surfaceRockOrd(cell, surfaceY, seaLevel);
                    top = o >= 0 ? ROCK_BLOCKS[o] : STONE;
                    fill = top;
                }
                case GRAVEL -> { top = GRAVEL; fill = GRAVEL; }
                case PODZOL -> { top = PODZOL; fill = DIRT; }
                default     -> { top = GRASS;  fill = DIRT; }
            }
        }

        // R9 落块（DW 语义）：地表按 groundY 铺、水柱灌到 waterTop；墙区地面已被
        // carve 抬到水面 → 落块自然形成堤岸（groundY=水面 → 水柱 1 块 + 墙顶草皮）。
        int waterTopBlock = (int) Math.floor(waterTop);
        // ★ 瀑布水幕（2026-08-30）：跌水列在水面之上再挂一段垂直流动水（潭面 → 唇口），
        //   即旧 Streams fillRiver 的 `yDownstreamSurface+1 .. ySurface` 语义——
        //   瀑布不是几何特例，只是"本列两个水位之间的垂直水体"。
        //   普通列 riverLipY == riverSurfaceY → lipBlock == waterTopBlock，无副作用。
        int lipBlock = (riverWater && cell.riverLipY > waterTop)
                ? (int) Math.floor(cell.riverLipY) : waterTopBlock;
        int fillTopBlock = Math.max(waterTopBlock, lipBlock);
        // ★ 2026-09-14 Phase T10：预解析本列的【水平地层】查找表（LUT）。
        //
        //   【Δ 为何改为水平层】T9b 是"披盖式"：地层从地表往下累加层厚
        //   ⇒ 层界随地形起伏（像洋葱一层层裹住山体）。但<b>真实地层是水平沉积</b>的，
        //   后经构造倾斜/褶皱、再被侵蚀切割露出 —— 参考 RTG 的恶地彩带（绝对 Y 取模）
        //   与探索结论「区域层序 + 绝对 Y 基准 + 倾斜」最接近真实地质。
        //
        //   【实现】层序在垂直方向<b>循环</b>（RTG 式 y % 周期），配合：
        //     · 可变层厚（T9b，5~42 块噪声）
        //     · 区域倾斜 tilt（T10，模拟褶皱使层界面起伏，而非绝对平面）
        //   ⇒ 水平层 + 界面起伏 + 层厚变化，且山顶/深谷都有层（循环保证）。
        //
        //   【LUT】把"周期内的每一格 Y → 岩性"展开成数组 ⇒ 逐 y 查表 O(1)，
        //   避免每格重复累加（周期总厚 ≤ 16层×42 = 672 格，数组极小）。
        //   只在陆地列构建（cell.isWater() 为假）：海洋列保持 STONE/DEEPSLATE
        //   （修改海洋侧风险更高，且海底被水覆盖 ⇒ 暂不动）。
        // ★★★ 2026-09-14 性能回归修复（用户："卡在 0% 要等很久"）★★★
        //   【原实现】把"周期内每格 Y → 岩性"展开成 LUT（int[total]），
        //   total 可达 16层×42 = 672 ⇒ <b>每列分配 2.7KB，每 chunk 256 列 = 705KB 垃圾</b>
        //   ⇒ 世界生成数百 MB/s 分配 ⇒ GC 频繁停顿（进度卡 0%）。
        //   【另有一处错误】nLay 写成 LAYER_COUNT*4=16，但 packSequence 本就按
        //   LAYER_COUNT=4 循环填充、seqAt 也按 4 取模 ⇒ 16 只是重复 4 遍相同序列（浪费 4 倍）。
        //   【正解】不建 LUT：只保留 nLay=4 的【层厚 + 层岩性】两个小数组，
        //   逐 y 用累减查找（4 次比较）。nLay 仅 4 ⇒ 比 O(1) 查表稍慢但<b>零大分配</b>，
        //   整体远快于"分配 2.7KB/列 + GC"（无 GC 才是关键）。
        int[] rockTh = null;      // 各层厚度（块）
        int[] rockOrd = null;     // 各层岩性 ordinal（-1 = 回退 STONE）
        int rockPeriod = 0;
        int rockBase = 0;
        if (!cell.isWater() && cell.rockSeqPacked != 0) {
            final int nLay = StratumField.LAYER_COUNT;         // = 4（层序循环长度）
            rockTh = new int[nLay];
            rockOrd = new int[nLay];
            int period = 0;
            for (int i = 0; i < nLay; i++) {
                rockTh[i] = StratumField.thicknessOf(
                        StratumField.thickLevelAt(cell.rockSeqPacked, cell.rockLayer + i));
                int id = StratumField.seqAt(cell.rockSeqPacked, cell.rockLayer + i);
                rockOrd[i] = (id >= 0 && id < ROCK_BLOCKS.length) ? id : -1;   // 越界守卫
                period += rockTh[i];
            }
            rockPeriod = period;
            // 基准：海平面 + 区域倾斜（tilt 使层界面在区域尺度起伏 = 褶皱）
            rockBase = (int) Math.round(seaLevel + cell.rockTilt);
        }
        for (int y = WORLD_MIN_Y; y < WORLD_MAX_Y; y++) {
            mPos.set(wx, y, wz);
            BlockState state;
            if (y == WORLD_MIN_Y) {
                state = BEDROCK;
            } else if (y < surfaceY - 3) {
                if (rockTh != null && rockPeriod > 0) {
                    // 水平层：按【绝对 Y】取模周期内位置（floorMod 保证负 y 正确）
                    int local = Math.floorMod(y - rockBase, rockPeriod);
                    // 累减查找（nLay=4 ⇒ 最多 4 次比较，零分配）
                    int ord = -1, acc = 0;
                    for (int i = 0; i < rockTh.length; i++) {
                        if (local < acc + rockTh[i]) { ord = rockOrd[i]; break; }
                        acc += rockTh[i];
                    }
                    state = ord >= 0 ? ROCK_BLOCKS[ord] : ((y < 0) ? DEEPSLATE : STONE);
                } else {
                    state = (y < 0) ? DEEPSLATE : STONE;       // 无岩性数据 / 海洋列
                }
            } else if (y < surfaceY) {
                state = fill;                                     // 表层下 3 格（土/沙）
            } else if (y == surfaceY) {
                state = top;                                      // 地表最顶块
            } else if (water && y <= fillTopBlock) {
                if (y > waterTopBlock) {
                    state = FLOWING_WATER;          // 瀑布水幕：垂直悬挂的流动水
                } else {
                    // 表层用流动水（方案 B 流动观感）；其余静水预填，确定性、永不断裂
                    state = (y == waterTopBlock) ? FLOWING_WATER : WATER; // 水柱（水面以下）
                }
            } else {
                break;                                            // 地表/水面以上：默认 AIR，跳过
            }
            chunk.setBlockState(mPos, state, false);
        }

        // 雪线以上铺雪层：isSnow 由 CellGenerator 按 snowLine/纬度/湿度配置统一计算，
        // 与 BiomeClassifier 的垂直带共用同一阈值（消除此前"两套雪线"）。
        if (!water && cell.isSnow) {
            int snowY = surfaceY + 1;
            if (snowY < WORLD_MAX_Y) {
                mPos.set(wx, snowY, wz);
                chunk.setBlockState(mPos, SNOW, false);
            }
        }
    }

    // ===== 基础覆写 =====

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public int getGenDepth() {
        return WORLD_MAX_Y - WORLD_MIN_Y;
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return WORLD_MIN_Y;
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structureManager) {
        // 重新启用原版群系装饰：委托基类 ChunkGenerator.applyBiomeDecoration。
        // 基类按 FEATURES 状态遍历群系，用 biomeSource 返回的群系（原版 biome Holder）
        // 按其 BiomeGenerationSettings 放置树/草/花/甘蔗等特征。
        // 地表已由 fillFromNoise 在 NOISE 阶段铺好（草/沙/砾石顶块），
        // 装饰在 FEATURES 阶段叠加，顺序正确，无需自研放置逻辑。
        super.applyBiomeDecoration(level, chunk, structureManager);
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                              BiomeManager biomeManager, StructureManager structures,
                              ChunkAccess chunk, GenerationStep.Carving carving) {
        // 暂不实现洞穴雕刻
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structures,
                              RandomState random, ChunkAccess chunk) {
        // fillFromNoise 已直接设置顶层方块
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // 暂不自定义生物生成
    }

    // ===== 可选覆写（世界预设显示） =====

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        // 调试屏幕信息（可选）
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                              LevelHeightAccessor levelHeightAccessor,
                              RandomState randomState) {
        // ★ 2026-09-11 P0-1 止血：原走 terrain.sampleCell → getChunkCells → 冷侵蚀 tile
        //   （实测 400~719 ms/次）。而 STRUCTURE_STARTS 早于 NOISE → 此处必然是未生成 chunk，
        //   等于把全管线最贵的操作接到最热的调用点 → 世界生成卡死。
        //   改走【非阻塞】两级降级：
        //     ① 已生成 chunk → 取缓存 Cell，与落块完全一致（零额外成本）
        //     ② 未生成 chunk → 廉价重算（基础场 + 已缓存侵蚀增量），绝不触发侵蚀 tile
        double h = terrain != null ? terrain.sampleHeightNonBlocking(x, z) : SEA_LEVEL;
        return (int) Math.round(h);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z,
                                      LevelHeightAccessor levelHeightAccessor,
                                      RandomState randomState) {
        // 同 getBaseHeight：非阻塞两级降级，绝不触发侵蚀 tile（详见上方注释）
        double h = terrain != null ? terrain.sampleHeightNonBlocking(x, z) : SEA_LEVEL;
        int iy = (int) Math.round(h);
        BlockState[] states = new BlockState[getGenDepth()];
        for (int i = 0; i < getGenDepth(); i++) {
            int y = getMinY() + i;
            if (y <= iy - 1) states[i] = y < 0 ? DEEPSLATE : STONE;
            else if (y <= SEA_LEVEL) states[i] = WATER;
            else states[i] = AIR;
        }
        return new NoiseColumn(getMinY(), states);
    }
}
