package com.geogenesis.worldgen;

import com.geogenesis.worldgen.geology.GeologySystem;
import com.geogenesis.worldgen.hydrology.HydrologySystem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Random;

/**
 * 材料映射 - 气候/地质/坡度/噪声驱动的方块选择
 *
 * 设计原则：
 * - 方块混合 - 同一区域不是纯一种方块
 * - 水流冲刷 - 河谷、高流量河流有裸露岩石
 * - 坡度敏感 - 坡度越大石头越裸露
 * - 积雪自然 - 只在高海拔、迎风坡、低坡度区域积雪
 * - 恶地风格 - 干旱高原有陶瓦+石头混合
 * - 噪声抖动 - 用Perlin噪声让方块变化更自然
 */
public class MaterialMapper {

    private static final int OCEAN_FLOOR_DEPTH = 4;
    private static final int SOIL_DEPTH = 4;

    private static final float SLOPE_FLAT = 0.04f;
    private static final float SLOPE_GENTLE = 0.12f;
    private static final float SLOPE_MODERATE = 0.25f;
    private static final float SLOPE_STEEP = 0.45f;

    private NoiseEngine noiseEngine;
    private HydrologySystem hydrologySystem;
    private long worldSeed;

    public MaterialMapper() {
        this.noiseEngine = null;
        this.hydrologySystem = null;
        this.worldSeed = 0;
    }

    public MaterialMapper(NoiseEngine noiseEngine, HydrologySystem hydrologySystem, long seed) {
        this.noiseEngine = noiseEngine;
        this.hydrologySystem = hydrologySystem;
        this.worldSeed = seed;
    }

    public void bind(NoiseEngine noiseEngine, HydrologySystem hydrologySystem, long seed) {
        this.noiseEngine = noiseEngine;
        this.hydrologySystem = hydrologySystem;
        this.worldSeed = seed;
    }

    private float getNoiseVariation(int wx, int wz) {
        if (noiseEngine != null) {
            return noiseEngine.sampleTerrainDetail(wx * 2, wz * 2);
        }
        long hash = (long)wx * 31 + (long)wz * 17 + worldSeed;
        Random rnd = new Random(hash);
        return rnd.nextFloat() * 0.4f - 0.2f;
    }

    private boolean isRiverErosionZone(int wx, int wz, float riverDepth) {
        if (hydrologySystem == null) return false;
        float noise = getNoiseVariation(wx, wz);
        return riverDepth > 0.15f + noise * 0.05f;
    }

    private boolean shouldSnow(float elevation, float temperature, float slope) {
        if (slope > SLOPE_GENTLE) return false;
        if (temperature > 0.25f) return false;
        if (elevation < 0.55f) return false;
        return true;
    }

