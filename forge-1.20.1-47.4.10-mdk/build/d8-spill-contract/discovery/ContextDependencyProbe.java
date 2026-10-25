package com.geogenesis.worldgen.hydrology.model;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Counterexamples for reducing production continuation to bed + heading only. */
public final class ContextDependencyProbe {
    static final int N=9,K=4*N+4,RECEIVER=6*N+4;
    static int checks;
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }

    static String join() throws Exception {
        double[] bed=new double[N*N];
        for(int x=0;x<N;x++) for(int z=0;z<N;z++) bed[x*N+z]=100-x+3.0*(z-4)*(z-4);
        var method=DeterministicSkeleton.class.getDeclaredMethod("nearbyJoin",double[].class,int.class,
                int.class,int.class,boolean[].class,int.class,int[].class,int.class,List.class,double[].class);
        method.setAccessible(true);
        boolean[] covered=new boolean[N*N]; int[] stamps=new int[N*N];
        int ordinary=DeterministicSkeleton.vectorTraceNext(K,bed,N,N,1,0,DeterministicSkeleton.GRADIENT_SPAN).cell();
        int empty=(int)method.invoke(null,bed,K,N,N,covered,2,stamps,1,List.of(),bed);
        covered[RECEIVER]=true;
        int occupied=(int)method.invoke(null,bed,K,N,N,covered,2,stamps,1,List.of(),bed);
        List<int[]> crossing=Collections.singletonList(new int[]{5,3,5,5});
        int blocked=(int)method.invoke(null,bed,K,N,N,covered,2,stamps,1,crossing,bed);
        check(ordinary==5*N+4,"fixture actual ordinary next");
        check(empty==-1 && occupied==RECEIVER && blocked==-1,"same bed/heading: covered and crossing change actual JOIN predicate");
        check(occupied!=ordinary,"JOIN destination differs from ordinary successor");
        return "sameBedHeading ordinary="+ordinary+" emptyJoin="+empty+" coveredJoin="+occupied+" crossingJoin="+blocked+"\n";
    }

    @SuppressWarnings("unchecked")
    static String admission() throws Exception {
        double[] level=new double[N*N],flow=new double[N*N]; int[] parent=new int[N*N];
        boolean[] resolved=new boolean[N*N],lake=new boolean[N*N],sea=new boolean[N*N];
        Arrays.fill(parent,-1); Arrays.fill(resolved,true);
        for(int x=0;x<N;x++) for(int z=0;z<N;z++) {
            level[x*N+z]=100-x+3.0*(z-4)*(z-4);
            if(x+1<N)parent[x*N+z]=(x+1)*N+z;
        }
        var params=ModelHydrologyProvider.productionParams(1.0);
        Method method=DeterministicSkeleton.class.getDeclaredMethod("collectSources",double[].class,int[].class,
                boolean[].class,boolean[].class,boolean[].class,double[].class,int.class,int.class,DeterministicSkeleton.Params.class);
        method.setAccessible(true);
        List<Integer> dry=(List<Integer>)method.invoke(null,level,parent,resolved,lake,sea,flow,N,N,params);
        flow[K]=params.minSourceAccum()+1;
        List<Integer> admitted=(List<Integer>)method.invoke(null,level,parent,resolved,lake,sea,flow,N,N,params);
        check(dry.isEmpty() && admitted.equals(List.of(K)),"same bed/parent requires actual discharge to determine source admission");
        return "sameBedParent sourcesBelowThreshold="+dry+" sourcesAboveThreshold="+admitted+" threshold="+params.minSourceAccum()+"\n";
    }

    static DeterministicSkeleton.Result built(boolean admit,DeterministicSkeleton.Result base) {
        int n=33; double[] bed=new double[n*n],flow=new double[n*n];
        int[] parent=new int[n*n]; boolean[] resolved=new boolean[n*n],lake=new boolean[n*n],sea=new boolean[n*n];
        Arrays.fill(parent,-1); Arrays.fill(resolved,true);
        for(int x=0;x<n;x++) for(int z=0;z<n;z++) {
            int c=x*n+z; bed[c]=100-x+3.0*(z-16)*(z-16);
            sea[c]=x==32; if(x<32)parent[c]=c+n;
        }
        var params=ModelHydrologyProvider.productionParams(1.0);
        if(admit)flow[18*n+16]=params.minSourceAccum()+1;
        var wires=base==null?List.<WireIn>of():List.of(new WireIn(4*n+16,5*n+16,1,0,4,16,5,16));
        return DeterministicSkeleton.build(bed,parent,resolved,lake,sea,flow,null,n,n,params,
                wires.stream().map(WireIn::anchorCell).toList(),wires,bed,null,null,base,
                base==null?DeterministicSkeleton.PassRole.PRIMARY:DeterministicSkeleton.PassRole.SECOND_PASS);
    }

    static int successor(DeterministicSkeleton.Result result,int cell) {
        for(var trace:result.traces) for(int i=1;i<trace.cell.length;i++)
            if(trace.cell[i-1]==cell)return trace.cell[i];
        return -1;
    }

    static String buildContext() {
        var empty=built(false,null); var occupied=built(true,null);
        check(empty.traces.isEmpty() && occupied.traces.size()==1,"actual PRIMARY must construct differing covered context");
        var first=built(true,empty); var second=built(true,occupied);
        int cell=16*33+16;
        check(successor(first,cell)==17*33+16 && successor(second,cell)==18*33+16,
                "actual SECOND_PASS same incoming/bed has context-dependent shared-cell successor");
        return "actualBuild sameIncoming successorWithoutBase="+successor(first,cell)
                +" successorWithBase="+successor(second,cell)+"\n";
    }

    static Set<Integer> reverseJoin(int target,int n,int radius,double[] level,boolean[] covered,
                                    int[] stamps,List<int[]> segments) throws Exception {
        Method method=DeterministicSkeleton.class.getDeclaredMethod("nearbyJoin",double[].class,int.class,
                int.class,int.class,boolean[].class,int.class,int[].class,int.class,List.class,double[].class);
        method.setAccessible(true); Set<Integer> result=new TreeSet<>();
        int tx=target/n,tz=target%n;
        for(int x=Math.max(0,tx-radius);x<=Math.min(n-1,tx+radius);x++)
            for(int z=Math.max(0,tz-radius);z<=Math.min(n-1,tz+radius);z++) {
                int candidate=x*n+z;
                if((int)method.invoke(null,level,candidate,n,n,covered,2,stamps,1,segments,level)==target)
                    result.add(candidate);
            }
        return result;
    }

    static String reverseJoinCoverage() throws Exception {
        int n=33,target=18*n+16,current=16*n+16;
        double[] level=new double[n*n]; boolean[] covered=new boolean[n*n]; int[] stamps=new int[n*n];
        for(int x=0;x<n;x++)for(int z=0;z<n;z++)level[x*n+z]=100-x+3.0*(z-16)*(z-16);
        covered[target]=true;
        Set<Integer> full=reverseJoin(target,n,2,level,covered,stamps,List.of());
        Set<Integer> oracle=new TreeSet<>();
        Method join=DeterministicSkeleton.class.getDeclaredMethod("nearbyJoin",double[].class,int.class,
                int.class,int.class,boolean[].class,int.class,int[].class,int.class,List.class,double[].class);
        join.setAccessible(true);
        for(int k=0;k<n*n;k++)if((int)join.invoke(null,level,k,n,n,covered,2,stamps,1,List.of(),level)==target)oracle.add(k);
        check(full.equals(oracle),"radius two JOIN reverse candidates equal exhaustive all-cell oracle");
        check(full.contains(current),"reverse JOIN includes actual SECOND_PASS two-cell edge");
        check(!reverseJoin(target,n,1,level,covered,stamps,List.of()).contains(current),
                "D8-only reverse search misses actual JOIN predecessor");
        List<int[]> cross=Collections.singletonList(new int[]{17,15,17,17});
        check(!reverseJoin(target,n,2,level,covered,stamps,cross).contains(current),"reverse JOIN respects crossing history");
        stamps[target]=1;
        check(reverseJoin(target,n,2,level,covered,stamps,List.of()).isEmpty(),"reverse JOIN excludes own trace receiver");
        return "reverseJoin fullCandidates="+full.size()+" actualPredecessor="+current+" target="+target
                +" ordinaryRadiusMiss=true crossedRejected=true selfRejected=true\n";
    }

    public static void main(String[] args) throws Exception {
        String evidence=join()+admission()+buildContext()+reverseJoinCoverage()+"CONTEXT COUNTEREXAMPLES PASS checks="+checks
                +"\nNot a river-network repair or world-discovery certificate.\n";
        Files.writeString(Path.of(args[0]),evidence); System.out.print(evidence);
    }
}
