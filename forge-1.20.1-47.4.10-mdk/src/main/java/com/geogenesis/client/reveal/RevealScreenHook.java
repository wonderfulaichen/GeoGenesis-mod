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

    private RevealScreenHook() { }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        try {
            Screen next = event.getNewScreen();
            if (!(next instanceof LevelLoadingScreen)) return;
            if (played) return;
            if (!SpawnSelection.has()) return;
            if (RevealDataHolder.current() == null) return;

            played = true;
            // fallback 传 null：动画结束直接关屏（原版此时也已经不打算显示加载屏了）
            event.setNewScreen(new RevealScreen(null));
        } catch (Throwable t) {
            // 静默退回原版（用户裁定 (a)）
            com.mojang.logging.LogUtils.getLogger()
                    .warn("[GeoGenesis] 落成动画接管失败，走原版加载屏：{}", t.toString());
        }
    }

    /** 新世界开始时重置（由服务器事件调用）。 */
    public static void resetSession() {
        played = false;
    }
}
