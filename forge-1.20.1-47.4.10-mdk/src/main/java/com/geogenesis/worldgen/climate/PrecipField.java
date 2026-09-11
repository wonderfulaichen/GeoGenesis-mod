package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.terrain.CacheStats;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 降水的地形调制场（★ 2026-09-11 Phase B）：<b>地形雨 / 雨影 / 焚风</b>。
 *
 * <p><b>零 MC 依赖</b>（仅 {@code java.*} + 同包零依赖类 + {@code CacheStats}）。</p>
 *
 * <h3>三个地形气候效应（同一个"上风向回扫"的产物）</h3>
 * <ul>
 *   <li><b>地形雨 (a)</b>：沿风向前进一格若抬升（{@code lift>0}）→ 迎风坡强迫抬升 → 增雨；</li>
 *   <li><b>雨影 (b)</b>：沿风向上溯若存在更高地形（{@code barrier>0}）→ 已在背风坡 → 减雨；</li>
 *   <li><b>焚风 (c)</b>：背风坡干绝热下沉，升温率高于湿绝热 → 净增温。</li>
 * </ul>
 *
 * <p><b>三者互斥且完备</b>（不会同时"既是迎风又是背风"）：
 * 山脊顶 lift&lt;0 且 barrier&lt;0（都无）；迎风坡 lift&gt;0 且 barrier&lt;0；
 * 背风坡 lift&lt;0 且 barrier&gt;0。</p>
 *
 * <h3>为什么用「节点纯函数 + 无锁有损缓存」而不是 region + margin</h3>
 * <p>设计文档 §4.3.1 初版用"region(512wu) + margin + 双线性"。实现时改为
 * <b>节点级纯函数</b>（节点值 = f(seed, 节点坐标)，与 region 无关）：
 * 无缝性由构造保证（每个节点只有一个确定值），<b>不需要 margin、不存在跨 region 耦合</b>，
 * 严格更简单也更安全。缓存退化为纯加速手段（有损直接映射，参考本项目既有
 * {@code GeoGenesisBiomeSource} 的 biome 定槽缓存），节点值仅 3 个 double、重算代价 ~14 次廉价采样。</p>
 *
 * <h3>铁律</h3>
 * <p>{@link HeightFn} <b>必须廉价、且绝不能触发侵蚀 tile</b>（设计文档 §3.1）——
 * 约定实现为 {@code heightFromE(terrainEQuick(x, z))}。</p>
 */
public final class PrecipField {

    /**
     * 地形调制量。{@code foehnWarm} 与 {@code waterMoist} 单位与 {@code Climate.temperature} /
     * {@code humidity} 同量纲（[-1,1] e 单位的增量）。
     *
     * <p>★ 2026-09-11 Phase E 新增 {@code waterMoist}（上风向海域回灌湿度）：
     * 上风是海面 → 下风向空气更湿（水汽来源）。这是"水文 → 气候"反向耦合的第一条通路。</p>
     */
    public record Mod(double orographicGain, double shadowLoss, double foehnWarm, double waterMoist) {
        public static final Mod NONE = new Mod(0.0, 0.0, 0.0, 0.0);
    }

