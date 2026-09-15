package com.geogenesis.worldgen.cave;

/**
 * 洞穴配置（<b>零 Minecraft 依赖的纯数据载体 + 档位解析纯函数</b>）。
 *
 * <h2>设计目标：游戏性优先，可一键开关</h2>
 * <p>用户的要求：「<i>不需要完全按照地质学去设计，毕竟我们这是 MC 游戏，要考虑游戏性。
 * 比如洞穴相关的可以开关配置，想要模拟现实洞穴就打开，不需要就关闭回默认原版。</i>」</p>
 *
 * <p>据此定了三条原则：</p>
 * <ol>
 *   <li><b>档位优先于旋钮</b>：绝大多数玩家只想选"拟真 / 接近原版 / 精简 / 关闭"，
 *       而不是调 20 个数值。故提供 {@link Preset} 一键档位，旋钮只在
 *       {@link Preset#CUSTOM} 下才需要关心。</li>
 *   <li><b>⚠ 关于"回退原版"的技术现实（必须讲清楚）</b>：本项目是<b>自定义
 *       {@code ChunkGenerator}</b>、<b>没有 {@code NoiseSettings}/{@code NoiseChunk}</b>
 *       ⇒ 原版 carver（{@code CaveFeature}/{@code WorldCarver}）<b>在物理上无法调用</b>
 *       （这正是当初自研洞穴的原因）。故 {@link Preset#OFF} 的语义只能是
 *       <b>"地下无洞穴"</b> —— 与参考项目 RTG 的 {@code useCaves=false} 完全一致。
 *       作为补偿，{@link Preset#VANILLA_LIKE} 提供"更接近原版观感"的参数档
 *       （洞更大更圆、岩性不门控、无层状切割）。</li>
 *   <li><b>拟真是一档、不是默认</b>：{@link Preset#REALISTIC} 会启用岩性门控
 *       （石灰岩溶洞大、花岗岩几乎无洞）与层调制（水平层状洞穴）；二者都是
 *       <b>本项目独创</b>（三个参考项目均无），但它们是"地质拟真"而非"好玩"，
 *       故与 {@link Preset#VANILLA_LIKE} 并列，由玩家选择。</li>
 * </ol>
 *
 * <h2>参考项目对照（哪些是抄的、哪些是自研）</h2>
 * <table border="1">
 *   <caption>三个参考项目的洞穴配置能力</caption>
 *   <tr><th>项目</th><th>配置项数</th><th>开关</th><th>预设</th></tr>
 *   <tr><td>TerraForged-0.3.x</td><td>0（纯 datapack JSON）</td><td>无</td><td>无</td></tr>
 *   <tr><td>FreeTerraForged-1.21.1</td><td>10（{@code CaveSettings}）</td>
 *       <td>隐式（{@code probability=0} 即关）</td><td>8 个</td></tr>
 *   <tr><td>RTG-Community</td><td>5</td><td><b>{@code useCaves}/{@code useRavines}</b></td><td>无</td></tr>
 * </table>
 * <p>本项目借鉴：① <b>RTG 的直白布尔开关</b>（{@code useCaves=false} → 直接不生成）；
 * ② <b>FreeTF 的档位思路</b>（其 8 个预设里 {@code CommunityPreset1} 是唯一"拟真向"）。
 * <b>三个项目都没有</b>"关闭自研洞穴 ⇒ 自动回退原版 carver"的双实现 —— 本项目同样不做
 * （技术不可行，见上）。</p>
 *
 * <h2>可验证性</h2>
 * <p>本类是纯数据 + 纯函数 ⇒ {@code CaveBiomeProbe}/{@code CaveShapeProbe} 可离线
 * 校验"档位解析是否正确"（如 {@code OFF} 必须使洞穴全无、{@code REALISTIC} 的
 * 岩性门控必须生效）。</p>
 */
public final class CaveConfig {

    /** 洞穴档位（一键切换，游戏性优先）。 */
    public enum Preset {
        /**
         * 关闭：地下**无任何洞穴**。
         *
         * <p>⚠ 这是"关闭"，<b>不是"回退原版"</b> —— 本项目自定义
         * {@code ChunkGenerator} 无 {@code NoiseSettings}，原版 carver 无法调用。
         * 语义与 RTG 的 {@code useCaves=false} 一致（挖下去是实心岩）。</p>
         */
        OFF,
        /**
         * 接近原版：洞更大更圆、<b>不做岩性门控</b>、<b>不做层调制</b>。
         *
         * <p>用于"我想要原版那种感觉，不要地质层状"的玩家。因为无法真调原版 carver，
         * 这里用现有 3D 噪声换个参数档去<b>近似</b>：阈值放大 ⇒ 洞更粗；
         * 关掉层调制 ⇒ 不出现"水平层带"（原版 cheese 洞没有明显层理）。</p>
         */
        VANILLA_LIKE,
        /**
         * 精简：只保留细隧道（关掉空腔），洞少而细。
         *
         * <p>用途：① 性能受限/低配服务器；② 喜欢"地下紧凑、不空旷"的玩家；
         * ③ 与"拟真"对比时作为低密度基准。</p>
         */
        MINIMAL,
        /**
         * 拟真（默认）：岩性门控（石灰岩溶洞大 / 花岗岩几乎无洞）+ 层调制
         * （沿层理发育的水平洞穴）+ 保留地表保护。
         *
         * <p>这是本项目的"完整地质版"，三个参考项目<b>都没有</b>这两个耦合。</p>
         */
        REALISTIC,
        /**
         * 自定义：各旋钮按 {@link CaveConfig} 的字段值直接生效（档位不参与缩放）。
         */
        CUSTOM
    }

