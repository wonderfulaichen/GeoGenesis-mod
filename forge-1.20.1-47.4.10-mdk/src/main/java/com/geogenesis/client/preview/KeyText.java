package com.geogenesis.client.preview;

import com.geogenesis.config.ConfigKeyCatalog;
import com.geogenesis.config.ConfigSpecRanges;

import java.util.Locale;

/**
 * <b>配置键的中文短名 + 语义说明</b>（面板上显示的全部文字）。
 *
 * <h2>★ 铁律：这里<b>不许出现数字</b>（默认值/范围一律由 {@link ConfigSpecRanges} 现取）</h2>
 * <p>2026-11-07 审计抓到 5 处真实漂移，全部是"把默认值抄进说明文字"：
 * {@code cAffinityStrength} 写 1.5 实际 0.0、{@code oceanFadeStart} 写 -0.15 实际 -0.13、
 * {@code landRampEnd} 写 0.08 实际 0.05、{@code coastlineWarpOctaves} 写 5 实际 6、
 * {@code coastlineWarpPersistence} 写 0.5 实际 0.6。</p>
 * <p>这类漂移<b>每改一次默认值就新增一处</b>，靠 review 抓不住。所以：
 * 说明只写"改它会发生什么"，<b>数量信息走 {@link #tooltip(String)} 的自动后缀</b>。</p>
 *
 * <h2>维护</h2>
 * <p>{@code ConfigPanelCoverageProbe} 会断言：面板引用到的每个键都必须在这里有说明
 * （否则面板上会出现「（缺说明）」），且说明文本里<b>不得</b>出现 {@code 默认}/数字字样。</p>
 */
final class KeyText {

    /** 键 → 中文短名（面板行左侧）。 */
    static String label(String key) {
        String s = LABELS.get(key);
        if (s != null) return s;
        String legacy = LegacyText.label(key);
        return legacy != null ? legacy : key;
    }

    /** 键 → 悬停说明（语义 + 自动追加的「默认 x，范围 [a, b]」）。 */
    static String tooltip(String key) {
        String s = NOTES.get(key);
        if (s == null) s = LegacyText.note(key);
        if (s == null) s = "（缺说明）";
        String suffix = ConfigSpecRanges.rangeSuffix(key);
        return suffix.isEmpty() ? s : s + "\n" + suffix;
    }

    /** 是否存在说明（探针用；不静默兜底，让覆盖缺口可见）。 */
    static boolean hasNote(String key) {
        return NOTES.containsKey(key) || LegacyText.note(key) != null;
    }

    /** 该键是否按"旧算法"口径渲染（LEGACY_ONLY 的自动后缀）。 */
    static boolean isLegacy(String key) {
        return ConfigKeyCatalog.axisOf(key) == ConfigKeyCatalog.Axis.LEGACY_ONLY;
    }

    // ---------------------------------------------------------------- 说明表

    private static final java.util.Map<String, String> NOTES = new java.util.HashMap<>();
    private static final java.util.Map<String, String> LABELS = new java.util.HashMap<>();

    private static void put(String key, String label, String note) {
        LABELS.put(key, label);
        NOTES.put(key, note);
    }

