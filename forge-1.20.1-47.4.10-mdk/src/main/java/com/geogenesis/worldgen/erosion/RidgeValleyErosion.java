package com.geogenesis.worldgen.erosion;

import com.geogenesis.config.GeoGenesisConfig;

/**
 * 脊-谷条纹侵蚀（骨架层）—— 梯度对齐条纹滤镜，纯局部算子。
 *
 * <p><b>算法溯源</b>：移植自 Rune Skovbo Johansen "Fast and Gorgeous Erosion Filter" (2026-03) 与
 * Luke Mitchell 的 Burst C# 实现（lpmitchell/AdvancedTerrainErosion，MPL-2.0 + MIT）。
 * 本文件为<b>独立重写</b>，仅参考其算法结构（PhacelleNoise / ErosionFilter / Hash2），并标注出处，
 * 未直接拷贝源文件。算法机制详见 {@code docs/05-分析诊断/03-脊谷条纹侵蚀骨架-ridge-valley-erosion.md}。</p>
 *
 * <p><b>作用</b>：在低分辨率地形网格上生成「大山脊基本型」骨架——沿地形梯度挤出脊-谷条纹网络，
 * 把圆包状噪声切成多峰山脊；随后由 {@link ErosionEngine#runErosionOnFlat} 的粒子侵蚀做细节打磨。
 * 本类<b>纯数学工具、不依赖 CellGenerator</b>：输入未平滑的低分辨率高度网格，输出同维度高度增量。</p>
 *
 * <p><b>无缝原理</b>：每点独立 evaluate，条纹方向按全局对齐细胞的确定性 pivot 旋转 + 邻细胞钟形权重混合 →
 * 同一世界坐标的 coarseDelta 与任意 tile 算出的值一致，跨 tile 天然无接缝（复用现有 Tile Context Chain）。</p>
 */
public final class RidgeValleyErosion {

    private RidgeValleyErosion() {}

    private static final float TAU = (float) (2.0 * Math.PI);

    // ===== 2026-08-10 高原平顶假峰修复：局部窗口抬升衰减 =====
    // 根因：eLand 被类型加权上限 blendHi 夹成平顶（PLATEAU hi=0.71，平台≈0.49），但骨架是
    // "纯高度场"工具不知道类型上限——SLOPE_BOOST=8 放大平台微丘坡度 → combiMask 触发 →
    // 抬升 delta 把 e 顶出 blendHi → 平台面上凸出"抬升一段然后下降"的假山脊峰。
    // 修复：抬升（delta>0）按"局部窗口最高点余量"衰减——局部已高于周围（平顶/丘顶/山顶）→
    // 抬升衰减至 0（平台不长峰、山脉山顶不再被无脑抬高成平顶）；坡面中段 headroom 大 → 脊线
    // 照常成形并在接近坡顶处自然收束。下切（delta<0）完全不衰减（河谷/沟壑照常，不违反
    // 2026-08-08"侵蚀无类型/高度限制"决策——抬升衰减只是上限物理化，非侵蚀限制）。
    // 局部窗口（16 块 < 条纹半周期 25-50 块）→ 不产生全局高度等值线（区别于已否决的 fadeTarget）。
    /** 局部窗口半径（骨架网格格数，spacing=2 → 8 格 = 16 块） */
    private static final int LIFT_WINDOW_R = 8;
    /** ★ 2026-08-12 修复：headroom 阈值恢复 0.05（原 2026-08-10 语义）——
     *  塑性恢复靠"平台区分"（PLATEAU_THRESHOLD），不再靠收紧 headroom。
     *  山脊线 headroom≈0 但窗口落差大 → 不衰减；平顶 headroom≈0 且窗口全平 → 衰减。 */
    private static final float LIFT_HEADROOM_FULL = 0.05f;
    /** ★ 2026-08-12 平台区分阈值：窗口最高点与均值差 < 0.03e（≈7.6 块）判定为"平台"（平顶/高原），
     *  此时抬升衰减（2026-08-10 高原平顶修复目的）；山脊线窗口落差 >0.03e → 不衰减（塑性恢复）。 */
    private static final float PLATEAU_THRESHOLD = 0.03f;
    /** ★ 2026-08-12 fadeTarget 项单独缩放（fadeHeight=true 时）：
     *   fadeTarget 是"峰顶/谷底淡出目标"，原版 catto strength 仅 0.02-0.05 所以直接使用；
     *   我们 strength=0.8（条纹时代调参，fadeTarget=0）→ fadeTarget 项必须压缩 10 倍才不爆炸
     *   （低地 fadeTarget=0.17·0.8·2.5≈0.34e 抬升 → ×0.1 后 0.034e 温和）。
     *   条纹（gullX·gw）不受影响——沟壑/脊线雕刻保持。 */
    private static final float FADE_TARGET_SCALE = 0.1f;

