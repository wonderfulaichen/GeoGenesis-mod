package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.terrain.VolcanicShape;

/**
 * <b>火山几何（类型层的独立覆盖族）</b>——对齐 {@link PlateauRecipe} 的「覆盖族」模式。
 *
 * <h3>形态（2026-11-17 用户验收定稿）</h3>
 * <p>沙箱（{@code VolcanoFormProbe}）迭代出并经用户目检确认的形态：</p>
 * <ul>
 *   <li><b>凹形主锥</b>：{@code exp(−d/0.30)}——坡顶陡、坡脚缓（stratovolcano 特征；
 *       旧 CONE 的高斯剖面是「缓顶陡底」的盾状形态，不像层状火山）；</li>
 *   <li><b>放射状冲沟</b>（barrancos）：按方位角取噪声，翼部的沟槽/山脊条纹
 *       （层状火山翼部的标志性地貌）；</li>
 *   <li><b>宽缓裙摆</b>：2.2×半径 / 12% 高——让火山从地形里「长出来」而非「扣上去」；</li>
 *   <li><b>火口</b>：深 {@value #CRATER_DEPTH}e（14 格）、半径 0.30×火山半径；</li>
 *   <li><b>域扭曲</b>：{@value #WARP_AMP}wu——打破完美圆。</li>
 * </ul>
 *
 * <h3>尺寸（用户验收）</h3>
 * <table border="1">
 *   <caption>与旧外挂层的对比</caption>
 *   <tr><th>项</th><th>旧 LandFeatures（外挂）</th><th>本类（类型层，已验收）</th></tr>
 *   <tr><td>半径</td><td>140~300wu</td><td><b>{@value #RADIUS}wu（锥体直径 480 块）</b></td></tr>
 *   <tr><td>高度</td><td>0.20~0.42e（47~99 格）</td><td><b>{@value #AMP}e（57 格）</b></td></tr>
 *   <tr><td>火口</td><td>深 0.02~0.07e（太浅看不出）</td><td><b>深 {@value #CRATER_DEPTH}e（14 格）、宽 72 块</b></td></tr>
 *   <tr><td>裙摆</td><td>无</td><td><b>2.2×半径（1056 块宽）</b></td></tr>
 * </table>
 *
 * <h3>确定性</h3>
 * <p>纯函数：位置与几何全部由 {@code hash(seed, cellX, cellZ, salt)} 派生，
 * 无随机状态、无缓存。同一 {@code (seed, wx, wz)} 永远给同一值。</p>
 */
public final class VolcanoRecipe {

    /** 撒点网格间距（wu）。 */
    static final double GRID = 1200.0;
    /** 每格出现火山的概率（千分比）：400/1000 = 40%。 */
    static final int CHANCE_PERMILLE = 400;
    /** 火山半径（wu）：锥体直径 480 块（用户验收）。 */
    static final double RADIUS = 120.0;
    /** 山体高度（e）：57 格（用户验收）。 */
    static final double AMP = 0.24;
    /** ★ 类型判定阈值（e）：edifice 高于此即 {@code TerrainClass.VOLCANO}。 */
    static final double VOLCANO_TYPE_GATE = 0.10;
    /** 火口深度（e）：14 格（用户验收）。 */
    static final double CRATER_DEPTH = 0.060;
    /** 火口作用半径（占火山半径的比例）。 */
    static final double CRATER_FRAC = 0.30;
    /** 主锥凹形剖面的衰减尺度（wu）：exp(−d/0.30)，坡顶陡、坡脚缓。 */
    static final double CONE_DECAY = 0.30;
    /**
     * ★ 堆积基底（apron）——2026-11-17 用户反馈「像从地面挤出来，真实火山是堆积起来的」。
     *
     * <p>真实层状火山：底部直径 ≈ 锥高 × 10~20，山麓是 1~3° 的火山碎屑堆积扇，
     * 30° 的主锥坐落在它上面。旧高斯裙摆只有 0.12·amp（7 格）⇒ 锥体像「薄饼上的痘痘」。
     * 新基底：半径 {@value #APRON_R}R（816 块）、高 0.24·amp（13.6 格），
     * 肩线 {@code (1−d/R)^1.5}——近锥脚稍陡、远脚趋平（碎屑扇自然收尾）。
     * 沙箱定稿见 {@code VolcanoFormProbe}（剖面：大缓丘托小锥 + 火口双峰 + 长尾）。</p>
     */
    static final double APRON_R = 3.4;
    static final double APRON_AMP = 0.24 * AMP;
    /** 基底轮廓不规则度（半径按方位角噪声 ±18%——不是圆规画的圆）。 */
    static final double APRON_ROUGH = 0.18;
    /** 域扭曲幅度（wu）：打破完美圆。 */
    static final double WARP_AMP = 30.0;
    /** 放射状冲沟幅度（e，相对 AMP）。延伸到堆积扇远端（d<2.6）才渐隐。 */
    static final double GULLY_AMP = 0.13;
    /** 山体拉伸范围（面积守恒的各向异性）。 */
    static final double STRETCH_MIN = 1.0;
    static final double STRETCH_SPAN = 0.5;
    /** 格内抖动比例。 */
    static final double JITTER = 0.38;

