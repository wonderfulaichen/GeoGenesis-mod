package com.geogenesis.worldgen.terrain;

/**
 * 统一嵌套样条树（对标 MC offset.json）。
 * 
 * Phase 1：2 层嵌套（大陆性 c → 内层样条 lo/hi）
 * Phase 2：3 层嵌套（大陆性 c → 地形类型 → 内层样条）
 * 
 * 结构：
 * 外层样条（大陆性 c）→ 中层样条（地形类型）→ 内层样条（lo/hi 形状）
 */
public final class UnifiedSpline {
    
    // 外层样条控制点（大陆性 c）
    private final OuterNode[] outerNodes;
    
    public UnifiedSpline(OuterNode[] outerNodes) {
        // 按 location 排序（必须单调递增）
        java.util.Arrays.sort(outerNodes, (a, b) -> Double.compare(a.location, b.location));
        this.outerNodes = outerNodes;
    }
    
    /**
     * 外层样条节点（大陆性 c 控制点）。
     * Phase 2：包含中层样条（地形类型分布）。
     */
    public static class OuterNode {
        public final double location;  // c 值（-1.0 到 1.0）
        public final MidSpline midSpline;  // 中层样条（地形类型分布）
        public final double derivative;  // 导数（MC 会乘以段宽）
        
        public OuterNode(double location, MidSpline midSpline, double derivative) {
            this.location = location;
            this.midSpline = midSpline;
            this.derivative = derivative;
        }
    }
    
    /**
     * 中层样条（地形类型分布）。
     * 
     * 每个中层样条包含多个控制点，每个控制点对应一个地形类型。
     * 控制点的 location 是类型在类型轴上的位置（0.0 到 1.0）。
     * 控制点的 value 是该类型的权重（0.0 到 1.0）。
     * 控制点的 innerSpline 是该类型的内层样条（lo/hi 形状）。
     */
    public static class MidSpline {
        public final MidNode[] nodes;  // 中层控制点（地形类型）
        
        public MidSpline(MidNode[] nodes) {
            // 按 location 排序（必须单调递增）
            java.util.Arrays.sort(nodes, (a, b) -> Double.compare(a.location, b.location));
            this.nodes = nodes;
        }
        
        /**
         * 根据类型位置采样内层样条。
         * 
         * @param typePosition 类型在类型轴上的位置（0.0 到 1.0）
         * @return 内层样条（lo/hi 形状）
         */
        public InnerSpline sampleInner(double typePosition) {
            if (nodes.length == 0) return null;
            if (nodes.length == 1) return nodes[0].innerSpline;
            
            // 二分查找最近的控制点
            int lo2 = 0, hi2 = nodes.length - 1;
            while (lo2 < hi2) {
                int mid = (lo2 + hi2) >>> 1;
                if (nodes[mid].location < typePosition) lo2 = mid + 1;
                else hi2 = mid;
            }
            int i = lo2;
            
            // 边界处理
            if (i <= 0) return nodes[0].innerSpline;
            if (i >= nodes.length) return nodes[nodes.length - 1].innerSpline;
            
            // 返回最近的控制点（Phase 2 简化：不插值）
            double distLeft = typePosition - nodes[i - 1].location;
            double distRight = nodes[i].location - typePosition;
            
            return distLeft <= distRight ? nodes[i - 1].innerSpline : nodes[i].innerSpline;
        }
        