    // ===== 骨架层配置（从 GeoGenesisConfig 读取覆盖；stylistic 参数用代码常量初值）=====
    public static class RidgeConfig {
        public boolean enabled = true;
        /** fadeTarget 参考面（对称中点，e 单位）。默认 0.15 匹配陆地中值（PLAIN meanElev≈0.154）；
         *  旧固定 0.25 在低地世界（eLand 0.10~0.18）恒负 → 平坦区被骨架整体下削（DIAG-EXT 全负 delta 根因）。 */
        public float landRef = 0.15f;
        /** ★ 2026-08-12 恢复原版 fadeTarget 高度映射开关（catto Erosion.java: fadeTarget=clamp(h/0.6,-1,1)）：
         *  true=谷黑峰白高度域塑形（原版比例，塑性更强）；false=固定 0（2026-08-06 用户决策，
         *  消除人工等值线纹理）。默认 false——等值线风险可回退，用户 A/B 实测后定案。 */
        public boolean fadeHeight = false;
        public float strength = 0.8f;         // 单 octave 侵蚀强度（2026-08-12: 0.12→0.8 塑性增强，对齐 GeoGenesisConfig 默认）
        public float cellWorldSize = 100f;    // 骨架特征尺度（世界块）= 条纹细胞世界尺寸
        public float stripeFreq = 1.2f;       // 细胞内条纹频率（sideDir 幅度，↑密度）
        public int octaves = 4;               // gully 层级（主脊+次级脊+细沟，spacing=2 下不混叠）
        public float gullyWeight = 0.7f;      // 坡度累积权重（0.5→0.7：正弦 U 形谷主导，抑制 fadeTarget 均匀深挖的 V 尖沟）
        public float detail = 1.0f;           // 堆叠淡出幂次（PowInv power，↓更密小沟）
        public float normalization = 0.5f;    // 条纹幅度归一化度
        public float gain = 0.6f;             // 每 octave 强度衰减
        public float lacunarity = 2.0f;       // 每 octave 频率倍增
        public float[] rounding = {0.1f, 0.12f, 0.1f, 2.0f};   // [1]=crease 谷底圆化（0→0.12，U 形谷，用户定 0.12 比 0.15 合适）
        public float[] onset = {0.9f, 1.25f, 2.8f, 1.5f};      // [0]=combiMask 主阈值（1.25→0.9，谷壁进入削切）
        public float[] assumedSlope = {0.7f, 1.0f};
        public int seed = 1337;
        /** 水平缩放（块→wu 映射）：梯度换算用。骨架标定的坡度是"每块"（物理坡度），
         *  2026-08-10 wu 化后骨架网格间距为 wu → gradX/gradZ 必须 ×hs 还原物理坡度，
         *  否则 HS>1 时坡度被除以 hs → combiMask 触发弱 → 骨架条纹削弱（用户对比发现漂移）。 */
        public float horizontalScale = 1f;

        /** 从 Forge 配置读取骨架参数（字段缺失时回退默认值）。 */
        public static RidgeConfig fromConfig(GeoGenesisConfig cfg) {
            RidgeConfig c = new RidgeConfig();
            if (cfg != null) {
                c.enabled = cfgBool(cfg.erosionRidgeEnabled, true);
                c.landRef = (float) cfgDbl(cfg.erosionRidgeLandRef, 0.15);
                // ★ 2026-08-12 回退默认 0.12→0.8 对齐 GeoGenesisConfig 默认（配置铁律：三处一致）
                c.strength = (float) cfgDbl(cfg.erosionRidgeStrength, 0.8);
                // ★ 2026-08-12 fadeHeight 开关（回退默认 false = 2026-08-06 用户决策）
                c.fadeHeight = cfgBool(cfg.erosionRidgeFadeHeight, false);
                c.cellWorldSize = (float) cfgDbl(cfg.erosionRidgeScale, 100.0);
                c.stripeFreq = (float) cfgDbl(cfg.erosionRidgeCellScale, 1.2);
                c.octaves = cfgInt(cfg.erosionRidgeOctaves, 4);
                c.gullyWeight = (float) cfgDbl(cfg.erosionRidgeGullyWeight, 0.7);
                // 脊线软硬（0=尖锐 V 形，0.5=默认，1=圆滑 U 形）
                double softness = cfgDbl(cfg.erosionRidgeRounding, 0.5);
                c.rounding[0] = (float) (0.02 + softness * 0.18);  // ridge rounding
                c.rounding[1] = (float) (softness * 0.24);          // crease (valley) rounding（softness 0.5→0.12，U 形谷，用户定）
                // 细节密度（high=主脊干净，low=满布小沟）
                c.detail = (float) cfgDbl(cfg.erosionRidgeDetail, 1.0);
                c.horizontalScale = (float) cfgDbl(cfg.horizontalScale, 1.0);
            }
            return c;
        }
    }

