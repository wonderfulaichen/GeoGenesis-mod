package com.geogenesis.acceptance;

import com.geogenesis.GeoGenesisMod;
import com.geogenesis.config.ConfigSafe;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.generator.GeoGenesisGenerator;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 隔离验收支线：客户端 tick 自动新建 geogenesis 世界 → 服务器线程 FULL 加载 3x3 → 读回真实
 * BlockState 与 {@link GeoGenesisTerrain} 计划比对 → 写 UTF-8 报告 + 俯视 PNG。
 *
 * <p>开关（默认关闭）：{@code -Dgeogenesis.acceptance=true}，可选 {@code .out} / {@code .level} /
 * {@code .seed} / {@code .timeoutMs} / {@code .expectModel} / {@code .expectHier}。
 * 存档 id 默认 {@code geogenesis-accept-<时间戳>}，存在即换名或拒绝，<b>绝不删除或覆盖</b>。</p>
 *
 * <h3>退出码（三档，不可混）</h3>
 * <ul>
 *   <li>{@code 0} = 全绿：所有 chunk FULL、所有点 PASS、所有 link PASS、水覆盖已建立且匹配；</li>
 *   <li>{@code 1} = 计划/实际不一致（含任一点 FAIL、link FAIL、link UNVERIFIED、
 *       覆盖 PARTIAL）；</li>
 *   <li>{@code 3} = <b>加载器通过但水覆盖未建立</b>（{@code wetCoverageMissing}：
 *       6 个测点与整个已加载 3x3 内计划零湿列）。不是 0，也不是普通 FAIL ——
 *       它说明"世界能生成、能读回"，但这一轮<b>没有验到水</b>；</li>
 *   <li>{@code 2} = 环境错误（生成器不是 GeoGenesis / 世界没建成 / 超时 / 前置断言失败 / 写报告失败）。</li>
 * </ul>
 *
 * <p>结束顺序：写报告 → {@code server.halt(false)} → 等 {@code isShutdown()} → 带码 halt；
 * 保存期间绝不强杀，超时只记日志由外部结束。</p>
 *
 * <p><b>边界</b>：单世界单种子 6 点 + 3 条 link + 覆盖扫描的定向回归，不是完整实机验收；
 * <b>不能截图</b>，PNG 是每列最顶层方块的 MapColor 俯视回读图。</p>
 */