    /** 参数（Phase B 用 defaults；接 Forge 配置推迟到接线完成时，避免产生死配置）。 */
    public record Params(double oroGain, double oroRefRise, double shadowMax, double shadowRef,
                         double foehnK, double lapseDiff, double foehnMax,
                         double moistGain, int upwindCells, double cellSize) {
        public static Params defaults() {
            // foehnK = 8.0：MC 垂直尺度被压缩，按真实直减率算焚风仅 ~1°C 不可见，
            // 故按"游戏可感知"标定（设计文档 §4.4 已明确标注为游戏化放大）。
            //
            // ★ foehnMax = 3.0（2026-09-11 修复）：
            //   原式 foehnWarm = foehnK·barrier·lapseDiff 是【线性无上限】的，
            //   而雨影 shadowLoss 却被 shadowRef(40) 归一化饱和 —— 二者不对称。
            //   实测 barrier 可达 137 块（此时雨影已饱和 0.70），焚风算出 5.27，
            //   超出 §4.4 的 1~3 °C 目标带；极端地形下更会失控增长
            //   （仅被 CellGenerator 的 clamp(temp,−1,1) 兜住，最多 +40 °C 等效）。
            //   现按"典型 ≈2 °C、上限 3 °C"截断：低 barrier 段保留原有梯度，超出即封顶。
            //
            //   ⚠️ 单位说明（本次曾算错一次，务必留意）：CellGenerator 以
            //   `temp += foehnWarm / DEG_C_PER_E_UNIT` 施加，而 1 e 单位 = 40 °C
            //   （DEG_C_PER_E_UNIT=40）→ **增益的 °C 值在数值上恰等于 foehnWarm**。
            //   故此处直接写"3.0"即代表 3 °C，**不要**再除以 40。
            //
            // ★ cellSize 32→96（2026-09-11 性能修正）：本类被放在最热的
            //   CellGenerator.sample() 里，而每个【冷】节点要 8 次 terrainEQuick
            //   （1 自身 + 1 迎风 + 6 上风）。实测 runFlowAccumProbe 的 coldMs
            //   从基线 1849ms 涨到 ~17600ms（9.4×）。节点密度降 9× 后代价回落；
            //   96wu 仍优于地形雨所需的"山脉尺度"（数百 wu），保真度损失可接受。
            //
            // ★ moistGain = 0.25（Phase E，2026-09-11）：上风向海域回灌的最大湿度增益
            //   （humidity ∈ [−1,1]，故 0.25 属"可感知但不淹没原有湿度噪声"的量级）。
            //   随上风距离线性衰减（最近的岸线影响最大，最远 6×96=576wu 处降至 0.167×）。
            return new Params(0.85, 24.0, 0.70, 40.0, 8.0, 0.0048, 3.0, 0.25, 6, 96.0);
        }
    }

    /** 地形高度取用器（单位：块）。约定 {@code heightFromE(terrainEQuick(x,z))}。 */
    @FunctionalInterface
    public interface HeightFn {
        double at(double wx, double wz);
    }

    private static final int CACHE_BITS = 16;                 // 65536 节点 ≈ 2.1M wu 覆盖
    private static final int CACHE_SIZE = 1 << CACHE_BITS;
    private static final int CACHE_MASK = CACHE_SIZE - 1;
    private static final long EMPTY = Long.MIN_VALUE;

    private long seed;
    private final HeightFn height;
    private final double latScale;
    private final Params p;
    private final WindField.Params wind;
    /**
     * 海平面高度（块）—— Phase E 用它判"上风向是否为海域"（{@code h ≤ seaY}）。
     * 由调用方注入（{@code heightCurve.seaLevelY()}），本类保持对 HeightCurve 零依赖。
     */
    private final double seaY;

    /** 无锁有损直接映射缓存（命中/未命中仅作诊断，覆盖写不回退）。 */
    private final long[] keys = new long[CACHE_SIZE];
    private final Mod[] vals = new Mod[CACHE_SIZE];
    private final CacheStats stats = new CacheStats("precipNode");
    private final AtomicLong slotCollisions = new AtomicLong();

    public PrecipField(long seed, HeightFn height, double latScale,
                       Params p, WindField.Params wind) {
        this(seed, height, latScale, p, wind, Double.NaN);
    }

    /**
     * @param seaY 海平面高度（块）；用于 Phase E 判定"上风向海域"。
     *             传 {@link Double#NaN} 表示<b>关闭海域回灌</b>（此时 {@code waterMoist} 恒 0）。
     */
    public PrecipField(long seed, HeightFn height, double latScale,
                       Params p, WindField.Params wind, double seaY) {
        this.seed = seed;
        this.height = height;
        this.latScale = latScale;
        this.p = p;
        this.wind = wind;
        this.seaY = seaY;
        java.util.Arrays.fill(keys, EMPTY);
    }

    /** 换种子（清空节点缓存与埋点）。 */
    public void setSeed(long worldSeed) {
        this.seed = worldSeed;
        java.util.Arrays.fill(keys, EMPTY);
        stats.reset();
        slotCollisions.set(0);
    }

    public CacheStats stats() { return stats; }

    /** 缓存覆盖次数（有损缓存的代价指标）。 */
    public long slotCollisions() { return slotCollisions.get(); }

