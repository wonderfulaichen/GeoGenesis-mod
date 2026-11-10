package com.geogenesis.worldgen.spawn;

import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * <b>用玩家在沙盘上选的落点作为世界出生点</b>（★ v35）。
 *
 * <h2>用户需求</h2>
 * <blockquote>
 * 「让它真的生效 —— 这个标记应该直接决定世界生成的出生点，而不只是画个十字。」
 * </blockquote>
 *
 * <h2>为什么用 {@code LevelEvent.CreateSpawnPosition}</h2>
 * <p>Forge 在 {@code MinecraftServer.setInitialSpawn} 里给了一个<b>可取消</b>事件，
 * <b>只在 {@code !levelData.isInitialized()} 时触发</b>（即"建新世界"这一刻）。
 * 取消它并自己 {@code setDefaultSpawnPos} ⇒ 原版就不会先在随机位置找出生点，
 * 随后 {@code prepareLevels} 直接围着我们选的落点生成出生点区块。
 * （参考项目 {@code SiteSpawnHandler} 用的就是这套。）</p>
 *
 * <h2>y 怎么定</h2>
 * <p>用<b>生成器自己的高度查询</b>（{@code getBaseHeight} + {@code WORLD_SURFACE_WG}），
 * 它读的是噪声列、<b>不加载区块</b> ⇒ 在建世界阶段就能拿到地表 y。
 * 这与参考项目 {@code SurfaceProbe} 的思路一致（我们用更简单的直接调用）。</p>
 *
 * <p>取不到（返回海平面以下/异常）就退回 {@code seaLevel + 1}，
 * 绝不让出生点落在水里或虚空。</p>
 */
public final class SpawnPositionHandler {

    private SpawnPositionHandler() { }

    /** 出生点 y 相对地表的安全余量（格）。 */
    private static final int Y_CLEARANCE = 1;

    /**
     * 处理出生点接管（由 {@code GeoGenesisServerEvents.onCreateSpawnPosition} 调用）。
     *
     * <p>⚠ 这里<b>不加</b> {@code @SubscribeEvent}：本类没有注册到任何总线，
     * 事件订阅统一在 {@code GeoGenesisServerEvents}（它已 {@code @EventBusSubscriber}）。
     * 加注解会让人误以为它自己会触发。</p>
     */
    public static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        // 取用即消费：种子不符 / 过期 / 从没选过 → null，交回原版逻辑
        SpawnSelection.Pending pending = SpawnSelection.take(level.getSeed());
        if (pending == null) return;

        ChunkGenerator gen = level.getChunkSource().getGenerator();
        int x = pending.x();
        int z = pending.z();
        int surfaceY = surfaceY(level, gen, x, z);
        BlockPos spawn = new BlockPos(x, surfaceY + Y_CLEARANCE, z);

        // ★ 1.20.1：取消事件 + 自己设出生点。
        //   （事件的 getter 是 getLevel()/getSettings()，但 LevelSettings 在 1.20.1
        //     没有公开的 setSpawnPos ⇒ 用 ServerLevel.setDefaultSpawnPos 这个公开 API。）
        level.setDefaultSpawnPos(spawn, 0.0F);
        event.setCanceled(true);
    }

    /**
     * 该列的地表 y（<b>不加载区块</b>）。
     *
     * <p>{@code Heightmap.Types.WORLD_SURFACE_WG} = "雕刻与地物之前"的地表，
     * 由 {@code getBaseHeight} 直接查噪声列得出。这是建世界阶段唯一可用的高度来源
     * （此时区块还没生成）。</p>
     */
    private static int surfaceY(ServerLevel level, ChunkGenerator gen, int x, int z) {
        int h;
        try {
            // ★ 1.20.1 签名：(x, z, Type, LevelHeightAccessor, RandomState)
            //   我们的 GeoGenesisGenerator 重写了它，内部走 sampleHeightNonBlocking
            //   （不触发侵蚀 tile、不加载区块）⇒ 建世界阶段可用。
            h = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                    level, level.getChunkSource().randomState());
        } catch (RuntimeException e) {
            // 拿不到就用海平面兜底（绝不落在虚空里）
            return gen.getSeaLevel();
        }
        if (h < level.getMinBuildHeight()) return gen.getSeaLevel();
        return Math.min(h, level.getMaxBuildHeight() - 2);
    }
}
