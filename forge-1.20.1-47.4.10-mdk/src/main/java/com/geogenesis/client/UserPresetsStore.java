package com.geogenesis.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户自定义预设存储（单例）。
 *
 * <p>把当前全局配置（通过 {@link GeoGenesisConfig#captureAllValues()} 拍的快照）以命名预设保存，
 * 持久化到 {@code config/geogenesis/user_presets.json}，供配置屏「我的预设」区加载/删除。
 * 与内置 {@link PresetLibrary} 预设并存、互不影响。</p>
 */
public final class UserPresetsStore {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final Path FILE = Path.of("config/geogenesis/user_presets.json");
    private static final Type LIST_TYPE = new TypeToken<List<UserPreset>>() {}.getType();

    private static UserPresetsStore INSTANCE;

    private final List<UserPreset> presets = new ArrayList<>();

    /** 用户预设：id + 名称 + 字段名→字符串值（与 {@link GeoGenesisConfig#applyNamedValues} 对应）。 */
    public static class UserPreset {
        public String id;
        public String name;
        public Map<String, String> values;

        public UserPreset() {
        }

        public UserPreset(String id, String name, Map<String, String> values) {
            this.id = id;
            this.name = name;
            this.values = values;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Map<String, String> values() {
            return values;
        }
    }

    private UserPresetsStore() {
        load();
    }

    public static UserPresetsStore getInstance() {
        if (INSTANCE == null) INSTANCE = new UserPresetsStore();
        return INSTANCE;
    }

    /** 返回当前所有用户预设（副本，避免外部改动内部列表）。 */
    public List<UserPreset> list() {
        return new ArrayList<>(presets);
    }

    /** 保存一个命名预设：同名覆盖，values 为字段名→当前值的快照。 */
    public void savePreset(String name, Map<String, Object> values) {
        Map<String, String> str = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            str.put(e.getKey(), String.valueOf(e.getValue()));
        }
        presets.removeIf(p -> p.name != null && p.name.equals(name));
        String id = "user_" + System.currentTimeMillis();
        presets.add(new UserPreset(id, name, str));
        save();
        LOGGER.info("Saved user preset '{}' ({} params)", name, str.size());
    }

    /** 按 id 删除一个预设。 */
    public void delete(String id) {
        if (presets.removeIf(p -> p.id != null && p.id.equals(id))) {
            save();
            LOGGER.info("Deleted user preset {}", id);
        }
    }

    private void load() {
        if (!Files.exists(FILE)) return;
        try (FileReader r = new FileReader(FILE.toFile())) {
            List<UserPreset> data = new Gson().fromJson(r, LIST_TYPE);
            if (data != null) {
                presets.clear();
                presets.addAll(data);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load user presets: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (FileWriter w = new FileWriter(FILE.toFile())) {
                new GsonBuilder().setPrettyPrinting().create().toJson(presets, LIST_TYPE, w);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to save user presets: {}", e.getMessage());
        }
    }
}
