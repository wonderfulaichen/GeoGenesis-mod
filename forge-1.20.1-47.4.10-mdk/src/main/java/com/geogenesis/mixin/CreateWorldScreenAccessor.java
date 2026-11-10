package com.geogenesis.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * <b>让配置屏能触发「真正的世界创建」</b>（★ v38，本项目唯一的 mixin）。
 *
 * <h2>为什么必须有它（用户实测的核心问题）</h2>
 * <blockquote>
 * 「动画的前提不对啊，我们点击完成后就直接是关闭回到类型选择了。」
 * </blockquote>
 *
 * <p>根因：我们的「完成」按钮只是 {@code setScreen(parent)} —— 退回
 * {@code CreateWorldScreen}，<b>根本没有开始创建世界</b>。玩家还得自己再点一次
 * 原版「创建世界」按钮，那才会走到 {@code LevelLoadingScreen} ⇒ 落成动画永远等不到触发点。</p>
 *
 * <p>{@code CreateWorldScreen.onCreate()} 是<b>私有方法</b>，外部无法调用。
 * 参考项目 {@code dev.yard.site.mixin.CreateWorldScreenAccessor} 的做法
 * 正是用 {@code @Invoker} 把它暴露出来：点「确认并创建」时就调它，
 * 于是<b>同一个点击</b>直接开始建世界 ⇒ 动画在正确的时机接管。</p>
 *
 * <h2>★ 必须写成 "accessor mixin 接口"</h2>
 * <p>参考项目注释原话：「必须写成「accessor mixin 接口」（自己带 {@code @Mixin}，
 * 并写进 mixin 配置的 client 列表），而不是普通接口：普通接口不会生成
 * {@code @Invoker} 实现，运行时会 {@code AbstractMethodError}。」</p>
 *
 * <p>本类<b>不注入任何逻辑</b>（没有 {@code @Inject}），只生成一个方法转发 ——
 * 这是风险最低的 mixin 类型。</p>
 */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenAccessor {

    /** 调用原版私有的 {@code CreateWorldScreen#onCreate()}（真正开始建世界）。 */
    @Invoker("onCreate")
    void geogenesis$createWorld();
}
