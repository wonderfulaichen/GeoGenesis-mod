package com.geogenesis.client.reveal;

/**
 * <b>落成动画的让位调度器</b>（★ v37，零 MC 依赖纯逻辑，可离线验证）。
 *
 * <h2>要解决什么</h2>
 * <p>落成动画里，柱子要<b>一块块让位</b>给下面的真实地形。直接"时间到就淡"会有两个问题
 * （参考项目逐条踩过，注释写得很明白）：</p>
 * <ol>
 *   <li><b>不能按区块到货先后淡</b> —— 到货登记是逐行扫的，
 *       会"从某条边推到另一边"。正确做法是按<b>离落点中心的距离</b>排波次，从中心向外扩。</li>
 *   <li><b>不能只按波次时间淡</b> —— 必须等<b>它身下那块真实区块</b>数据真的到了才淡，
 *       否则"柱子化了、底下还是空的"。</li>
 * </ol>
 *
 * <h2>三重上限（参考项目的血泪）</h2>
 * <p>细场有 961 个区块列，而视距 12 的客户端最多只加载得到 625 块 —— 外面那一圈
 * <b>永远等不到</b>。以前就是被它们拖在天下十几秒。所以：</p>
 * <table border="1">
 *   <caption>放行方式</caption>
 *   <tr><th>情况</th><th>放行时机</th></tr>
 *   <tr><td>区块数据到货</td><td>到货时刻 + {@link #NO_FILTER_MESH_MS}（给网格编译留余量）</td></tr>
 *   <tr><td>数据到了但一直没有效</td><td>到货后 {@link #DATA_MISS_MS} 强制放行</td></tr>
 *   <tr><td>数据永远不来（视距外）</td><td>波次时刻到达即放行</td></tr>
 * </table>
 * <p>且<b>只等等待圈内的</b>（视距 clamp 到 8~12）：圈外那几百块不吊着镜头。</p>
 *
 * <h2>为什么这个类要零 MC 依赖</h2>
 * <p>用户明确要求「抽离 MC 做完全纯 Java 核心，可以在外部计算后直接复用」。
 * 让位调度是纯时间/距离计算，没有任何渲染依赖 ⇒ 探针能完整验证。</p>
 *
 * <p>参考项目对应：{@code SiteRevealWorldRenderer} 的
 * {@code waveAt / fadeAt / dataAt / waitZone / collapseAt} 与 {@code REVEAL_SPREAD_MS} 等常量。</p>
 */
public final class RevealSchedule {

    // ===== 时长常量（照参考项目 SiteRevealWorldRenderer 65-115 行）=====

    /** 单块柱子淡出的时长（毫秒）。参考项目 {@code FADE_MS=1600}。 */
    public static final int FADE_MS = 1600;
    /** 收尾整体消散的时长（毫秒）。参考项目 {@code COLLAPSE_MS=300}。 */
    public static final int COLLAPSE_MS = 300;
    /** 让位从中心扩散到边缘要多久（毫秒）。参考项目 {@code REVEAL_SPREAD_MS=8000}。 */
    public static final long SPREAD_MS = 8000L;
    /** 等待圈内那一波让位的时长（毫秒）。参考项目 {@code REVEAL_SPREAD_NEAR_MS=2200}。 */
    public static final long SPREAD_NEAR_MS = 2200L;
    /** 区块到货后给网格编译留的余量（毫秒）。参考项目 {@code NO_FILTER_MESH_MS=1200}。 */
    public static final long NO_FILTER_MESH_MS = 1200L;
    /** 数据到了但这么久还没进绘制列表：按波次时刻放行。参考项目 {@code DATA_MISS_MS=2500}。 */
    public static final long DATA_MISS_MS = 2500L;
    /** 总上限：动画再好看也不能把人关在外面（毫秒）。参考项目 {@code HARD_CAP_MS=60000}。 */
    public static final long HARD_CAP_MS = 60_000L;
    /** 等待圈半径的上下限（区块）。参考项目 {@code WAIT_RADIUS_MIN/MAX=8/12}。 */
    public static final int WAIT_RADIUS_MIN = 8;
    public static final int WAIT_RADIUS_MAX = 12;

    /**
     * 一块柱子的调度状态。
     *
     * <p>{@code fadeAt < 0} = 还没定（还在等数据）；{@code >= 0} = 已定下开始时刻。</p>
     */
    public static final class Block {
        /** 该块中心到落点的距离（方块）—— 波次排序用。 */
        public double dist;
        /** 按距离算出的<b>最早</b>让位时刻（相对动画时钟，毫秒）。 */
        public long waveAt;
        /** 真实区块数据到货时刻（+1；0 = 还没到）。 */
        public long dataAt;
        /** 定下来的淡出开始时刻（-1 = 还没定）。 */
        public long fadeAt = -1L;
        /** 在不在等待圈里（只有圈内算进度、算"还没好"）。 */
        public boolean inWaitZone;
        /** 是否已经淡完。 */
        public boolean done;
    }

    private final Block[] blocks;
    private final double maxDist;
    private long collapseAt = -1L;

    /**
     * @param blocks  所有块（已填好 {@code dist} 与 {@code inWaitZone}）
     * @param maxDist 最大距离（用于把距离归一化到 [0,1] 决定波次）
     */
    public RevealSchedule(Block[] blocks, double maxDist) {
        this.blocks = blocks;
        this.maxDist = Math.max(1e-6, maxDist);
        for (Block b : blocks) {
            // 波次时刻 = 距离归一化 × 扩散时长
            //   ⇒ 中心块立即轮到，边缘块 SPREAD_NEAR_MS 之后才轮到
            double t = Math.min(1.0, b.dist / this.maxDist);
            b.waveAt = (long) (t * SPREAD_NEAR_MS);
        }
    }