    static {
        // ---- 世界与尺度 ----
        put("continentScale", "大陆尺度",
                "大陆噪声的特征尺度（块）。越大 ⇒ 大陆/海洋板块越辽阔、海岸线弯折越平缓；\n"
                + "越小 ⇒ 陆块越碎、岛屿与小海湾变多。");
        put("continentWarp", "大陆域扭曲",
                "在大陆场上再叠一层低频坐标位移，把大陆边界揉弯。\n"
                + "0 = 关闭（推荐：多倍频 FBM 本身已给出自然海岸）；调高会让海岸出现大尺度弯折。");
        put("continentFbmOctaves", "FBM 倍频数",
                "大陆性 FBM 叠加的倍频层数。层数越多 ⇒ 海岸线多尺度细节越丰富（自相似犬牙交错）。");
        put("continentFbmLacunarity", "FBM 频率倍增",
                "每层倍频的频率放大系数。越大 ⇒ 细节尺度之间跨度越大（粗细节并置更尖锐）。");
        put("continentFbmPersistence", "FBM 振幅衰减",
                "每层倍频的振幅衰减系数。越大 ⇒ 高频细节越强，海岸更破碎多齿。");
        put("verticalScale", "垂直缩放",
                "把地形高度按比例拉伸。注意陆地侧是「乘完再截顶」，\n"
                + "调得太大会让高峰被截平成平台 ⇒ 想整体加高优优先调「峰高比例」。");
        put("horizontalScale", "水平缩放",
                "块 → 世界单位(wu) 的统一换算。全链路（地形噪声 / 侵蚀笔刷 / 河流雕刻）等比缩放；\n"
                + "调大 ⇒ 同一地形摊得更开（坡度变缓、视野内特征变少）。");
        put("peakHeightFraction", "峰高比例",
                "实际峰顶占世界高度上限的比例（留余量避免触顶被钳平）。\n"
                + "调高 ⇒ 全地形整体更高、山更陡；调低 ⇒ 整体更矮。这是「世界整体高矮」的首选旋钮。");

        // ---- 气候 ----
        put("climateRegionSize", "气候区尺寸",
                "气候区（温湿在区内恒定的抖动网格）的边长（wu）。\n"
                + "越大 ⇒ 气候斑块越大、群系越成片；越小 ⇒ 群系越碎、过渡越频繁。");
        put("tempFrozenThreshold", "温度·极寒分界",
                "温度轴上的分段边界。移动它只改「哪些地方算极寒」，不改地形高度。");
        put("tempColdThreshold", "温度·寒冷分界", "温度轴上的分段边界（只改群系分布）。");
        put("tempWarmThreshold", "温度·温和分界", "温度轴上的分段边界（只改群系分布）。");
        put("tempHotThreshold", "温度·炎热分界", "温度轴上的分段边界（只改群系分布）。");
        put("humidityDryThreshold", "湿度·干旱分界",
                "湿度轴上的分段边界。移动它只改群系分布，不改地形高度。");
        put("humiditySemiThreshold", "湿度·半干旱分界", "湿度轴上的分段边界（只改群系分布）。");
        put("humidityWetThreshold", "湿度·潮湿分界", "湿度轴上的分段边界（只改群系分布）。");
        put("continentDeepOceanThreshold", "大陆性·深海分界",
                "大陆性轴上的分段边界，只用于群系判定（不影响海陆面积）。");
        put("continentNearOceanThreshold", "大陆性·近海分界", "大陆性轴上的分段边界。");
        put("continentCoastThreshold", "大陆性·沿海分界", "大陆性轴上的分段边界。");
        put("continentTransitionalThreshold", "大陆性·过渡分界", "大陆性轴上的分段边界。");
        put("continentNearInlandThreshold", "大陆性·近岸分界", "大陆性轴上的分段边界。");
        put("continentInlandThreshold", "大陆性·内陆分界", "大陆性轴上的分段边界。");
        put("tempInfluence", "温度影响权重",
                "温度对群系分类的影响程度。0 = 温度不参与分类（全图同温）。");
        put("humidityInfluence", "湿度影响权重", "湿度对群系分类的影响程度。0 = 湿度不参与分类。");
        put("continentInfluence", "大陆性影响权重", "大陆性（距海远近）对群系分类的影响程度。");
        put("latitudeScale", "纬度缩放",
                "温度随 Z 变化的周期（块）。越大 ⇒ 温度带越宽、南北跨度越大。");
        put("tempWarpScale", "温度扰动尺度",
                "温度扰动场的空间尺度（块）。越大 ⇒ 温度在空间上变化越平缓。");
        put("humidityScale", "湿度场尺度", "湿度场的空间尺度（块）。越大 ⇒ 干湿过渡越平缓。");

        // ---- 地形形态 ----
        put("deepOceanLoc", "深海锚点", "大陆性轴上的深海控制点位置（越负 ⇒ 深海占更宽的大陆性区间）。");
        put("shelfLoc", "大陆架锚点", "大陆性轴上的大陆架外缘位置。");
        put("shallowLoc", "浅海锚点", "大陆性轴上的浅海位置。");
        put("deepOceanDepth", "深海深度", "深海的海床高度（e 单位，负值）。越负 ⇒ 洋盆越深。");
        put("shelfDepth", "大陆架深度", "大陆架的海床高度（e 单位）。");
        put("shallowDepth", "浅海深度", "浅海的海床高度（e 单位）。越深 ⇒ 近岸带越早变蓝。");
        put("deepOceanDeriv", "深海斜率", "深海样条控制点导数（MC 会按段宽缩放）。");
        put("shelfDeriv", "大陆架斜率", "大陆架控制点导数：越大 ⇒ 陆坡越陡。");
        put("shallowDeriv", "浅海斜率", "浅海控制点导数：控制浅水带是斜坡还是平台。");
        put("coastDeriv", "海岸斜率", "海岸控制点导数。");
        put("oceanLo", "海洋下限", "「海洋」类型的高度区间下界（类型着色/分类用）。");
        put("oceanHi", "海洋上限", "「海洋」类型的高度区间上界。");
        put("deepOceanLo", "深海下限", "「深海」类型的高度区间下界。");
        put("deepOceanHi", "深海上限", "「深海」类型的高度区间上界。");
        put("shelfLo", "大陆架下限", "「大陆架」类型的高度区间下界。");
        put("shelfHi", "大陆架上限", "「大陆架」类型的高度区间上界。");
        put("subRidgeLo", "洋中脊下限", "「洋中脊」类型的高度区间下界。");
        put("subRidgeHi", "洋中脊上限", "「洋中脊」类型的高度区间上界。");
        put("seamountLo", "海山下限", "「海山」类型的高度区间下界。");
        put("seamountHi", "海山上限", "「海山」类型的高度区间上界。");
        put("lakeLo", "湖泊下限", "「湖泊」类型的高度区间下界。");
        put("lakeHi", "湖泊上限", "「湖泊」类型的高度区间上界。");
        put("riverLo", "河流下限", "「河流」类型的高度区间下界。");
        put("riverHi", "河流上限", "「河流」类型的高度区间上界。");
        put("snowLine", "雪线海拔", "积雪起始的海拔（e 单位），还会被温度与湿度耦合修正。越低 ⇒ 雪线越低、雪覆盖越多。");
        put("snowLatitudeInfluence", "雪线温度耦合", "温度对雪线的抬升幅度：暖区雪线更高。");
        put("snowHumidityInfluence", "雪线湿度耦合", "湿度对雪线的修正：干燥 → 雪线上移，湿润 → 下移。");
        put("splineOuterLoc0", "外层样条 0", "外层（大陆性）样条第 1 个控制点位置（深海侧）。");
        put("splineOuterLoc1", "外层样条 1", "外层样条第 2 个控制点位置（大陆架侧）。");
        put("splineOuterLoc2", "外层样条 2", "外层样条第 3 个控制点位置（浅海侧）。");
        put("splineOuterLoc3", "外层样条 3", "外层样条第 4 个控制点位置（海岸）。");
        put("splineOuterLoc4", "外层样条 4", "外层样条第 5 个控制点位置（近岸陆地）。");
        put("splineOuterLoc5", "外层样条 5", "外层样条第 6 个控制点位置（内陆）。");
        put("splineOuterLoc6", "外层样条 6", "外层样条第 7 个控制点位置（深远内陆）。");
        put("plainHiVal0", "平原上限", "平原类型的陆地 e 上限（参与 maxLandHi）。");
        put("hillsHiVal0", "丘陵上限", "丘陵类型的陆地 e 上限（参与 maxLandHi）。");
        put("mountHiVal0", "山脉上限",
                "山脉类型的陆地 e 上限。它同时是 maxLandHi 的决定项 ⇒\n"
                + "直接决定侵蚀 softCap 的启动阈值；压得太低会提前压缩高峰。");
        put("platHiVal0", "高原上限", "高原类型的陆地 e 上限（参与 maxLandHi）。");
        put("basinHiVal0", "盆地上限", "盆地类型的陆地 e 上限（参与 maxLandHi）。");

        // ---- 侵蚀与河流 ----
        put("erosionEnabled", "粒子侵蚀",
                "本地粒子侵蚀总开关。关闭 ⇒ 地形只剩基础层，没有沟壑与冲刷细节。");
        put("erosionStrength", "侵蚀强度",
                "侵蚀增量的总倍率。调大 ⇒ 沟谷更深、起伏更碎；0 = 等效关闭。");
        put("erosionYieldToRiver", "侵蚀让位于河床",
                "河床按到河心距离衰减侵蚀增量，使河床与计划水面同源。\n"
                + "关闭 ⇒ 回到「河床全量跟随侵蚀」，容易出现阶梯状河道与干滩。");
        put("erosionXSEnabled", "细纹理微侵蚀",
                "额外的十字微笔刷层，在陡坡加极细纹理。\n"
                + "实测会加剧 chunk 边界的脊状伪影，默认关闭。");
        put("erosionDropsMul", "水滴数量倍率",
                "每轮撒下的液滴数倍率。★ 这是【预览提速】手段：调小 ⇒ 预览/加载明显更快，代价是沟壑细节变粗。");
        put("erosionErodeMul", "侵蚀速率倍率",
                "单个液滴的刻蚀速率倍率。调大 ⇒ 同样水量刻得更深。");
        put("erosionCascadeStrength", "级联塌落强度",
                "侵蚀后把高处物质向最低邻居转移的强度，用于平顺河床底部。0 = 关闭。");
        put("erosionMomentumTransfer", "动量正反馈",
                "液滴沿下游动量场自我加速（河流自我增强）。0 = 关闭，正值越强 ⇒ 主河道越明显。");
        put("erosionIterations", "迭代轮数",
                "每轮重撒全部液滴并平滑场。轮数越多 ⇒ 河道渐进加深成型，代价是耗时成正比上升。");
        put("erosionLrate", "场平滑率",
                "每轮把轨迹按多少比例并入稳态场。越大 ⇒ 场更平滑（河道更宽更糊）。");
        put("riverEnabled", "河流",
                "河流（水文模型：汇流场派生河网 + 距离场雕刻）总开关。关闭 ⇒ 陆地无河道与水面。");

        // ---- 洞穴与矿物 ----
        // ⚠ 洞穴与矿物两组在页面上由 CavePanel / OrePanel 的<b>专门 UI</b>渲染
        //   （档位按钮 + 开关行 + 判定摘要），不是滑块行。这里给说明是为了：
        //   ① 悬停文案统一走 KeyText.tooltip；② ConfigPanelCoverageProbe 能核对覆盖完整。
        put("cavePreset", "洞穴档位",
                "一键切换：关闭 / 精简 / 拟真 / 接近原版。手动改下面任一开关后自动变「自定义」。");
        put("caveEnabled", "洞穴总开关", "关闭后地下不再生成任何洞穴。");
        put("caveTunnelEnabled", "隧道分量",
                "细长的曲折通道，把分散的洞穴连接起来。关闭后洞穴之间不再连通。");
        put("caveChamberEnabled", "空腔分量",
                "能走进去、站得起来的较大空间。关闭后只剩细隧道，可能难以通行。");
        put("caveLayerEnabled", "层调制",
                "把高大的空腔压成一层层水平洞穴，避免挖出贯通上下的竖井。");
        put("caveDensityMul", "洞穴密度倍率", "乘在分量阈值上：数值越大，洞穴越多、越粗。");
        put("caveSurfaceLid", "洞顶保护厚度",
                "洞顶与地表之间至少保留的厚度（格）。设为 0 时洞穴可以破开地表形成洞口。");
        put("caveDepthMin", "最浅深度", "洞穴带起点（距地表格数）。");
        put("caveDepthMax", "最深深度", "洞穴带终点（距地表格数）。");
        put("caveLithoGating", "岩性门控",
                "洞穴只在容易溶蚀的岩石（如石灰岩）中发育，花岗岩等坚硬岩石中很少。");
        put("caveBiomesEnabled", "地下洞穴群系",
                "洞穴内出现钟乳石与石笋（滴水石洞）；湿润气候下还有苔藓与藤蔓（繁茂洞穴）。");
        put("oreVeinsEnabled", "自研地质矿脉",
                "按「宿主岩 + 深度带」成矿：煤在砂岩/页岩、钻石在片麻岩深处。叠加在原版矿之上。");
        put("oreOverrideVanilla", "接管原版矿",
                "移除原版金属矿，地下矿改由自研地质矿脉独占。只影响原版矿，其它模组的矿不受影响。");

        // ---- 诊断 ----
        put("worldgenProfilerEnabled", "世界生成全流程诊断",
                "把每个阶段（填地形 / 装饰 / 挖洞 / 结构）的耗时打进日志（[WGP]），\n"
                + "并汇总到运行目录的 geogenesis-wgp.txt。默认关闭 ⇒ 零开销、产出逐位不变。");
        put("worldgenProfilerEveryChunks", "诊断汇总间隔",
                "每多少块打一次滚动汇总。0 = 只在世界卸载时打总计。");
        put("elevHigh", "高海拔阈值（只读诊断）",
                "⚠ 生产地形链不读它：src/main 内唯一读取点是一个带 main 的量化诊断类。\n"
                + "改这个值【不会】改变地形。保留仅为诊断对照。");
        put("reliefHigh", "高起伏阈值（只读诊断）",
                "⚠ 与「高海拔阈值」同：只被诊断类读取，改它不会改变地形。");

        // ---- 显示与色带（仅预览标尺）----
        put("seaLevel", "海平面（预览标尺）",
                "⚠ 仅预览标尺：游戏内海平面由生成器的编译期常量决定，本键不驱动世界。");
        put("minY", "世界下限（预览标尺）",
                "⚠ 仅预览标尺：游戏内世界下限由生成器的编译期常量决定，本键不驱动世界。");
        put("maxY", "世界上限（预览标尺）",
                "⚠ 仅预览标尺：游戏内世界上限由生成器的编译期常量决定，本键不驱动世界。");
    }

