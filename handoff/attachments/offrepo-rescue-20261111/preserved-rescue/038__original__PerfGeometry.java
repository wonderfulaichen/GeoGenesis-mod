package com.geogenesis.worldgen.terrain.candidate;
import java.util.*;
public class PerfGeometry {
 static final long SEED=9139912035078620160L; static volatile double sink;
 public static void main(String[] a){int zone=Integer.parseInt(a[0]);var cat=new RecipeMorphCatalog(SEED);double x=zone*1024+512,z=512; for(int k=0;k<8;k++){var land=new DoubleFullMorphLand(cat);long t=System.nanoTime();var tile=land.tileAt(x,z);long build=System.nanoTime()-t;var edges=tile.geometry().edges();int seg=0;double max=0;for(var e:edges){seg+=e.wx.length-1;for(int i=0;i+1<e.wx.length;i++)max=Math.max(max,Math.hypot(e.wx[i+1]-e.wx[i],e.wz[i+1]-e.wz[i]));} double sum=0;long st=System.nanoTime();for(int i=0;i<10000;i++){double xx=zone*1024+(i%100)*10+.25,zz=(i/100)*10+.75;sum+=land.e(xx,zz);}long sample=System.nanoTime()-st;sink=sum;System.out.printf(Locale.ROOT,"%d,%d,%.6f,%.6f,%d,%d,%.9f,%.17g%n",zone,k,build/1e6,sample/1e6,edges.size(),seg,max,sum);}}
}