    /**
     * 在未平滑的低分辨率网格 rawLowRes 上计算粗侵蚀骨架 delta（同维度返回）。
     *
     * @param rawLowRes 未平滑地形高度（e 场），维度 extLR×extLR
     * @param spacing   粗采世界间距（块）
     * @param startX/startZ 网格 (0,0) 对应的世界坐标
     * @param seaE      海平面 e 值（海洋侧乘 landMask 抑制，避免海床造假山脊）
     * @param cfg       骨架配置
     * @return 与 rawLowRes 同维度的高度增量 delta
     */
    public static float[][] computeCoarseDelta(float[][] rawLowRes, int extLR,
                                                int spacing, int startX, int startZ,
                                                float seaE, RidgeConfig cfg) {
        float[][] delta = new float[extLR][extLR];
        float cellGridFreq = 1.0f / Math.max(1f, cfg.cellWorldSize); // 条纹细胞世界尺寸 = cellWorldSize
        // 【全局对称参考面】源码 fadeTarget = inverse_lerp(valleyAlt, peakAlt, h)*2-1，
        // 跨真实高程对称映射（谷=-1、峰=+1），脊-谷下切/抬升幅度对称。
        // 本项目 h=terrainE∈[0,0.9]，用**配置化中点 cfg.landRef（默认 0.15）** + 半幅 LAND_HALF=0.25：
        //   (h-landRef)/0.25 → e=0(谷底)→-0.6, e=landRef→0, e=landRef+0.25→+1，陆地全覆盖且对称。
        //   - 全局常数（所有 tile 一致）→ fadeTarget 无 tile 边界跳变 → 无缝。
        //   - 低地 fadeTarget<0 → valley rounding 生效（尖谷）；高地>0 → ridge rounding（圆脊），山形更自然。
        //   - 2026-08-01：LAND_REF 0.25→cfg.landRef 0.15（低地世界 eLand 0.10~0.18 恒负 → 平坦区整体下削，
        //     DIAG-EXT delta 全负 avg -0.012~-0.028（4~11 块）根因；0.15 与 PLAIN meanElev≈0.154 对齐）。
        final float LAND_HALF = 0.25f;
        for (int tz = 0; tz < extLR; tz++) {
            for (int tx = 0; tx < extLR; tx++) {
                delta[tz][tx] = evaluateCell(rawLowRes, tx, tz, extLR, spacing,
                    startX, startZ, seaE, cfg);
            }
        }
        return delta;
    }

