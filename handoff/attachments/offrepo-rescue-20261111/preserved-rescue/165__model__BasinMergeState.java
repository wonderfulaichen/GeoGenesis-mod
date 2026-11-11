package com.geogenesis.worldgen.hydrology.model;

/** Equilibrium merge eligibility, not an instantaneous replacement for transient face fluxes. */
public final class BasinMergeState {
    public enum Status { SEPARATE, CONNECTED_EQUILIBRIUM, NEXT_SADDLE_PENDING }
    public record Result(Status status, double volume, double level) { }
    private final BasinStorage parent;
    private final double joiningHeight;

    public BasinMergeState(BasinStorage parent, double joiningHeight) {
        if (!parent.complete() || !Double.isFinite(joiningHeight) || joiningHeight >= parent.crest()) {
            throw new IllegalArgumentException("complete parent with a higher next saddle required");
        }
        this.parent = parent; this.joiningHeight = joiningHeight;
    }

    /** Input is water already accounted for in the complete parent domain after equilibration. */
    public Result equilibrium(double totalVolume) {
        if (!Double.isFinite(totalVolume) || totalVolume < 0) throw new IllegalArgumentException("volume");
        if (totalVolume > parent.volumeAt(parent.crest())) {
            return new Result(Status.NEXT_SADDLE_PENDING, totalVolume, Double.NaN);
        }
        // At exactly the saddle the wet regions still only touch a zero-depth crest.
        if (totalVolume <= parent.volumeAt(joiningHeight)) {
            return new Result(Status.SEPARATE, totalVolume, Double.NaN);
        }
        double level;
        try { level = parent.levelForVolume(totalVolume); }
        catch (IllegalStateException disconnected) {
            return new Result(Status.SEPARATE, totalVolume, Double.NaN);
        }
        return new Result(Status.CONNECTED_EQUILIBRIUM, totalVolume, level);
    }
}
