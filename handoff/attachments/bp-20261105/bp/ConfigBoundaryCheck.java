package com.geogenesis.worldgen.terrain;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.erosion.RidgeValleyErosion;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.security.MessageDigest;
public final class ConfigBoundaryCheck {
 public static void main(String[] args)throws Exception {
  Path out=Path.of(args[1]);StringBuilder log=new StringBuilder();
  var c=GeoGenesisConfig.INSTANCE;
  require(!GeoGenesisConfig.SPEC.isLoaded(),"fresh JVM must be unloaded");
  dump(log,"unloaded");
  try(var cfg=CommentedFileConfig.of(Path.of(args[0]),TomlFormat.instance())) {
   cfg.load();GeoGenesisConfig.SPEC.setConfig(cfg);dump(log,"loaded");
   var p=c.buildParams();var gen=new CellGenerator(p,-64,320);gen.seed(9139912035078620160L);
   int size=128,n=146,ox=-1000,oz=3800;float[] input=new float[n*n];
   for(int z=0;z<n;z++)for(int x=0;x<n;x++) input[z*n+x]=(float)Math.max(gen.terrainEQuick(ox+x-9,oz+z-9),-0.05);
   float[] a=run(input,n,size,ox,oz,1f),b=run(input,n,size,ox,oz,3f);
   require(Arrays.equals(a,b),"positive str currently ignored: evidence changed");
   log.append("str1_vs_str3_bitwiseEqual=true\n");
   double old=c.erosionErodeMul.get();long fp=GeoGenesisConfig.configFingerprint();
   c.erosionErodeMul.set(3.0);b=run(input,n,size,ox,oz,1f);
   require(Arrays.equals(a,b),"erode multiplier currently ignored: evidence changed");
   log.append("erodeMul1_vs_3_bitwiseEqual=true fingerprintEqual=").append(fp==GeoGenesisConfig.configFingerprint()).append('\n');
   c.erosionErodeMul.set(old);
   old=c.erosionCascadeStrength.get();c.erosionCascadeStrength.set(0.0);b=run(input,n,size,ox,oz,1f);
   require(!Arrays.equals(a,b),"cascade negative control did not affect output");
   log.append("cascade0_changesOutput=true fingerprintEqual=").append(fp==GeoGenesisConfig.configFingerprint()).append('\n');
   c.erosionCascadeStrength.set(old);
   log.append("particleBaselineSha=").append(hash(a)).append("\ncascadeZeroSha=").append(hash(b)).append('\n');
   boolean se=c.erosionEnabled.get(),sr=c.erosionRidgeEnabled.get();
   c.erosionEnabled.set(false);c.erosionRidgeEnabled.set(false);
   var off=new CellGenerator(p,-64,320);off.seed(9139912035078620160L);
   var t=off.getErosionTileResultForProbe(-960,3840);long count=0;
   for(float[] row:t.delta)for(float v:row)if(v!=0f)count++;
   log.append("bothOff_nonzeroDeltaCells=").append(count).append("/16384\n");
   c.erosionEnabled.set(se);c.erosionRidgeEnabled.set(sr);
  }
  Files.writeString(out,log.toString());System.out.print(log);System.out.println("CONFIG BOUNDARY CHECK PASS");
 }
 static float[] run(float[] input,int n,int size,int ox,int oz,float str) {
  float[] a=input.clone();com.geogenesis.config.ParticleErosionConfigAdapter.create(false,CellGenerator.TILE_PARALLELISM).runErosionOnFlat(a,input.clone(),n,size,ox,oz,0f,str,null,2f);return a;
 }
 static String hash(float[] a)throws Exception{
  var b=new ByteArrayOutputStream();try(var d=new DataOutputStream(b)){for(float f:a)d.writeInt(Float.floatToRawIntBits(f));}
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toByteArray()));
 }
 static void dump(StringBuilder log,String label)throws Exception {
  var c=GeoGenesisConfig.INSTANCE;log.append("["+label+"]\n");
  for(var f:c.getClass().getFields())if(f.getName().startsWith("erosion")||f.getName().equals("horizontalScale")){
   Object v=f.get(c);var get=v.getClass().getMethod("get");String val;
   try{val=String.valueOf(get.invoke(v));}catch(ReflectiveOperationException e){val="UNLOADED:"+e.getCause().getClass().getSimpleName();}
   log.append(f.getName()).append('=').append(val).append('\n');
  }
  var r=com.geogenesis.config.RidgeConfigAdapter.fromConfig(c);
  log.append("ridge.gullyWeight=").append(r.gullyWeight).append(" rounding=").append(Arrays.toString(r.rounding)).append(" hs=").append(r.horizontalScale).append('\n');
 }
 static void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
}
