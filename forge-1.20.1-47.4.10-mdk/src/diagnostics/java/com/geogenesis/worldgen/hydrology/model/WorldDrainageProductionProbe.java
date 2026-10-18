package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * <h1>世界坐标排水（{@link WorldDrainage}）生产链【只读】判据</h1>
 * <p>被测：{@code ModelHydrologyProvider.worldForProbe(rx,rz)} / {@code worldDrainage()}（同包直调）。
 * ①两个显式 owner (-2,0)/(-2,1) ⇒ {@code FINE_SOLVES} Δ 恰 2（无邻区细管线扇出），世界边/待决/成本落 CSV；②跨 z=512
 * 共享跨缝边集两侧逐位相同且非空，端点+中间整数列在真实 {@code columnsFor} 下逐列【真湿】（{@code fillWater &&
 * waterSurfaceY > carvedGroundY}，湖列按真湿计入）；③既有三源点（G42/G44 权威：-776,376 / -712,128 / -676,400）
 * 用独立 {@link WorldDrainage} 追到真实极小 ⇒ 无格跳/无重复、沿途 admission 单调（首次准入后不再丢），如实报告原源
 * 是否仍被准入；④逐边展开历史 raw 追踪上的全部真实整数列（含中间、含干列）导出，断言的是
 * <b>【历史 raw 中实际仍发布的子集】</b>逐列真湿 —— <b>该子集 ≠ 全后缀</b>（旧口径只对照"仍发布"的那几条边），
 * 真正的<b>全周期</b>是 ⑤ {@link #secLifecycle()}（真实世界后继穿盆溢流到海，每条边都在当前区域图里）；
 * ⑥两目标盆 published member {@code from} 数 vs 总 members（断言支持闭包真正删去非支持 member，几何 131072）；
 * ⑦冷逆序 fresh gen/provider（先 (-2,1) 再 (-2,0)）与当前两区：边集 / worldWater 全部 Water / 固定代表点
 * （跨缝、两盆 lip 内+外、(-680,16) 回水）真实列逐位一致
 * （只做冷反序，不重做真 3 并发 —— 合成并发已由 {@code WorldBasinRoutingProbe} 覆盖）。
 * ⑧显式 {@code basin-depth x1 z1 [x2 z2 ...]} 模式（<b>只读证据，除参数校验外不作任何判定</b>、不在常规 suite 内）：
 * 从给定 root 逐层 {@code resolve(131072)} → path 末点 → {@code routing.terminal}，最多 {@link #DEPTH_LAYERS} 层，
 * 遇 sea / loop / 非 settled 几何 / 非盆底 / 求解异常即停，逐层与逐 root 落
 * {@code basin-depth.csv} / {@code basin-depth-roots.csv} —— 用于回答 schema89 仅剩
 * 历史 {@code (-2,-2) 18 unknown = basin depth budget}（旧上限 32 层）；当前上限见 {@link WorldBasinRouting#CHAIN_LIMIT}。
 * NOT VERIFIED：不证全球唯一极小/整网连通/入海；只测两个 owner 与三个源点；不改生产、不放宽 0.5 填水门槛。</p>
 */
public final class WorldDrainageProductionProbe {
    static final long SEED = 9139912035078620160L;
    /** 三个既有已发布源点，逐位保留（G42 rollingSites / G44 权威；第三 = -676,400）。 */
    static final int[][] ORIGINAL3 = {{-776, 376}, {-712, 128}, {-676, 400}}, OWNERS = {{-2, 0}, {-2, 1}};
    static final int Z_SEAM = 512, TRACE_BUDGET = 20000;
    /** 稳定点序（与生产 {@code WorldBasinRouting.ORDER} 同口径）。 */
    static final Comparator<WorldDrainage.Point> ORDER =
            Comparator.comparingInt(WorldDrainage.Point::x).thenComparingInt(WorldDrainage.Point::z);

    /** 世界格步（生产默认；探针不覆盖 modelStep）。 */
    static final int STEP = Integer.getInteger("geogenesis.modelStep", 4);
    /** 生产同档盆体几何预算（{@code ModelHydrologyProvider.solveHier} 的 {@code WorldBasinRouting} 131072）。 */
    static final int GEOM_BUDGET = 131072;
    /** {@code basin-depth} 只读逐层上限；生产限制独立读取 {@code WorldBasinRouting.CHAIN_LIMIT}。 */
    static final int DEPTH_LAYERS = 128;
    static final Path OUT_ROOT = Path.of("build", "world-drainage-production");
    static int failures;
    static final StringBuilder LOG = new StringBuilder();
    static final Map<Long, List<HydrologyBlockCarvedColumn>> COLS = new HashMap<>();
    static final WorldDrainage.Result[] WORLD = new WorldDrainage.Result[2];
    /** 两目标盆的溢口 {innerX,innerZ,outerX,outerZ} —— 由 {@link #secBasinMembers()} 填入，供冷逆序代表点复用。 */
    static final List<int[]> LIPS = new ArrayList<>();
    static TerrainParams tp; static CellGenerator gen; static ModelHydrologyProvider prov; static Path out;

    private WorldDrainageProductionProbe() { }

    public static void main(String[] args) throws Exception {
        // ★ 开关必须在触碰 ModelHydrologyProvider 之前（HIER/SKELETON_TRACE/SHARED_LAKE 均 static final）。
        System.setProperty("geogenesis.hierarchicalHydrology", "true");
        System.setProperty("geogenesis.skeletonTrace", "true");
        System.setProperty("geogenesis.sharedLakeDomains", "true");
        System.setProperty("geogenesis.hierStrictContract", "false");
        System.setProperty("geogenesis.drainageAudit", "true");
        out = OUT_ROOT.resolve(String.valueOf(System.currentTimeMillis()));
        Files.createDirectories(out);
        chk(loadConfig(), "用户 toml 复制为唯一工作副本并 cfg.load()（原件只读、绝不写回）",
                "原件: " + Path.of("run", "config", "geogenesis-common.toml").toAbsolutePath());
        tp = params(); gen = newGen(tp, SEED);
        prov = new ModelHydrologyProvider(gen, SEED); prov.clear();
        log("=== WorldDrainageProductionProbe ===");
        log("seed=" + SEED + " hier=" + ModelHydrologyProvider.hierarchical() + " shared="
                + ModelHydrologyProvider.sharedActive() + " skeleton=" + ModelHydrologyProvider.SKELETON_TRACE
                + " step=" + STEP + " sea=" + tp.seaLevel() + " zSeam=" + Z_SEAM + " out=" + out);
        if(args.length>0 && args[0].equals("geometry")) {
            secGeometry(new WorldDrainage.Point(Integer.parseInt(args[1]),Integer.parseInt(args[2])),Integer.parseInt(args[3]));
        } else if(args.length>0 && args[0].equals("region-water")) {
            secRegionWater(Integer.parseInt(args[1]),Integer.parseInt(args[2]),args.length>3?Integer.parseInt(args[3]):32768);
        } else if(args.length>0 && args[0].equals("basin-flow")) {
            secWorldBasinFlow();
        } else if(args.length>0 && args[0].equals("membership")) {
            secMembership(new WorldDrainage.Point(Integer.parseInt(args[1]),Integer.parseInt(args[2])));
        } else if(args.length>0 && args[0].equals("basin-depth")) {
            secBasinDepth(args);
        } else { secOwners(); secAudit(); secSeam(); secSources(); secBasins(); secLifecycle(); secBasinMembers();
            secColdReverse(); }
        log("\n" + HierarchicalHydrology.Diag.report());
        log("\n判定：" + (failures == 0 ? "ALL PASS" : "★ FAIL（fail=" + failures + "）"));
        Files.writeString(out.resolve("metrics.txt"), LOG.toString());
        System.out.print(LOG);
        System.out.println("OUT=" + out.toAbsolutePath());
        System.exit(failures == 0 ? 0 : 1);
    }

    /** ① 两个显式 owner 世界解：FINE_SOLVES Δ=2，无邻区细管线；边/待决/成本落 CSV。 */
    private static void secOwners() {
        log("\n=== [owner] worldForProbe(-2,0)/(-2,1)：FINE_SOLVES Δ=2，无邻区细管线 ===");
        prov.clear();
        long f0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(), s0 = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        WORLD[0] = prov.worldForProbe(OWNERS[0][0], OWNERS[0][1]);
        WORLD[1] = prov.worldForProbe(OWNERS[1][0], OWNERS[1][1]);
        long df = HierarchicalHydrology.Diag.FINE_SOLVES.get() - f0, ds = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get() - s0;
        chk(df == 2, "两个显式 owner 世界解 ⇒ FINE_SOLVES Δ=2（无邻区细管线扇出）", "Δ=" + df);
        chk(ds == 0, "世界解自身不消费共享域细解（SHARED_FINE_SOLVES Δ=0）", "Δ=" + ds);
        chk(WORLD[0] != null && WORLD[1] != null, "worldForProbe 返回非 null 世界解", "null");
        var e = new StringBuilder("ownerRx,ownerRz,fromX,fromZ,toX,toZ\n");
        var p = new StringBuilder("ownerRx,ownerRz,x,z\n");
        var c = new StringBuilder("ownerRx,ownerRz,edges,pendingBasins,sampledNodes,terrainSamples\n");
        for (int i = 0; i < OWNERS.length; i++) {
            int rx = OWNERS[i][0], rz = OWNERS[i][1];
            for (var g : WORLD[i].edges()) row(e, rx, rz, g.from().x(), g.from().z(), g.to().x(), g.to().z());
            for (var q : WORLD[i].pendingBasins()) row(p, rx, rz, q.x(), q.z());
            row(c, rx, rz, WORLD[i].edges().size(), WORLD[i].pendingBasins().size(), WORLD[i].sampledNodes(), WORLD[i].terrainSamples());
            log("    owner(" + rx + "," + rz + ") edges=" + WORLD[i].edges().size() + " pending=" + WORLD[i].pendingBasins().size()
                    + " nodes=" + WORLD[i].sampledNodes() + " terrainSamples=" + WORLD[i].terrainSamples());
        }
        write("world-edges.csv", e); write("world-pending.csv", p); write("world-cost.csv", c);
    }

    private static void secAudit() {
        for (int i = 0; i < OWNERS.length; i++) {
            int rx = OWNERS[i][0], rz = OWNERS[i][1];
            var world = WORLD[i]; var audit = prov.drainageForProbe(rx, rz);
            int tract = ModelHydrologyProvider.hierParams().tract();
            var pipe = prov.pipeForProbe(rx * tract + tract / 2, rz * tract + tract / 2);
            int ox = rx * tract + tract / 2 - (pipe.nx() - 1) / 2 * STEP;
            int oz = rz * tract + tract / 2 - (pipe.nz() - 1) / 2 * STEP;
            var reaches = prov.reachesForProbe(rx * tract + tract / 2, rz * tract + tract / 2);
            chk(audit != null && !audit.filtered() && audit.segments().size() == world.edges().size(),
                    "实际世界排水快照完整且逐边对应 owner=" + rx + "," + rz, "missing/mismatch");
            var altered = new ArrayList<>(reaches); altered.remove(altered.size() - 1);
            chk(ProductionDrainageAudit.captureWorld(world, pipe, ox, oz, tract, prov.worldDrainage(), altered).filtered(),
                    "负控：删除一个实际发布段必须被审计识别", "false clean");
            boolean noCycles = audit.chains().stream().noneMatch(c -> c.terminal() == ProductionDrainageAudit.Terminal.CYCLE
                    || c.terminal() == ProductionDrainageAudit.Terminal.BROKEN_JOIN);
            chk(noCycles, "实际世界快照无环或断裂JOIN owner=" + rx + "," + rz, "cycle/join");
        }
        auditTerminalFixture();
    }

    private static void auditTerminalFixture() {
        int tract = ModelHydrologyProvider.hierParams().tract();
        var pipe = prov.pipeForProbe(-2 * tract + tract / 2, tract / 2);
        var a = new WorldDrainage.Point(-STEP, 0); var b = new WorldDrainage.Point(0, 0);
        var world = new WorldDrainage.Result(List.of(new WorldDrainage.Edge(a, b)), List.of(), 0, 0);
        var seaGraph = new WorldDrainage(STEP, 1, 0, (x, z) -> -1);
        var sea = ProductionDrainageAudit.captureWorld(world, pipe, -tract / 2, 0, tract, seaGraph, List.of());
        chk(sea.chains().get(0).terminal() == ProductionDrainageAudit.Terminal.SEA,
                "跨缝入海终点不能被external覆盖", sea.chains().toString());
        var basinGraph = new WorldDrainage(STEP, 1, -100, (x, z) -> x * x + z * z);
        var pending = ProductionDrainageAudit.captureWorld(world, pipe, -tract / 2, 0, tract, basinGraph, List.of());
        chk(pending.chains().get(0).terminal() == ProductionDrainageAudit.Terminal.UNRESOLVED,
                "跨缝盆底仍待决，不假作外部可续流或闭湖", pending.chains().toString());
    }

    /** ② 跨 z=512 共享跨缝边集 + 端点/中间整数列【真湿】（fillWater 且 surface>ground）。 */
    private static void secSeam() {
        log("\n=== [seam] z=" + Z_SEAM + " 共享跨缝边集两侧一致 + 端点/中间整数列真湿（surface>ground） ===");
        Set<WorldDrainage.Edge> a = crossing(WORLD[0]), b = crossing(WORLD[1]);
        Set<WorldDrainage.Edge> onlyA = new LinkedHashSet<>(a), onlyB = new LinkedHashSet<>(b);
        onlyA.removeAll(b); onlyB.removeAll(a);
        log("    crossA=" + a.size() + " crossB=" + b.size() + " onlyA=" + onlyA.size() + " onlyB=" + onlyB.size());
        chk(!a.isEmpty() && !b.isEmpty(), "跨 z" + Z_SEAM + " 世界边两侧均非空（否则判据空转）", "A=" + a.size() + " B=" + b.size());
        chk(onlyA.isEmpty() && onlyB.isEmpty(), "共享跨缝边集两侧逐位相同（世界坐标后继与窗口无关）",
                "onlyA=" + diff(onlyA) + " onlyB=" + diff(onlyB));
        var rows = new StringBuilder("fromX,fromZ,toX,toZ,colX,colZ,fill,surfaceAboveGround,lake\n");
        int cols = 0, dry = 0;
        for (var g : a) for (int[] k : line(g.from(), g.to())) {
            var col = column(k[0], k[1]); cols++;
            if (wet(col)) continue;
            dry++;
            row(rows, g.from().x(), g.from().z(), g.to().x(), g.to().z(), k[0], k[1],
                    col != null && col.fillWater(), col != null && col.waterSurfaceY() > col.carvedGroundY(),
                    col != null && col.lakePlan());
        }
        log("    跨缝边端点+中间整数列=" + cols + " 非真湿列=" + dry);
        chk(cols > 0, "跨缝边展开出真实整数列（非空转）", "cols=0");
        chk(dry == 0, "跨缝边端点与中间整数列在真实 columnsFor 下逐列真湿（fillWater 且 surface>ground，湖算真湿）", "非真湿=" + dry);
        write("seam-crossing-dry.csv", rows);
    }

    /**
     * ③ 原三点独立追踪（无格跳/无重复/admission 单调）；④ 逐边全部真实列导出，
     * 但真湿判据<b>只作用于"历史 raw 追踪里当前仍发布"的那几条边</b>（见 {@link #traceOne}）。
     */
    private static void secSources() {
        log("\n=== [sources] 原三点历史 raw 追踪：逐边全列导出；真湿判据只对照【当前仍发布子集】(非全后缀) ===");
        var wd = prov.worldDrainage();
        var csv = new StringBuilder("sourceX,sourceZ,edgeIndex,colX,colZ,fill,surfaceAboveGround,lake,"
                + "inHistPublishedOnly,edgeStillPublished,edgeState\n");
        int admitted = 0;
        for (int[] s : ORIGINAL3) admitted += traceOne(wd, s, csv) ? 1 : 0;
        log("    原点自身仍被准入=" + admitted + "/3 —— 新政策允许源变化；只有 3/3 才能称『三条原源成河』，否则如实登记");
        write("sources-trace.csv", csv);
    }

    /**
     * 单源点历史 raw 追踪。
     *
     * <p><b>★ 口径更正（勿再称"全后缀"）</b>：本方法走的仍是 {@link WorldDrainage#trace} 的
     * <b>旧 raw 追踪</b>，湿判据只落在「raw 路径里<b>当前仍被发布</b>的边」这一<b>子集</b>上
     * （{@code histPublished}）。新政策（WorldBasinRouting/Flow）允许源点与后继改变 ⇒
     * 这条 raw 路径既不是当前发布路径，其"仍发布子集"也<b>不等于完整生命周期</b>。
     * 完整生命周期是 {@link #secLifecycle()}（沿真实世界后继穿盆溢流到海，每条边都在当前区域图内）。
     * 本方法只保留它历史基线的作用：格跳/重复/admission 单调 + "历史路径上仍发布的那几条边"逐列真湿。</p>
     */
    private static boolean traceOne(WorldDrainage wd, int[] s, StringBuilder csv) {
        var origin = new WorldDrainage.Point(s[0], s[1]);
        String tag = "(" + s[0] + "," + s[1] + ")";
        boolean originAdmitted = wd.admission(origin).channel();
        List<WorldDrainage.Point> path;
        try { path = wd.trace(origin, TRACE_BUDGET); }
        catch (RuntimeException ex) { chk(false, "原源点可独立世界追踪 " + tag, ex.getClass().getSimpleName() + ":" + ex.getMessage()); return originAdmitted; }
        int first = firstAdmitted(wd, path);
        boolean monotonic = admissionMonotonic(wd, path, first);
        String defect = defects(path);
        var state = wd.at(path.get(path.size() - 1)).state();
        boolean terminal = state == WorldDrainage.State.BELOW_SEA || state == WorldDrainage.State.BASIN_PENDING;
        int cols = 0, stillN = 0, wetN = 0, dry = 0;
        for (int e = 0; e + 1 < path.size(); e++) {
            var a = path.get(e); var b = path.get(e + 1);
            var actual = prov.worldForProbe(Math.floorDiv(a.x(), 512), Math.floorDiv(a.z(), 512));
            boolean stillPublished = actual.edges().contains(new WorldDrainage.Edge(a, b));
            // histPublished = 历史 raw 路径 ∩ 【当前仍发布】 —— 只是子集，**不是**全后缀
            boolean histPublished = first >= 0 && e >= first && stillPublished;
            if (stillPublished) stillN++;
            for (int[] k : line(a, b)) {
                var col = column(k[0], k[1]); cols++;
                boolean w = wet(col);
                row(csv, s[0], s[1], e, k[0], k[1], col != null && col.fillWater(),
                        col != null && col.waterSurfaceY() > col.carvedGroundY(), col != null && col.lakePlan(),
                        histPublished, stillPublished, wd.at(a).state());
                if (!histPublished) continue;
                if (w) wetN++; else dry++;
            }
        }
        var last = path.get(path.size() - 1);
        log("    " + tag + " originAdmitted=" + originAdmitted + " nodes=" + path.size() + " terminal=" + last + " state=" + state
                + " firstAdmitted=" + first + " edgesStillPublished=" + stillN + "/" + (path.size() - 1)
                + " cols=" + cols + " histPublishedWet=" + wetN + "/" + (wetN + dry) + " admissionMonotonic=" + monotonic);
        chk(defect == null, "追踪无格跳且无节点重复 " + tag, String.valueOf(defect));
        chk(terminal, "追踪终止于盆地待决/海（非预算耗尽或中间态） " + tag, "state=" + state);
        chk(first >= 0, "raw 路径上存在被准入（channel）的节点 —— 历史成河基线 " + tag, "全路径未准入 ⇒ 原源点已非成河源");
        chk(monotonic, "沿途 admission 单调：首次准入后不再丢（raw 基线） " + tag, "后缀 admission 回退");
        // ★ 断言保留（不删），但口径改为"历史 raw ∩ 当前仍发布子集"，绝不再称全后缀
        if (first >= 0) chk(dry == 0, "【历史 raw ∩ 当前仍发布子集】逐列真湿（surface>ground，不放宽 0.5；"
                + "非全后缀，全周期见 secLifecycle） " + tag, "该子集干列=" + dry + "/" + (wetN + dry));
        return originAdmitted;
    }

    private static void secBasins() {
        var wd=prov.worldDrainage();var sinks=new LinkedHashSet<WorldDrainage.Point>();
        for(var s:ORIGINAL3) {var path=wd.trace(new WorldDrainage.Point(s[0],s[1]),TRACE_BUDGET);sinks.add(path.get(path.size()-1));}
        var csv=new StringBuilder("sinkX,sinkZ,state,idX,idZ,sill,members,exits,samples,budget,inflow,loss,release,runoffCells\n");
        for(var sink:sinks) {
            var solver=new WorldBasin(wd,(x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()),STEP,32768);
            var basin=solver.resolve(sink);var budget=solver.budget(basin,new LakeWaterBalance.Climate(.004,.002,.3));
            row(csv,sink.x(),sink.z(),basin.state(),basin.identity()==null?"":basin.identity().x(),
                    basin.identity()==null?"":basin.identity().z(),basin.sill(),basin.members().size(),basin.exits().size(),
                    basin.samples(),budget.state(),budget.inflow(),budget.evaporation(),budget.release(),budget.uniqueRunoffCells());
            log("BASIN sink="+sink+" state="+basin.state()+" members="+basin.members().size()+" exits="+basin.exits().size()
                    +" sill="+basin.sill()+" samples="+basin.samples()+" ISOLATED_FULL_BASIN_CONDITION_ONLY=true TAILWATER_AND_MERGE_PENDING=true budget="+budget);
            var members=new StringBuilder("x,z\n");for(var p:basin.members())row(members,p.x(),p.z());
            write("basin-"+sink.x()+"-"+sink.z()+"-members.csv",members);
            var path=new StringBuilder("x,z\n");for(var p:basin.path())row(path,p.x(),p.z());
            write("basin-"+sink.x()+"-"+sink.z()+"-path.csv",path);
        }
        write("basins.csv",csv);
        for (var sink : sinks) {
            var solver = new WorldBasin(prov.worldDrainage(),
                    (x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()), STEP, 32768);
            var certificate = solver.certify(sink);
            var release = solver.release(sink, new LakeWaterBalance.Climate(.004,.002,.3));
            log("BASIN_CHAIN sink="+sink+" geometry="+certificate.reach()+" nodes="+certificate.chain().size()
                    +" sill="+certificate.sill()+" operative="+certificate.operativeSill()
                    +" conditionalBalance="+release.released()+" reason="+release.reason()+" PRODUCTION_PERMISSION=false");
            var chain = new StringBuilder("node,x,z,hopCrest\n");
            for (int i=0;i<certificate.chain().size();i++) {
                var p=certificate.chain().get(i);
                row(chain,i,p.x(),p.z(),i==0?"":certificate.crests().get(i-1));
            }
            write("basin-"+sink.x()+"-"+sink.z()+"-chain.csv",chain);
        }
    }

    private static void secGeometry(WorldDrainage.Point root,int budget) {
        var raw=prov.worldDrainage();
        var g=new WorldBasin(raw,(x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()),STEP,budget).resolve(root);
        log("GEOMETRY_BUDGET root="+root+" budget="+budget+" samples="+g.samples()+" state="+g.state()+" members="+g.members().size()+" sill="+g.sill()+" exits="+g.exits());
        var csv=new StringBuilder("innerX,innerZ,outerX,outerZ,crest,bed,terminalX,terminalZ\n");
        for(var f:g.exits()) {
            var trace=raw.trace(f.outer(),4096);var end=trace.get(trace.size()-1);
            row(csv,f.inner().x(),f.inner().z(),f.outer().x(),f.outer().z(),f.crest(),raw.at(f.outer()).bed(),end.x(),end.z());
        }
        write("geometry-exits.csv",csv);
        chk(g.state()!=WorldBasin.State.EXHAUSTED,"预算实验是否得到完整首割（不代表生产许可）",g.state().name());
    }

    private static void secMembership(WorldDrainage.Point point) {
        var raw=prov.worldDrainage();var routing=new WorldBasinRouting(raw,terrain(),STEP,GEOM_BUDGET);
        var b=routing.at(point);log("MEMBERSHIP initial="+point+" basin="+(b==null?null:b.identity()));
        if(b==null || !b.published()){chk(false,"membership seed published","missing");return;}
        int bad=0;var csv=new StringBuilder("x,z,rawNext,rawTerminal,basin,contains,expected\n");
        for(var m:b.members()) {
            var found=routing.at(m);boolean same=found!=null && found.published() && found.identity().equals(b.identity()) && found.members().contains(m);
            row(csv,m.x(),m.z(),raw.at(m).next(),routing.terminal(m),found==null?null:found.identity(),same,b.identity());
            if(!same){bad++;log("MEMBERSHIP_BAD member="+m+" raw="+raw.at(m)+" terminal="+routing.terminal(m)+" actual="+(found==null?null:found.identity()));}
        }
        write("membership.csv",csv);chk(bad==0,"whole basin members have one query-independent identity","bad="+bad);
    }
    private static void secRegionWater(int rx,int rz,int flowBudget) {
        var raw=prov.worldDrainage();
        var routing=new WorldBasinRouting(raw,(x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()),STEP,131072);
        var region=new WorldBasinFlow(raw,routing,new LakeWaterBalance.Climate(.004,.002,.3),STEP,flowBudget).region(rx,rz,512);
        log("FLOW_BUDGET_EXPERIMENT="+flowBudget+" (production unchanged; diagnostic only)");
        var reasons=new TreeMap<String,Integer>();var roots=new TreeSet<WorldDrainage.Point>(Comparator.comparingInt(WorldDrainage.Point::x).thenComparingInt(WorldDrainage.Point::z));
        var csv=new StringBuilder("x,z,reason,rootX,rootZ\n");
        for(var e:region.water().entrySet())if(!e.getValue().known()) {
            reasons.merge(e.getValue().reason(),1,Integer::sum);var root=routing.terminal(e.getKey());roots.add(root);
            row(csv,e.getKey().x(),e.getKey().z(),e.getValue().reason(),root.x(),root.z());
        }
        log("REGION_WATER rx="+rx+" rz="+rz+" edges="+region.graph().edges().size()+" unknown="+region.unresolved()+" reasons="+reasons);
        write("unknown.csv",csv);var geometry=new StringBuilder("rootX,rootZ,state,sill,floor,members,exits,pathEndX,pathEndZ\n");
        for(var root:roots) {
            if(raw.at(root).state()==WorldDrainage.State.BELOW_SEA) {
                log("ROOT_GEOMETRY "+root+" BELOW_SEA (upstream unresolved, not a basin root)");continue;
            }
            var g=new WorldBasin(raw,(x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()),STEP,131072).resolve(root);
            log("ROOT_GEOMETRY "+root+" state="+g.state()+" sill="+g.sill()+" members="+g.members().size()+" exits="+g.exits());
            var end=g.path().isEmpty()?root:g.path().get(g.path().size()-1);
            row(geometry,root.x(),root.z(),g.state(),g.sill(),g.floor(),g.members().size(),g.exits().size(),end.x(),end.z());
        }
        write("unknown-geometries.csv",geometry);
        var failed=new StringBuilder("x,z,state,samples,members,sill\n");
        for(var e:routing.geometriesForProbe().entrySet())if(e.getValue().state()!=WorldBasin.State.UNIQUE_EXIT) {
            var g=e.getValue();row(failed,e.getKey().x(),e.getKey().z(),g.state(),g.samples(),g.members().size(),g.sill());
            log("DOWNSTREAM_GEOMETRY root="+e.getKey()+" state="+g.state()+" samples="+g.samples()+" exits="+g.exits());
        }
        write("downstream-geometries.csv",failed);
        chk(region.unresolved()==0,"完整区域水量没有未知节点",String.valueOf(region.unresolved()));
    }

    /**
     * ⑧ <b>显式 basin-depth 探测（只读证据，不作水文判定）</b>：
     * {@code basin-depth x1 z1 [x2 z2 ...]}。
     *
     * <p>用途：schema89 只剩 (-2,-2) 的 18 个 {@code basin depth budget} 未知节点
     * （{@code region-water} 日志 root=(-884,-820)/(-876,-820)）。生产 {@code Basin.at} 的
     * 历史下潜层数上限 32 导致该失败，当前上限见 {@link WorldBasinRouting#CHAIN_LIMIT}；超界仍为未知 ——
     * 本模式用<b>只读</b>方式逐层 {@code resolve(131072)} → path 末点 → {@code routing.terminal}
     * 走最多 {@link #DEPTH_LAYERS} 层，回答"到底需要几层、断在第几层的什么状态"。
     * 每层一行落 {@code basin-depth.csv}，每个 root 一行落 {@code basin-depth-roots.csv}
     * （含该 root 在<b>生产 CHAIN_LIMIT 层预算</b>下的实际裁决，便于对照"逐层够不够"）；
     * 遇 sea / loop / 非 settled 几何 / 非盆底 / 求解异常即停。
     * <b>不</b>改生产预算、<b>不</b>放宽本探针任何既有断言（本模式不参与常规 suite）；
     * 除参数校验外不产生任何 PASS/FAIL（{@code failures} 恒不变）。</p>
     */
    private static void secBasinDepth(String[] args) {
        var roots = new ArrayList<WorldDrainage.Point>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            try { roots.add(new WorldDrainage.Point(Integer.parseInt(args[i].trim()),
                    Integer.parseInt(args[i + 1].trim()))); }
            catch (NumberFormatException ex) { chk(false, "basin-depth 参数须成对整数", Arrays.toString(args)); break; }
        }
        log("\n=== [basin-depth] root 逐层 resolve(" + GEOM_BUDGET + ") → path 末点 → routing.terminal，最多 "
                + DEPTH_LAYERS + " 层（只读证据，无水文判定） ===");
        chk(!roots.isEmpty(), "basin-depth 至少给一个 root（usage: basin-depth x1 z1 [x2 z2 ...]）", "roots=0");
        if (roots.isEmpty()) return;
        var raw = prov.worldDrainage();
        var routing = new WorldBasinRouting(raw, terrain(), STEP, GEOM_BUDGET);
        var layers = new StringBuilder("rootX,rootZ,layer,basinX,basinZ,geometryState,sill,floor,members,exits,"
                + "pathEndX,pathEndZ,targetX,targetZ,targetState,stop\n");
        var rootsCsv = new StringBuilder("rootX,rootZ,layers,stop,productionRouting,productionLimit\n");
        for (var root : roots) depthWalk(raw, routing, root, layers, rootsCsv);
        write("basin-depth.csv", layers);
        write("basin-depth-roots.csv", rootsCsv);
        log("    basin-depth.csv / basin-depth-roots.csv 已落盘（" + roots.size()
                + " 个 root）；本段不改变任何判定");
    }

    /**
     * 单 root 的逐层下潜；每层一行（{@code stop} 非空 = 该层即终止原因）。
     *
     * <p>链 = {@code resolve(root) → path 末点 → routing.terminal(末点)}，再以该 target 作下一层 basin。
     * 这里只记录原始几何链，不执行生产的共同鞍点、回水合并与发布裁决；生产层数读取
     * {@link WorldBasinRouting#CHAIN_LIMIT}。逐层状态便于复核历史 32 层不足，不能代替生产验收。</p>
     */
    private static void depthWalk(WorldDrainage raw, WorldBasinRouting routing, WorldDrainage.Point root,
                                  StringBuilder layers, StringBuilder rootsCsv) {
        var seen = new LinkedHashSet<WorldDrainage.Point>();
        // 每层使用独立几何预算，避免把前面层的采样累计成后面层的假耗尽。
        var p = root;
        String stop = "";
        int layer = 0;
        for (; layer < DEPTH_LAYERS; layer++) {
            if (!onLattice(p)) { stop = "OFF_LATTICE"; log("  DEPTH root=" + root + " 非格点 " + p); break; }
            var rawState = stateOf(raw, p);
            if ("BELOW_SEA".equals(rawState)) { stop = "SEA"; log("  DEPTH root=" + root + " 已到海 " + p); break; }
            if (!seen.add(p)) { stop = "LOOP"; log("  DEPTH root=" + root + " 层间环 " + p); break; }
            if (!"BASIN_PENDING".equals(rawState)) {
                stop = "NOT_SINK";
                log("  DEPTH root=" + root + " layer=" + layer + " 非盆底 " + p + " state=" + rawState);
                row(layers, root.x(), root.z(), layer, p.x(), p.z(), rawState, "", "", 0, 0,
                        "", "", "", "", "", stop);
                break;
            }
            var g = resolveOrNull(new WorldBasin(raw, terrain(), STEP, GEOM_BUDGET), p);
            if (g == null) {
                stop = "ERROR";
                row(layers, root.x(), root.z(), layer, p.x(), p.z(), "ERROR", "", "", 0, 0,
                        "", "", "", "", "", stop);
                break;
            }
            var end = g.path().isEmpty() ? null : g.path().get(g.path().size() - 1);
            var target = terminalOrNull(routing, end);
            String targetState = target == null ? "" : stateOf(raw, target);
            stop = depthStop(g.state(), end, target);
            row(layers, root.x(), root.z(), layer, p.x(), p.z(), g.state(), g.sill(), g.floor(),
                    g.members().size(), g.exits().size(), end == null ? "" : end.x(), end == null ? "" : end.z(),
                    target == null ? "" : target.x(), target == null ? "" : target.z(), targetState, stop);
            log("  DEPTH root=" + root + " layer=" + layer + " basin=" + p + " state=" + g.state()
                    + " sill=" + g.sill() + " floor=" + g.floor() + " members=" + g.members().size()
                    + " exits=" + g.exits().size() + " pathEnd=" + end + " target=" + target
                    + " targetState=" + targetState + (stop.isEmpty() ? "" : " stop=" + stop));
            if (!stop.isEmpty()) break;
            p = target;
        }
        if (stop.isEmpty()) stop = "LAYERS";
        int used = stop.equals("LAYERS") ? layer : layer + 1;
        row(rootsCsv, root.x(), root.z(), used, stop, productionRouting(routing, root), WorldBasinRouting.CHAIN_LIMIT);
        log("  DEPTH_ROOT root=" + root + " layers=" + used + " stop=" + stop
                + " productionRouting=" + productionRouting(routing, root)
                + " DEPTH_LIMIT=" + DEPTH_LAYERS + " PRODUCTION_LIMIT=" + WorldBasinRouting.CHAIN_LIMIT);
    }

    /** raw 状态字符串；非格点或预算异常以 {@code ERROR(...)} 表达（只读探测绝不因此中断整段）。 */
    private static String stateOf(WorldDrainage raw, WorldDrainage.Point p) {
        if (!onLattice(p)) return "OFF_LATTICE";
        try { return String.valueOf(raw.at(p).state()); }
        catch (RuntimeException ex) { return "ERROR(" + ex.getClass().getSimpleName() + ")"; }
    }

    /** 生产 CHAIN_LIMIT 层预算下该 root 的裁决（published / UNKNOWN(reason) / SEA）—— 与逐层证据对照用。 */
    private static String productionRouting(WorldBasinRouting routing, WorldDrainage.Point root) {
        try {
            var b = routing.at(root);
            if (b == null) return "SEA";
            return b.published() ? "published" : "UNKNOWN(" + b.reason() + ")";
        } catch (RuntimeException ex) {
            return "UNKNOWN(" + ex.getClass().getSimpleName() + ")";
        }
    }

    /** 生产同档（{@link #GEOM_BUDGET}）几何；求解异常如实记为 null 并由调用方落 ERROR 行。 */
    private static WorldBasin.Geometry resolveOrNull(WorldBasin solver, WorldDrainage.Point p) {
        try { return solver.resolve(p); }
        catch (RuntimeException ex) {
            log("  DEPTH 求解异常 " + p + " " + ex.getClass().getSimpleName() + ":" + ex.getMessage());
            return null;
        }
    }

    /** 平洼追踪超限等异常不许把只读探测整段崩掉（记 UNKNOWN 并由 {@link #depthStop} 终止）。 */
    private static WorldDrainage.Point terminalOrNull(WorldBasinRouting routing, WorldDrainage.Point end) {
        if (end == null) return null;
        try { return routing.terminal(end); }
        catch (RuntimeException ex) {
            log("  DEPTH terminal 异常 " + end + " " + ex.getClass().getSimpleName() + ":" + ex.getMessage());
            return null;
        }
    }

    private static boolean onLattice(WorldDrainage.Point p) {
        return Math.floorMod(p.x(), STEP) == 0 && Math.floorMod(p.z(), STEP) == 0;
    }

    /** 本层是否应当终止（空串 = 继续下一层）。几何未 settled（含真双出口）一律如实终止、不假作可续流。 */
    private static String depthStop(WorldBasin.State state, WorldDrainage.Point end, WorldDrainage.Point target) {
        if (!WorldBasin.settled(state)) return "UNKNOWN(" + state + ")";
        if (end == null) return "NO_PATH";
        if (target == null) return "NO_TARGET";
        return "";
    }

    /**
     * ⑤ <b>真正的完整生命周期（full lifecycle）</b>：沿真实世界后继（{@link WorldBasinRouting}：
     * 盆内走 {@code Basin.next}、溢口续流、平洼走 {@code baseNext}）逐节点走到海，
     * 且<b>每条边都必须在当前区域发布图里</b>（{@code worldWaterForProbe(rx,rz).graph()}）——
     * 一旦出现未发布边即 {@code UNPUBLISHED} 终止并 FAIL。
     *
     * <p>与 ④ 的区别（勿混淆）：④ 走的是<b>历史 raw</b> 追踪，只对照其中"仍发布"的子集；
     * 本段才是"从源点到海全周期连续"的判据。路由预算沿用既有基线 {@code 32768}
     * （改它会让既有已 PASS 基线失效）；新加的 {@link #secBasinMembers()} 才按生产口径用 {@code 131072}。</p>
     */
    private static void secLifecycle() {
        var raw=prov.worldDrainage();
        var routing=new WorldBasinRouting(raw,(x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()),STEP,32768);
        var csv=new StringBuilder("sourceX,sourceZ,node,x,z,nextX,nextZ,q,level,lake,fill,surface,ground\n");
        for(var s:ORIGINAL3) {
            var p=new WorldDrainage.Point(s[0],s[1]);var seen=new HashSet<WorldDrainage.Point>();int count=0,dry=0;
            boolean started=false;String terminal="BUDGET";
            for(int i=0;i<4096;i++) {
                if(raw.at(p).state()==WorldDrainage.State.BELOW_SEA){terminal="SEA";break;}
                if(!seen.add(p)){terminal="CYCLE";break;}
                int rx=Math.floorDiv(p.x(),512),rz=Math.floorDiv(p.z(),512);
                var region=prov.worldWaterForProbe(rx,rz);var water=region.water().get(p);
                var col=column(p.x(),p.z());
                var next=routing.at(p);var q=next!=null && next.published()?next.routeFrom(p):null;
                if(q==null)q=routing.baseNext(p);
                if(q==null){terminal="NO_SUCCESSOR";break;}
                var edge=new WorldDrainage.Edge(p,q);boolean published=region.graph().edges().contains(edge);
                if(published)started=true;
                if(started && !published){terminal="UNPUBLISHED";break;}
                if(started)for(var k:line(p,q)){count++;if(!wet(column(k[0],k[1])))dry++;}
                row(csv,s[0],s[1],i,p.x(),p.z(),q.x(),q.z(),water==null?"":water.discharge(),
                    water==null?"":water.level(),col.lakePlan(),col.fillWater(),col.waterSurfaceY(),col.carvedGroundY());
                p=q;
            }
            log("LIFECYCLE source="+Arrays.toString(s)+" terminal="+terminal+" nodes="+seen.size()+" wet="+(count-dry)+"/"+count
                    +" (full lifecycle：真实后继 + 每条边均在当前发布图内)");
            chk(terminal.equals("SEA"),"实际世界后继穿盆溢流连续到海（full lifecycle） "+Arrays.toString(s),terminal);
            chk(count>0 && dry==0,"完整生命周期逐边整数列真湿（full lifecycle） "+Arrays.toString(s),"dry="+dry+" count="+count);
        }
        write("lifecycle.csv",csv);
    }

    /**
     * ⑥ 两目标盆（三历史源点 raw 追踪的真实极小）的<b>支持闭包</b>核账：
     * published member {@code from} 数 vs 总 members。几何用生产同档 {@code 131072}（{@link #GEOM_BUDGET}）。
     *
     * <p>断言：①成员上仍有已发布出流；②支持闭包真正删去了非支持 member（发布 from 严格少于 members）；
     * ③至少一条盆外已发布入流，否则溢口必须仍在发布；④溢口不断（lip 成员 → lip 外点的边仍在当前区域图里）；
     * ⑤凡成员上发布的边都能沿 {@code Basin.next} 走到 lip（无截断入流）。
     * 未知/未发布一律如实 FAIL —— 不放宽"未知不判干"。海终结的源点无盆体，如实登记跳过。</p>
     */
    private static void secBasinMembers() {
        log("\n=== [members] 两目标盆 published member from 数 vs 总 members（几何 " + GEOM_BUDGET + "，支持闭包） ===");
        var raw = prov.worldDrainage();
        var routing = new WorldBasinRouting(raw, terrain(), STEP, GEOM_BUDGET);
        var csv = new StringBuilder("sinkX,sinkZ,idX,idZ,state,sill,members,publishedFrom,outsideInflow,"
                + "spillPublished,allInflowsReachLip,dropped\n");
        for (var sink : targetSinks(raw)) {
            var b = publishedBasin(routing, raw, sink);
            if (b == null) continue;
            var members = new LinkedHashSet<>(b.members());
            var edges = basinRegionEdges(members);
            // 计数用【去重点位】：同一 from 若在两区各发一条不同的边，按边数会虚增。
            var fromPts = new TreeSet<WorldDrainage.Point>(ORDER);
            var inflowEdges = new LinkedHashSet<WorldDrainage.Edge>();
            for (var e : edges) {
                if (members.contains(e.from())) fromPts.add(e.from());
                else if (members.contains(e.to())) inflowEdges.add(e);
            }
            int from = fromPts.size(), inflow = inflowEdges.size();
            boolean spill = edges.contains(new WorldDrainage.Edge(b.outlet().inner(), b.outlet().outer()));
            boolean reaches = reachesLip(b, edges);
            LIPS.add(new int[]{b.outlet().inner().x(), b.outlet().inner().z(),
                    b.outlet().outer().x(), b.outlet().outer().z()});
            row(csv, sink.x(), sink.z(), b.identity() == null ? "" : b.identity().x(),
                    b.identity() == null ? "" : b.identity().z(), b.state(), b.sill(),
                    members.size(), from, inflow, spill, reaches, members.size() - from);
            log("MEMBERS sink=" + sink + " id=" + b.identity() + " members=" + members.size()
                    + " publishedFrom=" + from + " outsideInflow=" + inflow + " sill=" + b.sill()
                    + " lip=" + b.outlet().inner() + "->" + b.outlet().outer() + " spillPublished=" + spill
                    + " allInflowsReachLip=" + reaches + " dropped=" + (members.size() - from));
            chk(from > 0, "成员上仍有已发布出流（支持闭包非空） " + sink, "publishedFrom=0");
            // 单格盆（members=1）时"删去非支持 member"是空真：唯一成员就是 lip 本身，如实登记不假判。
            chk(from < members.size() || members.size() == 1,
                    "支持闭包真正删去非支持 member（publishedFrom < members；单格盆空真） " + sink,
                    "from=" + from + " members=" + members.size());
            chk(inflow > 0 || spill, "至少一条盆外已发布入流，否则溢口必须仍在发布（不断流） " + sink,
                    "outsideInflow=" + inflow + " spillPublished=" + spill);
            chk(spill, "溢口不断：lip 成员 → lip 外点的边仍在当前区域图里 " + sink,
                    b.outlet().inner() + "->" + b.outlet().outer());
            chk(reaches, "成员上每条已发布边都能沿 Basin.next 走到 lip（入流不被截断） " + sink, "truncated");
        }
        write("basin-members.csv", csv);
    }

    /** 生产同档地形查询（侵蚀后地形，block 坐标；与 {@code solveHier} 同源）。 */
    private static WindowOutlets.Terrain terrain() {
        double hs = Math.max(0.01, gen.params().horizontalScale());
        return (x, z) -> gen.erodedHeight(x / hs, z / hs);
    }

    /** 三历史源点 raw 追踪的真实极小（去重、稳定顺序）。 */
    private static List<WorldDrainage.Point> targetSinks(WorldDrainage raw) {
        var sinks = new LinkedHashSet<WorldDrainage.Point>();
        for (var s : ORIGINAL3) {
            var p = raw.trace(new WorldDrainage.Point(s[0], s[1]), TRACE_BUDGET);
            sinks.add(p.get(p.size() - 1));
        }
        return List.copyOf(sinks);
    }

    /**
     * 取已发布盆体。各分支分别如实登记，<b>绝不把未知/双出口当"干"</b>：
     * ① 海终结 ⇒ 无盆体成员（不假造盆地）；② 真双出口（{@code MULTIPLE_EXITS}，不同鞍点/不同受纳）
     * ⇒ 生产口径本就不合并、不判干，这里只登记（不假判 PASS 也不 FAIL）；
     * ③ 其它未发布（几何待决/回水不完整/溢径不出流域…）⇒ FAIL；
     * ④ 求解抛异常（预算耗尽/平洼追踪超限）⇒ FAIL 并继续后面的源点（不整段崩掉判据）。
     */
    private static WorldBasinRouting.Basin publishedBasin(WorldBasinRouting routing, WorldDrainage raw,
                                                          WorldDrainage.Point sink) {
        if (raw.at(sink).state() == WorldDrainage.State.BELOW_SEA) {
            log("MEMBERS sink=" + sink + " 跳过：raw 终结于海（无盆体成员，不假造盆地）");
            return null;
        }
        WorldBasinRouting.Basin b;
        try { b = routing.at(sink); }
        catch (RuntimeException ex) {
            chk(false, "目标盆可求已发布统一几何（求解异常即 FAIL，绝不判干） " + sink,
                    ex.getClass().getSimpleName() + ":" + ex.getMessage());
            return null;
        }
        if (b != null && b.published()) return b;
        String why = b == null ? "null" : b.reason();
        if (why.contains("MULTIPLE_EXITS")) {
            log("MEMBERS sink=" + sink + " 未发布：真双出口（不同鞍点/受纳）⇒ 如实未知、不合并、不判干 —— " + why);
            return null;
        }
        chk(false, "目标盆有已发布统一几何（未知一律 FAIL，绝不判干） " + sink, why);
        return null;
    }

    /**
     * 覆盖该盆 members 的各 region 的【生产发布】世界边（worldWater graph = 与落块同源）。
     * 去重：跨 region 缝的同一条边会被两侧各发布一次，不去重会把 {@code from} 数虚增。
     * ⚠ 取值域保持原口径（members 的 rx 集合 × rz 集合）—— 本次只补 evidence，<b>不改判据</b>；
     * 若日后要收紧成"含 member 的 (rx,rz) 对"，须先确认盆外入流 {@code from} 不会因此少计。
     */
    private static List<WorldDrainage.Edge> basinRegionEdges(Collection<WorldDrainage.Point> members) {
        var rx = new TreeSet<Integer>(); var rz = new TreeSet<Integer>();
        for (var p : members) { rx.add(Math.floorDiv(p.x(), 512)); rz.add(Math.floorDiv(p.z(), 512)); }
        var out = new LinkedHashSet<WorldDrainage.Edge>();
        for (int x : rx) for (int z : rz) out.addAll(prov.worldWaterForProbe(x, z).graph().edges());
        return List.copyOf(out);
    }

    /**
     * 成员上每条已发布边沿 {@code Basin.next} 是否都终止于 lip 外点（且至少有一条被检查）。
     * 溢口边 {@code inner→outer} 本身也走同一循环：{@code routeFrom(inner)=outer} 直接落到 lip。
     */
    private static boolean reachesLip(WorldBasinRouting.Basin b, List<WorldDrainage.Edge> edges) {
        var members = new HashSet<>(b.members()); var lip = b.outlet().outer();
        int checked = 0;
        for (var e : edges) {
            if (!members.contains(e.from())) continue;
            checked++;
            var p = e.from();
            for (int i = 0; i < 64 && p != null && members.contains(p); i++) p = b.routeFrom(p);
            if (!lip.equals(p)) return false;
        }
        return checked > 0;
    }

    /**
     * ⑦ 冷逆序对照：fresh gen/provider，先 (-2,1) 再 (-2,0)（与 ① 的正序相反），与当前 WORLD 逐位比对。
     *
     * <p>比对项：两区<b>全部边集</b>（含顺序）、<b>worldWater 全部 Water</b>、以及固定世界代表点
     * （跨 z 缝点 / 两盆 lip 内+外 / (-680,16) 回水）的<b>实际列</b>逐位相同。
     * 断言冷启动 fine 计数恰 2（无邻区细管线扇出）。只做冷反序，<b>不</b>重做真 3 并发
     * （合成并发已由 {@code WorldBasinRoutingProbe} 覆盖）。</p>
     *
     * <p>★ 清理顺序：先取完暖参照（列经 {@link #COLS}、世界/水量已缓存在 {@link #WORLD}），
     * 再 {@code prov.clear()} 让静态 region/粗层/缝域缓存真正清空，最后才用新实例冷跑
     * ⇒ 该清空只会影响本段之后的冷跑，<b>不会</b>污染前面任何已完成的段。</p>
     */
    private static void secColdReverse() {
        log("\n=== [cold-reverse] fresh gen/provider 逆序 (-2,1)→(-2,0) 与当前 WORLD 逐位对照（fine 恰 2） ===");
        var points = representativePoints();
        var tags = new ArrayList<String>();
        for (var p : points) tags.add("(" + p[0] + "," + p[1] + ")" + labelOf(p)
                + "@r" + Math.floorDiv(p[0], 512) + "," + Math.floorDiv(p[1], 512));
        log("    代表点（" + points.size() + "）：" + tags + " —— 新段几何预算沿用生产 " + GEOM_BUDGET);
        chk(!points.isEmpty(), "代表点非空（否则本段判据空转）", "points=0");
        var warmCols = new ArrayList<HydrologyBlockCarvedColumn>();
        for (var p : points) warmCols.add(column(p[0], p[1]));
        var warmEdges = new ArrayList<List<WorldDrainage.Edge>>();
        var warmWater = new ArrayList<Map<WorldDrainage.Point, WorldBasinFlow.Water>>();
        for (int i = 0; i < OWNERS.length; i++) {
            warmEdges.add(WORLD[i] == null ? List.of() : WORLD[i].edges());
            warmWater.add(prov.worldWaterForProbe(OWNERS[i][0], OWNERS[i][1]).water());
        }
        prov.clear();                                   // 清静态 region/粗层/缝域缓存 ⇒ 下一步是真冷跑
        CellGenerator coldGen = newGen(tp, SEED);
        ModelHydrologyProvider cold = new ModelHydrologyProvider(coldGen, SEED);
        long f0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(), s0 = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        var coldWorld = new WorldDrainage.Result[2];
        coldWorld[1] = cold.worldForProbe(OWNERS[1][0], OWNERS[1][1]);     // 逆序：先 (-2,1)
        coldWorld[0] = cold.worldForProbe(OWNERS[0][0], OWNERS[0][1]);
        long df = HierarchicalHydrology.Diag.FINE_SOLVES.get() - f0, ds = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get() - s0;
        log("    cold FINE_SOLVES Δ=" + df + " SHARED_FINE_SOLVES Δ=" + ds);
        chk(df == 2, "冷逆序两区 ⇒ FINE_SOLVES Δ=2（无邻区细管线扇出）", "Δ=" + df);
        chk(ds == 0, "冷逆序世界解不消费共享域细解（SHARED_FINE_SOLVES Δ=0）", "Δ=" + ds);
        chk(coldWorld[0] != null && coldWorld[1] != null, "冷逆序 worldForProbe 两个 owner 均返回非 null", "null");
        coldEdges(coldWorld, warmEdges);
        coldWater(cold, warmWater);
        var coldCols = new HashMap<Long, List<HydrologyBlockCarvedColumn>>();
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i);
            var got = columnOf(cold, coldGen, coldCols, p[0], p[1]);
            chk(Objects.equals(warmCols.get(i), got), "冷逆序实际列逐位相同 at (" + p[0] + "," + p[1] + ") "
                    + labelOf(p), "warm=" + warmCols.get(i) + " cold=" + got);
        }
        coldCols.clear();                               // 冷实例的列缓存只在段内有效
        log("    cold 对照完成：points=" + points.size() + " （仅冷反序，未重做真 3 并发）");
        int cleared = COLS.size();
        COLS.clear();                                   // 本段之后无人再读 ⇒ 清缓存不污染前面任何已完成段
        log("    COLS 已在全部对照之后清空（" + cleared + " 项）");
    }

    /** 冷逆序两区边集 vs 当前 WORLD（含顺序）。 */
    private static void coldEdges(WorldDrainage.Result[] coldWorld, List<List<WorldDrainage.Edge>> warmEdges) {
        boolean ok = true;
        for (int i = 0; i < OWNERS.length; i++) {
            var got = coldWorld[i] == null ? List.<WorldDrainage.Edge>of() : coldWorld[i].edges();
            boolean same = got.equals(warmEdges.get(i));
            ok &= same;
            chk(same, "冷逆序边集与当前 WORLD 逐位相同（含顺序） owner=" + OWNERS[i][0] + "," + OWNERS[i][1],
                    "warm=" + warmEdges.get(i).size() + " cold=" + got.size()
                            + " onlyWarm=" + minus(warmEdges.get(i), got) + " onlyCold=" + minus(got, warmEdges.get(i)));
        }
        log("    cold 边集对照 edgesOk=" + ok);
    }

    /** 冷逆序两区 worldWater 的全部 Water（含未知/预算耗尽）逐位 vs 当前。 */
    private static void coldWater(ModelHydrologyProvider cold,
                                  List<Map<WorldDrainage.Point, WorldBasinFlow.Water>> warmWater) {
        boolean ok = true;
        for (int i = 0; i < OWNERS.length; i++) {
            var got = cold.worldWaterForProbe(OWNERS[i][0], OWNERS[i][1]).water();
            var want = warmWater.get(i);
            boolean same = got.equals(want);
            ok &= same;
            chk(same, "冷逆序 worldWater 全部 Water 逐位相同 owner=" + OWNERS[i][0] + "," + OWNERS[i][1],
                    "warm=" + want.size() + " cold=" + got.size() + " diff=" + waterDiff(want, got));
        }
        log("    cold 水量对照 waterOk=" + ok);
    }

    /** 固定世界代表点：跨 z 缝点 + 两盆 lip 内(lake 侧)/外(续流侧) 点 + (-680,16) 回水点（去重；顺序确定）。 */
    private static List<int[]> representativePoints() {
        var seen = new LinkedHashSet<String>();
        var out = new ArrayList<int[]>();
        var cross = WORLD[0] == null ? Set.<WorldDrainage.Edge>of() : crossing(WORLD[0]);
        if (!cross.isEmpty()) {
            var e = cross.iterator().next();
            if (seen.add(e.from().x() + "," + e.from().z())) out.add(new int[]{e.from().x(), e.from().z()});
        }
        for (var lip : LIPS) {
            if (seen.add(lip[0] + "," + lip[1])) out.add(new int[]{lip[0], lip[1]});
            if (seen.add(lip[2] + "," + lip[3])) out.add(new int[]{lip[2], lip[3]});
        }
        if (seen.add("-680,16")) out.add(new int[]{-680, 16});
        return out;
    }

    private static String labelOf(int[] p) {
        if (p[0] == -680 && p[1] == 16) return "回水";
        for (var lip : LIPS) {
            if (lip[0] == p[0] && lip[1] == p[1]) return "盆lip内";
            if (lip[2] == p[0] && lip[3] == p[1]) return "盆lip外";
        }
        return "跨缝";
    }

    private static String minus(List<WorldDrainage.Edge> a, List<WorldDrainage.Edge> b) {
        var rest = new LinkedHashSet<>(a); rest.removeAll(new LinkedHashSet<>(b));
        var sb = new StringBuilder("["); int n = 0;
        for (var e : rest) { if (n++ == 3) { sb.append(" ..."); break; } sb.append(e.from()).append("->").append(e.to()).append(' '); }
        return sb.append(']').append(rest.size()).toString();
    }

    private static String waterDiff(Map<WorldDrainage.Point, WorldBasinFlow.Water> a,
                                    Map<WorldDrainage.Point, WorldBasinFlow.Water> b) {
        var keys = new TreeSet<WorldDrainage.Point>(ORDER);
        keys.addAll(a.keySet()); keys.addAll(b.keySet());
        var sb = new StringBuilder(); int n = 0;
        for (var k : keys) if (!Objects.equals(a.get(k), b.get(k))) {
            if (n++ == 3) { sb.append(" ..."); break; }
            sb.append(k).append(" warm=").append(a.get(k)).append(" cold=").append(b.get(k)).append(' ');
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void secWorldBasinFlow() {
        var graph=prov.worldDrainage();
        var routing=new WorldBasinRouting(graph,(x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()),STEP,32768);
        var flow=new WorldBasinFlow(graph,routing,new LakeWaterBalance.Climate(.004,.002,.3),STEP,32768);
        if(Boolean.getBoolean("geogenesis.testBasinRegion")) {
            for(var p:List.of(new WorldDrainage.Point(-680,16),new WorldDrainage.Point(-676,12))) {
                var b=routing.at(p);
                log("CYCLE_CAUSE point="+p+" terminal="+routing.terminal(p)+" id="+(b==null?null:b.identity())
                    +" sill="+(b==null?0:b.sill())+" floor="+(b==null?0:b.floor())+" member="+(b!=null && b.members().contains(p))
                    +" route="+flow.geometricNext(p));
            }
            var roots=new LinkedHashSet<WorldDrainage.Point>();
            for(var p:List.of(new WorldDrainage.Point(-668,20),new WorldDrainage.Point(-676,0))) {
                var root=routing.terminal(p);roots.add(root);
                log("UNKNOWN_ROOT input="+p+" root="+root);
            }
            for(var root:roots) {
                var g=new WorldBasin(graph,(x,z)->gen.erodedHeight(x/tp.horizontalScale(),z/tp.horizontalScale()),STEP,32768).resolve(root);
                log("UNKNOWN_GEOMETRY root="+root+" state="+g.state()+" sill="+g.sill()+" exits="+g.exits()+" path="+g.path());
            }
            for(int rz: Boolean.getBoolean("geogenesis.testBasinCauseOnly") ? new int[0] : new int[]{0,1}) {
                var region=flow.region(-2,rz,512);
                log("WORLD_WATER_REGION rz="+rz+" edges="+region.graph().edges().size()+" unresolved="+region.unresolved()+" water="+region.water().size());
                var reasons=new TreeMap<String,Integer>();var unknown=new StringBuilder("x,z,reason\n");
                for(var e:region.water().entrySet())if(!e.getValue().known()) {
                    reasons.merge(e.getValue().reason(),1,Integer::sum);row(unknown,e.getKey().x(),e.getKey().z(),e.getValue().reason());
                }
                log("UNKNOWN_REASONS "+reasons);write("water-unknown-"+rz+".csv",unknown);
                chk(region.unresolved()==0,"世界水量区域未决节点必须显式为零 rz="+rz,String.valueOf(region.unresolved()));
            }
        }
        for(var sink:List.of(new WorldDrainage.Point(-656,652),new WorldDrainage.Point(-580,580))) {
            long start=System.nanoTime();var basin=routing.at(sink);
            log("WORLD_BASIN_GEOMETRY "+sink+" state="+(basin==null?"SEA":basin.state())+" reason="+(basin==null?"":basin.reason()));
            if(basin==null || !basin.published()) {chk(false,"真实盆体有完整统一几何 "+sink,"unresolved");continue;}
            var water=flow.at(sink);
            log("WORLD_BASIN_FLOW "+sink+" known="+water.known()+" q="+water.discharge()+" level="+water.level()
                    +" members="+basin.members().size()+" next="+basin.nextSink()+" reason="+water.reason()+" elapsedMs="+(System.nanoTime()-start)/1e6);
            chk(water.known() && water.discharge()>0,"真实盆体扣损后有可续接出流 "+sink,water.reason());
        }
    }

    // ---- 小工具 -----------------------------------------------------------------------
    /** 真湿：fillWater 且水面高于雕刻床面（含湖列、要求真实水柱，不放宽 0.5 门槛）。 */
    private static boolean wet(HydrologyBlockCarvedColumn c) {
        return c != null && c.fillWater() && c.waterSurfaceY() > c.carvedGroundY();
    }

    private static Set<WorldDrainage.Edge> crossing(WorldDrainage.Result w) {
        var set = new LinkedHashSet<WorldDrainage.Edge>();
        for (var e : w.edges()) {
            int f = e.from().z(), t = e.to().z();
            if ((f < Z_SEAM && t >= Z_SEAM) || (t < Z_SEAM && f >= Z_SEAM)) set.add(e);
        }
        return set;
    }

    /** 线段上的全部整数列（端点 + 中间；世界格步 ≤4 ⇒ 逐整数精确）。 */
    private static List<int[]> line(WorldDrainage.Point a, WorldDrainage.Point b) {
        int dx = b.x() - a.x(), dz = b.z() - a.z(), n = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
        var pts = new ArrayList<int[]>();
        for (int i = 0; i <= n; i++) pts.add(new int[]{a.x() + Math.round(dx * (i / (float) n)), a.z() + Math.round(dz * (i / (float) n))});
        return pts;
    }

    private static HydrologyBlockCarvedColumn column(int bx, int bz) {
        return columnOf(prov, gen, COLS, bx, bz);
    }

    /** 任意 provider/gen 上的列查询（冷逆序段用独立实例，绝不与暖会话共缓存）。 */
    private static HydrologyBlockCarvedColumn columnOf(ModelHydrologyProvider p, CellGenerator g,
                                                       Map<Long, List<HydrologyBlockCarvedColumn>> cache,
                                                       int bx, int bz) {
        int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
        for (var c : cache.computeIfAbsent((((long) cx) << 32) ^ (cz & 0xffffffffL),
                k -> p.columnsFor(cx, cz, sampleChunk(g, cx, cz)))) if (c.blockX() == bx && c.blockZ() == bz) return c;
        return null;
    }

    /** 追踪几何缺陷：格跳过 step 或零步 ⇒ 格跳；节点重复 ⇒ repeat。无缺陷返回 null。 */
    private static String defects(List<WorldDrainage.Point> path) {
        for (int i = 1; i < path.size(); i++) {
            int dx = path.get(i).x() - path.get(i - 1).x(), dz = path.get(i).z() - path.get(i - 1).z();
            if (Math.abs(dx) > STEP || Math.abs(dz) > STEP || (dx == 0 && dz == 0)) return "jump@" + i;
        }
        return new LinkedHashSet<>(path).size() == path.size() ? null : "repeat";
    }

    private static int firstAdmitted(WorldDrainage wd, List<WorldDrainage.Point> path) {
        for (int i = 0; i < path.size(); i++) if (wd.admission(path.get(i)).channel()) return i;
        return -1;
    }

    /** 沿途 admission 单调：从首次准入处起，之后每个节点仍被准入（suffix 不再丢）。 */
    private static boolean admissionMonotonic(WorldDrainage wd, List<WorldDrainage.Point> path, int first) {
        if (first < 0) return true;
        for (int i = first; i < path.size(); i++) if (!wd.admission(path.get(i)).channel()) return false;
        return true;
    }

    private static String diff(Set<WorldDrainage.Edge> s) {
        var sb = new StringBuilder();
        boolean corner = !s.isEmpty(), first = false;
        for (var e : s) {
            if (Math.floorDiv(e.from().x(), 512) == Math.floorDiv(e.to().x(), 512)) corner = false;
            if (!first) { first = true; sb.append(e.from().x()).append(',').append(e.from().z()).append("->").append(e.to().x()).append(',').append(e.to().z()); }
        }
        return sb.length() == 0 ? "-" : sb + (corner ? " (全部同时跨 x 缝=双轴拐角)" : " ...");
    }

    private static void row(StringBuilder sb, Object... v) {
        for (int i=0;i<v.length;i++) {
            if(i>0)sb.append(',');String text=String.valueOf(v[i]);
            if(text.contains(",") || text.contains("\"") || text.contains("\n"))sb.append('"').append(text.replace("\"","\"\"")).append('"');
            else sb.append(text);
        }
        sb.append('\n');
    }

    private static void write(String name, CharSequence body) {
        try { Files.writeString(out.resolve(name), body); } catch (Exception e) { throw new RuntimeException("写 " + name + " 失败", e); }
    }

    private static boolean loadConfig() {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.exists(src)) { log("[cfg] " + src + " 不存在"); return false; }
        try {
            Path work = out.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return true;
        } catch (Throwable e) { log("[cfg] 工作副本加载失败: " + e); return false; }
    }

    private static TerrainParams params() {
        try { return GeoGenesisConfig.INSTANCE.buildParams(); }
        catch (Throwable e) { log("[cfg] buildParams 失败 ⇒ defaults 兜底并计 FAIL: " + e); failures++; return TerrainParams.defaults(); }
    }

    private static CellGenerator newGen(TerrainParams p, long seed) { CellGenerator g = new CellGenerator(p, p.minY(), p.maxY()); g.seed(seed); return g; }

    private static Cell[] sampleChunk(CellGenerator g, int cx, int cz) {
        double hs = Math.max(0.01, g.params().horizontalScale()); Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) cells[lx * 16 + lz] = g.sample((cx * 16 + lx) / hs, (cz * 16 + lz) / hs);
        return cells;
    }

    private static void chk(boolean ok, String name, String detail) {
        if (ok) { log("  [PASS] " + name); return; }
        failures++;
        log("  [FAIL] " + name + " —— " + detail);
    }

    private static void log(String s) { LOG.append(s).append('\n'); }
}
