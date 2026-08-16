package com.geogenesis.worldgen.river;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 源头驱动追踪河网构建器（汇水分析驱动 · 阶段 A）。
 *
 * <p>范式与废弃的 {@code RiverBuilder2}（几何折线 + 评分寻路）相反：
 * <b>每一条河都从地形算出来</b>——源头按 e 降序选取（分水岭高起点），
 * 沿 D8 最陡下坡追踪（{@link FlowField}），途中踩到更低河道即汇入成树，
 * 入海（e≤0）/ 洼地（dir=-1，阶段 B 填湖）终止。长度 = 源头到终点的
 * 地形距离，与到海距离自然成比例。</p>
 *
 * <p><b>确定性铁律</b>（结构性无缝根）：一切生成只依赖世界坐标 +
 * {@code worldSeed} + e 场，绝不依赖"已缓存的邻域时序"。为此：</p>
 * <ul>
 *   <li><b>主河层</b>：每 REGION（640wu）恰 1 条——源点 = REGION 内
 *       16wu 栅格 e 最高的 <b>5 个候选各追踪一次、取最长</b>（分水岭制高点
 *       且远离海洋 → 主河长度 = 分水岭到海的地形距离）。主河间不互相汇入
 *       （平行流域），避免跨 REGION 缓存时序依赖。</li>
 *   <li><b>支流层</b>：每 REGION 源头池（32wu 栅格 e&gt;0.1 降序 + 间距
 *       ≥64wu + 上限 40 条）。支流只汇入 <b>本 REGION 内已生成支流</b>
 *       （生成顺序 = e 降序，固定）+ <b>3×3 REGION 主河池</b>（确定性缓存
 *       对象）→ 任何时序下汇入判定一致。★ GRID(256) → REGION(640)：
 *       GRID 内源头互相够不到（间距 64wu），join 率仅 13% 实锤；REGION 级
 *       同区源头追踪距离 1040wu 内互相汇入机会大增。</li>
 * </ul>
 */
final class FlowRiverBuilder {

    /** REGION 边长（wu，主河/支流单元） */
    static final int REGION_SIZE = 640;
    /** 追踪步长（wu，= FlowField 网格间距） */
    static final int STEP = 4;
    /** 汇入判定半径（wu；★ 2026-08-17 40→8：40wu（10 格）的提前瞬移跳 = 隐式
     *  吸引——支流还在正常下降，40wu 外满足水面条件的主河节点（哪怕在坡上）就
     *  被 `t.add(hit)` 瞬移过去，中间路径凭空消失（用户"突然大转弯被扯向上连接
     *  主河"实锤）。8wu（2 格）= 真正走到主河道旁才连，物理相遇语义：主河节点
     *  间距 4wu、支流步进 4wu，汇入谷底时必经过节点 8wu 内。§10.5① 曾 18→40
     *  提 join 率（15%→44-53%），代价就是远距瞬移——现在以动线物理正确优先，
     *  join 率允许回落，由树状汇入替代空吸引。 */
    static final double JOIN_DIST = 8.0;
    /** 绕行多尺度（wu）：逐级扩大找下坡——近尺度捕捉微谷、远尺度捕捉海洋下降 */
    static final double[] SCALES = {4, 8, 16, 24, 40};
    /** 近海判定 e 阈值：低于此 e 河流已在入海走廊，允许远尺度大步向海
     *  （海滩平缓 → 40wu 内可能无更低，海在更远深水；用户实锤"差一点入海却断头"） */
    static final double SAIL_E = 0.06;
    /** 近海绕行尺度（wu）：逐级扩到 192wu 捕捉深水下降 */
    static final double[] SAIL_SCALES = {8, 16, 32, 64, 128, 192};
    /** 平坦漫流远尺度（wu）：非凹坑（8 邻存在 ≤ 自身+1e-4）时绕行失败再扩到 640wu
     *  ——冲积平原/高原面微齿无严格下坡，40wu 内可能无更低但远处有坡向海
     *  （98765 实测 flat 断头 e≈0.47 集中高原面，400wu 内仍无更低，640wu 抓边缘）；
     *  真凹坑（8 邻全明显更高）保持断头填湖，不穿盆地。 */
    static final double[] FAR_SCALES = {96, 160, 240, 320, 400, 640};
    /** 源头最低 e（海平面以上起点） */
    static final double SOURCE_MIN_E = 0.1;
    /** 源头池最小间距（wu；★ 64→48：源头稀 → 支流互相够不到，join 15% 实锤） */
    static final double SOURCE_SPACING = 48.0;
    /** 单 REGION 支流上限 */
    static final int TRIB_MAX = 60;
    /** 缓存上限（防 OOM） */
    private static final int CACHE_MAX = 2048;

