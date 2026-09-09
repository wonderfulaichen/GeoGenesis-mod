package com.geogenesis.worldgen.hydrology.riverline;

import com.geogenesis.worldgen.noise.NoiseUtil;

/**
 * 河线网络参数（不可变）。
 *
 * <p>坐标语义：全部为 wu（world unit，= MC block ÷ horizontalScale）。
 * 宽度/深度为 block 语义值由调用方换算。</p>
 *
 * <p>2026-08-28 汇流范式：河网从地形汇流场派生（高位布源 + 下坡窗口追踪 + 防交叉），
 * 水面 = min(单调水面, 当地地形)（Streams maxSurfaceAt 范式）。</p>
 */
public record RiverLineParams(
    /** region 边长（wu）。越大河越稀、单条越长。 */
    int regionSize,
    /** 分形中点位移抖动幅度（占段长比例）。旧分形线用，保留兼容。 */
    double jitter,
    /** 分形最大二分次数。旧分形线用，保留兼容。 */
    int fractalLevels,
    /** 锚点从 hash 位置向局部低 e 走廊吸附的搜索半径（wu）。旧锚点用，保留兼容。 */
    double anchorSnapRadius,
    /** 锚点吸附采样步长（wu）。旧锚点用，保留兼容。 */
    double anchorSnapStep,
    /** 线节点贴谷偏置最大位移（wu）。旧贴谷用，保留兼容。 */
    double valleyBiasAmp,
    /** 起始河道半宽（block，源头）。 */
    double minWidth,
    /** 最大河道半宽（block，海口）。Streams 式窄河。 */
    double maxWidth,
    /** 起始水深（block，水面以下）。 */
    double minDepth,
    /** 最大水深（block）。Streams 式深河。 */
    double maxDepth,
    /** 岸带宽度系数（× 半宽）。 */
    double bankFactor,
    /** 高度淡出上阈值（已弃用：河由汇流场决定，山地也有溪）。 */
    double fadeHighE,
    /** 高度淡出下阈值（已弃用）。 */
    double fadeLowE,
    /** 水面低于沿岸地形目标的偏移（block）。 */
    double surfaceSink,
    /** 最小汇水面积（wu²）。旧干谷门控用，保留兼容。 */
    double minDischargeArea,
    /** 海洋 e 阈值：格 e < 此值视为海洋出口。 */
    double oceanE,
    // ===== 汇流场派生河网 =====
    /** 选线场山压低系数（0~1]：routingE 把 e 按此系数压低（见 routingE）。
     *  1.0 = 恒等（用原始 e 追踪，河线回到 a7a8d68 基线位置）；<1 压低山峰、河线贴谷避峰。
     *  实测 mountainScale<1 会把河从原出河位置挤走（用户反馈"河流变少"），故默认 1.0。 */
    double mountainScale,
    /** 汇流场栅格边长（wu）：D8 流向/累积的采样分辨率。 */
    double gridCell,
    /** 下坡追踪最大步数（防洼地/平地死循环兜底）。 */
    int maxTraceSteps,
    /** 源点最小 e：仅高于此 e 的格可作为河源。0.40 为 a7a8d68 基线（山溪发源，低地由下游覆盖）；
     *  调低（如 0.20）可让平原/低地也出河（option A，用户要求更多河）。 */
    double sourceMinE,
    /** 源点最小间距（栅格格数）：控制河网密度。 */
    int sourceSpacingCells,
    /** 下坡追踪窗口（栅格格数）：在 step×step 邻域取最陡下降格（PL-RGA step_size=2）。 */
    int traceStep,
    /** 一条河最少节点数：短于它的视为噪音丢弃。 */
    int minRiverNodes,
    /** 河网汇流面积阈值（wu²）：格点汇流面积高于此才成河（树状稀疏度）。 */
    double riverAccumThreshold,
    /** 河高沿程缓降（block）：源端相对地形降低此量，保证水面单调顺滑。 */
    double slopeDrop,
    // ===== 河谷/蜿蜒 =====
    /** 河谷壁半宽系数（× 半宽）：河道半宽之外、地形向原地形渐变形成河谷的范围。 */
    double bankWidth,
    /** 河谷壁陡缓指数：outer = 1 - (dist/valley)^valleyExp，越大壁越陡。 */
    double valleyExp,
    /**
     * 岸坡坡度上限（水平跨度 / 每 1 格岸高）：谷壁跨度 R ≥ 岸高 H × 本值。
     * 岸高 H = 原地形 − 河缘雕刻面（profile=0 处的雕刻目标）。高岸自动展宽、
     * 低岸维持原宽度 → 根治"岸坡垂直面"（旧版 R 恒 = bankFactor×半宽，
     * H 大到 15 格、R 仅 7.5 格时坡度 2.0 ≈ 63°，视觉竖直）。
     * 默认 1.5 → 坡角 ≈ arctan(1/1.06/1.5) ≈ 32°（valleyExp=1.5 时 outer 最大斜率 ≈1.06）。
     */
    double bankSlopeRun,
    /** 谷壁跨度上限（block）：自适应展宽的绝对上限，防止深切河谷无限外扩吞掉地形。 */
    double bankRunMax,
    /**
     * 保形下挖强度（0~1，2026-09-09）：谷壁带<b>不再把地形 lerp 向"水面平面"</b>
     * （旧式 `carved = original·(1−outer) + carveSurfaceY·outer` 本质是拉向一个水平面，
     *  整片自然起伏被刨平 → 用户实测"河岸向上一整片被削平的斜坡/平台"），改为只减去
     *  一个<b>平滑</b>衰减量 A0 ⇒ `carved = original − A0·outer`，起伏原样保留、
     *  只是整体沉降；带外缘 A0·outer→0 时<b>严格等于实际（已侵蚀）地形</b>。
     *  0 = 旧行为（拉向平面），1 = 完全保形。
     */
    double bankRelief,
    /** 保形下切量基准（block）：A0 = depth + 本值。深岸只沉 A0、保住山的形状。 */
    double bankIncise,
    /**
     * 雕刻分段化（Zoned Carve，2026-09-09）：内层定形带宽系数。
     * 谷壁带拆为【内层定形】（width ~ width+formRun，保留现有 outer lerping，
     * 定形的权力完全归它）+【外层接缝】（formRun 之外 → 谷外缘，
     * `cut = cut(内层出口) × seamFade(s)`，只做"收敛到 0"，不含目标面信息）。
     * 0 = 关闭分段（精确回退到旧的一段式 outer）。
     */
    double formRunFactor,
    /** 外层接缝带宽上限（block）：接缝带 = min(valley − (width+formRun), 本值)，
     * 内层出口 cut 值经 smoothstep 衰减到 0 ⇒ 谷外缘数学保证等于实际地形。 */
    double seamRun,
    /** 蜿蜒振幅（block）：沿河路径叠加的垂直正弦偏移。 */
    double meanderAmp,
    /** 蜿蜒波长（block）。 */
    double meanderWavelength,
    // ===== PL-RGA 对齐（拓扑健壮性 / 湖 / 多河混合）=====
    /** 每 region 最大河数（按 e 降序取前 N；PL-RGA RIVER_COUNT=50）。 */
    int riverCount,
    /** 距 region 边界安全距（wu）：此内不布源、不终止为湖（PL-RGA border/lake_safe_mask）。 */
    double borderDist,
    /** 湖面半径（wu）：outlet_local_minimum 节点展平半径（PL-RGA RIVR_LAKE_RADIUS）。 */
    double lakeRadius,
    /** 湖面边距（wu）：距湖节点 ≤ 此 为满湖面（PL-RGA RIVER_LAKE_SURFACE_MARGIN_DISTANCE）。 */
    double lakeMargin,
    /** 湖高淡出距（wu）：湖面→地形渐变半径（PL-RGA RIVER_LAKE_NODE_HEIGHT_FADE_DISTANCE）。 */
    double lakeFadeDist,
    /** 多河 IDW 混合半径（wu）：此内反距离平方加权混合河高（PL-RGA RIVER_HEIGHT_BLEND_DISTANCE）。 */
    double heightBlendDist,
    /** 河高混合回地形幂次（PL-RGA RIVER_BLEND_EXP=1.5）。 */
    double blendExp,
    /** 最小下坡量：邻居须严格低于此才连边/汇入（PL-RGA RIVER_MIN_DROP）。 */
    double minDrop,
    /** smooth-min 合并宽度（block）：逐段 carve 经此宽度 C1 过渡，根治属主切换放射折痕。 */
    double smoothMinK,
    // ===== 下游水力几何（2026-08-29 宽深改革）=====
    /** 宽度参考汇流面积（wu²）：宽度映射的原点，即"宽度 = minWidth"时所对应的汇流面积。
     *  ★ 必须与 riverAccumThreshold（成河门槛）解耦——旧实现把门槛当宽度原点，
     *    导致调密度（降门槛）时全河宽度整体平移，无法独立标定。默认 = gridCell²（单格面积）。 */
    double widthAreaRef,
    /** 河宽-汇流面积幂指数 bW：W ∝ A^bW（Leopold-Maddock 下游水力几何 b≈0.5，
     *  本项目取 0.42 以压缩超大汇流的宽度爆炸）。幂律不会像 smoothstep 那样中段饱和。 */
    double widthExp,
    /** 河深-汇流面积幂指数 bD：D ∝ A^bD（LM 水力几何 f≈0.4）。
     *  bD < bW 使宽深比 W/D ∝ A^0.02 随下游缓慢增大（下游更宽浅）。 */
    double depthExp,
    /** 宽深比护栏：D ≤ maxDepthRatio × W。防止窄而极深的非物理断面。 */
    double maxDepthRatio,
    /** 河口向海延伸距离（block，按"地形在海面下的深度"计）。海床雕刻在此深度内平滑
     *  淡出，使河道延伸进海里形成河口湾/淹没河谷，而不是在海岸线处直接截断。 */
    double mouthFadeDepth,
    /** 河口喇叭口过渡长度（wu）：入海口向上游回溯此距离内宽度渐增。 */
    double estuaryLength,
    /** 河口展宽倍数（相对上游河宽）。参考 Streams 河口比上游更宽。 */
    double estuaryWidthFactor,
    /** 河口半宽上限（block）。★ 独立于河道 maxWidth：否则大河上游已接近 maxWidth，
     *  河口会被钳制到与河道同宽，喇叭口完全展不开。 */
    double mouthMaxWidth,
    /** 河口最小水深（block），保证河口不被填平（参考 Streams RiverMouthComponent MinDepth）。 */
    double mouthMinDepth,
    /** 跨 region 连续河：到网格边（缝外 margin）的河作为出口种子交接给下游邻 region，
     *  携带汇流面积/层级/水面续流，消除瓦片缝断河与宽度颈缩。默认开启。 */
    boolean crossRegion,
    // ===== 瀑布 / 跌水（2026-08-30）=====
    /** 瀑布最小落差（block）：短窗内累计水面落差达到此值才判定为裂点（DW WATERFALL_THRESHOLD=2）。 */
    double waterfallMinDrop,
    /** 瀑布最大落差（block）：单级 sanity 钳（防失控级）。贴地形阶梯化后真实崖面
     *  单级可达 20+ 格，此值只拦"远超地形落差"的失控级，不再限制正常大落差。 */
    double waterfallMaxDrop,
    /** 裂点检测窗口（节点数）：窗口内累计落差最大的位置即为跌水点。 */
    int waterfallWindowNodes,
    /** 两级跌水最小间距（节点数）：防止跌水连成阶梯（DW MIN/MAX_TERRACE_LENGTH 语义）。
     *  节点间距 SMOOTH_SPACING(4wu)×horizontalScale → 默认 16 节点 ≈ 128 block 一级。 */
    int waterfallMinSpacing,
    /** 跌水潭加深系数（× 落差）：跌水下方冲刷潭的额外深度（Farseek plungePoolDepth 语义）。 */
    double plungePoolFactor,
    // ===== 瀑布 / 跌水（2026-08-30 重写：地形角度触发 + 阶梯分阶）=====
    /** 瀑布最小坡角（度，block 空间）：原地形连续陡降段夹角 ≥ 此值才挂瀑，
     *  低于则视为普通河流（平缓坡不挂瀑）。默认 12°（仅明显崖壁成瀑）。 */
    double waterfallMinAngle,
    /** 单级最小落差（block）：台阶数公式的基准步高 h0（θ≤45° 时每级约此落差）。 */
    double waterfallStepHeight,
    /** 一级对应的水平跨度（block）：run 水平跨度 < 此值强制 1 阶（短陡坡）；
     *  长陡坡按 floor(runLen/stepRun) 多阶。 */
    double waterfallStepRun,
    /** 单 run 最大台阶数：防止极长陡坡被切成无限多级（视觉碎裂）。 */
    int waterfallMaxSteps
) {
    /** 返回副本并把跨 region 连续河开关设为 v（探针 A/B 用）。 */
    public RiverLineParams withCrossRegion(boolean v) {
        return new RiverLineParams(
                regionSize, jitter, fractalLevels, anchorSnapRadius, anchorSnapStep,
                valleyBiasAmp, minWidth, maxWidth, minDepth, maxDepth, bankFactor,
                fadeHighE, fadeLowE, surfaceSink, minDischargeArea, oceanE, mountainScale,
                gridCell, maxTraceSteps, sourceMinE, sourceSpacingCells, traceStep,
                minRiverNodes, riverAccumThreshold, slopeDrop, bankWidth, valleyExp,
                bankSlopeRun, bankRunMax, bankRelief, bankIncise, formRunFactor, seamRun,
                meanderAmp, meanderWavelength, riverCount, borderDist, lakeRadius,
                lakeMargin, lakeFadeDist, heightBlendDist, blendExp, minDrop, smoothMinK,
                widthAreaRef, widthExp, depthExp, maxDepthRatio, mouthFadeDepth,
                estuaryLength, estuaryWidthFactor, mouthMaxWidth, mouthMinDepth, v,
                waterfallMinDrop, waterfallMaxDrop, waterfallWindowNodes,
                waterfallMinSpacing, plungePoolFactor, waterfallMinAngle,
                waterfallStepHeight, waterfallStepRun, waterfallMaxSteps);
    }

    public static RiverLineParams defaults() {
        return new RiverLineParams(
            640,                     // regionSize
            0.16,                    // jitter（旧分形用）
            4,                       // fractalLevels（旧分形用）
            96.0,                    // anchorSnapRadius（旧锚点用）
            12.0,                    // anchorSnapStep（旧锚点用）
            40.0,                    // valleyBiasAmp（旧贴谷用）
            1.0,                     // minWidth（半宽 block）★全宽 2 block = 山泉/源头溪流档
            10.0,                    // maxWidth（半宽 block）★全宽 20 block = 大河档
            1.6,                     // minDepth（block）★保证小溪水面宽 ≥1.33 block，灌水门控稳定命中
            8.0,                     // maxDepth（block）
            2.5,                     // bankFactor
            0.30,                    // fadeHighE（已弃用）
            0.10,                    // fadeLowE（已弃用）
            1.0,                     // surfaceSink
            2048.0,                  // minDischargeArea（旧门控用）
            -0.02,                   // oceanE
            1.0,                     // mountainScale（=1.0：恒等，原始 e，匹配 a7a8d68 基线河位）
            24.0,                    // gridCell（D8 采样分辨率）
            512,                     // maxTraceSteps
            0.05,                    // sourceMinE（下探到低地/山坡：汇流面积小 → 产出小溪；
                                     //  原 0.20 使源点只聚集在少数高地，互相 claimed 阻断，密度上不去）
            2,                       // sourceSpacingCells（≈48wu 间距；1 时河网过密，用户实机反馈"河流有点多"）
                                     //  ★ 2026-08-31 实测：加汇流面积门限后改 1 对河数无影响
                                     //    （14→14），约束不在间距而在候选被已有河路径 claimed，故保持 2。
            2,                       // traceStep（下坡窗口 2 格）
            3,                       // minRiverNodes
            2304.0,                  // riverAccumThreshold（wu²）= 4 格汇流面积
                                     //  ★ 200→2304（2026-08-31，修"河流源头全在山顶"）：
                                     //    原值 200 < 单格面积 gridCell²=24²=576，使 commitRiver
                                     //    的"源头细流裁剪"while 循环永不触发（每格 accum≥576>200）
                                     //    → 成河判据形同虚设，河头一路留到布源点。而候选源又纯按
                                     //    高程 e 降序取点 → 源头必然落在山顶。实测种子
                                     //    9139912035078620160：山顶 24% + 山脊 29% = 过半在峰顶
                                     //    脊线，落在谷头/洼地的仅 6%，源头平均高程百分位 95%。
                                     //    取 4 格 = 坡面汇流首成槽处（channel initiation），河头
                                     //    自动落到山坳：山顶/山脊 → 0%，谷头/坡面 → 100%。
                                     //    密度 34→22（配合 sourceMinE 0.12→0.05 补回一部分）。
            0.5,                     // slopeDrop（block：源端缓降）
            2.5,                     // bankWidth（河谷壁系数 ×半宽）
            1.5,                     // valleyExp（谷壁陡缓）
            1.5,                     // bankSlopeRun（岸坡跨度 / 每格岸高；≈32° 上限）
            24.0,                    // bankRunMax（谷壁跨度上限 block）
                                     //   ★ 实测不可收窄（2026-09-09）：24→12 使平缓种子
                                     //     台阶 0→193。带宽是"摊开爬升"的必要尺度，
                                     //     削平台的问题靠保形(bankRelief)解决，不靠收窄。
            0.0,                     // bankRelief（保形强度；★ 默认 0 = 关闭，实测见下）
                                     //   ★★ 实测结论（2026-09-09，双种子 A/B）：保形能削掉
                                     //     "被刨平的斜坡"，但【必然】在河槽缘制造台阶——
                                     //     平整的计划床面(carveSurfaceY)与保形的自然起伏
                                     //     相差 (H − A0)，H 含地形锯齿 ⇒ 台阶。
                                     //     平缓种子 12345：0 → 302（劣化）；
                                     //     山地种子 9139912035078620160：458 → 160（改善）。
                                     //   仅在"多山世界、可容忍河槽缘台阶"时手动开 1.0。
            8.0,                     // bankIncise（A0 = depth + 8.0：只有岸高超过它的深岸才保形，
                                     //   浅岸经 min() 自动等于原值 → 平缓区完全不动）
                                     //   ★ 实测甜点（2026-09-09 seed 12345）：16 → 谷壁台阶 110，
                                     //     footprint 372364；24 → 台阶 0，footprint 372933（几乎不变）
                                     //     ——跨度上限几乎不 binding，被截的恰是"最需要展宽的深岸"，
                                     //     故 24 严格优于 16。
            1.5,                     // formRunFactor（内层定形带 = width × 1.5）
            12.0,                    // seamRun（外层接缝带宽上限 12 block）
            2.5,                     // meanderAmp（蜿蜒振幅 block）
            40.0,                    // meanderWavelength（蜿蜒波长 block）
            80,                      // riverCount（每 region 最大河数，加密河网）
            96.0,                    // borderDist（≈0.15×regionSize，边界安全距 wu）
            120.0,                   // lakeRadius（湖面半径 wu）
            8.0,                     // lakeMargin（湖面边距 wu）
            100.0,                   // lakeFadeDist（湖高淡出距 wu）
            100.0,                   // heightBlendDist（多河 IDW 混合半径 wu）
            1.5,                     // blendExp（河高混合回地形幂次）
            1e-6,                    // minDrop（最小下坡量）
            4.0,                     // smoothMinK（smooth-min 合并宽度 block）
            576.0,                   // widthAreaRef（= gridCell²=24²，单格汇流面积）
            0.42,                    // widthExp（W ∝ A^0.42）
            0.40,                    // depthExp（D ∝ A^0.40）
            0.9,                     // maxDepthRatio（宽深比护栏 D ≤ 0.9W）
            6.0,                     // mouthFadeDepth（河口向海延伸 6 格后淡出）
            140.0,                   // estuaryLength（河口喇叭口过渡长度 wu）
            1.9,                     // estuaryWidthFactor（河口展宽 1.9 倍）
            14.0,                    // mouthMaxWidth（河口半宽上限 14 block，全宽 28）
            2.0,                     // mouthMinDepth（河口最小水深 2 格）
            true,                    // crossRegion（跨 region 连续河，默认开启）
            2.0,                     // waterfallMinDrop（block；DW WATERFALL_THRESHOLD=2）
            24.0,                    // waterfallMaxDrop（block：单级 sanity 钳，允许大落差级）
            3,                       // waterfallWindowNodes（裂点窗口 3 节点 ≈ 24 block）
            16,                      // waterfallMinSpacing（≈128 block 一级，防连成阶梯）
            1.0,                     // plungePoolFactor（跌水潭深 = 1.0 × 落差）
            12.0,                    // waterfallMinAngle（度：原地形坡角 ≥12° 才挂瀑）
            6.0,                     // waterfallStepHeight（block：θ≤45° 时每级基准落差 h0）
            8.0,                     // waterfallStepRun（block：一级水平跨度；短陡坡<此值→1阶）
            3                        // waterfallMaxSteps（单 run 最大台阶数：保证大落差级）
        );
    }

    /**
     * 选线场高程变换：把高于中段的高程按 {@link #mountainScale()} 比例压低，
     * 使河网在"压低后的地形"上追踪——贴谷而非贴峰（PL-RGA firstHeightField）。
     * 低地（e ≤ mid）保持不变，仅压低山脊/高峰，避免河线硬切陡坡。
     *
     * @param e 原始汇流场高程（terrainEQuick）
     * @return 选线用高程
     */
    public double routingE(double e) {
        final double mid = 0.4;
        if (e <= mid) return e;
        double tt = NoiseUtil.saturate((e - mid) / (1.0 - mid));
        double lower = mountainScale + (1.0 - mountainScale) * (1.0 - tt);
        return e * lower;
    }
}
