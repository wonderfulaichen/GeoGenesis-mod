package com.geogenesis.client.reveal;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.worldgen.spawn.SpawnSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <b>进世界落成动画</b>（★ v37，步骤 A）。
 *
 * <h2>用户需求</h2>
 * <blockquote>
 * 「动画不是不适用，而是就应该做的功能，我需要进世界落成动画」
 * </blockquote>
 *
 * <h2>流程（对照参考项目 SiteRevealScreen，已按用户裁定简化）</h2>
 * <pre>
 *   ① 开场：沙盘那张图从正交 morph 到透视（INTRO_MS=3000）
 *   ② 空中等待：镜头慢绕，等真实区块到货
 *   ③ 让位：柱子按距离波次一块块淡出，露出真实地形
 *   ④ 收尾消散：剩下的柱子一起散（300ms，镜头不动）
 *   ⑤ 落机：空中 → 落点上空14格 → 玩家眼睛
 * </pre>
 *
 * <h2>★ 异常兜底：静默退回原版（用户裁定 (a)）</h2>
 * <p>整个 {@code render} 与 {@code tick} 都包在 try/catch 里。
 * 一旦抛任何异常，<b>立即关闭本屏、回到原版加载流程</b> ——
 * 玩家至少能看到正常的加载画面，而不是卡在一个坏掉的动画里。</p>
 *
 * <h2>与参考项目的关键差异</h2>
 * <ul>
 *   <li><b>不用 mixin</b>：参考项目靠 {@code MinecraftSiteLoadMixin} 换屏，
 *       我们用 Forge 的 {@code ScreenEvent.Opening} 在原版加载屏打开前替换 ——
 *       不需要新增 mixin 基础设施（本项目此前零 mixin，引入风险大）。</li>
 *   <li><b>不做 Sodium 绘制列表探测</b>：退化为"数据到货 + 余量"。</li>
 *   <li><b>无换源</b>：我们只有一份数据。</li>
 *   <li><b>无蓝图/门廊</b>：没有建筑系统。</li>
 * </ul>
 */
public class RevealScreen extends Screen {

    /** 总上限：再好看也不能把人关在外面（毫秒）。参考项目 {@code HARD_CAP_MS}。 */
    private static final long HARD_CAP_MS = 60_000L;
    /** 一直没有世界可渲染就认输（毫秒）。 */
    private static final int NO_WORLD_MS = 30_000;
    /** 不透明底淡出的时长（毫秒）。 */
    private static final int BACKDROP_FADE_MS = 900;

    private final Screen fallback;
    private final long openedAt = System.currentTimeMillis();

    private final RevealWorldRenderer world = new RevealWorldRenderer();
    private RevealSchedule schedule;
    private DescentPath descent;

    private boolean begun;
    private boolean descending;
    private long loginAt;
    private long collapseAt = -1L;
    /** 一旦出错就置位：后续帧直接退回原版。 */
    private boolean failed;

    private final float[] eyeBuf = new float[3];

    /**
     * @param fallback 出问题/正常结束时交回的原版屏幕（可为 null = 直接关屏）
     */
    public RevealScreen(Screen fallback) {
        super(Component.literal("GeoGenesis"));
        this.fallback = fallback;
    }

    @Override
    public boolean isPauseScreen() {
        // 一暂停服务端就不 tick，区块发不出来
        return false;
    }

    @Override
    protected void init() {
        // 隐藏 HUD/手（参考项目第 253-259 行：第一人称的手会挡在画面底部）
        if (this.minecraft != null) {
            this.minecraft.gameRenderer.setRenderHand(false);
        }
        // ★ v38：交给相机 hook 接管真实世界的相机/视野
        //   （不接管的话动画只是"盖在上面的一张图"，镜头根本不动）
        RevealCameraHook.setActive(this);
        RevealScreenHook.setActive(this);
    }

    /** 落机航线（相机 hook 用；null = 还没开始落机 ⇒ 不接管相机）。 */
    public DescentPath descentPath() {
        return descent;
    }