    private final RiverNetwork net;
    /** REGION → 主河段列表（确定性缓存；每 REGION ≤3 条 = 分水岭最高 3 候选） */
    private final Map<Long, List<RiverSegment>> mainRegions = new ConcurrentHashMap<>();
    /** REGION → 支流段列表（确定性缓存） */
    private final Map<Long, List<RiverSegment>> regionTribs = new ConcurrentHashMap<>();

    FlowRiverBuilder(RiverNetwork net) {
        this.net = net;
    }

    /** 主河段表（RiverNetwork.mainSegmentAt 用，接口兼容旧 builder；每 REGION 取最长） */
    Map<Long, RiverSegment> mainSegments() {
        Map<Long, RiverSegment> flat = new ConcurrentHashMap<>();
        mainRegions.forEach((k, segs) -> {
            RiverSegment best = null;
            for (RiverSegment s : segs) {
                if (best == null || s.path.size() > best.path.size()) best = s;
            }
            if (best != null) flat.put(k, best);
        });
        return flat;
    }

    // ===== 对外：buildPlate（5×5 REGION 主河 + 3×3 REGION 支流；±1600wu 覆盖长主河） =====
    // ★ 修复 #1（2026-08-16）：主河 3×3 → 5×5。主河从 REGION 中心 D8 追踪可达
    //   1000+wu（avg 493-576wu），3×3（±640wu）收集让长段尾部流出 plate → 采样层
    //   NONE → MC 里河直接消失。5×5（±1280wu）覆盖全部主河段；支流短（汇入 3×3
    //   主河池），保持 3×3 防 plate 膨胀。

    RiverPlate buildPlate(int tileCX, int tileCZ) {
        Set<Integer> seen = new HashSet<>();
        List<RiverSegment> segs = new ArrayList<>();
        double wx = tileCX * RiverNetwork.BASIN_SIZE;
        double wz = tileCZ * RiverNetwork.BASIN_SIZE;
        int rx0 = floorDiv((int) Math.floor(wx), REGION_SIZE);
        int rz0 = floorDiv((int) Math.floor(wz), REGION_SIZE);
        for (int rz = rz0 - 1; rz <= rz0 + 1; rz++) {
            for (int rx = rx0 - 1; rx <= rx0 + 1; rx++) {
                for (RiverSegment s : regionTributaries(rx, rz)) {
                    if (seen.add(s.uid)) segs.add(s);
                }
            }
        }
        for (int rz = rz0 - 2; rz <= rz0 + 2; rz++) {
            for (int rx = rx0 - 2; rx <= rx0 + 2; rx++) {
                for (RiverSegment seg : regionMainRivers(rx, rz)) {
                    if (seen.add(seg.uid)) segs.add(seg);
                }
            }
        }
        return new RiverPlate(tileCX, tileCZ, List.copyOf(segs));
    }

    // ===== 主河层：每 REGION ≤3 条（分水岭最高 3 候选 → D8 到海） =====

    /** REGION → 主河段列表（缓存；海洋/低地 REGION = 空列表 = 无主河）。 */
    List<RiverSegment> regionMainRivers(int rx, int rz) {
        long key = pack(rx, rz);
        List<RiverSegment> cached = mainRegions.get(key);
        if (cached != null) return cached;
        List<RiverSegment> segs = generateRegionMains(rx, rz);
        List<RiverSegment> prev = mainRegions.putIfAbsent(key, segs);
        prune();
        return prev != null ? prev : segs;
    }

