package com.geogenesis.worldgen.terrain.candidate;
import java.lang.reflect.*;
import java.util.*;
/**
 * Isolated copy of the owning DoubleTree, extended ONLY inside this directory.
 * Added: Terrace and Erosion (same formulas in double coordinates), DualNode (explicit double pair),
 * and DirectionWarped through them. Anything else still fails closed.
 */
final class DoubleTreeFixed {
 static final PerlinScalarCache PS=new PerlinScalarCache();
 static Object f(Object o,String name) { try { Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch(ReflectiveOperationException e){throw new IllegalStateException(e);} }
 static double num(Object o,String n){return ((Number)f(o,n)).doubleValue();}
 static int integer(Object o,String n){return ((Number)f(o,n)).intValue();}
 static double clamp(double x,double a,double b){return x<a?a:x>b?b:x;}
 static double map(double x,double a,double b){double v=clamp(x,a,b)-a, r=b-a;return v>=r?1:v/r;}
 static double lerp(double a,double b,double t){return a+(b-a)*t;}
 static double herm(double x){return x*x*(3-2*x);}
 static double eval(RefNoise.Node n,double x,double z) {
  if(!Double.isFinite(x)||!Double.isFinite(z))throw new IllegalArgumentException("nonfinite coordinate");
  if(n instanceof RecipeMorphCatalog.ScalarIdentity) return x;
  if(n instanceof RefNoise.Perlin a) return PS.get(a).compute(x,z);
  if(n instanceof RefNoise.PerlinRidge || n instanceof RefNoise.Billow) return ridge(n,x,z);
  if(n instanceof RefNoise.Cubic) return cubic(n,x,z);
  if(n instanceof RefNoise.WorleyEdge || n instanceof RefNoise.WorleyEdgeAdd) return worley(n,x,z);
  if(n instanceof RefNoise.Constant a)return a.value();
  if(n instanceof RefNoise.Add a)return eval(a.a(),x,z)+eval(a.b(),x,z);
  if(n instanceof RefNoise.Mul a)return eval(a.a(),x,z)*eval(a.b(),x,z);
  if(n instanceof RefNoise.AlphaMix a)return eval(a.input(),x,z)*a.alpha()+(1f-a.alpha());
  if(n instanceof RefNoise.Warped a)return eval(a.input(),x+(eval(a.wx(),x,z)-.5)*a.pow(),z+(eval(a.wz(),x,z)-.5)*a.pow());
  if(n instanceof RefNoise.Clamped a)return clamp(eval(a.input(),x,z),a.min(),a.max());
  if(n instanceof RefNoise.Mapped a)return a.from()+(eval(a.alpha(),x,z)-a.lo())/(a.hi()-a.lo())*(a.to()-a.from());
  if(n instanceof RefNoise.Mix a){double t=clamp(eval(a.alpha(),x,z),0,1);return eval(a.a(),x,z)*(1-t)+eval(a.b(),x,z)*t;}
  if(n instanceof RefNoise.Curve3 a)return herm(map(eval(a.input(),x,z),0,1));
  if(n instanceof RefNoise.Power a)return Math.pow(eval(a.input(),x,z),a.power());
  if(n instanceof RefNoise.PowerCurve a){double v=eval(a.input(),x,z), out=v>=a.mid()?a.mid()+Math.pow(v-a.mid(),a.power()):a.mid()-Math.pow(a.mid()-v,a.power());return map(out,a.min(),a.max());}
  if(n instanceof RefNoise.Inverted a)return a.max()-clamp(eval(a.input(),x,z),a.min(),a.max());
  if(n instanceof RefNoise.Smoothstep a)return herm(clamp((eval(a.input(),x,z)-a.edge0())/(a.edge1()-a.edge0()),0,1));
  if(n instanceof RefNoise.Blend a){double c=a.alphaMin()+(a.alphaMax()-a.alphaMin())*a.mid(),lo=Math.max(a.alphaMin(),c-a.range()/2),hi=Math.min(a.alphaMax(),c+a.range()/2),v=eval(a.alpha(),x,z);if(v<lo)return eval(a.lower(),x,z);if(v>hi)return eval(a.upper(),x,z);return lerp(eval(a.lower(),x,z),eval(a.upper(),x,z),(v-lo)/(hi-lo));}
  if(n instanceof RefNoise.Steps a){double v=eval(a.input(),x,z),r=a.slopeMax()-a.slopeMin();if(r<=0)return (int)(v*a.steps())/(double)a.steps();v=1-v;double base=(int)(v*a.steps())/(double)a.steps(),t=map((v-base)*a.steps(),a.slopeMin(),a.slopeMax());return 1-lerp(base,v,a.useHermite()?herm(t):t);}
  if(n instanceof DualCoordinateCall.DualNode d) return d.computeDouble(x,z);
  if(n instanceof RefNoise.DirectionWarped a){double angle=eval(a.direction(),x,z)*(double)6.2831855f;return eval(a.input(),x+Math.sin(angle)*a.strength(),z+Math.cos(angle)*a.strength());}
  if(n instanceof RefNoise.Erosion a) return erosionEval(a,x,z);
  if(n instanceof RefNoise.Terrace a){
   int steps=Math.max(2,integer(a,"steps"));
   double v=clamp(eval(a.input(),x,z),0,0.999999);
   double inMin=num(a,"inMin"),inMax=num(a,"inMax");
   double spacing=(inMax-inMin)/(steps-1);
   int index=a.indexBySpacing()
     ?(int)Math.min(steps-1,Math.floor((v-inMin)/spacing))
     :(int)Math.min(steps-1,Math.floor(v*steps));
   double value=index*spacing;
   if(index==steps-1)return value;
   double blendRange=num(a,"blendRange"),ramp=num(a,"ramp"),cliff=num(a,"cliff"),rampHeight=num(a,"rampHeight");
   double bound=(spacing-spacing*blendRange)/2, lower=value-bound, upper=value+bound;
   if(v<lower)return value;
   if(v>upper)return value+spacing;
   double rampT=1-ramp*0.5, cliffT=1-cliff*0.5, alpha=(v-lower)/(upper-lower), out=value;
   if(alpha>rampT){double rampAlpha=(alpha-rampT)/(1-rampT);out+=spacing*rampAlpha*rampHeight;}
   if(alpha>cliffT){double cliffAlpha=(alpha-cliffT)/(1-cliffT);out=lerp(out,value+spacing,cliffAlpha);}
   return out;
  }
  throw new UnsupportedOperationException("unsupported node: "+(n==null?"null":n.getClass().getName()));
 }
 /** Erosion in double coordinates: same formula and traversal order as the owning class. */
 static double erosionEval(RefNoise.Erosion n,double x,double z){
  double strength=num(n,"strength");
  double value=eval((RefNoise.Node)f(n,"input"),x,z);
  double erosion=erosionValue(n,x,z,new double[25]);
  return lerp(erosion,value,1-strength);
 }
 static double erosionValue(Object n,double x,double y,double[] scratch){
  int oct=integer(n,"octaves"),seed=integer(n,"seed");
  double grid=num(n,"gridSize"),amp=num(n,"amplitude"),fall=num(n,"distanceFallOff"),lac=num(n,"lacunarity");
  double sum=0,max=0,gain=1,distance=grid;
  for(int i=0;i<oct;i++){
   sum+=singleErosion(n,seed,x,y,distance,scratch)*gain;
   max+=gain; gain*=amp; distance*=fall; x*=lac; y*=lac;
  }
  return sum/max;
 }
 static double singleErosion(Object n,int seed,double x,double y,double grid,double[] scratch){
  Arrays.fill(scratch,Double.NaN);
  int pix=(int)Math.floor(x/grid), piy=(int)Math.floor(y/grid);
  double minDist2=Double.MAX_VALUE;
  for(int dy1=-1;dy1<=1;dy1++)for(int dx1=-1;dx1<=1;dx1++){
   int pax=pix+dx1,pay=piy+dy1;
   float[] v1=RegionCellTable.CELL[RefNoise.hash2D(seed,pax,pay)&255];
   double ax=(pax+(double)v1[0])*grid, ay=(pay+(double)v1[1])*grid;
   double bx=ax,by=ay,lowest=Double.MAX_VALUE;
   for(int dy2=-1;dy2<=1;dy2++)for(int dx2=-1;dx2<=1;dx2++){
    int pbx=pax+dx2,pby=pay+dy2;
    float[] v2=(pbx==pax&&pby==pay)?v1:RegionCellTable.CELL[RefNoise.hash2D(seed,pbx,pby)&255];
    double cx=(pbx+(double)v2[0])*grid, cy=(pby+(double)v2[1])*grid;
    double h=cachedHeight(n,dx1+dx2,dy1+dy2,cx,cy,scratch);
    if(h<lowest){lowest=h;bx=cx;by=cy;}
   }
   double d2=segmentDistance2(x,y,ax,ay,bx,by);
   if(d2<minDist2)minDist2=d2;
  }
  return clamp(Math.sqrt(minDist2)/grid,0,1);
 }
 static double cachedHeight(Object n,int dx,int dy,double px,double py,double[] scratch){
  int index=(dy+2)*5+(dx+2);
  double v=scratch[index];
  if(Double.isNaN(v)){v=eval((RefNoise.Node)f(n,"input"),px,py);scratch[index]=v;}
  return v;
 }
 static double segmentDistance2(double px,double py,double ax,double ay,double bx,double by){
  double padx=px-ax,pady=py-ay,badx=bx-ax,bady=by-ay;
  double paba=padx*badx+pady*bady,baba=badx*badx+bady*bady;
  double h=(paba==0&&baba==0)?0:clamp(paba/baba,0,1);
  double dx=badx*h-padx,dy=bady*h-pady;
  return dx*dx+dy*dy;
 }
 static int lattice(double v,int margin){if(!Double.isFinite(v)||v<=Integer.MIN_VALUE+margin||v>=Integer.MAX_VALUE-margin)throw new IllegalArgumentException("noise lattice outside int range");return (int)Math.floor(v);}
 static double sample(int seed,double x,double z){lattice(x,2);lattice(z,2);return GeometryTransform.sampleValueAndGradient(seed,x,z)[0];}
 static double ridge(Object n,double x,double z){int seed=integer(n,"seed"),oct=integer(n,"octaves");double freq=num(n,"frequency"),lac=num(n,"lacunarity"),gain=num(n,"gain"),max=num(n,"max"),amp=2,value=0,weight=1;float[] spectral=(float[])f(n,"spectral");x*=freq;z*=freq;for(int i=0;i<oct;i++){double s=1-Math.abs(sample(seed+i,x,z));s*=s;s*=weight;weight=clamp(s*amp,0,1);value+=s*spectral[i];x*=lac;z*=lac;amp*=gain;}double result=map(value,0,max);return n instanceof RefNoise.Billow?1-result:result;}
 static double cubic(Object n,double x,double z){int seed=integer(n,"seed"),oct=integer(n,"octaves");double lac=num(n,"lacunarity"),gain=num(n,"gain");x*=num(n,"frequency");z*=num(n,"frequency");double sum=cubicSample(seed,x,z),amp=1;for(int i=1;i<oct;i++){x*=lac;z*=lac;amp*=gain;sum+=cubicSample(seed+i,x,z)*amp;}return map(sum,num(n,"min"),num(n,"max"));}
 static double cubicSample(int seed,double x,double z){int ix=lattice(x,3),iz=lattice(z,3);double[] row=new double[4];for(int j=-1;j<=2;j++)row[j+1]=cub(val(seed,ix-1,iz+j),val(seed,ix,iz+j),val(seed,ix+1,iz+j),val(seed,ix+2,iz+j),x-ix);return cub(row[0],row[1],row[2],row[3],z-iz)*(double)0.44444445f;}
 static double cub(double a,double b,double c,double d,double t){double p=d-c-(a-b);return t*t*t*p+t*t*(a-b-p)+t*(c-a)+b;}
 static double val(int seed,int x,int z){int n=seed^(1619*x)^(31337*z);return (double)(n*n*n*60493)/2147483648.0;}
 static double worley(Object n,double x,double z){int seed=integer(n,"seed");x*=num(n,"frequency");z*=num(n,"frequency");int ix=lattice(x,2),iz=lattice(z,2);double d1=Double.MAX_VALUE,d2=d1;for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){int cx=ix+i,cz=iz+j;float[] v=RegionCellTable.CELL[RefNoise.hash2D(seed,cx,cz)&255];double dx=cx+(double)v[0]-x,dz=cz+(double)v[1]-z,d=Math.sqrt(dx*dx+dz*dz);if(d<d1){d2=d1;d1=d;}else if(d<d2)d2=d;}if(n instanceof RefNoise.WorleyEdge)return map(d2-1,-1,1);return map(d2+Math.max(1e-6f,d1)-1,-1,1.6f);}
}
