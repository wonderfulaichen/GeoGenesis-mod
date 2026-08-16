package com.geogenesis.worldgen.river;

/**
 * 河谷雕刻计划（DW 完整模型，2026-08-15 R9 重写）。
 *
 * <p>严格对齐 Dynamic Waters {@code HydrologyManager.calculateRiverCarving}（624-863 行）：</p>
 * <ul>
 *   <li><b>水面 = 节点缓抬水位（terrace 包络），不贴列高</b>——贴地 = 水膜 = "不像河"
 *       根因。地形与水面之差由雕刻吸收：</li>
 *   <li><b>地形高于水面 → 挖谷壁</b>（DW valleyDepth/valleyFloor/wallNoise）——河在
 *       谷中，两岸有壁</li>
 *   <li><b>地形低于水面 → 抬墙</b>（DW wallTopY/外墙 blend）——低洼处水面成明确水面，
 *       岸顶草皮</li>
 *   <li>河心（d&lt;w）：深 1-3 块（DW depth = 1 + 2×(1−d/w)），圆底（roundFactor）</li>
 *   <li>岸坡（w≤d&lt;w+bankW）：bankW = max(5, 8×(w/baseW))，smoothstep 渐变</li>
 * </ul>
 *
 * <p>纯函数 → 任意 chunk/线程同结果。输出 CarvedColumn 供落块（挖/填/水/密封）。</p>
 */
public final class RiverCarver {

    /** 列雕刻结果（DW RiverCarving 语义子集） */
    public record CarvedColumn(
            double groundY,      // 雕刻后地面 Y（块）
            double waterTopY,    // 水面 Y（块；无河道水 = -∞）
            boolean inChannel,   // 是否在河道
            boolean isBed,       // 是否河心（河床区）
            boolean isWall,      // 是否墙区（地形低于水面，需抬填）
            boolean isBank,      // 是否岸坡/谷壁区（地形高于水面，需挖切）
            double wallTopY) {   // 墙顶 Y（墙区地表；非墙 = -∞）
        public static final CarvedColumn NONE =
            new CarvedColumn(0, Double.NEGATIVE_INFINITY, false, false, false, false, Double.NEGATIVE_INFINITY);
    }

    private RiverCarver() {}

