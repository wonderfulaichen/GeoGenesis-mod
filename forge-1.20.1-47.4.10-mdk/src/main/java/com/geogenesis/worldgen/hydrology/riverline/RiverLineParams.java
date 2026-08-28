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
    /** 宽度/深度面积驱动的对数动态范围：汇流面积达 riverAccumThreshold×此值 时宽度到 max。 */
    double areaLogRange,
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
    double smoothMinK
) {
    public static RiverLineParams defaults() {
        return new RiverLineParams(
            640,                     // regionSize
            0.16,                    // jitter（旧分形用）
            4,                       // fractalLevels（旧分形用）
            96.0,                    // anchorSnapRadius（旧锚点用）
            12.0,                    // anchorSnapStep（旧锚点用）
            40.0,                    // valleyBiasAmp（旧贴谷用）
            3.0,                     // minWidth（半宽 block）★加宽：源头也有可见水面
            8.0,                     // maxWidth（半宽 block，海口宽河）
            2.5,                     // minDepth（block）★加深：满足 carved<水面−0.5 灌水门控
            7.0,                     // maxDepth（block）
            2.5,                     // bankFactor
            0.30,                    // fadeHighE（已弃用）
            0.10,                    // fadeLowE（已弃用）
            1.0,                     // surfaceSink
            2048.0,                  // minDischargeArea（旧门控用）
            -0.02,                   // oceanE
            1.0,                     // mountainScale（=1.0：恒等，原始 e，匹配 a7a8d68 基线河位）
            24.0,                    // gridCell（D8 采样分辨率）
            512,                     // maxTraceSteps
            64.0,                    // areaLogRange
            0.20,                    // sourceMinE（option A：低地也出河，河网更密）
            3,                       // sourceSpacingCells（≈72wu 间距，加密源点→河网更密）
            2,                       // traceStep（下坡窗口 2 格）
            3,                       // minRiverNodes
            2000.0,                  // riverAccumThreshold（wu²）
            0.5,                     // slopeDrop（block：源端缓降）
            2.5,                     // bankWidth（河谷壁系数 ×半宽）
            1.5,                     // valleyExp（谷壁陡缓）
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
            4.0                      // smoothMinK（smooth-min 合并宽度 block）
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
