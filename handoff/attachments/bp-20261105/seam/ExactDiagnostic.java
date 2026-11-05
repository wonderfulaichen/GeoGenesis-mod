package com.geogenesis.worldgen.process;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.config.ParticleErosionConfigAdapter;
import com.geogenesis.worldgen.core.BaseWorldSettings;
import java.nio.file.*;
import java.util.*;
public final class ExactDiagnostic {
 public static void main(String[] args) throws Exception {
  Path o=Path.of(args[0]), original=Path.of(args[1]);
  try(var config=CommentedFileConfig.of(original.resolve("config-input.toml"),TomlFormat.instance())) {
   config.load();
   if(!GeoGenesisConfig.SPEC.isCorrect(config))throw new AssertionError("invalid frozen config");
   GeoGenesisConfig.SPEC.setConfig(config);
   var c=GeoGenesisConfig.INSTANCE;
   var ctx=new ProcessContext(new ProcessContext.Definition(9139912035078620160L,
    "P1-frozen-20261105",c.buildParams(),new BaseWorldSettings(c.tempInfluence.get(),c.humidityInfluence.get(),c.continentInfluence.get()),
    -64,320,ProcessContext.Mode.PARTICLE,ParticleErosionConfigAdapter.snapshot(),c.erosionXSEnabled.get(),c.erosionStrength.get().floatValue(),true,
    "config-input.toml;loaded;sha256=cc5d985f7554e4cfb316708e88373530241b9520e4ce983f745a6defcf50c16f"));
   String originalIdentity=Files.readAllLines(original.resolve("bp-coast-slope-definition.txt")).get(0);
   if(!ctx.identity().equals(originalIdentity))throw new AssertionError("identity mismatch");
   Files.writeString(o.resolve("exact-identity.txt"),ctx.identity()+"\nIDENTICAL TO ORIGINAL\n");
   long start=System.nanoTime();
   try(var runtime=new ProcessRuntime(ctx,new ProcessRuntime.Budget(2,16,128));
       var w=Files.newBufferedWriter(o.resolve("exact-samples.csv"),StandardOpenOption.CREATE_NEW)) {
    w.write("id,x,z,eB,yB,eP,yP,deltaE,deltaY\n");
    for(String line:Files.readAllLines(o.resolve("queries.csv")).subList(1,Files.readAllLines(o.resolve("queries.csv")).size())) {
     String[] v=line.split(",");double x=Double.parseDouble(v[1]),z=Double.parseDouble(v[2]);var p=runtime.exactP(x,z);
     w.write(v[0]+","+x+","+z+","+p.base().eB()+","+p.base().yB()+","+p.eP()+","+p.yP()+","+p.deltaE()+","+p.deltaY()+"\n");
    }
    System.out.println("exact seconds="+(System.nanoTime()-start)/1e9+" "+runtime.metrics());
   }
   // Diagnostic-only package access; independent original tiles at fixed coastal profile locations.
   Map<TileGeometry.Owner,ParticleTile> cache=new HashMap<>();
   try(var w=Files.newBufferedWriter(o.resolve("tile-contributions.csv"),StandardOpenOption.CREATE_NEW)) {
    w.write("id,x,z,ownerX,ownerZ,weight,independentDeltaE\n");
    for(String line:Files.readAllLines(o.resolve("queries.csv"))) {
     if(!line.startsWith("profile"))continue;
     String[] v=line.split(",");double x=Double.parseDouble(v[1]),z=Double.parseDouble(v[2]);var f=TileGeometry.footprint(x,z);
     for(var owner:f.required()) {
      ParticleTile tile=cache.get(owner);
      if(tile==null){tile=ParticleTile.build(ctx,owner,()->false);cache.put(owner,tile);}
      double wx=owner.x()==f.main().x()?1-f.fx():f.fx();
      double wz=owner.z()==f.main().z()?1-f.fz():f.fz();
      w.write(v[0]+","+x+","+z+","+owner.x()+","+owner.z()+","+(wx*wz)+","+tile.at(x,z)+"\n");
     }
    }
   }
   System.out.println("independent tiles="+cache.size()+" total seconds="+(System.nanoTime()-start)/1e9);
  }
 }
}
