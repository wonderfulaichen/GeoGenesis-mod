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
public final class ChainDiagnostic {
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

   Map<TileGeometry.Owner,ParticleTile> tiles=new HashMap<>();
   Map<TileGeometry.Owner,float[]> observed=new HashMap<>();
   int[][] owners={{-1920,-2976},{-2112,-3024},{-2160,-2928}};
   for(int t=0;t<3;t++){
    var owner=new TileGeometry.Owner(owners[t][0],owners[t][1]);
    float[] post=new float[146*146];
    try(var r=new DataInputStream(new BufferedInputStream(Files.newInputStream(o.resolve("tile"+t+"/original-flat.bin"))))){for(int i=0;i<post.length;i++)post[i]=r.readFloat();}
    float[] pre=ParticleTile.buildInput(ctx,owner,()->false),delta=new float[128*128];
    for(int z=0;z<128;z++)for(int x=0;x<128;x++){int i=(z+9)*146+x+9;delta[z*128+x]=ParticleTile.limitedDelta(post[i],pre[i]);}
    observed.put(owner,delta);
    float[][] g=ErosionEngine.buildHardnessGrid(ctx::resistance,11,owner.inputX(),owner.inputZ());
    float[] hard=new float[146*146];for(int z=0;z<146;z++)for(int x=0;x<146;x++)hard[z*146+x]=ErosionEngine.sampleHardness(g,11,owner.inputX(),owner.inputZ(),owner.inputX()+x,owner.inputZ()+z);
    binary(o.resolve("tile"+t+"/sampled-hardness.bin"),hard);
   }
   int n=0,bad=0,selected=0;
   try(var w=Files.newBufferedWriter(o.resolve("chain.csv"),StandardOpenOption.CREATE_NEW)){
    w.write("tile,x,z,originalEP,reconstructedEP,selectedWeight,delta00,delta10,delta01,delta11,fx,fz\n");
    for(String line:Files.readAllLines(o.resolve("exact-local.csv")).subList(1,7204)){
     String[] v=line.split(",");double x=Double.parseDouble(v[1]),z=Double.parseDouble(v[2]),ep=Double.parseDouble(v[5]);var f=TileGeometry.footprint(x,z);
     for(var owner:f.required())if(!tiles.containsKey(owner))tiles.put(owner,ParticleTile.build(ctx,owner,()->false));
     double d00=at(f.main(),x,z,tiles,observed),d10=f.fx()>0?at(f.sideX(),x,z,tiles,observed):d00;
     double d01=f.fz()>0?at(f.sideZ(),x,z,tiles,observed):d00;
     double d11=f.fx()>0&&f.fz()>0?at(f.corner(),x,z,tiles,observed):f.fx()>0?d10:d01;
     double delta=d00;
     if(f.fx()>0||f.fz()>0)delta=d00*(1-f.fx())*(1-f.fz())+d10*f.fx()*(1-f.fz())+d01*(1-f.fx())*f.fz()+d11*f.fx()*f.fz();
     double rec=ctx.assemble(ctx.baseE(x,z),delta),weight=0;
     for(var owner:f.required())if(observed.containsKey(owner))weight+=(owner.x()==f.main().x()?1-f.fx():f.fx())*(owner.z()==f.main().z()?1-f.fz():f.fz());
     if(weight>0)selected++;if(Double.doubleToRawLongBits(ep)!=Double.doubleToRawLongBits(rec))bad++;n++;
     w.write(v[0]+","+x+","+z+","+ep+","+rec+","+weight+","+d00+","+d10+","+d01+","+d11+","+f.fx()+","+f.fz()+"\n");
    }
   }
   Files.writeString(o.resolve("chain-result.txt"),"points="+n+"; mismatches="+bad+"; observed-tile-contributing-points="+selected+"; required-tiles="+tiles.size()+"\n");
   if(bad!=0)throw new AssertionError("chain mismatch");
  }
 }
 static double at(TileGeometry.Owner owner,double x,double z,Map<TileGeometry.Owner,ParticleTile> tiles,Map<TileGeometry.Owner,float[]> observed){
  float[] delta=observed.get(owner);if(delta==null)return tiles.get(owner).at(x,z);
  double lx=x-owner.originX(),lz=z-owner.originZ();int ix=(int)Math.floor(lx),iz=(int)Math.floor(lz);
  float fx=(float)(lx-ix),fz=(float)(lz-iz);int i=iz*128+ix;
  float top=delta[i]+(delta[i+1]-delta[i])*fx;float bot=delta[i+128]+(delta[i+129]-delta[i+128])*fx;
  return top+(bot-top)*fz;
 }
}
