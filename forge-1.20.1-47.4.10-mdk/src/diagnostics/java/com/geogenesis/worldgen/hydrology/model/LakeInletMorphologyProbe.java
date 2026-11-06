package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;
import java.util.List;

/** Analytic geometry contracts; exceptions deliberately carry nonzero process status. */
public final class LakeInletMorphologyProbe {
    private LakeInletMorphologyProbe() { }
    private static void check(boolean b, String label) {
        if (!b) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        int nx=65,nz=25;
        double[] p=new double[nx*nz];
        boolean[] wet=new boolean[p.length];
        for(int x=0;x<nx;x++) for(int z=0;z<nz;z++) {
            int k=x*nz+z;
            p[k]=10-x*0.08;
            wet[k]=x>0 && x<nx-1 && z>0 && z<nz-1;
        }
        var d=new LakeInletMorphology.Domain(0,0,nx,nz,wet,p);
        var in=new LakeInletMorphology.Inlet(1,3,12,2,8,0);
        var a=LakeInletMorphology.solve(d,List.of(in));
        var b=LakeInletMorphology.solve(d,List.of(in));
        check(a.corridors().get(0).status().equals("SOLVED_GEOMETRIC"),"analytic section found");
        var points=a.corridors().get(0).points();
        var end=points.get(points.size()-1);
        check(Math.abs(end.p()-end.bed())<1e-9,"natural receiving value");
        check(a.cut(3,12)>1,"nonidentity inlet cut");
        for(int x=-2;x<nx+2;x++) for(int z=-2;z<nz+2;z++) {
            double cut=a.cut(x,z);
            check(cut>=0 && Double.isFinite(cut),"finite below P");
            check(Double.doubleToLongBits(cut)==Double.doubleToLongBits(b.cut(x,z)),"recompute determinism");
            if(x<=0 || z<=0 || x>=nx-1 || z>=nz-1) check(cut==0,"external unchanged");
        }
        for(int x=2;x<nx-2;x++) {
            double h=1e-5,z=12.37;
            double left=(a.interpolatedCut(x,z)-a.interpolatedCut(x-h,z))/h;
            double right=(a.interpolatedCut(x+h,z)-a.interpolatedCut(x,z))/h;
            check(Math.abs(left-right)<1e-5,"tensor first slope continuity");
            check(a.interpolatedCut(x,12)==a.cut(x,12),"tensor node identity");
        }
        var union=LakeInletMorphology.solve(d,List.of(in,new LakeInletMorphology.Inlet(2,4,13,2,8,0)));
        for(var corridor:union.corridors()) if(!corridor.points().isEmpty()) {
            var receiver=corridor.points().get(corridor.points().size()-1);
            check(union.cut((int)receiver.x(),(int)receiver.z())==0,"other corridor cannot invade hard receiver");
        }
        boolean[] blocked=wet.clone();
        for(int z=0;z<nz;z++) blocked[12*nz+z]=false;
        var island=LakeInletMorphology.solve(new LakeInletMorphology.Domain(0,0,nx,nz,blocked,p),List.of(in));
        check(island.corridors().get(0).status().equals("NO_NATURAL_SECTION"),"cannot cross shore/island");
        double[] flat=p.clone(); Arrays.fill(flat,10);
        check(LakeInletMorphology.solve(new LakeInletMorphology.Domain(0,0,nx,nz,wet,flat),List.of(in))
                .corridors().get(0).status().equals("NO_NATURAL_SECTION"),"no arbitrary endpoint fallback");
        double[] actual=new double[p.length];
        for(int x=0;x<nx;x++) for(int z=0;z<nz;z++) actual[x*nz+z]=p[x*nz+z]-a.cut(x,z);
        if(args.length>0) {
            switch(args[0]) {
                case "identity" -> actual=p.clone();
                case "external", "shore" -> actual[12*nz+12]=p[12*nz+12]-2;
                case "step" -> actual[(int)end.x()*nz+(int)end.z()]-=2;
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
        check(actual[3*nz+12]<p[3*nz+12]-1,"actual nonidentity inlet cut");
        check(Math.abs(actual[(int)end.x()*nz+(int)end.z()]-end.p())<1e-9,"actual receiving step");
        if(args.length>0 && (args[0].equals("shore") || args[0].equals("external")))
            check(actual[12*nz+12]==p[12*nz+12],"actual protected shore/external modification");
        System.out.println("PASS analytic receiving/belowP/shore/recompute/nonidentity contracts");
    }
}
