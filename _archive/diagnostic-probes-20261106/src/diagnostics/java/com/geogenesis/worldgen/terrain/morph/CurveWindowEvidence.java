package com.geogenesis.worldgen.terrain.morph;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Narrow root-cause instrumentation: same-point candidate sets, no terrain mutation. */
final class CurveWindowEvidence {
    record Hit(double value, int sourceX, int sourceZ) {}
    record Seam(int octave, int axis, int cx, int cz, double x, double z,
                Hit left, Hit right, Hit oracle) {}

    static MorphNoise.Erosion production() throws Exception {
        MorphCatalog catalog = new MorphCatalog(MorphCatalog.CANONICAL_SEED);
        MorphKernel kernel = new MorphKernel(MorphLandRecipe.all(catalog), MorphCatalog.CANONICAL_SEED, true);
        Field f = MorphKernel.class.getDeclaredField("erosion"); f.setAccessible(true);
        return (MorphNoise.Erosion) f.get(kernel);
    }

    static Hit hit(CurvedSegmentErosion arm, int octave, int cx, int cz,
                   double x, double z, int radius) {
        double best = Double.MAX_VALUE; int sx = cx, sz = cz;
        for (int dz=-radius;dz<=radius;dz++) for (int dx=-radius;dx<=radius;dx++) {
            double d = arm.geometryDistance2(arm.edge(octave,cx+dx,cz+dz),x,z);
            if (d < best) { best=d; sx=cx+dx; sz=cz+dz; }
        }
        return new Hit(Math.min(1,Math.sqrt(best)/arm.grid(octave)),sx,sz);
    }

    /** Largest same-coordinate 3x3 support discrepancy per axis, in the frozen mountain window. */
    static Seam[] scan(CurvedSegmentErosion arm) {
        Seam[] worst = new Seam[2]; double[] maximum = {-1,-1};
        double frequency=1;
        for(int octave=0;octave<arm.original().octaves();octave++) {
            double grid=arm.grid(octave);
            int x0=(int)Math.ceil(-4158.0078086275535*frequency/grid);
            int x1=(int)Math.floor(-2328.0078103318738*frequency/grid);
            int z0=(int)Math.ceil(-4279.531246014376*frequency/grid);
            int z1=(int)Math.floor(-2449.531247718696*frequency/grid);
            for(int axis=0;axis<2;axis++) {
                // Fixed stride only reduces along-boundary samples, not the candidates at a sample.
                for(int cell=(axis==0?x0:z0);cell<=(axis==0?x1:z1);cell++) {
                    for(int row=(axis==0?z0:x0);row<=(axis==0?z1:x1);row+=2) {
                        for(int part=0;part<3;part++) {
                            int cx=axis==0?cell:row,cz=axis==0?row:cell;
                            double x=(cx+(axis==0?0:(part+.37)/3))*grid;
                            double z=(cz+(axis==1?0:(part+.37)/3))*grid;
                            Hit a=hit(arm,octave,cx-(axis==0?1:0),cz-(axis==1?1:0),x,z,1);
                            Hit b=hit(arm,octave,cx,cz,x,z,1);
                            double difference=Math.abs(a.value-b.value);
                            if(difference>maximum[axis]) {
                                Hit oracle=hit(arm,octave,cx,cz,x,z,6);
                                maximum[axis]=difference;worst[axis]=new Seam(octave,axis,cx,cz,x,z,a,b,oracle);
                            }
                        }
                    }
                }
            }
            frequency*=arm.original().lacunarity();
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        MorphNoise.Erosion original=production();
        CurvedSegmentErosion coarse=new CurvedSegmentErosion(original,.15);
        CurvedSegmentErosion fine=new CurvedSegmentErosion(original,.15,256);
        Path log=Path.of("../debug-agent.log").toAbsolutePath().normalize();
        for(Seam seam:scan(coarse)) {
            Hit leftFine=hit(fine,seam.octave,seam.cx-(seam.axis==0?1:0),
                    seam.cz-(seam.axis==1?1:0),seam.x,seam.z,1);
            Hit rightFine=hit(fine,seam.octave,seam.cx,seam.cz,seam.x,seam.z,1);
            String line=String.format(Locale.ROOT,
                    "{\"hypothesis\":\"A_missing_source_vs_B_polyline\",\"location\":\"CurveWindowEvidence\","
                    +"\"axis\":%d,\"octave\":%d,\"x\":%.17g,\"z\":%.17g,\"oldLeft\":%.17g,\"oldRight\":%.17g,"
                    +"\"oracleRadius6\":%.17g,\"winnerSource\":[%d,%d],\"fine256Left\":%.17g,\"fine256Right\":%.17g}%n",
                    seam.axis,seam.octave,seam.x,seam.z,seam.left.value,seam.right.value,
                    seam.oracle.value,seam.oracle.sourceX,seam.oracle.sourceZ,leftFine.value,rightFine.value);
            System.out.print(line);
            // #region agent log: keep until user confirms the seam repair.
            Files.writeString(log,line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            // #endregion
            if(Math.abs(seam.left.value-seam.right.value)<1e-3) throw new AssertionError("no reproduction");
            if(Math.abs(leftFine.value-rightFine.value)<1e-3) throw new AssertionError("coarse-only error");
        }
    }
}
