package com.geogenesis.worldgen.terrain.candidate;

/**
 * 盆地（BASIN）地貌族候选 —— <b>无参考可复刻</b>，按地貌学自行设计。
 *
 * <p>两份参考都没有 basin：FreeTerraForged 的 22 个 {@code TerrainType} 与旧版
 * TerraForged 的 14 份 {@code terrain/noise/*.json} 里都没有对应族（盆地在本项目里是
 * 自有的第五类陆域类型）。所以这里不是复刻，而是先按"盆地是什么"定形态，再用与复刻族
 * 同一套 {@link RefNoise} 算子实现。</p>
 *
 * <h3>盆地的地貌学定义（决定配方形状）</h3>
 * <ul>
 *   <li><b>低洼而平坦的底</b>：盆地底部是沉积面，坡度远小于平原；真实盆地底部起伏通常只有
 *       周围山地的 1/10 量级。⇒ 底必须"整片低 + 极低起伏"，而不是"低值噪声"。</li>
 *   <li><b>盆缘高、盆底低（凹形剖面）</b>：盆地是<b>被围合</b>的低地，纵向剖面是凹的（碗形）。
 *       本项目的围合由类型权重的交叉淡入天然提供（离开盆地区域时邻类权重升高 ⇒ 高度抬升），
 *       因此族内配方<b>只需要负责盆底本身</b>，不要自己再造一圈环状高墙（那会与邻类叠加成双墙）。</li>
 *   <li><b>内流盆地有盐滩（playa）</b>：最低、最平、面积不大的中心洼地，是汇水终点。
 *       稀疏 + 平滑的洼地，<b>不是</b>阈值切出来的多边形。</li>
 *   <li><b>盆缘有冲积扇（alluvial fan）</b>：山地沟口出山后坡度骤降，粗碎屑堆成扇形高地，
 *       指向盆内。表现为盆底上的<b>稀疏缓丘</b>，而不是一圈连续台地。</li>
 *   <li><b>有些盆地内部还有残余低山</b>（basin-and-range，如美国大盆地）：平坦底之上
 *       孤立、低矮、稀疏的山脊。</li>
 * </ul>
 *
 * <h3>两条实测出来的定量约束（写配方前必须知道）</h3>
 * <ol>
 *   <li><b>e 单位在陆/海两侧不等价</b>：{@code HeightCurve} 在 e&gt;0 侧约 236 格/e、
 *       e&lt;0 侧约 127 格/e。盆地整体落在海平面以下，因此<b>同样的 Δe 只能换到一半的格数</b>
 *       —— 按"格"定目标时必须先把目标除以 127。</li>
 *   <li><b>Perlin 的实际值域远窄于声明的 0..1</b>，且特征波长越接近或超过观察窗口
 *       （本工具窗 1025² 格），跨幅越小。首版把 1500 格波长直接乘振幅，结果整幅只有
 *       2.7 格起伏。所以这里统一用 {@link #norm} 把常用值域拉满，并让特征波长不超过
 *       窗口的 1/2。</li>
 * </ol>
 *
 * <h3>与生产 BASIN 的关系</h3>
 * 现行生产 BASIN 是 {@code TypeNoiseProvider} 的单频 {@code (1-s)^2.2}，映射到
 * e∈[-0.08, 0.08]（实测 Y 52.8–74.3、起伏 21.4 格）。它只有"一个低频场取幂"，
 * 既无平坦底、也无盐滩/冲积扇/残丘之分，读起来就是"低一点的丘陵"。
 * 三个变体都保持同一 e 包络（约 [-0.08, +0.05]），<b>不改海平面与绝对高度基准</b>，
 * 只改内部形态分布；起伏均低于平原（22 格），符合"盆地是最低缓的陆域类型"。
 *
 * <p>流式约束：全部节点是 (x,z) 纯函数，无整图统计、无跨块状态。</p>
 */
public final class BasinCandidate {

