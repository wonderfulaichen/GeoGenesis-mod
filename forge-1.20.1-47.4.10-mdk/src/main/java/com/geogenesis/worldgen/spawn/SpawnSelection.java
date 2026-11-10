package com.geogenesis.worldgen.spawn;

/**
 * <b>出生点选点：建世界界面（客户端）→ 新世界初始化（集成服务端）的一次性交接</b>
 * （★ v35）。
 *
 * <h2>用户需求</h2>
 * <blockquote>
 * 「让它真的生效 —— 这个标记应该直接决定世界生成的出生点，而不只是画个十字。」
 * </blockquote>
 *
 * <h2>为什么需要 static 交接</h2>
 * <p>单人世界里客户端与服务端在<b>同一个 JVM</b> 里。
 * 玩家在配置屏的沙盘上右键点了出生点，那一刻世界还没创建；
 * 等服务端走 {@code CreateSpawnPosition} 时（可能隔了几秒、也可能玩家又改了种子），
 * 需要拿到那个坐标。</p>
 *
 * <p>参考项目 {@code dev.yard.site.worldgen.SiteSelection} 就是这套做法：
 * {@code offer()} 写入、{@code take(seed)} 取用即消费，
 * <b>种子不符或超过 10 分钟就丢弃</b> —— 防止玩家改了种子/中途取消后误用旧选点。</p>
 *
 * <h2>与参考项目的差异</h2>
 * <p>参考项目额外有 {@code exact}（是否"落座钉在这个区块、服务端不再就近找平地"）。
 * 我们没有建筑系统，出生点就是落点本身 ⇒ 不需要这个标志。</p>
 */
public final class SpawnSelection {

    /** 超过这个时间就当作过期（毫秒）。防止改了种子/取消后误用旧选点。 */
    private static final long MAX_AGE_MS = 10L * 60L * 1000L;

    /**
     * 玩家选定的出生点。
     *
     * @param x         方块 X
     * @param z         方块 Z
     * @param seed      选点时用的种子（服务端必须匹配才采纳）
     * @param createdAt 选点时刻（毫秒）
     */
    public record Pending(int x, int z, long seed, long createdAtMs) { }

    private static volatile Pending pending;

    private SpawnSelection() { }

    /** 客户端选点后写入（同一坐标重复写入等于刷新时间戳）。 */
    public static void offer(int x, int z, long seed) {
        pending = new Pending(x, z, seed, System.currentTimeMillis());
    }

    /** 玩家取消选点（右键同一格）时清除。 */
    public static void clear() {
        pending = null;
    }

    /** 当前是否有待用选点（UI 用来决定是否显示标记）。 */
    public static boolean has() {
        return pending != null;
    }

    /**
     * <b>只看不消费</b>（落成动画用）。
     *
     * <p>{@link #take(long)} 是"取用即消费"，服务端建出生点时会调它。
     * 但落成动画在客户端播，需要反复读坐标而<b>不能把它消费掉</b>
     * （消费了服务端就拿不到了）。</p>
     */
    public static Pending peek() {
        return pending;
    }

    /**
     * 服务端取用：<b>取用即消费</b>。
     *
     * @return 采纳的选点；种子不符 / 过期 / 从没选过 → {@code null}
     */
    public static Pending take(long seed) {
        Pending p = pending;
        pending = null;
        if (p == null) return null;
        if (p.seed() != seed) return null;
        if (System.currentTimeMillis() - p.createdAtMs() > MAX_AGE_MS) return null;
        return p;
    }
}
