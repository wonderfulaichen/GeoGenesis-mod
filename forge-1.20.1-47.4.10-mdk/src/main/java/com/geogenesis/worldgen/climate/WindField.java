package com.geogenesis.worldgen.climate;

/**
 * 气压驱动的风场（★ 2026-09-11 Phase A：气候-水文闭环的起点）。
 *
 * <p><b>零 MC 依赖</b>（仅 {@code java.*}）：不 import Mojang/Forge，也<b>不复用</b>
 * {@code noise/Simplex} —— 后者引 {@code com.mojang.serialization.Codec}，会破坏本约定
 * （故类内自带极简 {@link #valueNoise}）。</p>
 *
 * <p><b>为什么不是"纬度风带硬编码"</b>：信风/西风是
 * <b>温度差 → 气压差 → 气压梯度力 + 科里奥利</b> 的<b>涌现结果</b>，不是固定常量。
 * 本类先建气压场 {@code P}，再由 {@code ∇P} 求地转风 ——
 * 风带从同一条气压廓线的两侧自然出现（同时回答了"为什么会有风带"）。</p>
 *
 * <p><b>坐标约定</b>（MC）：{@code +x}=东、{@code -z}=北；z 为纬度轴（|z| 越大越近极）。
 * 记北向 {@code y = -z}，地转风（气象约定）为
 * {@code u = (1/(ρf))·∂p/∂y}、{@code v = (1/(ρf))·∂p/∂x}；
 * 代入 {@code ∂p/∂y = -dPdz} 得本类所用：
 * {@code vgx = dPdz/f}、{@code vgz = -dPdx/f}。</p>
 *
 * <p><b>注意</b>：设计文档 §4.2.2 初版把该式写成 {@code vgx = -dPdz/f, vgz = dPdx/f}（符号反向），
 * 已按上述推导更正 —— 用错误符号会得到"西风带吹向东→西"，与实测的风带方向相反。</p>
 */
public final class WindField {

    private WindField() {}

    /** 风：{@code (x,z)} 为<b>单位方向向量</b>，{@code speed} 为 0..1 归一化风速。 */
    public record Wind(double x, double z, double speed) {
        /** 向东分量（方向 × 风速）。 */
        public double vx() { return x * speed; }
        /** 向南分量（MC {@code +z}）。 */
        public double vz() { return z * speed; }
    }

    /**
     * 风场参数。
     *
     * <p>Phase A 用 {@link #defaults()}；接 Forge 配置放到"接线阶段"（Phase B/C）——
     * 避免在配置里出现<b>无人消费的死参数</b>（本项目刚清理过一批）。</p>
     */
    public record Params(double coriolisF, double frictionAngleDeg, double speedGain,
                         double itczHalfWidth, double anomalyAmp, double anomalyFreq) {
        public static Params defaults() {
            return new Params(1.0, 25.0, 1.0, 0.12, 0.15, 1.0 / 900.0);
        }
    }

    /** 梯度中心差分步长（wu）。远小于气压系统尺度（~900wu），且不过小以免放大噪声。 */
    private static final double GRAD_STEP = 8.0;

    /** 地转风参数 f 的绝对值下限（≈ lat01 0.05），防赤道除零发散。 */
    private static final double F_MIN = 0.08;

    /** ITCZ（梯度风）→ 地转风的过渡半宽（lat01）。 */
    private static final double ITCZ_BLEND = 0.06;

    /** 标准 smoothstep：{@code x<=a → 0}；{@code x>=b → 1}；之间 {@code 3t²−2t³}。 */
    private static double smoothstep(double a, double b, double x) {
        if (b <= a) return x < a ? 0.0 : 1.0;
        double t = (x - a) / (b - a);
        t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        return t * t * (3.0 - 2.0 * t);
    }

    // ===================== 气压场 =====================

    /**
     * 纬向平均海平面气压廓线：<b>低-高-低-高</b>
     * （赤道低压 → 副热带高压 ≈1/3 → 副极地低压 ≈2/3 → 极地高压）。
     *
     * <p>{@code -cos(3π·lat01)} 恰在 {@code lat01 = 0, 1/3, 2/3, 1} 取极值（−1, +1, −1, +1），
     * 而极值处 {@code |∇P|→0} → 天然形成<b>无风带</b>：ITCZ 与副热带高压（"马纬度"）。
     * 这正是"风带为什么长这样"的物理来源，不是人为摆的开关。</p>
     */
    public static double latPressure(double lat01) {
        return -Math.cos(3.0 * Math.PI * lat01);
    }

    /**
     * 局地气压异常（"天气系统"）：低频 value noise，代表温度异常 / 环流扰动。
     *
     * <p>Phase B 会把"温度异常 {@code -kT·(T-Tref)} + 大陆性 {@code kC·cont}"并入此处；
     * Phase A 先用纯噪声，保证本类自包含可测。</p>
     */
    public static double pressureAnomaly(long seed, double wx, double wz, Params p) {
        double f = p.anomalyFreq();
        double n = valueNoise(seed ^ 0x9E3779B97F4A7C15L, wx * f, wz * f)
                 + 0.5 * valueNoise(seed ^ 0xBF58476D1CE4E5B9L, wx * f * 2.3, wz * f * 2.3);
        return p.anomalyAmp() * n / 1.5;
    }

