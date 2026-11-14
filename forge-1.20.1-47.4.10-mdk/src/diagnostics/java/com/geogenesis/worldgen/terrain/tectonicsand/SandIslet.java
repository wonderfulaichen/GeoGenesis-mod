package com.geogenesis.worldgen.terrain.tectonicsand;

/**
 * 沙箱：<b>岛链 / 群岛</b>——从大陆边缘或远洋成串冒出的小岛。
 *
 * <h2>参考的构造（planet.js 第 2 段 + 第 5 段，逐条对应）</h2>
 * <ol>
 *   <li><b>锚点</b>：随机挑一个大陆核。{@code 35%} 是<b>远洋岛弧</b>
 *       （offset = {@code r + 8..26°}，即冠缘再往外），其余是<b>近岸群岛</b>
 *       （offset = {@code r·(0.85..1.20) + 1°}，贴着海岸）。</li>
 *   <li><b>成链</b>：{@code n = 3..11} 个小岛，沿一个方向 {@code dir} 逐颗步进，
 *       每步方向加一点 {@code curve = ±0.125} 的弯 ⇒ <b>弯曲的岛链</b>
 *       （日本/阿留申/加勒比那种弧）。</li>
 *   <li><b>单岛</b>：半径 {@code r}、高 {@code h}；形状
 *       {@code dn = 距离/r + 0.35·fbm(3 倍频)}，{@code add = h·(1 − dn²)}——
 *       即"噪声扰动过的锥体"，不是圆盘。</li>
 *   <li><b>叠加</b>：参考用 {@code e = max(e, −200 m + add·1.1)}，
 *       所以岛能从深海底"顶"到海平面附近。</li>
 * </ol>
 *
 * <h2>无限世界怎么改（用户裁定：必须无限生成）</h2>
 * <p>参考一次性生成整张 {@code islets} 列表。我们改成<b>链格点</b>：
 * 每 {@link #CHAIN_SPACING} wu 一格，按哈希决定这一格有没有链、链锚在哪、
 * 朝哪个方向、几颗、每颗多大。查询时扫 ±1 格并用<b>包围圆</b>快速拒绝 ⇒ O(1)。</p>
 *
 * <h2>与参考的两处<b>有意</b>偏离（都为了不违反本项目的连续性铁律）</h2>
 * <ul>
 *   <li><b>叠加改成"只加不减"</b>：参考的 {@code max(e, −200+add)} 在深海会留下
 *       一圈从 −4500 m 直接跳到 −200 m 的<b>圆柱壁</b>（阶跃）。改成加性项
 *       {@code e += add}，岛从当地海底平滑升起——代价是深海里的岛不会冒出海面
 *       （那本来也看不见），近岸群岛则完全不受影响。</li>
 *   <li><b>尺寸放大</b>：参考的岛半径 0.18~1.32°（≈3~20wu）在他们那张全分辨率图上
 *       只有 1~6 格；我们 1wu = 2 格，照抄会得到"几格大的点"。改为 14~38wu
 *       （直径 28~76wu ≈ 8~22 格），并按半径限高，避免细长的针。</li>
 * </ul>
 */
final class SandIslet {

    /** 链格点间距（wu）。取板块间距的 ~2 倍：一片海盆里 1~2 条链。 */
    static final double CHAIN_SPACING = 700.0;
    /** 链搜索半径（格）。 */
    static final int SEARCH = 2;

    /** 单岛半径范围（wu）。40~100wu ⇒ 直径 80~200wu ≈ 23~57 格（MC 里 160~400 格宽）。 */
    static final double R_MIN = 40.0;
    static final double R_MAX = 100.0;
    /**
     * 出水高度范围（米，<b>海平面以上</b>）。
     *
     * <p>参考的 {@code h = 900~2500 m} 是"从 −200 m 基准往上"的量；
     * 我们改成 {@code 水深 + 出水高度}，所以这里只需要"露出海面多少"：
     * 150~700 m（小岛矮、大岛高）。</p>
     */
    static final double PEAK_MIN = 150.0;
    static final double PEAK_MAX = 700.0;
    /** 远洋岛弧的比例（参考 0.35）。 */
    static final double OCEANIC_FRAC = 0.35;
    /**
     * 有链的格点比例。
     *
     * <p>参考是"每核 0.5~4.5 条链"（全局 3~36 条 / 6~8 个核）。我方按面积密度给：
     * 0.62 时岛链（含浅滩）覆盖 8.8% 的图幅，把洋深 p75 从 3886 m 拉到 3106 m
     * （地球 3880）⇒ 压到 0.42，覆盖回到 ~5%。</p>
     */
    static final double CHAIN_PROB = 0.42;

    private static final long SALT_CHAIN = 0x2F7D3B91C4A68E05L;
    private static final long SALT_ISL = 0x6B1E48D27C93A5F0L;

    private SandIslet() { }

    /** 一条链的常量参数（一次性由格点哈希给出，链内每颗岛再单独哈希）。 */
    record Chain(double ax, double az, double dir, double curve, int n, double rBase) { }