    /**
     * 旧算法（LEGACY_ONLY）键的短名与说明 —— <b>按命名规律生成</b>，不是逐条手写。
     *
     * <h3>为什么用生成而不是 70 条手写字符串</h3>
     * <p>这批键的名字本身就是结构化的：{@code {类型}{Lo|Hi}{Loc|Val|Deriv}{0|1}}，
     * 例如 {@code mountHiVal1} = 「山脉 / hi 样条 / 端点值 / 位置 1」。
     * 手抄 70 条只会引入新的抄写错误（本类存在的全部理由就是消灭这类错误）；
     * 用命名规律生成则<b>不可能抄错</b>，且新增同类键自动获得说明。</p>
     *
     * <p>语义（本轮实测，不是推测）：生产只在内层样条的<b>端点</b>取值
     * （{@code noiseValue ∈ {0,1}}），而 2 点 Hermite 在端点处 {@code h10(0)=h11(1)=0}
     * ⇒ {@code Loc*} 与 {@code Deriv*} 数学上零贡献；只有 {@code Val0/Val1} 参与 legacy 高度。</p>
     */
    private static final class LegacyText {

        private static final java.util.Map<String, String> TYPE_NAMES = java.util.Map.ofEntries(
                java.util.Map.entry("plain", "平原"),
                java.util.Map.entry("hills", "丘陵"),
                java.util.Map.entry("mount", "山脉"),
                java.util.Map.entry("plat", "高原"),
                java.util.Map.entry("basin", "盆地"));

