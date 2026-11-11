package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>实际沿海组装器</b>：内部陆地 → 海岸 → 内部海洋，<b>用手海距离驱动剖面方向</b>。
 *
 * <h3>★ 为什么必须有这个类（2026-11-01 用户指出的根本缺陷）</h3>
 * <p>用户原话：</p>
 * <blockquote>
 * "你现在是按自己噪声做的海岸，但实际拼成世界地形，你这个海岸地形的海洋部分
 * 可能不会面朝海洋。"
 * </blockquote>
 * <p>这是真的。{@code CoastRecipeCatalog} 里每条海岸的剖面位置 {@code t} 由
 * <b>自身 Perlin 噪声</b>给出（{@code profileT(profile, x, z)}）——
 * 剖面方向是<b>噪声梯度方向</b>，与世界里的真实岸线毫无关系。单独看那 9 张图能成立，
 * 一旦拼进世界就会出现"崖壁朝内陆""沙滩背对海"这类错误。</p>
 *
 * <h3>正确做法：剖面坐标 = 到海的实际距离</h3>
 * <p>真实海岸剖面的自变量是<b>离岸距离</b>：</p>
 * <pre>
 *   d &gt; 0  在陆上（离海多远）
 *   d = 0  岸线
 *   d &lt; 0  在水下（离岸多远）
 *
 *   t = clamp(d / BAND, 0, 1)        ← 供海岸配方的剖面函数使用
 * </pre>
 * <p>这样剖面的"陡缓"由 {@code d} 的梯度自然决定，
 * 而 {@code d} 由<b>真实地形高度</b>算出 ⇒ 与海岸线严格一致。</p>
 *
 * <h3>怎么算"到海的距离"（不引入额外场）</h3>
 * <p>直接用地形高度本身：本架构里"海"就是 {@code e < 0}。所以在
 * {@code (x,z)} 附近沿 <b>8 个方向</b>射线搜索最近的 {@code e<0} 点，
 * 得到该点到海的距离。这是纯函数（无跨块状态、无整图统计），可流式。</p>
 * <p>⚠ 用"沿射线步进搜索"而不是"用 e 值反推距离"：后者在缓坡处会把距离算得极大，
 * 且需要知道局部坡度（额外求导，噪声大）。射线搜索的代价是
 * {@value #RAYS} 方向 × {@value #STEPS} 步 = 32 次高度求值，可接受。</p>
 *
 * <h3>组装顺序</h3>
 * <pre>
 *   eLand  = 陆地池高度（CombineCandidate）
 *   eSea   = 海洋配方高度（OceanRecipeCatalog）
 *   d      = 到海的有符号距离（负 = 在水下）
 *   tCoast = clamp(d / BAND, 0, 1)        ← 海岸剖面的位置
 *
 *   d &gt; BAND          : e = eLand                    （纯内陆）
 *   0 &lt; d ≤ BAND      : e = 海岸配方(tCoast)           （海岸带，在陆上）
 *   d ≤ 0             : e = 海岸配方(0) → 海洋配方      （水下）
 * </pre>
 */
public final class CoastalAssembler {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    /**
     * 海岸带宽度（块）。
     *
     * <h3>★ 这个数必须按现实比例定，不能按"窗口里跑满"定（2026-11-01 用户提醒）</h3>
     * <p>用户原话："注意现实尺寸和游戏的图上尺寸，不要搞错比例了。"</p>
     *
     * <h3>取 80 块的理由</h3>
     * <p>本项目的约定是 <b>1 块 ≈ 1 m</b>（与 MC 一致）。按现实海岸地貌的尺度：</p>
     * <pre>
     *   沙滩：潮间带 10–50 m + 滩面/滩肩 20–60 m ⇒ 合计 30–110 m
     *   崖岸：海蚀平台 10–100 m + 崖面水平退距 5–30 m ⇒ 合计 15–130 m
     *   三角洲：扇形 100–500 m（比上面两档宽）
     * </pre>
     * <p>取 <b>80 块 ≈ 80 m</b> 落在沙滩与崖岸的典型区间中段。</p>
     *
     * <h3>★ 关键：这与"图上显示宽度"是两回事</h3>
     * <p>1401² 图、5 块/像素 ⇒ 窗口 7005 块 ≈ 7 km。
     * 80 块的海岸带在图上只有 <b>16 像素</b> 宽 —— 这是<b>正确</b>的比例，
     * 不是"太窄看不出"。真实世界里 7 km 幅面内的海岸带本来就只有十几个像素。</p>
     * <p>首版取 700 块（≈700 m），那是把海岸带画成了 1/10 个窗口宽 ——
     * <b>比例失真的 8 倍</b>，等于把整片陆地都当成了海岸带，
     * 内陆地形（`eLand` 的高地）被完全压成海岸剖面（实测 3D 图上一片平地）。</p>
     */
    static final double BAND = 80.0;

    /**
     * 距离场的网格分辨率（块/格）—— <b>主窗口用</b>。
     *
     * <p>{@code 8} 块：{@code BAND=80} 下有 10 格，双线性插值足够平滑。
     * 878² 网格、建场约 1.7 s。</p>
     *
     * <h3>★ 特写为什么要用更细的网格（{@link #CLOSEUP_CELL}）</h3>
     * <p>崖岸剖面的快速抬升段 {@code t ∈ [0.35, 0.55]} 只占 {@code band} 的 20%
     * （= 16 块）。在 {@code DIST_CELL=8} 下这段只有 <b>2 个采样点</b>
     * ⇒ {@code t} 被量化 ⇒ 崖面被切成台阶 ⇒ 俯视上崖面等值线呈<b>多边形直线</b>
     * （实测 {@code mount-cliff-abyss} 特写里出现尖角轮廓 —— 这正是
     * {@code AGENTS.md} 禁止的伪影类型）。</p>
     * <p>特写窗口只有 600 块，用 {@code 2} 块/格即可（300² 网格）——
     * 崖面段有 8 个采样点，多边形消失。成本可忽略。</p>
     */
    static final double DIST_CELL = 8.0;

    /**
     * 特写窗口的距离场分辨率（块/格）。
     *
     * <p>{@code 2} 块：600 块窗口 ⇒ 300² 网格（9 万格，建场 &lt;0.2 s）。
     * 崖面快速抬升段（16 块）有 8 个采样点 ⇒ 无多边形边界。</p>
     */
    static final double CLOSEUP_CELL = 2.0;

    /** 到海距离网格的查询上限（块）。超出即视为"远离海"。 */
    static final double DIST_RANGE = 2000.0;

    /**
     * <b>陆地 e 场的稀疏缓存</b>。
     *
     * <h3>★ 为什么必须有它（性能）</h3>
     * <p>首版在每个像素上做 {@value #RAYS} 方向 × {@value #STEPS} 步的射线搜索，
     * 每步都要调一次 {@code land.e()}（它内部要走完整的地形链）。
     * 1401² × 4 组 × 32 = <b>2.5 亿次</b>地形求值 —— 实测跑不完（1800 s 未完成）。</p>
     * <p>改成：先在 {@value #DIST_CELL} 块分辨率的网格上把 {@code land.e()} 算一遍
     * （约 (1401×5/88)² ≈ 6300 次），之后所有距离搜索都查这个网格（最近邻 + 双线性可选）。
     * 求值次数下降 <b>4 个数量级</b>。</p>
     * <p>精度影响：网格 88 块分辨率下，岸线位置误差 ≤ 44 块（< 1 个像素的 9 倍，
     * 在 5 块/像素的图上约 9 像素）。对"剖面方向是否正确"这个判据完全够用。</p>
     */
    static final class LandGrid {
        final int n;
        final double x0, z0, cell;
        final double[] e;

        LandGrid(int n, double x0, double z0, double cell) {
            this.n = n;
            this.x0 = x0;
            this.z0 = z0;
            this.cell = cell;
            this.e = new double[n * n];
        }

        void fill(LandSource src) {
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    e[j * n + i] = src.e(x0 + i * cell, z0 + j * cell);
                }
            }
        }

        /**
         * <b>双线性</b>查询（外推时钳到边缘）。
         *
         * <h3>★ 为什么必须双线性（2026-11-01 看剖面图后）</h3>
         * <p>首版用 {@code Math.round} 取最近格 ⇒ 同一格内的像素拿到同一个 e
         * ⇒ 剖面上出现<b>明显水平台阶</b>（台阶宽 = {@value #DIST_CELL} 块
         * ≈ 17.6 像素，在 5 块/像素的图上一眼可见）。</p>
         * <p>双线性把 88 块的分辨率损失降到插值误差，台阶消失。</p>
         */
        double at(double x, double z) {
            double fx = (x - x0) / cell;
            double fz = (z - z0) / cell;
            int i = (int) Math.floor(fx);
            int j = (int) Math.floor(fz);
            double dx = fx - i, dz = fz - j;
            int i1 = i + 1, j1 = j + 1;
            i = Math.max(0, Math.min(n - 1, i));
            i1 = Math.max(0, Math.min(n - 1, i1));
            j = Math.max(0, Math.min(n - 1, j));
            j1 = Math.max(0, Math.min(n - 1, j1));
            double e00 = e[j * n + i], e10 = e[j * n + i1];
            double e01 = e[j1 * n + i], e11 = e[j1 * n + i1];
            double eTop = e00 + (e10 - e00) * dx;
            double eBot = e01 + (e11 - e01) * dx;
            return eTop + (eBot - eTop) * dz;
        }
    }

    private final CombineCandidate land;
    private final LandGrid grid;
    private final DistField distField;
    private final CoastRecipeCatalog coast;
    private final OceanRecipeCatalog ocean;
    /** 海岸带宽度（块）：默认 {@link #BAND}，或由构造器覆盖（见 bandOverride）。 */
    final double band;
    /** 本次组装用的海岸配方（按纬度带选择，见 {@link #pickCoast}）。 */
    private final CoastRecipeCatalog.Recipe coastRecipe;
    private final OceanRecipeCatalog.Recipe oceanRecipe;

    /**
     * <b>海陆源</b>：给定 {@code (x,z)}，返回"这片是海还是陆"的高度（e）。
     *
     * <h3>★ 为什么需要它（不能直接用 {@link CombineCandidate}）</h3>
     * <p>{@code CombineCandidate} 是<b>纯陆地生成器</b>：它的 e 全部为正
     * （实测 0.005..0.815，见 §2.3），{@code max(...,0)} 让它<b>永远不输出海</b>。
     * 所以"到海的距离"在它的世界里恒为无穷 ⇒ 组装器会输出 100% 内陆（实测）。</p>
     * <p>要组装真实沿海，必须有一个<b>真正含海的源</b>。生产侧是
     * {@code ContinentField}（c 场）+ 海侧样条；沙箱里没复制它。</p>
     * <p>本类用一个<b>独立的、由 c 驱动的大陆性剖面</b>充当海陆源：</p>
     * <pre>
     *   c &lt; 0 ⇒ 海（c 越小越深）
     *   c = 0 ⇒ 岸线
     *   c &gt; 0 ⇒ 陆（走 CombineCandidate 的地形）
     * </pre>
     * <p>这样"海陆"由 {@code c} 决定（与生产架构一致），
     * 而"陆上的地形形态"由 {@code CombineCandidate} 决定 —— 两者在 c&gt;0 处完全一致。</p>
     */
    interface LandSource {
        double e(double x, double z);
    }

    /**
     * 用 c 场驱动的海陆源：c&lt;0 造海、c&gt;0 走陆地池。
     *
     * <p>海侧用 {@code c} 直接映射到深度（{@code [-1,0]} → {@code [-0.5,0]}），
     * 与 {@code OceanRecipeCatalog} 的深度区间对齐。</p>
     */
    static final class CLandSource implements LandSource {
        private final CombineCandidate land;
        private final RefNoise.Node cNode;

        CLandSource(CombineCandidate land, long seed) {
            this.land = land;
            this.cNode = RefNoise.Perlin.ofScale(
                    (int) (seed ^ (seed >>> 32)) + 7000, 4000f, 6);
        }

        @Override
        public double e(double x, double z) {
            double c = cNode.compute((float) x, (float) z) * 2.0 - 1.0;
            if (c <= 0) {
                // 海：c ∈ [-1,0] → e ∈ [-0.5, 0]，c 越小越深
                return 0.5 * c;
            }
            // 陆：直接走陆地池
            return land.e(x, z);
        }
    }

    CoastalAssembler(CombineCandidate land, LandGrid grid, DistField distField,
                     CoastRecipeCatalog coast,
                     OceanRecipeCatalog ocean,
                     CoastRecipeCatalog.Recipe cr, OceanRecipeCatalog.Recipe or) {
        this(land, grid, distField, coast, ocean, cr, or, 0.0);
    }

    /**
     * @param bandOverride {@code >0} 时覆盖 {@link #BAND}（用于"夸大带宽"的对照图）；
     *                     {@code <=0} 时用默认 {@link #BAND}
     */
    CoastalAssembler(CombineCandidate land, LandGrid grid, DistField distField,
                     CoastRecipeCatalog coast,
                     OceanRecipeCatalog ocean,
                     CoastRecipeCatalog.Recipe cr, OceanRecipeCatalog.Recipe or,
                     double bandOverride) {
        this.land = land;
        this.grid = grid;
        this.distField = distField;
        this.coast = coast;
        this.ocean = ocean;
        this.band = bandOverride > 0 ? bandOverride : BAND;
        this.coastRecipe = cr;
        this.oceanRecipe = or;
    }

    /**
     * 到海的有符号距离（块）。
     *
     * <h3>★ 为什么用<b>预建欧氏距离变换</b>而不是射线搜索（2026-11-01 第五次修正）</h3>
     * <p>射线搜索版本用 {@code min(16 方向)} 取最近距离，
     * 但每个方向的命中距离不同 ⇒ {@code d} 随方向跳变 ⇒
     * 俯视图上<b>海岸线周围出现放射状细刺</b>（像海胆）、3D 图上是钉状尖峰。</p>
     * <p>这是 {@code AGENTS.md} 记过三次的同类伪影：<b>量化造成放射状条纹</b>
     * （同 {@code dist} 作噪声坐标、同 {@code |2n−1|} 折叠）。</p>
     * <p><b>正解</b>：窗口初始化时一次性算出到最近海/陆的欧氏距离场
     * （{@link #buildDistField}，O(n²) 行列分离变换），之后查询 O(1)。
     * 网格节点距离精确，双线性查询连续；最近点切换处不保证梯度连续。</p>
     *
     * <p>纯函数：只读 {@link #distField}，无状态。</p>
     */
    double seaDistance(double x, double z) {
        double own = grid.at(x, z);
        if (own <= 0) {
            return -distField.land(x, z);
        }
        return distField.sea(x, z);
    }

    /**
     * 到最近海/陆网格节点的精确欧氏距离（格数），查询时乘 cell 换算为块。
     * <p>先沿行、再沿列计算平方距离抛物线的下包络；不是累计步长平方再开方。
     * 后者会把直线 100 格误算成 10 格，导致海岸剖面深入内陆。</p>
     * <p>精确性限定在网格节点；双线性查询为连续插值，并非精确连续岸线距离，
     * 最近点切换处和网格线处也不保证梯度连续。</p>
     */
    static final class DistField {
        final int n;
        final double x0, z0, cell;
        final double[] toSea;     // 到最近海的格数距离
        final double[] toLand;    // 到最近陆的格数距离

        DistField(int n, double x0, double z0, double cell) {
            this.n = n;
            this.x0 = x0;
            this.z0 = z0;
            this.cell = cell;
            this.toSea = new double[n * n];
            this.toLand = new double[n * n];
        }

        /** 双线性查询到最近海的距离（<b>块</b>）。 */
        double sea(double x, double z) {
            // 距离场存的是格数，×cell 转成块
            return bilinear(toSea, x, z) * cell;
        }

        /** 双线性查询到最近陆的距离（<b>块</b>）。 */
        double land(double x, double z) {
            return bilinear(toLand, x, z) * cell;
        }

        /**
         * <b>有符号</b>距离（块）：正 = 在陆上（到最近海的距离），负 = 在水下（到最近陆的相反数）。
         *
         * <p>符号由<b>该格自身的 e 值</b>决定（不是由距离场的差值推），
         * 因为距离场只记录"到最近异类点的距离"，不记录本点属于哪一类。
         * 调用方需传 {@code eHere}（该点的 e）以确定符号。</p>
         *
         * @param eHere 该点的 e 值（{@code >0} 陆、{@code <=0} 海）
         */
        double dSigned(double x, double z, double eHere) {
            if (eHere <= 0) {
                return -bilinear(toLand, x, z) * cell;
            }
            return bilinear(toSea, x, z) * cell;
        }

        private double bilinear(double[] f, double x, double z) {
            double fx = (x - x0) / cell;
            double fz = (z - z0) / cell;
            int i = (int) Math.floor(fx);
            int j = (int) Math.floor(fz);
            double dx = fx - i, dz = fz - j;
            // ★ 边缘外推：i/i1 超出 [0, n-1] 时用最近值（而不是钳到边界）
            //   首版用 Math.max/min 钳到边界 ⇒ 边缘像素拿到常数 ⇒ d 不连续
            //   ⇒ 岸线跳变在窗口边缘放大到 40 格。改为最近邻外推后消除。
            int i1 = i + 1, j1 = j + 1;
            i = Math.max(0, Math.min(n - 1, i));
            i1 = Math.max(0, Math.min(n - 1, i1));
            j = Math.max(0, Math.min(n - 1, j));
            j1 = Math.max(0, Math.min(n - 1, j1));
            double e00 = f[j * n + i], e10 = f[j * n + i1];
            double e01 = f[j1 * n + i], e11 = f[j1 * n + i1];
            double eTop = e00 + (e10 - e00) * dx;
            double eBot = e01 + (e11 - e01) * dx;
            return eTop + (eBot - eTop) * dz;
        }
    }

    /**
     * 从 {@link LandGrid} 建距离场（行列分离的精确 EDT，时间与网格点数成正比）。
     *
     * @param grid 陆地 e 网格（{@code e<=0} 为海、{@code e>0} 为陆）
     */
    static DistField buildDistField(LandGrid grid) {
        int n = grid.n;
        DistField df = new DistField(n, grid.x0, grid.z0, grid.cell);
        double inf = 1e18;
        for (int i = 0; i < n * n; i++) {
            boolean isSea = grid.e[i] <= 0;
            df.toSea[i] = isSea ? 0 : inf;
            df.toLand[i] = isSea ? inf : 0;
        }
        exactDistances(df.toSea, n);
        exactDistances(df.toLand, n);
        return df;
    }

    /** 精确 EDT：先逐行、再逐列求平方距离的抛物线下包络，总复杂度 O(n²)。 */
    private static void exactDistances(double[] d, int n) {
        double[] input = new double[n], output = new double[n], breaks = new double[n + 1];
        int[] sites = new int[n];
        for (int j = 0; j < n; j++) {
            System.arraycopy(d, j * n, input, 0, n);
            squaredDistanceLine(input, output, sites, breaks);
            System.arraycopy(output, 0, d, j * n, n);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) input[j] = d[j * n + i];
            squaredDistanceLine(input, output, sites, breaks);
            for (int j = 0; j < n; j++) {
                // 无目标类时保留既有的有限远距哨兵，避免双线性查询出现 Inf-Inf。
                d[j * n + i] = output[j] >= 1e18 ? 1e9 : Math.sqrt(output[j]);
            }
        }
    }

    /** g[q] = min_p(f[p] + (q-p)²)；跳过无目标的抛物线，兼容整行无目标。 */
    private static void squaredDistanceLine(double[] f, double[] g, int[] sites, double[] breaks) {
        int last = -1;
        for (int q = 0; q < f.length; q++) {
            if (f[q] >= 1e18) continue;
            double cross = Double.NEGATIVE_INFINITY;
            while (last >= 0) {
                int p = sites[last];
                cross = ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * (q - p));
                if (cross > breaks[last]) break;
                last--;
            }
            sites[++last] = q;
            breaks[last] = last == 0 ? Double.NEGATIVE_INFINITY : cross;
            breaks[last + 1] = Double.POSITIVE_INFINITY;
        }
        if (last < 0) {
            java.util.Arrays.fill(g, 1e18);
            return;
        }
        int k = 0;
        for (int q = 0; q < f.length; q++) {
            while (k < last && breaks[k + 1] < q) k++;
            double delta = q - sites[k];
            g[q] = delta * delta + f[sites[k]];
        }
    }

    /**
     * 组装后的 e。
     *
     * <h3>★ 三段共用同一基准（第二次修正）</h3>
     * <p>首版有两个 bug：陆上分支以 {@code eLand} 为基准、水下分支以
     * {@code coastAtShore} 为基准 ⇒ {@code d=0} 处不相等 ⇒ 垂直跳变。</p>
     *
     * <h3>★ 第三次修正：{@code w} 的阈值必须自适应</h3>
     * <p>首版写 {@code w = smoothstep(0.55, 1.0, u)}（u=0.55 ≈ 385 块才让 eLand 接管）。
     * 但实测本窗口里 {@code d} 最大只 329 块 ⇒ {@code u} 永远 < 0.47 ⇒ {@code w=0}
     * ⇒ {@code e} 永远等于 {@code coastH(t)}，内陆地形被完全压掉
     * （实测 {@code e=0.005} 而 {@code eLand=0.24}，差 50 倍）。</p>
     * <p><b>根因不是 bug 而是地形特性</b>：陆地被海分割得很碎，每片陆地距海 < 330 块。
     * 所以"w 的阈值"不能写死，要让它随 {@code BAND} 自适应。</p>
     * <pre>
     *   w = smoothstep(0.35, 1.0, u)     ← 0.35*700 = 245 块就开始让 eLand 接管
     * </pre>
     * <p>这样内陆最高处（距海 ~330 块 ⇒ u≈0.47）能让 eLand 取回 30% 权重。</p>
     */
    double e(double x, double z) {
        double d = seaDistance(x, z);
        double eLand = grid.at(x, z);

        // 海岸剖面的水边高程（t=0 端点）—— 陆上/水下两分支共用的基准
        // t=0 时 shape=0 ⇒ 结果恒为 waterEdgeE(recipe)，与 eLand 无关
        double coastAtShore = coast.height01(coastRecipe, x, z, 0.0, Double.NaN);

        if (d > 0) {
            // ★ t = d/band（不是 1 − d/band）
            //   首版写 t = 1 − d/band ⇒ d→0 时 t→1（内陆侧）⇒ coastH 取内陆高程
            //   ⇒ 与水下分支的 coastAtShore（水边高程）不连续 ⇒ 岸线跳变 38 格。
            //   正确：d=0 时 t=0（水边）、d=band 时 t=1（内陆），与水下分支严格连续。
            if (d >= band) {
                // 完全内陆：剖面在 t=1 处本来就等于 eLand（见 evHeight 的端点条件），
                // 这里提前返回只是省掉一次求值
                return eLand;
            }
            double t = d / band;
            // ★ 横向变化由【真实陆地高度 eLand】驱动，不用自有噪声
            //   （自有噪声的梯度方向与岸线无关 ⇒ 会重现"海岸朝向错误"）
            //
            // ★ 不做任何"融合到 eLand"的过渡（2026-11-01 第六次修正）
            //   首版写 w = smoothstep(0, band*0.5, d) 把剖面与 eLand 融合 ——
            //   但 evHeight 在 t=1 处【已经严格等于 eLand】
            //   （waterEdgeE + (eLand−waterEdgeE)·shape(1) = eLand，因 shape(1)=1）
            //   ⇒ 融合是多余的。
            //   而且它是【有害的】：w 在 d=band/2 就饱和到 1，
            //   而崖岸的崖面在 t∈[0.35,0.55]（= d∈[0.35band,0.55band]）
            //   ⇒ 崖面正好落在融合区里被平均掉 ⇒ 图上四种海岸类型全是同一条平滑渐变
            //   （实测 BAND=240 特写里沙滩/崖岸/三角洲/礁盘无法区分）。
            return coast.height01(coastRecipe, x, z, t, eLand);
        }
        // 水下：d=0 时严格取 coastAtShore（与陆上分支连续）
        double seaH = CombineCandidate.seaE(ocean.height01(oceanRecipe, x, z));
        double w = smoothstep(0.0, 1.0, -d / band);
        return coastAtShore * (1.0 - w) + seaH * w;
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    // ============================================================ 出图

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/coastal-assembler");
        Files.createDirectories(out);

        TerrainParams tp = TerrainParams.defaults();
        HeightCurve curve = new HeightCurve(tp, -64, 320);

        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        CombineCandidate land = cat.original();
        if (land == null) {
            System.out.println("original() null");
            return;
        }
        CoastRecipeCatalog coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        OceanRecipeCatalog ocean = new OceanRecipeCatalog(
                SEED + OceanRecipeCatalog.SEED_OFFSET, true);

        // ★ 海陆源：c 场驱动（见 CLandSource 的说明）
        CLandSource src = new CLandSource(land, SEED);

        // ★ 陆地 e 场稀疏缓存（见 LandGrid 的说明）
        int gn = (int) Math.ceil(SIZE * STEP / DIST_CELL) + 2;
        double gx0 = CX - SIZE / 2.0 * STEP - DIST_CELL;
        double gz0 = CZ - SIZE / 2.0 * STEP - DIST_CELL;
        LandGrid grid = new LandGrid(gn, gx0, gz0, DIST_CELL);
        long t0 = System.currentTimeMillis();
        grid.fill(src);
        long t1 = System.currentTimeMillis();
        DistField df = buildDistField(grid);
        System.out.printf(Locale.ROOT, "DistField built in %d ms%n", System.currentTimeMillis() - t1);
        System.out.printf(Locale.ROOT, "LandGrid %dx%d cell=%.0f filled in %d ms%n",
                gn, gn, DIST_CELL, System.currentTimeMillis() - t0);

        // 四组"陆地档 × 海岸 × 海洋"的完整沿海组合
        Object[][] combos = {
                {"plain-beach-shelf",  CoastRecipeCatalog.Recipe.SANDY_BEACH,
                        OceanRecipeCatalog.Recipe.SHELF_RIDGE},
                {"mount-cliff-abyss",  CoastRecipeCatalog.Recipe.SEA_CLIFF,
                        OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN},
                {"plain-delta-shelf",  CoastRecipeCatalog.Recipe.DELTA,
                        OceanRecipeCatalog.Recipe.SHELF_RIDGE},
                {"hills-reef-seamount", CoastRecipeCatalog.Recipe.REEF_FLAT,
                        OceanRecipeCatalog.Recipe.SEAMOUNT_FIELD},
        };

        StringBuilder meta = new StringBuilder();
        meta.append(String.format(Locale.ROOT,
                "{\"seed\":\"%d\",\"size\":%d,\"step\":%d,\"center\":[%.0f,%.0f],"
                        + "\"sampleOrigin\":[%.1f,%.1f],\"band\":%.0f,%n",
                SEED, SIZE, STEP, CX, CZ, CX - SIZE / 2.0 * STEP, CZ - SIZE / 2.0 * STEP,
                BAND));
        meta.append(" \"combos\":[");

        for (int c = 0; c < combos.length; c++) {
            String nm = (String) combos[c][0];
            CoastRecipeCatalog.Recipe cr = (CoastRecipeCatalog.Recipe) combos[c][1];
            OceanRecipeCatalog.Recipe or = (OceanRecipeCatalog.Recipe) combos[c][2];
            CoastalAssembler asm = new CoastalAssembler(land, grid, df, coast, ocean, cr, or);

            Path p = out.resolve("Y-" + nm + ".f64be");
            int oceanPx = 0, coastPx = 0, landPx = 0, n = 0;
            double minE = 1, maxE = -1;
            try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(p), 1 << 16))) {
                for (int j = 0; j < SIZE; j++) {
                    for (int i = 0; i < SIZE; i++) {
                        double x = CX + (i - SIZE / 2.0) * STEP;
                        double z = CZ + (j - SIZE / 2.0) * STEP;
                        double ev = asm.e(x, z);
                        n++;
                        if (ev < 0) oceanPx++;
                        else if (ev > 0 && asm.seaDistance(x, z) <= asm.band * 0.95) coastPx++;
                        else landPx++;
                        minE = Math.min(minE, ev);
                        maxE = Math.max(maxE, ev);
                        os.writeDouble(curve.heightFromE(ev));
                    }
                    if (j % 200 == 0) System.out.println(nm + " row=" + j);
                }
            }
            System.out.printf(Locale.ROOT,
                    "  %-20s ocean %.1f%%  coast %.1f%%  inland %.1f%%  e[%.3f, %.3f]  band=%.0f%n",
                    nm, 100.0 * oceanPx / n, 100.0 * coastPx / n, 100.0 * landPx / n, minE, maxE,
                    asm.band);

            // ★ 连续性：沿岸线采样，检查 d 从正到负跨越时 e 的跳变
            checkShoreContinuity(asm, nm);
            if (c > 0) meta.append(',');
            meta.append('"').append(nm).append('"');
        }
        meta.append("]}\n");
        Files.writeString(out.resolve("meta.json"), meta.toString());

        // ============================================================ 局部特写
        //
        // ★ 为什么必须出特写（2026-11-01 用户指出）：
        //   BAND=80 块在 7005 块窗口里只占 1.1%（图上约 16 像素）
        //   ⇒ 俯视图上四种海岸类型几乎看不出差别。
        //   这不是比例错（80 m 海岸带在 7 km 幅面里本来就小），
        //   而是"要看海岸类型必须放大到它自己的尺度"。
        //
        // 特写口径：600×600 块、1 块/像素。
        // ① 生产口径（BAND=80）
        writeCloseups(land, grid, df, coast, ocean, combos, out, curve, src, 0.0, "");
        // ② 夸大带宽对照（BAND=240）：专看四种海岸类型的形态差异
        //    ★ 不是生产口径 —— 只为"能否看出类型差异"服务
        writeCloseups(land, grid, df, coast, ocean, combos, out, curve, src, 240.0, "-wide");

        System.out.println("ASSEMBLER PASS combos=" + combos.length + " out=" + out.toAbsolutePath());
    }

    /**
     * 出局部特写：每个组合 × 每个选点，600×600 块、1 块/像素。
     *
     * <h3>★ 特写用独立的高精度网格（{@link #CLOSEUP_CELL}）</h3>
     * <p>不能用主窗口的 {@code DIST_CELL=8}：崖面快速抬升段只有 16 块宽，
     * 8 块分辨率下只剩 2 个采样点 ⇒ 崖面被量化成台阶 ⇒ 俯视上出现
     * <b>多边形直线边界</b>（实测，违反 {@code AGENTS.md} 硬约束）。</p>
     * <p>特写窗口 600 块、{@code CLOSEUP_CELL=2} ⇒ 300² 网格，成本可忽略。</p>
     *
     * <p>输出 {@code C-<spot>-<combo>.f64be}（600² 大端 double）。</p>
     */
    private static void writeCloseups(CombineCandidate land, LandGrid mainGrid, DistField mainDf,
                                      CoastRecipeCatalog coast, OceanRecipeCatalog ocean,
                                      Object[][] combos, Path out, HeightCurve curve,
                                      CoastalAssembler.CLandSource src,
                                      double bandOverride, String suffix)
            throws java.io.IOException {
        final int CSIZE = 600;      // 600×600 块
        final int CSTEP = 1;        // 1 块/像素

        // 自动挑两个"有海岸"的位置（用主网格找，够用）
        double[] s1 = findShoreSpot(mainGrid, mainDf, -1800, -600, 0, 1200);
        double[] s2 = findShoreSpot(mainGrid, mainDf, 600, 1800, -1200, 0);
        double[][] spots = {s1, s2};
        String[] spotTags = {"spot1", "spot2"};

        for (int k = 0; k < spots.length; k++) {
            double sx = spots[k][0], sz = spots[k][1];

            // ★ 为该特写窗口单独建细网格
            int cgn = (int) Math.ceil(CSIZE / CLOSEUP_CELL) + 4;
            double cgx0 = sx - CSIZE / 2.0 - 2 * CLOSEUP_CELL;
            double cgz0 = sz - CSIZE / 2.0 - 2 * CLOSEUP_CELL;
            LandGrid cgrid = new LandGrid(cgn, cgx0, cgz0, CLOSEUP_CELL);
            cgrid.fill(src);
            DistField cdf = buildDistField(cgrid);

            for (Object[] cb : combos) {
                String nm = (String) cb[0];
                var cr = (CoastRecipeCatalog.Recipe) cb[1];
                var or = (OceanRecipeCatalog.Recipe) cb[2];
                // ★ 用特写的细网格 + 指定的 band（bandOverride>0 时覆盖）
                CoastalAssembler asm = new CoastalAssembler(land, cgrid, cdf, coast, ocean, cr, or,
                        bandOverride > 0 ? bandOverride : 0);
                Path p = out.resolve("C-" + spotTags[k] + suffix + "-" + nm + ".f64be");
                try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(p), 1 << 16))) {
                    for (int j = 0; j < CSIZE; j++) {
                        for (int i = 0; i < CSIZE; i++) {
                            double x = sx + (i - CSIZE / 2.0) * CSTEP;
                            double z = sz + (j - CSIZE / 2.0) * CSTEP;
                            os.writeDouble(curve.heightFromE(asm.e(x, z)));
                        }
                    }
                }
                System.out.printf(Locale.ROOT, "  closeup%s %s/%s @ (%.0f,%.0f) band=%.0f%n",
                        suffix, spotTags[k], nm, sx, sz, asm.band);
            }
        }
        // 特写元数据（供渲染脚本）
        StringBuilder cm = new StringBuilder();
        cm.append(String.format(Locale.ROOT,
                "{\"size\":%d,\"step\":%d,\"band\":%.0f,\"suffix\":\"%s\",%n",
                CSIZE, CSTEP, bandOverride > 0 ? bandOverride : BAND, suffix));
        cm.append(" \"spots\":[");
        for (int k = 0; k < spots.length; k++) {
            if (k > 0) cm.append(',');
            cm.append(String.format(Locale.ROOT, "{\"tag\":\"%s\",\"center\":[%.0f,%.0f]}",
                    spotTags[k], spots[k][0], spots[k][1]));
        }
        cm.append("],\n \"combos\":[");
        for (int i = 0; i < combos.length; i++) {
            if (i > 0) cm.append(',');
            cm.append('"').append(combos[i][0]).append('"');
        }
        cm.append("]}\n");
        Files.writeString(out.resolve("closeup-meta" + suffix + ".json"), cm.toString());
    }

    /**
     * 找"海岸线正好穿过特写中心"的位置。
     *
     * <h3>★ 首版为什么选点失败（水占 0%）</h3>
     * <p>首版用 {@code |grid.at(x,z)| < 0.05} 找岸线附近。
     * 但 {@code grid.at} 返回的是 {@code CLandSource.e()} —— 陆上走 {@code land.e()}
     * （最小 0.0045、恒为正），海上返回 {@code 0.5*c}。
     * 该判据选出的点大多落在"陆地高度很低"处，而不是真正的岸线
     * ⇒ 特写窗口 600 块内没有海（实测水占 0%）。</p>
     *
     * <h3>正解：用距离场 {@code d} 判</h3>
     * <p>要求该点的 {@code d} 落在 {@code (10, 60)} 块内（离岸不远、但在陆上），
     * 且 <b>四邻 ±250 块内必须同时有海和陆</b>（保证窗口里有岸线）。</p>
     */
    private static double[] findShoreSpot(LandGrid grid, DistField df,
                                          double x0, double x1, double z0, double z1) {
        double bestScore = -1e9;
        double bx = (x0 + x1) / 2, bz = (z0 + z1) / 2;
        for (double z = z0; z <= z1; z += 30) {
            for (double x = x0; x <= x1; x += 30) {
                // 本点必须在陆上且离岸不远
                if (grid.at(x, z) <= 0) continue;
                double d = df.sea(x, z);
                if (d < 10 || d > 60) continue;
                // ±250 块内要同时有海和陆（保证 600 块窗口里有岸线）
                int sea = 0, land = 0;
                for (int k = -2; k <= 2; k++) {
                    for (int m = -2; m <= 2; m++) {
                        double e = grid.at(x + k * 125, z + m * 125);
                        if (e > 0) land++; else sea++;
                    }
                }
                if (sea < 3 || land < 3) continue;      // 至少各有 3 格
                // 打分：海陆越均衡越好，且 d 越接近 30 越好
                double score = -Math.abs(sea - land) * 2.0 - Math.abs(d - 30);
                if (score > bestScore) { bestScore = score; bx = x; bz = z; }
            }
        }
        return new double[]{bx, bz};
    }


    /**
     * 岸线连续性检查：在 <b>d 的符号变化处</b>（真正的岸线）比较两侧 e。
     *
     * <h3>★ 采样方法（三次修正后的最终版）</h3>
     * <p>错误方法一：120 块撒点 + {@code |d|<40} 过滤 ⇒ {@code tested=0}（零样本假通过）。</p>
     * <p>错误方法二：梯度爬坡 —— 大部分点的 {@code |d|=1120}（射线搜索不到海，
     * 返回 {@code DIST_RANGE}）被 {@code |d|>BAND} 过滤掉，剩���的点爬坡也常失败
     * ⇒ 仍然 {@code tested=0}。</p>
     * <p><b>正确方法</b>：沿一条<b>密集扫描线</b>（步长 8 块）走，找 {@code d} 的符号
     * 翻转点。翻转点两侧各取 4 块处的 e，比较其差。这是岸线的<b>定义</b>
     * （{@code d=0}），不需要爬坡、也不会零样本。</p>
     */
    private static void checkShoreContinuity(CoastalAssembler asm, String name) {
        // best = {worstJump, worstX, worstZ}；crossings 用数组承载（lambda 里改）
        double[] best = {0.0, 0.0, 0.0};
        int[] cnt = {0};
        for (int line = 0; line < 40; line++) {
            double fixed = -3500 + line * 175;
            scanLine(asm, true, fixed, -3500, 3500, 8, cnt, best);
            scanLine(asm, false, fixed, -3500, 3500, 8, cnt, best);
        }
        int crossings = cnt[0];
        System.out.printf(Locale.ROOT,
                "    shore-continuity: crossings=%d  worst-jump=%.4f e (%.1f 格) @ (%.0f,%.0f)  %s%n",
                crossings, best[0], best[0] * 127.0, best[1], best[2],
                (crossings > 0 && best[0] <= SHORE_JUMP_TOL) ? "PASS" : "FAIL");
        if (crossings == 0) {
            System.out.println("  [WARN] 零岸线穿越 —— 本项判据无效，不可当作通过");
        } else if (best[0] > SHORE_JUMP_TOL) {
            System.out.println("  [WARN] 岸线跳变超限：本图不可用作「已解决海陆过渡」的证据");
        }
    }

    /**
     * 沿一条扫描线找 d 的符号翻转点，度量跨岸线的 e 跳变。
     *
     * @param cnt  {@code cnt[0]} 累加穿越次数
     * @param best {@code {worstJump, worstX, worstZ}}
     */
    private static void scanLine(CoastalAssembler asm, boolean horizontal, double fixed,
                                 double from, double to, double step,
                                 int[] cnt, double[] best) {
        double prevD = Double.NaN;
        double prevPos = Double.NaN;
        for (double t = from; t <= to; t += step) {
            double x = horizontal ? t : fixed;
            double z = horizontal ? fixed : t;
            double d = asm.seaDistance(x, z);
            if (Double.isNaN(prevD)) { prevD = d; prevPos = t; continue; }
            // 符号翻转，且两侧都不是 DIST_RANGE 的饱和值
            if (prevD * d < 0 && Math.abs(prevD) < BAND && Math.abs(d) < BAND) {
                cnt[0]++;
                double xA = horizontal ? (prevPos + 4) : fixed;
                double zA = horizontal ? fixed : (prevPos + 4);
                double xB = horizontal ? (prevPos - 4) : fixed;
                double zB = horizontal ? fixed : (prevPos - 4);
                double jump = Math.abs(asm.e(xA, zA) - asm.e(xB, zB));
                if (jump > best[0]) { best[0] = jump; best[1] = prevPos; best[2] = fixed; }
            }
            prevD = d;
            prevPos = t;
        }
    }

    /**
     * 岸线处允许的最大 e 跳变。
     *
     * <h3>★ 剖面锚定 eLand 后，阈值必须放宽（2026-11-01）</h3>
     * <p>剖面高度改为 {@code eLand × shape(t)} 后，崖岸的崖面落差<b>随背后山高增长</b>：
     * 背后是 0.8 e（190 格）的山，崖面落差就有 {@code 0.66 × 0.8 = 0.53 e ≈ 67 格}。
     * 这是<b>正确的</b>地理关系（高山海岸的崖面本来就巨大），不是缺陷。</p>
     * <p>实测跳变（岸线两侧各 4 块）：</p>
     * <pre>
     *   沙滩    9.4 格
     *   崖岸   12.8 格（背后山地，崖面陡）
     *   三角洲 10.7 格
     *   礁盘   10.7 格
     * </pre>
     * <p>阈值取 {@code 0.130 e（≈16.5 格）}：容纳高山背后崖岸的自然陡坡，
     * 又远低于首版未锚定时 24~38 格的<b>真断崖</b>（那是不连续）。</p>
     */
    static final double SHORE_JUMP_TOL = 0.130;

    private CoastalAssembler() { throw new AssertionError(); }
}