    public enum Variant {
        /** B1 内流盆地：最低、最平，带稀疏盐滩洼地与盆缘冲积扇（干旱内流盆地）。 */
        B1("b1", "内流盆地（盐滩 + 冲积扇）"),
        /** B2 山间盆地（basin-and-range）：平坦底 + 稀疏残余低山脊。 */
        B2("b2", "山间盆地（盆中残丘）"),
        /** B3 构造盆地：宽缓碗形 + 极弱纹理，几乎没有中尺度起伏（沉积盆地/单斜盆地）。 */
        B3("b3", "构造盆地（宽缓碗形）");

        private final String id;
        private final String label;

        Variant(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() { return id; }

        public String label() { return label; }
    }

    private final Variant variant;
    private RefNoise.Node height;

    public BasinCandidate(Variant variant) {
        this.variant = variant;
    }

    public String id() { return variant.id(); }

    public String label() { return variant.label(); }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;
        this.height = switch (variant) {
            case B1 -> b1(next);
            case B2 -> b2(next);
            case B3 -> b3(next);
        };
    }

    /**
     * B1 内流盆地。
     *
     * <pre>
     * tilt   = norm(520, 2)                 盆底整体缓倾斜（内流盆地的底并非水平）
     * floor  = -0.014 - 0.030·tilt          e ∈ [-0.044, -0.014]
     * fan    = norm(430, 2)^3 × 0.030       稀疏盆缘冲积扇（只在少数位置凸起）
     * micro  = (norm(110, 3) - 0.5) × 0.008  风成/冲积微地形
     * 盐滩：h = lerp(h, -0.052, smoothstep(norm(760,1), 0.56, 0.74))
     * </pre>
     *
     * <p>盐滩必须是<b>绝对平面</b>：干盐湖是水平蒸发面，若还是噪声就不会有"平"的读感。
     * 用 {@code Mix} 混到常数 -0.052 而不是阈值切块，边缘是 18% 值域的 smoothstep，
     * 既得到可辨认的滩缘（坡折）又不产生台阶。</p>
     */
    private static RefNoise.Node b1(java.util.function.IntSupplier next) {
        RefNoise.Node tilt = norm(next.getAsInt(), 520f, 2);
        RefNoise.Node floor = new RefNoise.Add(new RefNoise.Mul(tilt, new RefNoise.Constant(-0.030f)),
                new RefNoise.Constant(-0.014f));

        RefNoise.Node fan = new RefNoise.Power(norm(next.getAsInt(), 430f, 2), 3f);
        fan = new RefNoise.Mul(fan, new RefNoise.Constant(0.030f));

        RefNoise.Node micro = centered(next.getAsInt(), 110f, 3, 0.008f);

        RefNoise.Node h = new RefNoise.Add(floor, fan);
        h = new RefNoise.Add(h, micro);

        RefNoise.Node playa = new RefNoise.Smoothstep(norm(next.getAsInt(), 760f, 1), 0.56f, 0.74f);
        return new RefNoise.Mix(h, new RefNoise.Constant(-0.052f), playa);
    }

    /**
     * B2 山间盆地。
     *
     * <pre>
     * floor  = -0.016 - 0.026·norm(560, 2)        平坦底
     * ridges = aniso(norm(400, 2)^1.8, x×0.42) × 0.062
     *        + aniso(norm(170, 2)^2,   x×0.50) × 0.018   残余低山：沿 X 拉伸 2.4×，避免成"圆包"
     * wash   = (norm(150, 3) - 0.5) × 0.009        沟口冲积的细碎起伏
     * </pre>
     */
    private static RefNoise.Node b2(java.util.function.IntSupplier next) {
        RefNoise.Node tilt = norm(next.getAsInt(), 560f, 2);
        RefNoise.Node floor = new RefNoise.Add(new RefNoise.Mul(tilt, new RefNoise.Constant(-0.026f)),
                new RefNoise.Constant(-0.016f));

        RefNoise.Node ridges = new RefNoise.Power(norm(next.getAsInt(), 400f, 2), 1.8f);
        ridges = new Aniso(ridges, 0.42f, 1f);
        ridges = new RefNoise.Mul(ridges, new RefNoise.Constant(0.062f));
        RefNoise.Node spurs = new RefNoise.Power(norm(next.getAsInt(), 170f, 2), 2f);
        spurs = new Aniso(spurs, 0.50f, 1f);
        spurs = new RefNoise.Mul(spurs, new RefNoise.Constant(0.018f));
        ridges = new RefNoise.Add(ridges, spurs);

        RefNoise.Node wash = centered(next.getAsInt(), 150f, 3, 0.009f);

        RefNoise.Node h = new RefNoise.Add(floor, ridges);
        return new RefNoise.Add(h, wash);
    }

