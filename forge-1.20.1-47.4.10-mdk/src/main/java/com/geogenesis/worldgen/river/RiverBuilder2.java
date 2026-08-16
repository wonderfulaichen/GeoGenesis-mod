package com.geogenesis.worldgen.river;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DW 分区河网构建器（2026-08-15 R13 完整对齐 DynamicWaters 11.1.2）。
 *
 * <p>核心 = {@code HydrologyManager.generateFractalForRegion} 的逐字移植：</p>
 * <ul>
 *   <li><b>每 640wu REGION 恰生成 1 条主河</b>（DW REGION_SIZE=640）：</li>
 *   <li>起点 = 本分区内随机点（rx*640+170+rand(172)）</li>
 *   <li>终点 = <b>东或南邻分区</b>内随机点（rx+1 或 rz+1，RandomSource 掷币）</li>
 *   <li>宽度 = 40% 概率 MAJOR（13.0）/ 60% BASE（默认 8 块 ÷HS）</li>
 *   <li>河型 = {@code MeanderingPath}：中点细分蛇曲（bisections=7 → 2^7=128 段折线，
 *       jitter 0.05-0.2×段长沿法线偏移，角度平滑防急弯）——<b>纯几何不依赖地形</b></li>
 *   <li>采样 = 3×3 REGION 邻域查最近河（归一化距离 d/width）</li>
 * </ul>
 *
 * <p>与旧版（R12 主行带 + 负缓存）的本质差异：<b>每分区独立恰 1 条河</b>，
 * 无"找陆地/找海/整带构建"逻辑 → 无跨 tile 时序、无负缓存陷阱
 * （游戏内"完全不显示"根因 = R12 builtRows 永久负缓存，DW 模型天然免疫）。</p>
 *
 * <p>构建纯函数：世界坐标 + 种子哈希 → 确定性；REGION 缓存 LRU。</p>
 */
public final class RiverBuilder2 {

    /** REGION 边长（wu，DW REGION_SIZE） */
    static final int REGION_SIZE = 640;
    /** GRID 边长（wu，DW 山地河 256 网格） */
    static final int GRID_SIZE = 256;
    /** 分区缓存上限 */
    private static final int REGION_CACHE_MAX = 512;
    /** 8 邻方向（k=0..7，D8 下坡采样；DX8[k]/DZ8[k]） */
    private static final int[] DX8 = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ8 = {0, 1, 1, 1, 0, -1, -1, -1};

    private final RiverNetwork net;
    /** REGION 缓存：region key → 该分区生成的所有河段（每区恰 1 条主河 + 支流） */
    private final ConcurrentHashMap<Long, RiverSegment> mainRegions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<RiverSegment>> mountainGrids = new ConcurrentHashMap<>();

    RiverBuilder2(RiverNetwork net) {
        this.net = net;
    }

    Map<Long, RiverSegment> mainSegments() {
        return mainRegions;
    }

    // ===== 对外：buildPlate（3×3 REGION 主河 + 5×5 GRID 山地河） =====

    RiverPlate buildPlate(int tileCX, int tileCZ) {
        Set<Integer> seen = new HashSet<>();
        List<RiverSegment> segs = new ArrayList<>();
        double wx = tileCX * RiverNetwork.BASIN_SIZE;
        double wz = tileCZ * RiverNetwork.BASIN_SIZE;
        int rx0 = floorDiv((int) Math.floor(wx), REGION_SIZE);
        int rz0 = floorDiv((int) Math.floor(wz), REGION_SIZE);
        for (int rz = rz0 - 1; rz <= rz0 + 1; rz++) {
            for (int rx = rx0 - 1; rx <= rx0 + 1; rx++) {
                RiverSegment seg = regionMainRiver(rx, rz);
                if (seen.add(seg.uid)) segs.add(seg);
            }
        }
        int gx0 = floorDiv((int) Math.floor(wx), GRID_SIZE);
        int gz0 = floorDiv((int) Math.floor(wz), GRID_SIZE);
        for (int gz = gz0 - 2; gz <= gz0 + 2; gz++) {
            for (int gx = gx0 - 2; gx <= gx0 + 2; gx++) {
                for (RiverSegment s : mountainGrid(gx, gz)) {
                    if (seen.add(s.uid)) segs.add(s);
                }
            }
        }
        return new RiverPlate(tileCX, tileCZ, List.copyOf(segs));
    }

    /** GRID 山地河（确定性生成 + 缓存）。 */
    private List<RiverSegment> mountainGrid(int gx, int gz) {
        long key = pack(gx, gz);
        List<RiverSegment> cached = mountainGrids.get(key);
        if (cached != null) return cached;
        List<RiverSegment> segs = generateMountainGrid(gx, gz);
        List<RiverSegment> prev = mountainGrids.putIfAbsent(key, segs);
        pruneMountainCaches(); // ★ R21f 内存审计
        return prev != null ? prev : segs;
    }

