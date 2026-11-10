package com.geogenesis.client.reveal;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <b>落成动画期间接管真实世界的相机与视野</b>（★ v38，补第三处遗漏）。
 *
 * <h2>为什么这步不能省</h2>
 * <p>参考项目用 {@code CameraMixin} + {@code GameRendererMixin} 每帧改相机
 * （{@code SiteRevealScreen.cameraTargetFor} 第 698-709 行）。</p>
 *
 * <p>不接管的后果：玩家看到的是<b>原版第一人称视角</b>（站在出生点上），
 * 而我们的沙盘动画只是<b>盖在上面的一张图</b> —— 镜头不动、看不到"从空中飞下来"，
 * 只有最后落机时把图撤掉才会"啪"地跳一下。<b>整个动画就白做了。</b></p>
 *
 * <h2>为什么走事件而不是 mixin</h2>
 * <p>Forge 提供 {@code ViewportEvent.ComputeCameraAngles}（yaw/pitch/roll）与
 * {@code ViewportEvent.ComputeFov}（视野）—— 每帧都发、可写、正好覆盖
 * 参考项目那两个 mixin 做的事。引用项目用 mixin 是因为 NeoForge 当时没有这些事件
 * （或作者没找到），我们<b>不需要再引入两个 mixin</b>。</p>
 *
 * <h2>接管范围（与参考项目一致）</h2>
 * <ul>
 *   <li><b>登录前不接管</b>（真世界还不存在）；</li>
 *   <li><b>落到位后不接管</b>（{@code descend >= 1} 就把相机完全交回原版）——
 *       否则玩家永远动不了。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "geogenesis", bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class RevealCameraHook {

    private RevealCameraHook() { }

    /** 当前在播的动画（null = 不接管）。 */
    private static RevealScreen active;

    public static void setActive(RevealScreen screen) {
        active = screen;
    }

    /**
     * 相机角度接管。
     *
     * <p>★ 只在 {@code descProgress < 1} 时接管：落到位就让原版相机完全接管，
     * 玩家才能正常转头（参考项目第 708 行同判据）。</p>
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        RevealScreen s = active;
        if (s == null) return;
        if (Minecraft.getInstance().level == null) return;
        DescentPath d = s.descentPath();
        if (d == null || d.done()) return;
        try {
            event.setYaw(180.0f + d.yaw());
            event.setPitch(-d.pitch());
        } catch (Throwable ignored) {
            // 接管失败就退回原版相机（不影响正确性）
        }
    }

    /** 视野接管（空中段用长焦，落机时展开回玩家视野）。 */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        RevealScreen s = active;
        if (s == null) return;
        if (Minecraft.getInstance().level == null) return;
        DescentPath d = s.descentPath();
        if (d == null || d.done()) return;
        try {
            // 从 40（长焦）过渡到玩家自己的视野
            double target = event.getFOV();
            float fov = (float) (40.0 + (target - 40.0) * d.lookBlend());
            event.setFOV(fov);
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
