package com.geogenesis.worldgen.hydrology.model;

import java.nio.file.*;
import java.util.*;

/** Finite synthetic ordinary-flow interface experiment; not a production discovery certificate. */
public final class ReverseOrdinaryProbe {
    record Point(int x,int z) { }
    record State(Point point,int heading) { }
    record Evidence(Set<State> closure,Set<Point> sources,long expanded,boolean complete) { }
    static final int[][] DIR={{0,0},{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
    static int checks;
    final int origin;
    final int extent;
    final Set<Point> admitted;
    final Map<State,State> transitions=new HashMap<>();
    final Set<Point> sampled=new HashSet<>();
    long sampleCalls;

    ReverseOrdinaryProbe(int origin) { this(origin,36); }

    ReverseOrdinaryProbe(int origin,int extent) {
        this.origin=origin;
        this.extent=extent;
        admitted=Set.of(new Point(origin,0),new Point(origin+8,2));
    }

    boolean inside(Point p) { return p.x>=origin && p.x<=origin+extent && p.z>=-4 && p.z<=4; }
    double bed(Point p) { return 100-p.x+3.0*p.z*p.z; }
    static void check(boolean value,String name) {
        if(!value) throw new AssertionError(name);
        checks++;
    }

    State next(State state) {
        if(transitions.containsKey(state)) return transitions.get(state);
        int span=Math.max(1,DeterministicSkeleton.GRADIENT_SPAN), n=2*span+1;
        double[] bed=new double[n*n];
        for(int i=0;i<n;i++) for(int j=0;j<n;j++) {
            Point sample=new Point(state.point.x+i-span,state.point.z+j-span);
            sampleCalls++; sampled.add(sample); bed[i*n+j]=bed(sample);
        }
        int[] d=DIR[state.heading]; double length=Math.hypot(d[0],d[1]);
        var result=DeterministicSkeleton.vectorTraceNext(span*n+span,bed,n,n,
                length==0?0:d[0]/length,length==0?0:d[1]/length,span);
        State to=null;
        if(result.cell()>=0) {
            int dx=result.cell()/n-span,dz=result.cell()%n-span, heading=-1;
            for(int h=1;h<DIR.length;h++) if(DIR[h][0]==dx && DIR[h][1]==dz) heading=h;
            check(heading>0,"actual vector result must be D8 adjacent");
            to=new State(new Point(state.point.x+dx,state.point.z+dz),heading);
        }
        transitions.put(state,to);
        return to;
    }

    Evidence discover(Point target,int budget,boolean clipToLocalRegion) {
        Set<State> seen=new HashSet<>(); ArrayDeque<State> queue=new ArrayDeque<>();
        Set<Point> found=new HashSet<>();
        for(int h=0;h<DIR.length;h++) { var s=new State(target,h); seen.add(s); queue.add(s); }
        long work=0;
        while(!queue.isEmpty()) {
            if(work==budget) return new Evidence(Set.copyOf(seen),Set.copyOf(found),work,false);
            State current=queue.removeFirst(); work++;
            if(current.heading==0) {
                if(admitted.contains(current.point)) found.add(current.point);
                continue;
            }
            int[] d=DIR[current.heading];
            Point prior=new Point(current.point.x-d[0],current.point.z-d[1]);
            // inside() is an explicitly finite fixture universe, NOT a production radius.
            if(!inside(prior)) continue;
            if(clipToLocalRegion && Math.floorDiv(prior.x-origin,8)!=Math.floorDiv(target.x-origin,8)) continue;
            for(int h=0;h<DIR.length;h++) {
                State candidate=new State(prior,h);
                if(current.equals(next(candidate)) && seen.add(candidate)) queue.addLast(candidate);
            }
        }
        return new Evidence(Set.copyOf(seen),Set.copyOf(found),work,true);
    }

    Set<Point> forwardOracle(Point target) {
        Set<Point> expected=new HashSet<>();
        for(Point source:admitted) {
            State s=new State(source,0); Set<State> seen=new HashSet<>();
            while(s!=null && inside(s.point) && seen.add(s)) {
                if(s.point.equals(target)) { expected.add(source); break; }
                State to=next(s);
                check(to==null || bed(to.point)<bed(s.point),"fixture ordinary flow must strictly descend");
                s=to;
            }
        }
        return expected;
    }

    static String run(int origin) {
        var probe=new ReverseOrdinaryProbe(origin);
        Point downstream=new Point(origin+28,0), middle=new Point(origin+20,0);
        var tail=probe.discover(downstream,4096,false);
        long tailCalls=probe.sampleCalls;
        int tailUnique=probe.sampled.size(),tailTransitions=probe.transitions.size();
        var mid=probe.discover(middle,4096,false);
        check(tail.complete && mid.complete,"fixture reverse dependency closure must finish within 4096");
        check(!tail.sources.isEmpty(),"downstream-only must discover remote admitted source");
        check(tail.sources.equals(probe.forwardOracle(downstream)),"reverse downstream sources equal exhaustive forward oracle");
        check(mid.sources.equals(probe.forwardOracle(middle)),"reverse middle sources equal exhaustive forward oracle");
        var clipped=probe.discover(downstream,4096,true);
        check(!clipped.sources.equals(tail.sources),"negative control local-only discovery loses remote sources");
        var exhausted=probe.discover(downstream,1,false);
        check(!exhausted.complete && exhausted.expanded==1,"budget exhaustion cannot certify absence");
        var fresh=new ReverseOrdinaryProbe(origin);
        var reverseMid=fresh.discover(middle,4096,false);
        var reverseTail=fresh.discover(downstream,4096,false);
        check(reverseMid.equals(mid) && reverseTail.equals(tail),"cold inverse query order identical closure and logical work");
        return "origin="+origin+" tailSources="+tail.sources.size()+" tailWork="+tail.expanded
                +" midSources="+mid.sources.size()+" midWork="+mid.expanded+" clippedSources="+clipped.sources.size()
                +" tailTransitionChecks="+tailTransitions+" tailSampleCalls="+tailCalls+" tailUniqueSamples="+tailUnique+"\n";
    }

    static String longDependency() {
        var probe=new ReverseOrdinaryProbe(-128,128);
        var evidence=probe.discover(new Point(-16,0),4096,false);
        check(!evidence.complete && evidence.expanded==4096,
                "long ordinary dependency must remain incomplete without resetting budget");
        return "longDependency complete="+evidence.complete+" expanded="+evidence.expanded
                +" transitions="+probe.transitions.size()+" uniqueSamples="+probe.sampled.size()+"\n";
    }

    public static void main(String[] args) throws Exception {
        String summary=run(0)+run(-40)+longDependency();
        summary+="COMPONENT PASS checks="+checks+"\nScope: finite synthetic universe; actual vectorTraceNext and discrete incoming heading.\n"
                +"NOT CERTIFIED: production admitted-source enumeration, spill/JOIN/crossing/spacing guards, arbitrary target chunks, geometry or wet columns.\n";
        Files.writeString(Path.of(args[0]),summary);
        System.out.print(summary);
    }
}
