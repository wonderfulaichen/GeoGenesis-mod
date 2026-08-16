package com.geogenesis.worldgen.river;

/**
 * 河段类型（确定性几何河网 · Phase 1）。
 *
 * <p>对齐 Farseek/Streams 的段分类（MIT）：
 * <ul>
 *   <li>{@link #REACH}：主河段（主干河链上的每一 basin 一段）</li>
 *   <li>{@link #TRIBUTARY}：支流段（Dijkstra 从源头泉眼追到主河接入点）</li>
 *   <li>{@link #MOUTH}：河口段（链尾 basin 中心 e &lt; 0，河流入海终止）</li>
 * </ul>
 */
public enum RiverSegmentType {
    REACH,
    TRIBUTARY,
    MOUTH
}