        /** 解析 {@code {type}{band}{part}{idx}}；不是该形状返回 null。 */
        private static String[] parse(String key) {
            for (String type : TYPE_NAMES.keySet()) {
                if (!key.startsWith(type)) continue;
                String rest = key.substring(type.length());
                if (rest.length() < 2) continue;
                String band = rest.startsWith("Lo") ? "Lo" : rest.startsWith("Hi") ? "Hi" : null;
                if (band == null) continue;
                rest = rest.substring(2);
                String part;
                if (rest.startsWith("Loc")) part = "Loc";
                else if (rest.startsWith("Val")) part = "Val";
                else if (rest.startsWith("Deriv")) part = "Deriv";
                else continue;
                String idx = rest.substring(part.length());
                if (!idx.equals("0") && !idx.equals("1")) continue;
                return new String[]{type, band, part, idx};
            }
            return null;
        }

        static String label(String key) {
            String[] p = parse(key);
            if (p == null) return null;
            String type = TYPE_NAMES.get(p[0]);
            String band = p[1].equals("Lo") ? "下界" : "上界";
            String part = switch (p[2]) {
                case "Loc" -> "位置";
                case "Val" -> "端点值";
                default -> "导数";
            };
            return type + band + part + p[3];
        }

        static String note(String key) {
            String[] p = parse(key);
            if (p != null) {
                String part = switch (p[2]) {
                    case "Loc" -> "样条控制点位置。生产只在端点求值 ⇒ 数学上零贡献，改它不会改变任何产出。";
                    case "Val" -> "样条端点值 —— 这是真正参与高度计算的那一项。";
                    default -> "样条控制点导数。生产只在端点求值 ⇒ 数学上零贡献，改它不会改变任何产出。";
                };
                return "旧算法专用（仅旧存档生效）。" + part;
            }
            return LEGACY_MISC.get(key);
        }

