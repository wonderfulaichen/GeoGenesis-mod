package com.geogenesis.client.reveal;

/**
 * <b>落机航线</b>（★ v37，步骤 A 简化版，零 MC 依赖）。
 *
 * <h2>参考项目的四段航线 vs 我们的两段</h2>
 * <p>参考项目（{@code SiteRevealScreen} 第 37-40 行）走四段：
 * 「空中机位 → 大门正上方（屋顶以上）→ 门廊外·门轴线上·眼高 → 顺着门洞等速进屋」。
 * 那是为了从门洞穿进去、不刮檐口。</p>
 *
 * <p>我们<b>没有建筑</b>（用户裁定不建方块/物品），落点就是出生点 ⇒
 * 简化为两段（用户已确认：「简化航线就行」）：</p>
 * <pre>
 *   空中机位 ──(纵向下降)──▶ 落点上空 APPROACH_ABOVE 格 ──(竖直)──▶ 玩家眼睛
 * </pre>
 *
 * <h2>为什么用 Hermite 而不是直线插值</h2>
 * <p>直线插值在拐点会有速度突变（"卡一下"）。Hermite 带切线，
 * 参考项目第 77-81 行专门为此把中转点往外挪了一点：
 * 「三点同 z 的话，Hermite 会在竖直段里往回悠一下，看着像卡了一下」。</p>
 *
 * <p>我们用最简单的一维缓动（五段 smoothstep）代替，两段之间用缓动连接，
 * 不会有速度突变，也不需要调切线 —— 因为我们的航线是"纯下降"，
 * 没有参考项目那种"侧向穿门"的复杂性。</p>
 */
public final class DescentPath {

    /** 落机时长范围（毫秒）。参考项目 {@code DESCEND_MIN/MAX_MS}。 */
    public static final int DESCEND_MIN_MS = 3400;
    public static final int DESCEND_MAX_MS = 5500;
    /** 中转点比落点高多少格（参考项目独立安装时的 {@code SPAWN_APPROACH_ABOVE=14}）。 */
    public static final double APPROACH_ABOVE = 14.0;
    /**
     * 视角对齐区间：在落机进度的这一段里把镜头从"空中俯视"转成"玩家正向平视"。
     *
     * <p>参考项目 {@code LOOK_FROM=0.30/SPAN=0.45}（第 85-86 行）：
     * 「要早于穿门，进门前就得看正前方」。我们没有门，但同样希望
     * <b>落地时已经是平视</b>，否则玩家一进来看到的是天空。</p>
     */
    public static final float LOOK_FROM = 0.30f;
    public static final float LOOK_SPAN = 0.45f;
    /** 单帧最多推进的毫秒（卡帧不瞬移）。参考项目 {@code MAX_DESCEND_FRAME_MS=80}。 */
    public static final long MAX_FRAME_MS = 80L;

    /** 起点：空中机位。 */
    private final double fromX, fromY, fromZ;
    /** 中转点：落点上空。 */
    private final double midX, midY, midZ;
    /** 终点：玩家眼睛。 */
    private final double toX, toY, toZ;
    /** 起始/结束的视角（度）。 */
    private final float fromYaw, fromPitch;
    private final float toYaw, toPitch;
    /** 本段总时长（按行程算）。 */
    private final float totalMs;

    private float clockMs;

    /**
     * @param fromX/fromY/fromZ 空中机位
     * @param siteX/siteEyeY/siteZ 落点上空（{@code siteEyeY} 已含 APPROACH_ABOVE）
     * @param toX/toY/toZ 玩家眼睛
     */
    public DescentPath(double fromX, double fromY, double fromZ,
                       double siteX, double siteEyeY, double siteZ,
                       double toX, double toY, double toZ,
                       float fromYaw, float fromPitch, float toYaw, float toPitch) {
        this.fromX = fromX; this.fromY = fromY; this.fromZ = fromZ;
        this.midX = siteX; this.midY = siteEyeY; this.midZ = siteZ;
        this.toX = toX; this.toY = toY; this.toZ = toZ;
        this.fromYaw = fromYaw; this.fromPitch = fromPitch;
        this.toYaw = toYaw; this.toPitch = toPitch;

        // 时长按行程定速（参考项目第 63-69 行同思路），再夹进区间
        double leg1 = distance(fromX, fromY, fromZ, midX, midY, midZ);
        double leg2 = distance(midX, midY, midZ, toX, toY, toZ);
        double speed = 32.0;     // 格/秒（空中掠过去可以快）
        double ms = (leg1 + leg2) / speed * 1000.0;
        this.totalMs = (float) Math.max(DESCEND_MIN_MS, Math.min(DESCEND_MAX_MS, ms));
    }

    /** 推进（每帧一次；单帧封顶 {@link #MAX_FRAME_MS}）。 */
    public void tick(long nowMs) {
        if (lastAt == 0L) { lastAt = nowMs; return; }
        long dt = Math.min(MAX_FRAME_MS, Math.max(0L, nowMs - lastAt));
        lastAt = nowMs;
        clockMs += dt;
    }

    private long lastAt;

    /** 落机进度（0 → 1）。 */
    public float progress() {
        return Math.max(0f, Math.min(1f, clockMs / Math.max(1f, totalMs)));
    }

    /** 是否已经落完。 */
    public boolean done() { return clockMs >= totalMs; }

    /** 本段总时长（毫秒）。 */
    public float totalMs() { return totalMs; }

    /**
     * 当前位置（写入 {@code out[0..2]}）。
     *
     * <p>两段各占一半时间，段内用五次缓动 ⇒ 起停都平滑、不留拐点。</p>
     */
    public void eye(float[] out) {
        float p = progress();
        if (p <= 0.5f) {
            float t = quintic(p / 0.5f);
            out[0] = (float) lerp(fromX, midX, t);
            out[1] = (float) lerp(fromY, midY, t);
            out[2] = (float) lerp(fromZ, midZ, t);
        } else {
            float t = quintic((p - 0.5f) / 0.5f);
            out[0] = (float) lerp(midX, toX, t);
            out[1] = (float) lerp(midY, toY, t);
            out[2] = (float) lerp(midZ, toZ, t);
        }
    }

    /**
     * 视角混合（0 = 空中俯视，1 = 玩家平视）。
     *
     * <p>参考项目 {@code lookBlend} 第 610 行：在 {@code [LOOK_FROM, LOOK_FROM+LOOK_SPAN]}
     * 区间内用 smoothstep 完成对齐 ⇒ 落地那一刻已经看正前方。</p>
     */
    public float lookBlend() {
        float p = progress();
        float t = (p - LOOK_FROM) / LOOK_SPAN;
        return quintic(Math.max(0f, Math.min(1f, t)));
    }

    public float yaw() { return (float) lerp(fromYaw, toYaw, lookBlend()); }
    public float pitch() { return (float) lerp(fromPitch, toPitch, lookBlend()); }

    // ------------------------------------------------------------------

    private static double distance(double ax, double ay, double az,
                                   double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double lerp(double a, double b, float t) { return a + (b - a) * t; }

    /** 五次缓动（起停两头都缓）—— 参考项目淡出用的同一条曲线族。 */
    private static float quintic(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }
}