    /**
     * 主河生成（纯函数）：REGION 内 16wu 栅格 e 最高 <b>3 候选全保留</b>
     * （间距 ≥64wu 过滤）→ 各 D8 到海。★ 单候选实锤：20 条主河覆盖区域仅 1.7%，
     * 支流随机碰撞期望 ~60 条与实测 join 57 吻合——树状度瓶颈 = 主河密度。
     * 3 条平行流域主河（分水岭主排水线 + 次排水线）→ 覆盖 ×3。
     * ★ 长度门槛 ≥80wu（20 节点）：3 候选全保留引入短河（24/59 <100wu 实锤），
     * 短候选 = 海岸小山溪（长度不由地形比例决定，实为支流级）→ 丢弃。 */
    private List<RiverSegment> generateRegionMains(int rx, int rz) {
        List<double[]> tops = highestCells(rx * REGION_SIZE, rz * REGION_SIZE, REGION_SIZE, 16.0, 3);
        if (tops.isEmpty() || tops.get(0)[0] <= SOURCE_MIN_E) return List.of(); // 海洋/低地
        List<RiverSegment> out = new ArrayList<>();
        for (double[] c : tops) {
            Trace t = trace(c[1], c[2], null, null);
            if (t.pts < 20) continue; // <80wu 不成主河（短候选 = 海岸山溪 → 丢弃）
            // ★ 主河水面 = 源头→出口线性插值（PL-RGA `_applyRiverHeightSlopeDrop`
            //   语义：路径确定后水面平滑单调，不受追踪路径地形起伏影响——
            //   2026-08-16 用户"支流非单调下降"实锤）。surfN = min(出口, 源头)
            //   clamp 防异常。
            double surf0 = t.surf[0], surfN = Math.min(t.surf[t.pts - 1], surf0);
            List<RiverNode> path = new ArrayList<>(t.pts);
            double[] nw = new double[t.pts];
            for (int i = 0; i < t.pts; i++) {
                double f = t.pts > 1 ? (double) i / (t.pts - 1) : 1.0;
                // ★ 修复 #2（2026-08-16）：水面保底 = min(线性插值, 追踪水面)。
                //   线性水面在深谷中虚高（覆盖掉追踪的下潜）→ 雕刻
                //   `origHeight < waterY-3` → NONE（MC 主河消失实锤）。两个单调
                //   不升序列取 min 仍单调（无新非单调）；平地保留线性落差，谷中
                //   贴地形下潜（真河穿过深谷该有的样子）。
                double surf = Math.min(surf0 + (surfN - surf0) * f, t.surf[i]);
                path.add(new RiverNode(t.x[i], t.z[i], surf, surf - net.mainDepth()));
                nw[i] = net.mainWidth();
            }
            out.add(new RiverSegment(RiverSegmentType.REACH, rx, rz, path,
                -1, -1, -1, -1, surf0, surfN - net.mainDepth(), net.mainWidth(), nw, 1));
        }
        return List.copyOf(out);
    }

    // ===== 支流层：GRID 源头池 → D8 追踪 + 树状汇入 =====

    /** REGION → 支流段列表（缓存；确定性：源头 e 降序 + 间距约束）。 */
    List<RiverSegment> regionTributaries(int rx, int rz) {
        long key = pack(rx, rz);
        List<RiverSegment> cached = regionTribs.get(key);
        if (cached != null) return cached;
        List<RiverSegment> segs = generateRegionTribs(rx, rz);
        List<RiverSegment> prev = regionTribs.putIfAbsent(key, segs);
        prune();
        return prev != null ? prev : segs;
    }

