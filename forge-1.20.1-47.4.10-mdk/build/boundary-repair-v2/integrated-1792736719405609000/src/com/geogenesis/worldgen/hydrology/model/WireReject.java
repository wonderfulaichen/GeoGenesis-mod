package com.geogenesis.worldgen.hydrology.model;

/**
 * ★ v2：<b>一条未兑现的端口承诺</b>（显式留痕，绝不当成功）。
 *
 * <p>旧实现里"没接上"只有两种下场：① 静默不画（看着像本来就没河）；
 * ② 抛异常把整个 region 打断。两种都不算证据。v2 一律记录成一条本类型：</p>
 * <ul>
 *   <li>{@code WIRE_ANCHOR_MOVED} —— 锚点被挪动（typed 接力禁止 bestHandoffStart 改道）；</li>
 *   <li>{@code WIRE_NEXT_OUT_OF_WINDOW} —— 承诺的下一步不落在本区窗口；</li>
 *   <li>{@code WIRE_NEXT_NOT_ADJACENT} —— 下一步与锚点不相邻；</li>
 *   <li>{@code WIRE_NEXT_CLIMBS} —— 下一步比锚点高（零爬坡硬约束）；</li>
 *   <li>{@code WIRE_DIR_MISSING} / {@code WIRE_DIR_MISMATCH} —— 来向缺失/与网格边不符；</li>
 *   <li>{@code BASE_NO_SUCCESSOR} / {@code BASE_JOIN_WITHOUT_DOWNSTREAM} /
 *       {@code BASE_TERMINAL_NOT_WATER:<end>} —— base 里"接上了"但<b>后继审计</b>证不出水体；</li>
 *   <li>{@code PENDING_NEXT_OTHER_OWNER} / {@code ANCHOR_OFF_GRID} / {@code NEXT_OFF_GRID} /
 *       {@code DUPLICATE_ANCHOR} —— 装配期（端口→WireIn）的几何拒收。</li>
 * </ul>
 *
 * <p>⚠ 出现任何一条 ⇒ 该端口<b>未闭环</b>。审计据此判红；不得用"过滤后没有坏链"当通过依据。</p>
 *
 * @param anchorCell 接收方窗口格索引（无法换算时 {@code -1}）
 * @param anchorX    共享锚点世界坐标 X（未知 = {@link Integer#MIN_VALUE}）
 * @param anchorZ    共享锚点世界坐标 Z
 * @param status     拒收原因（见上）
 */
public record WireReject(int anchorCell, int anchorX, int anchorZ, String status) {

    @Override
    public String toString() {
        return "reject[" + status + " cell=" + anchorCell + " anchor=(" + anchorX + ","
                + anchorZ + ")]";
    }
}