    /** 气压场 {@code P(x,z)} = 纬向廓线 + 局地异常。 */
    public static double pressure(long seed, double wx, double wz, double latScale, Params p) {
        return latPressure(Latitude.latitude01(wz, latScale)) + pressureAnomaly(seed, wx, wz, p);
    }

    // ===================== 风 =====================

    /** 采样风（<b>纯函数、确定性</b>）。 */
    public static Wind sample(long seed, double wx, double wz, double latScale, Params p) {
        // 1) 气压梯度（中心差分）
        double s = GRAD_STEP;
        double dPdx = (pressure(seed, wx + s, wz, latScale, p)
                     - pressure(seed, wx - s, wz, latScale, p)) / (2.0 * s);
        double dPdz = (pressure(seed, wx, wz + s, latScale, p)
                     - pressure(seed, wx, wz - s, latScale, p)) / (2.0 * s);

        double lat01 = Latitude.latitude01(wz, latScale);
        double hemi = -Math.signum(wz);          // MC：-z 为北 → 北半球 = +1
        double f = p.coriolisF() * Math.sin(lat01 * Math.PI / 2.0) * hemi;

        // 2) 求方向：先算两种风，再按纬度【平滑混合】——
        //    ★ 2026-09-11 修正：初版在 lat01=itczHalfWidth 处【硬切换】分支，
        //    实测剖面出现风向突变（lat01≈0.083 处 vx 由正翻负、风速 0.69），
        //    会在 Phase B 的地形雨里刻出一条【假的降水线】。改为 smoothstep 过渡。
        //    A = 地转风：沿等压线吹（f 取绝对值下限，防赤道发散）
        //    B = 梯度风：由高压直指低压（ITCZ 无科里奥利，辐合上升主导）
        double fSafe = f >= 0 ? Math.max(f, F_MIN) : Math.min(f, -F_MIN);
        double ax = dPdz / fSafe, az = -dPdx / fSafe;
        double bx = -dPdx,       bz = -dPdz;
        double am = Math.hypot(ax, az), bm = Math.hypot(bx, bz);
        double t = smoothstep(p.itczHalfWidth() - ITCZ_BLEND,
                              p.itczHalfWidth() + ITCZ_BLEND, lat01);   // 0=ITCZ 梯度风, 1=地转风
        double gx, gz;
        if (am < 1e-15 && bm < 1e-15) {
            gx = 0.0; gz = 0.0;
        } else if (am < 1e-15) {
            gx = bx / bm; gz = bz / bm;
        } else if (bm < 1e-15) {
            gx = ax / am; gz = az / am;
        } else {
            gx = (ax / am) * t + (bx / bm) * (1.0 - t);   // 单位向量插值
            gz = (az / am) * t + (bz / bm) * (1.0 - t);
        }

        // 3) 近地面摩擦：斜穿等压线指向低压。MC(x,z) 平面内北半球为顺时针 → 角度取负。
        double th = -Math.toRadians(p.frictionAngleDeg()) * hemi;
        double ct = Math.cos(th), st = Math.sin(th);
        double rx = gx * ct - gz * st;
        double rz = gx * st + gz * ct;

        // 4) 速度：由 |∇P| 归一化（以纬向廓线的最大梯度为参考 → 与 latScale 无关）
        double gradRef = 3.0 * Math.PI / latScale;          // |dP/dlat01| 最大 = 3π
        double speed = Math.min(1.0, Math.hypot(dPdx, dPdz) / gradRef) * p.speedGain();

        double mag = Math.hypot(rx, rz);
        if (mag < 1e-12 || speed <= 0.0) return new Wind(0.0, 0.0, 0.0);
        return new Wind(rx / mag, rz / mag, speed);
    }

    // ===================== 极简确定性 value noise =====================

    /**
     * 2D value noise，返回 {@code [-1,1]}。
     *
     * <p>内联实现（不依赖 {@code noise/Simplex}）以保住本类<b>零 MC 依赖</b>；
     * 双线性 + smoothstep 插值，确定性由 {@code (seed, x, z)} 决定。</p>
     */
    static double valueNoise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);
        double n00 = hash01(seed, x0, z0),     n10 = hash01(seed, x0 + 1, z0);
        double n01 = hash01(seed, x0, z0 + 1), n11 = hash01(seed, x0 + 1, z0 + 1);
        double a = n00 + (n10 - n00) * sx;
        double b = n01 + (n11 - n01) * sx;
        return (a + (b - a) * sz) * 2.0 - 1.0;
    }

    /** 整数格点哈希 → [0,1)（SplitMix64 变体）。 */
    private static double hash01(long seed, int xi, int zi) {
        long h = seed;
        h += xi * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h += zi * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
