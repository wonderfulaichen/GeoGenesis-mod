package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.*;
import java.util.*;

/**
 * Figure input only: exports integer-block height grids sampled through the production terrain entry
 * ({@code CellGenerator.erodedHeight} with the production wu conversion {@code block / horizontalScale}).
 *
 * <p>This is <b>terrain geometry</b>, not a hydrology verdict and not a preview acceptance image: no sea/lake/channel
 * classification, no provider, no water. The rendered figure must be labelled diagnostic. Nothing here asserts.</p>
 */
public final class SharedFlowTerrainExport {
    private static final Path OUT=Path.of(System.getProperty("localFlow.output","build/local-flow-reassessment/figure"));
    /** name, x0, z0, size, step — fixed so two cold runs export identical grids. */
    private static final Object[][] REGIONS={
            {"basin-detail",-464,-130,80,1},
            {"receiver-detail",-792,360,48,1},
            {"overview",-820,-160,480,4},
            {"combined",-820,-160,620,5}
    };
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);long seed=args.length>0?Long.parseLong(args[0]):ViewSpec.SEED;
        Path config=Path.of("run/config/geogenesis-common.toml");byte[] original=Files.readAllBytes(config);
        Path copy=OUT.resolve("config.toml");Files.write(copy,original);
        var gen=generator(config,seed);
        double hs=gen.params().horizontalScale();
        WindowOutlets.Terrain terrain=(x,z)->gen.erodedHeight(x/hs,z/hs);
        var manifest=new StringBuilder("region,x0,z0,size,step,horizontalScale,seaLevel,seed\n");
        for(var region:REGIONS)export(terrain,(String)region[0],(int)region[1],(int)region[2],(int)region[3],
                (int)region[4],hs,gen.seaLevel(),seed,manifest);
        Files.writeString(OUT.resolve("manifest.csv"),manifest);
        if(!Arrays.equals(original,Files.readAllBytes(config)))throw new IllegalStateException("user TOML changed during export");
        System.out.print(manifest);
        System.out.println("terrain grids exported; DIAGNOSTIC GEOMETRY ONLY (no provider, no water, no acceptance)");
    }
    private static CellGenerator generator(Path config,long seed) {
        try(var cfg=CommentedFileConfig.of(config,TomlFormat.instance())) {
            cfg.load();GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams params=GeoGenesisConfig.INSTANCE.buildParams();
            var gen=new CellGenerator(params,params.minY(),params.maxY());gen.seed(seed);return gen;
        }
    }
    private static void export(WindowOutlets.Terrain terrain,String name,int x0,int z0,int size,int step,
                               double hs,double sea,long seed,StringBuilder manifest) throws Exception {
        if(size<2 || step<1)throw new IllegalArgumentException("grid geometry");
        var csv=new StringBuilder("x,z,height\n");int samples=0;
        for(int i=0;i<size;i+=step)for(int j=0;j<size;j+=step) {
            int x=x0+i,z=z0+j;double h=terrain.height(x,z);
            if(!Double.isFinite(h))throw new IllegalArgumentException("nonfinite terrain at "+x+","+z);
            csv.append(x).append(',').append(z).append(',').append(h).append('\n');samples++;
        }
        Files.writeString(OUT.resolve(name+".csv"),csv);
        manifest.append(name).append(',').append(x0).append(',').append(z0).append(',').append(size).append(',')
                .append(step).append(',').append(hs).append(',').append(sea).append(',').append(seed).append('\n');
        System.out.println(name+" grid="+size+"x"+size+" step="+step+" samples="+samples);
    }
}