    /**
     * 单点骨架 delta 计算（★ 2026-08-09 提取自 computeCoarseDelta 内层循环，供并行分片复用）。
     * 纯局部计算：gradX/gradZ 只读邻居、erosionFilter 纯函数 → 任意线程安全，输出与串行逐点一致。
     */
    public static float evaluateCell(float[][] rawLowRes, int tx, int tz, int extLR,
                                     int spacing, int startX, int startZ, float seaE, RidgeConfig cfg) {
        float cellGridFreq = 1.0f / Math.max(1f, cfg.cellWorldSize); // 条纹细胞世界尺寸 = cellWorldSize
        float h = rawLowRes[tz][tx];
        float gx = gradX(rawLowRes, tx, tz, extLR, spacing, cfg.horizontalScale);
        float gz = gradZ(rawLowRes, tx, tz, extLR, spacing, cfg.horizontalScale);
        // 峰侧衰减：fadeTarget 正侧（抬升）在峰尖衰减——否则 h>0.5 全部饱和 +1，
        // 峰顶被每 octave 均匀抬升（4 oct × 0.08 ≈ +0.17e）→ 触 delta 限幅/softCap → 平顶。
        // 谷侧（负值）不受影响，山谷照常下切；山体中下部仍抬升成脊线。
        // 窗口收窄到 0.72~0.92（只作用峰尖）：减少 flat 场形态改变，控制液滴 tile 边界差异。
        // 2026-08-06 用户决策（最终版）：fadeTarget 直接归 0。
        // 原因链：fadeTarget 按高度空间变化 → 低地下切量不均匀 → 高程图产生人工等值线纹理
        // （第二张截图的弯曲线）。砍掉正半后只剩下切 → 更明显。完全归 0 后：
        // - delta 纯由 combiMask×gullies 条纹提供（正负交替 = 沟壑+脊线）
        // - 低地无条纹（combiMask≈0 → delta≈0）
        // - 山地有条纹（combiMask 触发）→ 坡度自然控制，无类型/高度约束
        // ★ 2026-08-12 fadeTarget 修复（第四轮，对照 catto Erosion.java + Rune 博客原文）：
        //   博客原文：fadeTarget = inverse_lerp(valleyAlt, peakAlt, h)×2−1 —— fadeTarget 是
        //   "条纹淡出目标"：当坡度≈0（峰顶/谷底）时地形向该目标靠拢（峰抬升/谷下切）。
        //   三轮教训：①零点=landRef+strength 0.8 → 低地爆炸下切（圆坑）；
        //   ②零点=seaE+strength 0.8 → 低地爆炸抬升(+0.15e)；③strength×0.1 后 landRef 零点 →
        //   低地仍下切 -0.018e、山区抬升 7 块无下切。
        //   **根因：平原（坡度≈0）也被 fadeTarget 直接抬升/下切**——原版 strength 小（0.02-0.05）
        //   平原抬升无碍，我们 strength 再小也破坏地形。
        //   定案：fadeTarget ×"起伏门控"（fadeGate）——局部窗口内落差大（峰/谷/山脊，plateau 大）
        //   → fadeTarget 全量塑形；窗口内全平（平原/高原，plateau 小）→ fadeTarget→0（不动地形）。
        //   零点=seaE（谷=-1、峰=+1，对齐 catto h/0.6 语义）。
        float lm = localWindowMax(rawLowRes, tx, tz, extLR, LIFT_WINDOW_R);
        float plateau = lm - localWindowAvg(rawLowRes, tx, tz, extLR, LIFT_WINDOW_R);
        // ★ 2026-08-12 起伏门控：平原（窗口内全平，plateau<0.005）fadeTarget→0（不动地形），
        //   峰/谷/山脊（plateau>0.04）全量塑形。按实测 plateau 分布（0-0.05 集中）标定。
        float fadeGate = smoothstep(0.005f, 0.04f, plateau);
        float fadeTarget = cfg.fadeHeight
            ? Math.max(-1f, Math.min(1f, (h - seaE) / 0.6f)) * fadeGate
            : 0f;
        float hs = (h - cfg.landRef) * 0.5f + 0.5f;
        // 2026-08-06 修复：坡度放大 SLOPE_BOOST × 海岸带保护。
        // SLOPE_BOOST 让真实陡坡进入 combiMask 触发区 → 脊谷条纹成形。
        // 保护语义（用户决策："侵蚀必须入海，不加上海洋不是完整地形"）：
        //   仅海平面附近（|h-seaE|<0.15）抑制坡度放大（防海岸人造弧线）；
        //   内陆与深海（水下海底）同样参与骨架侵蚀 → 海底峡谷/水下河谷。
        float coastDist = Math.abs(h - seaE);
        float landMask = Math.min(1f, coastDist / 0.15f);
        final float SLOPE_BOOST = 8f;
        float gxs = gx * SLOPE_BOOST * landMask, gzs = gz * SLOPE_BOOST * landMask;
        float d = erosionFilter(startX + tx * spacing, startZ + tz * spacing,
                hs, gxs, gzs, fadeTarget, cellGridFreq, cfg);
        // ★ 2026-08-12 修复：LIFT 抬升衰减改"平台区分"——原版衰减杀死所有脊线抬升：
        //   localWindowMax 取窗口最高点，而山脊线本身就是窗口最高 → headroom 恒≈0 →
        //   抬升恒被平滑衰减到 0 → 只有谷下切、脊抬不起（探针：max +0.0088 vs min -0.0389 不对称）。
        //   修复：同时要求"平台"（窗口最高点与均值差 < PLATEAU_THRESHOLD）才衰减——
        //   山脊线 headroom≈0 但窗口落差大（plateau 大）→ 不衰减，脊线恢复抬升；
        //   高原平顶 headroom≈0 且窗口内全平（plateau 小）→ 仍衰减（2026-08-10 原目的保留）。
        if (d > 0f) {
            float headroom = lm - h;
            if (headroom < LIFT_HEADROOM_FULL) {
                if (plateau < PLATEAU_THRESHOLD) {
                    float t = headroom / LIFT_HEADROOM_FULL; // 0..1（>1 已跳过）
                    d *= t * t * (3f - 2f * t);
                }
            }
        }
        // 海岸带保护：仅海平面附近抑制 delta（防弧线），内陆/深海全幅（侵蚀入海）
        // 2026-08-07 用户否决 flatMask（平顶保护）——"侵蚀必须无限制自然形成"，
        // 高原折痕靠高原 v8 丘沟纹理自然融合骨架条纹解决，不限制侵蚀本身。
        return d * landMask;
    }