    @Override
    public void removed() {
        if (this.minecraft != null) {
            this.minecraft.gameRenderer.setRenderHand(true);
        }
        // ★ v38：撤销相机接管（否则玩家进世界后镜头还锁在动画位置，动不了）
        RevealCameraHook.setActive(null);
        // ★ v38：恢复玩家的无敌状态（动画期间替他扛了伤害）
        restoreInvulnerability(this.minecraft);
        // 通知 hook：动画结束，后续 ReceivingLevelScreen 不再拦
        RevealScreenHook.onRevealClosed();
        RevealDataHolder.clear();
    }

    /**
     * 是否还要"占住"这个界面（★ v38，照参考项目 {@code SiteRevealScreen.holdsHandoff}）。
     *
     * <p>为 true 时 {@link RevealScreenHook} 会拦掉原版登录后的「收区块」过渡屏
     * （{@code ReceivingLevelScreen}），否则动画会被它盖掉。
     * 超过 {@link #HARD_CAP_MS} 就返回 false，保证不会无限拒绝交回。</p>
     */
    public boolean holdsHandoff() {
        return System.currentTimeMillis() - openedAt < HARD_CAP_MS;
    }

    /** 首次渲染是否已打过日志。 */
    private boolean renderedOnce;
    /** tick 计数（诊断）。 */
    private int tickCount;
    /** 上次打 tick 日志的时刻。 */
    private long lastTickLog;

