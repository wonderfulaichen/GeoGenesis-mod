package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 同一生产provider会话的接点取证。默认与显式boundaryPorts=true分组，不修改生产默认值。
 * Trace.cell与Shaped节点索引不同；几何投影限定在已确认接收trace，记录残差而不冒充精确锚点。
 * 段半宽不是IDW/jitter后的列半宽；单点湿列不能证明两侧连续。采集缺失计非零退出。
 * 用法：[seed] [rx] [rz] [outDir]；Gradle入口见tools/local-flow-reassessment.gradle。
 */
public final class RiverJunctionEvidenceProbe {

    static final long DEF_SEED = 9139912035078620160L;
    static final Path DEF_OUT = Path.of("build", "river-local-contract", "probe-output");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    static long seed;
    static int rx, rz, region = 512;
    static Path out;
    static CellGenerator gen;
    static ModelHydrologyProvider prov;
    static final List<String> NOTES = new ArrayList<>();
    static int fail;

    private RiverJunctionEvidenceProbe() { }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
            code = 3;
        }
        System.out.println("EXIT=" + code);
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        seed = args.length > 0 ? Long.parseLong(args[0].trim()) : DEF_SEED;
        rx = args.length > 1 ? Integer.parseInt(args[1].trim()) : 0;
        rz = args.length > 2 ? Integer.parseInt(args[2].trim()) : 0;
        out = Path.of(args.length > 3 ? args[3].trim()
                : System.getProperty("geogenesis.junction.out", DEF_OUT.toString()));
        Files.createDirectories(out);
        TerrainParams tp = loadConfig();
        gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        prov = new ModelHydrologyProvider(gen, seed);
        region = staticInt("REGION", 512);

        int cx = rx * region + region / 2, cz = rz * region + region / 2;
        DeterministicSkeleton.Result sk = prov.finalSkeletonForProbe(rx, rz);
        if (sk == null) {
            fail++;
            return emit(null, "finalSkeletonForProbe(" + rx + "," + rz + ") 返回 null");
        }
        int[] f = prov.windowFrameForProbe(cx, cz);
        int ox = f[0], oz = f[1], step = f[2], n = f[3];

        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("probe", "RiverJunctionEvidenceProbe");
        meta.put("readOnly", true);
        meta.put("mode", System.getProperty("geogenesis.boundaryPorts") == null
                ? "DEFAULT(no geogenesis.boundaryPorts)" : "EXPLICIT(boundaryPorts="
                + System.getProperty("geogenesis.boundaryPorts") + ")");
        meta.put("boundaryPortsProperty", System.getProperty("geogenesis.boundaryPorts", "(unset)"));
        meta.put("portEgressEnabled", staticBool(DeterministicSkeleton.class, "PORT_EGRESS"));
        meta.put("providerBoundaryPorts", staticBool(ModelHydrologyProvider.class, "BOUNDARY_PORTS"));
        meta.put("seed", seed);
        meta.put("region", List.of(rx, rz));
        meta.put("windowFrame", List.of(ox, oz, step, n));
        meta.put("strategyFingerprint", prov.strategyFingerprintValue());
        meta.put("configFingerprint", cfgFingerprint());
        meta.put("traces", sk.traces.size());
        meta.put("ports", sk.ports.size());
        meta.put("mergeEvidence", sk.mergeEvidence.size());
        meta.put("mergedIncoming", sk.mergedIncoming);
        root.put("meta", meta);

        require(prov.shapeAlignedForProbe(rx, rz), "traces 与落块折线 shapedPolys 下标对齐",
                "两者数量不同 ⇒ 无法按下标互查");

        Object rd = regionFor(cx, cz);
        double[] seg = segArray(rd);
        @SuppressWarnings("unchecked")
        List<DeterministicSkeleton.Shaped> polys =
                (List<DeterministicSkeleton.Shaped>) accessor(rd, "shapedPolys");
        HydrologyPipeline.Result pipe = prov.pipeForProbe(cx, cz);

        root.put("join", collectJoin(sk, polys, seg, pipe, ox, oz, step, n));
        root.put("crossRegion", collectCross(sk, polys, seg, ox, oz, step, n));
        // fingerprint 消费【上面已采到的列】，不重复调 columnsFor（避免副作用与重复求解）。
        root.put("fingerprint", fingerprint(sk, polys, seg, pipe, root));
        root.put("notes", new ArrayList<>(NOTES));
        root.put("collectionFailures", fail);
        return emit(root, null);
    }

    // ---------------- 配置（只读副本，原件不写回）----------------

    static TerrainParams loadConfig() {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.isRegularFile(src)) throw new IllegalStateException("Missing production config: " + src);
        try {
            Path work = out.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            NOTES.add("[cfg] 工作副本=" + work.toAbsolutePath() + "（原件只读、未写回）");
            return GeoGenesisConfig.INSTANCE.buildParams();
        } catch (Exception e) {
            throw new IllegalStateException("Production config could not be loaded", e);
        }
    }

    static long cfgFingerprint() {
        try {
            return GeoGenesisConfig.configFingerprint();
        } catch (Throwable e) {
            return 0L;
        }
    }

    // ---------------- 反射薄层（只读）----------------

    static Object regionFor(int bx, int bz) throws Exception {
        Method m = ModelHydrologyProvider.class.getDeclaredMethod("regionFor", int.class, int.class);
        m.setAccessible(true);
        return m.invoke(prov, bx, bz);
    }

    static Object accessor(Object rec, String name) {
        if (rec == null) return null;
        try {
            Method m = rec.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(rec);
        } catch (Throwable t) {
            NOTES.add("[reflect] accessor " + name + " 失败：" + t);
            return null;
        }
    }

    static double[] segArray(Object rd) {
        Object si = accessor(rd, "skeletonSeg");
        if (si == null) return null;
        try {
            Field f = si.getClass().getDeclaredField("seg");
            f.setAccessible(true);
            return (double[]) f.get(si);
        } catch (Throwable t) {
            NOTES.add("[reflect] SegmentIndex.seg 读取失败：" + t);
            return null;
        }
    }

    static Object staticBool(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(null);
        } catch (Throwable t) {
            return "NOT_COLLECTED";
        }
    }

    static int staticInt(String name, int dflt) {
        try {
            Field f = ModelHydrologyProvider.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return dflt;
        }
    }

    /** 已求解 region 键集（rx:rz）—— 用于精确列出 columnsFor 实际新增了哪些 final。 */
    static TreeSet<String> solvedKeys() {
        TreeSet<String> s = new TreeSet<>();
        try {
            Field f = ModelHydrologyProvider.class.getDeclaredField("SHARED_CACHE");
            f.setAccessible(true);
            Object map = f.get(null);
            if (map == null) return s;
            synchronized (map) {
                for (Object k : ((Map<?, ?>) map).keySet()) {
                    s.add(accessor(k, "rx") + ":" + accessor(k, "rz"));
                }
            }
        } catch (Throwable t) {
            NOTES.add("[reflect] SHARED_CACHE 读取失败：" + t);
        }
        return s;
    }

    // ---------------- 段定位：逐位相等，证明归属 ----------------

    /** 折线 {@code poly} 的第 {@code j} 段（连接节点 j→j+1）在 seg 中的下标；找不到 = −1。 */
    static int segIndexOf(List<DeterministicSkeleton.Shaped> polys, double[] seg, int poly, int j) {
        if (seg == null || polys == null || poly < 0 || poly >= polys.size()) return -1;
        var sh = polys.get(poly);
        if (j < 0 || j + 1 >= sh.size()) return -1;
        int base = 0;
        for (int i = 0; i < poly; i++) base += Math.max(0, polys.get(i).size() - 1);
        double x0 = sh.x[j], z0 = sh.z[j], x1 = sh.x[j + 1], z1 = sh.z[j + 1];
        if (exact(seg, base + j, x0, z0, x1, z1)) return base + j;
        // 基址推算失效时的兜底：仍要求四个坐标逐位相等 ⇒ 归属依然被证明
        for (int k = 0; k < seg.length / 8; k++) if (exact(seg, k, x0, z0, x1, z1)) return k;
        return -1;
    }

    static boolean exact(double[] seg, int idx, double x0, double z0, double x1, double z1) {
        int o = idx * 8;
        if (o < 0 || o + 7 >= seg.length) return false;
        return bits(seg[o], x0) && bits(seg[o + 1], z0) && bits(seg[o + 2], x1) && bits(seg[o + 3], z1);
    }

    static boolean bits(double a, double b) { return Double.doubleToLongBits(a) == Double.doubleToLongBits(b); }

    /** 段参数（含归属证明）；找不到即 NOT_COLLECTED。 */
    static Map<String, Object> segAt(List<DeterministicSkeleton.Shaped> polys, double[] seg,
                                     int poly, int j, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("polyIndex", poly);
        m.put("segmentOrdinalInPoly", j);
        int idx = segIndexOf(polys, seg, poly, j);
        if (idx < 0) {
            m.put("status", "NOT_COLLECTED");
            m.put("reason", "该折线的第 " + j + " 段在 seg 中找不到四坐标逐位相等的记录 ⇒ 拒绝盲猜");
            return m;
        }
        int o = idx * 8;
        m.put("status", "COLLECTED");
        m.put("segIndex", idx);
        m.put("ownershipProof", "seg[" + idx + "] 的 (x0,z0,x1,z1) 与 polys[" + poly
                + "] 节点 " + j + "→" + (j + 1) + " 逐位相等");
        m.put("x0", seg[o]);
        m.put("z0", seg[o + 1]);
        m.put("x1", seg[o + 2]);
        m.put("z1", seg[o + 3]);
        m.put("half0", seg[o + 4]);
        m.put("half1", seg[o + 5]);
        m.put("depth0", seg[o + 6]);
        m.put("depth1", seg[o + 7]);
        m.put("notFinalColumnValue",
                "段半宽/段深度是 SegmentIndex 值；落块列还会再叠 IDW 混合与河岸 jitter、"
                        + "depth 由 eroded-surface 改写 ⇒ 不可当列值");
        return m;
    }

    // ---------------- 同区 JOIN ----------------

    static Map<String, Object> collectJoin(DeterministicSkeleton.Result sk,
                                           List<DeterministicSkeleton.Shaped> polys, double[] seg,
                                           HydrologyPipeline.Result pipe,
                                           int ox, int oz, int step, int n) {
        Map<String, Object> sec = new LinkedHashMap<>();
        Map<Integer, int[]> recv = receivers(sk);
        int joins = 0, usable = 0;
        int upIdx = -1, downIdx = -1, downNode = -1;
        for (int i = 0; i < sk.traces.size(); i++) {
            var t = sk.traces.get(i);
            if (t.end != DeterministicSkeleton.End.JOIN || t.cell.length < 2) continue;
            joins++;
            int[] r = recv.get(t.mouthCell());
            if (r == null || r[0] < 0 || r[0] == i) continue;
            var down = sk.traces.get(r[0]);
            if (r[1] <= 0 || r[1] + 1 >= down.cell.length) continue;   // 需两侧都有段可报
            usable++;
            if (upIdx < 0) {
                upIdx = i;
                downIdx = r[0];
                downNode = r[1];
            }
        }
        sec.put("joinTraces", joins);
        sec.put("usableWithBothSides", usable);
        require(upIdx >= 0, "存在可采的同区 JOIN（下游接点两侧都有段，可给上下游各自半宽）",
                "joins=" + joins + " usable=" + usable + " ⇒ 无样本，属采集缺口");
        if (upIdx < 0) {
            sec.put("status", "NOT_COLLECTED");
            return sec;
        }
        var up = sk.traces.get(upIdx);
        var down = sk.traces.get(downIdx);
        sec.put("status", "COLLECTED");
        sec.put("upTraceIndex", upIdx);
        sec.put("upNodes", up.cell.length);
        sec.put("upEnd", String.valueOf(up.end));
        sec.put("upFromOutlet", up.fromOutlet);
        sec.put("upMouthNodeIndex", up.cell.length - 1);
        sec.put("downTraceIndex", downIdx);
        sec.put("downNodes", down.cell.length);
        sec.put("downEnd", String.valueOf(down.end));
        sec.put("downFromOutlet", down.fromOutlet);
        sec.put("downNodeIndexAtJoint", downNode);
        sec.put("jointCell", up.mouthCell());
        sec.put("jointWorld", worldOf(up.mouthCell(), ox, oz, step, n));
        sec.put("jointCellEqualsDownNode", down.cell[downNode] == up.mouthCell());
        sec.put("q", jointQ(pipe, up, down, downNode, n));
        sec.put("geometry", jointGeometry(polys, seg, upIdx, downIdx));
        sec.put("upBeforeJointColumns", columnsAt(up.cell[up.cell.length - 2], ox, oz, step, n, "支流接点前格"));
        sec.put("downAfterJointColumns", columnsAt(down.cell[downNode + 1], ox, oz, step, n, "承接河接点后格"));
        sec.put("columns", columnsAt(up.mouthCell(), ox, oz, step, n,
                "JOIN 接点【单点】列 —— 同一点只产一列，不能用来证明两侧属性连续"));
        return sec;
    }

    /** 所有节点序号在 Shaped 空间中取得，绝不使用 Trace.cell 的节点序号。 */
    static Map<String, Object> jointGeometry(List<DeterministicSkeleton.Shaped> polys, double[] seg,
                                            int upIdx, int downIdx) {
        var up = polys.get(upIdx);
        int last = up.size() - 1;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upShapedTail", List.of(up.x[last], up.z[last]));
        m.put("upTail", segAt(polys, seg, upIdx, last - 1, "actual shaped tail"));
        m.put("downHeadUsedByConfluence", segAt(polys, seg, downIdx, 0, "downSeq[0] reference"));
        m.put("receiverProjection", project(polys, seg, downIdx, up.x[last], up.z[last]));
        m.put("caveat", "projection限定在已确认接收trace的shaped折线上；记录距离，不把最近投影冒充拓扑锚点。"
                + "半宽为最终段值，未包含nearest IDW与落块jitter；不能据此反推传播前下压量。");
        return m;
    }

    static Map<String, Object> project(List<DeterministicSkeleton.Shaped> polys, double[] seg,
                                       int poly, double x, double z) {
        var sh = polys.get(poly);
        int best = -1;
        double distance = Double.POSITIVE_INFINITY, fraction = 0;
        for (int j = 0; j + 1 < sh.size(); j++) {
            double dx = sh.x[j + 1] - sh.x[j], dz = sh.z[j + 1] - sh.z[j];
            double length2 = dx * dx + dz * dz;
            double t = length2 == 0 ? 0 : Math.max(0, Math.min(1,
                    ((x - sh.x[j]) * dx + (z - sh.z[j]) * dz) / length2));
            double d = Math.hypot(x - sh.x[j] - t * dx, z - sh.z[j] - t * dz);
            if (d < distance) { distance = d; fraction = t; best = j; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", List.of(x, z));
        m.put("polyIndex", poly);
        m.put("shapeNodeCount", sh.size());
        m.put("projectionDistance", best < 0 ? null : distance);
        m.put("fraction", fraction);
        m.put("segment", segAt(polys, seg, poly, best, "projected segment on known receiving trace"));
        int si = segIndexOf(polys, seg, poly, best);
        if (si >= 0) m.put("interpolatedSegmentHalf", seg[si * 8 + 4] * (1 - fraction) + seg[si * 8 + 5] * fraction);
        return m;
    }

    /** 生产 riverJoins 的 first-wins 接收者规则（JOIN 尾格不参与；重复格判 −1）。 */
    static Map<Integer, int[]> receivers(DeterministicSkeleton.Result sk) {
        Map<Integer, int[]> rec = new LinkedHashMap<>();
        for (int i = 0; i < sk.traces.size(); i++) {
            var t = sk.traces.get(i);
            for (int k = 0; k < t.cell.length; k++) {
                if (t.end == DeterministicSkeleton.End.JOIN && k == t.cell.length - 1) continue;
                if (k > 0 && t.cell[k] == t.cell[k - 1]) continue;
                rec.put(t.cell[k], rec.containsKey(t.cell[k]) ? new int[]{-1, -1} : new int[]{i, k});
            }
        }
        return rec;
    }

    static Map<String, Object> jointQ(HydrologyPipeline.Result pipe,
                                      DeterministicSkeleton.Trace up, DeterministicSkeleton.Trace down,
                                      int downNode, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "RUNOFF_SCALED_CUMULATIVE —— 相对水量代理，非物理流量，更非体量");
        m.put("definition", "pipe.discharge[]：RiverNetwork.build 的累积出流量 ——"
                + "每格源项 = runoffPerCell（坡面径流常数），沿 D8 parent 逆拓扑累加，遇湖盆拦截（lakeSink）");
        m.put("source", "same region pipe.discharge[cell]; not a calibrated physical unit");
        m.put("upFirst", q(pipe, up.cell[0], n));
        m.put("upMouth", q(pipe, up.mouthCell(), n));
        m.put("downFirst", q(pipe, down.cell[0], n));
        m.put("downAtJoint", q(pipe, down.cell[downNode], n));
        m.put("downLast", q(pipe, down.mouthCell(), n));
        m.put("upBeforeJoint", q(pipe, up.cell[up.cell.length - 2], n));
        m.put("downAfterJoint", q(pipe, down.cell[downNode + 1], n));
        m.put("sharedCellNote", "upMouth 与 downAtJoint 是同一 coarse 格；相等不证明流量正确继承");
        m.put("whyNotVolume", "该值不乘降水强度/面积/时长，也未做单位标定 ⇒"
                + " 与流量(m³/s)、体积、蓄水量都不成正比，只能作【相对】比较");
        return m;
    }

    static Object q(HydrologyPipeline.Result pipe, int cell, int n) {
        if (pipe == null || pipe.discharge() == null) return "NOT_COLLECTED(no pipe)";
        if (cell < 0 || cell >= pipe.discharge().length) return "NOT_COLLECTED(cell out of window)";
        return pipe.discharge()[cell];
    }

    static Object pass1(int x, int z) throws Exception {
        Method m = ModelHydrologyProvider.class.getDeclaredMethod("regionPass1", int.class, int.class);
        m.setAccessible(true);
        return m.invoke(prov, x, z);
    }

    static Map<String, Object> crossAttributes(BoundaryPort port, int anchor, int next,
                                               HydrologyPipeline.Result receiver) throws Exception {
        Object sender = pass1(port.senderRegionX(), port.senderRegionZ());
        var pipe = (HydrologyPipeline.Result) accessor(sender, "pipe");
        int ox = (Integer) accessor(sender, "originX"), oz = (Integer) accessor(sender, "originZ");
        int step = pipe.cellBlocks(), n = pipe.nz();
        int cell = ((port.anchorX() - ox) / step) * n + (port.anchorZ() - oz) / step;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("senderPass1Q", q(pipe, cell, n));
        m.put("senderPass1Surface", pipe.waterSurface()[cell]);
        m.put("receiverQ", q(receiver, anchor, receiver.nz()));
        m.put("receiverNextQ", q(receiver, next, receiver.nz()));
        m.put("receiverSurface", receiver.waterSurface()[anchor]);
        m.put("caveat", "sender为实际承诺的pass1；没有发送侧最终列，不能声称两边宽深相同");
        return m;
    }

    // ---------------- typed 跨区接力 ----------------

    static Map<String, Object> collectCross(DeterministicSkeleton.Result sk,
                                            List<DeterministicSkeleton.Shaped> polys, double[] seg,
                                            int ox, int oz, int step, int n) throws Exception {
        Map<String, Object> sec = new LinkedHashMap<>();
        List<Object> ports = new ArrayList<>();
        List<Map<String, Object>> consumed = new ArrayList<>();
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                int nrx = rx + dRX, nrz = rz + dRZ;
                for (BoundaryPort raw : prov.pass1PortsForProbe(nrx, nrz)) {
                    BoundaryPort p = raw.stamp(nrx, nrz, region);
                    if (p.receiverRegionX() != rx || p.receiverRegionZ() != rz) continue;
                    Map<String, Object> row = classifyPort(p, sk, ox, oz, step, n);
                    ports.add(row);
                    if (Boolean.TRUE.equals(row.get("consumed"))) consumed.add(row);
                }
            }
        }
        sec.put("portEgressEnabled", staticBool(DeterministicSkeleton.class, "PORT_EGRESS"));
        sec.put("portsPointingHere", ports.size());
        if (ports.isEmpty()) {
            sec.put("status", "NOT_PRESENT");
            sec.put("reason", "本区 pass1 邻域没有任何 typed 端口指向这里"
                    + (Boolean.getBoolean("geogenesis.boundaryPorts") ? "（开关已开，确属无承诺）"
                    : "；默认未设 -Dgeogenesis.boundaryPorts ⇒ DeterministicSkeleton.PORT_EGRESS=false，"
                    + "骨架层不产端口（这是配置事实，不是断流）"));
            return sec;
        }
        sec.put("ports", ports);
        require(!consumed.isEmpty(), "存在被接收方实际消费的 typed 接力（merge 后继逐位相等 或 起链首步相等）",
                "有 " + ports.size() + " 个端口指向本区但都无法证明被消费");
        if (consumed.isEmpty()) {
            sec.put("status", "NOT_CONSUMED");
            return sec;
        }
        Map<String, Object> pick = consumed.get(0);
        sec.put("status", "COLLECTED");
        sec.put("picked", pick);
        int anchorCell = (Integer) pick.get("anchorCell");
        int nextCell = (Integer) pick.get("nextCell");
        sec.put("anchorColumns", columnsAt(anchorCell, ox, oz, step, n, "typed 接力 anchor 列"));
        sec.put("nextColumns", columnsAt(nextCell, ox, oz, step, n, "typed 接力 next 列（相邻列）"));
        var port = (BoundaryPort) pick.get("sourcePort");
        sec.put("attributes", crossAttributes(port, anchorCell, nextCell,
                prov.pipeForProbe(rx * region + region / 2, rz * region + region / 2)));
        var owners = receivers(sk);
        int[] owner = owners.get(anchorCell);
        if (owner != null && owner[0] >= 0) {
            sec.put("receiverTraceIndex", owner[0]);
            sec.put("receiverTraceNode", owner[1]);
            sec.put("receiverFromOutlet", sk.traces.get(owner[0]).fromOutlet);
            sec.put("receiverSourceCell", sk.traces.get(owner[0]).sourceCell());
            sec.put("receiverGeometry", project(polys, seg, owner[0], port.anchorX(), port.anchorZ()));
        } else sec.put("receiverGeometry", "NOT_COLLECTED: no unique trace owner");
        return sec;
    }


    /**
     * 逐端口判定"是否被实际消费"，只认两种<b>可证明</b>的形态：
     * <ol>
     *   <li>{@code INCOMING_CHAIN} —— 本区有链以 anchor 为首格且第二格 == port.next；</li>
     *   <li>{@code MERGED_BASE_SUCCESSOR} —— {@code mergeEvidence} 里 anchor 一致、
     *       {@code resolved()} 为真、且 {@code evidence.next == port.next}（逐位相等）。</li>
     * </ol>
     * 不再用"anchor 被 base 覆盖"这种不可证的近似。
     */
    static Map<String, Object> classifyPort(BoundaryPort p, DeterministicSkeleton.Result sk,
                                            int ox, int oz, int step, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        int ai = Math.floorDiv(p.anchorX() - ox, step), aj = Math.floorDiv(p.anchorZ() - oz, step);
        int ni = Math.floorDiv(p.nextX() - ox, step), nj = Math.floorDiv(p.nextZ() - oz, step);
        boolean onGrid = ox + (long) ai * step == p.anchorX() && oz + (long) aj * step == p.anchorZ()
                && ox + (long) ni * step == p.nextX() && oz + (long) nj * step == p.nextZ();
        int anchorCell = ai * n + aj, nextCell = ni * n + nj;
        m.put("port", p.toString());
        m.put("sourcePort", p);
        m.put("senderRegion", List.of(p.senderRegionX(), p.senderRegionZ()));
        m.put("anchorWorld", List.of(p.anchorX(), p.anchorZ()));
        m.put("nextWorld", List.of(p.nextX(), p.nextZ()));
        m.put("anchorCell", anchorCell);
        m.put("nextCell", nextCell);
        m.put("onGrid", onGrid);
        boolean rejected = prov.wireAssemblyRejectsForProbe(rx, rz).stream()
                .anyMatch(r -> r.contains(p.toString()));
        m.put("rejected", rejected);
        require(onGrid, "typed 端口锚点/next 逐位落在接收方网格 anchor=" + p.anchorX() + "," + p.anchorZ(),
                "不在网格上 ⇒ 无法判定");
        int chainIdx = chainStartingAt(sk, anchorCell, nextCell);
        m.put("receiverTraceIndex", chainIdx);
        String mode = "NOT_CONSUMED";
        if (chainIdx >= 0) mode = "INCOMING_CHAIN";
        if ("NOT_CONSUMED".equals(mode)) {
            for (var e : sk.mergeEvidence) {
                if (e.anchor() != anchorCell) continue;
                m.put("mergeResolution", e.resolution());
                m.put("mergeNext", e.next());
                m.put("mergeSteps", e.steps());
                m.put("mergeNextEqualsPortNext", e.next() == nextCell);
                if (e.resolved() && e.next() == nextCell) mode = "MERGED_BASE_SUCCESSOR";
                break;
            }
        }
        m.put("consumptionMode", mode);
        m.put("consumed", !rejected && onGrid && !"NOT_CONSUMED".equals(mode));
        return m;
    }

    static int chainStartingAt(DeterministicSkeleton.Result sk, int anchorCell, int nextCell) {
        for (int i = 0; i < sk.traces.size(); i++) {
            var t = sk.traces.get(i);
            if (t.cell.length >= 2 && t.cell[0] == anchorCell && t.cell[1] == nextCell) return i;
        }
        return -1;
    }

    // ---------------- 生产列（允许既有懒邻区 final，精确记录新增键）----------------

    static Map<String, Object> columnsAt(int cell, int ox, int oz, int step, int n, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        int wx = (int) (ox + (cell / n) * (double) step), wz = (int) (oz + (cell % n) * (double) step);
        m.put("label", label);
        m.put("block", List.of(wx, wz));
        int chunkX = Math.floorDiv(wx, 16), chunkZ = Math.floorDiv(wz, 16);
        m.put("chunk", List.of(chunkX, chunkZ));
        TreeSet<String> before = solvedKeys();
        try {
            List<HydrologyBlockCarvedColumn> cols = prov.columnsFor(chunkX, chunkZ, sampleChunk(chunkX, chunkZ));
            TreeSet<String> after = solvedKeys();
            after.removeAll(before);
            m.put("status", "COLLECTED");
            var column = columnOf(cols, wx, wz);
            require(column.containsKey("carvedGroundY"), "actual column present", "missing " + wx + "," + wz);
            m.put("sampledColumn", column);
            m.put("newlySolvedFinalRegions", new ArrayList<>(after));
            m.put("lazyNeighborNote", "空列表 = 未触发邻区 final；非空 = 生产 columnsFor 的边缘路径"
                    + "实际求解了这些 region（既有生产行为，不是本探针新路由）");
        } catch (Throwable t) {
            m.put("status", "FAILED");
            m.put("reason", String.valueOf(t));
            fail++;
        }
        return m;
    }

    static Cell[] sampleChunk(int chunkX, int chunkZ) {
        double hs = Math.max(0.01, gen.params().horizontalScale());
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                cells[lx * 16 + lz] = gen.sample((chunkX * 16 + lx) / hs, (chunkZ * 16 + lz) / hs);
            }
        }
        return cells;
    }

    static Map<String, Object> columnOf(List<HydrologyBlockCarvedColumn> cols, int wx, int wz) {
        for (HydrologyBlockCarvedColumn c : cols) {
            if (c.blockX() != wx || c.blockZ() != wz) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blockX", c.blockX());
            m.put("blockZ", c.blockZ());
            m.put("carvedGroundY", c.carvedGroundY());
            m.put("waterSurfaceY", c.waterSurfaceY());
            m.put("waterColumn", c.waterSurfaceY() - c.carvedGroundY());
            m.put("erosion", c.erosion());
            m.put("fillWater", c.fillWater());
            m.put("lakePlan", c.lakePlan());
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "NOT_COLLECTED");
        m.put("reason", "columnsFor 结果里没有该方块（不补 0）");
        return m;
    }

    // ---------------- 确定性指纹 ----------------

    /** FNV-1a 64；double 走 raw bits；不含 Result.rivers。 */
    static Map<String, Object> fingerprint(DeterministicSkeleton.Result sk,
                                           List<DeterministicSkeleton.Shaped> polys, double[] seg,
                                           HydrologyPipeline.Result pipe, Map<String, Object> root) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> sections = new LinkedHashMap<>();

        long h = 0xcbf29ce484222325L;
        h = mixLong(h, seed);
        h = mixLong(h, prov.strategyFingerprintValue());
        h = mixLong(h, rx);
        h = mixLong(h, rz);
        sections.put("meta", h);

        h = 0xcbf29ce484222325L;
        if (pipe != null) {
            h = mixInt(h, pipe.nx());
            h = mixInt(h, pipe.nz());
            h = mixInt(h, pipe.cellBlocks());
            h = mixDoubles(h, pipe.bed());
            h = mixBools(h, pipe.sea());
            h = mixBools(h, pipe.resolved());
            h = mixDoubles(h, pipe.level());
            h = mixDoubles(h, pipe.depth());
            h = mixBools(h, pipe.lake());
            h = mixInts(h, pipe.parent());
            h = mixDoubles(h, pipe.discharge());
            h = mixBools(h, pipe.channel());
            h = mixDoubles(h, pipe.width());
            h = mixDoubles(h, pipe.waterSurface());
        }
        sections.put("pipe", h);

        h = 0xcbf29ce484222325L;
        h = mixInt(h, sk.traces.size());
        for (var t : sk.traces) {
            h = mixInts(h, t.cell);
            h = mixString(h, String.valueOf(t.end));
            h = mixLong(h, t.fromOutlet ? 1 : 0);
            h = mixDoubles(h, t.outsideTail);
            h = mixDoubles(h, t.contPath);
        }
        sections.put("traces", h);

        h = 0xcbf29ce484222325L;
        if (polys != null) {
            h = mixInt(h, polys.size());
            for (var sh : polys) {
                h = mixDoubles(h, sh.x);
                h = mixDoubles(h, sh.z);
            }
        }
        sections.put("shaped", h);

        h = 0xcbf29ce484222325L;
        h = mixDoubles(h, seg);
        sections.put("segIndex", h);

        sections.put("boundary", boundaryFingerprint(sk));

        // ★ columns 段：消费【上面已采到的】列，不重复调 columnsFor（无副作用、无重复求解）。
        sections.put("columns", columnFingerprint(root));

        long all = 0xcbf29ce484222325L;
        for (long v : sections.values()) all = mixLong(all, v);
        out.put("all", all);
        out.put("sections", new LinkedHashMap<String, Object>(sections));
        out.put("legend", "FNV-1a 64；double=raw bits；不含 Result.rivers");
        return out;
    }

    static long boundaryFingerprint(DeterministicSkeleton.Result sk) {
        long h = 0xcbf29ce484222325L;
        h = mixString(h, System.getProperty("geogenesis.boundaryPorts", "UNSET"));
        h = mixInt(h, sk.incomingStarts);
        h = mixInt(h, sk.mergedIncoming);
        h = mixInt(h, sk.wiredIncoming);
        h = mixInt(h, sk.ports.size());
        for (BoundaryPort p : sk.ports) h = mixPort(h, p);
        h = mixInt(h, sk.mergeEvidence.size());
        for (var e : sk.mergeEvidence) {
            h = mixInt(h, e.anchor());
            h = mixInt(h, e.next());
            h = mixString(h, e.resolution());
        }
        return h;
    }

    static long columnFingerprint(Map<String, Object> root) {
        long h = 0xcbf29ce484222325L;
        for (String path : new String[]{"join.columns", "join.upBeforeJointColumns", "join.downAfterJointColumns",
                "crossRegion.anchorColumns", "crossRegion.nextColumns"}) {
            var col = columnFrom(root, path);
            h = mixString(h, path);
            if (col == null) { h = mixString(h, "NOT_COLLECTED"); continue; }
            for (String k : new String[]{"blockX", "blockZ", "carvedGroundY", "waterSurfaceY", "erosion"})
                h = mixDouble(h, ((Number) col.get(k)).doubleValue());
            h = mixLong(h, Boolean.TRUE.equals(col.get("fillWater")) ? 1 : 0);
            h = mixLong(h, Boolean.TRUE.equals(col.get("lakePlan")) ? 1 : 0);
        }
        return h;
    }

    /** 按点号路径从已采证据里取一列的 sampledColumn（不存在 ⇒ null，不补值）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> columnFrom(Map<String, Object> root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = ((Map<String, Object>) m).get(seg);
        }
        if (!(cur instanceof Map<?, ?> m)) return null;
        Object col = ((Map<String, Object>) m).get("sampledColumn");
        return col instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
    }

    static long mixLong(long h, long v) { return (h ^ v) * 0x100000001b3L; }
    static long mixInt(long h, int v) { return mixLong(h, v); }
    static long mixDouble(long h, double v) { return mixLong(h, Double.doubleToRawLongBits(v)); }

    static long mixString(long h, String s) {
        if (s == null) return mixLong(h, -1);
        for (int i = 0; i < s.length(); i++) h = mixLong(h, s.charAt(i));
        return mixLong(h, s.length());
    }

    static long mixDoubles(long h, double[] a) {
        if (a == null) return mixLong(h, -2);
        h = mixLong(h, a.length);
        for (double d : a) h = mixDouble(h, d);
        return h;
    }

    static long mixInts(long h, int[] a) {
        if (a == null) return mixLong(h, -3);
        h = mixLong(h, a.length);
        for (int v : a) h = mixLong(h, v);
        return h;
    }

    static long mixBools(long h, boolean[] a) {
        if (a == null) return mixLong(h, -4);
        h = mixLong(h, a.length);
        for (boolean v : a) h = mixLong(h, v ? 1 : 0);
        return h;
    }

    static long mixPort(long h, BoundaryPort p) {
        h = mixInt(h, p.senderRegionX());
        h = mixInt(h, p.senderRegionZ());
        h = mixInt(h, p.anchorX());
        h = mixInt(h, p.anchorZ());
        h = mixInt(h, p.nextX());
        h = mixInt(h, p.nextZ());
        h = mixInt(h, p.receiverRegionX());
        h = mixInt(h, p.receiverRegionZ());
        return mixInt(h, p.cell());
    }

    // ---------------- 输出 ----------------

    static int emit(Map<String, Object> root, String abortReason) throws Exception {
        if (abortReason != null) {
            root = new LinkedHashMap<>();
            root.put("abort", abortReason);
            root.put("collectionFailures", fail);
        }
        root.put("exitCode", fail > 0 ? 1 : 0);
        String json = GSON.toJson(root);
        Files.writeString(out.resolve("evidence.json"), json + "\n", StandardCharsets.UTF_8);
        StringBuilder txt = new StringBuilder();
        txt.append("# RiverJunctionEvidenceProbe v2 — 只读生产证据\n");
        for (String s : NOTES) txt.append("# ").append(s).append('\n');
        txt.append("# collectionFailures=").append(fail).append('\n');
        Files.writeString(out.resolve("evidence.txt"), txt.toString(), StandardCharsets.UTF_8);
        System.out.println("[out] " + out.toAbsolutePath().resolve("evidence.json"));
        System.out.print(txt);
        return fail > 0 ? 1 : 0;
    }

    // ---------------- 工具 ----------------

    static List<Integer> worldOf(int cell, int ox, int oz, int step, int n) {
        if (cell < 0 || cell >= n * n) return List.of();
        return List.of(ox + (cell / n) * step, oz + (cell % n) * step);
    }

    static void require(boolean cond, String name, String detail) {
        if (cond) {
            NOTES.add("[OK] " + name);
            return;
        }
        fail++;
        NOTES.add("[COLLECTION-FAIL] " + name + " —— " + detail);
    }
}