    /**
     * 推进一帧：给"该让位的"块定下 {@code fadeAt}。
     *
     * @param nowMs     动画时钟（毫秒，相对开场）
     * @param hasData   某块的真实区块数据是否已到货（由调用方查 clientChunkCache）
     * @return 本帧新定下让位的块数
     */
    public int advance(long nowMs, DataProbe hasData) {
        int newly = 0;
        for (int i = 0; i < blocks.length; i++) {
            Block b = blocks[i];
            if (b.fadeAt >= 0L || b.done) continue;

            // 记录到货时刻（只记第一次）
            if (b.dataAt == 0L && hasData.has(i)) {
                b.dataAt = nowMs + 1L;
            }

            // 还没到自己的波次：等
            if (nowMs < b.waveAt) continue;

            long start = -1L;
            if (b.dataAt > 0L) {
                // 数据到了：再等网格编译余量
                long earliest = b.dataAt - 1L + NO_FILTER_MESH_MS;
                // 但数据到了太久还没生效 ⇒ 强制放行（视距边缘/相机背面）
                long giveUp = b.dataAt - 1L + DATA_MISS_MS;
                if (nowMs >= giveUp) start = Math.max(b.waveAt, giveUp);
                else if (nowMs >= earliest) start = Math.max(b.waveAt, earliest);
            } else {
                // 数据永远不来（视距外）：波次到了就放行，别吊着
                long giveUp = b.waveAt + DATA_MISS_MS;
                if (nowMs >= giveUp) start = giveUp;
            }

            if (start >= 0L) {
                b.fadeAt = start;
                newly++;
            }
        }
        return newly;
    }

    /** 某块当前的淡出进度（0 = 还没开始，1 = 淡完）。 */
    public float fadeProgress(Block b, long nowMs) {
        if (b.fadeAt < 0L) return 0f;
        long el = nowMs - b.fadeAt;
        if (el <= 0L) return 0f;
        if (el >= FADE_MS) return 1f;
        float t = el / (float) FADE_MS;
        // 五次曲线（参考项目：「开始化和淡完两头都缓」）
        float t2 = t * t, t3 = t2 * t;
        return t3 * (t * (t * 6f - 15f) + 10f);
    }

    /** 等待圈内还有几块没定下让位（非 0 就不能落机）。 */
    public int waitingInZone() {
        int n = 0;
        for (Block b : blocks) {
            if (b.inWaitZone && b.fadeAt < 0L) n++;
        }
        return n;
    }

    /** 等待圈内已经淡完几块（进度条分子）。 */
    public int readyInZone(long nowMs) {
        int n = 0;
        for (Block b : blocks) {
            if (!b.inWaitZone) continue;
            if (b.fadeAt < 0L) continue;
            if (nowMs - b.fadeAt >= FADE_MS) n++;
        }
        return n;
    }

    /** 等待圈内的总块数（进度条分母）。 */
    public int totalInZone() {
        int n = 0;
        for (Block b : blocks) {
            if (b.inWaitZone) n++;
        }
        return n;
    }

    /** 圈内全部放行且最后一块也淡完 ⇒ 可以落机。 */
    public boolean canDescend(long nowMs) {
        if (waitingInZone() > 0) return false;
        // 圈内最后开始淡的那块也要淡完
        long latest = -1L;
        for (Block b : blocks) {
            if (b.inWaitZone && b.fadeAt > latest) latest = b.fadeAt;
        }
        return latest < 0L || nowMs - latest >= FADE_MS;
    }

    /**
     * 触发<b>收尾消散</b>：剩下没轮到的柱子在同一时刻开始一起散干净。
     *
     * <p>参考项目：「否则镜头一挪就会从还立着的柱子中间穿过去」。</p>
     */
    public void beginCollapse(long nowMs) {
        if (collapseAt >= 0L) return;
        collapseAt = nowMs;
        for (Block b : blocks) {
            if (b.fadeAt < 0L) b.fadeAt = nowMs;   // 一起开始
        }
    }

    /** 收尾消散是否已完成（镜头可以开始落机）。 */
    public boolean collapseDone(long nowMs) {
        return collapseAt >= 0L && nowMs - collapseAt >= COLLAPSE_MS;
    }

    public long collapseAt() { return collapseAt; }

    /**
     * 按<b>柱索引</b>取对应的调度块（渲染器逐柱查用）。
     *
     * <p>步骤 A 的简化：柱索引与块索引<b>一一对应</b>（{@code blockOf[col] = col}）。
     * 若日后要做"一块 = 多个区块"，在这里换映射即可，渲染器不用改。</p>
     */
    private int[] blockOf;

    /** 建立"柱索引 → 块索引"映射（默认恒等）。 */
    public void setBlockOf(int[] mapping) {
        this.blockOf = mapping;
    }

    /** 取该柱对应的块（越界返回 null）。 */
    public Block blockAt(int column) {
        if (blocks.length == 0) return null;
        int idx = column;
        if (blockOf != null) {
            if (column < 0 || column >= blockOf.length) return null;
            idx = blockOf[column];
        }
        if (idx < 0 || idx >= blocks.length) return null;
        return blocks[idx];
    }

    // =================================================================

    /** 数据探针：问"第 i 块的真实区块数据到货了吗"。让本类保持零 MC 依赖。 */
    public interface DataProbe {
        boolean has(int blockIndex);
    }
}