    /** 局部窗口最高点（含自身；越界 clamp 到网格边缘）。 */
    private static float localWindowMax(float[][] g, int tx, int tz, int n, int r) {
        int z0 = Math.max(0, tz - r), z1 = Math.min(n - 1, tz + r);
        int x0 = Math.max(0, tx - r), x1 = Math.min(n - 1, tx + r);
        float m = Float.NEGATIVE_INFINITY;
        for (int z = z0; z <= z1; z++) {
            float[] row = g[z];
            for (int x = x0; x <= x1; x++) {
                float v = row[x];
                if (v > m) m = v;
            }
        }
        return m;
    }

    /** ★ 2026-08-12 局部窗口均值（平台区分用：lm−avg 大 = 脊线/峰体，小 = 平顶/高原）。 */
    private static float localWindowAvg(float[][] g, int tx, int tz, int n, int r) {
        int z0 = Math.max(0, tz - r), z1 = Math.min(n - 1, tz + r);
        int x0 = Math.max(0, tx - r), x1 = Math.min(n - 1, tx + r);
        float sum = 0;
        int cnt = 0;
        for (int z = z0; z <= z1; z++) {
            float[] row = g[z];
            for (int x = x0; x <= x1; x++) {
                sum += row[x];
                cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0f;
    }

    // ===== ErosionFilter（octave 循环 + 堆叠掩码）=====
    private static float erosionFilter(float px, float pz, float h0, float gx, float gz,
                                        float fadeTarget, float cellGridFreq, RidgeConfig cfg) {
        fadeTarget = clamp(fadeTarget, -1f, 1f);
        float inputH = h0;
        float slopeLen = (float) Math.max(Math.hypot(gx, gz), 1e-10);
        float roundingForInput = lerp(cfg.rounding[1], cfg.rounding[0], clamp01(fadeTarget + 0.5f)) * cfg.rounding[2];
        float combiMask = easeOut(smoothStart(slopeLen * cfg.onset[0], roundingForInput * cfg.onset[0]));
        float ridgeMask = easeOut(slopeLen * cfg.onset[2]);
        float ridgeFade = fadeTarget;
        // gullySlope：实际坡度 ↔ 假定坡度（assumedSlope.y=1 → 全用假定方向，幅度 assumedSlope.x）
        float glen = (float) Math.hypot(gx, gz);
        float ngsx = glen < 1e-8f ? 0f : gx / glen;
        float ngsz = glen < 1e-8f ? 0f : gz / glen;
        float gsx = lerp(gx, ngsx * cfg.assumedSlope[0], cfg.assumedSlope[1]);
        float gsz = lerp(gz, ngsz * cfg.assumedSlope[0], cfg.assumedSlope[1]);

        // ★ 2026-08-12 修复（第五轮）：fadeTarget 项单独缩放（不缩小条纹 strength）——
        //   第四轮 FADE_STRENGTH_SCALE 把整个 strength 缩小 → 条纹下切也变弱 →
        //   山区只剩 fadeTarget 正抬升（min=0 无下切）。正解：strength 恢复 0.8（条纹保持），
        //   fadeTarget 项在 fGx 内单独 ×FADE_TARGET_SCALE（见下）。
        float strength = cfg.strength;
        float freq = cellGridFreq;
        float roundingMult = 1f;
        for (int i = 0; i < cfg.octaves; i++) {
            float[] ph = phacelleNoise(px * freq, pz * freq, norm(gsx, gsz), cfg.stripeFreq, 0.25f, cfg.normalization, cfg.seed);
            float phx = ph[0], phy = ph[1];
            float sdx = ph[2] * -freq;
            float sdz = ph[3] * -freq;
            float sloping = Math.abs(phy);
            // 沿累积斜率分叉（供下一 octave）
            gsx += Math.signum(phy) * sdx * strength * cfg.gullyWeight;
            gsz += Math.signum(phy) * sdz * strength * cfg.gullyWeight;
            // gullies：x=高度偏移(-1..1)，yz=导数（sin × sideDir）
            float gullX = phx;
            float gullYx = phy * sdx;
            float gullYz = phy * sdz;
            // fadedGullies = lerp((fadeTarget,0,0), gullies*gullyWeight, combiMask)
            // ★ 2026-08-12 第五轮：fadeTarget 项单独 ×FADE_TARGET_SCALE（条纹 gullX 不受影响）——
            //   fadeTarget 是"峰顶/谷底淡出目标"，原版 strength 小所以直接使用；
            //   我们 strength=0.8（条纹时代调参）→ fadeTarget 必须压缩才不爆炸。
            float fGx = lerp(fadeTarget * FADE_TARGET_SCALE, gullX * cfg.gullyWeight, combiMask);
            float fGyX = lerp(0f, gullYx * cfg.gullyWeight, combiMask);
            float fGyZ = lerp(0f, gullYz * cfg.gullyWeight, combiMask);
            h0 += fGx * strength;
            gx += fGyX * strength;
            gz += fGyZ * strength;
            fadeTarget = fGx;
            // 堆叠淡出（防覆盖已有脊线）
            float roundingOct = lerp(cfg.rounding[1], cfg.rounding[0], clamp01(phx + 0.5f)) * roundingMult;
            float newMask = easeOut(smoothStart(sloping * cfg.onset[1], roundingOct * cfg.onset[1]));
            combiMask = powInv(combiMask, cfg.detail) * newMask;
            ridgeFade = lerp(ridgeFade, gullX, ridgeMask);
            float newRidgeMask = easeOut(sloping * cfg.onset[3]);
            ridgeMask *= newRidgeMask;
            strength *= cfg.gain;
            freq *= cfg.lacunarity;
            roundingMult *= cfg.rounding[3];
        }
        return h0 - inputH; // 高度增量
    }

    // ===== PhacelleNoise（4×4 细胞钟形权重混合的脊-谷条纹）=====
    private static float[] phacelleNoise(float pxf, float pzf, float[] nd, float freq, float offset, float normalization, int seed) {
        float ndx = nd[0], ndy = nd[1];
        float sdx = ndy * (-1f) * freq * TAU;   // sideDir.x = -normDir.y * freq * TAU
        float sdz = ndx * (1f) * freq * TAU;    // sideDir.z =  normDir.x * freq * TAU
        float off = offset * TAU;
        int pxi = (int) Math.floor(pxf);
        int pzi = (int) Math.floor(pzf);
        float pxfr = pxf - pxi;
        float pzfr = pzf - pzi;
        float phaseX = 0f, phaseY = 0f, weightSum = 0f;
        for (int i = -1; i <= 2; i++) {
            for (int j = -1; j <= 2; j++) {
                float gpx = pxi + i, gpz = pzi + j;
                float[] rnd = hash2(gpx, gpz, seed);
                float rx = rnd[0] * 0.5f, rz = rnd[1] * 0.5f;
                float vx = pxfr - i - rx;
                float vz = pzfr - j - rz;
                float sqr = vx * vx + vz * vz;
                float w = (float) Math.exp(-sqr * 2f);
                w = Math.max(0f, w - 0.01111f); // 距离 1.5 处归零，消除细网格线伪影
                weightSum += w;
                float wave = vx * sdx + vz * sdz + off;
                phaseX += (float) Math.cos(wave) * w;
                phaseY += (float) Math.sin(wave) * w;
            }
        }
        if (weightSum < 1e-8f) weightSum = 1e-8f;
        float ix = phaseX / weightSum, iy = phaseY / weightSum;
        float mag = (float) Math.hypot(ix, iy);
        mag = Math.max(1f - normalization, mag);
        return new float[]{ix / mag, iy / mag, sdx, sdz};
    }

    // ===== Hash2（确定性细胞 pivot，返回 [-1,1]²）=====
    // 2026-08-06 修复（对照 catto/Rune 原版 Java 直译）：y 维乘子原误写为 k1(1/PI)，
    // 应为 k2(e^-1)——两维用相同乘子使格点偏移 x/z 高度相关 → PhacelleNoise 细胞网格
    // 方向分布异常 → 条纹网格对齐伪影（用户反馈侵蚀骨架实测有 bug）。
    private static float[] hash2(float x, float y, int seed) {
        float k1 = 1f / (float) Math.PI;       // 1/PI
        float k2 = (float) Math.exp(-1.0);     // e^-1
        float sx = seed * 0.06711056f, sy = seed * 0.00583715f;
        float xx = (x + sx) * k1 + k2;         // x 维：1/PI 乘子 + e^-1 偏移
        float yy = (y + sy) * k2 + k1;         // y 维：e^-1 乘子 + 1/PI 偏移（交叉，对齐原版）
        float t = frac(xx * yy * (xx + yy));
        float inner1 = 16f * k1 * t;
        float inner2 = 16f * k2 * t;
        return new float[]{-1f + 2f * frac(inner1), -1f + 2f * frac(inner2)};
    }

    // ===== 工具 =====

    /**
     * 梯度（返回"每块"物理坡度，2026-08-10 wu 化修正）：网格间距为 wu，需 ×horizontalScale
     * 换算回块——骨架 SLOPE_BOOST/combiMask 按物理坡度（e/块）标定，HS≠1 时保持不变。
     * HS=1（或独立预览默认）时 hs=1 → 行为与 wu 化前逐位一致。
     */
    private static float gradX(float[][] g, int tx, int tz, int n, int spacing, float hs) {
        int xm = Math.max(0, tx - 1), xp = Math.min(n - 1, tx + 1);
        float d = (g[tz][xp] - g[tz][xm]);
        float denom = (float) ((xp - xm) * spacing);
        if (hs > 0.01f && hs != 1f) denom *= hs;
        return denom > 0 ? d / denom : 0f;
    }

    private static float gradZ(float[][] g, int tx, int tz, int n, int spacing, float hs) {
        int zm = Math.max(0, tz - 1), zp = Math.min(n - 1, tz + 1);
        float d = (g[zp][tx] - g[zm][tx]);
        float denom = (float) ((zp - zm) * spacing);
        if (hs > 0.01f && hs != 1f) denom *= hs;
        return denom > 0 ? d / denom : 0f;
    }

    private static float[] norm(float x, float y) {
        float l = (float) Math.hypot(x, y);
        if (l < 1e-8f) return new float[]{1f, 0f}; // 零向量回退固定方向
        return new float[]{x / l, y / l};
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float clamp(float v, float mn, float mx) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return mn;
        return Math.max(mn, Math.min(mx, v));
    }

    private static float clamp01(float v) { return clamp(v, 0f, 1f); }

    private static float frac(float v) { return v - (float) Math.floor(v); }

    private static float smoothstep(float e0, float e1, float x) {
        if (e1 - e0 < 1e-8f) return x < e0 ? 0f : 1f;
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    private static float easeOut(float t) {
        float v = 1f - clamp01(t);
        return 1f - v * v;
    }

    private static float smoothStart(float t, float s) {
        if (t >= s) return t - 0.5f * s;
        return 0.5f * t * t / s;
    }

    private static float powInv(float t, float p) {
        return 1f - (float) Math.pow(1f - clamp01(t), p);
    }

    // ===== 配置读取辅助 =====

    private static boolean cfgBool(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean fb) {
        try { return v != null && v.get(); } catch (Exception e) { return fb; }
    }

    private static double cfgDbl(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, double fb) {
        try { return v != null ? v.get() : fb; } catch (Exception e) { return fb; }
    }

    private static int cfgInt(net.minecraftforge.common.ForgeConfigSpec.IntValue v, int fb) {
        try { return v != null ? v.get() : fb; } catch (Exception e) { return fb; }
    }
}