    /** 任意世界坐标处的地形调制（双线性 + smoothstep 插值）。 */
    public Mod at(double wx, double wz) {
        double cs = p.cellSize();
        double gx = wx / cs, gz = wz / cs;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        double fx = gx - ix, fz = gz - iz;
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);

        Mod a = node(ix, iz),         b = node(ix + 1, iz);
        Mod c = node(ix, iz + 1),     d = node(ix + 1, iz + 1);
        Mod ab = lerp(a, b, sx);
        Mod cd = lerp(c, d, sx);
        return lerp(ab, cd, sz);
    }

    /** 节点调制量（纯函数 + 有损缓存）。 */
    private Mod node(int ix, int iz) {
        long key = pack(ix, iz);
        int slot = slot(key);
        if (keys[slot] == key) {
            stats.hit();
            return vals[slot];
        }
        stats.miss();
        Mod v = computeNode(ix, iz);
        if (keys[slot] != EMPTY) slotCollisions.incrementAndGet();
        vals[slot] = v;
        keys[slot] = key;
        return v;
    }

    /**
     * 节点计算：<b>上风向回扫</b> —— 一次扫描同时得出地形雨、雨影、焚风。
     */
    private Mod computeNode(int ix, int iz) {
        double cs = p.cellSize();
        double wx = ix * cs, wz = iz * cs;
        double hP = height.at(wx, wz);

        WindField.Wind w = WindField.sample(seed, wx, wz, latScale, wind);
        if (w.speed() <= 0.0) return Mod.NONE;      // 无风 → 无地形气候效应

        // (a) 地形雨：沿风向前进一格的高度差 > 0 = 迎风上坡 = 强迫抬升
        double hAhead = height.at(wx + w.x() * cs, wz + w.z() * cs);
        double lift = hAhead - hP;
        double orographicGain = p.oroGain() * clamp01(lift / p.oroRefRise()) * w.speed();

        // (b) 雨影 / (c) 焚风 / (d) 海域回灌：沿风向上溯一次扫描全部得出
        int L = p.upwindCells();
        double barrier = 0.0;
        double seaFrac = 0.0;
        boolean seaEnabled = !Double.isNaN(seaY);   // ★ Phase E 开关
        for (int d = 1; d <= L; d++) {
            double hUp = height.at(wx - w.x() * cs * d, wz - w.z() * cs * d);
            double wd = 1.0 - (d - 1.0) / L;        // 越近的屏障影响越大
            barrier = Math.max(barrier, wd * (hUp - hP));
            // ★ 2026-09-11 Phase E：上风向若为海面（h ≤ 海平面）→ 水汽来源。
            //   复用本循环【已经采样过】的 hUp → 零额外采样开销。
            if (seaEnabled && hUp <= seaY) seaFrac = Math.max(seaFrac, wd);
        }
        double shadowLoss = p.shadowMax() * clamp01(barrier / p.shadowRef());
        // 焚风：线性段保留"屏障越高增温越强"的梯度，但用 foehnMax 封顶
        // （与雨影在 shadowRef 处饱和相呼应——原式无上限，极端地形会失控）。
        double foehnWarm = Math.min(p.foehnMax(),
            p.foehnK() * Math.max(0.0, barrier) * p.lapseDiff());
        // 海域回灌：上风海面占比 × 增益（下风向增湿；无上风海域则为 0）
        double waterMoist = p.moistGain() * seaFrac;

        return new Mod(orographicGain, shadowLoss, foehnWarm, waterMoist);
    }

    // ===== 工具 =====

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static Mod lerp(Mod a, Mod b, double t) {
        if (t == 0.0) return a;
        if (t == 1.0) return b;
        return new Mod(
            a.orographicGain() + (b.orographicGain() - a.orographicGain()) * t,
            a.shadowLoss()     + (b.shadowLoss()     - a.shadowLoss())     * t,
            a.foehnWarm()      + (b.foehnWarm()      - a.foehnWarm())      * t,
            a.waterMoist()     + (b.waterMoist()     - a.waterMoist())     * t);
    }

    private static long pack(int ix, int iz) {
        return ((long) ix << 32) | (iz & 0xFFFFFFFFL);
    }

    private static int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) ((h >>> 40) & CACHE_MASK);
    }
}
