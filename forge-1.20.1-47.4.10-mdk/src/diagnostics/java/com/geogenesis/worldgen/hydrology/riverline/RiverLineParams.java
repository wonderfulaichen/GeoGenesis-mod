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
    /** 分形最大二分次数。⚠ 2026-09-18 警告：本字段注释原写"旧分形线用，保留兼容"，
     *  极易被当成死参数清理 —— 但【新范式 RiverTrace.nodeCount() 仍在消费它】，
     *  决定 D8 河线的节点数（2^levels + 1）。清理前必须先改 RiverTrace，否则静默改变产出。 */
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
    int waterfallMaxSteps,
    // ===== 峡谷（大峡谷式，★ 2026-09-14）=====
    //
    //   【为何需要】实测（CanyonProfileProbe）：现状已是"深且陡"的深谷并不缺——
    //   缺的是【典型河谷太宽缓】：谷壁跨度被 bankFactor(2.5×半宽) 与 bankRunMax(24)
    //   双重托底，h=20 格的河谷跨度也达 ~24 格 ⇒ 坡度仅 ~0.9（42°），
    //   视觉上是"宽缓河谷"而非峡谷（渲染图直接可见）。
    //
    //   【做法】在"岸高 h = 原地形 − 水面"足够大的河段，把谷壁跨度【收窄】：
    //   同样的落差压缩到更窄的横向距离 ⇒ 陡壁。**水面不动** ⇒ 不污染水文标定
    //   （这是本方案最关键的取舍：不改深度，只改横向跨度）。
    /** 峡谷门控下界（block）：岸高 h = 原地形 − 水面 ≥ 本值开始进入峡谷形态。 */
    double canyonMinBank,
    /** 峡谷门控上界（block）：岸高 ≥ 本值达到全强度（smoothstep 过渡）。 */
    double canyonFullBank,
    /** 峡谷区岸坡跨度系数（水平跨度 / 每格岸高）：&lt; bankSlopeRun ⇒ 崖更陡。
     *  0.40 → 坡度上限 ≈ arctan(1/1.06/0.40) ≈ 67°（对照 bankSlopeRun=1.5 → 32°）。 */
    double canyonSlopeRun,
    /** 峡谷区谷底跨度系数（× 河道半宽）：&lt; bankFactor ⇒ 谷底更窄、崖壁更贴近河道。
     *  1.6 → 谷底带 = 1.6×半宽（保留"宽底"，但远窄于常规的 2.5×）。 */
    double canyonWallFactor,
    /**
     * ★ 2026-09-20：支流分叉参数（复刻 FTF {@code BaseRiverGenerator.generateForks} 的
     * 【布点规则】，追踪仍走本项目的 D8 下坡 {@code traceRiver}）。
     *
     * <p>单独成 record 的原因：本 record 已有 ~60 个位置参数，再平铺十几个分叉参数
     * 极易错位 ⇒ 归组为嵌套 record，只经 {@link #withFork(ForkParams)} 一个出入口。</p>
     *
     * <p><b>默认 {@code enabled=false} ⇒ 零行为变更</b>（与 {@code Caves}/{@code Ores}
     * 同范式：先做默认关闭的实现，再靠实机 A/B 标定）。</p>
     */
    ForkParams fork
) {
    /**
     * ★ 2026-09-20 采样密度实验开关：对 {@code gridCell} 与【以格为基准的面积量】做全局缩放。
     *
     * <p>{@code 1.0} = 生产默认（零变更）。{@code 0.5} ⇒ gridCell 24→12wu、
     * widthAreaRef 576→144、riverAccumThreshold 2304→576；后两者同步按 {@code scale²} 缩放，
     * 是为了<b>保持"4 格汇流面积 / 单格面积"的口径不变</b>（否则会把河流宽度整体平移）。</p>
     *
     * <p><b>为什么需要它</b>：用户判据"运动路线还是有点不自然，感觉像采样太稀少导致的" ——
     * 根因就是 D8 采样格距本身是 48 block；有界粒子只能在格内弯，改不了"格粗"。</p>
     *
     * <p>⚠ 设非 1.0 会改变产出，且水文耗时约 ×(1/scale)²（0.5 ⇒ ~4×）。仅用于实验对比。</p>
     */
    public static volatile double gridCellScale = 1.0;

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
                waterfallStepHeight, waterfallStepRun, waterfallMaxSteps,
                canyonMinBank, canyonFullBank, canyonSlopeRun, canyonWallFactor, fork);
    }

    /** 返回副本并替换【支流分叉】参数组（探针 A/B / 实机调参用）。 */
    public RiverLineParams withFork(ForkParams v) {
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
                estuaryLength, estuaryWidthFactor, mouthMaxWidth, mouthMinDepth, crossRegion,
                waterfallMinDrop, waterfallMaxDrop, waterfallWindowNodes,
                waterfallMinSpacing, plungePoolFactor, waterfallMinAngle,
                waterfallStepHeight, waterfallStepRun, waterfallMaxSteps,
                canyonMinBank, canyonFullBank, canyonSlopeRun, canyonWallFactor, v);
    }

    // ==================================================================
    // ★ 支流分叉（2026-09-20）—— 复刻 FTF generateForks 的【布点规则】
    //
    //   【参考原文（已逐行核对）】
    //     FreeTerraForged-1.21.1 .../rivermap/river/BaseRiverGenerator.java:77-119
    //       if (depth > 2) return;
    //       length = 0.44F * parent.length;  if (length < 300f) return;
    //       for (offset = 0.25F; offset < 0.9F; offset += spacing.next(random)) {
    //           direction = -direction;
    //           angle = parentAngle + direction * 2π * FORK_ANGLE.next(random);
    //           (x1,z1) = parent.pos(offset);                  ← 汇入点（父河上）
    //           (x2,z2) = (x1,z1) - (sin angle, cos angle) * length;   ← 叉源（外侧）
    //       }
    //     River.java:14-16  FORK_ANGLE = 0.075+rand*0.115（圈数）⇒ ±27.0°~68.4°
    //                       MAIN_SPACING = 0.1+0.25 / FORK_SPACING = 0.25+0.25
    //
    //   【⚠ 与参考的两处关键差异（勿照抄数值）】
    //     ① FTF 的 fork 是【直线段】（两点、不追地形）；我方必须由 traceRiver 沿 D8
    //        下坡追踪，否则会造出『河悬在空中』⇒ 只搬【布点规则】，不搬几何。
    //     ② FTF 的 length=0.44×父长 是【同量级大支流】，而 300 的量纲是 FTF 世界单位；
    //        我方 regionSize=640wu、格距 24wu ⇒ 阈值必须由实测标定（见
    //        ForkFeasibilityProbe）。
    //
    //   【⚠ 长度与"短溪"目标的关系（动手前核查发现，务必记住）】
    //     minRiverNodes=3 是【格数】（traceRiver:1151）⇒ 最短河 = 3 格 = 72wu，
    //     经 smoothPath 按 4wu 重采样 ≈ 13 节点 ⇒ 只能落进 <20 桶，<10 恒为 0。
    //     唯一能进 <10 的通路是 feeder 语义的【2 格例外】（commitRiver:877-878，
    //     2 格 ≈ 24wu ≈ 7 节点）⇒ 分叉必须以 feeder 提交，且长度要短。
    // ==================================================================
    public record ForkParams(
            /** 总开关。false ⇒ 不进入分叉循环（零行为变更）。 */
            boolean enabled,
            /**
             * 布点方式：<b>0</b> = FTF 几何（父河切向 ±角 反推源点）；
             * <b>1</b> = 沿 D8 <b>上游未认领分支</b>回走（本项目地形驱动）。
             *
             * <p>为什么必须有 1（2026-09-20 M0 实测）：几何布点 151 个候选仅 8 个过闸，
             * 且 8 个<b>全部</b>被 traceRiver 回滚 —— 叉源离父河仅 1~3 格 ⇒ 追踪 2 格就
             * 撞上父河 ⇒ {@code path.size() < minRiverNodes(3)} ⇒ 返回 null。
             * 而"支流"在 D8 图上<b>就是</b>【汇入该点的上游分支】⇒ 沿 flowTo 反向走即可：
             * 天然在谷槽、天然汇入父河、长度由步数直接控制。</p>
             */
            int mode,
            /** 最大递归深度（FTF：depth > 2 即 return ⇒ 0/1/2 三代）。 */
            int maxDepth,
            /** 叉长 = 本值 × 父河弧长（FTF 0.44）。再被 [lenMinWu, lenMaxWu] 钳制。 */
            double lengthFrac,
            /** 父河弧长下限（wu）：短于此不分叉（FTF 等价 300/0.44≈682，量纲不同需标定）。 */
            double minParentLenWu,
            /** 叉长下限（wu）。 */
            double lenMinWu,
            /** 叉长上限（wu）：防分叉长到与主河同量级（FTF 无此约束）。 */
            double lenMaxWu,
            /** 分叉角下限（圈数，FTF 0.075 ⇒ 27°）。 */
            double angleMinTurns,
            /** 分叉角跨度（圈数，FTF 0.115 ⇒ 上限 68.4°）。 */
            double angleRangeTurns,
            /** 沿父河布点间距下限（占弧长比例；FTF MAIN_SPACING 0.10）。 */
            double spacingMin,
            /** 布点间距随机跨度（FTF MAIN_SPACING 0.25）。 */
            double spacingRange,
            /** 二级及以下分叉的间距下限（FTF FORK_SPACING 0.25，更稀疏）。 */
            double spacingMinDeep,
            /** 二级及以下分叉的间距跨度（FTF 0.25）。 */
            double spacingRangeDeep,
            /** 布点起点（占弧长比例，FTF 0.25）。 */
            double offsetLo,
            /** 布点终点（占弧长比例，FTF 0.90）。 */
            double offsetHi,
            /** mode=1：从汇入点沿【上游未认领分支】回走的格数 ⇒ 直接决定叉长（格）。 */
            int upstreamCells,
            /**
             * 叉源的汇流槽判据强度：{@link #TROUGH_OFF} / {@link #TROUGH_STRICT} / {@link #TROUGH_WEAK}。
             *
             * <p>⚠ M0 实测（2026-09-20，mode=1）：<b>严格</b>判据单独拒绝 <b>51%</b> 的候选，
             * 导致可成叉 ≈ 0。根因是"未认领的上游格"<b>本来就是因为过不了严格判据才没被
             * 选作主河源头</b>；而一阶支流在山坡上天然"一侧更低"（水正是从那侧汇下来的），
             * 那是山坡支流的正常形态，不是缺陷。</p>
             *
             * <p>默认 {@link #TROUGH_WEAK}：只排除<b>山脊/分水岭顶部</b>（两侧都更低 =
             * 水向两侧同时散开）—— 那才是"河槽切在坡面上"的真正观感来源 ⇒ 保住
             * 2026-09-01『源头应该在山谷中』要求的本意，同时能出叉。</p>
             */
            int troughMode,
            /**
             * 分叉追踪步长（格）：<b>0 = 沿用 {@code params.traceStep()}</b>。
             *
             * <p>M0 实测：步长 2 时 90 个过闸候选里 65 个被 {@code segmentCrossesAny} 回滚
             * —— 2 格跳跃会【跨过】介于中间的他人河段 ⇒ 判为交叉。改 1（逐格走）应能救回
             * 其中一部分（待实测确认）。</p>
             */
            int traceStep,
            /** 叉源与既有河的最小净空（wu）：太近则不开叉（FTF 用 250 的线段相交排斥）。 */
            double clearanceWu,
            /** 每 region 分叉数上限（护栏：防数量/耗时失控）。 */
            int countCap
    ) {
        /**
         * 默认：<b>关闭</b>（零行为变更）+ mode=1（上游分支）+ FTF 原始角度/间距比例。
         *
         * <p>绝对阈值（minParentLenWu / upstreamCells / clearanceWu）由
         * {@code ForkFeasibilityProbe} 实测标定，<b>不照搬 FTF 数值</b>。</p>
         */
        /** 汇流槽判据：不检查。 */
        public static final int TROUGH_OFF = 0;
        /** 汇流槽判据：严格（任一侧更低即否决）—— 主河源头语义。 */
        public static final int TROUGH_STRICT = 1;
        /** 汇流槽判据：弱（仅两侧都更低 = 山脊顶部 才否决）—— 分叉默认。 */
        public static final int TROUGH_WEAK = 2;

        /**
         * ★ 2026-09-20 M0 标定后<b>启用</b>的默认值（实测依据见 {@code runForkFeasibilityProbe}）：
         *
         * <pre>
         *   25 region / seed 9139912035078620160 / 只量 depth 0：
         *     槽判据 严格 ⇒ 可成叉 2 条（0.1/region）      ← 等于没有
         *     槽判据 弱   ⇒ 可成叉 31 条（1.2/region）★启用
         *     槽判据 关   ⇒ 可成叉 49 条（2.0/region）     ← 含山脊顶部源，不采用
         *   密度 3.7 → 4.9 条/region（+32%）；叉长 3~5 格 ≈ 13~21 节点
         * </pre>
         *
         * <p>⚠ 目标口径：分叉改善的是 <b>{@code <20} 桶</b>（短溪）；{@code <10} 结构性
         * 不可达 —— {@code minRiverNodes=3} 是<b>格数</b>（3 格 = 72wu ≈ 13 节点）。</p>
         */
        public static ForkParams defaults() {
            return new ForkParams(true, 1, 2, 0.44, 40.0, 24.0, 200.0,
                    0.075, 0.115, 0.10, 0.25, 0.25, 0.25,
                    0.25, 0.90, 3, TROUGH_WEAK, 1, 24.0, 40);
        }

        public ForkParams withEnabled(boolean v) {
            return tune(v, null, null, null, null, null, null, null, null, null, null, null);
        }

        /**
         * 探针调参入口：<b>null = 沿用当前值</b>。
         *
         * <p>取代逐个 {@code with*} —— 本 record 有 19 个组件，每个 wither 都抄一遍
         * 极易漏项（与本项目"参数平铺导致错位"的既有教训同源）。</p>
         */
        public ForkParams tune(Boolean enabled, Integer mode, Integer maxDepth,
                               Double lengthFrac, Double minParentLenWu,
                               Double lenMinWu, Double lenMaxWu, Integer upstreamCells,
                               Integer troughMode, Integer traceStep,
                               Double clearanceWu, Integer countCap) {
            return new ForkParams(
                    enabled != null ? enabled : this.enabled,
                    mode != null ? mode : this.mode,
                    maxDepth != null ? maxDepth : this.maxDepth,
                    lengthFrac != null ? lengthFrac : this.lengthFrac,
                    minParentLenWu != null ? minParentLenWu : this.minParentLenWu,
                    lenMinWu != null ? lenMinWu : this.lenMinWu,
                    lenMaxWu != null ? lenMaxWu : this.lenMaxWu,
                    this.angleMinTurns, this.angleRangeTurns,
                    this.spacingMin, this.spacingRange,
                    this.spacingMinDeep, this.spacingRangeDeep,
                    this.offsetLo, this.offsetHi,
                    upstreamCells != null ? upstreamCells : this.upstreamCells,
                    troughMode != null ? troughMode : this.troughMode,
                    traceStep != null ? traceStep : this.traceStep,
                    clearanceWu != null ? clearanceWu : this.clearanceWu,
                    countCap != null ? countCap : this.countCap);
        }
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
            24.0 * gridCellScale,    // gridCell（D8 采样分辨率）× 实验缩放
            512,                     // maxTraceSteps
            0.05,                    // sourceMinE（下探到低地/山坡：汇流面积小 → 产出小溪；
                                     //  原 0.20 使源点只聚集在少数高地，互相 claimed 阻断，密度上不去）
            2,                       // sourceSpacingCells（≈48wu 间距；1 时河网过密，用户实机反馈"河流有点多"）
                                     //  ★ 2026-08-31 实测：加汇流面积门限后改 1 对河数无影响
                                     //    （14→14），约束不在间距而在候选被已有河路径 claimed，故保持 2。
            2,                       // traceStep（下坡窗口 2 格）
            3,                       // minRiverNodes
            2304.0 * gridCellScale * gridCellScale,   // riverAccumThreshold（wu²）= 4 格汇流面积 × 缩放²
                                     // ★ 2026-09-19【P5 实验已回退，勿重犯】
                                     //   曾试 4 格 → 2 格（1152），目的：让短小源头溪流生成。
                                     //   实测结果（runRiverWidthProfileProbe，25 region）：
                                     //     · 河数 85 → 101（+19%）、密度 3.4 → 4.0
                                     //     · 最大半宽 ≥8 块 34.2% → 13.9%（改善"有的很宽"）
                                     //     · ★ 但【节点数 <10 的河仍是 0】⇒ 目的（短溪）没达到
                                     //     · ★★ 且使【湖面不平 σ 由 0.471812 恶化到 0.912168】
                                     //        （runLakeLevelFlatnessProbe，604→573 格水体，跨度 2.1→2.98 块）
                                     //   ⇒ 用一个【用户已报的缺陷】（湖面不平）去换一个【观感改善】不划算，已回退。
                                     //   ⚠ 归因教训：P2 那次改选湖规则时我同时带着本改动，
                                     //     差点把"σ 恶化"错记到 P2 头上 —— 单变量对照不可省。
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
            // （2026-09-19：曾在此处加 minBankHeight「河谷最小岸高」，实测【无效】已撤销。
            //   见 HydrologyBlockCarver 干地下界处的说明，与 PLAN-hydrology.md §1.18。）
            1.5,                     // formRunFactor（内层定形带 = width × 1.5）
            12.0,                    // seamRun（外层接缝带宽上限 12 block）
            6.0,                     // meanderAmp（蜿蜒振幅 block）
                                     //   ★ 2026-09-20：2.5 → 6.0。用户判据"运动路线还是有点
                                     //     不自然，感觉像采样太稀少" —— 根因是 D8 采样格距 48 block，
                                     //     而 2.5 块的蜿蜒相对格距几乎不可见（试过降格距到 24 block：
                                     //     形态变好但水体膨胀 + 耗时 ×4 + 全套 wu 半径需重标定 ⇒ 放弃）。
                                     //     ⇒ 改在【可见折线】层加蜿蜒：零成本、不动世界。
                                     //     与新加的"自贴近守卫"配合（守卫拦自绕，见 traceRiver）。
            24.0,                    // meanderWavelength（蜿蜒波长 block）
                                     //   ★ 2026-09-20：40 → 24，与 meanderAmp 6.0 配套
                                     //     （振幅增大时波长须相应缩短，否则弯道被拉直成缓波）。
            80,                      // riverCount（每 region 最大河数，加密河网）
            96.0,                    // borderDist（≈0.15×regionSize，边界安全距 wu）
            120.0,                   // lakeRadius（湖面半径 wu）
            8.0,                     // lakeMargin（湖面边距 wu）
            100.0,                   // lakeFadeDist（湖高淡出距 wu）
            100.0,                   // heightBlendDist（多河 IDW 混合半径 wu）
            1.5,                     // blendExp（河高混合回地形幂次）
            1e-6,                    // minDrop（最小下坡量）
            4.0,                     // smoothMinK（smooth-min 合并宽度 block）
            576.0 * gridCellScale * gridCellScale,   // widthAreaRef（= gridCell²，单格汇流面积）× 缩放²
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
            3,                       // waterfallMaxSteps（单 run 最大台阶数：保证大落差级）
            // ===== 峡谷（★ 2026-09-14）=====
            8.0,                     // canyonMinBank（岸高 ≥8 格起进入峡谷过渡）
            20.0,                    // canyonFullBank（岸高 ≥20 格达全强度；
                                     //   ★ 实测 CanyonProfileProbe：谷壁带内最大岸高 22.6 格，
                                     //     故 20 使最深的河谷接近全强度、且 8~20 平滑过渡）
            0.40,                    // canyonSlopeRun（≈67° 上限；对照 bankSlopeRun=1.5 ≈32°）
            1.6,                     // canyonWallFactor（谷底带 = 1.6×半宽；对照 bankFactor=2.5）
            ForkParams.defaults()    // ★ 支流分叉（默认关闭 ⇒ 零行为变更）
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
