package com.geogenesis.worldgen.terrain;

import net.minecraft.nbt.CompoundTag;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * 通用 record ↔ CompoundTag 序列化器（仅依赖 MC NBT，零额外依赖）。
 *
 * <p>支持组件类型：{@code double} / {@code int} / {@code boolean} / {@code String} / 嵌套 record。
 * 用于把 {@link TerrainParams}（及其嵌套 {@code SplineConfig}/{@code OceanSplineConfig}/{@code MidSplineConfig}）
 * 以"按世界存档"方式持久化，免去手工编写数百行 Mojang Codec（字段极多且易因顺序错位损坏存档）。
 *
 * <p>约束：record 的所有叶子组件必须是上述基本/字符串类型之一（项目内 TerrainParams 树已确认满足）。
 * 缺失字段（旧版本存档）按类型默认零值读取——新增字段后旧存档需配合默认值逻辑，当前阶段由调用方保证字段齐备。
 */
public final class RecordSerializer {

    private RecordSerializer() {
    }

    /** 把 record 写入 tag（按组件名 → 值）。 */
    public static void writeRecord(Record rec, CompoundTag tag) {
        for (RecordComponent rc : rec.getClass().getRecordComponents()) {
            try {
                Object val = rec.getClass().getMethod(rc.getName()).invoke(rec);
                writeValue(tag, rc.getName(), val);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("序列化 record 组件失败: " + rc.getName(), e);
            }
        }
    }

    /** 从 tag 按规范构造器顺序重建 record。 */
    @SuppressWarnings("unchecked")
    public static <T extends Record> T readRecord(Class<T> clazz, CompoundTag tag) {
        RecordComponent[] comps = clazz.getRecordComponents();
        Class<?>[] types = new Class[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            RecordComponent rc = comps[i];
            types[i] = rc.getType();
            args[i] = readValue(rc.getType(), rc.getName(), tag);
        }
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor(types);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("反序列化 record 失败: " + clazz.getName(), e);
        }
    }

    private static void writeValue(CompoundTag tag, String name, Object val) {
        if (val instanceof Double d)       tag.putDouble(name, d);
        else if (val instanceof Integer i)  tag.putInt(name, i);
        else if (val instanceof Boolean b)  tag.putBoolean(name, b);
        else if (val instanceof String s)   tag.putString(name, s);
        else if (val instanceof Record r) {
            CompoundTag sub = new CompoundTag();
            writeRecord(r, sub);
            tag.put(name, sub);
        } else {
            throw new UnsupportedOperationException(
                "RecordSerializer 不支持的组件类型: " + (val == null ? "null" : val.getClass()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object readValue(Class<?> type, String name, CompoundTag tag) {
        if (type == double.class || type == Double.class)    return tag.getDouble(name);
        if (type == int.class    || type == Integer.class)   return tag.getInt(name);
        if (type == boolean.class|| type == Boolean.class)   return tag.getBoolean(name);
        if (type == String.class)                            return tag.getString(name);
        if (type.isRecord()) {
            CompoundTag sub = tag.getCompound(name);
            return readRecord((Class<? extends Record>) type, sub);
        }
        throw new UnsupportedOperationException(
            "RecordSerializer 不支持的反序列化类型: " + type + " (" + name + ")");
    }
}
