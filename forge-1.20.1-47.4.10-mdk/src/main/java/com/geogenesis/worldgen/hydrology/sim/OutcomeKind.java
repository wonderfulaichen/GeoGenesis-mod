package com.geogenesis.worldgen.hydrology.sim;

/**
 * 一条水系的终点种类 —— 验收口径：**不允许 {@link #LAND_SINK} 作为最终结果**。
 *
 * <p>PLAN / 用户判据："每条河终点必须是湖泊、海洋或跨区续流出口"。
 * 故本枚举里 {@code LAND_SINK} 只作为<b>内部诊断标记</b>存在，生产输出必须为 0。</p>
 */
public enum OutcomeKind {

    /** 入海（水面 = 海平面）。 */
    SEA,

    /** 进入下一个盆地（洼地蓄水 → 该盆地自己的溢口继续）。 */
    SPILL_TO_BASIN,

    /** 跨 tile 出口，由邻 tile 接管。 */
    SPILL_TO_TILE,

    /** 在当前影响半径内证明闭合的内流盆地（干旱区蒸发平衡下的蓄水终点）。 */
    CLOSED_BASIN,

    /** 未决：递归上限内没到海/盆地出口。保守标记，需更大半径重解，**不是断头**。 */
    BOUNDARY_PENDING,

    /** 陆地凭空断头。**生产输出必须恒为 0**，仅用于反向验证与诊断。 */
    LAND_SINK
}
