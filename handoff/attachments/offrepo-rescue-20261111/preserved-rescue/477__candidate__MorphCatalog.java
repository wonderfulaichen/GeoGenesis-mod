package com.geogenesis.worldgen.terrain.candidate;

/** 保留陆地身份顺序；海洋目录独立播种，不消费陆地游标。 */
final class MorphCatalog {
    private MorphCatalog() {}

    static MorphRecipe[] land(RecipeMorphCatalog catalog) {
        MorphRecipe[] result = new MorphRecipe[catalog.size()];
        for (int i = 0; i < result.length; i++) {
            RecipeMorphCatalog.Entry entry = catalog.entry(i);
            result[i] = new MorphRecipe() {
                public double raw(double x, double z) { return entry.raw((float) x, (float) z); }
                public double amplitude(double x, double z) { return entry.amplitude((float) x, (float) z); }
                public double bias(double x, double z) { return entry.bias((float) x, (float) z); }
                public Role role() { return Role.valueOf(entry.kind().name()); }
                public boolean land() { return true; }
                public boolean clamped() { return entry.clamped(); }
            };
        }
        return result;
    }

    static MorphRecipe[] world(RecipeMorphCatalog land, OceanRecipeCatalog ocean) {
        MorphRecipe[] result = java.util.Arrays.copyOf(land(land), land.size() + OceanRecipeCatalog.ENTRY_COUNT);
        for (int i = 0; i < OceanRecipeCatalog.ENTRY_COUNT; i++) result[land.size() + i] = ocean.morphEntry(i);
        return result;
    }
}