        /**
         * 根据类型位置采样类型权重。
         * 
         * @param typePosition 类型在类型轴上的位置（0.0 到 1.0）
         * @return 类型权重（0.0 到 1.0）
         */
        public double sampleWeight(double typePosition) {
            if (nodes.length == 0) return 0.0;
            if (nodes.length == 1) return nodes[0].weight;
            
            // 二分查找最近的控制点
            int lo2 = 0, hi2 = nodes.length - 1;
            while (lo2 < hi2) {
                int mid = (lo2 + hi2) >>> 1;
                if (nodes[mid].location < typePosition) lo2 = mid + 1;
                else hi2 = mid;
            }
            int i = lo2;
            
            // 边界处理
            if (i <= 0) return nodes[0].weight;
            if (i >= nodes.length) return nodes[nodes.length - 1].weight;
            
            // 返回最近的控制点（Phase 2 简化：不插值）
            double distLeft = typePosition - nodes[i - 1].location;
            double distRight = nodes[i].location - typePosition;
            
            return distLeft <= distRight ? nodes[i - 1].weight : nodes[i].weight;
        }
    }
    
    /**
     * 中层样条节点（地形类型控制点）。
     */
    public static class MidNode {
        public final double location;  // 类型在类型轴上的位置（0.0 到 1.0）
        public final double weight;    // 类型权重（0.0 到 1.0）
        public final InnerSpline innerSpline;  // 内层样条（lo/hi 形状）
        public final double derivative;  // 导数
        
        public MidNode(double location, double weight, InnerSpline innerSpline, double derivative) {
            this.location = location;
            this.weight = weight;
            this.innerSpline = innerSpline;
            this.derivative = derivative;
        }
    }
    
    /**
     * 内层样条（lo/hi 形状控制）。
     * 
     * 每个内层样条包含：
     * - loSpline：噪声值 → lo 值的样条
     * - hiSpline：噪声值 → hi 值的样条
     */
    public static class InnerSpline {
        public final Spline loSpline;  // lo 样条
        public final Spline hiSpline;  // hi 样条
        
        public InnerSpline(Spline loSpline, Spline hiSpline) {
            this.loSpline = loSpline;
            this.hiSpline = hiSpline;
        }
        
        /**
         * 根据噪声值计算 lo/hi 范围。
         */
        public double[] sampleRange(double noiseValue) {
            double lo = loSpline.sample(noiseValue);
            double hi = hiSpline.sample(noiseValue);
            return new double[]{lo, hi};
        }
    }
    
    /**
     * 单条样条（Cubic Hermite）。
     */
    public static class Spline {
        private final double[] locations;
        private final double[] values;
        private final double[] derivatives;
        
        public Spline(double[] locations, double[] values, double[] derivatives) {
            this.locations = locations;
            this.values = values;
            this.derivatives = derivatives;
        }
        
        /**
         * 采样样条值。
         */
        public double sample(double x) {
            return SplineUtil.splint(locations, values, derivatives, x);
        }
    }
    
    /**
     * Phase 2：通过 3 层嵌套样条计算 eLand。
     * 
     * @param c 大陆性值（-1.0 到 1.0）
     * @param typePosition 类型在类型轴上的位置（0.0 到 1.0）
     * @param noiseValue 地形类型噪声值（0.0 到 1.0）
     * @return eLand 值
     */
    public double sample(double c, double typePosition, double noiseValue) {
        // 1. 通过外层样条找到当前 c 值对应的中层样条
        MidSpline midSpline = sampleOuter(c);
        if (midSpline == null) {
            return 0.0; // fallback
        }
        
        // 2. 通过中层样条找到当前类型位置对应的内层样条
        InnerSpline innerSpline = midSpline.sampleInner(typePosition);
        if (innerSpline == null) {
            return 0.0; // fallback
        }
        
        // 3. 通过内层样条计算 lo/hi
        double[] range = innerSpline.sampleRange(noiseValue);
        double lo = range[0];
        double hi = range[1];
        
        // 4. 噪声值映射到 [lo, hi] 范围
        double eLand = lo + (hi - lo) * noiseValue;
        
        // 5. 钳制
        return eLand < -1.0 ? -1.0 : (eLand > 1.0 ? 1.0 : eLand);
    }
    