    // ===================== 字段（纯数据，全部 final）=====================

    /** 是否启用洞穴（false ⇒ 完全不挖，等价 {@link Preset#OFF}）。 */
    public final boolean enabled;
    /** 档位。 */
    public final Preset preset;

    // ---- 分量开关（对应 CaveShape 的 F_TUNNEL / F_CAVERN / 层调制）----
    /** 隧道分量（蜿蜒细管，负责"连通"）。 */
    public final boolean tunnelEnabled;
    /** 空腔分量（可站立的洞厅，负责"能走"）。 */
    public final boolean chamberEnabled;
    /** 层调制（把大空腔切成层状，防止竖直贯穿）。 */
    public final boolean layerEnabled;

    // ---- 主要旋钮（游戏性导向，只暴露真正影响观感的）----
    /** 洞穴密度倍率（乘在分量阈值上）：越大洞越粗越密。 */
    public final double densityMul;
    /**
     * 洞顶保护厚度（block）：洞顶至少低于地表此值。
     *
     * <p>设为 0 ⇒ <b>允许洞穴破地表形成入口</b>（探索体验的关键，
     * 参考 TF 的 {@code BREACH_THRESHOLD} 思路）。默认 6 是保守值。</p>
     */
    public final int surfaceLid;
    /** 洞穴带最浅深度（距地表，block）。 */
    public final int depthMin;
    /** 洞穴带最深深度（距地表，block）。 */
    public final int depthMax;

    // ---- 拟真耦合（仅 REALISTIC 默认开）----
    /** 岩性门控（石灰岩溶洞大 / 花岗岩几乎无洞）。 */
    public final boolean lithoGating;
    /** 地下洞穴群系（滴水石洞 / 繁茂洞穴）。 */
    public final boolean caveBiomes;

    private CaveConfig(boolean enabled, Preset preset, boolean tunnelEnabled,
                       boolean chamberEnabled, boolean layerEnabled, double densityMul,
                       int surfaceLid, int depthMin, int depthMax,
                       boolean lithoGating, boolean caveBiomes) {
        this.enabled = enabled;
        this.preset = preset;
        this.tunnelEnabled = tunnelEnabled;
        this.chamberEnabled = chamberEnabled;
        this.layerEnabled = layerEnabled;
        this.densityMul = densityMul;
        this.surfaceLid = surfaceLid;
        this.depthMin = depthMin;
        this.depthMax = depthMax;
        this.lithoGating = lithoGating;
        this.caveBiomes = caveBiomes;
    }

    // ===================== 档位解析（纯函数）=====================

    /**
     * 由档位解析出完整配置（<b>纯函数</b>，探针可直接校验）。
     *
     * <h3>档位参数依据</h3>
     * <ul>
     *   <li><b>OFF</b>：{@code enabled=false} ⇒ 其余字段无意义（但仍给合法值）。</li>
     *   <li><b>VANILLA_LIKE</b>：{@code densityMul=1.5}（洞更粗）、
     *       {@code layerEnabled=false}（不出现层理）、{@code lithoGating=false}
     *       （岩性一视同仁）。依据：原版 cheese/spaghetti 洞无层理、也不看岩性。</li>
     *   <li><b>REALISTIC</b>：全部拟真耦合开启（本项目独创）。</li>
     *   <li><b>CUSTOM</b>：不在此解析（由调用方给全字段）。</li>
     * </ul>
     */
    public static CaveConfig fromPreset(Preset preset) {
        return switch (preset) {
            case OFF -> new CaveConfig(false, Preset.OFF,
                    false, false, false, 1.0, 6, 8, 120, false, false);
            case VANILLA_LIKE -> new CaveConfig(true, Preset.VANILLA_LIKE,
                    true, true, false, 1.5, 6, 6, 140, false, true);
            // 精简：关空腔（只留细隧道）+ 密度降到 0.5 ⇒ 洞少而细。
            case MINIMAL -> new CaveConfig(true, Preset.MINIMAL,
                    true, false, true, 0.5, 6, 8, 90, false, true);
            case REALISTIC -> new CaveConfig(true, Preset.REALISTIC,
                    true, true, true, 1.0, 6, 8, 120, true, true);
            case CUSTOM -> new CaveConfig(true, Preset.CUSTOM,
                    true, true, true, 1.0, 6, 8, 120, true, true);
        };
    }

    /** 自定义构造（供配置界面/诊断使用）。 */
    public static CaveConfig custom(boolean enabled, boolean tunnelEnabled,
                                    boolean chamberEnabled, boolean layerEnabled,
                                    double densityMul, int surfaceLid,
                                    int depthMin, int depthMax,
                                    boolean lithoGating, boolean caveBiomes) {
        return new CaveConfig(enabled, Preset.CUSTOM, tunnelEnabled, chamberEnabled,
                layerEnabled, densityMul, surfaceLid, depthMin, depthMax,
                lithoGating, caveBiomes);
    }

    /** 生产默认：拟真档。 */
    public static final CaveConfig DEFAULT = fromPreset(Preset.REALISTIC);

    /** 该配置是否真的会挖洞（总开关 + 至少一个分量开）。 */
    public boolean carvesAnything() {
        return enabled && (tunnelEnabled || chamberEnabled);
    }

    @Override
    public String toString() {
        return "CaveConfig{" + preset + " enabled=" + enabled
                + " tunnel=" + tunnelEnabled + " chamber=" + chamberEnabled
                + " layer=" + layerEnabled + " densityMul=" + densityMul
                + " lid=" + surfaceLid + " depth=[" + depthMin + "," + depthMax + "]"
                + " litho=" + lithoGating + " biomes=" + caveBiomes + '}';
    }
}