    /** ★ R21f 内存审计修复：mountainGrids/mainRegions 原无界（REGION_CACHE_MAX
     *  定义未用）→ 玩家移动访问无限 tile → OOM。与 RiverNetwork.plates 同款
     *  prune（大上限避免频繁重建——重建贵，R21f 后重建结果确定，安全）。 */
    private void pruneMountainCaches() {
        if (mountainGrids.size() > 2048) {
            var it = mountainGrids.keySet().iterator();
            int toRemove = Math.max(1, mountainGrids.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        if (mainRegions.size() > 2048) {
            var it = mainRegions.keySet().iterator();
            int toRemove = Math.max(1, mainRegions.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    /**
     * DW generateMountainPathsForRegion 逐字移植（每 256 GRID 22-30 条）：
     * - 源 1：现有山地河分支（距网格中心最近的河，取平缓节点 → 反流向 ±随机）
     * - 源 2：主河段分叉（主河距网格中心最近 → 段上随机点 → 反流向 ±随机）
     * - 源 3：GRID 中心独立源（随机方向，y = 海平面−1）
     * - 截断：与现有河 <30（非前 8 节点）→ validNodeCount；nodeCount≤15 丢弃
     * - 分支：validNodeCount−10>5 → 1 级分支（宽 2.5+1.0r）递归
     */
    private List<RiverSegment> generateMountainGrid(int gx, int gz) {
        long s = net.worldSeed() + gx * 93128712L + gz * 15289741L ^ 74213987123L;
        java.util.Random r = new java.util.Random(s);
        int count = 22 + r.nextInt(9);
        double gridX = gx * 256.0 + 128.0;
        double gridZ = gz * 256.0 + 128.0;
        double ySea = net.seaLevelY();
        // 主河池：5×5 REGION（DW 26 循环 rx±2 = 覆盖 ±1280wu，防支流越区撞主河）
        List<RiverSegment> mains = new ArrayList<>();
        int mrx0 = floorDiv(gx * GRID_SIZE, REGION_SIZE);
        int mrz0 = floorDiv(gz * GRID_SIZE, REGION_SIZE);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                mains.add(regionMainRiver(mrx0 + dx, mrz0 + dz));
            }
        }
        // 现有山地河：只收本 grid 内已生成（循环内顺序积累 = 确定）。
        // ★ R21f 审计修复：原"7×7 邻域已缓存"收集依赖懒构建时序——mountainGrids
        //   按访问顺序缓存，同一 grid 在不同时序下"已缓存"集合不同 → 支流
        //   汇入/排斥检查集合不同 → 河网非确定（结构性无缝破坏：跨线程/重载
        //   河流位置变化）。PL-RGA 板块局部语义：只与本板块河交互，不读邻板块。
        //   副作用（grid 边界处可能靠近邻 grid 河）由源头池边界缩进缓解。
        List<MountainPath> existing = new ArrayList<>();
        List<RiverSegment> out = new ArrayList<>();
        // ---- R21 源头池（PL-RGA _sourcePixels 思路移植） ----
        // 用户实锤：固定 22~30 次"从主河分叉"→ 密度与地形无关（高地低地一样密）、
        // 长度与到海距离无关（maxSteps 固定 150~240wu）→ 支流密且短。
        // PL-RGA：源头 = 地形最高点贪心 + 间距约束，河从源头下坡走到终点
        // （海/汇入/洼地），长度由源头到终点的地形距离决定。
        //   - 源头池：GRID 内 16wu 栅格采样 e>0.1（≈海平面以上的丘陵起点，
        //     排除沙洲/低地碎源头——可视化实锤海里沙洲也出支流），e 降序，
        //     间距 ≥96wu（≈GRID 38%，量级参照 PL-RGA MIN_SOURCE_SPACING 5 像素
        //     + 50 源/板块；用户实锤 48wu 太密 → 拉稀）
        //   - 数量：≤count（22~30），且随陆地面积收缩（池空即止）→ 密度 ∝ 地形
        //   ★ R21f：源头缩进边界 ≥32wu（PL-RGA RIVR_BORDER_DIST 思想）——
        //     去邻域汇入后，跨界支流在边界走廊相遇概率上升；源头远离边界
        //     后跨界距离变长，与邻域河相遇概率下降
        List<double[]> sources = new ArrayList<>();
        {
            double srcSpacing = 96.0, srcSpacingSq = srcSpacing * srcSpacing;
            List<double[]> cand = new ArrayList<>();
            for (double px = gx * 256.0 + 32.0; px < (gx + 1) * 256.0 - 32.0; px += 16.0) {
                for (double pz = gz * 256.0 + 32.0; pz < (gz + 1) * 256.0 - 32.0; pz += 16.0) {
                    double e = net.eAt(px, pz);
                    if (e > 0.1) cand.add(new double[]{e, px, pz});
                }
            }
            cand.sort((a, b) -> Double.compare(b[0], a[0]));
            for (double[] c : cand) {
                boolean tooClose = false;
                for (double[] sel : sources) {
                    double dx = c[1] - sel[0], dz = c[2] - sel[1];
                    if (dx * dx + dz * dz < srcSpacingSq) { tooClose = true; break; }
                }
                if (!tooClose) sources.add(new double[]{c[1], c[2]});
            }
        }
        for (int i = 0; i < count && i < sources.size(); i++) {
            double sx = sources.get(i)[0], sz = sources.get(i)[1];
            // 起始高度 = 源头地形（R19d 语义）
            double yStart = groundYAt(net, sx, sz);
            // 下坡起步方向（D8 最低邻；PL-RGA _downhillNeighbor 语义 + 小扰动防直线）
            int bi = -1;
            double bMin = Double.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                double ex = net.eAt(sx + DX8[k] * 4.0, sz + DZ8[k] * 4.0);
                if (ex < bMin) { bMin = ex; bi = k; }
            }
            double sDir = bi >= 0
                ? Math.atan2(DZ8[bi], DX8[bi]) + (r.nextDouble() - 0.5) * 0.3
                : r.nextDouble() * Math.PI * 2.0;
            // ---- 构造 ----
            // R21：源头溪流统一窄宽（PL-RGA 宽度与源类型无关；DW 分支宽语义）
            double wBlocks = 2.5 + r.nextDouble() * 1.0;
            MountainPath mp = new MountainPath(r, net, sx, sz, sDir,
                existing, mains, ySea, yStart, wBlocks);
            // ★ R20：汇入的短支流保留（它已连上干流，是树的一部分；非汇入仍按 DW 15 丢弃）
            // ★ R22：join 残片过滤——join 窗口放大后起步豁免刚结束（step≈8）即
            //   汇入的残片（<60wu）泛滥（trib 2568→35968 实锤）；汇入太近 = 
            //   无意义的短残片，丢弃
            if (mp.nodeCount <= 15 && mp.joinNodeCount < 0) continue;
            if (mp.joinNodeCount >= 0 && mp.nodeCount < 20) continue;
            // ★ R21f 审计：删除全部截断检查（30wu 山地河 + 13wu 主河）——
            //   探针实锤 endHigh=93/93（全部支流末端悬空断头）：
            //   1) R21 下坡递减后支流水面与干流同高 → join 条件
            //      `p2.segY[k] < curY−0.5` 恒假 → 汇入从不触发；
            //   2) 截断（30/13wu）先于汇入（6wu）抢跑 → 支流在距干流
            //      13~30wu 处被削断 → 末端悬空。
            //   汇入（6wu）+ 入海终止全权接管终止语义（PL-RGA join 范式）
            boolean cut = false;
            if (mp.validNodeCount - 10 <= 5 && mp.joinNodeCount < 0) continue; // 太短丢弃（DW 2032-2044；R20 汇入豁免）
            // ★ R20：汇入河不锥化（末端已接干流，锥化会断开连接点）
            if (!mp.hitMainRiver && mp.joinNodeCount < 0) mp.taperEnd(); // DW 1925-1937
            existing.add(mp);
            out.add(toSegment(mp, gx, gz));
            // ★ R22（用户实测"支流未形成树状"）：恢复 branchFrom 二级分支——
            //   子支流从母支流中段分叉（顺流向侧偏 → 侧谷 → 汇入母河/主河），
            //   形成 主河→支流→子支流 树状结构（DW 语义）
            branchFrom(mp, existing, mains, r, ySea, out, gx, gz);
        }
        return out;
    }

    /** 从母河分支出 1 级支流（DW 2046-2465：平缓节点 → 反流向 ±随机）。 */
    private void branchFrom(MountainPath parent, List<MountainPath> existing,
                            List<RiverSegment> mains, java.util.Random r,
                            double ySea, List<RiverSegment> out, int gx, int gz) {
        int lim = parent.validNodeCount - 10;
        if (lim <= 5) return;
        List<Integer> flat = new ArrayList<>();
        for (int i = 2; i < lim; i++) {
            if (Math.abs(parent.segY[i - 1] - parent.segY[i + 1]) <= 8) flat.add(i);
        }
        if (flat.isEmpty()) return;
        int tries = 15 + r.nextInt(10);
        for (int t = 0; t < tries && !flat.isEmpty(); t++) {
            int idx = flat.remove(r.nextInt(flat.size()));
            // ★ R22（树状结构修复）：子支流方向 = 母河流向 + **大角度侧偏
            //   （1.2~1.5 rad ≈ 69~86°，垂直母河）**——R22 首版顺流向 40~63° 侧偏
            //   仍有顺流分量 → 子支流追着母河下游几步即 join 回母河 = 短残片
            //   （trib 2568→35968 爆炸实锤）。垂直母河 → 直插侧谷独立下坡 →
            //   汇入主河/母河下游 = 树状汇入
            double dir = Math.atan2(parent.segZ[Math.min(idx + 1, parent.nodeCount - 1)] - parent.segZ[idx],
                parent.segX[Math.min(idx + 1, parent.nodeCount - 1)] - parent.segX[idx]);
            // ★ R19c 侧偏地形化（同 generateMountainGrid 源 1，见 branchDir）
            dir = branchDir(parent.segX[idx], parent.segZ[idx], dir, r);
            {
                // 加大侧偏幅度：branchDir 返回 ±0.7~1.1 → 调成 ±1.2~1.5
                double forward = Math.atan2(parent.segZ[Math.min(idx + 1, parent.nodeCount - 1)] - parent.segZ[idx],
                    parent.segX[Math.min(idx + 1, parent.nodeCount - 1)] - parent.segX[idx]);
                double side = dir - forward;
                while (side > Math.PI) side -= Math.PI * 2.0;
                while (side < -Math.PI) side += Math.PI * 2.0;
                double amp2 = side >= 0 ? 1.2 + r.nextDouble() * 0.3 : -(1.2 + r.nextDouble() * 0.3);
                dir = forward + amp2;
            }
            double wBlocks = 2.5 + r.nextDouble() * 1.0;
            // ★ R19d：起始高度 = 分支点原始地形（同 generateMountainGrid 源 1）
            MountainPath child = new MountainPath(r, net, parent.segX[idx], parent.segZ[idx], dir,
                existing, mains, ySea, groundYAt(net, parent.segX[idx], parent.segZ[idx]), wBlocks);
            if (child.nodeCount <= 15 && child.joinNodeCount < 0) continue;
            if (child.joinNodeCount >= 0 && child.nodeCount < 20) continue;
            // 截断检查（★ R20：汇入河限制到 joinNodeCount，防末端被截断破坏汇入）
            boolean cut = false;
            int childCutEnd = child.joinNodeCount >= 0 ? child.joinNodeCount : child.nodeCount;
            for (MountainPath p2 : existing) {
                for (int k = 5; k < childCutEnd; k++) {
                    if (p2.distTo(child.segX[k], child.segZ[k]) < 9.0) {
                        child.validNodeCount = Math.min(child.validNodeCount, k);
                        cut = true;
                        break;
                    }
                }
                if (cut) break;
            }
            for (int k = 0; k < childCutEnd; k++) {
                for (RiverSegment m : mains) {
                    double limW = m.width + 10.0;
                    if (mainIntersectSq(m, child.segX[k], child.segZ[k]) < limW * limW) {
                        child.validNodeCount = Math.min(child.validNodeCount, k);
                        break;
                    }
                }
            }
            if (child.validNodeCount - 10 <= 5 && child.joinNodeCount < 0) continue;
            if (!child.hitMainRiver && child.joinNodeCount < 0) child.taperEnd();
            existing.add(child);
            out.add(toSegment(child, gx, gz));
        }
    }

    /** MountainPath → RiverSegment（TRIBUTARY，path = 上游→下游：源头在前）。 */
    private RiverSegment toSegment(MountainPath mp, int gx, int gz) {
        int n = mp.validNodeCount;
        if (n < 2) n = mp.nodeCount;
        // DW path 顺序 = 下游（接入点）→ 上游（源头）；我们 path = 上游→下游
        List<RiverNode> path = new ArrayList<>(n);
        double[] nw = new double[n];
        for (int i = 0; i < n; i++) {
            int j = n - 1 - i; // reverse：源头在前
            double y = mp.segY[j];
            path.add(new RiverNode(mp.segX[j], mp.segZ[j], y + 1.0, y - 1.0));
            nw[i] = mp.segW[j];
        }
        return new RiverSegment(RiverSegmentType.TRIBUTARY,
            gx, gz, path, -1, -1, -1, -1,
            path.get(0).waterSurfaceY(), path.get(path.size() - 1).bedY(),
            mp.segW[n - 1], nw, 1, mp);
    }

    // ===== DW generateFractalForRegion 移植：每 REGION 恰 1 条主河 =====

    /**
     * DW HydrologyManager.generateFractalForRegion 逐字移植：
     * seed = WORLD_SEED + rx*341873128712 + rz*132897987541 ^ 84213987123
     * 起点 (rx*640+170+rand(172), rz*640+170+rand(172))
     * 终点分区 = 东/南邻（掷币），同公式
     * 宽度 = rand(100)<40 ? MAJOR(13.0) : BASE(默认 8.0 块 ÷HS)
     * MeanderingPath(rand, 7 bisections, x1,z1,x2,z2, width, isTributary=false)
     */
    private RiverSegment generateRegion(int rx, int rz) {
        // ★ DW WORLD_SEED 语义：河网每世界不同（世界种子 + REGION 坐标哈希）。
        //   （2026-08-15 R13c：曾用纯坐标哈希 → 三 seed 探针统计完全一致 81/50
        //     = 跨世界同一河网实锤；DW generateFractalForRegion 首行 = WORLD_SEED
        //    + rx*341873128712 + rz*132897987541 ^ 84213987123）
        long s1 = net.worldSeed() + rx * 341873128712L + rz * 132897987541L ^ 84213987123L;
        java.util.Random r1 = new java.util.Random(s1);
        double x1 = rx * 640.0 + 170.0 + r1.nextInt(172);
        double z1 = rz * 640.0 + 170.0 + r1.nextInt(172);
        // ★ R21f 审计结论：起点保持随机（R19 谷线流线从任意高处下坡 = 长河，
        //   探针实锤：谷底起点让 long rivers 4→2、avgLen 93wu）。源头消失
        //   的根因是 RiverCarver 的 fade 高度门控（R18 为"纯随机折线主河"
        //   设计），R19 流线天然在谷中 → fade 已删（见 RiverCarver）
        // DW 宽度 = 块语义（MAJOR=13.0/BASE=8.0 默认，配置可调）；我们的 mainWidth
        // 已是 wu（调用方 ÷HS）→ 直接以 mainWidth 为基准分档（MAJOR ×1.4）
        double wuWidth = net.mainWidth() * (r1.nextInt(100) < 40 ? 1.4 : 1.0);

        // 水面 = 海平面（DW 主河水面 = WORLD_SEA_LEVEL−1 处雕刻，采样用海平面）
        double surf = net.seaLevelY() - 1.0;
        double bed = surf - net.mainDepth();

        // ★ R22（用户实测"主河长度极短"）：恢复 DW 终点语义——主河从 REGION
        //   中心**定向走向 REGION 随机边界点**（长度 ~300~640wu；R21 谷线流线
        //   无定向 → 中心离海近即 50wu 夭折，len dist <50:29 实锤）。
        //   路径保留 R19 谷线贴合（60° 锥形内 D8 下坡 + 蛇曲）——纯直线会穿山
        //   （R13/R14 实锤），纯谷线不定向会短（R21 实锤）；折中：朝终点方向
        //   的谷线流。终止：到终点 16wu 内 / 入海（e≤0）/ 步数上限
        double ex, ez;
        {
            int edge = r1.nextInt(4);
            double minX = rx * 640.0 + 20.0, minZ = rz * 640.0 + 20.0;
            double maxX = (rx + 1) * 640.0 - 20.0, maxZ = (rz + 1) * 640.0 - 20.0;
            double ex1 = minX + r1.nextDouble() * (maxX - minX);
            double ez1 = minZ + r1.nextDouble() * (maxZ - minZ);
            if (edge == 0) { ex = maxX; ez = ez1; }
            else if (edge == 1) { ex = ex1; ez = maxZ; }
            else if (edge == 2) { ex = minX; ez = ez1; }
            else { ex = ex1; ez = minZ; }
        }
        double phase = r1.nextDouble() * Math.PI * 2.0;
        double freq = 0.04 + r1.nextDouble() * 0.03;
        int maxSteps = 260 + r1.nextInt(80);
        List<RiverNode> path = new ArrayList<>(maxSteps);
        double[] nw = new double[maxSteps];
        double curX = x1, curZ = z1;
        double curE = net.eAt(curX, curZ);
        double curDir = r1.nextDouble() * Math.PI * 2.0;
        int n = 1;
        path.add(new RiverNode(curX, curZ, surf, bed));
        nw[0] = wuWidth;
        // 海洋 REGION 起点（e≤0）→ 仅占位节点（无河道，采样天然跳过）
        for (int step = 0; step < maxSteps && n < maxSteps && curE > 0.0; step++) {
                double targetAng = Math.atan2(ez - curZ, ex - curX);
                // 8 邻：60° 锥形内找 e 最低（定向谷线下坡）
                double bestE = curE + 0.004;
                int bestDir = -1;
                for (int k = 0; k < 8; k++) {
                    double e = net.eAt(curX + DX8[k] * 4.0, curZ + DZ8[k] * 4.0);
                    if (e >= bestE) continue;
                    double ang = Math.atan2(DZ8[k], DX8[k]);
                    double d = ang - targetAng;
                    while (d > Math.PI) d -= Math.PI * 2.0;
                    while (d < -Math.PI) d += Math.PI * 2.0;
                    if (Math.abs(d) > 1.05) continue; // 60° 锥形
                    bestE = e; bestDir = k;
                }
                // 锥形内无下坡邻 → 全局最低邻（绕行出洼地/绕山）
                if (bestDir < 0) {
                    bestE = curE;
                    for (int k = 0; k < 8; k++) {
                        double e = net.eAt(curX + DX8[k] * 4.0, curZ + DZ8[k] * 4.0);
                        if (e < bestE) { bestE = e; bestDir = k; }
                    }
                }
                double nd;
                if (bestDir >= 0) {
                    // 下坡/绕行：细选平滑（粗选方向 ±0.35 rad 内 5 候选，取 e 最低）
                    // ★ R19b：细选中心加 0.25 rad 蛇曲摆动——纯 D8 细选在谷线
                    //   笔直时河成直线（可视化 100wu 垂直实锤）；缓坡时细选
                    //   随蛇曲中心偏转自然蜿蜒，陡坡时真谷线 e 明显低仍拉回
                    double base = Math.atan2(DZ8[bestDir], DX8[bestDir])
                        + Math.sin(step * freq * Math.PI * 2.0 + phase) * 0.25;
                    nd = base;
                    double be = bestE;
                    for (int k = -2; k <= 2; k++) {
                        double a = base + k * 0.175;
                        double e = net.eAt(curX + Math.cos(a) * 4.0, curZ + Math.sin(a) * 4.0);
                        if (e < be) { be = e; nd = a; }
                    }
                    curDir = nd;
                } else {
                    break; // 洼地无路可走（8 邻全更高）→ 终止
                }
                curX += Math.cos(nd) * 4.0;
                curZ += Math.sin(nd) * 4.0;
                curE = net.eAt(curX, curZ);
                path.add(new RiverNode(curX, curZ, surf, bed));
                nw[n] = wuWidth;
                n++;
                // R22：到终点 16wu 内终止（主河长度 = 中心→边界 ~300~640wu）
                double dEndX = ex - curX, dEndZ = ez - curZ;
                if (dEndX * dEndX + dEndZ * dEndZ < 256.0) break;
                if (curE <= 0.0) break; // 入海终止
        }
        // 组装 RiverSegment（点链 + 每点水面/床；DW 主河宽度恒定不 taper）
        double[] nw2 = n == maxSteps ? nw : Arrays.copyOf(nw, n);
        return new RiverSegment(RiverSegmentType.REACH,
            rx, rz, path, -1, -1, -1, -1, surf, bed, wuWidth, nw2, 1);
    }

    /** REGION 主河（缓存 + 懒生成，供 buildPlate/山地河收集）。 */
    RiverSegment regionMainRiver(int rx, int rz) {
        long key = pack(rx, rz);
        RiverSegment cached = mainRegions.get(key);
        if (cached != null) return cached;
        RiverSegment seg = generateRegion(rx, rz);
        RiverSegment prev = mainRegions.putIfAbsent(key, seg);
        pruneMountainCaches(); // ★ R21f 内存审计
        return prev != null ? prev : seg;
    }

    // ===== DW MountainRiverPath 完整移植（2026-08-15 R14） =====

    /** 地形地面 Y（e → Y；DW getCachedBaseHeight 语义；静态供 MountainPath 用） */
    static double groundYAt(RiverNetwork net, double wx, double wz) {
        double e = Math.max(0.0, net.eAt(wx, wz));
        return net.curve().heightFromE(e);
    }

    /** 山地河路径（DW MountainRiverPath 逐字：步进寻路 + 梯田 Y + 单调修正）。
     *  R22：private → 包可见（探针需读取 joinNodeCount 区分汇入河/断头河）。 */
    static final class MountainPath {
        static final double MAX_ANGLE = 2.0420352248333655;
        double[] segX, segZ;
        int[] segY;
        double[] segW;
        boolean hitMainRiver = false;
        // ★ R20 汇入（PL-RGA join 语义）：joinNodeCount ≥ 0 = 已汇入山地河
        //   （路径末端与干流节点重合，树状汇流）；joinedMain = 汇入主河
        int joinNodeCount = -1;
        boolean joinedMain = false;
        int nodeCount = 0;
        int validNodeCount;
        double minX, maxX, minZ, maxZ;
        double width;

        MountainPath(java.util.Random r, RiverNetwork net,
                     double sx, double sz, double sDir,
                     List<MountainPath> existingM, List<RiverSegment> existingMain,
                     double ySea, double yStart, double wBlocks) {
            // DW 宽度 = 块语义（分支 2.5-3.5、独立 4.0-5.5 块）：maxSteps 分档用块，
            // 雕刻宽度 ÷HS 转 wu（铁律：引擎坐标 = wu，块→wu 换算只在门面层）
            // ★ R21：统一宽松上限（PL-RGA MAX_RIVER_STEPS=160 语义）——
            //   长度由终点（海/汇入/洼地）的地形距离决定，上限只防死循环；
            //   R19e 的固定 150~240wu 上限正是"支流短"的根因之一
            int maxSteps = 200 + r.nextInt(100);
            this.width = wBlocks / net.horizontalScale();
            int terraceLen = 16 + r.nextInt(20);
            double phase = r.nextDouble() * Math.PI * 2.0;
            double meander = 0.6 + r.nextDouble() * 0.5;
            double[] cand = {0.0, 0.1, -0.1, 0.4, -0.4, 0.8, -0.8, 1.2, -1.2};
            List<Double> xs = new ArrayList<>(), zs = new ArrayList<>();
            List<Integer> ys = new ArrayList<>();
            double curX = sx, curZ = sz, curDir = sDir;
            // ★ 2026-08-15 修复（DW 字节码实锤）：curY 初始 = yStart（分叉节点水面），
            //   原 ySea 使支流水面从海平面起步 → 前 10 步寻路被吸向"地形≈海平面"的
            //   低地等高线 → 支流从母河分叉后冲向低地平原（"位置不合理"根因）。
            double curY = yStart;
            int stepsInTerrace = 0;
            xs.add(sx); zs.add(sz); ys.add((int) curY);
            for (int step = 0; step < maxSteps; step++) {
                // DW 39：minDist = min(100, 主河近−width, 山地河近)
                // ★ R21f 性能：包围盒预筛（bbox 字段早已算好，之前未用 →
                //   每候选×每河×逐节点距离 = 探针 1m48s 主因）
                double minDist = 100.0;
                for (RiverSegment m : existingMain) {
                    if (curX < m.minX - 100 || curX > m.maxX + 100
                        || curZ < m.minZ - 100 || curZ > m.maxZ + 100) continue;
                    minDist = Math.min(minDist, Math.sqrt(mainIntersectSq(m, curX, curZ)) - m.width);
                }
                for (MountainPath p2 : existingM) {
                    if (curX < p2.minX - 100 || curX > p2.maxX + 100
                        || curZ < p2.minZ - 100 || curZ > p2.maxZ + 100) continue;
                    minDist = Math.min(minDist, p2.distTo(curX, curZ));
                }
                double s1 = Math.max(0.0, Math.min(1.0, (minDist - 35.0) / 40.0));
                double snake = Math.sin(step * Math.PI * 2.0 / terraceLen + phase)
                    * meander * Math.max(0.35, s1);
                // ★ R22（对照 DW 源码）：恢复 DW 蛇曲幅度——DW MountainRiverPath
                //   442-503：snake = sin(i·2π/slope + phase)·base·max(0.35, s1)，
                //   base=0.6~1.1 rad、s1=(minDist−35)/40 随距河距离 0.35~1.0 衰减
                //   （远离河道自由蜿蜒，靠近河道受约束）。R21f 的 ±0.15 钳制
                //   把支流变成直筒（用户实测"动线不正常"实锤）。仅保留 ±1.2
                //   兜底防极端摆动
                snake = Math.max(-1.2, Math.min(1.2, snake));
                double dirJitter = (r.nextDouble() - 0.5) * 0.2;
                int nAngle = 0, nDist = 0, nIhit = 0, nSelf = 0, nMhit = 0, nTerr = 0;
                double bestScore = -Double.MAX_VALUE;
                double bestX = curX, bestZ = curZ, bestDir = curDir, bestTerr = curY;
                boolean found = false;
                boolean joined = false;       // R20 汇入山地河
                boolean joinedMain = false;   // R20 汇入主河
                boolean hitSea = false;       // R21 入海终止
                for (double off : cand) {
                    double nd = off == 0 ? curDir
                        : (Math.abs(off) <= 0.15 ? curDir + off : curDir + dirJitter + off);
                    // DW 725-740：与**初始方向**角差 > 2.042 拒绝。
                    // ★ R21f：参考改为上一步方向（curDir）——源头下坡模型下
                    //   谷线连续转弯，初始方向锁扇区会让支流在山谷弯道全灭
                    //   （a=2 断头实锤）；连续转弯允许 + self 检查防折返
                    double ad = Math.abs(nd - curDir);
                    while (ad > Math.PI) ad -= Math.PI * 2.0;
                    while (ad < -Math.PI) ad += Math.PI * 2.0;
                    if (Math.abs(ad) > MAX_ANGLE) { nAngle++; continue; }
                    double nx = curX + Math.cos(nd) * 3.0;
                    double nz = curZ + Math.sin(nd) * 3.0;
                    double sd = Math.hypot(nx - sx, nz - sz);
                    if (sd > 600.0) { nDist++; continue; }
                    // ★ R20 汇入（PL-RGA join 语义）：候选点距现有山地河节点
                    //   ≤6wu 且其水面更低 → 直接接受并终止路径 = 汇入干流成树。
                    //   "靠近干流"从失败（排斥）变为成功（汇入）——前 5 轮修阈值
                    //   都在排斥范式内，支流仍互相残杀（每 grid 0~3 条）；参考
                    //   Streams/PL-RGA 后实锤：其河网密度靠汇入而非排斥
                    if (step >= 8) { // 起步期（<8 步）不汇入，先离开母河
                        for (MountainPath p2 : existingM) {
                            if (nx < p2.minX - 6 || nx > p2.maxX + 6
                                || nz < p2.minZ - 6 || nz > p2.maxZ + 6) continue;
                            for (int k = 0; k < p2.nodeCount; k++) {
                                double ddx = nx - p2.segX[k], ddz = nz - p2.segZ[k];
                                // ★ R22：join 窗口 6wu → 10wu（用户实测"支流未与
                                //   主河连接"——6wu 窗口在低地谷底（主河宽 5~6wu）
                                //   里几乎碰不到干流节点；10wu 让支流下到谷底即汇入）
                                if (ddx * ddx + ddz * ddz <= 100.0
                                    && p2.segY[k] < curY) {
                                    joined = true;
                                    bestX = nx; bestZ = nz; bestDir = nd;
                                    bestTerr = p2.segY[k]; // 接干流水面，防水位断差
                                    found = true;
                                    break;
                                }
                            }
                            if (joined) break;
                        }
                    }
                    if (joined) break; // 汇入命中：接受该候选，退出候选循环
                    // DW 806-931：山地河冲突（起点在河边 10wu 内且 step<8 →
                    // 只跳过该河不拒绝 = 起步豁免）。★ R20：汇入接管 ≤6wu 后，
                    // 排斥圈只防平行重叠（8wu ≈ 河宽）
                    boolean iHit = false;
                    for (MountainPath p2 : existingM) {
                        if (nx < p2.minX - 8 || nx > p2.maxX + 8
                            || nz < p2.minZ - 8 || nz > p2.maxZ + 8) continue;
                        double oldD = p2.distTo(curX, curZ);
                        if (p2.distTo(nx, nz) < 8.0) {
                            if (oldD < 10.0 && step < 8) continue; // DW 907-919 起步豁免
                            iHit = true;
                            break;
                        }
                    }
                    if (iHit) { nIhit++; continue; }
                    // DW 939-1044：自交（step≥15，**前 size−10 个节点** <625²——
                    // 防绕回起点方向打转；检查早期路径而非最近节点，最近节点必近）
                    if (step >= 15) {
                        boolean self = false;
                        for (int i = 0; i < xs.size() - 10; i++) {
                            double dx = nx - xs.get(i), dz = nz - zs.get(i);
                            // ★ R22：self 半径 6 → 12wu——DW 蛇曲幅度恢复后
                            //   （±0.21~1.1 rad）路径弯道更大，6wu 半径挡不住
                            //   折返重叠；12wu = 4 步，防急折返 + 防自交
                            if (dx * dx + dz * dz < 144.0) { self = true; break; }
                        }
                        if (self) { nSelf++; continue; }
                    }
                    // ★ R20 汇入主河（候选距主河 ≤ w+1 且 step≥8）→ 汇流终止。
                    //   原 mHit（w+5 ≈ 9wu 硬拒）使支流停在主河 5~9wu 环形带外，
                    //   永远进不了 hitMainRiver 的 5wu 终止区 → 主河汇流几乎不发生
                    //   （hitMain 诊断仅豁免期内 6 条实锤）。汇入优先于硬拒
                    if (step >= 8) {
                        for (RiverSegment m : existingMain) {
                            // ★ R22：主河 join 窗口 w+1 → 10wu（用户实测"支流未与
                            //   主河连接"——低地谷底主河宽 5~6wu，w+1≈5.5wu 窗口
                            //   支流下到谷底仍难命中主河折线；10wu 让支流贴谷底
                            //   即汇入）。汇入末端水面 = 主河水面（R21f）
                            double lim = 10.0;
                            if (nx < m.minX - lim || nx > m.maxX + lim
                                || nz < m.minZ - lim || nz > m.maxZ + lim) continue;
                            if (mainIntersectSq(m, nx, nz) < lim * lim) {
                                joinedMain = true;
                                bestX = nx; bestZ = nz; bestDir = nd;
                                bestTerr = ySea - 1.0;
                                found = true;
                                break;
                            }
                        }
                    }
                    if (joinedMain) break; // 汇流命中：接受该候选，退出候选循环
                    // ★ R21f 审计：删除主河排斥 mHit（DW w+5 ≈ 8wu 硬拒）——
                    //   8wu 排斥带挡在 4wu 汇入窗口外 → 支流停在 8wu 环带外
                    //   永远进不了汇入窗口 → 断头（m=7 minDist=5.9 实锤）。
                    //   PL-RGA 语义：靠近主河 = 汇入，无排斥带（iHit 8wu 防
                    //   山地河重叠保留）
                    // ★ R21f 审计：删除悬崖否决（DW <curY−8 为"低地→高地"方向
                    //   设计：下坡候选 = 悬崖下 = 不该去）。R21 源头下坡模型下
                    //   下坡 = 正常流向——悬崖否决与下坡评分自相矛盾：下坡候选
                    //   （得分最高）全被否决 → 只能选山脊平缓候选 → curY 不递减
                    //   → 末端水面=源头高度断头（maxRise=0 + endHigh 实锤）。
                    //   水从悬崖流下 = 瀑布（自然），curY 递减一步到位无悬河
                    double g = groundYAt(net, nx, nz);
                    // ★ R21f：海阈值 e≤0 → e≤0.02（海边滩涂 e=0.007 微地形 →
                    //   下坡方向打转兜圈 → self 全灭断头，s=9 @y=64 实锤）
                    if (step >= 15 && net.eAt(nx, nz) <= 0.02) {
                        bestX = nx; bestZ = nz; bestDir = nd;
                        // ★ R21f 审计：入海末端水面 = 海平面（原 = 地形 63~70，
                        //   RiverCarver skip 判据 origHeight < waterY−3 → 入海口
                        //   断流消失 = "悬河"实锤）
                        bestTerr = ySea - 1.0;
                        found = true;
                        hitSea = true;
                        break;
                    }
                    // ★ R19d：陡坡硬否决（爬升 >12 块否决）。R19 初版 >6 实锤
                    //   过苛：支流从主河（水面 62）向谷壁爬升，山脉地形谷壁
                    //   普遍高 10~40 块 → 全拒 → 支流 22~30 尝试全灭
                    //   （[RIVER-DIAG] short=N 实锤）；12 块 = 悬崖级才否决
                    if (g - curY > 12.0) { nTerr++; continue; }
                    // DW 评分（蜿蜒惩罚 + 地形 + 爬坡方向惩罚）。
                    // ★ R21f：删主河吸引项（原 ±180 分压过下坡项——山脊顶被吸向
                    //   主河方向（可能上坡）→ 折返绕圈 self 全灭，s=9 实锤）。
                    //   R21 源头模型：支流靠地形下坡 + 汇入窗口（6wu）与主河互动，
                    //   无需"靠近主河加分"
                    double score = 0;
                    if (step < 6) score -= Math.abs(off - Math.sin(phase) * 0.4) * 8.0;
                    else score -= Math.abs(off - snake) * 7.5;
                    // ★ R21f 审计：地形评分方向反转——DW 语义"从低地向高地走"
                    //   （diff==0 平缓 +15、缓上坡 +5、**下坡 −5 惩罚**）与 R21
                    //   源头下坡模型（源头高地 → 下游低地）相反 → 支流沿山脊
                    //   等高线走、curY 不递减、末端水面=源头高度、断头
                    //   （maxRise=0 + endHigh=104/104 实锤）。
                    //   反转：下坡（diff<0）得分随落差增长（水往低处流），
                    //   平缓次之，爬升惩罚（悬崖否决 12 块兜底）
                    double diff = g - curY;
                    if (step < 10 && yStart > ySea) {
                        // 起步期（源头山脊）：立即下坡优先（原 +15 封顶 → 山脊
                        //   平缓区绕圈 self 全灭；陡降高分快速冲出山脊）
                        if (diff <= 0) score += 25.0 + Math.min(25.0, -diff * 3.0);
                        else score -= diff * 20.0;
                    } else {
                        if (diff <= 0) score += 15.0 + Math.min(25.0, -diff * 3.0);
                        else if (diff > 4) score -= (diff - 4) * 4.0;
                        else score -= diff * 2.0;
                        // 0<diff≤2 → 缓上坡轻微罚（保留蛇曲缓坡段）
                    }
                    // ★ R19：cardinalDist ×500 仅平坦/缓坡（diff≤2）生效——
                    //   原陡坡也罚对角方向 → 强制水平/垂直 = 垂直等高线直爬切山
                    if (diff > 0 && diff <= 2.0 && step > 10) {
                        double dn = ((nd % (Math.PI * 2.0)) + Math.PI * 2.0) % (Math.PI * 2.0);
                        double d45 = Math.abs(dn % (Math.PI / 2.0) - Math.PI / 4.0);
                        score -= (Math.PI / 4.0 - d45) * 500.0;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = nx; bestZ = nz; bestDir = nd;
                        bestTerr = g;
                        found = true;
                    }
                }
                if (!found) {
                    // ★ R21f：低地断头自动视为入海（海边滩涂/洼地微地形 → 兜圈
                    //   self 断头，s=9 @y=64 实锤）——curY ≤ 海平面+3 即入海终止，
                    //   末端水面对齐海平面（探针 end-align 归位）
                    if (curY < ySea + 3.0) {
                        curY = ySea - 1.0;
                        ys.set(ys.size() - 1, (int) curY);
                        hitSea = true;
                    }
                    break;
                }
                curDir = bestDir;
                stepsInTerrace++;
                // DW 1693-1799：Y 梯田爬升（terrace 满且地形高 2+ → +min(4,diff)；
                // 过半且高 1+ → +1；封顶地形，不下降低于 curY）
                // ★ R19d：地形高 4+ 时立即爬升（不等 terraceLen）——原等 16~36
                //   步才 +4，curY 跟不上陡坡谷壁 → 硬否决全拒 → 支流全灭
                double newY = curY;
                if (bestTerr - curY > 4.0) {
                    newY = curY + Math.min(4.0, bestTerr - curY);
                    stepsInTerrace = 0;
                    terraceLen = 16 + r.nextInt(20);
                } else if (stepsInTerrace >= terraceLen && bestTerr > curY + 2.0) {
                    newY = curY + Math.min(4.0, bestTerr - curY);
                    stepsInTerrace = 0;
                    terraceLen = 16 + r.nextInt(20);
                } else if (bestTerr > curY + 1.0 && stepsInTerrace > terraceLen / 2) {
                    newY = curY + 1.0;
                }
                if (newY > bestTerr && bestTerr > curY) newY = bestTerr;
                // ★ R21：下坡递减——DW 梯田只升不降是"从海爬向山"语义；
                //   R19d 起点改为源头高度后必须配套递减，否则 curY 卡在
                //   高地 → 低地地形 g < curY−8 全拒 → 支流低地边缘断流
                //   （可视化实锤）。水面跟随谷底（水往低处流）
                if (bestTerr < curY) newY = bestTerr;
                curY = newY;
                curX = bestX; curZ = bestZ;
                xs.add(bestX); zs.add(bestZ); ys.add((int) curY);
                // ★ R21：入海终止（outlet_sea，PL-RGA 语义）
                if (hitSea) break;
                // ★ R20：汇入终止（末端已接干流节点/主河，路径到此为止成树）
                if (joined || joinedMain) {
                    this.joinedMain = joinedMain;
                    joinNodeCount = ys.size();
                    break;
                }
                // DW 1893-1988：step>5 后主河相交（< (w+1)²）→ hitMainRiver 终止
                if (step > 5) {
                    boolean hit = false;
                    for (RiverSegment m : existingMain) {
                        double lim = m.width + 1.0;
                        if (curX < m.minX - lim || curX > m.maxX + lim
                            || curZ < m.minZ - lim || curZ > m.maxZ + lim) continue;
                        if (mainIntersectSq(m, curX, curZ) < lim * lim) { hit = true; break; }
                    }
                    if (hit) { hitMainRiver = true; break; }
                }
            }
            // ★ R21f 审计：删除 DW Y 单调修正（1991-2111）——该循环无条件
            //   ys.set(i, acc) 且 acc 单调不降（"水面只升不降" = DW 从低地
            //   爬向山的语义）。R21 源头下坡模型下，它把下坡水面全部抹平为
            //   起点高度（yStart=125 → yEnd=125 实锤：步级 curY 正常降到 63，
            //   组装后全被覆盖回 125）→ 支流水面恒源头高度 → 末端悬空断头。
            //   curY 已是谷底跟随（下坡递减），无需二次平滑
            nodeCount = ys.size();
            segX = new double[nodeCount];
            segZ = new double[nodeCount];
            segY = new int[nodeCount];
            segW = new double[nodeCount];
            double mnX = Double.MAX_VALUE, mxX = -Double.MAX_VALUE;
            double mnZ = Double.MAX_VALUE, mxZ = -Double.MAX_VALUE;
            for (int i = 0; i < nodeCount; i++) {
                segX[i] = xs.get(i);
                segZ[i] = zs.get(i);
                segY[i] = ys.get(i);
                // ★ R21f 审计：宽度 taper 方向修正——DW 支流从下游往上游生成
                //   （segX[0]=下游），taper 公式 w×(1−t)+0.35w×t 使其"下游宽"。
                //   我们 R21 源头池从源头（高地）往下游生成（segX[0]=源头），
                //   原公式 = 源头宽下游窄 = 反自然（源头粗河、入海口细到消失
                //   ——"支流断流"帮凶之一）。反转：源头窄 0.35w → 下游宽 w
                double t = nodeCount > 1 ? (double) i / (nodeCount - 1) : 1.0;
                segW[i] = Math.max(0.5, width * 0.35) * (1.0 - t) + width * t;
                mnX = Math.min(mnX, segX[i]); mxX = Math.max(mxX, segX[i]);
                mnZ = Math.min(mnZ, segZ[i]); mxZ = Math.max(mxZ, segZ[i]);
            }
            validNodeCount = nodeCount;
            minX = mnX; maxX = mxX; minZ = mnZ; maxZ = mxZ;
        }

        /** DW taperEnd：末两节点宽度收窄（未汇入主河的溪流源头渐隐）。
         *  ★ R21f 审计：收窄端修正——DW 从下游生成（末节点=上游源头），
         *    我们从源头生成（segX[0]=源头）→ 收窄 segW[0..1]（源头端） */
        void taperEnd() {
            if (nodeCount > 3) {
                segW[0] = 0.0;
                segW[1] *= 0.5;
            }
        }

        /** 点到路径最近距离（DW getClosestNodeData.distance 语义，节点级）。 */
        double distTo(double x, double z) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < nodeCount; i++) {
                double dx = segX[i] - x, dz = segZ[i] - z;
                best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
            }
            return best;
        }
    }

