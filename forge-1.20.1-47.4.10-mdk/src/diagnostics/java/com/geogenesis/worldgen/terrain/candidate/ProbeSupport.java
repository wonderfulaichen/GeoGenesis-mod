package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 沙箱探针共用的配置加载：读冻结 TOML → TerrainParams。 */
final class ProbeSupport {

    private ProbeSupport() { }

    static TerrainParams loadParams(Path toml, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        Path copy = outDir.resolve("config-input.toml");
        Files.copy(toml, copy, StandardCopyOption.REPLACE_EXISTING);
        CommentedFileConfig cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
        cfg.load();
        if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) {
            throw new IllegalStateException("frozen TOML unexpectedly requires correction");
        }
        GeoGenesisConfig.SPEC.setConfig(cfg);
        return GeoGenesisConfig.INSTANCE.buildParams();
    }
}