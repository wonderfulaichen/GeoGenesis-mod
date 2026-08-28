package com.geogenesis.worldgen.hydrology;

/** 河网中一条从源头或汇流点到下一个节点的有向河段。 */
public record RiverSegment(int source, int target, int length,
                           double sourceFlow, double targetFlow) {
}
