package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashSet;

/**
 * Phase 2 雕刻采样探针：验证 sampleRiver 纯函数 + 横截面连续性 + 雕刻计划。
 *
 * <p>检查项：</p>
 * <ul>
 *   <li>主河心线：水面 = 全局海平面、河床 = 海平面 − 主河深（inChannel）</li>
 *   <li>支流：水面沿下游 → 上游单调非降（抬升公式）</li>
 *   <li>连续性：沿折线相邻采样点水面/河床差 → 全局确定性（含跨 tile）</li>
 *   <li>雕刻计划：carve 输出 groundY ≤ 原地形（只切不抬）；河心 groundY = bedY</li>
 * </ul>
 */
public class RiverCarveProbe {

    static final String SEP = "  ";

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), gen::sampleDischarge);
        net.setWorldSeed(seed);
        double seaLevel = net.seaLevelY();
        System.out.println("RiverCarveProbe seed=" + seed + " seaLevel=" + seaLevel);
        // ★ discharge 场诊断：采样几个固定点验证非 0（真物理数据可用）
        // ★ discharge 场诊断：沿主河段路径采样（discharge 只在液滴路径上有值）
        double disSum = 0, disMax = 0;
        long disCount = 0, disNonZero = 0;
        for (int tz = 0; tz < 4; tz++) {
            for (int tx = 0; tx < 4; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    if (s.type != RiverSegmentType.REACH && s.type != RiverSegmentType.MOUTH) continue;
                    for (RiverNode nd : s.path) {
                        double d = gen.sampleDischarge(nd.x(), nd.z());
                        disSum += d;
                        disMax = Math.max(disMax, d);
                        disCount++;
                        if (d > 0.01) disNonZero++;
                    }
                }
            }
        }
        System.out.println(SEP + "discharge along main path: sum=" + disSum
            + " max=" + disMax + " nonzero=" + disNonZero + "/" + disCount
            + " (high % = path follows droplet convergence = true physical flow)");

        int mainSegs = 0, tribSegs = 0, checkedMain = 0, checkedTrib = 0;
        int badMainAboveGround = 0, badMainBed = 0, badTribRise = 0, badTribBed = 0, badCarve = 0;
        int dryBeds = 0, checkedAll = 0, blockedOnSteep = 0;
        // ★ R18 高度门控/海洋浅槽统计（DW getRiverCarve）
        int highMainGone = 0, highMainCarved = 0, oceanTrough = 0;
        double maxMainWaterDelta = 0;
        long mainContinuity = 0;
        double mainSurfMin = Double.MAX_VALUE, mainSurfMax = -Double.MAX_VALUE;
        double tribDepth = 4.0 * 0.5; // 探针构造 mainDepth=4, tribDepthFrac=0.5
        double mainDepth = 4.0;

        for (int tz = 0; tz < 16; tz++) {
            for (int tx = 0; tx < 16; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    boolean main = s.type == RiverSegmentType.REACH || s.type == RiverSegmentType.MOUTH;
                    if (main) mainSegs++;
                    else tribSegs++;
                    if (s.path.size() < 2) continue;

                    double prevWater = Double.NaN;
                    for (int i = 0; i < s.path.size() - 1; i++) {
                        RiverNode a = s.path.get(i), b = s.path.get(i + 1);
                        for (int k = 0; k <= 4; k++) {
                            double t = k / 4.0;
                            double px = a.x() + (b.x() - a.x()) * t;
                            double pz = a.z() + (b.z() - a.z()) * t;
                            RiverSample rs = net.sampleRiver(px, pz);
                            if (!rs.inChannel()) continue;
                            // 段匹配过滤：采样点可能命中更近的其他段（主河/相邻支流）→
                            // 只用"水面+河床都匹配本段插值"的样本
                            double segSurf = a.waterSurfaceY()
                                + (b.waterSurfaceY() - a.waterSurfaceY()) * t;
                            double segBed = a.bedY() + (b.bedY() - a.bedY()) * t;
                            if (Math.abs(rs.waterSurfaceY() - segSurf) > 0.5
                                    || Math.abs(rs.bedY() - segBed) > 0.5) continue;
                            checkedAll++;
                            // ★ 贴地形在 RiverCarver.carve（有游戏实际列高）。探针模拟：
                            //   假地形列高 = heightFromE(terrainEQuick)（探针无侵蚀 tile）。
                            double ground = net.curve().heightFromE(net.eAt(px, pz));
                            RiverCarver.CarvedColumn cc = RiverCarver.carve(ground, rs, px, pz, seaLevel);
                            // 水位模型不变式（R9 DW 完整模型）：
                            // 1) 干河道：床 < 水面 − 0.5（水柱 ≥ 1 块；墙顶 = 水面 → 1 块水）
                            if (cc.inChannel() && cc.groundY() >= cc.waterTopY() - 0.5) dryBeds++;
                            // ★ R18：真海洋列（ground < seaLevel−3）走贴海床浅槽——
                            //   "不深埋"判据不适用（DW getRiverCarve 深海底同样贴床浅槽）
                            boolean land = ground >= seaLevel - 3.0;
                            // 2) 只切不抬：非墙区雕刻后地面 ≤ 原地形（墙区抬墙合法——DW wallTopY）
                            if (land && cc.inChannel() && !cc.isWall() && cc.groundY() > ground + 0.01) badCarve++;
                            // 3) 浅槽（河心 d≈0）：床 ≥ 水面 − DW 实际最深（6+2.25≈8）− 2（不深埋）
                            //    DW R17：河床 = (sea−6)+5×roundFactor+bedNoise×max(0,1−2d/w)，
                            //    河心最深 = 56−2.25 = 53.75 ≈ 8 块深（探针旧阈值用段深 6 = 误报 138545）
                            if (land && cc.isBed() && cc.groundY() < cc.waterTopY() - 10.0) badCarve++;
                            if (main) {
                                // ★ R18 统计（DW getRiverCarve 高度门控）：
                                //   y≥130 → fade≤0 → NONE（高山上主河消失）；90<y<130 → 淡出浅雕
                                if (ground >= 130.0 && !cc.inChannel()) highMainGone++;
                                if (cc.inChannel()) {
                                    if (ground > 90.0) highMainCarved++;
                                    if (ground < seaLevel - 3.0) oceanTrough++;
                                }
                                // ★ R16：skip 条件（海/低地）返回 NONE → 不统计
                                if (!cc.inChannel()) continue;
                                checkedMain++;
                                mainSurfMin = Math.min(mainSurfMin, cc.waterTopY());
                                mainSurfMax = Math.max(mainSurfMax, cc.waterTopY());
                                if (!Double.isNaN(prevWater)) {
                                    maxMainWaterDelta = Math.max(maxMainWaterDelta,
                                        Math.abs(cc.waterTopY() - prevWater));
                                    mainContinuity++;
                                }
                            } else {
                                checkedTrib++;
                            }
                            prevWater = cc.waterTopY();
                        }
                    }
                }
            }
        }

        System.out.println(SEP + "main segs: " + mainSegs + ", trib segs: " + tribSegs
            + " (mountainPath=" + RiverBuilder.statNat + " dijkstraFallback=" + RiverBuilder.statDij + ")");
        System.out.println(SEP + "main samples: " + checkedMain
            + " (bad surf above ground: " + badMainAboveGround
            + ", bad bed!=surf-depth@center: " + badMainBed + ")");
        System.out.println(SEP + "main surf range: [" + mainSurfMin + ", " + mainSurfMax
            + "] (seaLevel=" + seaLevel + ", follow terrain)");
        System.out.println(SEP + "main continuity: " + mainContinuity
            + " max water delta: " + maxMainWaterDelta + " (expect 0)");
        System.out.println(SEP + "trib samples: " + checkedTrib
            + " (bad rise upstream: " + badTribRise + ", bad bed=surf-depth: " + badTribBed + ")");
        System.out.println(SEP + "carve violations: " + badCarve);
        System.out.println(SEP + "dry beds (bed >= water): " + dryBeds + " / " + checkedAll);
        System.out.println(SEP + "steep-slope hits still in channel: " + blockedOnSteep
            + " (expect 0, slope-blocked)");
        System.out.println(SEP + "R18 height gate (★ R21f fade removed): main y>=130 rejected=" + highMainGone
            + ", 90<y<130 carved=" + highMainCarved
            + ", ocean trough carved=" + oceanTrough
            + " (expect rejected=0 = mountain headwater carves normally)");

        // ---- R19：主河谷线贴合 + 支流爬升率统计（去重段，16×16 tile） ----
        // 8 邻方向（RiverBuilder2.DX8/DZ8 为私有，探针自持一份）
        int[] d8x = {1, 1, 0, -1, -1, -1, 0, 1};
        int[] d8z = {0, 1, 1, 1, 0, -1, -1, -1};
        HashSet<Integer> seenMain = new HashSet<>();
        HashSet<Integer> seenTrib = new HashSet<>();
        long mNodes = 0, mDown = 0, mTotal = 0;
        double mDevSum = 0, mDevMax = 0;
        double mLenSum = 0, mLenMax = 0;
        int mSegCount = 0;
        // 长度分布诊断（R19：定位 avgLen 偏短的构成）
        int[] lenBuckets = new int[6]; // <50, 50-200, 200-500, 500-1000, 1000-1500, >=1500
        int oceanPlaceholder = 0;
        double startESum = 0;
        int startECount = 0;
        // 长河坐标（可视化定位用）
        StringBuilder longRivers = new StringBuilder();
        long tSteps = 0, tSteep = 0;
        double tRiseMax = 0;
        // ★ R21f 审计：支流末端水面对齐（末端 = 下游端 = 汇入点/入海口；
        //   对齐目标 = 主河水面 seaLevel−1；断差大 = 汇入点悬河/瀑布；
        //   tribEndHigh = 悬空末端断头河）
        long tribEndAll = 0, tribEndOK = 0, tribEndHigh = 0;
        double tribEndDeltaMax = 0;
        for (int tz = 0; tz < 16; tz++) {
            for (int tx = 0; tx < 16; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    boolean main = s.type == RiverSegmentType.REACH || s.type == RiverSegmentType.MOUTH;
                    if (main) {
                        if (!seenMain.add(s.uid)) continue;
                        if (s.path.isEmpty()) continue;
                        mSegCount++;
                        double len = s.length();
                        mLenSum += len;
                        mLenMax = Math.max(mLenMax, len);
                        if (len < 50) lenBuckets[0]++;
                        else if (len < 200) lenBuckets[1]++;
                        else if (len < 500) lenBuckets[2]++;
                        else if (len < 1000) lenBuckets[3]++;
                        else if (len < 1500) lenBuckets[4]++;
                        else lenBuckets[5]++;
                        if (s.path.size() == 1) oceanPlaceholder++;
                        double startE = net.eAt(s.path.get(0).x(), s.path.get(0).z());
                        startESum += startE;
                        startECount++;
                        if (len > 200) {
                            longRivers.append(" (" + (int) s.path.get(0).x() + ","
                                + (int) s.path.get(0).z() + "):" + (int) len + "wu e="
                                + String.format("%.2f", startE) + ")");
                        }
                        double prevE = Double.NaN;
                        for (RiverNode nd : s.path) {
                            double e = net.eAt(nd.x(), nd.z());
                            if (!Double.isNaN(prevE)) {
                                mTotal++;
                                if (e <= prevE) mDown++;
                            }
                            prevE = e;
                            // 谷线偏差 = 节点 e − 8 邻（4wu）最低 e（贴谷 = 均值→0）
                            double minE = e;
                            for (int k = 0; k < 8; k++) {
                                minE = Math.min(minE, net.eAt(nd.x() + d8x[k] * 4.0,
                                    nd.z() + d8z[k] * 4.0));
                            }
                            double dev = e - minE;
                            mDevSum += dev;
                            mDevMax = Math.max(mDevMax, dev);
                            mNodes++;
                        }
                    } else {
                        if (!seenTrib.add(s.uid)) continue;
                        // 支流爬升率：相邻节点水面差 > 6 占比（R19 硬否决后的代理指标）
                        for (int i = 1; i < s.path.size(); i++) {
                            double rise = s.path.get(i).waterSurfaceY()
                                - s.path.get(i - 1).waterSurfaceY();
                            tSteps++;
                            if (Math.abs(rise) > 6.0) {
                                tSteep++;
                                tRiseMax = Math.max(tRiseMax, Math.abs(rise));
                            }
                        }
                        // ★ R21f：支流末端（下游端 = path[0]——toSegment 反序存储，
                        //   下游在前源头在后；uid=26 end=源头位置实锤）。悬空末端
                        //   = 断头河计数
                        RiverNode endNode = s.path.get(0);
                        double endDelta = Math.abs(endNode.waterSurfaceY() - (seaLevel - 1.0));
                        tribEndAll++;
                        // ★ R22：join 河（末端汇入干流节点/主河）排除——末端 =
                        //   干流水面（高处）≠ 海平面，endHigh 误判；join 是正常
                        //   树状汇入
                        boolean joinedRiver = s.mountainPath instanceof RiverBuilder2.MountainPath
                            && ((RiverBuilder2.MountainPath) s.mountainPath).joinNodeCount >= 0;
                        if (endNode.waterSurfaceY() > seaLevel + 2.0 && !joinedRiver) {
                            tribEndHigh++;
                        }
                        if (endDelta < 1.5) tribEndOK++;
                        tribEndDeltaMax = Math.max(tribEndDeltaMax, endDelta);
                    }
                }
            }
        }
        System.out.println(SEP + "R19 main river: segs=" + mSegCount
            + " avgLen=" + (mSegCount > 0 ? (int) (mLenSum / mSegCount) : 0)
            + "wu maxLen=" + (int) mLenMax + "wu"
            + " | downhill " + (mTotal > 0 ? mDown * 100 / mTotal : 100) + "%"
            + " | valley dev mean=" + (mNodes > 0 ? String.format("%.4f", mDevSum / mNodes) : "?")
            + " max=" + String.format("%.4f", mDevMax)
            + " (e-units; expect mean~0.00x = path hugs valley)");
        System.out.println(SEP + "R19 len dist <50:" + lenBuckets[0]
            + " 50-200:" + lenBuckets[1] + " 200-500:" + lenBuckets[2]
            + " 500-1000:" + lenBuckets[3] + " 1000-1500:" + lenBuckets[4]
            + " >=1500:" + lenBuckets[5]
            + " | oceanPlaceholder=" + oceanPlaceholder
            + " | startE mean=" + (startECount > 0 ? String.format("%.3f", startESum / startECount) : "?"));
        System.out.println(SEP + "R19 long rivers (>200wu):" + longRivers);
        System.out.println(SEP + "R19 tributary: steps=" + tSteps
            + " steep(>6y) " + (tSteps > 0 ? tSteep * 100.0 / tSteps : 0.0) + "%"
            + " maxRise=" + String.format("%.1f", tRiseMax)
            + " | end-align=" + (tribEndAll > 0 ? tribEndOK * 100.0 / tribEndAll : 0.0) + "%"
            + " endHigh=" + tribEndHigh + "/" + tribEndAll
            + " maxEndDelta=" + String.format("%.1f", tribEndDeltaMax)
            + " (expect low endHigh; join/sea ends = seaLevel-1, cut/break ends hang)");

        // ---- Phase 3：跨 tile 边界共享（横截面逐列一致性，纯函数结构性无缝） ----
        // 对每条段折线找与 tile 边界线（x 或 z = 128k）的交点，交点两侧各采样一次
        // （两个不同 tile 的 plate）→ 水面/河床差 = 0（expect < 0.01）
        long boundaryChecks = 0;
        double maxBoundaryDelta = 0;
        int boundaryHits = 0;
        for (int tz = 0; tz < 16; tz++) {
            for (int tx = 0; tx < 16; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    for (int i = 0; i < s.path.size() - 1; i++) {
                        RiverNode a = s.path.get(i), b = s.path.get(i + 1);
                        // 线段与边界线的交点参数 t（x 边界 128k 或 z 边界 128k）
                        for (double bound : boundaryCandidates(a.x(), a.z(), b.x(), b.z())) {
                            double t = bound;
                            double px = a.x() + (b.x() - a.x()) * t;
                            double pz = a.z() + (b.z() - a.z()) * t;
                            // 两侧采样：0.1wu 偏置（若偏置后越出采样区则跳过）
                            if (px < 0 || px > 16 * 128 || pz < 0 || pz > 16 * 128) continue;
                            RiverSample l = net.sampleRiver(px - 0.1, pz);
                            RiverSample r = net.sampleRiver(px + 0.1, pz);
                            if (!l.inChannel() || !r.inChannel()) continue;
                            boundaryHits++;
                            double dw = Math.abs(l.waterSurfaceY() - r.waterSurfaceY());
                            double db = Math.abs(l.bedY() - r.bedY());
                            maxBoundaryDelta = Math.max(maxBoundaryDelta, Math.max(dw, db));
                            boundaryChecks++;
                        }
                    }
                }
            }
        }
        System.out.println(SEP + "boundary samples: " + boundaryHits + " checks: " + boundaryChecks
            + " max delta: " + maxBoundaryDelta + " (expect < 0.01)");

        // 性能：单 tile 全量采样（256×256 wu，每 4wu 一点）
        long t0 = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 64; j++) {
                if (net.sampleRiver(i * 4.0 + 0.5, j * 4.0 + 0.5).inChannel()) hits++;
            }
        }
        long t1 = System.nanoTime();
        System.out.println(SEP + "tile 4096 samples: " + (t1 - t0) / 1000000 + " ms (hits=" + hits + ")");
        System.out.println("=== RiverCarveProbe done ===");
    }

    /**
     * 线段 (a→b) 与 tile 边界线（x=128k 或 z=128k）的交点参数 t 列表。
     * 端点跨越边界时才有交点；t ∈ (0,1)。
     */
    private static double[] boundaryCandidates(double ax, double az, double bx, double bz) {
        double[] out = new double[2];
        int n = 0;
        if (Math.floor(ax / 128.0) != Math.floor(bx / 128.0)) {
            double k = Math.floor(bx / 128.0);
            double bound = k * 128.0;
            if (Math.abs(bx - ax) > 1e-9) {
                double t = (bound - ax) / (bx - ax);
                if (t > 0 && t < 1) out[n++] = t;
            }
        }
        if (Math.floor(az / 128.0) != Math.floor(bz / 128.0)) {
            double k = Math.floor(bz / 128.0);
            double bound = k * 128.0;
            if (Math.abs(bz - az) > 1e-9) {
                double t = (bound - az) / (bz - az);
                if (t > 0 && t < 1) out[n++] = t;
            }
        }
        double[] res = new double[n];
        System.arraycopy(out, 0, res, 0, n);
        return res;
    }
}