    /** 点-折线最近距离²（主河采样用；DW intersectDistanceSq 语义） */
    static double mainIntersectSq(RiverSegment seg, double wx, double wz) {
        List<RiverNode> p = seg.path;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < p.size() - 1; i++) {
            RiverNode a = p.get(i), b = p.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-12 ? 0
                : Math.max(0, Math.min(1, ((wx - a.x()) * dx + (wz - a.z()) * dz) / len2));
            double nx = a.x() + dx * t, nz = a.z() + dz * t;
            double dd = (wx - nx) * (wx - nx) + (wz - nz) * (wz - nz);
            if (dd < best) best = dd;
        }
        return best;
    }

    // ===== DW MountainRiverPath 完整移植（2026-08-15 R14） =====

    /**
     * DW MeanderingPath.bisect 逐字移植（2026-08-15 R14 修正）：
     * 1. 中点细分 ×bisections（每中点沿法线偏移 jitter×len×0.5）
     * 2. 角度平滑 ×3（cos<0.34 时向中点内插 min(0.7, 1−(1+cos)/1.34)）
     * 3. 正弦蛇曲 pass（amp=0.5+rand×2.0，lenFrac=8+rand×12 或 3+rand×5，
     *    phase=rand×2π，freq=total×0.8/(amp×2π) clamp lenFrac，edgeFade=smoothstep
     *    0.15/0.85，法线 = i±3 窗口段向量）
     * 4. Catmull-Rom 平滑 ×7（(prev+2cur+next)×0.25）
     */
    static double[] meanderBisect(java.util.Random r, int bisections,
                                  double x1, double z1, double x2, double z2) {
        double[] seg = new double[]{x1, z1, x2, z2};
        // ---- 1. 中点细分 ----
        for (int b = 0; b < bisections; b++) {
            double[] out = new double[seg.length * 2 - 2];
            out[0] = seg[0];
            out[1] = seg[1];
            int oi = 2;
            for (int i = 0; i < seg.length - 2; i += 2) {
                double ax = seg[i], az = seg[i + 1];
                double bx = seg[i + 2], bz = seg[i + 3];
                double mx = (ax + bx) * 0.5;
                double mz = (az + bz) * 0.5;
                double dx = bx - ax, dz = bz - az;
                double len = Math.sqrt(dx * dx + dz * dz);
                // DW randomJitter：0.15×(2r−1) + (j≥0 ? +0.05 : −0.05) ∈ [−0.1, 0.2]
                double j = 2.0 * r.nextDouble() - 1.0;
                double jitter = 0.15 * j + (j >= 0 ? 0.05 : -0.05);
                if (len > 0.001) {
                    double nx = -dz / len, nz = dx / len;
                    double off = jitter * len * 0.5;
                    out[oi] = mx + nx * off;
                    out[oi + 1] = mz + nz * off;
                } else {
                    out[oi] = mx;
                    out[oi + 1] = mz;
                }
                out[oi + 2] = bx;
                out[oi + 3] = bz;
                oi += 4;
            }
            seg = out;
        }
        // ---- 2. 角度平滑 ×3（DW：cos<0.34 时 k = min(0.7, 1−(1+cos)/1.34)） ----
        int n = seg.length / 2;
        for (int iter = 0; iter < 3; iter++) {
            for (int i = 1; i < n - 1; i++) {
                double ax = seg[(i - 1) * 2], az = seg[(i - 1) * 2 + 1];
                double bx = seg[i * 2], bz = seg[i * 2 + 1];
                double cx = seg[(i + 1) * 2], cz = seg[(i + 1) * 2 + 1];
                double ux = bx - ax, uz = bz - az;
                double vx = cx - bx, vz = cz - bz;
                double ul = Math.sqrt(ux * ux + uz * uz);
                double vl = Math.sqrt(vx * vx + vz * vz);
                if (ul < 0.001 || vl < 0.001) continue;
                double cos = (ux * vx + uz * vz) / (ul * vl);
                if (cos < 0.34) {
                    double mx = (ax + cx) * 0.5;
                    double mz = (az + cz) * 0.5;
                    double k = Math.min(0.7, 1.0 - (1.0 + cos) / 1.34);
                    seg[i * 2] = bx + (mx - bx) * k;
                    seg[i * 2 + 1] = bz + (mz - bz) * k;
                }
            }
        }
        // ---- 3. 正弦蛇曲 pass（DW 546-1063 逐字） ----
        double amp0 = 0.5 + r.nextDouble() * 2.0;
        double lenFrac = r.nextDouble() < 0.5 ? 8.0 + r.nextDouble() * 12.0 : 3.0 + r.nextDouble() * 5.0;
        double phase = r.nextDouble() * Math.PI * 2.0;
        // 累积弧长
        double[] cum = new double[n];
        double total = 0;
        for (int i = 1; i < n; i++) {
            double dx = seg[i * 2] - seg[(i - 1) * 2];
            double dz = seg[i * 2 + 1] - seg[(i - 1) * 2 + 1];
            total += Math.sqrt(dx * dx + dz * dz);
            cum[i] = total;
        }
        double freq = total * 0.8 / (amp0 * Math.PI * 2.0);
        lenFrac = Math.min(lenFrac, freq);
        // 端到端拷贝（首尾点不动）
        double[] out = new double[seg.length];
        out[0] = seg[0];
        out[1] = seg[1];
        out[seg.length - 2] = seg[seg.length - 2];
        out[seg.length - 1] = seg[seg.length - 1];
        for (int i = 1; i < n - 1; i++) {
            // edgeFade：smoothstep 0.15/0.85 窗口
            double s = total > 0 ? cum[i] / total : 0;
            double fade;
            if (s < 0.15) fade = s / 0.15;
            else if (s > 0.85) fade = (1.0 - s) / 0.15;
            else fade = 1.0;
            fade = fade * fade * (fade * 6.0 - 15.0) + 10.0 * fade * fade * fade; // smoothstep
            // 法线：i±3 窗口段向量（clamp 到范围）
            int i0 = Math.max(0, i - 3) * 2;
            int i1 = Math.min(n - 1, i + 3) * 2;
            double vx = seg[i1] - seg[i0];
            double vz = seg[i1 + 1] - seg[i0 + 1];
            double vl = Math.sqrt(vx * vx + vz * vz);
            double nx = 0, nz = 0;
            if (vl > 0.001) {
                nx = -vz / vl;
                nz = vx / vl;
            }
            double off = Math.sin(s * amp0 * Math.PI * 2.0 + phase) * lenFrac * fade;
            out[i * 2] = seg[i * 2] + nx * off;
            out[i * 2 + 1] = seg[i * 2 + 1] + nz * off;
        }
        // ---- 4. Catmull-Rom 平滑 ×7（DW 1066-1219 逐字：(prev+2cur+next)×0.25） ----
        for (int iter = 0; iter < 7; iter++) {
            double[] sm = new double[out.length];
            sm[0] = out[0];
            sm[1] = out[1];
            sm[out.length - 2] = out[out.length - 2];
            sm[out.length - 1] = out[out.length - 1];
            for (int i = 2; i < out.length - 2; i += 2) {
                sm[i] = (out[i - 2] + 2.0 * out[i] + out[i + 2]) * 0.25;
                sm[i + 1] = (out[i - 1] + 2.0 * out[i + 1] + out[i + 3]) * 0.25;
            }
            out = sm;
        }
        return out;
    }

    // ===== 工具 =====

    /**
     * ★ R19c：支流分叉方向——反流向（flowDir）为基准 + 侧偏（0.7~1.1 rad）保证
     * 离开主河；偏转侧由地形决定（垂直反流向 ±0.9 rad 采样 8wu，e 低侧 =
     * 谷壁缓侧/小谷入口）。
     * <p>R19 初版"8 邻最低谷线方向"在分叉点 = 沿谷底 = 与主河平行 → 寻路第一步
     * 就撞主河冲突拒绝 → 支流 20wu 内夭折（2026-08-16 可视化 0 支流实锤）。</p>
     */
    private double branchDir(double sx, double sz, double flowDir, java.util.Random r) {
        double dl = wrapAngle(flowDir + 0.9);
        double dr = wrapAngle(flowDir - 0.9);
        double el = net.eAt(sx + Math.cos(dl) * 8.0, sz + Math.sin(dl) * 8.0);
        double er = net.eAt(sx + Math.cos(dr) * 8.0, sz + Math.sin(dr) * 8.0);
        return flowDir + (el <= er ? 1.0 : -1.0) * (0.7 + r.nextDouble() * 0.4);
    }

    /** 角度归一化到 [−π, π)。 */
    private static double wrapAngle(double a) {
        while (a > Math.PI) a -= Math.PI * 2.0;
        while (a < -Math.PI) a += Math.PI * 2.0;
        return a;
    }

    /** 世界坐标 → REGION 坐标（floorDiv 负坐标安全）。 */
    static int floorDiv(int v, int d) {
        int q = v / d;
        return (v % d != 0 && ((v ^ d) < 0)) ? q - 1 : q;
    }

    private static long pack(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }
}
