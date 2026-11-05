package com.geogenesis.worldgen.terrain.morph;

/** Complete distance query over the UNCHANGED curved geometry, with no height blending at seams. */
final class CompleteCurveQuery {
    private final CurvedSegmentErosion geometry;

    CompleteCurveQuery(CurvedSegmentErosion geometry) {
        this.geometry=geometry;
        for(float[] v:MorphCellTable.CELL) {
            if(v[0]<0 || v[0]>1 || v[1]<0 || v[1]>1) throw new IllegalArgumentException("node cell bound");
        }
        if(geometry.bend()>.15)throw new IllegalArgumentException("control excursion proof requires bend<=.15");
    }

    /**
     * A source cell c has A in [c,c+1]g and its receiver B in [c-1,c+2]g, componentwise.
     * Length <=2sqrt(2)g. All controls are in [c-1-.15*2sqrt(2), c+2+.15*2sqrt(2)]g:
     * sink offsets and downstream handles are each <=bend*length. Bezier and its polyline
     * lie in that convex hull. At radius 4, every omitted cell is > (3-.15*2sqrt(2))g
     * away in an axis (>g), while distances >=g saturate at 1. Thus omitted curves cannot
     * change the clamped answer. Radius is a proven bound, not a fitted halo.
     */
    double single(int octave,double x,double z) {
        double grid=geometry.grid(octave);
        int cx=(int)Math.floor(x/grid),cz=(int)Math.floor(z/grid);
        return forced(octave,cx,cz,x,z,true);
    }

    /** Same coordinate but an explicitly chosen anchor for left/right limit checks. */
    double forced(int octave,int cx,int cz,double x,double z,boolean prune) {
        double grid=geometry.grid(octave),best=grid*grid;
        // The prune bound is per source CELL, requiring no curve or raw evaluation.
        double excursion=geometry.bend()*2*Math.sqrt(2);
        for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++) {
            if(prune && cellBound2(cx+dx,cz+dz,grid,excursion,x,z)>Math.nextUp(best))continue;
            var edge=geometry.edge(octave,cx+dx,cz+dz);
            if(prune && controlBound2(edge,x,z)>Math.nextUp(best))continue;
            double d=geometry.geometryDistance2(edge,x,z);
            if(d<best)best=d;
        }
        return Math.min(1,Math.sqrt(best)/grid);
    }

    private static double cellBound2(int cx,int cz,double g,double e,double x,double z) {
        // Outward ULP guards make rejection conservative at large/negative coordinates.
        double scale=Math.max(Math.max(Math.abs(x),Math.abs(z)),Math.max(Math.abs(cx*g),Math.abs(cz*g)));
        double guard=32*Math.ulp(Math.max(1,scale));
        return boxDistance2((cx-1-e)*g-guard,(cx+2+e)*g+guard,
                (cz-1-e)*g-guard,(cz+2+e)*g+guard,x,z);
    }

    private static double controlBound2(CurvedSegmentErosion.Edge e,double x,double z) {
        double lx=Math.min(Math.min(e.ax(),e.bx()),Math.min(e.c1x(),e.c2x()));
        double hx=Math.max(Math.max(e.ax(),e.bx()),Math.max(e.c1x(),e.c2x()));
        double lz=Math.min(Math.min(e.az(),e.bz()),Math.min(e.c1z(),e.c2z()));
        double hz=Math.max(Math.max(e.az(),e.bz()),Math.max(e.c1z(),e.c2z()));
        double guard=32*Math.ulp(Math.max(1,Math.max(Math.max(Math.abs(lx),Math.abs(hx)),
                Math.max(Math.abs(lz),Math.abs(hz)))));
        return boxDistance2(lx-guard,hx+guard,lz-guard,hz+guard,x,z);
    }

    private static double boxDistance2(double lx,double hx,double lz,double hz,double x,double z) {
        double dx=Math.max(0,Math.max(lx-x,x-hx)),dz=Math.max(0,Math.max(lz-z,z-hz));
        return dx*dx+dz*dz;
    }

    double eval(double x,double z) {
        MorphNoise.Erosion n=geometry.original();
        double value=MorphNoiseEval.eval(n.input(),x,z),sum=0,max=0,gain=1;
        double amp=n.amplitude(),lac=n.lacunarity();
        for(int j=0;j<n.octaves();j++) {
            sum+=single(j,x,z)*gain;max+=gain;gain*=amp;x*=lac;z*=lac;
        }
        return MorphNoiseEval.lerp(sum/max,value,1-n.strength());
    }
}
