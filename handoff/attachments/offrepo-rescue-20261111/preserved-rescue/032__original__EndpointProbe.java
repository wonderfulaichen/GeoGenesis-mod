package com.geogenesis.worldgen.terrain.candidate;
import java.util.*;
public class EndpointProbe {
 public static void main(String[] args) {
  long seed=9139912035078620160L; var cat=new RecipeMorphCatalog(seed); var land=new DoubleFullMorphLand(cat);
  int[][] pairs={{0,0},{-4,3},{1831,1831},{-1832,-1832},{976,976},{-977,-977},{29295,29295},{-29296,-29296},{976,-977},{-977,976},{29295,-29296},{-29296,29295}};
  int ends=0,junction=0,other=0; double maxW=0,maxJ=0,maxO=0,maxSeg=0; String where="";
  for(int axis=0;axis<2;axis++)for(int[] p:pairs){double x=(p[0]+(axis==0?1:.5))*1024.,z=(p[1]+(axis==1?1:.5))*1024.;
   var a=land.tileAt(x-(axis==0?1:0),z-(axis==1?1:0)); var b=land.tileAt(x+(axis==0?1:0),z+(axis==1?1:0));
   for(int side=0;side<2;side++){var t=side==0?a:b; var g=t.geometry();
    for(var e:g.edges()) {for(int i=0;i+1<e.wx.length;i++)maxSeg=Math.max(maxSeg,Math.hypot(e.wx[i+1]-e.wx[i],e.wz[i+1]-e.wz[i]));
     for(int k:new int[]{0,e.wx.length-1}) {double xx=e.wx[k],zz=e.wz[k]; if(Math.hypot(xx-a.centerX(),zz-a.centerZ())>728||Math.hypot(xx-b.centerX(),zz-b.centerZ())>728)continue;
      double[] aa=DoubleFullMorphLand.mergeToEntries(a.entryOfSite(),a.weights(xx,zz)),bb=DoubleFullMorphLand.mergeToEntries(b.entryOfSite(),b.weights(xx,zz));double dw=0;for(int i=0;i<aa.length;i++)dw=Math.max(dw,Math.abs(aa[i]-bb[i]));
      var uv=g.warpForward(xx,zz); double f=DoubleBoundaryGeometry.potential(e.a.vx,e.a.vz,uv[0],uv[1]); boolean triple=false;
      for(var s:g.sites())if(s.key()!=e.a.key()&&s.key()!=e.b.key()&&Math.abs(DoubleBoundaryGeometry.potential(s.vx,s.vz,uv[0],uv[1])-f)<1e-7)triple=true;
      ends++;if(triple){junction++;maxJ=Math.max(maxJ,dw);}else{other++;maxO=Math.max(maxO,dw);}if(dw>maxW){maxW=dw;where=axis+":"+xx+","+zz;}
     }
    }
   }
  }
  System.out.printf(Locale.ROOT,"endpoints=%d probableTriple=%d other=%d maxW=%.17g tripleW=%.17g otherW=%.17g maxSegment=%.17g where=%s%n",ends,junction,other,maxW,maxJ,maxO,maxSeg,where);
 }
}
