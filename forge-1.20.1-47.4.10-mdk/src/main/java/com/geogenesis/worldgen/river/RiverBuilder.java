package com.geogenesis.worldgen.river;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 河网拓扑构建器（确定性几何河网 · Phase 1）。
 *
 * <p>构建规则（决策 1/3 拍板，对齐 Farseek/Streams MIT 思想）：</p>
 * <ol>
 *   <li><b>Basin 网格</b>：128wu，floorDiv 对齐（与侵蚀 tile 同网格）。</li>
 *   <li><b>主河行</b>：{@code basinZ % 4 == 0}（Farseek MainBasinZSpacing=4）。</li>
 *   <li><b>主河链</b>：从主河行 basin 出发 <b>Basin 级 D8</b>（8 邻居取 e 最低），
 *       链式下坡直到 e&lt;0（入海 → 尾段 Mouth）或局部最低（闭合盆地终止）。</li>
 *   <li><b>主河段</b>：链上每 basin 一段，相邻段共享出/入界点（结构性无缝雏形）；
 *       弯向由坐标奇偶确定性决定（Farseek bendsLeft = x.isEven == z.isEven 同款）。</li>
 *   <li><b>支流</b>：非主行 basin 且 4 邻在主河链上（段为 REACH 非 MOUTH）→
 *       Dijkstra（成本 = 上坡 e 差 + 步长）从接入点追到 basin 内最高点（泉眼），
 *       1-2 条/候选 basin（坐标哈希决定）。水面逐节点抬升（Farseek 公式，见下）。</li>
 * </ol>
 *
 * <p><b>支流抬升公式</b>（TributaryNode.scala:57-59 逐行对齐，delta(a,b)=b−a）：</p>
 * <pre>
 * fallHeight = (上游地表传播值 − 下游水面) / 2
 * surfaceLevel = downstreamSurfaceLevel + max(0, fallHeight)   // ≥3 块才抬
 * </pre>
 * 上游地表传播值 = 从泉眼向下游取 min（自身地表 / 上游传播值），泉眼 = 自身地表。
 *
 * <p><b>确定性铁律</b>：所有哈希基于世界坐标（basin 坐标 / 网格点坐标），
 * 无全局随机流；缓存写入统一「get → 计算 → putIfAbsent」（禁 computeIfAbsent 嵌套递归）。</p>
 */
final class RiverBuilder {

    /** Basin 边长（wu），与 RiverNetwork 常量同源 */
    static final int BASIN_SIZE = RiverNetwork.BASIN_SIZE;
    /** 主河行间隔（basin 单位，Farseek MainBasinZSpacing=4） */
    static final int MAIN_ROW_SPACING = 4;
    /** 段出/入界点距 basin 中心的比例（留边余量） */
    private static final double EDGE_FRAC = 0.45;
    /** 中点弯向幅度（× BASIN_SIZE） */
    private static final double BEND_AMP = 0.25;
    /** Dijkstra 上坡成本权重（e 差 × 权重 + 步长） */
    private static final double DIJKSTRA_UPHILL_WEIGHT = 2.0;
    /** 主河链最长追溯限制（防极端地形下失控；正常远小于此） */
    private static final int MAX_CHAIN_LENGTH = 1024;
    /** 主河流线最大步数（4wu/步 × 1500 = 6000wu 上限，防极端地形失控） */
    private static final int MAX_FLOW_STEPS = 1500;
    /** 支流逆流追踪最大步数（4wu/步 × 60 = 240wu，跨 1-2 basin 到分水岭） */
    private static final int TRIB_MAX_STEPS = 60;
    /** 支流最少节点数（< 5 = 死路残段，丢弃） */
    private static final int TRIB_MIN_NODES = 5;
    /** 主河 sink 溢流阈值（e 差）：局部最低时，8 邻最低出口 e 高于当前 < 此值 = 浅坑可溢流 */
    private static final double SINK_SPILL_EPS = 0.03;
    /** 主河水面每段最大抬升（Y 块，Dynamic Waters terrace rise=min(diff,4) 对齐） */
    private static final double MAX_RISE_PER_SEG = 4.0;
    // ===== P0 河网密度（2026-08-15，DW 三级分支缩比版） =====
    /** 泉眼最多数量（2026-08-15 R11：3 → 2——3 泉眼 + 二级分支 = 719 条爆炸的源头） */
    private static final int SPRING_MAX = 2;
    /** 泉眼间距下限（wu；两泉眼过近 = 路径重叠，DW 分支间距 ≥4 节点语义） */
    private static final double SPRING_MIN_DIST = 24.0;
    /** discharge 泉眼阈值（0.5 → 0.3：液滴场值偏低，0.5 杀掉大量真汇水点 = 支流 60 条稀疏主因） */
    private static final double DISCHARGE_SPRING_THRESHOLD = 0.3;
    /** 二级分支半宽系数（× 一级支流半宽；0.5→0.35：支流更细，视觉层级更分明） */
    private static final double SECONDARY_WIDTH_FRAC = 0.35;
    /** 二级分支最大步数区间（DW 宽度分档：窄河 12-20 步×3wu；我们步长 4wu → 12-25 步 = 48-100wu） */
    private static final int SECONDARY_STEPS_MIN = 12;
    private static final int SECONDARY_STEPS_MAX = 25;
    /** 二级分支最少节点数（< 5 = 死路残段，丢弃；DW 分支 nodeCount 下限缩比） */
    private static final int SECONDARY_MIN_NODES = 5;
    /** 二级缓存上限（有支流 basin key；防玩家无限移动 OOM，与 plates 同策略删 1/8） */
    private static final int SECONDARY_CACHE_MAX = 4096;
    /** discharge 场采样（液滴流量累积 → 真物理水流方向；null = 无侵蚀数据） */
    private final ESampler dischargeSampler;

    private final RiverNetwork net;

    /** 主河行 basin → 整条流线（{x,z} 点数组；R6 起 = 4wu 连续 D8 下坡流线，非 basin 网格链） */
    private final ConcurrentHashMap<Long, double[][]> chains = new ConcurrentHashMap<>();
    /** basin → 所在链的链头 basin key（4 邻"在主河链上"判定） */
    private final ConcurrentHashMap<Long, Long> basinToChain = new ConcurrentHashMap<>();
    /** 支流路径来源统计（诊断用） */
    static int statNat = 0, statDij = 0;

    /** 链上 basin → 主河段 */
    private final ConcurrentHashMap<Long, RiverSegment> mainSegments = new ConcurrentHashMap<>();
    /** 支流 basin → 支流段列表 */
    private final ConcurrentHashMap<Long, List<RiverSegment>> tributaries = new ConcurrentHashMap<>();
    /** 一级支流 basin → 二级分支段列表（P0 密度：DW 2nd degree，随一级同步懒构建） */
    private final ConcurrentHashMap<Long, List<RiverSegment>> secondaries = new ConcurrentHashMap<>();
    /** 负缓存：确认不在任何主河链上的 basin（海洋/链头非主河行/孤立盆），防重复反查 */
    private final java.util.Set<Long> nonChainBasins = ConcurrentHashMap.newKeySet();

    RiverBuilder(RiverNetwork net, ESampler discharge) {
        this.net = net;
        this.dischargeSampler = discharge;
    }

    // ===== Plate 构建 =====

    /**
     * 构建 tile 的河板块：3×3 basin 邻域内所有相关段。
     * 顺序固定：先主河行（链 ensure），后支流（依赖链存在判定）。
     */
    RiverPlate buildPlate(int tileCX, int tileCZ) {
        List<RiverSegment> segs = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        // 1. 主河段：
        //    a) 触发链构建（仅主河行 basin——链头资格 = 主河行，对齐 Farseek 主河源头
        //       语义；非主河行触发会让每个局部高盆都成链头 → 链爆炸 2026-08-14 实测）
        //    b) 统一查表收集 3×3 邻域内所有主河段（链上段由触发 plate 构建后，
        //       所有邻域 plate 通过 mainSegments 查表收集，无漏）
        for (int bz = tileCZ - 1; bz <= tileCZ + 1; bz++) {
            for (int bx = tileCX - 1; bx <= tileCX + 1; bx++) {
                if (mod(bz, MAIN_ROW_SPACING) == 0) {
                    ensureMainChain(bx, bz);
                }
            }
        }
        for (int bz = tileCZ - 1; bz <= tileCZ + 1; bz++) {
            for (int bx = tileCX - 1; bx <= tileCX + 1; bx++) {
                RiverSegment seg = mainSegments.get(pack(bx, bz));
                if (seg == null && mod(bz, MAIN_ROW_SPACING) != 0) {
                    // 非主河行兜底：链可能由远处 plate 触发（plate 缓存时序），
                    // 此处确保构建后复查（负缓存挡住重复反查）
                    ensureMainChain(bx, bz);
                    seg = mainSegments.get(pack(bx, bz));
                }
                addSegment(segs, seen, seg);
            }
        }

        // 2. 支流：3×3 邻域内非主行 basin
        for (int bz = tileCZ - 1; bz <= tileCZ + 1; bz++) {
            for (int bx = tileCX - 1; bx <= tileCX + 1; bx++) {
                if (mod(bz, MAIN_ROW_SPACING) == 0) continue;
                List<RiverSegment> tribs = ensureTributary(bx, bz);
                if (tribs == null) continue;
                for (RiverSegment s : tribs) addSegment(segs, seen, s);
            }
        }

        // 2.5 二级分支：3×3 邻域内一级支流 basin → 二级段（P0 密度，随一级缓存懒构建）
        for (int bz = tileCZ - 1; bz <= tileCZ + 1; bz++) {
            for (int bx = tileCX - 1; bx <= tileCX + 1; bx++) {
                List<RiverSegment> secs = secondaries.get(pack(bx, bz));
                if (secs == null) continue;
                for (RiverSegment s : secs) addSegment(segs, seen, s);
            }
        }
        pruneSecondaries();

        return new RiverPlate(tileCX, tileCZ, List.copyOf(segs));
    }

    private static void addSegment(List<RiverSegment> out, Set<Integer> seen, RiverSegment seg) {
        if (seg == null) return;
        // ★ 2026-08-15 P0 修正：basin key 去重会吞同 basin 多段（主河链经过非主河行
        //   basin 时的支流段、二级分支与一级同 basin）——改段 uid 去重（plate 3×3
        //   邻域同段会被多 plate 收集，uid 恰好精确去重）
        if (seen.add(seg.uid)) out.add(seg);
    }

    // ===== 主河链（Basin 级 D8） =====

