package com.geogenesis.worldgen;

import com.geogenesis.worldgen.terrain.RecordSerializer;
import com.geogenesis.worldgen.terrain.TerrainParams;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * 按世界存档的地形参数（per-save）。
 *
 * <p>每个主世界独立保存一份 {@link TerrainParams} 副本；存档被复制/移动到其他机器时也一并带走，
 * 实现"地形参数存档级"。{@code params} 为 null 表示尚未冻结——此时运行时回退到全局 toml 默认模板。
 *
 * <p>由 {@link com.geogenesis.GeoGenesisServerEvents} 在主世界加载时读取（或首次冻结）并注入
 * {@link com.geogenesis.worldgen.generator.GeoGenesisGenerator#setCurrentWorldParams}。
 */
public class GeoGenesisWorldData extends SavedData {

    private static final String ID = "geogenesis_world_params";

    private TerrainParams params;

    public GeoGenesisWorldData() {
    }

    public TerrainParams getParams() {
        return params;
    }

    public void setParams(TerrainParams p) {
        this.params = p;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (params != null) {
            CompoundTag p = new CompoundTag();
            RecordSerializer.writeRecord(params, p);
            tag.put("params", p);
        }
        return tag;
    }

    public static GeoGenesisWorldData load(CompoundTag tag) {
        GeoGenesisWorldData d = new GeoGenesisWorldData();
        if (tag.contains("params")) {
            d.params = RecordSerializer.readRecord(TerrainParams.class, tag.getCompound("params"));
        }
        return d;
    }

    public static GeoGenesisWorldData get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(GeoGenesisWorldData::load, GeoGenesisWorldData::new, ID);
    }
}
