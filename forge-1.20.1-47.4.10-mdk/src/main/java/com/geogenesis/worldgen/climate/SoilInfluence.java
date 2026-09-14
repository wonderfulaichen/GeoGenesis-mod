package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.terrain.RockType;

/**
 * 成土母质对植被的调制（★ 2026-09-14 Phase T12：<b>地质 → 群系耦合</b>）。
 *
 * <h3>为何需要（用户的提问："地质和群系好像有一定关系？"）</h3>
 * <p>地理学上确实强相关：<b>岩性 → 风化产物 → 土壤理化性质 → 植被类型</b>。
 * 但本项目此前<b>完全没接</b>：{@code BiomeClassifier} 的群系输入只有
 * 温度 × 降水（Whittaker）、海拔垂直带、地形形态三样，
 * {@link RockType} 的全部消费方仅"方块层"与"预览图层"。
 *
 * <h3>★ 参考项目调研结论（两个项目都没做）</h3>
 * <p>经只读核查 FreeTerraForged 与 RTG-Community：</p>
 * <ul>
 *   <li><b>FreeTerraForged</b>：{@code CellSampler.Field.SEDIMENT} 只进入<b>地形密度函数</b>
 *       （碎屑堆积/侵蚀），<b>不参与群系轴</b>；其 {@code StrataRule} 仅作用于
 *       <b>地表方块分层</b>。全仓库无"岩性 → 群系"映射。</li>
 *   <li><b>RTG</b>：完全没有岩性轴，{@code plateauBands} 只是"高度 → 色带方块"。</li>
 * </ul>
 * <p>⇒ 本类是本项目的<b>原创扩展</b>，无现成配方可照搬。
 * 可借鉴的只有 FTG 的 {@code WeightedBlockSelector}（"权重 → 选择"的思路）。</p>
 *
 * <h3>本类的职责边界（接口与实现分离）</h3>
 * <p>本类只做<b>两件与 Minecraft 无关的纯逻辑</b>：</p>
 * <ol>
 *   <li>{@link #of} —— 岩性 → <b>成土性质</b>（{@link SoilCharacter}）；</li>
 *   <li>{@link #triggers} —— 噪声门控（决定该点是否施加岩性影响，成"有机斑块"）。</li>
 * </ol>
 * <p><b>岩性 → 具体群系键</b>的映射表放在 {@code BiomeClassifier} 内 —— 因为
 * 只有那里持有 {@code Biomes.*} 常量，且项目全部群系键映射都集中在那一处
 * （接口与实现分离：本类给"语义方向"，调用方决定"落到哪个群系"）。</p>
 *
 * <h3>★ 为何本类不 import Minecraft</h3>
 * <p>{@code BiomeClassifier} 的群系键索引需 Forge {@code Bootstrap} 才能加载
 * （见其 {@code KeyIndex} 的注释）。本类<b>刻意零 MC 依赖</b>，
 * 使其可在无引导进程（诊断探针）中直接测试。</p>
 *
 * <h3>约束</h3>
 * <ul>
 *   <li><b>零依赖纯函数</b>、<b>确定性</b>（同输入恒同输出）</li>
 *   <li><b>不新增噪声实例</b>：门控复用调用方已有的 {@code Cell.oasisNoise}</li>
 * </ul>
 */
public final class SoilInfluence {

    private SoilInfluence() {}

    /**
     * 成土性质（岩性对植被的"倾向"）。
     *
     * <p>只分三类 —— 刻意<b>不细分</b>，因为能安全落地的群系映射本就有限
     * （见 {@code BiomeClassifier.soilVariant} 的"铁律"说明）。</p>
     */
    public enum SoilCharacter {
        /**
         * 钙质/肥沃：石灰岩（钙质土、排水极佳）与火山岩（火山土、高肥力）。
         *
         * <p>真实对应：欧洲的 <b>钙质草地（calcareous grassland）</b>是公认的
         * 高多样性生境；火山土区（如爪哇、夏威夷）以高生产力著称。</p>
         */
        CALCAREOUS,
        /**
         * 酸性/贫瘠：花岗岩、片麻岩、片岩（硅铝质、难风化、养分贫）。
         *
         * <p>真实对应：<b>酸性土壤</b>抑制多数阔叶树，利于松/桦等先锋树种
         * （"桦木是贫瘠土壤的先锋"是植被演替的经典结论）。</p>
         */
        ACIDIC,
        /**
         * 中性：砂岩（粗粒透水）、页岩（细粒黏重）—— 不产生明确倾向，保持基础群系。
         *
         * <p><b>★ 2026-09-14 决策记录：刻意"不对 NEUTRAL 做群系映射"</b></p>
         * <p>{@code BiomeClassifier.soilVariant} 对本类<b>不做任何改写</b>（直接返回 base）。
         * 理由：映射表的铁律是"只使用 {@code landVariant} 已确立的合法气候邻接对"，
         * 而砂岩/页岩<b>没有</b>可安全复用的既有对 —— 硬造新对正是本项目
         * "跨气候跳变"（曾出现「丛林紧挨草原」）的风险来源。</p>
         * <p>补充数据：{@code runStratumProbe} 实测 SANDSTONE 仅 0.3%（稀有）、SHALE 6.1%，
         * 即"不映射"的实际影响面本就很小；收益（避免邻接违例）远大于此。</p>
         * <p>若日后确实要让砂岩/页岩有植被表现，正确路径是<b>先论证其气候等价群系</b>
         * （并在 {@code landVariant} 中先确立该对），而非在映射表里直接新增。</p>
         */
        NEUTRAL
    }

