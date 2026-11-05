package com.geogenesis.worldgen.erosion;
import java.util.*;
import java.nio.file.*;
import java.io.*;
public final class Observation {
 public int iteration,scale,id,accepted,reason;
 public float[] birth;
 public final List<float[]> births=new ArrayList<>(),steps=new ArrayList<>();
 public final Map<String,float[]> snapshots=new LinkedHashMap<>();
 public final Map<String,double[]> fields=new LinkedHashMap<>();
 private final double[][][] accum=new double[2][3][4*146*146];
 private final int n=146*146;
 public void begin(float px,float py,int gx,int gz,int bn){
  scale=bn>100?0:bn>30?1:2; reason=0;id=births.size();
  birth=new float[]{id,iteration,scale,px,py,gx,gz,0,0,0,0};births.add(birth);
 }
 public void reason(int r){reason=r;birth[9]=r;}
 public void accept(){accepted++;birth[10]=1;}
 public void completed(float[] flat){
  if(reason==0)birth[9]=9;
  if(accepted==1||accepted==32||accepted==128||accepted==512)save("accepted"+accepted,flat);
 }
 public void step(int st,int idx,float px,float py,float nx,float ny,float dx,float dy,float h,float dh,float delta,float ld,float speed,boolean tracking){
  accum[iteration][scale][3*n+idx]++;
  steps.add(new float[]{id,iteration,scale,st,px,py,nx,ny,dx,dy,h,dh,delta,ld,speed,tracking?1:0});
 }
 public void brush(int i,float before,float after){double v=(double)after-before;accum[iteration][scale][(v<0?0:n)+i]+=Math.abs(v);}
 public void cascade(int i,float before,float after){accum[iteration][scale][2*n+i]+=(double)after-before;}
 public void save(String name,float[] f){
  snapshots.put(name,f.clone());
  double[] total=new double[4*n];for(int it=0;it<2;it++)for(int sc=0;sc<3;sc++)for(int i=0;i<total.length;i++)total[i]+=accum[it][sc][i];
  fields.put(name,total);
 }
 public void saveScales(String prefix){for(int sc=0;sc<3;sc++)fields.put(prefix+"-scale"+sc,accum[iteration][sc].clone());}
 public void write(Path o)throws Exception{
  Files.createDirectory(o);
  try(var w=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(o.resolve("snapshots.bin"))))){for(var e:snapshots.entrySet())for(float v:e.getValue())w.writeFloat(v);}
  Files.write(o.resolve("snapshot-names.txt"),snapshots.keySet());
  try(var w=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(o.resolve("fields.bin"))))){for(var e:fields.entrySet())for(double v:e.getValue())w.writeDouble(v);}
  Files.write(o.resolve("field-names.txt"),fields.keySet());
  for(var pair:List.of(new Object[]{"births.bin",births},new Object[]{"steps.bin",steps})){
   @SuppressWarnings("unchecked") List<float[]> rows=(List<float[]>)pair[1];
   try(var w=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(o.resolve((String)pair[0]))))){for(float[] row:rows)for(float v:row)w.writeFloat(v);}
  }
 }
}
