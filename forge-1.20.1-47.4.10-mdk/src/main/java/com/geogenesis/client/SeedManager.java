package com.geogenesis.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子收藏管理器（JSON 持久化）。
 * <p>
 * 存储位置：config/geogenesis/seeds.json
 * 格式：{"favorites": [{"seed":12345,"name":"种子1"}, ...], "lastSeed":12345}
 */
public class SeedManager {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    private static final Path SEEDS_FILE = Path.of("config/geogenesis/seeds.json");

    private static SeedManager INSTANCE;

    private final List<SeedEntry> favorites = new ArrayList<>();
    private long lastSeed = -1;
    private boolean dirty = false;

    public record SeedEntry(long seed, String name) {}

    private SeedManager() {
        load();
    }

    public static SeedManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SeedManager();
        }
        return INSTANCE;
    }

    // ====== 读写 ======

    /** 加载 JSON */
    private void load() {
        if (!Files.exists(SEEDS_FILE)) return;
        try (FileReader reader = new FileReader(SEEDS_FILE.toFile())) {
            var type = new TypeToken<SeedData>(){}.getType();
            SeedData data = new Gson().fromJson(reader, type);
            if (data != null) {
                favorites.clear();
                if (data.favorites != null) favorites.addAll(data.favorites);
                lastSeed = data.lastSeed;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load seeds: {}", e.getMessage());
        }
    }

    /** 保存 JSON */
    public void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(SEEDS_FILE.getParent());
            try (FileWriter writer = new FileWriter(SEEDS_FILE.toFile())) {
                new GsonBuilder().setPrettyPrinting().create().toJson(new SeedData(favorites, lastSeed), writer);
            }
            dirty = false;
        } catch (IOException e) {
            LOGGER.warn("Failed to save seeds: {}", e.getMessage());
        }
    }

    // ====== API ======

    public List<SeedEntry> getFavorites() { return List.copyOf(favorites); }

    /** 添加收藏（重复 seed 会更新名称） */
    public void addFavorite(long seed, String name) {
        favorites.removeIf(e -> e.seed == seed);
        favorites.add(new SeedEntry(seed, name));
        dirty = true;
        save();
    }

    /** 删除收藏 */
    public void removeFavorite(long seed) {
        favorites.removeIf(e -> e.seed == seed);
        dirty = true;
        save();
    }

    /** 是否为收藏 */
    public boolean isFavorite(long seed) {
        return favorites.stream().anyMatch(e -> e.seed == seed);
    }

    /** 上次使用的种子 */
    public long getLastSeed() { return lastSeed; }
    public void setLastSeed(long seed) {
        if (this.lastSeed != seed) {
            this.lastSeed = seed;
            dirty = true;
            save();
        }
    }

    // ====== JSON 模型 ======

    private record SeedData(List<SeedEntry> favorites, long lastSeed) {}
}