    /**
     * 岩性 → 成土性质。
     *
     * <p>分类依据：<b>化学风化产物</b>（钙质 vs 硅铝质）与<b>养分供给能力</b>。
     * 硬度（{@link RockType#resistance()}）与成土性质<b>无关</b>，故二者分开建表
     * —— 例如安山岩{@code resistance=0.70}、玄武岩{@code 0.80} 硬度不同，
     * 但成土性质同属火山土（{@link SoilCharacter#CALCAREOUS}）。</p>
     */
    public static SoilCharacter of(RockType rock) {
        if (rock == null) return SoilCharacter.NEUTRAL;
        return switch (rock) {
            case LIMESTONE, BASALT, ANDESITE -> SoilCharacter.CALCAREOUS;
            case GRANITE, GNEISS, SCHIST -> SoilCharacter.ACIDIC;
            case SANDSTONE, SHALE -> SoilCharacter.NEUTRAL;
        };
    }

    /**
     * 岩性影响强度（0 = 完全关闭，逐位退回接入前；1 = 最大覆盖）。
     *
     * <p>取 <b>0.50</b>：岩性影响<b>可见但不支配</b>气候 —— 气候仍是群系主控。
     * 与 {@code TectonicField} 的 {@code CONVERGENT_BOOST} 等常量一样，
     * 刻意用静态常量而非 Forge 配置：<b>避免产生"从未被读"的死配置</b>
     * （项目体检报告 C8 的教训；若日后确需玩家可调，再接配置不迟）。</p>
     */
    public static final double STRENGTH = 0.50;

    /**
     * 强度 → 覆盖率的上限映射系数。
     *
     * <p>门控公式 {@code 触发 ⟺ noise01 > 1 − STRENGTH × MAX_COVERAGE}：
     * 强度 0 时阈值为 1（<b>永不触发</b>，保证可回滚），
     * 强度 0.5 时阈值 0.70（覆盖率约 30%），
     * 强度 1 时阈值 0.40（覆盖率约 60%）。</p>
     *
     * <p>取 0.6 而非 1.0：即使满强度也让岩性影响<b>成"斑块"而非全境</b>
     * （真实植被-母质关系本就受微地形、水分再分配等局部因素打断）。</p>
     */
    private static final double MAX_COVERAGE = 0.6;

    /**
     * 噪声门控：该点是否施加岩性影响。
     *
     * <p><b>为何必须门控（而非"有岩性就改"）</b>：若全境无条件改写，群系会变成
     * 岩性分区的"硬边地图" —— 而岩性分区的边界（{@code StratumField} 的
     * {@code layerAt} 噪声）一旦被直接暴露，就会复现本项目反复出现的
     * 「群系边界成直线/多边形」问题。用大尺度噪声（{@code Cell.oasisNoise}，
     * 波长 ≈0.75 气候区）打散 ⇒ 切换呈<b>有机斑块</b>，与气候群系自然交融。</p>
     *
     * <p><b>为何复用 {@code oasisNoise}</b>：零新增噪声实例、零新增 Cell 字段。
     * 该噪声波长（0.75 气候区）与岩性分区尺度（{@code DEPTH_FREQ}=1/900wu）同量级，
     * 正是"成片而非碎斑"所需。</p>
     *
     * @param character 成土性质（{@link #NEUTRAL} 直接返回 false）
     * @param noise     {@code Cell.oasisNoise} ∈ [-1,1]
     * @param strength  强度（{@link #STRENGTH}）
     * @return 是否施加岩性影响
     */
    public static boolean triggers(SoilCharacter character, double noise, double strength) {
        if (character == null || character == SoilCharacter.NEUTRAL) return false;
        if (strength <= 0.0) return false;                  // 关闭 → 逐位退回
        double noise01 = noise * 0.5 + 0.5;                 // [-1,1] → [0,1]
        double threshold = 1.0 - strength * MAX_COVERAGE;   // 强度 0 → 1.0（永不触发）
        return noise01 > threshold;
    }
}