    private static final long SALT_POS = 0x5B1E9C3D7A2F6048L ^ 0x1111;
    private static final long SALT_SHAPE = 0x8C4A2F6E1D9B7350L ^ 0x2222;
    private static final long SALT_WARP_X = 0x1A2B3C4D5E6F7081L ^ 0x3333;
    private static final long SALT_WARP_Z = 0x2C3D4E5F60718293L ^ 0x4444;
    private static final long SALT_GULLY = 0x61726D9C4E2F8053L ^ 0x5555;
    private static final long SALT_APRON = 0x5A4B3C2D1E0F7869L ^ 0x6666;

    /** 搜索半径（格）：格内抖动 + 拉伸后最大半轴 ≈ 210wu < 1200wu ⇒ 1 格足够。 */
    private static final int SEARCH = 1;

    private long worldSeed;

    /** 播种（只记录种子——本类无噪声节点，「噪声」全部来自整数 hash 插值）。 */
    public void seed(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    /**
     * 该点的火山<b>高度增量</b>（e 单位，相对周围地形）。
     *
     * <p>调用方（{@code MorphLandField}）把它<b>加到</b>现有地形上
     * （增盈式）。火山是建在既有地形上的山体，加法在物理上正确；
     * 幅度 {@value #AMP}e 远小于类型系统量程（0.95e）且只影响局部
     * （半径 {@value #RADIUS}wu）⇒ 不需要像趋势层那样做预算重分配。</p>
     *
     * <p>⚠ 初版错误地做「插值到绝对高度」（{@code covered + (H − covered)·w}）：
     * 那会把火山当成"该点的地形值"，在本身较高的地形上火山反而被<b>压低</b>
     * （实测抬升只有 7.9 格）。</p>
     *
     * <p>⚠ 初版二错：{@code edifice()} 把 {@code coneAt} 的结果再乘一次 {@link #AMP}，
     * 而 {@code coneAt} 内部 {@code cone = AMP·exp(…)} 已带幅度 ⇒ 双重缩放，
     * 生产火山只有 0.21×0.24≈0.05e（12 格）而非验收的 57 格。
     * {@code coneAt} 返回的就是 e 单位成品——这里原样返回。</p>
     */
    public double height(double wx, double wz) {
        return edifice(wx, wz, Double.NEGATIVE_INFINITY, null);
    }

    /**
     * 带<b>中心陆上门控</b>的完整火山几何（供 {@code MorphLandField} 使用）。
     *
     * <p>⚠ 返回值已是 e 单位成品（{@code coneAt} 内部带 {@link #AMP}），
     * 不再二次缩放——初版的 {@code return best * AMP} 双重缩放把 57 格火山
     * 压成了 12 格（见 {@link #height} 的 javadoc）。</p>
     */
    double edifice(double wx, double wz, double minLand,
                   java.util.function.DoubleBinaryOperator landProbe) {
        double best = 0.0;
        int cx0 = (int) Math.floor(wx / GRID);
        int cz0 = (int) Math.floor(wz / GRID);
        for (int dz = -SEARCH; dz <= SEARCH; dz++) {
            for (int dx = -SEARCH; dx <= SEARCH; dx++) {
                double s = coneAt(wx, wz, cx0 + dx, cz0 + dz, minLand, landProbe);
                if (s > best) {
                    best = s;
                }
            }
        }
        return best;
    }

    /**
     * 单格火山（jittered 中心 + 椭圆锥 + 冲沟 + 裙摆 + 火口）。
     *
     * @return 火山几何高度（e）；0 = 该格无火山或点在火山外
     */
    private double coneAt(double wx, double wz, int cx, int cz,
                          double minLand, java.util.function.DoubleBinaryOperator landProbe) {
        long h = hash(worldSeed, cx, cz, SALT_POS);
        if ((h >>> 40) % 1000 >= CHANCE_PERMILLE) {
            return 0.0;   // 该格不生成火山（确定性：只看 hash）
        }
        // 中心：格内抖动 ±JITTER×GRID×0.5
        double sx = (cx + 0.5 + (unit(h >>> 8) - 0.5) * 2.0 * JITTER) * GRID;
        double sz = (cz + 0.5 + (unit(h >>> 24) - 0.5) * 2.0 * JITTER) * GRID;

        // ★ 陆上门控：按<b>中心</b>判定（整座火山按中心决定存亡）。
        //   逐点门控会让火山跨海岸线 ⇒ 主体在水下（实测峰顶 e=−0.095）。
        //   {@code landProbe == null} = 不做门控（{@code height()} 探针用）。
        if (landProbe != null && landProbe.applyAsDouble(sx, sz) <= minLand) {
            return 0.0;
        }

        long hs = hash(worldSeed, cx, cz, SALT_SHAPE);
        double stretch = STRETCH_MIN + unit(hs) * STRETCH_SPAN;
        double ang = unit(hs >>> 16) * Math.PI;

        // 域扭曲后的局部坐标
        double px = wx + WARP_AMP * fbm2(wx / 220.0, wz / 220.0, SALT_WARP_X);
        double pz = wz + WARP_AMP * fbm2(wx / 220.0, wz / 220.0, SALT_WARP_Z);
        double dx = px - sx;
        double dz = pz - sz;

        // 椭圆主轴系
        double u = (dx * Math.cos(ang) + dz * Math.sin(ang)) / RADIUS / stretch;
        double v = (-dx * Math.sin(ang) + dz * Math.cos(ang)) / RADIUS / stretch;
        double d = Math.sqrt(u * u + v * v);

        // 主锥：凹形剖面（坡顶陡、坡脚缓）
        double cone = d < 2.2 ? AMP * Math.exp(-d / CONE_DECAY) : 0.0;
        // ★ 堆积基底（apron）：宽缓碎屑扇托住主锥（形态定稿 2026-11-17）。
        //   半径按<b>地心方位角</b>噪声起伏 ±18%：基底轮廓不规则（与沙箱同式）。
        double angC = Math.atan2(dz, dx);
        double apronR = APRON_R * (1.0 + APRON_ROUGH
                * fbm2(Math.cos(angC) * 1.7 + 3.1, Math.sin(angC) * 1.7 - 2.4, SALT_APRON));
        double apron = d < apronR ? APRON_AMP * Math.pow(1.0 - d / apronR, 1.5) : 0.0;
        // 放射状冲沟（barrancos）：按方位角取噪声，翼部一直延续到堆积扇远端
        double ang2 = Math.atan2(v, u);
        double gullyN = fbm2(Math.cos(ang2) * 2.6 + 7.3, Math.sin(ang2) * 2.6 - 4.1, SALT_GULLY);
        double gully = GULLY_AMP * AMP
                * Math.max(0.0, Math.min(1.0, (d - 0.15) / 0.25))
                * (1.0 - Math.max(0.0, Math.min(1.0, (d - 1.2) / 1.4)))
                * gullyN;
        // 火口：中心下凹（VolcanicShape.crater 同式）
        double crater = VolcanicShape.crater(d, CRATER_FRAC, CRATER_DEPTH);

        return Math.max(0.0, cone + apron + gully - crater);
    }

    /** 两倍频值噪声（与 {@code VolcanoFormProbe} 同式——形态验收用的同一实现）。 */
    private double fbm2(double x, double z, long salt) {
        double n1 = vnoise(x, z, salt);
        double n2 = vnoise(x * 2.3 + 11.7, z * 2.3 + 5.9, salt ^ 0x7919L);
        return (n1 + 0.5 * n2) / 1.5;
    }

    private double vnoise(double x, double z, long salt) {
        long seed = worldSeed;
        double fx = Math.floor(x), fz = Math.floor(z);
        long ix = (long) fx, iz = (long) fz;
        double tx = x - fx, tz = z - fz;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double n00 = unit(hash(seed, ix, iz, salt)) * 2 - 1;
        double n10 = unit(hash(seed, ix + 1, iz, salt)) * 2 - 1;
        double n01 = unit(hash(seed, ix, iz + 1, salt)) * 2 - 1;
        double n11 = unit(hash(seed, ix + 1, iz + 1, salt)) * 2 - 1;
        return (n00 * (1 - tx) + n10 * tx) * (1 - tz) + (n01 * (1 - tx) + n11 * tx) * tz;
    }

    private static long hash(long seed, long x, long z, long salt) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 31);
        h *= 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 27);
        h *= 0x165667B19E3779F9L;
        h ^= salt;
        h ^= h >>> 29;
        h *= 0x2545F4914F6CDD1DL;
        h ^= h >>> 32;
        return h;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