    public BlockState getSurfaceBlock(float temperature, float moisture, float elevation,
                                       float continentality, int height, int seaLevel,
                                       float slope, int wx, int wz, float riverDepth) {
        float noiseVar = getNoiseVariation(wx, wz);

        if (isRiverErosionZone(wx, wz, riverDepth) && slope > SLOPE_GENTLE) {
            if (slope > SLOPE_MODERATE + noiseVar * 0.1f) {
                return Blocks.STONE.defaultBlockState();
            }
            return Blocks.GRAVEL.defaultBlockState();
        }

        if (slope >= SLOPE_STEEP + noiseVar * 0.05f) {
            return Blocks.STONE.defaultBlockState();
        }
        if (slope >= SLOPE_MODERATE + noiseVar * 0.05f) {
            if (noiseVar > 0.1f) {
                return Blocks.STONE.defaultBlockState();
            }
            if (noiseVar > -0.1f) {
                return Blocks.GRAVEL.defaultBlockState();
            }
            return Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (slope >= SLOPE_GENTLE + noiseVar * 0.05f) {
            return noiseVar > 0.0f ? Blocks.COARSE_DIRT.defaultBlockState()
                                   : Blocks.DIRT.defaultBlockState();
        }
        if (slope >= SLOPE_FLAT + noiseVar * 0.03f) {
            return noiseVar > 0.05f ? Blocks.DIRT.defaultBlockState()
                                   : Blocks.GRASS_BLOCK.defaultBlockState();
        }

        if (height < seaLevel) {
            if (continentality < 0.4f) {
                return noiseVar > 0.0f ? Blocks.SAND.defaultBlockState()
                                      : Blocks.GRAVEL.defaultBlockState();
            }
            return Blocks.GRAVEL.defaultBlockState();
        }

        if (shouldSnow(elevation, temperature, slope)) {
            if (elevation > 0.85f && temperature < 0.15f) {
                return Blocks.SNOW_BLOCK.defaultBlockState();
            }
            if (noiseVar > 0.05f) {
                return Blocks.STONE.defaultBlockState();
            }
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }

        if (temperature > 0.5f && moisture < 0.2f && elevation > 0.45f) {
            if (elevation > 0.6f) {
                if (noiseVar > 0.15f) {
                    return Blocks.TERRACOTTA.defaultBlockState();
                }
                if (noiseVar > -0.15f) {
                    return Blocks.COARSE_DIRT.defaultBlockState();
                }
                return Blocks.STONE.defaultBlockState();
            }
            if (noiseVar > 0.0f) {
                return Blocks.COARSE_DIRT.defaultBlockState();
            }
            return Blocks.GRAVEL.defaultBlockState();
        }

        if (temperature > 0.6f && moisture < 0.25f) {
            if (temperature > 0.75f && noiseVar > 0.0f) {
                return Blocks.RED_SAND.defaultBlockState();
            }
            if (slope > SLOPE_GENTLE * 0.8f && noiseVar > -0.1f) {
                return Blocks.SANDSTONE.defaultBlockState();
            }
            return Blocks.SAND.defaultBlockState();
        }

        if (continentality < 0.35f && elevation < 0.2f) {
            if (temperature > 0.55f) {
                return noiseVar > 0.0f ? Blocks.RED_SAND.defaultBlockState()
                                      : Blocks.SAND.defaultBlockState();
            }
            return noiseVar > 0.0f ? Blocks.SAND.defaultBlockState()
                                  : Blocks.GRAVEL.defaultBlockState();
        }

        if (elevation < 0.2f && moisture > 0.65f) {
            if (noiseVar > 0.1f && slope < SLOPE_GENTLE) {
                return Blocks.DIRT.defaultBlockState();
            }
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }

        if (temperature < 0.2f && moisture > 0.4f) {
            if (elevation > 0.5f && noiseVar > 0.15f) {
                return Blocks.STONE.defaultBlockState();
            }
            if (noiseVar > -0.05f) {
                return Blocks.PODZOL.defaultBlockState();
            }
            return Blocks.GRAVEL.defaultBlockState();
        }

        if (temperature < 0.25f && moisture < 0.35f && elevation > 0.5f) {
            if (noiseVar > 0.1f) {
                return Blocks.STONE.defaultBlockState();
            }
            return Blocks.GRAVEL.defaultBlockState();
        }

        if (noiseVar > 0.1f) {
            return Blocks.DIRT.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    public BlockState getSoilBlock(int y, int height, int seaLevel,
                                    float temperature, float moisture, float slope,
                                    int wx, int wz) {
        float noiseVar = getNoiseVariation(wx, wz);

        if (y < seaLevel) {
            return noiseVar > 0.0f ? Blocks.SAND.defaultBlockState()
                                   : Blocks.GRAVEL.defaultBlockState();
        }

        if (slope >= SLOPE_STEEP + noiseVar * 0.05f) {
            return Blocks.STONE.defaultBlockState();
        }
        if (slope >= SLOPE_MODERATE + noiseVar * 0.05f) {
            return noiseVar > 0.0f ? Blocks.STONE.defaultBlockState()
                                   : Blocks.GRAVEL.defaultBlockState();
        }
        if (slope >= SLOPE_GENTLE + noiseVar * 0.05f) {
            return noiseVar > 0.0f ? Blocks.GRAVEL.defaultBlockState()
                                   : Blocks.DIRT.defaultBlockState();
        }

        if (temperature > 0.55f && moisture < 0.28f) {
            int depth = height - y;
            if (depth < 2 && noiseVar > 0.0f) {
                return Blocks.SAND.defaultBlockState();
            }
            if (depth > 3 && noiseVar > -0.1f) {
                return Blocks.SANDSTONE.defaultBlockState();
            }
            return Blocks.GRAVEL.defaultBlockState();
        }

        if (temperature < 0.2f && moisture > 0.4f) {
            int depth = height - y;
            if (depth < 2 && noiseVar > 0.0f) {
                return Blocks.PODZOL.defaultBlockState();
            }
            return Blocks.DIRT.defaultBlockState();
        }

        if (temperature < 0.25f && moisture < 0.35f && y >= height - 2) {
            return noiseVar > 0.0f ? Blocks.GRAVEL.defaultBlockState()
                                   : Blocks.COARSE_DIRT.defaultBlockState();
        }

        if (noiseVar > 0.25f) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.DIRT.defaultBlockState();
    }

    public BlockState getDeepBlock(GeologySystem.GeologyProperties geology) {
        if (geology == null) return Blocks.STONE.defaultBlockState();

        switch (geology.rockType) {
            case GRANITE:
                return Blocks.GRANITE.defaultBlockState();
            case SANDSTONE:
                return Blocks.SANDSTONE.defaultBlockState();
            case SHALE:
            case CLAY:
                return Blocks.STONE.defaultBlockState();
            case LIMESTONE:
                return Blocks.STONE.defaultBlockState();
            case QUARTZITE:
                return Blocks.ANDESITE.defaultBlockState();
            case BASALT:
            case VOLCANIC:
                return Blocks.STONE.defaultBlockState();
            case PERMAFROST:
                return Blocks.PACKED_ICE.defaultBlockState();
            default:
                return Blocks.STONE.defaultBlockState();
        }
    }

    public BlockState getOceanFloorBlock(float depth, int y, int seaLevel, int wx, int wz) {
        float noiseVar = getNoiseVariation(wx, wz);
        int belowSea = seaLevel - y;
        if (belowSea > OCEAN_FLOOR_DEPTH + 8) {
            if (depth > 0.5f) {
                return noiseVar > 0.0f ? Blocks.GRAVEL.defaultBlockState()
                                      : Blocks.CLAY.defaultBlockState();
            }
            return noiseVar > 0.0f ? Blocks.SAND.defaultBlockState()
                                  : Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.SAND.defaultBlockState();
    }

    public BlockState getWaterBlock(int height, int seaLevel, int y, float temperature, int wx, int wz) {
        if (y > height && y <= seaLevel) {
            float noiseVar = getNoiseVariation(wx, wz);
            if (temperature < 0.25f && y >= seaLevel - 2) {
                if (temperature < 0.1f && noiseVar > -0.1f) {
                    return Blocks.BLUE_ICE.defaultBlockState();
                }
                if (temperature < 0.18f) {
                    return Blocks.PACKED_ICE.defaultBlockState();
                }
                if (noiseVar > 0.0f) {
                    return Blocks.ICE.defaultBlockState();
                }
            }
            if (temperature < 0.2f && y >= seaLevel - 1) {
                return noiseVar > -0.05f ? Blocks.ICE.defaultBlockState()
                                        : Blocks.WATER.defaultBlockState();
            }
            return Blocks.WATER.defaultBlockState();
        }
        return Blocks.AIR.defaultBlockState();
    }

    public int getSoilDepth(float temperature, float moisture, float elevation, float slope) {
        if (slope >= SLOPE_STEEP) return 0;
        if (slope >= SLOPE_MODERATE) return 1;
        if (slope >= SLOPE_GENTLE) return 2;
        if (elevation > 0.7f) return 2;
        if (temperature > 0.55f && moisture < 0.28f) return 3;
        return SOIL_DEPTH;
    }
}