    /**
     * 从主河行 basin 构建整条下游链（缓存；并发重复构建无害——纯函数结果一致，
     * putIfAbsent 收敛。链头判定 = 调用方 basin，上游链由上游 plate 另行构建）。
     */
    /**
     * 确保链存在：从任意陆地主河行 basin 出发，**先反查链头**（沿"指向本 basin 的
     * 上游邻居"上溯，e 单调递增天然无环）再从链头构建整条下游链。
     *
     * <p>为何反查：plate 的 3×3 邻域可能不含链头（链头在远处 tile），若不反查则
     * 该链的段永远不被本 plate 收集（2026-08-14 实测 (1,7) 主河段漏收集 bug）。
     * 反查后任意调用点都汇聚到同一链头 → 同一条链 → 段几何唯一。</p>
     *
     * <p>海洋 basin 无源头资格（e&lt;0 返回 null，其段由陆地链头触发时构建）。</p>
     */
    private double[][] ensureMainChain(int bx, int bz) {
        long key = pack(bx, bz);
        double[][] cached = chains.get(key);
        if (cached != null) return cached;
        if (nonChainBasins.contains(key)) return null;

        // 快速路径：段已存在（链已由其他 plate 触发构建）→ 从 basinToChain 取链
        if (mainSegments.containsKey(key)) {
            Long headKey = basinToChain.get(key);
            if (headKey != null) return chains.get(headKey);
            return null;
        }

        // 海洋 basin 无源头资格：e<0 时跳过（避免海洋内单段 MOUTH 链泛滥）
        if (net.eAt(basinCenterX(bx), basinCenterZ(bz)) < 0) {
            nonChainBasins.add(key);
            return null;
        }

        long[] head = findHead(bx, bz);
        long hKey = pack((int) head[0], (int) head[1]);
        double[][] hCached = chains.get(hKey);
        if (hCached != null) {
            chains.putIfAbsent(key, hCached);
            return hCached;
        }

        // ★ 2026-08-15 R11c 修复：nonChain 登记**链头 hKey** 而非调用点 key！
        //   旧 bug（seed 9999 NULL next: 4,10->3,9 实锤）：EMC(3,9) 反查到非主河行
        //   链头 5,11 → 把 3,9 登记 nonChain → 之后 3,9 段即使被远处链头(4,12)的
        //   flow 建立，所有查询都被 nonChain 挡死 → plate 收集不到 3,9 段 → NULL。
        //   链头资格与调用点无关（调用点只是途经 basin）→ 只拒绝链头本身。
        // 链头必须主河行（对齐 Farseek：主河源头在主河行；非主河行链头 = 支流源头）
        // ★ 2026-08-15 R11d 撤销（链爆炸 233 条实锤）：曾允许非主河行链头以修复
        //   反查断链（5,11 被误杀）——但每个无上游 basin 都成链 = 200+ 条链爆炸。
        //   正确解法 = 保留主河行限制 + **新段建立时失效覆盖 plate 缓存**（R11e：
        //   buildMainSegments 建段后 invalidate 3×3 邻域 plate → 4,12 链建 3,9 段
        //   后 plate(3,9) 缓存失效 → 下次访问重建收集 → 反查断链消除）。
        if (mod((int) head[1], MAIN_ROW_SPACING) != 0) {
            nonChainBasins.add(hKey);
            return null;
        }
        // ★ R6（2026-08-15）：主河 = 4wu 连续 D8 下坡流线（TerraForged 真范式）。
        //   旧 basin 网格 D8 每 128wu 拉直一次（探针实锤：主河点偏离 4wu 连续谷线
        //   平均 160wu、最大 895wu = 用户"动线像没看到地形"铁证）。流线从链头
        //   出发逐点 8 邻最低下坡，路径 100% 贴谷；入海/盆地终止。
        List<double[]> flow = traceFlowline((int) head[0], (int) head[1]);
        // 流线太短（< 8 点 = 32wu）不成河
        if (flow.size() < 8) {
            nonChainBasins.add(hKey);
            return null;
        }
        double[][] arr = flow.toArray(new double[0][]);
        buildMainSegments(flow);
        chains.putIfAbsent(hKey, arr);
        chains.putIfAbsent(key, arr); // 调用方 key 幂等登记
        return arr;
    }

    /**
     * 链级水面后处理（2026-08-15 R2）：从 MOUTH（下游）向源头单向传播，
     * 每段尾水面 = 下游段头水面（连接点精确连续），段内向上游逐点缓抬
     * （每点 ≤ 前点+2 且 ≤ 列高−0.5）→ 段内单调、段间连续、贴地钳制。
     * 单遍即收敛（上游段重算时其尾 = 已对齐的下游段头）。
     */
    private void alignChainWater(List<long[]> chain) {
        RiverSegment prev = null; // 下游段（链尾 → 链头遍历）
        for (int i = chain.size() - 1; i >= 0; i--) {
            long[] b = chain.get(i);
            RiverSegment seg = mainSegments.get(pack((int) b[0], (int) b[1]));
            if (seg == null) continue;
            List<RiverNode> p = seg.path;
            int n = p.size();
            if (n == 0) { prev = seg; continue; }
            double startW = seg.surfaceLevel;
            if (prev != null && !prev.path.isEmpty()) {
                startW = prev.path.get(0).waterSurfaceY(); // 下游段头水面（连接点连续）
            }
            // 从段尾（下游）向段头（上游）重算：每点 = max(下一点, min(下一点+2, 列高−0.5))
            // path 是 List.copyOf 不可变 → 重建新列表
            double w = startW;
            List<RiverNode> aligned = new ArrayList<>(n);
            for (int k = n - 1; k >= 0; k--) {
                RiverNode nd = p.get(k);
                double cap = groundYAt(nd.x(), nd.z()) - 0.5;
                if (k < n - 1) {
                    double prevW = aligned.get(0).waterSurfaceY(); // 已算的下游点（列表逆序构建，头=下游点）
                    w = Math.max(prevW, Math.min(prevW + MAX_RISE_PER_NODE, cap));
                } else {
                    w = startW;
                }
                aligned.add(new RiverNode(nd.x(), nd.z(), w, w - net.mainDepth()));
            }
            java.util.Collections.reverse(aligned);
            // 重建 RiverSegment（保 uid 语义：widths 同步重排——path 节点数不变，原 widths 直接复用）
            RiverSegment alignedSeg = new RiverSegment(
                seg.type, seg.basinX, seg.basinZ, aligned,
                seg.upstreamBasinX, seg.upstreamBasinZ, seg.downstreamBasinX, seg.downstreamBasinZ,
                startW, aligned.get(aligned.size() - 1).bedY(),
                seg.width, seg.nodeWidths, 1);
            mainSegments.put(pack((int) b[0], (int) b[1]), alignedSeg);
            prev = alignedSeg;
        }
    }

    /**
     * 反查链头：沿 8 邻中"e 更高且其 D8 最低邻居 = 本 basin"者上溯（取 e 最高者，
     * 确定性 tie-break 遍历序）。e 单调递增 → 天然无环（seen 防御）。
     */
    private long[] findHead(int bx, int bz) {
        Set<Long> seen = new HashSet<>();
        int cx = bx, cz = bz;
        while (seen.add(pack(cx, cz))) {
            int[] up = findUpstream(cx, cz);
            if (up == null) break;
            cx = up[0];
            cz = up[1];
        }
        return new long[]{cx, cz};
    }

