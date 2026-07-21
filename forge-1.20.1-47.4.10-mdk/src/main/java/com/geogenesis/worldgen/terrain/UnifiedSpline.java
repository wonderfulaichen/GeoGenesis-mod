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
         * 根据类型位置采样内层样条（线性插值）。
         * <p>
         * 在相邻类型节点间做线性插值，确保类型边界处连续过渡。
         * 
         * @param typePosition 类型在类型轴上的位置（0.0 到 1.0）
         * @return 内层样条（lo/hi 形状），如果需要插值则返回 null（调用方需用 sampleRangeInterpolated）
         */
        public InnerSpline sampleInner(double typePosition) {
            if (nodes.length == 0) return null;
            if (nodes.length == 1) return nodes[0].innerSpline;
            
            int idx = findBracketIndex(nodes, typePosition);
            if (idx <= 0) return nodes[0].innerSpline;
            if (idx >= nodes.length) return nodes[nodes.length - 1].innerSpline;
            return nodes[idx].innerSpline;
        }
        
        /**
         * 根据类型位置采样插值后的 lo/hi 范围（跨类型节点线性插值）。
         * <p>
         * 这是保证连续性的核心方法：在相邻类型节点间对 lo/hi 值做线性插值。
         * 
         * @param typePosition 类型位置（0.0 到 1.0）
         * @param noiseValue 噪声值（0.0 到 1.0）
         * @return [lo, hi] 插值后的范围
         */
        public double[] sampleRangeInterpolated(double typePosition, double noiseValue) {
            if (nodes.length == 0) return new double[]{0.0, 0.0};
            if (nodes.length == 1) return nodes[0].innerSpline.sampleRange(noiseValue);
            
            int idx = findBracketIndex(nodes, typePosition);
            if (idx <= 0) return nodes[0].innerSpline.sampleRange(noiseValue);
            if (idx >= nodes.length) return nodes[nodes.length - 1].innerSpline.sampleRange(noiseValue);
            
            // 线性插值两个相邻节点的 lo/hi
            double locL = nodes[idx - 1].location, locR = nodes[idx].location;
            double t = (locR > locL) ? (typePosition - locL) / (locR - locL) : 0.0;
            
            double[] rangeL = nodes[idx - 1].innerSpline.sampleRange(noiseValue);
            double[] rangeR = nodes[idx].innerSpline.sampleRange(noiseValue);
            
            return new double[]{
                rangeL[0] + (rangeR[0] - rangeL[0]) * t,
                rangeL[1] + (rangeR[1] - rangeL[1]) * t
            };
        }
        
        /**
         * 根据类型位置采样类型权重（线性插值）。
         */
        public double sampleWeight(double typePosition) {
            if (nodes.length == 0) return 0.0;
            if (nodes.length == 1) return nodes[0].weight;
            
            int idx = findBracketIndex(nodes, typePosition);
            if (idx <= 0) return nodes[0].weight;
            if (idx >= nodes.length) return nodes[nodes.length - 1].weight;
            
            double locL = nodes[idx - 1].location, locR = nodes[idx].location;
            double t = (locR > locL) ? (typePosition - locL) / (locR - locL) : 0.0;
            return nodes[idx - 1].weight + (nodes[idx].weight - nodes[idx - 1].weight) * t;
        }
        
        /** 二分查找：返回第一个 location > position 的节点索引 */
        private static int findBracketIndex(MidNode[] nodes, double position) {
            int lo = 0, hi = nodes.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (nodes[mid].location <= position) lo = mid + 1;
                else hi = mid;
            }
            return lo;
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
     * Phase 2：通过 3 层嵌套样条计算 eLand（每层线性插值）。
     * <p>
     * 外层（c）→ 中层（typePosition）→ 内层（noiseValue）→ eLand，
     * 每层在相邻控制点间做线性插值，确保三层都是 C0 连续。
     * 
     * @param c 大陆性值（-1.0 到 1.0）
     * @param typePosition 类型在类型轴上的位置（0.0 到 1.0）
     * @param noiseValue 地形类型噪声值（0.0 到 1.0）
     * @return eLand 值
     */
    public double sample(double c, double typePosition, double noiseValue) {
        if (outerNodes.length == 0) return 0.0;
        if (outerNodes.length == 1) return sampleMid(outerNodes[0].midSpline, typePosition, noiseValue);
        
        // 外层：找到 c 值两侧的 bracketing 节点
        int outerIdx = findOuterBracket(c);
        
        if (outerIdx <= 0) {
            return sampleMid(outerNodes[0].midSpline, typePosition, noiseValue);
        }
        if (outerIdx >= outerNodes.length) {
            return sampleMid(outerNodes[outerNodes.length - 1].midSpline, typePosition, noiseValue);
        }
        
        // 外层线性插值
        double cL = outerNodes[outerIdx - 1].location, cR = outerNodes[outerIdx].location;
        double tOuter = (cR > cL) ? (c - cL) / (cR - cL) : 0.0;
        
        double eLandL = sampleMid(outerNodes[outerIdx - 1].midSpline, typePosition, noiseValue);
        double eLandR = sampleMid(outerNodes[outerIdx].midSpline, typePosition, noiseValue);
        
        return clamp(eLandL + (eLandR - eLandL) * tOuter, -1.0, 1.0);
    }
    
    /**
     * Phase 1 兼容：通过 2 层嵌套样条计算 eLand（外层线性插值，内层用第一个类型节点）。
     * 
     * @param c 大陆性值（-1.0 到 1.0）
     * @param noiseValue 地形类型噪声值（0.0 到 1.0）
     * @return eLand 值
     */
    public double sample(double c, double noiseValue) {
        if (outerNodes.length == 0) return 0.0;
        
        // 使用第一个中层节点的内层样条（Phase 1 兼容）
        if (outerNodes.length == 1) {
            return computeELand(firstInner(outerNodes[0]), noiseValue);
        }
        
        int outerIdx = findOuterBracket(c);
        
        if (outerIdx <= 0) {
            return computeELand(firstInner(outerNodes[0]), noiseValue);
        }
        if (outerIdx >= outerNodes.length) {
            return computeELand(firstInner(outerNodes[outerNodes.length - 1]), noiseValue);
        }
        
        double cL = outerNodes[outerIdx - 1].location, cR = outerNodes[outerIdx].location;
        double t = (cR > cL) ? (c - cL) / (cR - cL) : 0.0;
        
        double eL = computeELand(firstInner(outerNodes[outerIdx - 1]), noiseValue);
        double eR = computeELand(firstInner(outerNodes[outerIdx]), noiseValue);
        
        return clamp(eL + (eR - eL) * t, -1.0, 1.0);
    }
    
    /** 获取外层节点的第一个中层内层样条（Phase 1 兼容） */
    private static InnerSpline firstInner(OuterNode node) {
        if (node.midSpline == null || node.midSpline.nodes.length == 0) return null;
        return node.midSpline.nodes[0].innerSpline;
    }
    
    /**
     * 中层样条采样：在类型节点间线性插值 lo/hi → eLand。
     */
    private double sampleMid(MidSpline midSpline, double typePosition, double noiseValue) {
        if (midSpline == null || midSpline.nodes.length == 0) return 0.0;
        if (midSpline.nodes.length == 1) return computeELand(midSpline.nodes[0].innerSpline, noiseValue);
        
        // 中层线性插值 lo/hi
        double[] range = midSpline.sampleRangeInterpolated(typePosition, noiseValue);
        double lo = range[0], hi = range[1];
        double eLand = lo + (hi - lo) * noiseValue;
        return clamp(eLand, -1.0, 1.0);
    }
    
    /** 内层样条 → eLand */
    private static double computeELand(InnerSpline innerSpline, double noiseValue) {
        if (innerSpline == null) return 0.0;
        double[] range = innerSpline.sampleRange(noiseValue);
        double eLand = range[0] + (range[1] - range[0]) * noiseValue;
        return eLand < -1.0 ? -1.0 : (eLand > 1.0 ? 1.0 : eLand);
    }
    
    /** 外层二分查找：返回第一个 location > c 的节点索引 */
    private int findOuterBracket(double c) {
        int lo = 0, hi = outerNodes.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (outerNodes[mid].location <= c) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
    
    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
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
