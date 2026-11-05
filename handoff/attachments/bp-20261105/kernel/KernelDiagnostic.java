package com.geogenesis.worldgen.process;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.config.ParticleErosionConfigAdapter;
import com.geogenesis.worldgen.core.BaseWorldSettings;
import com.geogenesis.worldgen.erosion.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class KernelDiagnostic {
 static void binary(Path p,float[] a)throws Exception{try(var w=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW)))){for(float v:a)w.writeFloat(v);}}
 static int mismatch(float[] a,float[] b){int n=0;for(int i=0;i<a.length;i++)if(Float.floatToRawIntBits(a[i])!=Float.floatToRawIntBits(b[i]))n++;return n;}
 public static void main(String[] args)throws Exception{
  Path o=Path.of(args[0]),original=Path.of(args[1]);
  try(var config=CommentedFileConfig.of(original.resolve("config-input.toml"),TomlFormat.instance())){
   config.load();if(!GeoGenesisConfig.SPEC.isCorrect(config))throw new AssertionError("frozen config invalid");GeoGenesisConfig.SPEC.setConfig(config);
   var c=GeoGenesisConfig.INSTANCE;
   var ctx=new ProcessContext(new ProcessContext.Definition(9139912035078620160L,"P1-frozen-20261105",c.buildParams(),new BaseWorldSettings(c.tempInfluence.get(),c.humidityInfluence.get(),c.continentInfluence.get()),-64,320,ProcessContext.Mode.PARTICLE,ParticleErosionConfigAdapter.snapshot(),c.erosionXSEnabled.get(),c.erosionStrength.get().floatValue(),true,"config-input.toml;loaded;sha256=cc5d985f7554e4cfb316708e88373530241b9520e4ce983f745a6defcf50c16f"));
   if(!ctx.identity().equals(Files.readAllLines(original.resolve("bp-coast-slope-definition.txt")).get(0)))throw new AssertionError("identity");
   Files.writeString(o.resolve("identity.txt"),ctx.identity()+"\n"+java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()+"\n"+System.getProperty("java.version"));
   int[][] owners={{-1920,-2976},{-2112,-3024},{-2160,-2928}};
   double[][] centers={{-1912.5,-2941.5},{-2070.5,-2997.5},{-2114.5,-2907.5}};
   try(var metrics=Files.newBufferedWriter(o.resolve("bitwise.csv"),StandardOpenOption.CREATE_NEW)){
    metrics.write("tile,ownerX,ownerZ,flatMismatch,dischargeMismatch,limitedDeltaMismatch,inputMismatch,accepted,attempts,steps\n");
    for(int t=0;t<3;t++){
     var owner=new TileGeometry.Owner(owners[t][0],owners[t][1]);var d=ctx.definition();
     float[] pre=ParticleTile.buildInput(ctx,owner,()->false),a=pre.clone(),b=pre.clone(),da=new float[a.length],db=new float[a.length];
     var clean=new ErosionEngine(d.xs(),d::particle,8);clean.setHardnessProvider(ctx::resistance);
     clean.runErosionOnFlat(a,pre,146,128,owner.originX(),owner.originZ(),ctx.seaE(),d.strength(),da,(float)d.terrain().horizontalScale());
     var obs=new Observation();var observed=new ObservedErosionEngine(d.xs(),d::particle,8);observed.observation=obs;observed.setHardnessProvider(ctx::resistance);
     observed.runErosionOnFlat(b,pre,146,128,owner.originX(),owner.originZ(),ctx.seaE(),d.strength(),db,(float)d.terrain().horizontalScale());
     int mf=mismatch(a,b),md=mismatch(da,db),mi=mismatch(pre,ParticleTile.buildInput(ctx,owner,()->false)),ml=0;
     var tile=ParticleTile.build(ctx,owner,()->false);
     for(int z=0;z<128;z++)for(int x=0;x<128;x++){
      int i=(z+9)*146+x+9;float v=ParticleTile.limitedDelta(b[i],pre[i]);
      if(Float.floatToRawIntBits(v)!=Float.floatToRawIntBits((float)tile.at(owner.originX()+x,owner.originZ()+z)))ml++;
     }
     metrics.write(t+","+owner.x()+","+owner.z()+","+mf+","+md+","+ml+","+mi+","+obs.accepted+","+obs.births.size()+","+obs.steps.size()+"\n");metrics.flush();
     if(mf+md+mi+ml!=0)throw new AssertionError("bitwise failure");
     Path to=o.resolve("tile"+t);obs.write(to);binary(to.resolve("original-flat.bin"),a);binary(to.resolve("original-discharge.bin"),da);binary(to.resolve("observed-discharge.bin"),db);
     try(var w=Files.newBufferedWriter(to.resolve("base.csv"),StandardOpenOption.CREATE_NEW)){
      w.write("x,z,eB,yB,input,hardness,yInput,yFinal,limitedDelta,assembledE,assembledY\n");
      for(int z=0;z<146;z++)for(int x=0;x<146;x++){
       int i=z*146+x;double wx=owner.inputX()+x,wz=owner.inputZ()+z,e=ctx.baseE(wx,wz);float ld=ParticleTile.limitedDelta(b[i],pre[i]);double assembled=ctx.assemble(e,ld);
       w.write(wx+","+wz+","+e+","+ctx.height(e)+","+pre[i]+","+ctx.resistance(wx,wz)+","+ctx.height(pre[i])+","+ctx.height(b[i])+","+ld+","+assembled+","+ctx.height(assembled)+"\n");
      }
     }
     Files.writeString(to.resolve("geometry.txt"),"owner="+owner+"\ninputOrigin="+owner.inputX()+","+owner.inputZ()+"\ncenter="+centers[t][0]+","+centers[t][1]+"\ngrid=146x146 step1wu; pad9; simulation128; owner48; halo40; HS2; C/M/F interleaved; iterations2; XSfalse\n");
     System.out.println("tile "+t+" bitwise flat/discharge/limited/input mismatches=0; steps="+obs.steps.size());
    }
   }
   try(var rt=new ProcessRuntime(ctx,new ProcessRuntime.Budget(2,16,128));var w=Files.newBufferedWriter(o.resolve("exact-local.csv"),StandardOpenOption.CREATE_NEW)){
    w.write("tile,x,z,eB,yB,eP,yP,deltaE,deltaY\n");
    for(int t=0;t<3;t++)for(int j=0;j<49;j++)for(int i=0;i<49;i++){
     double x=centers[t][0]-24+i,z=centers[t][1]-24+j;var p=rt.exactP(x,z);
     w.write(t+","+x+","+z+","+p.base().eB()+","+p.base().yB()+","+p.eP()+","+p.yP()+","+p.deltaE()+","+p.deltaY()+"\n");
    }
    Files.writeString(o.resolve("runtime-metrics.txt"),rt.metrics().toString());
   }
  }
 }
}
