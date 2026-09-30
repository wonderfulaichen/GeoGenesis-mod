package com.geogenesis.worldgen.hydrology.sim;

/**
 * tile 边界端口的种类。
 *
 * <p>端口是无限世界拓扑完整性的载体：tile 内未终结的水必须落在某个端口上，
 * 由相邻 tile 的 {@link #INGRESS} 侧接管。<b>禁止把"到边界就没了"当成合法终点。</b></p>
 */
public enum PortKind {

    /** 本 tile 向外输出水量（下游在邻 tile）。 */
    EGRESS,

    /** 邻 tile 向内输入水量（本 tile 是下游侧）。 */
    INGRESS,

    /** 边界格本身已低于海平面 ⇒ 入海，无需递归。 */
    SEA,

    /** 洼地的最低溢口正好落在边界上 ⇒ 溢出到邻 tile。 */
    SPILLWAY,

    /** 完整内流盆地：在当前影响半径内证明无出流（由衰减或显式闭合判定）。 */
    CLOSED_BASIN,

    /** 已达到递归上限仍未见海/盆地出口 ⇒ 保守未决（**不是**断头）。 */
    PENDING
}
