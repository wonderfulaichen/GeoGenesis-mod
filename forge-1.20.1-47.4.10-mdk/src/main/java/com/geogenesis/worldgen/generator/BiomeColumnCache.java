package com.geogenesis.worldgen.generator;

/** Direct-mapped shared 2D cache. All accesses to an entry require its slot monitor. */
public final class BiomeColumnCache<V> {
    public static final int SIZE = 65536;
    private final Object[] locks = new Object[SIZE];
    private final long[] keys = new long[SIZE];
    private final Object[] values = new Object[SIZE];
    private final int[] info = new int[SIZE];

    public BiomeColumnCache() {
        for (int i = 0; i < SIZE; i++) locks[i] = new Object();
    }

    public static int slot(int x, int z) { return (x * 66883 + z * 51749) & (SIZE - 1); }
    public static long key(int x, int z) { return (long) x << 32 | (z & 0xFFFFFFFFL); }
    public Object lock(int slot) { return locks[slot]; }

    public record Column<V>(V surface, int info, V result) { }

    /** Validity must be a lock-free token check, never acquire the lifecycle monitor here. */
    public V query(int x, int z, java.util.function.BooleanSupplier valid,
            java.util.function.Supplier<Column<V>> sample, Runnable hit) {
        int slot = slot(x, z);
        long key = key(x, z);
        synchronized (locks[slot]) {
            if (!valid.getAsBoolean()) return null;
            V cached = get(slot, key);
            if (cached != null) {
                if (hit != null) hit.run();
                return cached;
            }
            Column<V> column = sample.get();
            if (!valid.getAsBoolean()) return null;
            put(slot, key, column.surface(), column.info());
            return column.result();
        }
    }

    @SuppressWarnings("unchecked")
    public V get(int slot, long key) { return keys[slot] == key ? (V) values[slot] : null; }
    public int info(int slot) { return info[slot]; }
    public void put(int slot, long key, V value, int packedInfo) {
        keys[slot] = key;
        values[slot] = value;
        info[slot] = packedInfo;
    }
}
