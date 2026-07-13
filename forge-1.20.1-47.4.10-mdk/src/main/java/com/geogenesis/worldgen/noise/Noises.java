package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 噪声节点注册表与统一反序列化编码（DIRECT_CODEC）。
 * 预留给 P9 数据包覆盖：worldgen/noise/*.json 以 {type, ...} 描述噪声图。
 *
 * 字段初始化顺序保证：REGISTRY / CLASS_TO_TYPE / DIRECT_CODEC 在 static 注册块之前
 * 完成，DIRECT_CODEC 的 decode 惰性读取 REGISTRY，避免节点 Codec 反向引用时的
 * 静态初始化死循环（节点 codec 引用 Noises.DIRECT_CODEC）。
 */
public final class Noises {

    private static final Map<String, Codec<? extends Noise>> REGISTRY = new HashMap<>();
    private static final Map<Class<? extends Noise>, String> CLASS_TO_TYPE = new HashMap<>();

    @SuppressWarnings("unchecked")
    public static final Codec<Noise> DIRECT_CODEC = Codec.STRING.dispatch(
            "type",
            (Function<Noise, String>) (Noise n) -> CLASS_TO_TYPE.getOrDefault(n.getClass(), "constant"),
            (Function<String, Codec<? extends Noise>>) (String type) -> REGISTRY.getOrDefault(type, Constant.CODEC)
    );

    private Noises() {
    }

    public static void register(String name, Codec<? extends Noise> codec, Class<? extends Noise> cls) {
        REGISTRY.put(name, codec);
        CLASS_TO_TYPE.put(cls, name);
    }

    static {
        register("constant", Constant.CODEC, Constant.class);
        register("white", White.CODEC, White.class);
        register("simplex", Simplex.CODEC, Simplex.class);
        register("ridge", Ridge.CODEC, Ridge.class);
        register("billow", Billow.CODEC, Billow.class);
        register("add", Add.CODEC, Add.class);
        register("multiply", Multiply.CODEC, Multiply.class);
        register("min", Min.CODEC, Min.class);
        register("max", Max.CODEC, Max.class);
        register("abs", Abs.CODEC, Abs.class);
        register("invert", Invert.CODEC, Invert.class);
        register("power", Power.CODEC, Power.class);
        register("clamp", Clamp.CODEC, Clamp.class);
        register("frequency", Frequency.CODEC, Frequency.class);
        register("blend", Blend.CODEC, Blend.class);
        register("boost", Boost.CODEC, Boost.class);
        register("cache2d", Cache2d.CODEC, Cache2d.class);
        register("warp", Warp.CODEC, Warp.class);
        register("map", com.geogenesis.worldgen.noise.Map.CODEC, com.geogenesis.worldgen.noise.Map.class);
        register("curve", Curve.CODEC, Curve.class);
        register("terrace", Terrace.CODEC, Terrace.class);
        register("steps", Steps.CODEC, Steps.class);
    }

    /**
     * 递归播种所有 Seeded 子节点（含嵌套）。
     */
    public static void seedAll(Noise noise, long seed, int level) {
        noise.mapAll(n -> {
            if (n instanceof Seeded s) {
                s.seed(seed, level);
            }
            return n;
        });
    }
}
