package com.geogenesis;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.GeoGenesisWorldData;
import com.geogenesis.worldgen.generator.GeoGenesisGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端 FORGE 总线事件：把真实世界种子喂给地形引擎。
 *
 * <p>原 {@code GeoGenesisGenerator.worldSeed} 写死 12345L，{@code setWorldSeed} 从无调用，
 * 导致游戏世界种子变更后地形不变（始终 12345 播种）。此处监听主世界 {@link LevelEvent.Load}，
 * 在主世界 ServerLevel 加载时写入真实种子，使地形随世界种子变化。
 *
 * <p>订阅不加 {@code value} 限定（即 CLIENT/DEDICATED_SERVER 两边都注册）：集成服务器在客户端进程、
 * 专用服务器在服务器进程都会触发 {@code LevelEvent.Load(ServerLevel)}，两种部署均覆盖。
 */
@Mod.EventBusSubscriber(modid = GeoGenesisMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GeoGenesisServerEvents {

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel s && s.dimension() == Level.OVERWORLD) {
            GeoGenesisGenerator.setWorldSeed(s.getSeed());
            // 按存档级地形参数：读取（或首次冻结）当前世界参数，注入生成器/群系源。
            GeoGenesisWorldData data = GeoGenesisWorldData.get(s);
            if (data.getParams() == null) {
                // 首次加载且尚无存档参数：把当前全局 toml 模板冻结进存档，实现 per-save 隔离。
                data.setParams(GeoGenesisConfig.INSTANCE.buildParams());
            }
            GeoGenesisGenerator.setCurrentWorldParams(data.getParams());
        }
    }

    /**
     * ★ 2026-09-18：世界卸载时打一份<b>全流程诊断总计</b>。
     *
     * <p>为什么需要：全流程 profiler 的滚动汇总每 N 块打一次，<b>最后不足 N 块的那段</b>
     * 不会被包含 ⇒ 需要一次收尾总计。本方法只读统计、不改任何生成逻辑。</p>
     *
     * <p>⚠ 与 {@code GeoGenesisConfig} 的读取一样，探针/预览进程没有 Forge 生命周期
     * ⇒ 本方法只在真实服务端事件里触发，天然安全。</p>
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel s && s.dimension() == Level.OVERWORLD) {
            com.geogenesis.diagnostics.WorldGenProfiler.reportNow("世界卸载总计");
        }
    }

    private GeoGenesisServerEvents() {}
}