@Mod.EventBusSubscriber(modid = GeoGenesisMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeHydrologyAcceptance {

    private static final Logger LOG = LogManager.getLogger("geogenesis-acceptance");

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("geogenesis.acceptance", "false"));
    private static final long SEED = Long.getLong("geogenesis.acceptance.seed", 9139912035078620160L);
    private static final long TIMEOUT_MS = Long.getLong("geogenesis.acceptance.timeoutMs", 3_600_000L);
    private static final long SAVE_DEADLINE_MS = 120_000L;
    private static final String EXPECT_MODEL = System.getProperty("geogenesis.acceptance.expectModel");
    private static final String EXPECT_HIER = System.getProperty("geogenesis.acceptance.expectHier");
    private static final ResourceKey<WorldPreset> PRESET =
            ResourceKey.create(Registries.WORLD_PRESET, new ResourceLocation("geogenesis", "geogenesis"));

    /** 跨缝点所在缝的 Z 平面（seam 目标 z=508；缝落在 511/512 之间）。 */
    private static final int SEAM_Z = 512;
    private static final AcceptanceProbe.Target SEAM_TARGET = AcceptanceProbe.TARGETS.get(0);
    private static final AcceptanceProbe.Target WEST_A = AcceptanceProbe.TARGETS.get(1);
    private static final AcceptanceProbe.Target WEST_B = AcceptanceProbe.TARGETS.get(2);
    private static final AcceptanceProbe.Target EAST_A = AcceptanceProbe.TARGETS.get(3);
    private static final AcceptanceProbe.Target EAST_B = AcceptanceProbe.TARGETS.get(4);
    private static final AcceptanceProbe.Target EXT_TARGET = AcceptanceProbe.TARGETS.get(5);

    private enum ClientPhase { WAIT_TITLE, CREATING, WAIT_SERVER }

    private enum ServerPhase { IDLE, CHUNKS, OBSERVE, READ, REPORT, DONE }

    private static volatile ClientPhase clientPhase = ClientPhase.WAIT_TITLE;
    private static ServerPhase serverPhase = ServerPhase.IDLE;

    private static final ArrayDeque<AcceptanceProbe.ChunkJob> chunkQueue = new ArrayDeque<>();
    private static final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
    private static final List<AcceptanceProbe.ChunkRecord> chunkRecords = new ArrayList<>();
    private static final List<AcceptanceProbe.Point> points = new ArrayList<>();
    private static final List<AcceptanceProbe.LinkResult> links = new ArrayList<>();
    private static final List<AcceptanceProbe.Coverage> coverages = new ArrayList<>();

    private static MinecraftServer server;
    private static long startedAt;
    private static long stopRequestedAt;
    private static boolean shutdownStarted;
    private static int chunkDone;
    private static int chunkFailures;
    private static GeoGenesisTerrain terrain;
    private static String generatorClass = "unknown";
    private static String levelId = "unknown";
    private static String setupError;
    private static String lastWaterState;
    private static int observationTicks;
    private static final Map<Long,java.util.BitSet> initialWater=new LinkedHashMap<>();
    private static int newlyUnplannedWater;
    private static int ordinaryNonSources;

    // #region agent log: G49-D FULL/tick evidence; never generates a chunk or changes water
    @SubscribeEvent
    public static void onChunkLoaded(net.minecraftforge.event.level.ChunkEvent.Load event) {
        if (!ENABLED || event.getLevel().isClientSide())return;
        var chunk=event.getChunk();
        if (chunk.getPos().x==-37 && chunk.getPos().z==36)traceWater("CHUNK_LOAD",chunk,true);
    }

    private static void observeWater(String stage, boolean force) {
        var level=server==null?null:server.overworld();
        if (level==null)return;
        var chunk=level.getChunkSource().getChunkNow(-37,36);
        if (chunk!=null)traceWater(stage,chunk,force);
    }

    private static void traceWater(String stage, net.minecraft.world.level.chunk.ChunkAccess chunk, boolean force) {
        var pos=new net.minecraft.core.BlockPos(-588,64,580);
        String state=chunk.getBlockState(pos).toString();
        if (!force && state.equals(lastWaterState))return;
        String message="G49-D level="+levelId+" stage="+stage+" tick="+(server==null?-1:server.getTickCount())
                +" state="+state+" previous="+lastWaterState+" below="+chunk.getBlockState(pos.below())
                +" above="+chunk.getBlockState(pos.above())+" thread="+Thread.currentThread().getName();
        lastWaterState=state;
        LOG.info("[ACCEPT-PHASE] {}",message);
        try {
            Files.writeString(Path.of("D:/Office software/Development Project/GeoGenesis-mod/debug-agent.log"),
                    message+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException failure) { LOG.warn("acceptance phase log failed",failure); }
    }
    // #endregion

    // ===== 客户端：新建世界 =====

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || shutdownStarted) return;
        if (startedAt == 0L) startedAt = System.currentTimeMillis();
        if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
            abort(2, "client timeout in " + clientPhase + " after " + chunkDone + " chunks");
            return;
        }
        if (clientPhase != ClientPhase.WAIT_TITLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null || mc.level != null || mc.getSingleplayerServer() != null) return;
        tryCreateWorld(mc);
    }

    private static void tryCreateWorld(Minecraft mc) {
        clientPhase = ClientPhase.CREATING;
        try {
            String guard = sharedGameDirProblem(mc);
            if (guard != null) {
                abort(2, guard);
                return;
            }
            levelId = uniqueLevelId(mc);
            LOG.info("[accept] creating world id={} seed={} gameDir={}", levelId, SEED, mc.gameDirectory);
            WorldOpenFlows flows = mc.createWorldOpenFlows();
            flows.createFreshLevel(levelId, levelSettings(levelId), new WorldOptions(SEED, true, false),
                    ForgeHydrologyAcceptance::dimensions);
            clientPhase = ClientPhase.WAIT_SERVER;
            LOG.info("[accept] integrated server requested; waiting for BlockState readback");
        } catch (Throwable t) {
            LOG.error("[accept] world creation failed", t);
            abort(2, "world creation failed: " + t);
        }
    }

    /**
     * 存档 id：默认 {@code geogenesis-accept-<epochMillis>}；可用
     * {@code -Dgeogenesis.acceptance.level=<id>} 显式指定。
     *
     * <p>两种情况都<b>绝不删除、绝不覆盖</b>：目录已存在时显式 id 直接失败、
     * 时间戳 id 换名重试。并要求规范化后仍在 {@code saves/} 之内（拒绝路径逃逸）。</p>
     */
    private static String uniqueLevelId(Minecraft mc) throws IOException {
        Path saves = mc.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        String explicit = System.getProperty("geogenesis.acceptance.level");
        if (explicit != null && !explicit.isBlank()) {
            return requireFresh(saves, explicit.trim(), true);
        }
        String base = "geogenesis-accept-" + System.currentTimeMillis();
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                return requireFresh(saves, attempt == 0 ? base : base + "-" + attempt, false);
            } catch (IOException conflict) {
                LOG.warn("[accept] {}", conflict.getMessage());
            }
        }
        throw new IOException("could not allocate a fresh level id under " + saves);
    }

    private static String requireFresh(Path saves, String id, boolean hardFail) throws IOException {
        Path dir = saves.resolve(id).normalize();
        if (!dir.startsWith(saves) || dir.equals(saves)) {
            throw new IOException("level id escapes saves dir: " + id);
        }
        if (Files.exists(dir)) {
            throw new IOException((hardFail ? "refusing existing level id: " : "level id in use: ") + id);
        }
        return id;
    }

    /** 拒绝共用 gameDir：saves/ 下有非本支线目录即报错（不删任何东西）。 */
    private static String sharedGameDirProblem(Minecraft mc) throws IOException {
        Path saves = mc.getLevelSource().getBaseDir();
        if (!Files.isDirectory(saves)) return null;
        List<String> others = new ArrayList<>();
        try (var list = Files.list(saves)) {
            for (Path p : list.toList()) {
                if (Files.isDirectory(p) && !p.getFileName().toString().startsWith("geogenesis-accept")) {
                    others.add(p.getFileName().toString());
                }
            }
        }
        return others.isEmpty() ? null
                : "gameDir looks shared (saves/ has " + others.size() + " other world(s): "
                + String.join(",", others) + ") — use an isolated working directory. gameDir="
                + mc.gameDirectory.getAbsolutePath();
    }

    private static LevelSettings levelSettings(String id) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        rules.getRule(GameRules.RULE_SPAWN_RADIUS).set(0, null);
        return new LevelSettings(id, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                rules, WorldDataConfiguration.DEFAULT);
    }

    private static WorldDimensions dimensions(RegistryAccess access) {
        return access.registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(PRESET).value().createWorldDimensions();
    }

    // ===== 服务器：3x3 FULL（全部必达）=====

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!ENABLED || serverPhase != ServerPhase.IDLE) return;
        if (startedAt == 0L) startedAt = System.currentTimeMillis();
        server = event.getServer();
        try {
            ServerLevel level = server.overworld();
            if (level == null) {
                abort(2, "overworld missing");
                return;
            }
            generatorClass = level.getChunkSource().getGenerator().getClass().getName();
            if (!(level.getChunkSource().getGenerator() instanceof GeoGenesisGenerator)) {
                abort(2, "overworld generator is not GeoGenesisGenerator: " + generatorClass);
                return;
            }
            terrain = GeoGenesisGenerator.buildTerrain(GeoGenesisGenerator.resolveParams());
            setupError = preconditionError();
            if (setupError != null) {
                abort(2, setupError);
                return;
            }
            AcceptanceProbe.planChunks(AcceptanceProbe.TARGETS, chunkQueue);
            if(Boolean.getBoolean("geogenesis.acceptance.reverse")) {
                var reversed=new ArrayList<>(chunkQueue);java.util.Collections.reverse(reversed);
                chunkQueue.clear();chunkQueue.addAll(reversed);
            }
            serverPhase = ServerPhase.CHUNKS;
            LOG.info("[accept] server ready generator={} rivers={} seaLevel={} seed={} chunks={} model={} hier={}",
                    generatorClass, terrain.riversEnabled(), terrain.seaLevel(), level.getSeed(),
                    chunkQueue.size(), modelEffective(), hierEffective());
        } catch (Throwable t) {
            LOG.error("[accept] setup failed", t);
            abort(2, "setup failed: " + t);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED) return;
        observeWater("TICK_"+event.phase,false);
        if (event.phase != TickEvent.Phase.END) return;
        if (serverPhase == ServerPhase.IDLE || serverPhase == ServerPhase.DONE) return;
        MinecraftServer running = event.getServer();
        if (!running.isReady()) return;
        if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
            abort(2, "server timeout after " + chunkDone + " chunks (failed=" + chunkFailures + ")");
            return;
        }
        switch (serverPhase) {
            case CHUNKS -> stepChunk(running);
            case OBSERVE -> {
                if(++observationTicks>=200)serverPhase=ServerPhase.READ;
            }
            case READ -> {
                readTargets();
                serverPhase = ServerPhase.REPORT;
            }
            case REPORT -> finishAndStop();
            default -> { }
        }
    }

    /** 每个服务器 tick 只推进一个 chunk；任何一块未达 FULL 都计 FAIL。 */
    private static void stepChunk(MinecraftServer running) {
        if (chunkQueue.isEmpty()) {
            observeWater("ALL_FULL",true);
            captureInitialWater();
            serverPhase = ServerPhase.OBSERVE;
            LOG.info("[accept] chunk load done: ok={} failed={}", chunkDone, chunkFailures);
            return;
        }
        AcceptanceProbe.ChunkJob job = chunkQueue.poll();
        Loaded loaded = load(running, job.cx(), job.cz());
        chunkRecords.add(new AcceptanceProbe.ChunkRecord(job.cx(), job.cz(),
                loaded.ok(), loaded.why(), loaded.ms()));
        if (!loaded.ok()) {
            chunkFailures++;
            LOG.error("[accept] chunk ({},{}) NOT ready: {}", job.cx(), job.cz(), loaded.why());
            return;
        }
        chunkDone++;
        LOG.info("[accept] FULL ({},{}) {} ms [{} ok, {} failed, {} left]",
                job.cx(), job.cz(), loaded.ms(), chunkDone, chunkFailures, chunkQueue.size());
    }

    private record Loaded(int cx, int cz, LevelChunk chunk, boolean ok, String why, long ms) { }

    /** 强制 FULL 并严格确认（判据见 {@link AcceptanceProbe#isReady}）。已加载的直接复用。 */
    private static Loaded load(MinecraftServer running, int cx, int cz) {
        LevelChunk cached = chunks.get(ChunkPos.asLong(cx, cz));
        if (cached != null) return new Loaded(cx, cz, cached, true, "", 0L);
        long t0 = System.nanoTime();
        try {
            ServerLevel level = running.overworld();
            level.setChunkForced(cx, cz, true);
            Object access = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            LevelChunk now = level.getChunkSource().getChunkNow(cx, cz);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (!AcceptanceProbe.isReady(access, now)) {
                return new Loaded(cx, cz, null, false,
                        "access=" + (access == null ? "null" : access.getClass().getSimpleName())
                                + " chunkNow=" + (now == null ? "null" : "LevelChunk"), ms);
            }
            chunks.put(ChunkPos.asLong(cx, cz), now);
            return new Loaded(cx, cz, now, true, "", ms);
        } catch (Throwable t) {
            LOG.error("[accept] chunk ({},{}) threw", cx, cz, t);
            return new Loaded(cx, cz, null, false, "throw:" + t, 0L);
        }
    }

    // ===== 读回 + 比对 =====

    private static void readTargets() {
        verifyWaterPolicy();
        observeWater("READBACK",true);
        AcceptRun run = new AcceptRun(chunks, terrain);
        for (AcceptanceProbe.Target target : AcceptanceProbe.TARGETS) {
            points.add(run.checkPoint(target));
        }
        links.addAll(run.checkLinks());
        coverages.addAll(run.checkCoverage());
        auditWaterPlacement();
    }

    private static void verifyWaterPolicy() {
        try {
            var method=GeoGenesisGenerator.class.getDeclaredMethod("waterState",boolean.class,int.class,int.class);
            method.setAccessible(true);
            for(boolean model:new boolean[]{false,true})for(int y:new int[]{63,64,65}) {
                var state=(net.minecraft.world.level.block.state.BlockState)method.invoke(null,model,y,64);
                boolean expectedSource=y<64 || (model && y==64);
                if(!state.is(net.minecraft.world.level.block.Blocks.WATER) || state.getFluidState().isSource()!=expectedSource)
                    throw new IllegalStateException("water policy mismatch model="+model+" y="+y+" state="+state);
            }
            LOG.info("[accept] real production water policy: model source / legacy flowing / waterfall flowing PASS");
        } catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }

    private static java.util.BitSet waterBits(LevelChunk chunk) {
        var bits=new java.util.BitSet();var pos=new net.minecraft.core.BlockPos.MutableBlockPos();
        int min=chunk.getMinBuildHeight();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=min;y<chunk.getMaxBuildHeight();y++) {
            pos.set(chunk.getPos().getMinBlockX()+x,y,chunk.getPos().getMinBlockZ()+z);
            if(AcceptanceProbe.isWaterBody(chunk.getBlockState(pos)))bits.set((x*16+z)*384+y-min);
        }
        return bits;
    }
    private static void captureInitialWater() {
        chunks.forEach((key,chunk)->initialWater.put(key,waterBits(chunk)));
    }
    private static void auditWaterPlacement() {
        var rows=new ArrayList<String>();rows.add("x,y,z,state,planned,initiallyWet");
        int source=0,flowing=0,offPlan=0;newlyUnplannedWater=0;
        for(var entry:chunks.entrySet()) {
            var chunk=entry.getValue();var bits=waterBits(chunk);int min=chunk.getMinBuildHeight();
            for(int bit=bits.nextSetBit(0);bit>=0;bit=bits.nextSetBit(bit+1)) {
                int col=bit/384,y=min+bit%384,x=chunk.getPos().getMinBlockX()+col/16,z=chunk.getPos().getMinBlockZ()+col%16;
                var p=AcceptanceProbe.plan(terrain.sampleCell(x,z),terrain.riversEnabled(),terrain.seaLevel());
                boolean planned=p.water() && y>p.surfaceY() && y<=p.waterTopY();
                var state=chunk.getBlockState(new net.minecraft.core.BlockPos(x,y,z));
                if(planned && y<=p.waterTopBlock()) {
                    if(state.getFluidState().isSource())source++;else flowing++;
                }
                if(!planned) {
                    offPlan++;boolean initial=initialWater.get(entry.getKey()).get(bit);
                    if(!initial)newlyUnplannedWater++;
                    rows.add(x+","+y+","+z+",\""+state+"\",false,"+initial);
                }
            }
        }
        ordinaryNonSources=flowing;
        rows.add("# ordinarySources="+source+" ordinaryNonSources="+flowing+" offPlan="+offPlan+" newOffPlan="+newlyUnplannedWater);
        try { Files.createDirectories(outDir());Files.write(outDir().resolve("water-placement.csv"),rows,StandardCharsets.UTF_8); }
        catch(IOException e){throw new IllegalStateException(e);}
        LOG.info("[accept] water placement sources={} nonSources={} offPlan={} newOffPlan={}",source,flowing,offPlan,newlyUnplannedWater);
    }

    /** 一次验收读回的上下文（计划查询 + 计划窗口匹配 + 逐点/link/覆盖检查）。 */
    private static final class AcceptRun {
        private static final int LINK_RADIUS = 12;

        private final boolean hydrologyOn;
        private final double seaLevel;
        private final Map<Long, LevelChunk> chunkMap;
        private final AcceptanceProbe.PlanRef planRef;
        private final AcceptanceProbe.WindowWet window;
        private final AcceptanceProbe.WetFn matched;

        AcceptRun(Map<Long, LevelChunk> chunkMap, GeoGenesisTerrain terrain) {
            this.chunkMap = chunkMap;
            this.hydrologyOn = terrain.riversEnabled();
            this.seaLevel = terrain.seaLevel();
            this.planRef = (x, z) -> AcceptanceProbe.plan(
                    terrain.sampleCell(x, z), hydrologyOn, seaLevel);
            this.window = AcceptanceProbe.windowOf(this::chunkAt);
            this.matched = AcceptanceProbe.matchedMask(planRef, window);
        }

        private LevelChunk chunkAt(int cx, int cz) {
            return chunkMap.get(ChunkPos.asLong(cx, cz));
        }

        AcceptanceProbe.Point checkPoint(AcceptanceProbe.Target target) {
            LevelChunk centre = chunkAt(target.x() >> 4, target.z() >> 4);
            if (centre == null) {
                LOG.error("[accept] point {} ({},{}) centre chunk missing", target.label(),
                        target.x(), target.z());
                return AcceptanceProbe.missing(target, "centre chunk not loaded");
            }
            try {
                AcceptanceProbe.Plan plan = planRef.at(target.x(), target.z());
                AcceptanceProbe.Column actual = AcceptanceProbe.read(centre, target.x(), target.z());
                AcceptanceProbe.Component comp =
                        AcceptanceProbe.component(matched, target.x(), target.z(), LINK_RADIUS);
                AcceptanceProbe.Verdict cont = AcceptanceProbe.continuity(comp);
                String gate = AcceptanceProbe.pointGate(plan, actual, target.kind());
                AcceptanceProbe.Point point = AcceptanceProbe.evaluate(target, plan, actual, cont);
                if (gate != null) point = fail(point, "gate=" + gate);
                LOG.info("[accept] {} ({},{}) {} | plan[{}] | actual[{}] | cont[{}]",
                        target.label(), target.x(), target.z(), point.status(), plan, actual, cont.text());
                return point;
            } catch (Throwable t) {
                LOG.error("[accept] point {} ({},{}) read failed", target.label(), target.x(), target.z(), t);
                return AcceptanceProbe.error(target, String.valueOf(t));
            }
        }

        /** 把点降级为 FAIL 并追加原因。 */
        private AcceptanceProbe.Point fail(AcceptanceProbe.Point p, String why) {
            return new AcceptanceProbe.Point(p.target(), p.plan(), p.actual(), p.continuityNote(),
                    "FAIL", false, p.checks() + " " + why, p.waterNote());
        }

        /** 三条显式连通要求（实际侧用计划窗口匹配，不认地下水）。 */
        List<AcceptanceProbe.LinkResult> checkLinks() {
            List<AcceptanceProbe.LinkResult> out = new ArrayList<>();
            out.add(AcceptanceProbe.checkLink(AcceptanceProbe.Link.of("westLipAxis",
                    WEST_A.x(), WEST_A.z(), WEST_B.x(), WEST_B.z()), planRef, window));
            out.add(AcceptanceProbe.checkLink(AcceptanceProbe.Link.of("eastLipDiagonal",
                    EAST_A.x(), EAST_A.z(), EAST_B.x(), EAST_B.z()), planRef, window));
            AcceptanceProbe.LinkResult seam = seamLink();
            if (seam != null) out.add(seam);
            for (AcceptanceProbe.LinkResult r : out) {
                LOG.info("[accept] link {} status={} {}", r.label(), r.status(), r.why());
            }
            return out;
        }

        /** 跨缝：从计划掩码查真实后继（不猜），再要求实际同样连通。 */
        private AcceptanceProbe.LinkResult seamLink() {
            AcceptanceProbe.WetFn plan = AcceptanceProbe.planMaskOf(planRef);
            int[] succ = AcceptanceProbe.planSuccessorAcross(plan,
                    SEAM_TARGET.x(), SEAM_TARGET.z(), SEAM_Z, LINK_RADIUS);
            if (succ == null) {
                // 计划侧都找不到跨 z=SEAM_Z 的 8 连通后继 ⇒ 这条要求本次没验到，不是通过。
                return new AcceptanceProbe.LinkResult("seamContinuation",
                        AcceptanceProbe.LinkStatus.UNVERIFIED,
                        "noPlannedSuccessorAcrossZ" + SEAM_Z + " (unverified)", List.of());
            }
            AcceptanceProbe.Link link = AcceptanceProbe.Link.of("seamContinuation",
                    SEAM_TARGET.x(), SEAM_TARGET.z(), succ[0], succ[1]);
            return AcceptanceProbe.checkLink(link, planRef, window);
        }

        /**
         * 水覆盖：① 扩展 chunk 单独统计（计划全干是合法的，只记 dryCoverage）；
         * ② 全部已加载 chunk 合计扫描 —— 若整片零计划湿列，则明确
         * {@code wetCoverageMissing}，整体不绿。
         */
        List<AcceptanceProbe.Coverage> checkCoverage() {
            List<AcceptanceProbe.Coverage> out = new ArrayList<>();
            out.add(AcceptanceProbe.coverage("extChunk",
                    List.of(new int[]{EXT_TARGET.x() >> 4, EXT_TARGET.z() >> 4}),
                    planRef, window));
            out.add(AcceptanceProbe.coverage("loaded3x3", loadedChunkCoords(),
                    planRef, window));
            for (AcceptanceProbe.Coverage c : out) {
                LOG.info("[accept] coverage {} status={} planWet={} matched={}",
                        c.label(), c.status(), c.planWetColumns(), c.matchedColumns());
            }
            return out;
        }

        /**
         * 已加载 chunk 的坐标表。
         *
         * <p>★ <b>不手工解 {@code ChunkPos.asLong}</b>：MC 的打包格式是
         * <b>x 在低 32 位、z 在高 32 位</b>（实测 {@code asLong(528,-2400)} 的
         * {@code >>32 == -2400}、{@code (int) == 528}）。此前写成 {@code (key>>32, key)}
         * 会把 (528,-2400) 解成 (-2400,528)，扫描整片错误区域且零命中。
         * 这里直接取 {@link LevelChunk#getPos()}，<b>根本不重写编码</b>。</p>
         */
        private List<int[]> loadedChunkCoords() {
            return AcceptanceProbe.chunkCoordsOf(chunkPositionsOf(chunkMap.values()));
        }
    }

    /** 已加载 chunk → {@link ChunkPos}（唯一取坐标的入口；坐标编码见 {@link AcceptanceProbe#chunkCoordsOf}）。 */
    static List<ChunkPos> chunkPositionsOf(java.util.Collection<LevelChunk> chunks) {
        List<ChunkPos> out = new ArrayList<>(chunks.size());
        for (LevelChunk chunk : chunks) out.add(chunk.getPos());
        return out;
    }

    // ===== 报告 + 停机 =====

    /**
     * 汇总判定。
     *
     * <p>退出码优先级：环境错误 2 &gt; 计划不一致 1 &gt; 覆盖未建立 3 &gt; 0。
     * 覆盖 MISSING 时若其它一切正常，给 {@code 3}（loader 通过但水覆盖 pending），
     * <b>绝不给 0</b>。</p>
     */
    private static void finishAndStop() {
        serverPhase = ServerPhase.DONE;
        long defects = points.stream().filter(p -> !p.pass()).count();
        long linkFails = links.stream()
                .filter(r -> r.status() == AcceptanceProbe.LinkStatus.FAIL).count();
        long linkUnverified = links.stream()
                .filter(r -> r.status() == AcceptanceProbe.LinkStatus.UNVERIFIED).count();
        String linkGate = AcceptanceProbe.linkGate(links);
        List<String> coverageGates = new ArrayList<>();
        boolean coverageMissing = false;
        for (AcceptanceProbe.Coverage c : coverages) {
            if (c.missing()) coverageMissing = true;
            String g = AcceptanceProbe.coverageGate(c);
            if (g != null) coverageGates.add(g);
        }
        if(newlyUnplannedWater>0)coverageGates.add("newUnplannedWater="+newlyUnplannedWater);
        if(terrain.usesModelPipeline() && ordinaryNonSources>0)coverageGates.add("ordinaryNonSources="+ordinaryNonSources);
        boolean loaderOk = defects == 0 && chunkFailures == 0 && !points.isEmpty();
        boolean waterOk = linkGate == null && coverageGates.isEmpty();
        int code;
        if (!loaderOk || linkFails > 0) {
            code = 1;
        } else if (linkUnverified > 0 || !coverageGates.isEmpty()) {
            // 覆盖/连通没验到（或只验到一部分）⇒ 不绿。若纯粹是"整片零计划湿列"
            // 且没有别的失败，给 3 区分"loader 通过但水覆盖 pending"。
            code = coverageMissing && linkUnverified == 0 && coverageGates.size() == 1 ? 3 : 1;
        } else {
            code = waterOk ? 0 : 1;
        }
        code = writeReport(code, defects, linkFails, linkUnverified, linkGate, coverageGates);
        LOG.info("[accept] VERDICT code={} loaderOk={} waterOk={} defects={} linkFails={} "
                        + "linkUnverified={} coverageMissing={} chunksOk={} chunksFailed={}",
                code, loaderOk, waterOk, defects, linkFails, linkUnverified, coverageMissing,
                chunkDone, chunkFailures);
        beginShutdown(code);
    }

    private static int writeReport(int code, long defects, long linkFails, long linkUnverified,
                                   String linkGate, List<String> coverageGates) {
        try {
            Files.createDirectories(outDir());
            Path report = outDir().resolve("acceptance-report.txt");
            AcceptanceProbe.writeReport(report,
                    headerLines(code, defects, linkFails, linkUnverified, linkGate, coverageGates),
                    chunkRecords, points, links, coverages);
            Path png = outDir().resolve("acceptance-topview.png");
            AcceptanceProbe.writeTopView(png, AcceptanceProbe.panels(AcceptanceProbe.TARGETS),
                    (cx, cz) -> chunks.get(ChunkPos.asLong(cx, cz)));
            LOG.info("[accept] report={} topview={}", report.toAbsolutePath(), png.toAbsolutePath());
            return code;
        } catch (Throwable t) {
            LOG.error("[accept] report write failed", t);
            return 2;
        }
    }

    private static List<String> headerLines(int code, long defects, long linkFails,
                                            long linkUnverified, String linkGate,
                                            List<String> coverageGates) {
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        lines.add("# geogenesis acceptance — BlockState readback (NOT a game screenshot)");
        lines.add("# scope: 1 seed, 6 target points, 3 explicit links, coverage scan — NOT a full acceptance");
        lines.add("# exitCode 0=green 1=planMismatch 2=envError 3=loaderPassedButWaterCoveragePending");
        lines.add("schema=geogenesis-acceptance/4");
        lines.add("verdict=" + verdictText(code));
        lines.add("exitCode=" + code);
        lines.add("loaderVerdict=" + (code == 3 ? "PASSED" : code == 0 ? "PASSED" : "CHECK"));
        lines.add("waterVerdict=" + (code == 0 ? "PASSED"
                : code == 3 ? "COVERAGE_PENDING" : "FAILED_OR_INCOMPLETE"));
        lines.add("runEpochMillis=" + now);
        lines.add("seedRequested=" + SEED);
        lines.add("seedActual=" + (server != null && server.overworld() != null
                ? server.overworld().getSeed() : "n/a"));
        lines.add("levelId=" + levelId);
        lines.add("gameDir=" + Minecraft.getInstance().gameDirectory.getAbsolutePath());
        lines.add("generatorClass=" + generatorClass);
        lines.add("generatorIsGeoGenesis=" + GeoGenesisGenerator.class.getName().equals(generatorClass));
        lines.add("riverEnabled=" + (terrain != null && terrain.riversEnabled()));
        lines.add("seaLevel=" + (terrain != null ? terrain.seaLevel() : "n/a"));
        lines.addAll(envLines());
        lines.add("chunksLoaded=" + chunkDone);
        lines.add("chunksFailed=" + chunkFailures);
        lines.add("chunkLoadPolicy=all3x3Required");
        lines.add("chunkLoadOrder="+(Boolean.getBoolean("geogenesis.acceptance.reverse")?"reverse":"forward"));
        lines.add("postFullObservationTicks="+observationTicks);
        lines.add("ordinaryNonSources="+ordinaryNonSources);
        lines.add("newUnplannedWater="+newlyUnplannedWater);
        lines.add("spreadScope=loaded36ChunksAfterAllFULL_vs200Ticks;preFULLSpreadNotExcluded");
        lines.add("points=" + points.size());
        lines.add("defects=" + defects);
        lines.add("links=" + links.size());
        lines.add("linkFails=" + linkFails);
        lines.add("linkUnverified=" + linkUnverified);
        lines.add("connectivity=8neighbourPlanWindowMatched");
        lines.add("waterIdentity=Blocks.WATER|ICE (excludes lava, vegetation, waterlogged solids)");
        lines.add("columnCheck=fullColumnNoEarlyBreak");
        lines.add("topNonWaterNote=topmost non-air non-water; INCLUDES vegetation, NOT terrain surface");
        if (linkGate != null) lines.add("linkGate=" + linkGate);
        for (String g : coverageGates) lines.add("coverageGate=" + g);
        lines.add("reportKind=BlockStateReadback");
        lines.add("coverage=targetedRegressionOnly");
        for (AcceptanceProbe.Coverage c : coverages) {
            lines.add("dryCoverage." + c.label() + "=" + (c.planWetColumns() == 0
                    ? "plannedDry(legal)" : "plannedWet=" + c.planWetColumns()));
        }
        lines.add("topviewFile=acceptance-topview.png");
        lines.add("topviewNote=top-down MapColor of topmost block per column; NOT a game screenshot");
        lines.add("topviewCoverage=only loaded chunks drawn (dark gray 0x202020 = not loaded)");
        return lines;
    }

    private static String verdictText(int code) {
        return switch (code) {
            case 0 -> "PASS";
            case 3 -> "COVERAGE_PENDING";
            case 1 -> "FAIL";
            default -> "ERROR";
        };
    }

    /** model / 分层水文实际生效值；口径与 {@code HydrologyChunkEngine} 构造器一致。 */
    private static List<String> envLines() {
        List<String> lines = new ArrayList<>();
        lines.add("modelPipelineConfig=" + modelConfigValue());
        lines.add("modelPipelineProperty=" + System.getProperty("geogenesis.modelPipeline"));
        lines.add("modelPipelineEffective=" + modelEffective());
        lines.add("hierarchicalHydrology=" + hierEffective());
        lines.add("hierStrictContract=" + System.getProperty("geogenesis.hierStrictContract", "true"));
        lines.add("skeletonDeterministic="
                + ConfigSafe.bool(GeoGenesisConfig.INSTANCE.hydrologyDeterministicSkeleton, true));
        return lines;
    }

    private static boolean configLoaded() {
        try {
            GeoGenesisConfig.INSTANCE.hydrologyModelPipelineEnabled.get();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean modelConfigValue() {
        try {
            return GeoGenesisConfig.INSTANCE.hydrologyModelPipelineEnabled.get();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 配置优先；仅配置不可读时才看系统属性（与 HydrologyChunkEngine 相同，不是 OR）。 */
    private static boolean modelEffective() {
        return configLoaded() ? modelConfigValue()
                : Boolean.parseBoolean(System.getProperty("geogenesis.modelPipeline"));
    }

    private static boolean hierEffective() {
        return Boolean.getBoolean("geogenesis.hierarchicalHydrology");
    }

    private static String preconditionError() {
        StringBuilder sb = new StringBuilder();
        if (EXPECT_MODEL != null && Boolean.parseBoolean(EXPECT_MODEL) != modelEffective()) {
            sb.append("expectModel=").append(EXPECT_MODEL)
                    .append(" effective=").append(modelEffective()).append("; ");
        }
        if (EXPECT_HIER != null && Boolean.parseBoolean(EXPECT_HIER) != hierEffective()) {
            sb.append("expectHier=").append(EXPECT_HIER)
                    .append(" effective=").append(hierEffective()).append("; ");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Path outDir() {
        return Paths.get(System.getProperty("geogenesis.acceptance.out", "acceptance-out"));
    }

    // ===== 停机：先保存再带码退出，绝不强杀 =====

    /**
     * ① {@code server.halt(false)} 退出 tick 循环 → 触发 {@code stopServer()}（保存区块、
     * 关闭维度、释放世界锁）；② 守护线程等 {@code isShutdown()}；③ 才带码 halt。
     *
     * <p>不走 {@code mc.stop()} 正常路径：{@code Minecraft.destroy()} 结尾无条件
     * {@code System.exit(0)}，会把判定码冲成 0。也不在保存完成前强杀。</p>
     */
    private static void beginShutdown(int code) {
        if (shutdownStarted) return;
        shutdownStarted = true;
        stopRequestedAt = System.currentTimeMillis();
        try {
            if (server != null) {
                server.halt(false);
                LOG.info("[accept] server halt requested; waiting for save + world-lock release");
            } else {
                LOG.warn("[accept] no server handle; nothing to save");
            }
        } catch (Throwable t) {
            LOG.error("[accept] server.halt failed", t);
        }
        Thread watchdog = new Thread(() -> awaitSaveThenHalt(code), "geogenesis-accept-shutdown");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void awaitSaveThenHalt(int code) {
        long deadline = stopRequestedAt + SAVE_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (server == null || server.isShutdown()) {
                LOG.info("[accept] server stopped and world saved; exiting with code {}", code);
                noteSaveResult(true);
                System.out.flush();
                System.err.flush();
                Runtime.getRuntime().halt(code);
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        noteSaveResult(false);
        LOG.error("[accept] server did not stop within {}ms — NOT forcing exit; "
                        + "world may not be fully saved. End the process externally and read {}",
                SAVE_DEADLINE_MS, outDir().resolve("acceptance-report.txt").toAbsolutePath());
    }

    private static void noteSaveResult(boolean saved) {
        try {
            Files.writeString(outDir().resolve("acceptance-report.txt"),
                    "saveCompleted=" + saved + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            LOG.error("[accept] could not append save status", t);
        }
    }

    /** 环境级失败：写最小报告后同样走"先保存再退出"。 */
    private static void abort(int code, String message) {
        LOG.error("[accept] FAIL({}): {}", code, message);
        setupError = message;
        serverPhase = ServerPhase.DONE;
        if (shutdownStarted) return;
        try {
            Files.createDirectories(outDir());
            List<String> lines = new ArrayList<>();
            lines.add("# geogenesis acceptance — BlockState readback (NOT a game screenshot)");
            lines.add("schema=geogenesis-acceptance/4");
            lines.add("verdict=ERROR");
            lines.add("exitCode=" + code);
            lines.add("loaderVerdict=ERROR");
            lines.add("waterVerdict=NOT_EVALUATED");
            lines.add("seedRequested=" + SEED);
            lines.add("levelId=" + levelId);
            lines.add("generatorClass=" + generatorClass);
            lines.add("generatorIsGeoGenesis=" + GeoGenesisGenerator.class.getName().equals(generatorClass));
            lines.add("chunksLoaded=" + chunkDone);
            lines.add("chunksFailed=" + chunkFailures);
            lines.add("points=" + points.size());
            lines.add("error=" + message);
            AcceptanceProbe.writeReport(outDir().resolve("acceptance-report.txt"),
                    lines, chunkRecords, points, links, coverages);
        } catch (Throwable t) {
            LOG.error("[accept] error report failed", t);
        }
        beginShutdown(code);
    }

    private ForgeHydrologyAcceptance() { }
}
