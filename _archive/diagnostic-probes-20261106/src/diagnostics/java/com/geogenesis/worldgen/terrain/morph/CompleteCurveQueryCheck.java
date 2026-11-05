package com.geogenesis.worldgen.terrain.morph;

import java.util.stream.IntStream;

/** Complete support vs larger unpruned oracle, frozen old geometry and exact left/right limits. */
final class CompleteCurveQueryCheck {
    private static int assertions;
    private static void require(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
    private static long bits(double v){return Double.doubleToRawLongBits(v);}
    private static void same(double a,double b,String s){require(bits(a)==bits(b),s+" "+a+" != "+b);}

    public static void main(String[] args)throws Exception {
        try {
            realSeams();oracleAndReplays();
            System.out.println("PASS CompleteCurveQueryCheck assertions="+assertions);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }

    private static void realSeams()throws Exception {
        var old=new CurvedSegmentErosion(CurveWindowEvidence.production(),.15);
        var fixed=new CompleteCurveQuery(old);
        double maxOld=0,maxNew=0,maxSmall=0;
        for(var s:CurveWindowEvidence.scan(old)) {
            double left=fixed.forced(s.octave(),s.cx()-(s.axis()==0?1:0),s.cz()-(s.axis()==1?1:0),s.x(),s.z(),true);
            double right=fixed.forced(s.octave(),s.cx(),s.cz(),s.x(),s.z(),true);
            same(left,right,"same coordinate different source-window anchor");
            same(left,s.oracle().value(),"wide oracle at reproduced seam");
            maxOld=Math.max(maxOld,Math.abs(s.left().value()-s.right().value()));
            maxNew=Math.max(maxNew,Math.abs(left-right));
            double previous=Double.POSITIVE_INFINITY;
            for(double h:new double[]{1e-3,1e-4,1e-5,1e-6}) {
                double g=old.grid(s.octave()),dx=s.axis()==0?h*g:0,dz=s.axis()==1?h*g:0;
                double jump=Math.abs(fixed.single(s.octave(),s.x()-dx,s.z()-dz)
                        -fixed.single(s.octave(),s.x()+dx,s.z()+dz));
                require(jump<=previous*.2+1e-12,"two-side limit converges");previous=jump;
                if(h==1e-6)maxSmall=Math.max(maxSmall,jump);
            }
        }
        require(maxOld>.1,"real reproduction active");
        System.out.printf("REAL support mismatch before=%.12g after=%.12g near_limit=%.12g%n",maxOld,maxNew,maxSmall);
    }

    private static void oracleAndReplays() {
        for(long seed:new long[]{9139912035078620160L,12345}) {
            MorphNoise.Node raw=new MorphNoise.DualNode((x,z)->-999,
                    (x,z)->.5+.3*Math.sin(x/91)+.2*Math.cos(z/63));
            var n=new MorphNoise.Erosion(raw,(int)(seed^(seed>>>32)),2,1,128,.15f,3.1f,.8f);
            var geometry=new CurvedSegmentErosion(n,.15);
            var fixed=new CompleteCurveQuery(geometry);
            double[] expected=new double[96];
            for(int i=0;i<96;i++) {
                double x=qx(i),z=qz(i);expected[i]=fixed.eval(x,z);
                for(int o=0;o<2;o++) {
                    double g=geometry.grid(o),px=x*(o==0?1:(double)n.lacunarity()),pz=z*(o==0?1:(double)n.lacunarity());
                    int cx=(int)Math.floor(px/g),cz=(int)Math.floor(pz/g);
                    same(fixed.single(o,px,pz),fixed.forced(o,cx,cz,px,pz,false),"prune oracle");
                    same(fixed.single(o,px,pz),CurveWindowEvidence.hit(geometry,o,cx,cz,px,pz,7).value(),"radius7 oracle");
                }
            }
            var rebuilt=new CompleteCurveQuery(new CurvedSegmentErosion(n,.15));
            for(int i=95;i>=0;i--)same(expected[i],rebuilt.eval(qx(i),qz(i)),"cold reverse");
            IntStream.range(0,96).parallel().forEach(i->{
                if(bits(expected[i])!=bits(rebuilt.eval(qx(i),qz(i))))throw new AssertionError("parallel");
            });
            assertions++;
        }
        System.out.println("PASS radius7 oracle, pruning equivalence, negative cells, two seeds, rebuild/parallel");
    }
    private static double qx(int i){return (i*7919%12001-6000)*.783;}
    private static double qz(int i){return (i*4093%11003-5501)*.917;}
}