        /** 不是样条形状的 legacy 键（手写，数量少）。 */
        private static final java.util.Map<String, String> LEGACY_MISC = java.util.Map.ofEntries(
                java.util.Map.entry("continentThreshold", "旧算法专用（仅旧存档生效）。海陆参考阈值：只被旧链的 isLand 判定读。"),
                java.util.Map.entry("continentBias", "旧算法专用（仅旧存档生效）。海陆占比偏置：只在旧链从大陆性里减掉。"),
                java.util.Map.entry("coastLoc", "旧算法专用（仅旧存档生效）。海岸控制点位置（旧链路的海岸三角窗中心）。"),
                java.util.Map.entry("oceanFadeStart", "旧算法专用（仅旧存档生效）。海洋淡出起点：旧链两阶段混合的海侧边界。"),
                java.util.Map.entry("landRampEnd", "旧算法专用（仅旧存档生效）。陆地高度终点：旧链海滩带宽度由它决定。"),
                java.util.Map.entry("seabedDetail", "旧算法专用（仅旧存档生效）。海床细节振幅：只被旧链的海床噪声读。"),
                java.util.Map.entry("oceanDepthFactor", "旧算法专用（仅旧存档生效）。海洋深度乘数：只在旧链乘到海床上。"),
                java.util.Map.entry("beltReliefAmp", "旧算法专用（仅旧存档生效）。造山带起伏振幅：只被旧链的地形合成读。"),
                java.util.Map.entry("basinBase", "旧算法专用（仅旧存档生效）。盆地基底高度：只被旧链的盆地噪声读。"),
                java.util.Map.entry("cAffinityStrength", "旧算法专用（仅旧存档生效）。大陆性语义亲和度：只被旧链的类型权重调制读。"),
                java.util.Map.entry("coastlineWarpAmp", "旧算法专用（仅旧存档生效）。海岸线位移振幅：只被旧链的海岸线场读。"),
                java.util.Map.entry("coastlineWarpScale", "旧算法专用（仅旧存档生效）。海岸线位移基频尺度。"),
                java.util.Map.entry("coastlineWarpOctaves", "旧算法专用（仅旧存档生效）。海岸线位移倍频数。"),
                java.util.Map.entry("coastlineWarpLacunarity", "旧算法专用（仅旧存档生效）。海岸线位移频率倍增。"),
                java.util.Map.entry("coastlineWarpPersistence", "旧算法专用（仅旧存档生效）。海岸线位移振幅衰减。"));

        private LegacyText() { }
    }

    private KeyText() { }
}
