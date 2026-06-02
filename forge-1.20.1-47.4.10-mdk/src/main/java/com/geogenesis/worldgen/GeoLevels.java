package com.geogenesis.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record GeoLevels(int minY, int maxY, int baseHeight, int seaLevel) {

    public static final Codec<GeoLevels> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("min_y").forGetter(GeoLevels::minY),
            Codec.INT.fieldOf("max_y").forGetter(GeoLevels::maxY),
            Codec.INT.fieldOf("base_height").forGetter(GeoLevels::baseHeight),
            Codec.INT.fieldOf("sea_level").forGetter(GeoLevels::seaLevel)
    ).apply(instance, instance.stable(GeoLevels::new)));

    public static final GeoLevels DEFAULT = new GeoLevels(-64, 320, 128, 62);
}
