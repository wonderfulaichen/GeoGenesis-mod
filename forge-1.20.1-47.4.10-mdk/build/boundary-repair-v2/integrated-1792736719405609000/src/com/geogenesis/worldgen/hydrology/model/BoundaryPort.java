package com.geogenesis.worldgen.hydrology.model;

/**
 * ★ 显式出窗端口（ports 模块，<b>隔离实现</b>；未进生产）。
 *
 * <h3>为什么必须有独立类型</h3>
 * <p>旧的 {@link DeterministicSkeleton.ExitSeed} 只有 {@code (cell, dRX, dRZ)}：</p>
 * <ul>
 *   <li>只有【本窗格索引】⇒ 接收方要自己猜窗口原点才能换算世界坐标（已因此错位过）；</li>
 *   <li>只有【框方向】⇒ 接收 owner 只能"落在哪条框就推对角"，而角格会同时落在两条框上；</li>
 *   <li>没有【真实下一步】⇒ 无法区分"沿边框横走"与"真正出窗"。</li>
 * </ul>
 * <p>端口把发送方承诺说全：<b>发送身份 / 共享世界锚点 / 真实下一步 / 来向 / 实际接收 owner</b>，
 * 同时保留 {@code (cell,dRX,dRZ)} 作兼容载荷，使 {@code ExitSeed(int,int,int)} 与
 * {@code List<Integer>} 外部 API 不需要改。</p>
 *
 * <h3>身份层与几何层分开</h3>
 * <p>{@code DeterministicSkeleton} 只产出【窗口内事实】（锚点、下一步、来向 —— 全部世界坐标），
 * 身份字段留 {@link #UNKNOWN_REGION}；区域身份由 {@code ModelHydrologyProvider} 用
 * {@link #stamp(int,int,int)} 盖上（它才知道 region 网格）。这样骨架类不必知道 REGION 布局。</p>
 *
 * @param senderRegionX   发送方 region X（未盖身份 = {@link #UNKNOWN_REGION}）
 * @param senderRegionZ   发送方 region Z
 * @param anchorX         共享世界锚点 block X（= 链末格中心；接收方从这里开始，逐位相同）
 * @param anchorZ         共享世界锚点 block Z
 * @param nextX           真实下一步 block X（按同一向量规则选中的窗外候选；非有限值缺失时 = MIN_VALUE）
 * @param nextZ           真实下一步 block Z
 * @param receiverRegionX 实际接收 owner X（由 {@code next} 世界坐标 floorDiv 得出，不由框推）
 * @param receiverRegionZ 实际接收 owner Z
 * @param dirX            来向单位向量 x（锚点 → 下一步；接收方继承它，避免缝上拐角）
 * @param dirZ            来向单位向量 z
 * @param cell            兼容载荷：本窗格索引
 * @param dRX             兼容载荷：流出方向 X（-1/0/+1）
 * @param dRZ             兼容载荷：流出方向 Z
 */
public record BoundaryPort(int senderRegionX, int senderRegionZ,
                           int anchorX, int anchorZ,
                           int nextX, int nextZ,
                           int receiverRegionX, int receiverRegionZ,
                           double dirX, double dirZ,
                           int cell, int dRX, int dRZ) {

    /** 未盖身份哨兵（骨架层产出的端口就是这个状态）。 */
    public static final int UNKNOWN_REGION = Integer.MIN_VALUE;

    /** 骨架层构造：只有窗口内几何事实，身份未定。 */
    public static BoundaryPort of(int anchorX, int anchorZ, int nextX, int nextZ,
                                  double dirX, double dirZ, int cell, int dRX, int dRZ) {
        return new BoundaryPort(UNKNOWN_REGION, UNKNOWN_REGION, anchorX, anchorZ,
                nextX, nextZ, UNKNOWN_REGION, UNKNOWN_REGION, dirX, dirZ, cell, dRX, dRZ);
    }

    /** 是否已盖身份（区域网格侧 {@link #stamp} 之后）。 */
    public boolean stamped() {
        return senderRegionX != UNKNOWN_REGION || receiverRegionX != UNKNOWN_REGION;
    }

    /** 锚点是否可用（缺 outside 采样时骨架无法给出世界坐标）。 */
    public boolean hasAnchor() {
        return anchorX != Integer.MIN_VALUE || anchorZ != Integer.MIN_VALUE;
    }

    /**
     * 盖身份：发送 = 调用方 region；接收 = {@code next} 世界坐标所属 tract。
     *
     * <p>★ <b>不许按框推对角</b>：真实下一步落在哪个 tract 就是哪个 tract。
     * 角格同时贴两条框，但只有真实下一步那条边决定接收方（这正是候选方案
     * "只发对角种子"猜错的地方）。{@code next} 缺失时才退回 {@code owner + dR}。</p>
     */
    public BoundaryPort stamp(int ownerX, int ownerZ, int tract) {
        int rx = nextX == Integer.MIN_VALUE ? ownerX + dRX : Math.floorDiv(nextX, tract);
        int rz = nextZ == Integer.MIN_VALUE ? ownerZ + dRZ : Math.floorDiv(nextZ, tract);
        return new BoundaryPort(ownerX, ownerZ, anchorX, anchorZ, nextX, nextZ, rx, rz,
                dirX, dirZ, cell, dRX, dRZ);
    }

    /** 发送侧身份键（同区同锚点 = 同一个承诺）。 */
    public String key() {
        return sendKey() + "@" + anchorX + "," + anchorZ + "->" + nextX + "," + nextZ;
    }

    public String sendKey() {
        return senderRegionX + ":" + senderRegionZ;
    }

    /** 接收侧身份键：只由【共享锚点】决定，两侧必须逐位相同。 */
    public String receiveKey() {
        return anchorX + "," + anchorZ;
    }

    /** 同身份 = 同锚点且同接收 owner（端口"被合并"时必须仍指向同一有效下游）。 */
    public boolean sameIdentity(BoundaryPort other) {
        return other != null && anchorX == other.anchorX && anchorZ == other.anchorZ
                && receiverRegionX == other.receiverRegionX && receiverRegionZ == other.receiverRegionZ;
    }

    @Override
    public String toString() {
        return "port[" + senderRegionX + "," + senderRegionZ + "]->["
                + receiverRegionX + "," + receiverRegionZ + "] anchor(" + anchorX + "," + anchorZ
                + ") next(" + nextX + "," + nextZ + ") dir("
                + String.format(java.util.Locale.ROOT, "%.3f,%.3f", dirX, dirZ)
                + ") cell=" + cell + " dR=" + dRX + "," + dRZ;
    }
}
