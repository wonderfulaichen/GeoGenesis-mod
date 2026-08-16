package com.geogenesis.worldgen.river;

import java.util.List;

/**
 * 河段（确定性几何河网 · Phase 1）。
 *
 * <p>段是河网的基本单元：主河链上每 basin 一段，支流（Dijkstra 路径）一整条为一段。
 * 相邻主河段共享入/出界点（后段 path[0] = 前段 path[last]），是结构性无缝的雏形
 * （Phase 3 升级为横截面逐列副本）。所有字段构建期确定，不可变、线程安全。</p>
 */
public final class RiverSegment {

    /** 段唯一 ID（探针/诊断去重用；分配顺序确定，不参与任何河网逻辑） */
    private static final java.util.concurrent.atomic.AtomicInteger UID_SEQ = new java.util.concurrent.atomic.AtomicInteger();
    public final int uid = UID_SEQ.incrementAndGet();

    public final RiverSegmentType type;
    /** 所属 basin（支流段 = 支流 basin；主河段 = 链上 basin） */
    public final int basinX, basinZ;
    /** 点链（wu，≥2 点） */
    public final List<RiverNode> path;
    /** 上游段所属 basin（-1,-1 = 源头） */
    public final int upstreamBasinX, upstreamBasinZ;
    /** 下游段所属 basin（-1,-1 = 入海/盆地终止） */
    public final int downstreamBasinX, downstreamBasinZ;
    /** 水面 Y（主河 = 全局海平面；支流 = 抬升后段级水面） */
    public final double surfaceLevel;
    /** 河床 Y（Phase 1 段级 = surfaceLevel − 段深；Phase 2 横截面细化） */
    public final double bedLevel;
    /** 河道半宽（wu，段级代表值 = 最宽点；采样按节点插值宽度） */
    public final double width;
    /** 每节点半宽（wu，与 path 对齐；null = 恒 width）。DW segmentWidths taper 同款 */
    public final double[] nodeWidths;
    /** 流量（Phase 1 = 1 + 已汇入支流数；Phase 4 供 RIVER_NETWORK 图层） */
    public final int discharge;
    /** 包围盒（wu，采样快速剔除用；构造期算好） */
    public final double minX, maxX, minZ, maxZ;
    /** DW MountainRiverPath 引用（山地河段缓存；主河段 null）——供邻域 grid 收集 */
    public final Object mountainPath;

    public RiverSegment(RiverSegmentType type, int basinX, int basinZ,
                        List<RiverNode> path,
                        int upstreamBasinX, int upstreamBasinZ,
                        int downstreamBasinX, int downstreamBasinZ,
                        double surfaceLevel, double bedLevel,
                        double width, int discharge) {
        this(type, basinX, basinZ, path, upstreamBasinX, upstreamBasinZ,
            downstreamBasinX, downstreamBasinZ, surfaceLevel, bedLevel,
            width, null, discharge);
    }

    public RiverSegment(RiverSegmentType type, int basinX, int basinZ,
                        List<RiverNode> path,
                        int upstreamBasinX, int upstreamBasinZ,
                        int downstreamBasinX, int downstreamBasinZ,
                        double surfaceLevel, double bedLevel,
                        double width, double[] nodeWidths, int discharge) {
        this(type, basinX, basinZ, path, upstreamBasinX, upstreamBasinZ,
            downstreamBasinX, downstreamBasinZ, surfaceLevel, bedLevel,
            width, nodeWidths, discharge, null);
    }

    /** 全参构造（mountainPath 供 DW 山地河段缓存） */
    public RiverSegment(RiverSegmentType type, int basinX, int basinZ,
                        List<RiverNode> path,
                        int upstreamBasinX, int upstreamBasinZ,
                        int downstreamBasinX, int downstreamBasinZ,
                        double surfaceLevel, double bedLevel,
                        double width, double[] nodeWidths, int discharge,
                        Object mountainPath) {
        this.type = type;
        this.basinX = basinX;
        this.basinZ = basinZ;
        this.path = List.copyOf(path);
        this.upstreamBasinX = upstreamBasinX;
        this.upstreamBasinZ = upstreamBasinZ;
        this.downstreamBasinX = downstreamBasinX;
        this.downstreamBasinZ = downstreamBasinZ;
        this.surfaceLevel = surfaceLevel;
        this.bedLevel = bedLevel;
        this.width = width;
        this.nodeWidths = nodeWidths;
        this.discharge = discharge;
        this.mountainPath = mountainPath;
        double mnX = Double.POSITIVE_INFINITY, mxX = Double.NEGATIVE_INFINITY;
        double mnZ = Double.POSITIVE_INFINITY, mxZ = Double.NEGATIVE_INFINITY;
        for (RiverNode n : this.path) {
            mnX = Math.min(mnX, n.x());
            mxX = Math.max(mxX, n.x());
            mnZ = Math.min(mnZ, n.z());
            mxZ = Math.max(mxZ, n.z());
        }
        this.minX = mnX;
        this.maxX = mxX;
        this.minZ = mnZ;
        this.maxZ = mxZ;
    }

    /** 路径长度（wu） */
    public double length() {
        double len = 0;
        RiverNode prev = null;
        for (RiverNode n : path) {
            if (prev != null) {
                double dx = n.x() - prev.x(), dz = n.z() - prev.z();
                len += Math.sqrt(dx * dx + dz * dz);
            }
            prev = n;
        }
        return len;
    }

    @Override
    public String toString() {
        return "RiverSegment[" + type + " basin=(" + basinX + "," + basinZ
            + ") pts=" + path.size() + " surf=" + surfaceLevel + " bed=" + bedLevel
            + " w=" + width + " dis=" + discharge + "]";
    }
}
