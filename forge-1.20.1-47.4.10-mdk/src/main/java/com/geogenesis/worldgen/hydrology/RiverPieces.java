package com.geogenesis.worldgen.hydrology;

import java.util.Arrays;

public class RiverPieces {

    public static final RiverPieces NONE = new RiverPieces();

    private static final int INITIAL_SIZE = 4;

    private int riverCount = 0;
    private int lakeCount = 0;
    private RiverNode[] riverNodes = new RiverNode[INITIAL_SIZE];
    private RiverNode[] lakeNodes = new RiverNode[INITIAL_SIZE];

    RiverPieces() {}

    public RiverPieces reset() {
        riverCount = 0;
        lakeCount = 0;
        return this;
    }

    public int riverCount() { return riverCount; }
    public int lakeCount() { return lakeCount; }

    public RiverNode river(int i) { return riverNodes[i]; }
    public RiverNode lake(int i) { return lakeNodes[i]; }

    public void addRiver(RiverNode node) {
        riverNodes = ensureCapacity(riverCount, riverNodes);
        riverNodes[riverCount++] = node;
    }

    public void addLake(RiverNode node) {
        lakeNodes = ensureCapacity(lakeCount, lakeNodes);
        lakeNodes[lakeCount++] = node;
    }

    private static RiverNode[] ensureCapacity(int size, RiverNode[] array) {
        if (size < array.length) return array;
        return Arrays.copyOf(array, size + 1);
    }
}