    private void bailOut(String why) {
        if (failed) return;
        failed = true;
        com.mojang.logging.LogUtils.getLogger()
                .warn("[GeoGenesis] 落成动画异常，静默退回原版加载屏：{}", why);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(fallback);
        }
    }

    @Override
    public void tick() {
        if (failed) return;
        try {
            advance();
        } catch (Throwable t) {
            bailOut(t.toString());
        }
    }

    /**
     * <b>推进一帧</b>（★ v42 抽出；★ v45 改为"加载期间也推进"）。
     *
     * <h2>★ 为什么不能只靠 {@code tick()}</h2>
     * <p>日志铁证：{@code RevealScreen 首次渲染} 在 06:09:57 就出现了，
     * 但下一个日志是 84 秒后的 {@code 撞到 HARD_CAP} —— 中间<b>零条 tick 日志</b>。
     * ⇒ 原版 {@code Minecraft} 只在<b>已有 world</b> 时调 {@code screen.tick()}。
     * 所以由 {@code render()} 也调一次。</p>
     *
     * <h2>★ v45：{@code level == null} 时也要推进并画沙盘</h2>
     * <p>用户实测「从点击完成到进入加载世界画面这段时间全程黑屏无动画」，
     * 并指出：「压缩时间本身就不是解决问题的办法，设计加载动画也不是这么处理加载问题的。」
     * —— 加载动画就该<b>在加载期间给出有意义的内容</b>。</p>
     *
     * <p>参考项目 {@code SiteRevealScreen.renderBackground} 第 746 行：
     * {@code advance(frameNow);} <b>无条件调用</b>，里面
     * {@code world.tick(mc, now)} 同样无条件 —— 它整段动画都用缓存沙盘数据画，
     * <b>完全不依赖真世界</b>。真世界只负责"登录后把相机交过去 + 让柱子露出真地形"。</p>
     *
     * <p>⇒ 这里改成：<b>镜头推进永远无条件执行</b>；
     * 只有"区块让位 / 判断能否落机"才需要 {@code level}。</p>
     */
    private void advance() {
        long now = System.currentTimeMillis();
        // 诊断：前 10 帧 + 每 5 秒打一次
        tickCount++;
        if (tickCount <= 10 || now - lastTickLog > 5000L) {
            lastTickLog = now;
            Minecraft m = Minecraft.getInstance();
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] advance#{} elapsed={}ms level={} player={} begun={} data={}",
                    tickCount, now - openedAt,
                    m.level != null, m.player != null, begun, data() != null);
        }
        if (now - openedAt >= HARD_CAP_MS) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] 撞到 HARD_CAP，收场");
            finish();
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        // ★ v45【关键】动画不依赖 level：数据来自交接的沙盘缓存。
        //   没有 world 也要开播 —— 这一段正是"加载中那 25 秒"。
        if (!begun) {
            beginReveal();
            if (!begun) {
                if (!noDataLogged) {
                    noDataLogged = true;
                    com.mojang.logging.LogUtils.getLogger().info(
                            "[RevealDiag] beginReveal 失败（data={}）", data() != null);
                }
                return;
            }
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] beginReveal 成功，动画开始（level={}）", mc.level != null);
        }

        // 镜头推进：无条件（与参考项目 world.tick 一致）
        world.tick(now);

        // 以下是"要真世界才能做"的部分
        if (mc.level == null) {
            // 世界还没好：只播开场 morph，别让位、别落机
            if (now - openedAt > NO_WORLD_MS) {
                bailOut("一直没有世界可渲染");
            }
            return;
        }
        if (loginAt == 0L) {
            loginAt = now;
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] 世界已就绪（等了 {}ms），交给真世界", now - openedAt);
            // ★ 世界就绪才设无敌（此时才有 player）
            applyInvulnerability(mc);
        }

        advanceSchedule(now);

        if (!descending && canStartDescent(now)) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] 开始落机（clock={}ms）", (int) world.clockMs());
            startDescent(now);
        }
        if (descending) {
            descent.tick(now);
            if (descent.done()) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "[RevealDiag] 落机完成，收场");
                finish();
            }
        }
    }

    /** beginReveal 失败的日志只打一次（避免每帧刷屏）。 */
    private boolean noDataLogged;

    /**
     * <b>开始播动画</b>（★ v45：不再要求 {@code player} 存在）。
     *
     * <h2>★ 为什么可以在"没有 world / 没有 player"时启动</h2>
     * <p>参考项目 {@code SiteRevealWorldRenderer} 整段开场都用<b>交接来的沙盘数据</b>
     * 画（{@code intro.field()}），不碰真世界 —— 所以它在加载期间就能播。</p>
     *
     * <p>落点坐标同样来自交接（{@code SpawnSelection.peek()}），
     * 不需要 {@code mc.player}。玩家位置的读取放到"确实有 world"之后。</p>
     */
    private void beginReveal() {
        Minecraft mc = Minecraft.getInstance();
        TerrainFieldData d = data();
        if (d == null) return;    // 没有沙盘数据 ⇒ 播不了（这才是真正的必要前提）

        // 落点：优先用玩家选的；没有就用数据窗口中心
        SpawnSelection.Pending p = SpawnSelection.peek();
        int sx, sz;
        if (p != null) {
            sx = p.x();
            sz = p.z();
        } else if (mc.player != null) {
            sx = mc.player.blockPosition().getX();
            sz = mc.player.blockPosition().getZ();
        } else {
            sx = d.originX + d.blocksWide / 2;
            sz = d.originZ + d.blocksHigh / 2;
        }
        // 落点 y：数据里对应细格的真实高度（没有就取海平面）
        int sy = surfaceAt(d, sx, sz);

        // --- 正交相机：继承沙盘最后一帧（v45）---
        com.geogenesis.client.reveal.RevealDataHolder.OrthoView ov =
                com.geogenesis.client.reveal.RevealDataHolder.orthoView();
        float oScale, oOriginX, oOriginY, oYaw, oPitch, oSpan;
        if (ov != null) {
            oScale = ov.scale();
            oOriginX = ov.originX();
            oOriginY = ov.originY();
            oYaw = ov.yaw();
            oPitch = ov.pitch();
            oSpan = ov.reliefSpan();
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] 继承沙盘正交相机：scale={} origin=({},{}) yaw={} pitch={} span={}",
                    String.format(java.util.Locale.ROOT, "%.1f", oScale),
                    String.format(java.util.Locale.ROOT, "%.1f", oOriginX),
                    String.format(java.util.Locale.ROOT, "%.1f", oOriginY),
                    String.format(java.util.Locale.ROOT, "%.3f", oYaw),
                    String.format(java.util.Locale.ROOT, "%.3f", oPitch),
                    String.format(java.util.Locale.ROOT, "%.1f", oSpan));
        } else {
            // 没有交接（例如直接进世界）⇒ 退回一个"像沙盘"的默认值
            oScale = this.height * 0.46f;
            oOriginX = this.width / 2f;
            oOriginY = this.height / 2f;
            oYaw = (float) Math.toRadians(37);
            oPitch = (float) Math.toRadians(54);
            oSpan = 88f;
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] 没有沙盘相机交接，用默认正交参数");
        }

        float playerFov = mc.options.fov().get();
        world.begin(sx, sy, sz, oOriginX, oOriginY, oScale, oYaw, oPitch, playerFov);
        world.setReliefSpan(oSpan);
        // ★ v45：告诉渲染器"世界坐标怎么映到模型空间"（正交侧归一化用）
        //   半宽 = 数据窗口的一半；中心 = 数据窗口中心；基准面 = seaLevel - 4
        world.setWorldWindow(d.blocksWide / 2.0,
                d.originX + d.blocksWide / 2.0,
                d.originZ + d.blocksHigh / 2.0,
                59.0);
        begun = true;
        buildSchedule(sx, sz);
    }

    /** 世界坐标处的地表 y（从交接数据里取；越界/无数据 → 海平面）。 */
    private static int surfaceAt(TerrainFieldData d, int wx, int wz) {
        double bpc = d.blocksPerCell();
        int gx = (int) Math.round((wx - d.originX) / bpc);
        int gz = (int) Math.round((wz - d.originZ) / bpc);
        if (gx < 0) gx = 0;
        if (gz < 0) gz = 0;
        if (gx >= d.gridW) gx = d.gridW - 1;
        if (gz >= d.gridH) gz = d.gridH - 1;
        int i = d.index(gx, gz);
        if (i < 0 || Double.isNaN(d.height[i])) return 63;
        return (int) Math.round(d.height[i]);
    }

    /** 动画期间替玩家扛伤害（参考项目 applyInvulnerability 同思路）。 */
    private boolean invulTouched;
    private boolean invulBefore;

    private void applyInvulnerability(Minecraft mc) {
        if (mc == null || mc.player == null || invulTouched) return;
        invulTouched = true;
        invulBefore = mc.player.isInvulnerable();
        mc.player.setInvulnerable(true);
    }

    private void restoreInvulnerability(Minecraft mc) {
        if (!invulTouched) return;
        invulTouched = false;
        if (mc != null && mc.player != null) {
            mc.player.setInvulnerable(invulBefore);
        }
    }

    /** 建立让位调度（步骤 A：一块柱子一档，按到落点距离排波次）。 */
    private void buildSchedule(int siteX, int siteZ) {
        TerrainFieldData d = data();
        if (d == null) return;
        int pillars = Math.max(1, d.gridW / 2);
        RevealSchedule.Block[] blocks = new RevealSchedule.Block[pillars * pillars];
        double maxD = 1;
        int rd = Math.max(8, Math.min(12,
                Minecraft.getInstance().options.renderDistance().get()));
        for (int i = 0; i < pillars; i++) {
            for (int j = 0; j < pillars; j++) {
                RevealSchedule.Block b = new RevealSchedule.Block();
                double wx = d.worldX(i * 2), wz = d.worldZ(j * 2);
                b.dist = Math.hypot(wx - siteX, wz - siteZ);
                b.inWaitZone = b.dist <= rd * 16.0;
                maxD = Math.max(maxD, b.dist);
                blocks[i * pillars + j] = b;
            }
        }
        this.schedule = new RevealSchedule(blocks, maxD);
    }

    private TerrainFieldData data() {
        // 步骤 A：先复用沙盘那份数据（由 clientChunkCache 驱动采样已在沙盘做过）。
        //   这里返回 null 表示"还没有数据"，动画会停在开场等数据。
        return RevealDataHolder.current();
    }

    private void advanceSchedule(long now) {
        if (schedule == null) return;
        float clk = world.clockMs();
        schedule.advance((long) clk, i -> hasChunkData(i));
    }

    /** 步骤 A：区块数据是否到货（用客户端区块缓存粗判）。 */
    private boolean hasChunkData(int blockIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        TerrainFieldData d = data();
        if (d == null) return false;
        // 简化：只要这一格的 height 有值就算到货
        int pillars = Math.max(1, d.gridW / 2);
        int i = blockIndex / pillars, j = blockIndex % pillars;
        return d.hasCell(Math.min(d.gridW - 1, i * 2), Math.min(d.gridH - 1, j * 2));
    }

    private boolean canStartDescent(long now) {
        if (schedule == null || !world.introDone()) return false;
        float clk = world.clockMs();
        // 圈内全放行 → 触发收尾消散 → 消散完才落机（参考项目第 143-145 行）
        if (collapseAt < 0L) {
            if (schedule.canDescend((long) clk)) {
                schedule.beginCollapse((long) clk);
                collapseAt = now;
            }
            return false;
        }
        return schedule.collapseDone((long) clk);
    }

    private void startDescent(long now) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        descending = true;
        descent = new DescentPath(
                world.cameraEyeX(), world.cameraEyeY(), world.cameraEyeZ(),
                world.siteX(), world.siteY() + DescentPath.APPROACH_ABOVE, world.siteZ(),
                mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                0f, 35f, mc.player.getYRot(), mc.player.getXRot());
    }

    private void finish() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        // ★ v38：还没登录（世界没起来）就交回原版加载界面；
        //   参考项目 SiteRevealScreen.finish() 第 650-656 行的同一条判据 ——
        //   `setScreen(null)` 在 level==null 时会被顶成标题界面。
        if (mc.level == null) {
            if (fallback != null) {
                mc.setScreen(fallback);
            } else {
                mc.setScreen(null);
            }
            return;
        }
        mc.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // ★ v42：render 每帧都跑（与 world 是否就绪无关），
        //   而 tick 只在"已有 world"时被原版调用 ⇒ 必须在这里也推进一次，
        //   否则世界加载完之前动画完全不动（实测黑屏 84 秒的直接原因）。
        if (!failed) {
            try {
                advance();
            } catch (Throwable t) {
                bailOut(t.toString());
            }
        }
        // 首次渲染打一次（确认这个屏真的被激活了）
        if (!renderedOnce) {
            renderedOnce = true;
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] RevealScreen 首次渲染（{}×{}）", this.width, this.height);
        }
        if (failed) { super.render(g, mouseX, mouseY, partialTick); return; }
        try {
            // ★ 落机阶段：由 DescentPath 接管相机（参考项目 setDrawCamera 同思路）
            if (descending && descent != null) {
                descent.eye(eyeBuf);
                world.applyDescentCamera(eyeBuf[0], eyeBuf[1], eyeBuf[2],
                        descent.yaw(), descent.pitch());
            }
            TerrainFieldData d = data();
            RevealSchedule sch = schedule;
            // ★ v45：span 用继承来的沙盘值（不再写死 88f）——
            //   参考项目注释：「两侧用同一个数才对得上」。
            world.render(g, d, GeoPalette.PreviewLayer.ELEVATION,
                    System.currentTimeMillis(), sch, world.reliefSpan(),
                    0, 0, this.width, this.height);
            drawStatus(g);
        } catch (Throwable t) {
            bailOut(t.toString());
        }
    }

    private void drawStatus(GuiGraphics g) {
        if (schedule == null) return;
        float clk = world.clockMs();
        int total = schedule.totalInZone();
        if (total <= 0) return;
        int ready = schedule.readyInZone((long) clk);
        int pct = Math.min(100, ready * 100 / total);
        String line = descending ? "正在进入世界…" : ("地形加载 " + pct + "%");
        g.drawString(this.font, line, this.width / 2 - this.font.width(line) / 2,
                this.height - 40, 0xFFD4D9E4, true);
    }
}
