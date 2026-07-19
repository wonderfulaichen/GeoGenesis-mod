package com.geogenesis.worldgen.terrain;

/**
 * Phase 2：中层样条配置（地形类型分布）。
 * <p>
 * 每个外层控制点（大陆性 c 值）对应一个中层样条，定义该 c 值处的地形类型分布。
 * 
 * 结构：
 * - 每个中层样条有 5 个控制点（对应 5 个核心陆地类型）
 * - 每个控制点有 3 个字段：location（类型位置）、weight（类型权重）、derivative（导数）
 * - 7 个外层节点 × 5 个类型 × 3 字段 = 105 字段
 */
public record MidSplineConfig(
    // ===== 外层节点 0（深海 c=-0.80）的类型分布 =====
    double node0Type0Loc, double node0Type0Weight, double node0Type0Deriv,  // PLAIN
    double node0Type1Loc, double node0Type1Weight, double node0Type1Deriv,  // HILLS
    double node0Type2Loc, double node0Type2Weight, double node0Type2Deriv,  // MOUNTAINS
    double node0Type3Loc, double node0Type3Weight, double node0Type3Deriv,  // PLATEAU
    double node0Type4Loc, double node0Type4Weight, double node0Type4Deriv,  // BASIN

    // ===== 外层节点 1（大陆架 c=-0.50）的类型分布 =====
    double node1Type0Loc, double node1Type0Weight, double node1Type0Deriv,
    double node1Type1Loc, double node1Type1Weight, double node1Type1Deriv,
    double node1Type2Loc, double node1Type2Weight, double node1Type2Deriv,
    double node1Type3Loc, double node1Type3Weight, double node1Type3Deriv,
    double node1Type4Loc, double node1Type4Weight, double node1Type4Deriv,

    // ===== 外层节点 2（浅海 c=-0.16）的类型分布 =====
    double node2Type0Loc, double node2Type0Weight, double node2Type0Deriv,
    double node2Type1Loc, double node2Type1Weight, double node2Type1Deriv,
    double node2Type2Loc, double node2Type2Weight, double node2Type2Deriv,
    double node2Type3Loc, double node2Type3Weight, double node2Type3Deriv,
    double node2Type4Loc, double node2Type4Weight, double node2Type4Deriv,

    // ===== 外层节点 3（海岸 c=-0.04）的类型分布 =====
    double node3Type0Loc, double node3Type0Weight, double node3Type0Deriv,
    double node3Type1Loc, double node3Type1Weight, double node3Type1Deriv,
    double node3Type2Loc, double node3Type2Weight, double node3Type2Deriv,
    double node3Type3Loc, double node3Type3Weight, double node3Type3Deriv,
    double node3Type4Loc, double node3Type4Weight, double node3Type4Deriv,

    // ===== 外层节点 4（近岸陆地 c=0.20）的类型分布 =====
    double node4Type0Loc, double node4Type0Weight, double node4Type0Deriv,
    double node4Type1Loc, double node4Type1Weight, double node4Type1Deriv,
    double node4Type2Loc, double node4Type2Weight, double node4Type2Deriv,
    double node4Type3Loc, double node4Type3Weight, double node4Type3Deriv,
    double node4Type4Loc, double node4Type4Weight, double node4Type4Deriv,

    // ===== 外层节点 5（内陆 c=0.50）的类型分布 =====
    double node5Type0Loc, double node5Type0Weight, double node5Type0Deriv,
    double node5Type1Loc, double node5Type1Weight, double node5Type1Deriv,
    double node5Type2Loc, double node5Type2Weight, double node5Type2Deriv,
    double node5Type3Loc, double node5Type3Weight, double node5Type3Deriv,
    double node5Type4Loc, double node5Type4Weight, double node5Type4Deriv,

    // ===== 外层节点 6（深远内陆 c=0.80）的类型分布 =====
    double node6Type0Loc, double node6Type0Weight, double node6Type0Deriv,
    double node6Type1Loc, double node6Type1Weight, double node6Type1Deriv,
    double node6Type2Loc, double node6Type2Weight, double node6Type2Deriv,
    double node6Type3Loc, double node6Type3Weight, double node6Type3Deriv,
    double node6Type4Loc, double node6Type4Weight, double node6Type4Deriv
) {
    /** 生产级默认值 */
    public static MidSplineConfig defaults() {
        return new MidSplineConfig(
            // 节点 0（深海）：只有 BASIN 有权重（模拟深海盆地）
            0.0, 0.0, 0.0,  // PLAIN: 无
            0.0, 0.0, 0.0,  // HILLS: 无
            0.0, 0.0, 0.0,  // MOUNTAINS: 无
            0.0, 0.0, 0.0,  // PLATEAU: 无
            0.0, 1.0, 0.0,  // BASIN: 权重=1.0

            // 节点 1（大陆架）：只有 PLAIN 有权重（模拟平坦海底）
            0.0, 1.0, 0.0,  // PLAIN: 权重=1.0
            0.0, 0.0, 0.0,  // HILLS: 无
            0.0, 0.0, 0.0,  // MOUNTAINS: 无
            0.0, 0.0, 0.0,  // PLATEAU: 无
            0.0, 0.0, 0.0,  // BASIN: 无

            // 节点 2（浅海）：只有 PLAIN 有权重（模拟浅海平原）
            0.0, 1.0, 0.0,  // PLAIN: 权重=1.0
            0.0, 0.0, 0.0,  // HILLS: 无
            0.0, 0.0, 0.0,  // MOUNTAINS: 无
            0.0, 0.0, 0.0,  // PLATEAU: 无
            0.0, 0.0, 0.0,  // BASIN: 无

            // 节点 3（海岸）：PLAIN + HILLS（模拟海岸丘陵）
            0.0, 0.7, 0.0,  // PLAIN: 权重=0.7
            0.0, 0.3, 0.0,  // HILLS: 权重=0.3
            0.0, 0.0, 0.0,  // MOUNTAINS: 无
            0.0, 0.0, 0.0,  // PLATEAU: 无
            0.0, 0.0, 0.0,  // BASIN: 无

            // 节点 4（近岸陆地）：PLAIN + HILLS + MOUNTAINS（模拟近岸多样化地形）
            0.0, 0.4, 0.0,  // PLAIN: 权重=0.4
            0.0, 0.3, 0.0,  // HILLS: 权重=0.3
            0.0, 0.2, 0.0,  // MOUNTAINS: 权重=0.2
            0.0, 0.1, 0.0,  // PLATEAU: 权重=0.1
            0.0, 0.0, 0.0,  // BASIN: 无

            // 节点 5（内陆）：所有类型都有权重（模拟内陆多样化地形）
            0.0, 0.3, 0.0,  // PLAIN: 权重=0.3
            0.0, 0.3, 0.0,  // HILLS: 权重=0.3
            0.0, 0.2, 0.0,  // MOUNTAINS: 权重=0.2
            0.0, 0.1, 0.0,  // PLATEAU: 权重=0.1
            0.0, 0.1, 0.0,  // BASIN: 权重=0.1

            // 节点 6（深远内陆）：MOUNTAINS + PLATEAU 主导（模拟深远内陆山脉/高原）
            0.0, 0.1, 0.0,  // PLAIN: 权重=0.1
            0.0, 0.2, 0.0,  // HILLS: 权重=0.2
            0.0, 0.4, 0.0,  // MOUNTAINS: 权重=0.4
            0.0, 0.2, 0.0,  // PLATEAU: 权重=0.2
            0.0, 0.1, 0.0   // BASIN: 权重=0.1
        );
    }

    /**
     * 构建指定外层节点的中层样条。
     * 
     * @param nodeIndex 外层节点索引（0-6）
     * @param inners 内层样条数组（5 个类型）
     * @return 中层样条
     */
    public UnifiedSpline.MidSpline buildMidSpline(int nodeIndex, UnifiedSpline.InnerSpline[] inners) {
        double[] locs = getNodeLocations(nodeIndex);
        double[] weights = getNodeWeights(nodeIndex);
        double[] derivs = getNodeDerivatives(nodeIndex);
        
        UnifiedSpline.MidNode[] nodes = new UnifiedSpline.MidNode[5];
        for (int i = 0; i < 5; i++) {
            nodes[i] = new UnifiedSpline.MidNode(locs[i], weights[i], inners[i], derivs[i]);
        }
        
        return new UnifiedSpline.MidSpline(nodes);
    }

    /** 获取指定节点的类型位置 */
    private double[] getNodeLocations(int nodeIndex) {
        return switch (nodeIndex) {
            case 0 -> new double[]{node0Type0Loc, node0Type1Loc, node0Type2Loc, node0Type3Loc, node0Type4Loc};
            case 1 -> new double[]{node1Type0Loc, node1Type1Loc, node1Type2Loc, node1Type3Loc, node1Type4Loc};
            case 2 -> new double[]{node2Type0Loc, node2Type1Loc, node2Type2Loc, node2Type3Loc, node2Type4Loc};
            case 3 -> new double[]{node3Type0Loc, node3Type1Loc, node3Type2Loc, node3Type3Loc, node3Type4Loc};
            case 4 -> new double[]{node4Type0Loc, node4Type1Loc, node4Type2Loc, node4Type3Loc, node4Type4Loc};
            case 5 -> new double[]{node5Type0Loc, node5Type1Loc, node5Type2Loc, node5Type3Loc, node5Type4Loc};
            case 6 -> new double[]{node6Type0Loc, node6Type1Loc, node6Type2Loc, node6Type3Loc, node6Type4Loc};
            default -> new double[5];
        };
    }

    /** 获取指定节点的类型权重 */
    private double[] getNodeWeights(int nodeIndex) {
        return switch (nodeIndex) {
            case 0 -> new double[]{node0Type0Weight, node0Type1Weight, node0Type2Weight, node0Type3Weight, node0Type4Weight};
            case 1 -> new double[]{node1Type0Weight, node1Type1Weight, node1Type2Weight, node1Type3Weight, node1Type4Weight};
            case 2 -> new double[]{node2Type0Weight, node2Type1Weight, node2Type2Weight, node2Type3Weight, node2Type4Weight};
            case 3 -> new double[]{node3Type0Weight, node3Type1Weight, node3Type2Weight, node3Type3Weight, node3Type4Weight};
            case 4 -> new double[]{node4Type0Weight, node4Type1Weight, node4Type2Weight, node4Type3Weight, node4Type4Weight};
            case 5 -> new double[]{node5Type0Weight, node5Type1Weight, node5Type2Weight, node5Type3Weight, node5Type4Weight};
            case 6 -> new double[]{node6Type0Weight, node6Type1Weight, node6Type2Weight, node6Type3Weight, node6Type4Weight};
            default -> new double[5];
        };
    }

    /** 获取指定节点的类型导数 */
    private double[] getNodeDerivatives(int nodeIndex) {
        return switch (nodeIndex) {
            case 0 -> new double[]{node0Type0Deriv, node0Type1Deriv, node0Type2Deriv, node0Type3Deriv, node0Type4Deriv};
            case 1 -> new double[]{node1Type0Deriv, node1Type1Deriv, node1Type2Deriv, node1Type3Deriv, node1Type4Deriv};
            case 2 -> new double[]{node2Type0Deriv, node2Type1Deriv, node2Type2Deriv, node2Type3Deriv, node2Type4Deriv};
            case 3 -> new double[]{node3Type0Deriv, node3Type1Deriv, node3Type2Deriv, node3Type3Deriv, node3Type4Deriv};
            case 4 -> new double[]{node4Type0Deriv, node4Type1Deriv, node4Type2Deriv, node4Type3Deriv, node4Type4Deriv};
            case 5 -> new double[]{node5Type0Deriv, node5Type1Deriv, node5Type2Deriv, node5Type3Deriv, node5Type4Deriv};
            case 6 -> new double[]{node6Type0Deriv, node6Type1Deriv, node6Type2Deriv, node6Type3Deriv, node6Type4Deriv};
            default -> new double[5];
        };
    }
}