    /** REGION 支流生成（纯函数）：源头池 → 各源头 D8 追踪 + 树状汇入。 */
    private List<RiverSegment> generateRegionTribs(int rx, int rz) {
        // 主河池：3×3 REGION（确定性缓存对象；支流追踪 1040wu 内可见目标）
        List<RiverSegment> mains = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                mains.addAll(regionMainRivers(rx + dx, rz + dz));
            }
        }
        Map<Long, List<RiverNode>> mainBuckets = indexSegments(mains);
        // 源头池：32wu 栅格 e>0.1，e 降序，间距 ≥64wu，上限 TRIB_MAX
        List<double[]> sources = selectSources(rx * REGION_SIZE, rz * REGION_SIZE, REGION_SIZE);
        Map<Long, List<RiverNode>> localBuckets = new ConcurrentHashMap<>();
        List<RiverSegment> out = new ArrayList<>();
        java.util.Random r = new java.util.Random(regionSeed(rx, rz));
        for (double[] s : sources) {
            if (out.size() >= TRIB_MAX) break;
            Trace t = trace(s[1], s[2], mainBuckets, localBuckets);
            if (t.pts < 3) continue;
            RiverSegment seg = toTribSegment(t, rx, rz, r);
            indexSegment(localBuckets, seg); // 本 REGION 已生成（汇入目标，顺序确定）
            out.add(seg);
        }
        return List.copyOf(out);
    }

    /** 段列表 → 32wu bucket 节点索引（汇入快速查询）。 */
    private static Map<Long, List<RiverNode>> indexSegments(List<RiverSegment> segs) {
        Map<Long, List<RiverNode>> m = new ConcurrentHashMap<>();
        for (RiverSegment s : segs) indexSegment(m, s);
        return m;
    }

    /** 单段 → bucket 索引（追加）。 */
    private static void indexSegment(Map<Long, List<RiverNode>> m, RiverSegment s) {
        for (RiverNode n : s.path) {
            m.computeIfAbsent(bucketKey(n.x(), n.z()), k -> new ArrayList<>()).add(n);
        }
    }

    /** 源头池：32wu 栅格 + <b>径流门槛（气候湿度 → 源头密度）</b>降序 + 间距约束。
     *  ★ 阶段 D：干旱区源头门槛高（源头稀 → 河网疏），湿润区门槛低（密）；
     *  曾试"e 局部极大"→ 32wu 栅格上满足者极少（377→62 实锤）已废弃。 */
    private List<double[]> selectSources(double ox, double oz, int size) {
        List<double[]> cand = new ArrayList<>();
        for (double px = ox + 32.0; px < ox + size - 32.0; px += 32.0) {
            for (double pz = oz + 32.0; pz < oz + size - 32.0; pz += 32.0) {
                double e = net.eAt(px, pz);
                if (e > sourceThresholdAt(net, px, pz)) cand.add(new double[]{e, px, pz});
            }
        }
        cand.sort((a, b) -> Double.compare(b[0], a[0]));
        List<double[]> sel = new ArrayList<>();
        double sq = SOURCE_SPACING * SOURCE_SPACING;
        for (double[] c : cand) {
            boolean tooClose = false;
            for (double[] s : sel) {
                double dx = c[1] - s[1], dz = c[2] - s[2];
                if (dx * dx + dz * dz < sq) { tooClose = true; break; }
            }
            if (!tooClose) sel.add(c);
        }
        return sel;
    }

    /** 支流段组装：水面 = 追踪水面（地形跟随），河床 = 水面 − 深度，宽度源头窄→下游宽。 */
    private RiverSegment toTribSegment(Trace t, int gx, int gz, java.util.Random r) {
        int n = t.pts;
        List<RiverNode> path = new ArrayList<>(n);
        double[] nw = new double[n];
        double w = net.mainWidth() * net.tribWidthFrac() * (0.6 + r.nextDouble() * 0.5);
        double depth = Math.min(net.mainDepth() * 0.6, Math.max(1.0, w * 0.4));
        double surf0 = t.surf[0], surfN = Math.min(t.surf[n - 1], surf0); // clamp 防出口上坡
        for (int i = 0; i < n; i++) {
            // ★ 修复 #2（2026-08-16）：水面 = min(线性过渡, 追踪水面)——线性在
            //   深谷虚高 → 支流雕刻 `origHeight < waterY-3` 整段 NONE（MC 支流
            //   消失实锤）；min 保持单调不升，谷中贴地形。
            double f = n > 1 ? (double) i / (n - 1) : 1.0;
            double surf = Math.min(surf0 + (surfN - surf0) * f, t.surf[i]);
            double d = depth * (0.6 + 0.4 * f); // 源头浅 → 下游深
            path.add(new RiverNode(t.x[i], t.z[i], surf, surf - d));
            nw[i] = Math.max(0.4, w * 0.35 * (1.0 - f) + w * f); // 源头窄→下游宽
        }
        return new RiverSegment(RiverSegmentType.TRIBUTARY, gx, gz, path,
            -1, -1, -1, -1, surf0, surfN - depth, w, nw, 1);
    }

    // ===== D8 下坡追踪 =====

    /**
     * 追踪（核心）：源头 → 海（sink=0）/ 洼地（sink=-1）/ 汇入河道（sink=1）。
     * <ul>
     *   <li>缓坡（grad 小）蛇曲：细选中心加正弦摆动，5 候选取 e 最低；
     *       陡坡直落（蛇曲被地形压制）。</li>
     *   <li>水面 = 地形跟随且单调不升（水往低处流）；汇入时对齐目标水面。</li>
     * </ul>
     * @param mainBuckets 主河汇入索引（null = 不检查，主河自身生成）
     * @param localBuckets 本 GRID 已生成支流汇入索引（null = 不检查）
     */
    private Trace trace(double sx, double sz,
                        Map<Long, List<RiverNode>> mainBuckets,
                        Map<Long, List<RiverNode>> localBuckets) {
        // 步数上限 600（≈2400wu+，含绕行大步）；打转由净位移检测截断，上限仅兜底
        int maxSteps = 600;
        Trace t = new Trace(maxSteps);
        double curX = sx, curZ = sz;
        // ★ 2026-08-16 水面语义修正：surf 存<b>世界 Y</b>（heightFromE）——
        //   旧代码存 e 值（0..1）当 Y 用 → 支流水面≈0.3 贴地/挖穿、主河恒 62
        //   无落差（用户实锤"河流都在海平面低一格，没有落差"）。
        //   源头水面 = 源头地形高度（真实落差：内陆高 → 入海 62）
        double surf = heightFromE(Math.max(0.0, net.eAt(sx, sz)));
        t.add(curX, curZ, surf);
        // ★ 2026-08-16 振荡根治：追踪<b>全程锚定 4wu 网格</b>——D8 存在时严格
        //   D8 网格步进；d<0 时绕行锁定方向大步 16wu（4 格，天然落网格）+ 漫流
        //   沿 escapeDir 就近 8 方向网格步进。根因（98765 调试实锤）：旧细选用
        //   连续方向（cos/sin）产生非网格位置，flowDirAt 取最近格点 → 微地形上
        //   与 D8 方向互斥 → 原地振荡（(-135,446)→绕行→细选拉回→600 步耗尽）。
        //   蛇曲装饰留待阶段 E（在网格上做 k±1 蛇曲）。
        double escapeDir = Double.NaN; // 跨轮次漫流方向（绕行锁定后保存）
        int escapeSteps = 0;           // 漫流步数（>64 后重新绕行，防错误方向空走）
        boolean seaDash = false;       // ★ 修复 #4：入海冲刺激活（近海见海无条件推进）
        // ★ 停滞检测：60 步（240wu）内净位移 < 60wu = 打转才终止——血泪史：
        //   e 降阈值 0.01/0.002/0.0005 全部误杀缓坡平原/高原面（98765 flat
        //   246→471→472 实锤），纯几何判定：直线缓降净位移 ~240wu 永不误杀。
        double px60 = sx, pz60 = sz;
        for (int step = 0; step < maxSteps; step++) {
            if (step > 0 && step % 60 == 0) {
                double dx = curX - px60, dz = curZ - pz60;
                if (dx * dx + dz * dz < 60.0 * 60.0) { t.sink = -2; break; } // 打转
                px60 = curX;
                pz60 = curZ;
            }
            if (net.eAt(curX, curZ) <= 0.0) { // 入海：水面收束到海平面
                t.sink = 0;
                if (t.pts > 0) t.surf[t.pts - 1] = Math.min(t.surf[t.pts - 1], net.seaLevelY() - 1.0);
                break;
            }
            // 汇入检查放最前：**任何状态（D8/绕行/漫流）都查**——旧位置在细选前，
            // 绕行/漫流阶段完全跳过 → 平原漫流经过主河旁错过（flat=100 实锤）
            // ★ 高度条件恢复（Y 语义）：目标水面 ≤ 当前水面 + 2 → 只允许向
            //   下游汇入，杜绝"下降中向上折线跳河"（用户实锤）
            if (step >= 8) {
                double[] hit = joinTarget(curX, curZ, mainBuckets, surf);
                if (hit == null && localBuckets != null) hit = joinTarget(curX, curZ, localBuckets, surf);
                if (hit != null) {
                    t.sink = 1;
                    t.add(hit[0], hit[1], Math.min(surf, hit[2]));
                    break;
                }
            }
            int d = net.flowDirAt(curX, curZ);
            if (d >= 0) {
                // D8 网格步进（最陡下坡，锚定 4wu 网格，无细选振荡）
                escapeDir = Double.NaN;
                escapeSteps = 0;
                curX += FlowField.DX8[d] * STEP;
                curZ += FlowField.DZ8[d] * STEP;
                surf = Math.min(surf, heightFromE(Math.max(0.0, net.eAt(curX, curZ))));
                t.add(curX, curZ, surf);
                continue;
            }
            // d<0：漫流延续（沿 escapeDir 就近 8 方向网格步进；每步验证 e——
            // ★ 2026-08-16 用户"支流非单调下降"实锤：直线步进不查 e，平原微
            //   起伏走上坡。目标 e ≥ 当前 e（+容差）即停漫流 → 下轮重新绕行）
            if (!Double.isNaN(escapeDir) && escapeSteps < 64) {
                int k = nearestDir8(escapeDir);
                double nx = curX + FlowField.DX8[k] * STEP;
                double nz = curZ + FlowField.DZ8[k] * STEP;
                double curE = net.eAt(curX, curZ);
                if (net.eAt(nx, nz) < curE - 1e-6) { // 有下降才走
                    curX = nx;
                    curZ = nz;
                    escapeSteps++;
                    surf = Math.min(surf, heightFromE(Math.max(0.0, net.eAt(curX, curZ))));
                    t.add(curX, curZ, surf);
                    continue;
                }
                escapeDir = Double.NaN; // 该方向无下降 → 停止漫流，重新绕行
            }
            // 重新绕行：多尺度找下坡 → FAR 盆地溢出口 → 沿**选中 scale** 移动
            // ★ 2026-08-16 移动距离 = 采样 scale（scale=4→移1格…scale=40→移10格，
            //   FAR 96→24格=96wu 大步跨盆地）——到达点 = 采样最低点，地形 e 严格
            //   下降（PL-RGA `_downhillNeighbor` RIVER_MIN_DROP 语义）。旧固定
            //   16wu：scale=4 采样方向却跳 16wu → 中间 4 格可能更高（非单调实锤）。
            escapeDir = Double.NaN;
            escapeSteps = 0;
            seaDash = false;
            boolean nearSea = net.eAt(curX, curZ) < SAIL_E;
            double[] scales = nearSea ? SAIL_SCALES : SCALES;
            double bestE = net.eAt(curX, curZ);
            int bestK = -1;
            double bestScale = 0;
            for (double scale : scales) {
                for (int k = 0; k < 8; k++) {
                    double e = net.eAt(curX + FlowField.DX8[k] * scale,
                        curZ + FlowField.DZ8[k] * scale);
                    if (e < bestE) { bestE = e; bestK = k; }
                }
                if (bestK >= 0) { bestScale = scale; break; } // 该尺度有下坡 → 不再扩大
            }
            if (bestK < 0) {
                // 近尺度无下坡 → FAR 远尺度找盆地溢出口/坡向海。
                // ★ 门控 e < bestE - 0.03（≈2 block 落差）：浅凹坑/平原微起伏
                //   （0.01-0.03e）不穿（保持填湖），真盆地缘（落差 >2 block）
                //   才穿——98765 实测 flat 断头点 100wu 外落差 0.07-0.36e。
                for (double scale : FAR_SCALES) {
                    for (int k = 0; k < 8; k++) {
                        double e = net.eAt(curX + FlowField.DX8[k] * scale,
                            curZ + FlowField.DZ8[k] * scale);
                        if (e < bestE - 0.03) { bestE = e; bestK = k; }
                    }
                    if (bestK >= 0) { bestScale = scale; break; }
                }
            }
            if (bestK < 0 && nearSea) {
                // ★ 修复 #4（2026-08-16）入海冲刺：近海（e<SAIL_E）且所有尺度
                //   无严格下坡 → 8 方向长距找海（e≤0），见海无条件推进。海滩
                //   平缓（每百格降 ~0.01e），FAR 0.03e 门槛在 640wu 采样方向错位
                //   时够不到 → sink=-1 断头（"差一点入海就断头"用户实锤）。
                for (double scale : new double[]{800.0, 1024.0}) {
                    for (int k = 0; k < 8; k++) {
                        double e = net.eAt(curX + FlowField.DX8[k] * scale,
                            curZ + FlowField.DZ8[k] * scale);
                        if (e <= 0.0) { bestE = e; bestK = k; }
                    }
                    if (bestK >= 0) { bestScale = scale; seaDash = true; break; }
                }
            }
            if (bestK < 0) { t.sink = -1; break; } // 全尺度无更低 = 真洼地（阶段 B 填湖）
            // 沿选中尺度移动（到达 = 采样最低点，e 必降——PL-RGA 语义）。
            // ★ 封顶 16 格（64wu）：SCALES（≤40wu）用原值（≤10 格）；FAR
            //   （96-640wu）用 16 格——避免路径过疏/飞过汇入窗口（40wu）。
            //   FAR 到达点非采样点 → 验证 e < 起点：64wu 内仍无下降 = 该方向
            //   非真溢出口（盆地缘应持续下降）→ 断头填湖。
            escapeDir = Math.atan2(FlowField.DZ8[bestK], FlowField.DX8[bestK]);
            int moveSteps = Math.min(16, Math.max(1, (int) Math.round(bestScale / STEP)));
            double startE = net.eAt(curX, curZ);
            double nx = curX, nz = curZ;
            for (int mi = 0; mi < moveSteps; mi++) {
                nx += FlowField.DX8[bestK] * STEP;
                nz += FlowField.DZ8[bestK] * STEP;
            }
            // ★ 修复 #4：seaDash 跳过假溢出口验证——冲海路线在平缓海滩上 64wu
            //   内未必严格下降（正是冲刺要解决的）；是否真入海由后续迭代自证。
            if (!seaDash && bestScale > 40.0 && net.eAt(nx, nz) >= startE - 0.005) {
                t.sink = -1; break; // FAR 方向 64wu 内未下降 → 假溢出口，断头填湖
            }
            curX = nx;
            curZ = nz;
            escapeSteps = 0;
            surf = Math.min(surf, heightFromE(Math.max(0.0, net.eAt(curX, curZ))));
            t.add(curX, curZ, surf);
        }
        return t;
    }

    /** 弧度 → 最近 8 方向索引（漫流网格步进用） */
    private static int nearestDir8(double angle) {
        int best = 0;
        double bestDiff = 1e9;
        for (int k = 0; k < 8; k++) {
            double a = Math.atan2(FlowField.DZ8[k], FlowField.DX8[k]);
            double diff = Math.abs(angleDelta(a, angle));
            if (diff < bestDiff) { bestDiff = diff; best = k; }
        }
        return best;
    }

    /** 汇入判定：距河道节点 &lt; JOIN_DIST 且<b>目标地形不高于当前（+0.03e 容差）</b>
     *  且<b>目标水面 ≤ 当前水面 + 2</b> → {x, z, 目标水面}。
     *  ★ 高度条件恢复（2026-08-16 用户"下降中向上折线跳河"实锤）：Y 语义
     *  修正后目标/当前水面都是真实世界 Y，只有向下游（≤当前水面）才允许
     *  汇入；+2 容忍蛇曲平差。旧"水面条件恒假"是 e 值当 Y 用的派生 bug。
     *  ★ 逆梯度门控（2026-08-17 用户"下降后转折向上链接其他河流"实锤）：
     *  水面 Y 会被线性插值虚低（min(线性,追踪) 下主河在坡地水面仍低于当地
     *  地形）——只比水面会漏掉"支流在谷底被坡上主河吸上去"。地形 e 是真实
     *  梯度（不受插值影响），目标 e > 当前 e + 0.03 → 上坡节点直接跳过，
     *  支流只能等高/下坡汇入（真下游汇入）。 */
    private double[] joinTarget(double wx, double wz, Map<Long, List<RiverNode>> buckets,
                                double mySurf) {
        if (buckets == null) return null;
        double sq = JOIN_DIST * JOIN_DIST;
        double myE = net.eAt(wx, wz);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                List<RiverNode> nodes = buckets.get(bucketKey(wx + dx * 32.0, wz + dz * 32.0));
                if (nodes == null) continue;
                for (RiverNode n : nodes) {
                    double ddx = wx - n.x(), ddz = wz - n.z();
                    if (ddx * ddx + ddz * ddz > sq) continue;
                    if (net.eAt(n.x(), n.z()) > myE + 0.03) continue; // ★ 上坡节点不连
                    if (n.waterSurfaceY() <= mySurf + 2.0) {
                        return new double[]{n.x(), n.z(), n.waterSurfaceY()};
                    }
                }
            }
        }
        return null;
    }

    /** 世界坐标 → 32wu bucket 键（floorDiv 负坐标安全）。 */
    private static long bucketKey(double wx, double wz) {
        return pack(floorDiv((int) Math.floor(wx), 32), floorDiv((int) Math.floor(wz), 32));
    }

    /** 区域栅格 e 最高 k 个候选（e 降序 + 间距过滤；e≤SOURCE_MIN_E 无候选）。 */
    private List<double[]> highestCells(double ox, double oz, int size, double step, int k) {
        List<double[]> cand = new ArrayList<>();
        for (double px = ox + 4.0; px < ox + size - 4.0; px += step) {
            for (double pz = oz + 4.0; pz < oz + size - 4.0; pz += step) {
                double e = net.eAt(px, pz);
                if (e > SOURCE_MIN_E) cand.add(new double[]{e, px, pz});
            }
        }
        cand.sort((a, b) -> Double.compare(b[0], a[0]));
        List<double[]> sel = new ArrayList<>();
        double sq = SOURCE_SPACING * SOURCE_SPACING;
        for (double[] c : cand) {
            if (sel.size() >= k) break;
            boolean tooClose = false;
            for (double[] s : sel) {
                double dx = c[1] - s[1], dz = c[2] - s[2];
                if (dx * dx + dz * dz < sq) { tooClose = true; break; }
            }
            if (!tooClose) sel.add(c);
        }
        return sel;
    }

    /** 追踪中间结果（x/z 世界坐标 + 单调不升水面）。 */
    private static final class Trace {
        final double[] x, z, surf;
        int pts = 0;
        int sink = -2; // -2=步数上限 -1=洼地 0=入海 1=汇入

        Trace(int cap) {
            x = new double[cap];
            z = new double[cap];
            surf = new double[cap];
        }

        void add(double px, double pz, double s) {
            if (pts < x.length) { x[pts] = px; z[pts] = pz; surf[pts] = s; pts++; }
        }
    }

    // ===== 阶段 D：水量平衡（气候湿度 → 径流 → 河网密度） =====

    /** 确定性湿度场（0..1）：低频气候带（±0.5 → 干/湿带都出现）+ 高地地形雨。
     *  ★ 0.35 幅度实锤：seed 98765 区域全湿（无干旱带可验证密度差异） */
    static double humidityAt(RiverNetwork net, double wx, double wz) {
        double h = 0.5 + 0.5 * Math.sin(wx * 0.0025) * Math.cos(wz * 0.0017);
        double e = net.eAt(wx, wz);
        h += 0.15 * Math.max(0.0, Math.min(1.0, e * 2.0)); // 高地湿润（地形雨）
        return Math.max(0.0, Math.min(1.0, h));
    }

    /** 径流门槛（e）：湿 0.08（源头密）→ 干 0.25（源头稀）。沙漠河稀雨林河密 */
    static double sourceThresholdAt(RiverNetwork net, double wx, double wz) {
        return 0.08 + (1.0 - humidityAt(net, wx, wz)) * 0.17;
    }

    // ===== 工具 =====

    /** 地形地面 Y（e → Y；接口兼容旧 builder，探针用） */
    static double groundYAt(RiverNetwork net, double wx, double wz) {
        double e = Math.max(0.0, net.eAt(wx, wz));
        return net.curve().heightFromE(e);
    }

    /** e → 世界 Y（水面用；e≤0 → 海平面高度） */
    private double heightFromE(double e) {
        return net.curve().heightFromE(e);
    }

    private long regionSeed(int rx, int rz) {
        return net.worldSeed() ^ (rx * 341873128712L + rz * 132897987541L);
    }

    // ===== 后台预热（2026-08-17 卡死修复）=====
    // ★ 根因：fillFromNoise（Server thread）→ sampleRiver → plateForTile → buildPlate
    //   同步生成 5×5 REGION 段（3×3→5×5 后首次成本 ×2.8，1-2s/REGION）→ 保存/退出时
    //   MC 等 in-flight chunk 生成完成 → Server thread 卡在 buildPlate → 冻结（用户
    //   "退出保存存档中/退出游戏中卡死"实锤；RiverNetwork 157-160 行注释同类卡死）。
    //   修复：玩家移动时后台提前生成前方 REGION 段（putIfAbsent 幂等）→ Server thread
    //   首次 buildPlate 命中缓存。独立 daemon 单线程池：不抢 TILE_SAMPLER（无
    //   CallerRunsPolicy 反压到 Server thread 的风险）、不阻止 JVM 退出。
    private static final java.util.concurrent.ExecutorService WARMER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GeoGenesis-RiverWarm");
            t.setDaemon(true);
            return t;
        });
    private final java.util.Set<Long> warming = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 预热 (wx,wz) 周边 ±radius 个 REGION 的主河/支流段（幂等，后台串行，失败静默）。 */
    void warmRegionsAround(double wx, double wz, int radius) {
        int rx0 = floorDiv((int) Math.floor(wx), REGION_SIZE);
        int rz0 = floorDiv((int) Math.floor(wz), REGION_SIZE);
        for (int rz = rz0 - radius; rz <= rz0 + radius; rz++) {
            for (int rx = rx0 - radius; rx <= rx0 + radius; rx++) {
                long key = pack(rx, rz);
                if (!warming.add(key)) continue; // 已在队列/执行中 → 跳过
                final int frx = rx, frz = rz; // lambda 捕获需 effectively final
                WARMER.execute(() -> {
                    try {
                        regionMainRivers(frx, frz);
                        regionTributaries(frx, frz);
                    } catch (Throwable t) {
                        // 预热失败静默——下次访问同步生成兜底，正确性不受影响
                    } finally {
                        warming.remove(key);
                    }
                });
            }
        }
    }

    private void prune() {
        if (mainRegions.size() > CACHE_MAX) {
            var it = mainRegions.keySet().iterator();
            int toRemove = Math.max(1, mainRegions.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        if (regionTribs.size() > CACHE_MAX) {
            var it = regionTribs.keySet().iterator();
            int toRemove = Math.max(1, regionTribs.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    static int floorDiv(int v, int d) {
        int q = v / d;
        return (v % d != 0 && ((v ^ d) < 0)) ? q - 1 : q;
    }

    /** 角度差（弧度，归一化到 [-π, π]；细选 D8 方向偏差判定用） */
    private static double angleDelta(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    private static long pack(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }
}