    /**
     * 纯函数：游戏实际列高（含侵蚀 delta）+ 河流采样 → 列雕刻计划。
     *
     * @param origHeight 实际列高（cell.height）
     * @param rs         河流采样（含节点缓抬水面、段级宽度）
     */
    /**
     * ★ 2026-08-15 R17 逐字对齐 DW HydrologyManager.calculateRiverCarving
     * （字节码 2543-3133 全部核对；R15/R16 公式错误——riverHeight 是岸坡宽度
     * 不是深度，s1/s2 分档式错，现全部修正）。
     *
     * <p>★ 2026-08-16 R18 主河高度门控 + 海洋浅槽（DW getRiverCarve 逐字，
     * 字节码 3934-3951 核对）：</p>
     * <ul>
     *   <li><b>高度门控（仅主河 REACH/MOUTH）</b>：主河几何是纯随机折线（不读
     *   地形），DW 靠雕刻端隐藏高山段——{@code fade = y≤90 ? 1 : max(0,1−(y−90)/40)}，
     *   y≥130 完全消失。我们缺此门控 → 山顶上照样挖 6 块深槽+16 块岸坡 =
     *   "河流位置完全不自然"根因。fade 应用到河心深度与岸坡 riverHeight
     *   （DW carve×fade 语义）。</li>
     *   <li><b>海洋浅槽（主河专属）</b>：海洋列（origHeight &lt; seaLevelY−3）上
     *   主河段雕 1-2 块贴海床浅槽（河心 2 块/岸边 1 块），水下河谷连续延伸入海，
     *   河口不"凭空消失"（DW getRiverCarve：海里也雕浅槽）。支流不入海。</li>
     * </ul>
     *
     * <p>DW 公式（逐字）：</p>
     * <pre>
     * jitter      = sin(x×0.04+z×0.02)×0.8 + sin(−x×0.02+z×0.04)×0.6   (getRiverJitter)
     * bedNoise    = RIVER_BED_NOISE(x×0.05, z×0.05)×1.5                (getRiverBedNoise)
     * dJ          = max(0, d + jitter×w/10)
     * hAboveWater = terrain − (seaLevel−1)
     * s1          = clamp(1 − (hAboveWater−3)/10)
     * s2          = clamp(1 − (hAboveWater−16)/8)
     * riverHeight = max(0, (16×(1−s1)+4×s1+bedNoise×3) × (1−min(1,1.5×s2)))
     *   ─ 低地(hAboveWater<3): s1=s2=1 → riverHeight=0（无岸坡，河心浅槽）
     *   ─ 高地(hAboveWater≥24): s1=s2=0 → riverHeight=16+bedNoise×3（岸坡16块）
     * </pre>
     * <p>判定：</p>
     * <ul>
     *   <li>skip：terrain &lt; seaLevel−4 → NONE（海上河隐形，2562 之前 ifne 1258）；
     *   R18 起仅支流受此限制，主河走海洋浅槽</li>
     *   <li>河心（dJ &lt; w+riverHeight 且 dJ &lt; w）：isMainRiverWater=true</li>
     *   <li>岸坡（dJ &lt; w+riverHeight 且 dJ ≥ w）：smoothstep 坡到原地面</li>
     *   <li>远处（dJ ≥ w+riverHeight）：不雕刻</li>
     * </ul>
     *
     * @param seaLevelY 全局海平面 Y（块；真海洋判据 origHeight &lt; seaLevelY−3）
     */
    public static CarvedColumn carve(double origHeight, RiverSample rs, double wx, double wz,
                                     double seaLevelY) {
        if (!rs.inChannel()) return CarvedColumn.NONE;
        double d = rs.distance();
        double w = Math.max(rs.width(), 1e-9);
        double waterY = rs.waterSurfaceY();
        double x = wx; // 采样点世界坐标（DW 噪声基准）
        double z = wz;

        // ★ R21f 审计：删除主河高度门控 fade（R18 为"纯随机折线主河"设计——
        //   随机几何穿山，靠雕刻端隐藏高山段）。R19 主河 = 地形感知谷线流线，
        //   天然在谷中 → fade 只制造"源头凭空消失/半透明"怪相（源头常在山顶）。
        //   主河在山顶谷线雕刻深谷 = 高地干流源头，自然。
        boolean main = rs.type() == RiverSegmentType.REACH
                    || rs.type() == RiverSegmentType.MOUTH;
        double fade = 1.0;

        // DW skip：terrain < seaLevel−4 → 不雕刻（var10，ifne 1258）
        // ★ R18 分流：支流维持 NONE；主河在真海洋列（origHeight < seaLevelY−3）走浅槽
        // ★ 修复 #3（2026-08-16）：主河陆地低洼（原地形低于水面 3+ 块）从 NONE
        //   改为「保持原地形 + 标记河道」——深谷中主河整段消失（MC 断河实锤）的
        //   最后一道防线。inChannel=true + 水面 = 河道水面 → 水柱自然灌到水面成
        //   深水河段，地面不额外下切（不触发 RiverCarveProbe「不深埋」误报）。
        if (origHeight < waterY - 3.0) {
            if (!main) return CarvedColumn.NONE;
            if (origHeight < seaLevelY - 3.0) return carveOceanTrough(origHeight, d, w, jitter(x, z), seaLevelY);
            return new CarvedColumn(origHeight, waterY, true, true, false, false,
                Double.NEGATIVE_INFINITY);
        }

        // DW 河床噪声 + 抖动（getRiverBedNoise / getRiverJitter）
        double bedNoise = Math.sin(x * 0.05) * Math.cos(z * 0.05) * 1.5;
        double jitter = jitter(x, z);
        double dJ = Math.max(0.0, d + jitter * w / 10.0);

        // DW riverHeight（s1/s2 分档）；★ R18 主河岸坡 ×fade（淡出）
        double hAboveWater = origHeight - waterY; // DW: terrain − (seaLevel−1)
        double s1 = Math.min(1.0, Math.max(0.0, 1.0 - (hAboveWater - 3.0) / 10.0));
        double s2 = Math.min(1.0, Math.max(0.0, 1.0 - (hAboveWater - 16.0) / 8.0));
        double riverHeight = Math.max(0.0,
            ((16.0 * (1.0 - s1) + 4.0 * s1 + bedNoise * 3.0) * (1.0 - Math.min(1.0, 1.5 * s2))))
            * fade;

        boolean isBed = d <= w * 0.5;
        double target;

        if (dJ < w + riverHeight && dJ < w) {
            // ---- 河心（DW 2934-3013：isMainRiverWater=true，河床 = 水面−6+5×roundFactor）----
            double normDist = w > 0 ? Math.min(1.0, dJ / w) : 1.0;
            double roundFactor = 1.0 - Math.sqrt(Math.max(0.0, 1.0 - normDist * normDist));
            // DW 2993-3013 逐字：var69 = (seaLevel−6) + 5×var65(roundFactor)
            //   + var15(bedNoise)×max(0,1−2×var63(dJ/w))
            //   → 河心(d=0): 56+noise（深 4-8 块）；岸(d=w): 61（深 1 块）
            // ★ R18：深度 ×fade（DW carve×fade 语义——fade→0 时河床趋近水面=不挖）
            double depthBelow = (6.0 - 5.0 * roundFactor) * fade;
            double bedY = waterY - depthBelow;
            double bedCorr = Math.max(0.0, 1.0 - 2.0 * normDist);
            bedY += bedNoise * bedCorr * fade;
            target = Math.min(bedY, origHeight); // DW 3014-3017: min(bedY, terrainHeight)
            if (target >= waterY - 0.5) target = waterY - 1.0;
            return new CarvedColumn(target, waterY, true, isBed, false, false, Double.NEGATIVE_INFINITY);
        } else if (dJ < w + riverHeight && dJ >= w && riverHeight > 0) {
            // ---- 岸坡（DW 3079-3117：smoothstep 从水面升到原地面）----
            double t = Math.min(1.0, (dJ - w) / riverHeight);
            double smooth = t * t * (3.0 - 2.0 * t); // DW smoothstep
            target = waterY + hAboveWater * smooth;   // DW 3108-3117: floor(waterLevel + hAboveWater×smooth)
            // ★ R17b：岸坡可高出水面（smoothstep 升到原地面）——不得用河心防御
            //   （target ≥ waterY−0.5 → waterY−1 会把岸坡压平 = 高地无岸坡实锤）
            target = Math.min(target, origHeight);
            return new CarvedColumn(target, waterY, true, false, false, true, Double.NEGATIVE_INFINITY);
        }
        // 远处（dJ ≥ w+riverHeight）：不雕刻
        return CarvedColumn.NONE;
    }

    /** 海洋浅槽（DW getRiverCarve：海里也雕浅槽引导；河心 2 块 / 岸边 1 块贴海床） */
    private static CarvedColumn carveOceanTrough(double origHeight, double d, double w,
                                                 double jitter, double seaLevelY) {
        double dJ = Math.max(0.0, d + jitter * w / 10.0);
        if (dJ >= w) return CarvedColumn.NONE; // 河道范围外（浅槽仅覆盖河宽）
        boolean isBed = dJ <= w * 0.5;
        double target = origHeight - (isBed ? 2.0 : 1.0); // 贴海床挖 1-2 块
        return new CarvedColumn(target, seaLevelY, true, isBed, false, false,
            Double.NEGATIVE_INFINITY);
    }

    private static double jitter(double x, double z) {
        return Math.sin(x * 0.04 + z * 0.02) * 0.8
             + Math.sin(-x * 0.02 + z * 0.04) * 0.6;
    }
}
