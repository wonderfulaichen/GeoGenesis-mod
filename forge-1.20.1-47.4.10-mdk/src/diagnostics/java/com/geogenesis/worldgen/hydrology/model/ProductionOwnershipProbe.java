package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 检查北区已发布折线在南区 HALO 边界附近是否被列适配器裁断。 */
public final class ProductionOwnershipProbe {
    public static void main(String[] args) throws Exception {
        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY()); gen.seed(ViewSpec.SEED);
        var provider = new ModelHydrologyProvider(gen, ViewSpec.SEED);
        var north = shapes(provider.shapedNodesWorldForProbe(-256, 256));
        var south = shapes(provider.shapedNodesWorldForProbe(-256, 768));
        var index = ModelHydrologyProvider.SegmentIndex.buildFromNodes(north);
        StringBuilder csv = new StringBuilder("x,z,northHit,erosion,fill,lake\n");
        int missing = 0;
        for (int cx = -32; cx < 0; cx++) for (int cz = 37; cz <= 38; cz++) {
            Cell[] cells = new Cell[256];
            for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++)
                cells[i*16+j] = gen.sample((cx*16+i)/tp.horizontalScale(),(cz*16+j)/tp.horizontalScale());
            for (var c : provider.columnsFor(cx,cz,cells)) {
                if (c.blockZ() < 604 || c.blockZ() > 612 || index.nearest(c.blockX(), c.blockZ()) == null) continue;
                csv.append(c.blockX()).append(',').append(c.blockZ()).append(",true,")
                        .append(c.erosion()).append(',').append(c.fillWater()).append(',').append(c.lakePlan()).append('\n');
                if (!c.lakePlan() && c.erosion() <= 0) missing++;
            }
        }
        Path out=Path.of("build/lifecycle-diagnosis/ownership"); Files.createDirectories(out);
        Files.writeString(out.resolve("columns.csv"),csv);
        dump(out.resolve("north.csv"),north); dump(out.resolve("south.csv"),south);
        System.out.println("northPolys="+north.size()+" southPolys="+south.size()+" missing="+missing);
        if (missing > 0) throw new AssertionError("published north geometry dropped="+missing);
    }

    private static List<DeterministicSkeleton.Shaped> shapes(double[][] paths) {
        var result=new ArrayList<DeterministicSkeleton.Shaped>();
        for(var p:paths) {
            double[] x=new double[p.length/2],z=new double[x.length];
            for(int i=0;i<x.length;i++){x[i]=p[2*i];z[i]=p[2*i+1];}
            result.add(new DeterministicSkeleton.Shaped(x,z));
        }
        return result;
    }

    private static void dump(Path out,List<DeterministicSkeleton.Shaped> paths) throws Exception {
        var csv=new StringBuilder("trace,point,x,z\n");
        for(int t=0;t<paths.size();t++){var p=paths.get(t);for(int i=0;i<p.size();i++)
            csv.append(t).append(',').append(i).append(',').append(p.x[i]).append(',').append(p.z[i]).append('\n');}
        Files.writeString(out,csv);
    }
}