    /**
     * 8 邻中 e 更高且 D8 指向本 basin 的"真上游"（取 e 最高者；无则 null）。
     * 哨兵用 Integer.MIN_VALUE——basin 坐标可为负（-1 曾被误判为"无上游"，2026-08-14）。
     */
    private int[] findUpstream(int bx, int bz) {
        double eCur = net.eAt(basinCenterX(bx), basinCenterZ(bz));
        int bestX = Integer.MIN_VALUE, bestZ = Integer.MIN_VALUE;
        double bestE = Double.NEGATIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                int nx = bx + dx, nz = bz + dz;
                double eN = net.eAt(basinCenterX(nx), basinCenterZ(nz));
                if (eN <= eCur) continue;
                int[] lo = lowestNeighbor(nx, nz);
                if (lo[0] == bx && lo[1] == bz && eN > bestE) {
                    bestE = eN;
                    bestX = nx;
                    bestZ = nz;
                }
            }
        }
        return bestX == Integer.MIN_VALUE ? null : new int[]{bestX, bestZ};
    }

    /**
     * 8 邻居（含自身）中 e 最低者。与 D8 追踪同一判定
     * （严格更小 + 固定遍历序 → 确定性）。返回 {x, z}。
     */
    private int[] lowestNeighbor(int bx, int bz) {
        double bestE = net.eAt(basinCenterX(bx), basinCenterZ(bz));
        int bestX = bx, bestZ = bz;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                int nx = bx + dx, nz = bz + dz;
                double eN = net.eAt(basinCenterX(nx), basinCenterZ(nz));
                if (eN < bestE - 1e-9) {
                    bestE = eN;
                    bestX = nx;
                    bestZ = nz;
                }
            }
        }
        return new int[]{bestX, bestZ};
    }

    /**
     * 主河流线：4wu 连续 D8 下坡追踪（R6，2026-08-15）。
     *
     * <p>从链头 basin 中心（先 8wu 邻域细化为局部最低，防中心恰在山脊）出发，
     * 每步 4wu 取 8 邻 e 最低者（严格更小 1e-9 + 固定遍历序 → 确定性），直到
     * e&lt;0（入海）或局部最低（闭合盆地/湖）。e 单调下降 → 天然无环。</p>
     *
     * <p><b>贴谷保证</b>：路径点 = 每步 8 邻最低 → 流线恒在谷线上，永不横穿
     * 山脊。与落块地形（sampleCore.e）同构 → 河网场 = 实际地形场（纯噪声
     * 地形下也成立，用户实测场景）。</p>
     */
    private List<double[]> traceFlowline(int startBx, int startBz) {
        final int[] dxi = {1, -1, 0, 0, 1, 1, -1, -1};
        final int[] dzi = {0, 0, 1, -1, 1, -1, 1, -1};
        double cx = basinCenterX(startBx), cz = basinCenterZ(startBz);
        // 起点细化：8wu 邻域内取局部最低（链头 basin 中心可能在山脊/鞍部）
        double eStart = net.eAt(cx, cz);
        for (int k = 0; k < 8; k++) {
            double en = net.eAt(cx + dxi[k] * 8.0, cz + dzi[k] * 8.0);
            if (en < eStart - 1e-9) {
                eStart = en;
                cx += dxi[k] * 8.0;
                cz += dzi[k] * 8.0;
            }
        }
        List<double[]> flow = new ArrayList<>();
        // ★ 2026-08-15 R6 防绕行：禁止**重新进入曾经离开的 basin**（D8 蛇形穿回
        //   同 basin → 同 basin 第二运行段被 containsKey 跳过 → 链断 100wu，探针
        //   GAP 实锤）。语义：basin 变化时标记旧 basin 为"已离开"；候选禁入"已
        //   离开 basin"（当前 basin 内移动放行——128wu 内要走 32 步才到边界，
        //   一步一禁会把流线直接掐死在起点）。
        java.util.Set<Long> leftBasins = new java.util.HashSet<>();
        long curBasin = pack((int) Math.floor(cx / BASIN_SIZE), (int) Math.floor(cz / BASIN_SIZE));
        for (int s = 0; s < MAX_FLOW_STEPS; s++) {
            flow.add(new double[]{cx, cz});
            double e = net.eAt(cx, cz);
            if (e < 0) break; // 入海
            double bestE = e;
            int bestK = -1;
            for (int k = 0; k < 8; k++) {
                double nx = cx + dxi[k] * 4.0, nz = cz + dzi[k] * 4.0;
                long nbk = pack((int) Math.floor(nx / BASIN_SIZE), (int) Math.floor(nz / BASIN_SIZE));
                if (nbk != curBasin && leftBasins.contains(nbk)) continue; // 禁入已离开 basin
                double en = net.eAt(nx, nz);
                if (en < bestE - 1e-9) {
                    bestE = en;
                    bestK = k;
                }
            }
            if (bestK < 0) {
                // ★ 2026-08-15 R7 主河 sink 溢流（治"主河很短"）：纯噪声地形有大量
                //   闭合洼地（侵蚀/骨架全关更甚），D8 流线遇局部最低就 break → 主河
                //   提前终止在浅坑，未入海 = 用户"主河很短"根因。溢流 = 找 8 邻最低
                //   出口，浅坑（Δe < SINK_SPILL_EPS）→ 跳到出口继续流（物理 = 湖
                //   溢流成河），深湖 → 终止。
                double bestOut = Double.POSITIVE_INFINITY;
                int outK = -1;
                for (int k = 0; k < 8; k++) {
                    double nx = cx + dxi[k] * 4.0, nz = cz + dzi[k] * 4.0;
                    long nbk = pack((int) Math.floor(nx / BASIN_SIZE), (int) Math.floor(nz / BASIN_SIZE));
                    if (nbk != curBasin && leftBasins.contains(nbk)) continue;
                    double en = net.eAt(nx, nz);
                    if (en < bestOut) { bestOut = en; outK = k; }
                }
                if (outK < 0 || bestOut - e > SINK_SPILL_EPS) break; // 无出口 / 深湖
                cx += dxi[outK] * 4.0;
                cz += dzi[outK] * 4.0;
                long nbk2 = pack((int) Math.floor(cx / BASIN_SIZE), (int) Math.floor(cz / BASIN_SIZE));
                if (nbk2 != curBasin) {
                    leftBasins.add(curBasin);
                    curBasin = nbk2;
                }
                continue;
            }
            cx += dxi[bestK] * 4.0;
            cz += dzi[bestK] * 4.0;
            long nbk = pack((int) Math.floor(cx / BASIN_SIZE), (int) Math.floor(cz / BASIN_SIZE));
            // ★ 2026-08-15 R11 汇合 graft（治 maxGap 106wu 断链）：本链进入一个
            //   **已有段**的 basin（其他链先构建）→ 吸附到该段路径最近点终止。
            //   物理 = 两河汇流；几何 = 共享路径点 → sampleRiver 投影连续。
            //   （本链的段尚未构建，mainSegments 里必是其他链的段）
            //   ★ 2026-08-15 R11b：必须仅在新 basin 触发——在自己 basin 内移动时
            //     若本 basin 段已被其他链建（汇流 basin），误 graft 会提前终止，
            //     后续 run 全部丢失（seed 9999 NULL next: 4,10->3,9 实锤）。
            if (nbk != curBasin) {
                RiverSegment exist = mainSegments.get(nbk);
                if (exist != null && exist.path.size() >= 2) {
                    double[] gp = closestPointOnPath(exist, cx, cz);
                    if (gp != null) {
                        flow.add(new double[]{gp[0], gp[1]});
                        return flow;
                    }
                }
                leftBasins.add(curBasin); // 离开旧 basin → 禁回
                curBasin = nbk;
            }
        }
        return flow;
    }

    /** 点-折线最近点（只算路径点，不插值段间投影——graft 语义 = 吸附路径点） */
    private static double[] closestPointOnPath(RiverSegment seg, double wx, double wz) {
        double bestD = Double.MAX_VALUE;
        double bx = 0, bz = 0;
        for (RiverNode p : seg.path) {
            double dx = p.x() - wx, dz = p.z() - wz;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD) { bestD = d2; bx = p.x(); bz = p.z(); }
        }
        if (bestD == Double.MAX_VALUE) return null;
        return new double[]{bx, bz};
    }

    /**
     * 流线 → 主河段（R6，2026-08-15）。
     *
     * <p>把 4wu 连续下坡流线按 128wu basin 网格切成段（plate 缓存机制不变），
     * 段端点 = 流线上的自然点 → 相邻段共享端点（maxGap=0），路径 = 流线本身
     * （100% 贴谷，不再有段内几何/寻路）。</p>
     *
     * <p><b>水面</b>：整链从下游（入海 = 海平面；闭合盆地 = 地面−1）向上游逐点
     * 缓抬（每点 ≤ MAX_RISE_PER_NODE、≤ 列高−0.5），点级水面直接赋予节点——
     * 无段级漂移（旧 alignChainWater 的段级对齐不再需要）。</p>
     *
     * <p><b>宽度</b>：整链 taper（源头 0.55 → 入海 1.0 × mainWidth，按全局点序
     * 线性插值）——段内连续、段间共享点宽度连续。</p>
     */
    private void buildMainSegments(List<double[]> flow) {
        int n = flow.size();
        if (n < 8) return;
        // ---- 0. 整链水面缓抬（下游 → 上游逐点） ----
        double[] surf = new double[n];
        double[] ground = new double[n];
        for (int i = 0; i < n; i++) {
            ground[i] = groundYAt(flow.get(i)[0], flow.get(i)[1]);
        }
        double eTail = net.eAt(flow.get(n - 1)[0], flow.get(n - 1)[1]);
        surf[n - 1] = eTail < 0 ? net.seaLevelY() : Math.min(net.seaLevelY(), ground[n - 1] - 1.0);
        for (int i = n - 2; i >= 0; i--) {
            double cap = ground[i] - 0.5;
            // 保单调（水面 ≥ 下游水面）+ 缓抬 ≤ MAX_RISE_PER_NODE + 不淹没（≤ 列高−0.5）
            surf[i] = Math.max(surf[i + 1], Math.min(surf[i + 1] + MAX_RISE_PER_NODE, cap));
        }

        // ---- 1. 按 basin 运行段切分（相邻点 basin 变化 = 段边界） ----
        // ★ 2026-08-15 R6 段间共享端点：后段起点 = 前段尾点（runStart = i - 1）——
        //   相邻段精确共享一个流线点 → sampleRiver 投影连续 → maxGap = 0
        //   （旧逻辑 runStart = i 让相邻段差 4wu = 探针 GAP 4.0 根因）。
        int headBx = (int) Math.floor(flow.get(0)[0] / BASIN_SIZE);
        int headBz = (int) Math.floor(flow.get(0)[1] / BASIN_SIZE);
        List<int[]> runs = new ArrayList<>(); // {startIdx, endIdx(含), bx, bz}
        int runStart = 0;
        int curBx = headBx, curBz = headBz;
        for (int i = 1; i <= n; i++) {
            int bx, bz;
            if (i < n) {
                bx = (int) Math.floor(flow.get(i)[0] / BASIN_SIZE);
                bz = (int) Math.floor(flow.get(i)[1] / BASIN_SIZE);
            } else {
                bx = curBx + 1; // 哨兵强制收尾（不与 cur 相同）
                bz = curBz;
            }
            if (bx != curBx || bz != curBz) {
                runs.add(new int[]{runStart, i - 1, curBx, curBz});
                runStart = i - 1; // ← 后段起点 = 前段尾点（共享端点，maxGap=0）
                curBx = bx;
                curBz = bz;
            }
        }
        // ★ 2026-08-15 R6：禁用"短段并入前段"合并——合并会拉伸前段端点（探针 GAP
        //   100wu 实锤：碎片段尾点并入 prev 后，prev 端点到下一个共享点相距 100wu）。
        //   碎片段（1-2 点）独立保留，共享点语义正确 → maxGap = 0（段数略多，无害）。
        List<int[]> merged = new ArrayList<>(runs);

        // ---- 2. 每运行段生成 RiverSegment ----
        int runCount = merged.size();
        long headKey = pack(headBx, headBz);
        // ★ 2026-08-15 R11b 有效下游索引（治 NULL next: 4,10->3,9）：
        //   flow 在深坑 sink break 终止 → 末尾单点 run（ptCount<2 会被 skip）→
        //   前段 downX 仍指向无段的 basin = 探针 NULL 断链。跳过"单点且无段"的
        //   run 找真正可连接的下游（有段 = graft 汇流点，或 ≥2 点 = 将建段）。
        int[] effDown = new int[runCount];
        for (int ri = runCount - 1; ri >= 0; ri--) {
            int next = ri + 1;
            while (next < runCount) {
                int[] nrun = merged.get(next);
                int ncnt = nrun[1] - nrun[0] + 1;
                if (ncnt >= 2 || mainSegments.containsKey(pack(nrun[2], nrun[3]))) break;
                next++;
            }
            effDown[ri] = next < runCount ? next : -1;
        }
        for (int ri = 0; ri < runCount; ri++) {
            int[] run = merged.get(ri);
            int s = run[0], e = run[1];
            int bx = run[2], bz = run[3];
            long key = pack(bx, bz);
            if (mainSegments.containsKey(key)) continue; // 几何纯函数，幂等
            int ptCount = e - s + 1;
            if (ptCount < 2) continue;
            List<RiverNode> path = new ArrayList<>(ptCount);
            for (int i = s; i <= e; i++) {
                double[] pt = flow.get(i);
                path.add(new RiverNode(pt[0], pt[1], surf[i], surf[i] - net.mainDepth()));
            }
            // 段类型：有效下游为空且入海 → MOUTH（尾 run 若为单点被 skip，此段即尾段）
            boolean mouth = effDown[ri] < 0 && eTail < 0;
            int upX = ri == 0 ? -1 : merged.get(ri - 1)[2];
            int upZ = ri == 0 ? -1 : merged.get(ri - 1)[3];
            int downX = effDown[ri] < 0 ? -1 : merged.get(effDown[ri])[2];
            int downZ = effDown[ri] < 0 ? -1 : merged.get(effDown[ri])[3];
            // ★ 2026-08-15 R11 事后吸附（治 maxGap 106wu，时序无关）：
            //   汇合 basin 的段可能已被**其他链**先建（containsKey skip → 本链在该
            //   basin 无段）→ 本段尾点吸附到已有下游段路径最近点 → 视觉连续。
            //   （无论构建先后，尾段尾点最终落在下游段路径上；graft 同理）
            if (downX >= 0) {
                RiverSegment downSeg = mainSegments.get(pack(downX, downZ));
                if (downSeg != null && downSeg.path.size() >= 2) {
                    RiverNode last = path.get(path.size() - 1);
                    double[] gp = closestPointOnPath(downSeg, last.x(), last.z());
                    if (gp != null) {
                        path.set(path.size() - 1,
                            new RiverNode(gp[0], gp[1], last.waterSurfaceY(), last.bedY()));
                    }
                }
            }
            // 宽度 taper（全局点序线性：源头 0.55 → 入海 1.0）
            double[] nw = new double[ptCount];
            for (int k = 0; k < ptCount; k++) {
                double t = (s + k) / (double) Math.max(1, n - 1);
                nw[k] = net.mainWidth() * (0.55 + 0.45 * t);
            }
            RiverSegment seg = new RiverSegment(
                mouth ? RiverSegmentType.MOUTH : RiverSegmentType.REACH,
                bx, bz, path, upX, upZ, downX, downZ,
                surf[s], surf[s] - net.mainDepth(),
                net.mainWidth(), nw, 1);
            if (mainSegments.putIfAbsent(key, seg) == null) {
                // ★ R11e：新段建立 → 失效覆盖它的 plate 缓存（3×3 邻域）——
                //   远处链建段时，本 basin 的 plate 可能已缓存（缺此段）
                net.invalidatePlatesAround(bx, bz);
            }
            basinToChain.put(key, headKey);
        }
    }

    // ===== 支流（Dijkstra 上坡追源） =====

    /**
     * 构建支流段（缓存；null = 非候选）。候选判定（对齐 Farseek newSegment）：
     * <ul>
     *   <li>非主河行 basin</li>
     *   <li>4 邻中存在主河链 basin，且其段为 REACH（Mouth 段不接受汇入）</li>
     * </ul>
     * 密度：每候选 basin 由坐标哈希决定泉眼数 1-2。
     */
    private List<RiverSegment> ensureTributary(int bx, int bz) {
        long key = pack(bx, bz);
        List<RiverSegment> cached = tributaries.get(key);
        if (cached != null) return cached;

        double cx = basinCenterX(bx), cz = basinCenterZ(bz);

        // 1. 找接入主河段：4 邻中在主河链上且段为 REACH，取最近
        RiverSegment attach = null;
        double bestDist = Double.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                int nx = bx + dx, nz = bz + dz;
                long nk = pack(nx, nz);
                if (!basinToChain.containsKey(nk)) continue;
                RiverSegment seg = mainSegments.get(nk);
                if (seg == null || seg.type == RiverSegmentType.MOUTH) continue;
                double d = dist(cx, cz, basinCenterX(nx), basinCenterZ(nz));
                if (d < bestDist) { bestDist = d; attach = seg; }
            }
        }
        if (attach == null) return null;

        RiverNode attachPt = attach.path.get(attachPointIndex(bx, bz, attach));
        List<RiverSegment> segs = new ArrayList<>();
        // ★ 2026-08-15 R11 支流泉眼回归（治"支流 719 条爆炸 + up-steps 56%"）：
        //   支流 = **真泉眼驱动**——findSprings 取本 basin discharge 最高点（液滴
        //   汇聚 = 真汇水区）或谷底（fallback），mountainPath(upstream=true) 从
        //   接入点蜿蜒爬向泉眼；末端距泉眼 > 24wu = 寻路失败 → **Dijkstra 兜底**。
        //   旧实现：目标 = 法线方向 500wu 外占位（永不 reach → 走满 60-100 步自然
        //   终止）→ 支流无源无终、乱爬乱绕 = 用户"分支杂乱无章"直接元凶。
        double tribDepth = net.mainDepth() * net.tribDepthFrac();
        List<double[]> springs = findSprings(bx, bz);
        int springCount = Math.min(SPRING_MAX, springs.size());
        for (int si = 0; si < springCount; si++) {
            double[] spring = springs.get(si);
            // ★ 2026-08-15 R11 泉眼高度过滤（治"支流倒流爬坡 142 块"实锤）：
            //   fallback 泉眼 = basin 谷底最低 e 点，可能**低于**主河接入点 → 水
            //   从低处泉眼爬向高处接入点 = 倒流（探针 worst 0.105e @seg(16,31)：
            //   h 68.6→210.9，mountainPath 否决 >3 块/步 → 失败 → Dijkstra 兜底
            //   允许爬坡 → 23 块/步锯齿爬升）。泉眼必须高于接入点，否则跳过。
            if (net.eAt(spring[1], spring[2]) <= net.eAt(attachPt.x(), attachPt.z())) continue;
            // 接入点侧移（防与主河 0 距离重叠：起点在主河半宽外）
            double[] mdir = mainFlowDir(attach, attachPt);
            double nl = Math.hypot(mdir[0], mdir[1]);
            double nrmX = nl > 1e-9 ? -mdir[1] / nl : 1.0;
            double nrmZ = nl > 1e-9 ? mdir[0] / nl : 0.0;
            double side = hash01(bx, bz, 70 + si) < 0.5 ? -1.0 : 1.0;
            double sx = attachPt.x() + nrmX * side * Math.max(4.0, attach.width * 0.5);
            double sz = attachPt.z() + nrmZ * side * Math.max(4.0, attach.width * 0.5);
            int maxSteps = 40 + (int) (60 * hash01(bx, bz, 80 + si)); // 40-100 步 = 160-400wu
            List<RiverNode> mp = mountainPath(sx, sz, spring[1], spring[2], bx, bz,
                attachPt.waterSurfaceY(), attachPt.waterSurfaceY() - tribDepth,
                false, true, maxSteps);
            // 末端距泉眼 > 24wu = 未到达 → Dijkstra 兜底（保成功率）
            if (mp.size() >= TRIB_MIN_NODES) {
                RiverNode tail = mp.get(mp.size() - 1);
                if (dist(tail.x(), tail.z(), spring[1], spring[2]) > 24.0) {
                    mp = dijkstraToAttach(bx, bz, attach, spring);
                    if (mp == null || mp.size() < TRIB_MIN_NODES) continue;
                    statDij++;
                } else {
                    statNat++;
                }
            } else {
                mp = dijkstraToAttach(bx, bz, attach, spring);
                if (mp == null || mp.size() < TRIB_MIN_NODES) continue;
                statDij++;
            }
            // mountainPath/Dijkstra 输出 = 接入点→泉眼（下游→上游）；reverse = 泉眼→接入点
            // （buildTributarySegment 期望 path 顺序 = 上游→下游，surf 从接入点缓抬）
            java.util.Collections.reverse(mp);
            segs.add(buildTributarySegment(bx, bz, attach, mp, attachPt));
        }
        if (segs.isEmpty()) return null;

        List<RiverSegment> prev = tributaries.putIfAbsent(key, List.copyOf(segs));
        if (prev != null) return prev;
        // 二级分支（P0 密度）：胜出线程构建，随一级入缓存；loser 直接拿缓存
        buildSecondaries(segs, bx, bz);
        // 流量回填：接入主河段 discharge++
        RiverSegment cur = mainSegments.get(pack(attach.basinX, attach.basinZ));
        if (cur != null) {
            mainSegments.put(pack(attach.basinX, attach.basinZ),
                new RiverSegment(cur.type, cur.basinX, cur.basinZ, cur.path,
                    cur.upstreamBasinX, cur.upstreamBasinZ,
                    cur.downstreamBasinX, cur.downstreamBasinZ,
                    cur.surfaceLevel, cur.bedLevel, cur.width,
                    cur.discharge + segs.size()));
        }
        return segs;
    }

    /** 主河在接入点的切向（相邻路径点差归一化）。 */
    private static double[] mainFlowDir(RiverSegment attach, RiverNode attachPt) {
        List<RiverNode> p = attach.path;
        int idx = -1;
        for (int i = 0; i < p.size(); i++) {
            if (Math.abs(p.get(i).x() - attachPt.x()) < 1e-6
                && Math.abs(p.get(i).z() - attachPt.z()) < 1e-6) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return new double[]{1.0, 0.0};
        int i2 = Math.min(idx + 1, p.size() - 1);
        int i1 = Math.max(idx - 1, 0);
        double dx = p.get(i2).x() - p.get(i1).x();
        double dz = p.get(i2).z() - p.get(i1).z();
        double l = Math.hypot(dx, dz);
        if (l < 1e-9) return new double[]{1.0, 0.0};
        return new double[]{dx / l, dz / l};
    }

    /**
     * 泉眼：本 basin 网格内 <b>discharge 最高点</b>（液滴汇聚 = 真汇水区，DW drain 同义）
     * + 可选次高点（距最高 ≥ 32wu 才保留）。
     *
     * <p>★ 2026-08-14 两处修复：①曾取 e 最高 = 山脊 → 支流从主河爬到山顶横穿山坡
     * （用户截图"支流横穿山坡"根因）②discharge 全 0 的 basin（液滴未经过 = 无真汇水）
     * → <b>无泉眼无支流</b>（干地支流 = 假，用户"支流不自然"主因）。</p>
     */
    private List<double[]> findSprings(int bx, int bz) {
        RiverGrid g = net.gridFor(bx, bz);
        int n = g.n;
        // ★ 2026-08-15 R5 侵蚀全关修复：discharge 场（液滴流量累积）只在侵蚀开启时
        //   生成。侵蚀全关 → sampleDischargeCached 恒 0 → 旧逻辑 useDischarge=true
        //   恒成立 → maxKey=0 < 阈值 → 所有支流被杀光（用户"侵蚀全关测试也不正常"
        //   实锤根因）。fix = 先采样 discharge 全局 max：< 阈值 → **fallback 到 e 场
        //   谷底泉眼**（e 最低 = 山谷上游汇水，物理 = 溪源在谷底非山脊——用 e 最高
        //   会退回 2026-08-14 修掉的"支流爬山顶横穿山坡"旧 bug）。
        double maxDischarge = -Double.MAX_VALUE;
        if (dischargeSampler != null) {
            for (int iz = 0; iz <= n; iz++) {
                for (int ix = 0; ix <= n; ix++) {
                    maxDischarge = Math.max(maxDischarge, dischargeSampler.eAt(
                        bx * BASIN_SIZE + ix * net.gridSpacing(),
                        bz * BASIN_SIZE + iz * net.gridSpacing()));
                }
            }
        }
        boolean useDischarge = maxDischarge >= DISCHARGE_SPRING_THRESHOLD;
        // top3 泉眼（间距 ≥ SPRING_MIN_DIST；DW 分支密度缩比：1-2 → 3）
        double[] best = null, second = null, third = null;
        double minLandE = 0.02; // fallback 只取陆地（海洋谷底会选出海底最深点）
        for (int iz = 0; iz <= n; iz++) {
            for (int ix = 0; ix <= n; ix++) {
                double wx = bx * BASIN_SIZE + ix * net.gridSpacing();
                double wz = bz * BASIN_SIZE + iz * net.gridSpacing();
                double e = g.atNode(ix, iz);
                double key;
                if (useDischarge) {
                    key = dischargeSampler.eAt(wx, wz);
                } else {
                    if (e < minLandE) continue; // fallback 只陆地
                    key = -e; // 谷底优先（e 最低）
                }
                if (best == null || key > best[0]) {
                    third = second;
                    second = best;
                    best = new double[]{key, wx, wz};
                } else if (second == null || key > second[0]) {
                    third = second;
                    second = new double[]{key, wx, wz};
                } else if (third == null || key > third[0]) {
                    third = new double[]{key, wx, wz};
                }
            }
        }
        // useDischarge 时无真汇水（已由 maxDischarge 判断）→ 不可能再触发；fallback 无阈值
        List<double[]> out = new ArrayList<>();
        if (best != null) out.add(best);
        if (second != null && dist(best[1], best[2], second[1], second[2]) >= SPRING_MIN_DIST) {
            out.add(second);
        }
        if (third != null && second != null
            && dist(best[1], best[2], third[1], third[2]) >= SPRING_MIN_DIST
            && dist(second[1], second[2], third[1], third[2]) >= SPRING_MIN_DIST) {
            out.add(third);
        }
        // ★ 2026-08-15 R11 fallback 谷底上游泉眼：旧 = basin 最低 e 点（谷底**下游**，
        //   常低于主河接入点 → 支流倒流爬坡 142 块实锤 seg(16,31) h 68.6→210.9）。
        //   新 = 从谷底点沿"最低上坡邻居"回溯 8 格（= 沿谷底向上游走 32wu）→
        //   溪源 = 山谷上游汇聚点（仍贴谷底，不爬山脊；水顺流而下汇入主河）。
        if (!useDischarge) {
            int gs = net.gridSpacing();
            List<double[]> up = new ArrayList<>();
            for (double[] c : out) {
                double cx = c[1], cz = c[2];
                for (int s = 0; s < 8; s++) {
                    int ix = (int) Math.round((cx - bx * BASIN_SIZE) / gs);
                    int iz = (int) Math.round((cz - bz * BASIN_SIZE) / gs);
                    double curE = g.atNode(ix, iz);
                    double bestE = Double.MAX_VALUE, nx2 = cx, nz2 = cz;
                    int[] dx8 = {1, 1, 0, -1, -1, -1, 0, 1};
                    int[] dz8 = {0, 1, 1, 1, 0, -1, -1, -1};
                    for (int di = 0; di < 8; di++) {
                        int qx = ix + dx8[di], qz = iz + dz8[di];
                        if (qx < 0 || qz < 0 || qx > n || qz > n) continue;
                        double pe = g.atNode(qx, qz);
                        if (pe > curE && pe < bestE) { bestE = pe; nx2 = qx; nz2 = qz; }
                    }
                    if (bestE == Double.MAX_VALUE) break;
                    cx = bx * BASIN_SIZE + nx2 * gs;
                    cz = bz * BASIN_SIZE + nz2 * gs;
                }
                up.add(new double[]{0, cx, cz});
            }
            out = up;
        }
        return out;
    }

    /**
     * Dijkstra：接入点（attach 段出界点）→ 泉眼的最短上坡路径。
     * 域 = 本 basin + 接入 basin（1×2 或 2×2），网格间距 = riverGridSpacing。
     * 成本 = max(0, eN − eCur) × 2 + 1（上坡惩罚，对齐 Farseek 高度差成本）。
     * 确定性：PQ 只 push 严格更小（相等不更新）+ 固定 8 邻遍历序。
     */
    private List<RiverNode> dijkstraToAttach(int bx, int bz, RiverSegment attach, double[] spring) {
        int ax = attach.basinX, az = attach.basinZ;
        int minBx = Math.min(bx, ax), maxBx = Math.max(bx, ax);
        int minBz = Math.min(bz, az), maxBz = Math.max(bz, az);
        int spacing = net.gridSpacing();
        int nx = (maxBx - minBx + 1) * BASIN_SIZE / spacing + 1;
        int nz = (maxBz - minBz + 1) * BASIN_SIZE / spacing + 1;
        int ox = minBx * BASIN_SIZE, oz = minBz * BASIN_SIZE;

        // ★ 2026-08-15 R4：与 ensureTributary 同点接入（段内 30-70%），Y 形分叉
        RiverNode attachPt = attach.path.get(attachPointIndex(bx, bz, attach));
        int startIdx = nodeIndex(attachPt.x(), attachPt.z(), ox, oz, spacing, nx, nz);
        int goalIdx = nodeIndex(spring[1], spring[2], ox, oz, spacing, nx, nz);
        if (startIdx < 0 || goalIdx < 0) return null;

        double[] dist = new double[nx * nz];
        int[] prev = new int[nx * nz];
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(prev, -1);
        dist[startIdx] = 0;
        java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>(
            (a, b) -> Double.compare(a[0], b[0]));
        pq.add(new double[]{0, startIdx});

        while (!pq.isEmpty()) {
            double[] curE = pq.poll();
            int cur = (int) curE[1];
            if (cur == goalIdx) break;
            if (curE[0] > dist[cur]) continue;
            double curWorldX = ox + (cur % nx) * spacing;
            double curWorldZ = oz + (cur / nx) * spacing;
            double eCur = net.eAt(curWorldX, curWorldZ);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int ix = cur % nx + dx, iz = cur / nx + dz;
                    if (ix < 0 || ix >= nx || iz < 0 || iz >= nz) continue;
                    int nb = iz * nx + ix;
                    double eN = net.eAt(ox + ix * spacing, oz + iz * spacing);
                    // ★ 2026-08-14 支流 Dijkstra（Farseek TributaryBasin 共识）：
                    //   - 成本 = 上升量×6 + 1（上山惩罚），**下坡免费**（rise<0 → +0.2
                    //     仅防绕远）→ 路径自动沿谷底低地走（下山免费上山罚）
                    //   - 高度差 > 3 硬剪枝（RoadArchitect isSteep 同款）——曾只罚不剪
                    double hN = net.curve().heightFromE(eN);
                    double hC = net.curve().heightFromE(eCur);
                    double rise = hN - hC;
                    if (rise > 3.0) continue; // 陡升否决：每格（4wu）上升 >3 块 = 悬崖
                    double cost = rise > 0 ? rise * 6.0 + 1.0 : 0.2;
                    double nd = dist[cur] + cost;
                    if (nd < dist[nb]) {
                        dist[nb] = nd;
                        prev[nb] = cur;
                        pq.add(new double[]{nd, nb});
                    }
                }
            }
        }
        if (prev[goalIdx] < 0 && goalIdx != startIdx) return null;

        // 回溯：goal → start（泉眼 → 接入点），段 path 顺序 = 上游→下游
        List<RiverNode> rev = new ArrayList<>();
        for (int c = goalIdx; c >= 0; c = prev[c]) {
            rev.add(new RiverNode(ox + (c % nx) * spacing, oz + (c / nx) * spacing));
            if (c == startIdx) break;
        }
        java.util.Collections.reverse(rev);
        // ★ 平滑 + 抽稀：3 遍 3 点平均（去网格量化急转弯，端点保持）→ 等距抽稀 ≤24 点
        if (rev.size() > 4) {
            for (int pass = 0; pass < 3; pass++) {
                for (int i = 1; i < rev.size() - 1; i++) {
                    RiverNode a = rev.get(i - 1), b = rev.get(i), c = rev.get(i + 1);
                    rev.set(i, new RiverNode(
                        (a.x() + b.x() * 2 + c.x()) * 0.25,
                        (a.z() + b.z() * 2 + c.z()) * 0.25));
                }
            }
        }
        return thinPath(rev, 0, 0);
    }

    /** 世界坐标 → Dijkstra 域网格索引（域外返回 -1）。域可为矩形（nx≠nz）。 */
    private static int nodeIndex(double wx, double wz, int ox, int oz, int spacing, int nx, int nz) {
        int ix = (int) Math.floor((wx - ox) / spacing);
        int iz = (int) Math.floor((wz - oz) / spacing);
        if (ix < 0 || iz < 0 || ix >= nx || iz >= nz) return -1;
        return iz * nx + ix;
    }

    /**
     * 支流段装配：path（泉眼→接入点），水面逐节点抬升
     * （Farseek TributaryNode 公式：fallHeight = (上游地表传播值 − 下游水面)/2，≥3 才抬）。
     */
    private RiverSegment buildTributarySegment(int bx, int bz, RiverSegment attach,
                                               List<RiverNode> path, RiverNode attachPt) {
        int n = path.size();
        // 上游地表传播值（泉眼 → 下游取 min；接入端用主河水面，不参与链条）
        double[] maxSurfFromUp = new double[n];
        maxSurfFromUp[0] = groundY(path.get(0));
        for (int i = 1; i < n; i++) {
            maxSurfFromUp[i] = Math.min(maxSurfFromUp[i - 1], groundY(path.get(i)));
        }
        // 水面（下游 → 上游递推，DW 缓抬对齐：每节点最多 +4，上限地表−1，防淹没）
        // ★ 2026-08-14 节点水位缓抬（DW cy/terrace 机制，与主河一致）：从接入点
        //   （下游）向上游逐点抬升，每点 ≤ MAX_RISE_PER_NODE 块、≤ 路径点地形−0.5
        //   （平滑包络，不随地形剧烈起伏）。节点河床 = 节点水面 − 支流深。
        // ★ 2026-08-15 R4：水面起点 = 段内接入点水位（attachPt.waterSurfaceY()），
        //   非段头水位——接入点段内化后两者不同，用段头会错位（低处接高水面）。
        // ★ 2026-08-15 R9 支流水面去贴地（DW/Farseek terrace 包络）：水面从接入点
        //   缓抬、**不贴单点列高**——旧"≤ 地形−0.5"每点钳制 = 水面随微地形起伏 =
        //   水膜 = 用户"不像河"根因。正确语义 = Farseek 公式：fall = (上游地表传播值
        //   maxSurfFromUp − 下游水面)/2，≥ minFall 才抬。maxSurfFromUp 是上游方向
        //   的地表最小包络（平滑，非单点）→ 水面 = 平滑缓抬包络。地形差（地表高于
        //   水面 → 挖谷壁；低于水面 → 抬墙）由雕刻层（RiverCarver R9）吸收。
        double tribDepth = net.mainDepth() * net.tribDepthFrac();
        double[] surf = new double[n];
        double cy = attachPt.waterSurfaceY();
        for (int i = n - 1; i >= 0; i--) {
            double diff = maxSurfFromUp[i] - cy; // 上游地表传播 − 当前水面
            double rise = diff > 0 ? Math.min(diff, MAX_RISE_PER_NODE) : 0.0;
            cy = Math.max(cy, cy + rise); // 单调不降 + 缓抬（≤2）
            surf[i] = cy;
        }
        List<RiverNode> nodes = new ArrayList<>(n);
        // ★ 2026-08-15 R4 支流宽度 frac 0.6 → 0.35：旧值支流汇入处 4.8wu ≈ 主河
        //   （8wu）的 60%——视觉上与主河难分。0.35 = 2.8wu（主河 1/3），层级分明。
        double tribW = net.mainWidth() * Math.min(net.tribWidthFrac(), 0.35);
        double[] nw = new double[n];
        for (int i = 0; i < n; i++) {
            nodes.add(new RiverNode(path.get(i).x(), path.get(i).z(), surf[i], surf[i] - tribDepth));
            // ★ 2026-08-14 支流宽度 taper（DW segmentWidths 同款）：泉眼 35% → 汇流 100%
            double t = n > 1 ? (double) i / (n - 1) : 1.0;
            nw[i] = tribW * (0.35 + 0.65 * t);
        }
        RiverSegment seg = new RiverSegment(
            RiverSegmentType.TRIBUTARY, bx, bz, nodes,
            -1, -1, attach.basinX, attach.basinZ,
            surf[0], surf[n - 1] - tribDepth,
            tribW, nw, 1);
        return seg;
    }

    /**
     * 二级分支（DW 2nd degree 缩比版，2026-08-15 P0 河网密度改造）：
     * 每条一级支流从上游侧节点发射 2-4 条更细更短的支流（半宽 ×0.6、步数 12-25 =
     * 48-100wu），复用 {@link #mountainPath(double,double,double,double,int,int,double,double,boolean,boolean,int)}
     * upstream 上山寻路（等高线 + 蛇曲 + 峰值钳制，DW 分支同机制）。
     *
     * <p>确定性：发射索引/偏转角/步数全由 basin 坐标哈希决定（无全局随机流）；
     * 水面从发射节点（一级段节点水位）起缓抬（≤ 地形−0.5）；宽度 taper（源头 35%，
     * 与主河/一级同款）。cache 幂等收敛（putIfAbsent，构建纯函数）。</p>
     */
    private void buildSecondaries(List<RiverSegment> segs, int bx, int bz) {
        List<RiverSegment> out = new ArrayList<>();
        for (RiverSegment seg : segs) {
            List<RiverNode> p = seg.path;
            int n = p.size();
            if (n < 8) continue; // 一级太短，无可靠发射点
            // ★ 2026-08-15 R11 密度砍半（529 条二级 = "杂乱无章"元凶）：
            //   2-4 条 → 0-2 条（40% 概率 0 条），半宽 0.5 → 0.35（更细更短）
            if (hash01(bx, bz, 90) < 0.4) continue; // 40% 一级无二级
            int count = 1 + (hash01(bx, bz, 91) < 0.5 ? 0 : 1); // 1-2 条
            double secW = seg.width * SECONDARY_WIDTH_FRAC;
            double secDepth = Math.max(1.0, (net.mainDepth() * net.tribDepthFrac()) * SECONDARY_WIDTH_FRAC);
            int lastIdx = -1;
            // ★ 2026-08-15 P0 修正：发射点**整段均匀分布**（避开两端）——曾限上游半段
            //   （泉眼侧），但上游端已接近一级泉眼（汇水区高点），二级再向上无路可走
            //   （peakH−2 否决全杀 → 0 条二级）。DW 第一条分支反而靠 drain（下游）侧
            //   发射（minIdx+20 前），下游侧才有向山蔓延的空间。
            int limit = Math.max(4, n - 2);
            for (int k = 0; k < count; k++) {
                int idx = 1 + (int) (hash01(bx, bz, 100 + k) * Math.max(1, limit - 2));
                if (lastIdx >= 0 && Math.abs(idx - lastIdx) < 4) idx = Math.min(limit, idx + 4);
                lastIdx = idx;
                RiverNode em = p.get(idx);
                RiverNode up = p.get(Math.max(0, idx - 3)); // 上游切向基准（path 索引减 = 往泉眼）
                double dirX = em.x() - up.x(), dirZ = em.z() - up.z();
                double baseAng = Math.atan2(dirZ, dirX);
                // ★ 2026-08-15 R2：偏转 ±(0.7~1.1) rad → ±(0.3~0.6) rad。旧值 = DW
                //   分支角度（DW 分支从低地主河向两侧山坡大角度蔓延，地形平坦所以合法）；
                //   我们二级从已贴谷的一级支流发射，大角度 = 坡面乱切 = 用户"更假"主因。
                //   小偏转沿谷延伸自然分流（17-34°，8 步×4wu 后横向偏 10-18wu > 半宽）
                double angOff = 0.3 + hash01(bx, bz, 120 + k) * 0.3;
                if (hash01(bx, bz, 130 + k) < 0.5) angOff = -angOff;
                double ang = baseAng + angOff;
                // ★ 2026-08-15 R2 起点法向偏移（探针 boundary 17 根因）：二级从一级
                //   路径上发射（0 距离重叠）→ 重叠区最近段切换 → 水面跳变 17 块。
                //   发射点侧移 2×半宽（交替左右）→ 树形分叉起点，最近段稳定。
                double dl = Math.hypot(dirX, dirZ);
                double nrmX = dl > 1e-9 ? -dirZ / dl : 1.0;
                double nrmZ = dl > 1e-9 ? dirX / dl : 0.0;
                double side = hash01(bx, bz, 150 + k) < 0.5 ? -1.0 : 1.0;
                double off = Math.max(4.0, secW * 2.0);
                double emX = em.x() + nrmX * off * side;
                double emZ = em.z() + nrmZ * off * side;
                // ★ 2026-08-15 R11 二级真目标（治 up-steps 77% "倒流"假象）：
                //   旧 = 方向 120wu 占位（永不 reach）→ 走满自然终止 = 终点随机。
                //   新 = 沿方向扫描 3 档距离取**陆地 e 最高点**（山坡上游真高点）作
                //   目标 → 二级 = 从一级支流中段蜿蜒爬向山坡上游的小溪；
                //   寻路输出 reverse（发射点→高点 = 下游→上游 → 上游→下游）→
                //   path[0]=高点（上游）到 path[last]=发射点（下游），e 沿 path
                //   单调下降 → 探针 up-steps 归位（小溪从高处流下，非倒流）。
                double tx = emX, tz = emZ, bestTargetE = -Double.MAX_VALUE;
                for (double probe : new double[]{80.0, 120.0, 160.0}) {
                    double px = emX + Math.cos(ang) * probe;
                    double pz = emZ + Math.sin(ang) * probe;
                    double pe = net.eAt(px, pz);
                    if (pe > bestTargetE && groundYAt(px, pz) >= net.seaLevelY() + 2.0) {
                        bestTargetE = pe;
                        tx = px;
                        tz = pz;
                    }
                }
                int maxSteps = SECONDARY_STEPS_MIN
                    + (int) (hash01(bx, bz, 140 + k) * (SECONDARY_STEPS_MAX - SECONDARY_STEPS_MIN));
                List<RiverNode> path2 = mountainPath(emX, emZ, tx, tz, bx, bz,
                    em.waterSurfaceY(), em.waterSurfaceY() - secDepth,
                    false, true, maxSteps);
                if (path2.size() < SECONDARY_MIN_NODES) continue;
                java.util.Collections.reverse(path2); // 上游→下游（高点→发射点）
                // 水位沿水流方向单调缓降（上游起点 = 发射点水面+爬升余量，≤ 地形−0.5）
                // + 宽度 taper（源头 35% → 汇入 100%）
                List<RiverNode> nodes = new ArrayList<>(path2.size());
                double[] nw = new double[path2.size()];
                double cy = em.waterSurfaceY() + 4.0;
                for (int i = 0; i < path2.size(); i++) {
                    RiverNode q = path2.get(i);
                    double cap = groundYAt(q.x(), q.z()) - 0.5;
                    cy = Math.min(cy, cap); // 单调不升
                    nodes.add(new RiverNode(q.x(), q.z(), Math.max(cy, em.waterSurfaceY() - 0.5), cy - secDepth));
                    double t = path2.size() > 1 ? (double) i / (path2.size() - 1) : 1.0;
                    nw[i] = secW * (0.35 + 0.65 * t);
                }
                out.add(new RiverSegment(RiverSegmentType.TRIBUTARY, bx, bz, nodes,
                    -1, -1, seg.basinX, seg.basinZ,
                    nodes.get(0).waterSurfaceY(), nodes.get(nodes.size() - 1).bedY(),
                    secW, nw, 1));
            }
        }
        if (!out.isEmpty()) secondaries.putIfAbsent(pack(bx, bz), List.copyOf(out));
    }

    /** 二级缓存 prune（超上限删 1/8——重建便宜（无 Dijkstra），与 grids 同策略） */
    private void pruneSecondaries() {
        if (secondaries.size() > SECONDARY_CACHE_MAX) {
            var it = secondaries.keySet().iterator();
            int toRemove = Math.max(1, secondaries.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    /** 地表 Y（e → 世界高度） */
    private double groundY(RiverNode p) {
        return groundYAt(p.x(), p.z());
    }

    /**
     * 支流接入点索引（2026-08-15 R4）：主河段内 30-70% 处 hash 点（确定性）。
     * 段尾接入 = 支流与主河 0 距离重叠（最近段切换水面跳变 + 视觉打架）；
     * 段内接入 = 主河继续下坡、支流岔出上山 = 天然 Y 形分叉。
     */
    private static int attachPointIndex(int bx, int bz, RiverSegment attach) {
        List<RiverNode> ap = attach.path;
        int n = ap.size();
        if (n <= 2) return 0;
        return Math.max(1, Math.min(n - 2, (int) (n * (0.3 + 0.4 * hash01(bx, bz, 55)))));
    }

    /** 地表 Y（wu 坐标 → 世界高度） */
    private double groundYAt(double wx, double wz) {
        return net.curve().heightFromE(net.eAt(wx, wz));
    }

    /** 确定性坐标哈希 → [0,1)（MeanderingPath 抖动/蛇曲参数源，DW RandomSource 同义） */
    private static double hash01(int a, int b, int salt) {
        long h = (long) a * 0x9E3779B97F4A7C15L
            + (long) b * 0xC2B2AE3D27D4EB4FL
            + salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h & 0xFFFFFFFFFFFFFL) / (double) 0x10000000000000L;
    }

    /**
     * 主河段路径：in → RiverGrid 网格 D8 下坡追踪 → out。
     *
     * <p>对齐 DW 山地河"逐节点按地形高度寻路"：每步（4wu 网格点）取 8 邻 e 最低者
     * （严格更小 1e-9，固定遍历序先直线后对角 → 确定性）→ <b>e 严格下降 → 高度单调
     * 下降、路径贴谷线</b>（2026-08-14 动线修复：贝塞尔蛇曲甩出谷线横穿山脊，水面
     * 起伏不单调）。局部盆地（8 邻全 ≥ 自身）→ 朝下游直走兜底（自然湖）。</p>
     */
    /**
     * 主河段路径：in → 确定性几何蛇曲 → out（DW MeanderingPath 数学对齐）。
     *
     * <p>步骤（全部 basin 哈希确定性）：①中点垂直抖动（二分 1 次，±0.15·len）
     * ②sin meander（freq 0.5-2.5、amp 3-15wu、相位随机，amp 上限防自交）
     * ③7 遍 3 点平滑。不追踪高度 → 无爬坡/横漂；地形自适应 = 采样层坡度阻断
     * + 雕刻贴地（2026-08-14 动线修复二轮）。</p>
     */
    /** 路径抽稀上限（采样性能：closestOnPath 逐点遍历，点越多每列越慢——曾 8.8s/chunk） */
    private static final int MAX_PATH_POINTS = 24;
    /** 节点水位最大抬升（Y 块/4wu 步，DW terrace rise=min(diff,4) 对齐） */
    private static final double MAX_RISE_PER_NODE = 2.0;

    /**
     * 主河段内路径：4wu 细网格 D8 下坡追踪（TerraForged 范式，2026-08-15 R2）。
     *
     * <p><b>R2 根治"乱切地形"</b>：旧几何蛇曲（MeanderingPath 复刻）不读地形，
     * 128wu 段内 amp 3-15wu 横穿山脊/切坡 = 用户"河流与地形完全没关系"主因。
     * TerraForged 河流 = 地形场最陡下降链（cell 级 D8），天然贴谷线。此处同源：
     * 每步 4wu 取 8 邻 e 最低者（严格更小 1e-9、固定遍历序先直线后对角 →
     * 确定性；经 {@link RiverNetwork#eAt} 双线性插值 + 跨 tile 自动）→ 路径沿谷
     * 单调下坡。局部盆地（8 邻全 ≥ 自身）→ 朝 out 直走兜底（自然湖/平底）；
     * 近 out（≤4wu）→ snap 直连（段间共享端点无缝）。完成后 thinPath 抽稀
     * ≤24 点（采样性能铁律）。</p>
     */
    private List<RiverNode> valleyD8(double inX, double inZ, double outX, double outZ,
                                     int bx, int bz, double surf, double bed) {
        // ★ 2026-08-15 R2 步长：4/8/16wu 扫描后定 8wu（贴谷 18-22% 相当，avg 偏差
        //   ~1 块 = 4wu 插值噪声级；16wu 慢 2.6 倍无收益）。粗化平滑微噪声、跟随
        //   大尺度谷线（TerraForged cell 级 D8 语义）；返回前 4wu 中点插值细化。
        final double step = 8.0;
        final int maxSteps = 24; // 128wu / 8 + 兜底余量
        final double FLAT_EPS = 1e-4; // e 差 < 此值视为平坦（数值噪声级）
        // 投影约束（2026-08-15 R2）：候选投影不得比当前后退 > 0.5 步——禁钻入与
        // out 方向相反的旁支谷（贪心 D8 会为严格下坡绕 U 形，thinPath 抽稀后相邻点
        // 12wu 连线横切谷壁 = 探针 up-steps 30% 主因）。允许横向偏移（侧谷绕行不限）
        double dirX = outX - inX, dirZ = outZ - inZ;
        double dirLen = Math.hypot(dirX, dirZ);
        double ux = dirLen > 1e-9 ? dirX / dirLen : 1.0;
        double uz = dirLen > 1e-9 ? dirZ / dirLen : 0.0;
        double projIn = (inX * ux + inZ * uz);
        final int[] dxi = {1, -1, 0, 0, 1, 1, -1, -1};
        final int[] dzi = {0, 0, 1, -1, 1, -1, 1, -1};
        double cx = inX, cz = inZ;
        List<RiverNode> pts = new ArrayList<>(maxSteps + 2);
        pts.add(new RiverNode(cx, cz, surf, bed));
        for (int s = 0; s < maxSteps; s++) {
            double dxo = outX - cx, dzo = outZ - cz;
            double distOut = Math.hypot(dxo, dzo);
            if (distOut <= step) { // 近 out → 直连收尾（段间共享端点无缝）
                if (distOut > 0.001) pts.add(new RiverNode(outX, outZ, surf, bed));
                break;
            }
            double eCur = net.eAt(cx, cz);
            double projCur = cx * ux + cz * uz;
            double minE = Double.POSITIVE_INFINITY;
            int minK = -1;
            for (int k = 0; k < 8; k++) { // 固定遍历序：直线前、对角后（确定性）
                double nx = cx + dxi[k] * step, nz = cz + dzi[k] * step;
                if ((nx * ux + nz * uz) < projCur - step * 0.5) continue; // 投影后退过多
                double en = net.eAt(nx, nz);
                if (en < minE) { minE = en; minK = k; }
            }
            if (minK < 0) { // 全部被投影约束排除 → 只能直走（罕见防御）
                double l = Math.hypot(dxo, dzo);
                if (l < 1e-6) break;
                double bX = cx + dxo / l * step, bZ = cz + dzo / l * step;
                cx = bX; cz = bZ;
                pts.add(new RiverNode(cx, cz, surf, bed));
                continue;
            }
            double bX, bZ;
            if (minE < eCur - 1e-9) {
                // 正常：严格下坡（谷线）
                bX = cx + dxi[minK] * step;
                bZ = cz + dzi[minK] * step;
            } else if (eCur - minE <= FLAT_EPS) {
                // 平坦（平原/湖面）：朝 out 直走（原地打转防御）
                double l = Math.hypot(dxo, dzo);
                if (l < 1e-6) break;
                bX = cx + dxo / l * step;
                bZ = cz + dzo / l * step;
            } else {
                // ★ 盆地溢流（2026-08-15 R2 修正）：局部最低（侵蚀坑/洼地）→ 沿
                //   8 邻最低者离开（TerraForged sink→溢出口语义）。旧"朝 out 直走"
                //   把 4-8wu 小坑当湖、直走爬坡 → 河道"串珠湖" + e 跳升（探针 30%）。
                //   溢流方向 = 坑壁最低 = 出坑最短路径，1-2 步回到正常谷线。
                bX = cx + dxi[minK] * step;
                bZ = cz + dzi[minK] * step;
            }
            cx = bX;
            cz = bZ;
            pts.add(new RiverNode(cx, cz, surf, bed));
        }
        // 终点兜底（maxSteps 耗尽且仍离 out > 1 步）：直连 out（防段间 gap）
        double dxo = outX - cx, dzo = outZ - cz;
        if (Math.hypot(dxo, dzo) > step) {
            pts.add(new RiverNode(outX, outZ, surf, bed));
        }
        // 4wu 插值细化（步长 8wu 粗化后的平滑化）：相邻点 > 4.5wu 时补中点，直到
        // 相邻点 ≤ 4.5wu 或补满（保端点、保确定性——递归中点插值）
        List<RiverNode> fine = new ArrayList<>(pts.size() * 2 + 2);
        for (int i = 0; i < pts.size(); i++) {
            RiverNode a = pts.get(i);
            fine.add(a);
            if (i < pts.size() - 1) {
                RiverNode b = pts.get(i + 1);
                double d = Math.hypot(b.x() - a.x(), b.z() - a.z());
                if (d > 4.5) {
                    fine.add(new RiverNode((a.x() + b.x()) * 0.5, (a.z() + b.z()) * 0.5, surf, bed));
                }
            }
        }
        return thinPath(fine, surf, bed);
    }

    /** 等距抽稀：路径点 > MAX 时按弧长均匀取 ≤MAX 点（保端点；采样性能关键） */
    private static List<RiverNode> thinPath(List<RiverNode> pts, double surf, double bed) {
        int n = pts.size();
        if (n <= MAX_PATH_POINTS) return pts;
        double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            double dx = pts.get(i).x() - pts.get(i - 1).x();
            double dz = pts.get(i).z() - pts.get(i - 1).z();
            cum[i] = cum[i - 1] + Math.hypot(dx, dz);
        }
        double total = cum[n - 1];
        List<RiverNode> out = new ArrayList<>(MAX_PATH_POINTS);
        out.add(pts.get(0));
        for (int k = 1; k < MAX_PATH_POINTS - 1; k++) {
            double target = total * k / (MAX_PATH_POINTS - 1);
            int lo = 0, hi = n - 1;
            while (lo < hi - 1) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] < target) lo = mid; else hi = mid;
            }
            double segLen = cum[hi] - cum[lo];
            double t = segLen < 1e-9 ? 0 : (target - cum[lo]) / segLen;
            double x = pts.get(lo).x() + (pts.get(hi).x() - pts.get(lo).x()) * t;
            double z = pts.get(lo).z() + (pts.get(hi).z() - pts.get(lo).z()) * t;
            out.add(new RiverNode(x, z, surf, bed));
        }
        out.add(pts.get(n - 1));
        return out;
    }

    /**
     * 主河段路径：in → DW MountainRiverPath 原版寻路（等高线 + 蛇曲）→ out。
     *
     * <p><b>2026-08-14 终版（完整复刻 DW 评分，弃用 discharge 引导）</b>：discharge 场
     * 99% 格点非零但值 <2（梯度≈0）→ 方向引导恒 ≈ 朝 out 直线（"怎么修都没变化"根因）。
     * DW 原版无任何外力：角度自行演化（初始 = in→out），每步 9 角度候选
     * （0/±0.1/±0.4/±0.8/±1.2），评分 = <b>与蛇曲目标偏移的偏差</b>（-|offset-targetDelta|·7.5）
     * + <b>地形等高线</b>（diff≈0 → +15 最高分——河沿同高线蜿蜒）+ 缓坡分级。
     * 否决：爬坡 diff>0.5（真实河绝不爬坡）、悬崖 diff<-8、偏角 >117°、自交。</p>
     */
    private List<RiverNode> mountainPath(double inX, double inZ, double outX, double outZ,
                                         int bx, int bz, double surf, double bed) {
        return mountainPath(inX, inZ, outX, outZ, bx, bz, surf, bed, true, false);
    }

    /**
     * @param snapEnd true（主河）：死路 break 后强制连 out（末段直连）；
     *                false（支流）：死路直接返回已走路径，不连 out（调用方用 Dijkstra 兜底）
     * @param upstream true（支流）：从主河接入点出发蜿蜒<b>上山</b>到泉眼（DW 支流
     *                 branch 同款——drain → 上游，水位缓抬）；false（主河）：下坡。
     */
    private List<RiverNode> mountainPath(double inX, double inZ, double outX, double outZ,
                                         int bx, int bz, double surf, double bed,
                                         boolean snapEnd, boolean upstream) {
        return mountainPath(inX, inZ, outX, outZ, bx, bz, surf, bed, snapEnd, upstream, 200);
    }

    /**
     * @param maxSteps 最大步数（主河 200 = 800wu；二级分支 12-25 = 48-100wu，DW 宽度分档）
     */
    private List<RiverNode> mountainPath(double inX, double inZ, double outX, double outZ,
                                         int bx, int bz, double surf, double bed,
                                         boolean snapEnd, boolean upstream, int maxSteps) {
        double initialAngle = Math.atan2(outZ - inZ, outX - inX);
        double angle = initialAngle;
        double cyclePeriod = 16.0 + 12.0 * hash01(bx, bz, 0); // 16-28 步一周期
        double meanderPhase = hash01(bx, bz, 1) * Math.PI * 2.0;
        double winding = 0.6 + 0.5 * hash01(bx, bz, 2);      // DW 0.6-1.1
        double cx = inX, cz = inZ;
        List<RiverNode> pts = new ArrayList<>();
        pts.add(new RiverNode(cx, cz, surf, bed));
        double[] offsets = {0.0, 0.1, -0.1, 0.4, -0.4, 0.8, -0.8, 1.2, -1.2}; // DW 9 候选
        double stepSize = 4.0;
        boolean reached = false;
        double peakH = groundYAt(cx, cz); // 支流峰值钳制（upstream）
        double entranceCurve = Math.sin(meanderPhase) > 0.0 ? 0.4 : -0.4; // DW 入口曲线
        for (int step = 0; step < maxSteps; step++) {
            if (Math.hypot(outX - cx, outZ - cz) < stepSize) { reached = true; break; }
            double targetDelta = Math.sin(step * (2.0 * Math.PI / cyclePeriod) + meanderPhase) * winding;
            double bestScore = Double.NEGATIVE_INFINITY;
            double bestNx = cx, bestNz = cz, bestAngle = angle;
            boolean found = false;
            double terrainC = groundYAt(cx, cz);
            for (double offset : offsets) {
                double testAngle = angle + offset;
                // 偏角 > 117° 否决（DW）
                double diffAngle = Math.abs(testAngle - initialAngle);
                if (diffAngle > Math.PI) diffAngle = 2 * Math.PI - diffAngle;
                if (diffAngle > 2.0420352248333655) continue;
                double tx = cx + Math.cos(testAngle) * stepSize;
                double tz = cz + Math.sin(testAngle) * stepSize;
                // 自交（跳过最近 8 步，>20wu）
                if (pts.size() > 8) {
                    boolean collide = false;
                    for (int k = 0; k < pts.size() - 8; k++) {
                        RiverNode p = pts.get(k);
                        double dx = tx - p.x(), dz = tz - p.z();
                        if (dx * dx + dz * dz < 400.0) { collide = true; break; }
                    }
                    if (collide) continue;
                }
                double terrainN = groundYAt(tx, tz);
                double diff = terrainN - terrainC;
                // ★ 否决（DW 190 行）：悬崖（地形低于当前 8+）｜回海（后期、前方是海而当前陆地）
                if (diff < -8.0) continue;
                if (step > 15 && terrainN < net.seaLevelY() && terrainC >= net.seaLevelY() + 2) continue;
                if (upstream) {
                    // 支流（上山）：不陡升（>3 悬崖）、峰值钳制（不低于最高点 2 块）
                    if (diff > 3.0 || terrainN < peakH - 2.0) continue;
                } else {
                    // 主河（下坡）：绝不爬坡（diff>0.5）
                    if (diff > 0.5) continue;
                }
                // ★ DW 评分（201-207 行，逐行镜像）：
                //   蛇曲目标偏移偏差（前 6 步用入口曲线）
                double score = step < 6
                    ? -Math.abs(offset - entranceCurve) * 8.0
                    : -Math.abs(offset - targetDelta) * 7.5;
                //   地形等高线（diff=0 → 最高分；缓坡/缓升次之；陡变惩罚）
                if (diff == 0.0) score += 15.0;
                else if (upstream ? (diff > 0 && diff <= 2) : (diff < 0 && diff >= -2)) score += 5.0;
                else if (upstream ? (diff > 2 && diff <= 4) : (diff < -2 && diff >= -4)) score += 0.0;
                else if (upstream ? diff > 4 : diff < -4) score -= (Math.abs(diff) - 4.0) * 2.0;
                else score -= 5.0; // 反向（下游河上坡 / 上游河下坡）→ -5
                // ★ cardinalDist 惩罚 ×500（DW 202-207 行）——防对角直线路径的核心！
                //   角度接近 45° 倍数（对角）时重罚 → 路径必然蜿蜒
                if (step > 10) {
                    double normalizedAngle = ((testAngle % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
                    double angleToCardinal = Math.abs(normalizedAngle % 1.5707963267948966 - 0.7853981633974483);
                    double cardinalDist = 0.7853981633974483 - angleToCardinal;
                    score -= cardinalDist * 500.0;
                }
                // discharge 值吸引（走廊微调）
                if (dischargeSampler != null) score += dischargeSampler.eAt(tx, tz) * 4.0;
                if (score > bestScore) {
                    bestScore = score;
                    bestNx = tx; bestNz = tz; bestAngle = testAngle;
                    found = true;
                }
            }
            if (!found) {
                // 无可选方向（悬崖包围/死路）：终止（自然河到尽头 = 湖/海/泉眼）。
                // ★ snapEnd 只对主河（false → 支流不直连，防止直线横穿山坡）
                if (snapEnd) pts.add(new RiverNode(outX, outZ, surf, bed));
                return pts;
            }
            angle = bestAngle;
            cx = bestNx;
            cz = bestNz;
            peakH = Math.max(peakH, groundYAt(cx, cz)); // 峰值钳制更新
            pts.add(new RiverNode(cx, cz, surf, bed));
        }
        // ★ 到达才连 out（snapEnd=false 且未到达 → 不连——曾无条件直连 → 直线穿山，
        //   实测 maxRise 24.4 块 = 从山顶直线连到低处泉眼）
        if (reached || snapEnd) pts.add(new RiverNode(outX, outZ, surf, bed));
        // 平滑：2 遍 3 点平均（去锯齿，端点保持）
        int pc = pts.size();
        if (pc > 4) {
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 1; i < pc - 1; i++) {
                    RiverNode a = pts.get(i - 1), b = pts.get(i), c = pts.get(i + 1);
                    pts.set(i, new RiverNode(
                        (a.x() + b.x() * 2 + c.x()) * 0.25,
                        (a.z() + b.z() * 2 + c.z()) * 0.25, surf, bed));
                }
            }
        }
        // 等距抽稀（采样性能：每段 ≤24 点）
        return thinPath(pts, surf, bed);
    }



    private static double dist(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1, dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ===== 工具 =====

    private static double basinCenterX(int bx) { return (bx + 0.5) * BASIN_SIZE; }
    private static double basinCenterZ(int bz) { return (bz + 0.5) * BASIN_SIZE; }

    private static long pack(int x, int z) {
        return RiverNetwork.pack(x, z);
    }

    /** Java % 对负数取余为负 → 统一正模（主河行判定） */
    private static int mod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    /** 归一化向量（返回单位向量分量；零向量兜底 +x） */
    private static double[] norm(double x, double z) {
        double len = Math.sqrt(x * x + z * z);
        if (len < 1e-12) return new double[]{1.0, 0.0};
        return new double[]{x / len, z / len};
    }

    // ===== 内部访问器（RiverBuilderTributary 合并实现用） =====

    ConcurrentHashMap<Long, List<RiverSegment>> tributaries() { return tributaries; }
    ConcurrentHashMap<Long, RiverSegment> mainSegments() { return mainSegments; }
    ConcurrentHashMap<Long, Long> basinToChain() { return basinToChain; }
    RiverNetwork net() { return net; }
    static double bendAmp() { return BEND_AMP; }
    static double edgeFrac() { return EDGE_FRAC; }
    static double dijkstraUphillWeight() { return DIJKSTRA_UPHILL_WEIGHT; }
    static double basinCenterXStatic(int bx) { return basinCenterX(bx); }
    static double basinCenterZStatic(int bz) { return basinCenterZ(bz); }
}