    /**
     * Phase 1 兼容：通过 2 层嵌套样条计算 eLand。
     * 
     * @param c 大陆性值（-1.0 到 1.0）
     * @param noiseValue 地形类型噪声值（0.0 到 1.0）
     * @return eLand 值
     */
    public double sample(double c, double noiseValue) {
        // Phase 1：使用第一个中层节点的内层样条
        MidSpline midSpline = sampleOuter(c);
        if (midSpline == null || midSpline.nodes.length == 0) {
            return 0.0;
        }
        
        // 使用第一个中层节点的内层样条
        InnerSpline innerSpline = midSpline.nodes[0].innerSpline;
        if (innerSpline == null) {
            return 0.0;
        }
        
        // 通过内层样条计算 lo/hi
        double[] range = innerSpline.sampleRange(noiseValue);
        double lo = range[0];
        double hi = range[1];
        
        // 噪声值映射到 [lo, hi] 范围
        double eLand = lo + (hi - lo) * noiseValue;
        
        // 钳制
        return eLand < -1.0 ? -1.0 : (eLand > 1.0 ? 1.0 : eLand);
    }
    
    /**
     * 通过外层样条采样中层样条。
     * 
     * @param c 大陆性值（-1.0 到 1.0）
     * @return 中层样条（地形类型分布）
     */
    private MidSpline sampleOuter(double c) {
        if (outerNodes.length == 0) return null;
        if (outerNodes.length == 1) return outerNodes[0].midSpline;
        
        // 二分查找最近的控制点
        int lo2 = 0, hi2 = outerNodes.length - 1;
        while (lo2 < hi2) {
            int mid = (lo2 + hi2) >>> 1;
            if (outerNodes[mid].location < c) lo2 = mid + 1;
            else hi2 = mid;
        }
        int i = lo2;
        
        // 边界处理
        if (i <= 0) return outerNodes[0].midSpline;
        if (i >= outerNodes.length) return outerNodes[outerNodes.length - 1].midSpline;
        
        // 返回最近的控制点（Phase 2 简化：不插值）
        double distLeft = c - outerNodes[i - 1].location;
        double distRight = outerNodes[i].location - c;
        
        return distLeft <= distRight ? outerNodes[i - 1].midSpline : outerNodes[i].midSpline;
    }
    
    /**
     * 从 TerrainParams 构建默认统一样条。
     */
    public static UnifiedSpline fromTerrainParams(TerrainParams p) {
        // TODO Phase 1：从配置构建样条
        // 暂时返回空样条
        return new UnifiedSpline(new OuterNode[0]);
    }
    
    /**
     * 从旧的 center ± halfRange 转换为样条（向后兼容）。
     */
    public static UnifiedSpline fromLegacyConfig(double[] centers, double[] halfRanges) {
        if (centers.length != halfRanges.length || centers.length == 0) {
            return new UnifiedSpline(new OuterNode[0]);
        }
        
        OuterNode[] nodes = new OuterNode[centers.length];
        for (int i = 0; i < centers.length; i++) {
            double lo = centers[i] - halfRanges[i];
            double hi = centers[i] + halfRanges[i];
            
            // lo 样条：常量 = lo
            Spline loSpline = new Spline(
                new double[]{0.0, 1.0},
                new double[]{lo, lo},
                new double[]{0.0, 0.0}
            );
            
            // hi 样条：常量 = hi
            Spline hiSpline = new Spline(
                new double[]{0.0, 1.0},
                new double[]{hi, hi},
                new double[]{0.0, 0.0}
            );
            
            InnerSpline innerSpline = new InnerSpline(loSpline, hiSpline);
            
            // Phase 2：创建中层样条（单节点，权重=1.0）
            MidNode midNode = new MidNode(0.0, 1.0, innerSpline, 0.0);
            MidSpline midSpline = new MidSpline(new MidNode[]{midNode});
            
            nodes[i] = new OuterNode(centers[i], midSpline, 0.0);
        }
        
        return new UnifiedSpline(nodes);
    }
}
