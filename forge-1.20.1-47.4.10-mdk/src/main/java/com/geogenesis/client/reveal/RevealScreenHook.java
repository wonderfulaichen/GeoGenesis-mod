package com.geogenesis.client.reveal;

import com.geogenesis.worldgen.spawn.SpawnSelection;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <b>把原版"正在准备创建世界"屏换成落成动画</b>（★ v37）。
 *
 * <h2>为什么不用 mixin（参考项目用的是 mixin）</h2>
 * <p>参考项目 {@code MinecraftSiteLoadMixin} 注入 {@code Minecraft#doWorldLoad}。
 * 但<b>本项目此前零 mixin 基础设施</b> —— 引入 mixin 要加 json 配置、要处理
 * 混淆映射、写错直接崩游戏。而事件方案零风险。</p>
 *
 * <p>Forge 的 {@code ScreenEvent.Opening} 能在<b>任何屏幕打开前</b>替换它：
 * 检测到"要打开 {@code LevelLoadingScreen}"且"有选点"时，换成我们的
 * {@link RevealScreen} ⇒ 效果等价，且<b>不影响原版流程</b>
 * （原版的生成/落盘/发包照常，我们只是换了显示的那一层）。</p>
 *
 * <h2>生效条件（三条全满足才接管）</h2>
 * <ol>
 *   <li>正在打开的是 {@code LevelLoadingScreen}；</li>
 *   <li>玩家在配置屏<b>选过出生点</b>（{@code SpawnSelection.has()}）；</li>
 *   <li>有可用的地形数据（{@code RevealDataHolder.current() != null}）。</li>
 * </ol>
 * <p>任一条不满足 ⇒ 完全不干预，原版加载屏照常显示。</p>
 *
 * <h2>★ 异常兜底</h2>
 * <p>{@link #onScreenOpening} 整体包在 try/catch 里：出任何异常都<b>不替换</b>，
 * 原版加载屏照常 —— 这正是用户裁定的 (a)「静默退回原版」。</p>
 */
@Mod.EventBusSubscriber(modid = "geogenesis", bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class RevealScreenHook {

    /** 本会话是否已经播过（一次建世界只播一次）。 */
    private static boolean played;
    /** 正在 forceSetScreen（防事件递归）。 */
    private static boolean forcing;
    /** 当前正在播的动画屏（拦截 ReceivingLevelScreen 时要用它判断）。 */
    private static RevealScreen active;

    private RevealScreenHook() { }

    /**
     * 接管时机（★ v38 补了两个遗漏）。
     *
     * <h2>★ 遗漏 1：不能只等 {@code LevelLoadingScreen}</h2>
     * <p>用户实测「点完成后就直接关闭回到类型选择了」暴露了链路问题；
     * 修掉触发点后还有一个更细的时机问题 —— 原版 {@code onCreate()} 之后
     * <b>先</b>换一张 {@code GenericMessageScreen}（文案"准备创建世界"），
     * 建目录、装数据包，<b>之后</b>才进 {@code LevelLoadingScreen}。</p>
     * <p>参考项目为此专门注入 {@code queueLoadScreen}（{@code CreateWorldScreenMixin}
     * 第 50-63 行），注释写明：「这一段会把选址精读和落成动画切开」。
     * 我们不改 mixin，改为<b>同时认这两种屏</b> ⇒ 从点击那一刻就接管，不会闪一下灰屏。</p>
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        try {
            Screen next = event.getNewScreen();
            boolean preparing = isPreparingScreen(next);
            // ★ v40 诊断：把每次开屏与三个条件都打出来
            //   （用户实测"没看到动画，直接黑屏" ⇒ 必须知道是哪一条拦住了）
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] 开屏 {}：preparing={} played={} hasSpawn={} hasData={}",
                    next == null ? "null" : next.getClass().getSimpleName(),
                    preparing, played,
                    SpawnSelection.has(),
                    RevealDataHolder.current() != null);
            if (!preparing) return;
            if (played) return;
            if (forcing) return;          // ★ 防"forceSetScreen 再触发本事件"的递归
            if (!SpawnSelection.has()) return;
            if (RevealDataHolder.current() == null) return;

            played = true;
            RevealScreen rs = new RevealScreen(null);
            event.setNewScreen(rs);
            com.mojang.logging.LogUtils.getLogger().info(
                    "[RevealDiag] ★接管：换成 RevealScreen（并主动 forceSetScreen）");
            // ★ 参考项目 CreateWorldScreenMixin 第 60 行的做法：主动换屏。
            //   只 setNewScreen 实测<不能>让屏真正激活（RevealScreen 的
            //   init/tick/render 一次都没跑）。
            forcing = true;
            try {
                net.minecraft.client.Minecraft.getInstance().forceSetScreen(rs);
            } finally {
                forcing = false;
            }
        } catch (Throwable t) {
            // 静默退回原版（用户裁定 (a)）
            com.mojang.logging.LogUtils.getLogger()
                    .warn("[GeoGenesis] 落成动画接管失败，走原版加载屏：{}", t.toString());
        }
    }

    /**
     * 是不是"进世界的准备屏"。
     *
     * <p>两种都算：{@code GenericMessageScreen}（原版"准备创建世界"）与
     * {@code LevelLoadingScreen}（原版"加载地形中"）。只认前者在"从已建好的存档进入"时
     * 会漏（那条路直接走 LevelLoadingScreen）。</p>
     */
    private static boolean isPreparingScreen(Screen s) {
        if (s == null) return false;
        if (s instanceof LevelLoadingScreen) return true;
        // GenericMessageScreen 也被"保存世界""断开连接"等流程用 ⇒ 用父类名 + 当前有选点双重限定
        return s.getClass().getName().equals("net.minecraft.client.gui.screens.GenericMessageScreen");
    }

    /**
     * <b>★ 遗漏 2：拦住登录后的「收区块」过渡屏</b>（★ v38）。
     *
     * <p>参考项目 {@code SiteRevealGuard} 原文：「登录完原版要换上『收区块』的过渡屏
     * （{@code ReceivingLevelScreen}）。落成动画还没落完就先把我们这个界面留着：
     * 拦掉这一次开屏。」</p>
     *
     * <p>它走事件而非 mixin 的理由（原文）：「原版有两条路径会设这个界面
     * （{@code Minecraft#setLevel} 和 {@code ClientPacketListener#startWaitingForNewLevel}），
     * 而 {@code Minecraft#setScreen} 在赋值<b>之前</b>发这个可取消事件，一处就盖得全；
     * 取消也不会碰到 {@code removed()}，界面不被拆掉。」</p>
     *
     * <p>不拦的后果：动画刚开始播就被原版"正在接收区块"屏盖掉，玩家看不到。</p>
     */
    @SubscribeEvent
    public static void onReceivingLevel(ScreenEvent.Opening event) {
        try {
            Screen next = event.getNewScreen();
            if (next == null) return;
            if (!next.getClass().getName()
                    .equals("net.minecraft.client.gui.screens.ReceivingLevelScreen")) return;
            if (active != null && !active.holdsHandoff()) {
                // 动画已经落完 ⇒ 不再拦，交回原版
                return;
            }
            if (active != null) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
            // 拦不住只是多闪一下，不影响正确性
        }
    }

    /** 动画结束时清掉 active。 */
    public static void onRevealClosed() {
        active = null;
    }

    /**
     * 注册当前正在播的动画（由 {@code RevealScreen.init()} 调）。
     *
     * <p>拦 {@code ReceivingLevelScreen} 时要用它判断"动画还没落完 ⇒ 别让原版盖掉"。</p>
     */
    public static void setActive(RevealScreen screen) {
        active = screen;
    }

    /** 新世界开始时重置（由服务器事件调用）。 */
    public static void resetSession() {
        played = false;
        active = null;
    }
}