    /** 该格点是否有链；null = 没有。 */
    static Chain chainAt(long seed, int cx, int cz) {
        long h = SandPlateField.hash(seed, cx, cz, SALT_CHAIN);
        if (SandPlateField.unit(h) > CHAIN_PROB) {
            return null;
        }
        double ax = (cx + 0.5 + (SandPlateField.unit(h >>> 8) - 0.5) * 0.9) * CHAIN_SPACING;
        double az = (cz + 0.5 + (SandPlateField.unit(h >>> 20) - 0.5) * 0.9) * CHAIN_SPACING;
        double dir = SandPlateField.unit(h >>> 32) * Math.PI * 2.0;
        double curve = (SandPlateField.unit(h >>> 44) - 0.5) * 0.25;
        int n = 4 + (int) (SandPlateField.unit(h >>> 52) * 8.0);       // 4..11
        boolean oceanic = SandPlateField.unit(h >>> 60) < OCEANIC_FRAC;
        // rBase 只是"这条链的体质"（远洋弧偏小），每颗岛再按自己的哈希抖动
        double rBase = oceanic ? 0.55 : 1.0;
        return new Chain(ax, az, dir, curve, n, rBase);
    }

    /**
     * 岛链对地表的抬升（e，<b>≥0</b>，加性）。
     *
     * <p>★ 抬升目标不是"固定高度"，而是<b>当地水深 + 出水高度</b>：
     * 参考用 {@code max(e, −200 m + add)} 把小岛强行顶到海面附近；
     * 我们改成加性但<b>按水深补足</b>——{@code target = max(0, −ePre) + peak}，
     * 于是深海里的岛是把海底抬 4 km 出水面（缓坡锥体，不是针），
     * 陆架上的岛只抬几百米。既保证"看得见"，又不产生参考 max() 的圆柱壁。</p>
     *
     * @param ePre  该点"不含岛链"的高度（e）——用于算水深
     */
    static double add(long seed, double wx, double wz, double ePre) {
        // ⚠ 这里<b>不能</b>再用"壳值低于 X 就跳过"来省算力：那是硬开关，
        //   实测在 crust=0.20 附近一格内 4.4 km 的岛凭空出现/消失（跳 93 格）。
        //   非要做性能门控，也必须逐级渐隐；目前靠包围圆拒绝已经够快。
        int cx0 = (int) Math.floor(wx / CHAIN_SPACING);
        int cz0 = (int) Math.floor(wz / CHAIN_SPACING);
        double best = 0.0;
        for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                Chain c = chainAt(seed, cx0 + dx, cz0 + dz);
                if (c == null) {
                    continue;
                }
                // 包围圆快速拒绝：链长 ≤ n·(2·R_MAX + STEP_EXTRA)
                double reach = c.n() * (2.0 * R_MAX + 22.0) + R_MAX;
                double ddx = wx - c.ax(), ddz = wz - c.az();
                if (ddx * ddx + ddz * ddz > reach * reach) {
                    continue;
                }
                double v = walk(seed, cx0 + dx, cz0 + dz, c, wx, wz, ePre);
                if (v > best) {
                    best = v;
                }
            }
        }
        return best;
    }

    /** 沿链逐颗步进，返回该点上最大的单岛抬升（e）。 */
    private static double walk(long seed, int cx, int cz, Chain c, double wx, double wz,
                               double ePre) {
        double depthE = Math.max(0.0, -ePre);
        // 水深越大，岛越宽（盾形火山）：否则 4.5 km 深的海底上顶出一个小锥子，
        // 坡度 ~0.8 格/格 ⇒ 山影把整座岛打成一道白色亮弧（实测首版就是这样）。
        double widen = 1.0 + 1.1 * Math.min(1.0, depthE / 0.40);
        double px = c.ax(), pz = c.az();
        double a = c.dir();
        double best = 0.0;
        for (int k = 0; k < c.n(); k++) {
            long hk = SandPlateField.hash(seed, cx * 31 + k, cz * 17 - k, SALT_ISL + k);
            // 参考用 rnd()*rnd()（强烈偏小）；我方改用 sqrt(u1)
            // ——首版照抄偏小分布，全图都是 2~3px 的点，"群岛"根本读不出来。
            double u1 = SandPlateField.unit(hk);
            double r = (R_MIN + Math.sqrt(u1) * (R_MAX - R_MIN)) * c.rBase();
            r = Math.max(R_MIN * 0.75, Math.min(R_MAX, r));
            double rEff = r * widen;
            double d = Math.hypot(wx - px, wz - pz);
            if (d < rEff * 1.9) {
                // 形状噪声的波长必须<b>随岛径缩放</b>：固定 18wu 时，r=180wu 的大岛
                // 边缘被切成 18wu 的碎花，而幅度是 4 km 级 ⇒ 相邻格跳 47 格。
                // 按 0.45·rEff 取波长，边缘粗糙度和岛尺寸成比例。
                double dn = d / rEff + 0.22 * SandTerrain.fbm(wx / (0.70 * rEff),
                        wz / (0.45 * rEff), SALT_ISL + 977 * (k + 1), 3);
                if (dn < 1.0) {
                    // 出水高度：小岛 ~150 m，大岛 ~550 m；再叠加当地水深
                    double peak = (PEAK_MIN + SandPlateField.unit(hk >>> 30)
                            * (PEAK_MAX - PEAK_MIN)) / SandTerrain.METERS_PER_E;
                    double add = (depthE + peak) * (1.0 - dn * dn);
                    if (add > best) {
                        best = add;
                    }
                }
            }
            // 步进（参考：st = 2r + 0.5..1.9°，方向加 curve + ±0.2）
            double st = 2.0 * r + 14.0 + SandPlateField.unit(hk >>> 58) * 18.0;
            a += c.curve() + (SandPlateField.unit(hk >>> 60) - 0.5) * 0.4;
            px += Math.cos(a) * st;
            pz += Math.sin(a) * st;
        }
        return best;
    }
}