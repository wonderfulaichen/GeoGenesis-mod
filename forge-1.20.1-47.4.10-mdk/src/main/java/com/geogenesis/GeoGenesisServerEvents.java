package com.geogenesis;

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
        }
    }

    private GeoGenesisServerEvents() {}
}
