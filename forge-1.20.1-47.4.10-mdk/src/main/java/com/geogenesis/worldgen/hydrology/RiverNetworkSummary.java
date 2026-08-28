package com.geogenesis.worldgen.hydrology;

import java.util.List;

/** 河网拓扑摘要，为后续河床剖面与游戏接入保留稳定数据边界。 */
public record RiverNetworkSummary(List<RiverSegment> segments, List<RiverOutlet> outlets,
                                  RiverSegment mainStem, double totalDischarge) {
}
