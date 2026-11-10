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
            long now = System.currentTimeMillis();
            if (now - openedAt >= HARD_CAP_MS) {
                finish();
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            // 世界还没起来：等
            if (mc.level == null) {
                if (now - openedAt > NO_WORLD_MS) {
                    bailOut("一直没有世界可渲染");
                }
                return;
            }
            if (loginAt == 0L) loginAt = now;

            if (!begun) {
                beginReveal();
                if (!begun) return;    // 数据还没备好
            }

            world.tick(now);
            advanceSchedule(now);

            if (!descending && canStartDescent(now)) {
                startDescent(now);
            }
            if (descending) {
                descent.tick(now);
                if (descent.done()) finish();
            }
        } catch (Throwable t) {
            bailOut(t.toString());
        }
    }

    private void beginReveal() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        SpawnSelection.Pending p = SpawnSelection.peek();
        int sx = p != null ? p.x() : mc.player.blockPosition().getX();
        int sz = p != null ? p.z() : mc.player.blockPosition().getZ();
        int sy = (int) mc.player.getY();

        // ★ v38：动画期间玩家无敌（参考项目第 659-678 行）。
        //   不这么做的话：一进世界就可能被怪打/摔伤 ⇒ 红屏 + 镜头抖 ⇒ 动画被破坏。
        applyInvulnerability(mc);

        // 从沙盘继承正交参数（保证开场第一帧与沙盘一致）。
        //   步骤 A 简化：用固定值，效果是"从俯视正交开始"，不追求逐像素一致。
        world.begin(sx, sy, sz,
                this.width / 2.0, this.height / 2.0, this.height * 0.46,
                (float) Math.toRadians(37), (float) Math.toRadians(54),
                mc.options.fov().get());
        begun = true;
        buildSchedule(sx, sz);
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
            world.render(g, d, GeoPalette.PreviewLayer.ELEVATION,
                    System.currentTimeMillis(), sch, 88f,
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