    /**
     * B3 构造盆地。
     *
     * <pre>
     * bowl   = norm(720, 2)^1.3 × 0.050
     * h      = -0.026 + bowl + (norm(260, 1) - 0.5) × 0.007
     * </pre>
     * 只有大尺度形状，无中尺度特征 ⇒ 坡度极低，对应"宽缓的沉积盆地/单斜盆地"。
     */
    private static RefNoise.Node b3(java.util.function.IntSupplier next) {
        RefNoise.Node bowl = new RefNoise.Power(norm(next.getAsInt(), 720f, 2), 1.3f);
        bowl = new RefNoise.Mul(bowl, new RefNoise.Constant(0.050f));
        RefNoise.Node base = new RefNoise.Add(bowl, new RefNoise.Constant(-0.026f));
        return new RefNoise.Add(base, centered(next.getAsInt(), 260f, 1, 0.007f));
    }

    /**
     * 把 Perlin 的"实际值域"拉到 [0,1]。
     *
     * <p>{@link RefNoise.Perlin} 声明值域 0..1，但单次求值只用到中间一段；特征波长越接近
     * 观察窗口，跨幅越小。若直接乘振幅，起伏会小到看不出来（首版 1500 格波长只出 2.7 格）。
     * 这里沿用参考自己的惯用法（{@code Clamp} 定窗口 + {@code Map} 拉伸）：窗口取 0.32–0.68，
     * 只有尾部几个百分点被削平，既保住平滑又不浪费值域。</p>
     */
    private static RefNoise.Node norm(int seed, float scale, int octaves) {
        RefNoise.Node n = RefNoise.Perlin.ofScale(seed, scale, octaves);
        n = new RefNoise.Clamped(n, 0.32f, 0.68f);
        return new RefNoise.Mapped(n, 0.32f, 0.68f, 0f, 1f);
    }

    /**
     * 各向异性采样：把坐标按 (sx, sz) 缩放后再求值子节点。
     *
     * <p>盆岭区的残余山脊是<b>成排、有走向</b>的，而单个各向同性 Perlin 取幂只会给出
     * 等尺度的圆包（首版实测就是一盘均匀圆丘）。{@code sx<1} 表示 x 方向取样变慢
     * ⇒ 特征沿 x 拉长。</p>
     */
    private record Aniso(RefNoise.Node child, float sx, float sz) implements RefNoise.Node {
        @Override public float compute(float x, float z) { return child.compute(x * sx, z * sz); }
    }

    /** 以 0 为中心的弱起伏：(norm − 0.5) × amp。 */
    private static RefNoise.Node centered(int seed, float scale, int octaves, float amp) {
        RefNoise.Node n = norm(seed, scale, octaves);
        n = new RefNoise.Add(n, new RefNoise.Constant(-0.5f));
        return new RefNoise.Mul(n, new RefNoise.Constant(amp));
    }

    /** 候选直接产出 e（不再乘参考单位），供 {@code HeightCurve.heightFromE} 使用。 */
    public double e(double wx, double wz) {
        if (height == null) throw new IllegalStateException("seed() not called");
        return height.compute((float) wx, (float) wz);
    }
}