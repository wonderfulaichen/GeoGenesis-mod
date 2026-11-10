package com.geogenesis.client.reveal;

import com.geogenesis.client.preview.TerrainFieldData;

/**
 * <b>落成动画的数据交接</b>（★ v37）。
 *
 * <h2>为什么要 static 持有</h2>
 * <p>配置屏（{@code GeoGenesisConfigScreen}）关闭时手里有一份
 * {@code TerrainFieldData}（沙盘刚画过的那份）。落成动画要紧接着用它，
 * 但两者之间隔着"屏幕销毁 → 世界创建"的流程，没有共同的父对象。</p>
 *
 * <p>⇒ 用一次性交接（与 {@code SpawnSelection} 同模式）：
 * 配置屏关闭前 {@code offer(data)}，动画 {@code current()} 取用。
 * <b>不在动画里重新采样</b> —— 那正是用户反对的"重复造轮子"。</p>
 *
 * <h2>过期处理</h2>
 * <p>超过 2 分钟就丢弃：防止玩家退出配置屏、逛了很久才建世界时
 * 用到一份陈旧的地形数据。</p>
 */
public final class RevealDataHolder {

    /** 超过这么久就当作过期（毫秒）。 */
    private static final long MAX_AGE_MS = 2L * 60L * 1000L;

    private static volatile TerrainFieldData data;
    private static volatile long offeredAtMs;

    private RevealDataHolder() { }

    /** 配置屏关闭前把当前地形数据交给落成动画。 */
    public static void offer(TerrainFieldData d) {
        data = d;
        offeredAtMs = System.currentTimeMillis();
    }

    /** 取用（不清空 —— 动画期间每帧都要用）。过期或没有 → null。 */
    public static TerrainFieldData current() {
        TerrainFieldData d = data;
        if (d == null) return null;
        if (System.currentTimeMillis() - offeredAtMs > MAX_AGE_MS) {
            data = null;
            return null;
        }
        return d;
    }

    /** 动画结束后清掉（避免长期占内存）。 */
    public static void clear() {
        data = null;
        offeredAtMs = 0L;
    }
}
