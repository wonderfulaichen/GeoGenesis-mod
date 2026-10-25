package com.geogenesis.worldgen.hydrology.model;

import java.nio.file.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** Actual builder prefix replay experiment. Coverage remains relative to the supplied wire manifest. */
public final class ContextPrefixProbe {
    static final int N=65;
    static int checks;
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); checks++; }
    record Context(String world,String base,List<WireIn> prefix,WireIn target) {
        Context { prefix=List.copyOf(prefix); }
        String key() throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((world+"/"+base+"/"+prefix+"/"+target).getBytes(StandardCharsets.UTF_8)));
        }
    }
    static WireIn wire(int x,int z) { return new WireIn(x*N+z,(x+1)*N+z,1,0,x,z,x+1,z); }
    static DeterministicSkeleton.Result build(List<WireIn> wires,DeterministicSkeleton.Result base) {
        double[] bed=new double[N*N],flow=new double[N*N]; int[] parent=new int[N*N];
        boolean[] resolved=new boolean[N*N],lake=new boolean[N*N],sea=new boolean[N*N];
        Arrays.fill(parent,-1); Arrays.fill(resolved,true);
        for(int x=0;x<N;x++)for(int z=0;z<N;z++) {
            int c=x*N+z; bed[c]=200-x+0.03*(z-32)*(z-32);
            if(x+1<N)parent[c]=c+N; sea[c]=x==N-1;
        }
        return DeterministicSkeleton.build(bed,parent,resolved,lake,sea,flow,null,N,N,
                ModelHydrologyProvider.productionParams(1.0),wires.stream().map(WireIn::anchorCell).toList(),
                wires,bed,null,null,base,base==null?DeterministicSkeleton.PassRole.PRIMARY:DeterministicSkeleton.PassRole.SECOND_PASS);
    }
    static DeterministicSkeleton.Trace trace(DeterministicSkeleton.Result result,WireIn wire) {
        return result.traces.stream().filter(t->t.sourceCell()==wire.anchorCell()).findFirst().orElse(null);
    }
    static String geometry(DeterministicSkeleton.Trace trace) {
        if(trace==null)return "NO_TRACE";
        return trace.end+"/"+Arrays.toString(trace.cell)+"/"+trace.geometryPrefixNodes+"/"
                +Arrays.toString(trace.contPath)+"/"+Arrays.toString(trace.outsideTail)+"/"+trace.port;
    }
    static Context context(List<WireIn> manifest,WireIn target,String base) {
        List<WireIn> prefix=manifest.stream().filter(w->w.anchorCell()<target.anchorCell())
                .sorted(Comparator.comparingInt(WireIn::anchorCell)).toList();
        return new Context("analytic-65-step1-productionParams-v1",base,prefix,target);
    }
    static DeterministicSkeleton.Result replay(Context context) {
        List<WireIn> wires=new ArrayList<>(context.prefix); wires.add(context.target);
        return build(wires,build(List.of(),null));
    }
    static String prefixReplay() throws Exception {
        List<WireIn> all=List.of(wire(4,30),wire(4,32),wire(4,34));
        var base=build(List.of(),null); check(base.traces.isEmpty(),"fixture base has no local sources");
        var full=build(all,base); check(full.traces.size()==3,"all fixture incoming traces must survive");
        StringBuilder report=new StringBuilder();
        for(WireIn target:all) {
            Context context=context(all,target,"empty-primary"); var partial=replay(context);
            check(trace(full,target)!=null,"target trace exists, not vacuous null equality");
            check(geometry(trace(full,target)).equals(geometry(trace(partial,target))),"prefix replay preserves actual target geometry");
            List<WireIn> reversed=new ArrayList<>(all); Collections.reverse(reversed);
            check(context.key().equals(context(reversed,target,"empty-primary").key()),"context identity independent of arrival order");
            report.append("target=").append(target.anchorCell()).append(" prefix=").append(context.prefix.size())
                    .append(" nodes=").append(trace(full,target).cell.length).append(" end=").append(trace(full,target).end)
                    .append(" context=").append(context.key()).append('\n');
        }
        WireIn target=all.get(1);
        var omitted=replay(context(List.of(target),target,"empty-primary"));
        check(!geometry(trace(full,target)).equals(geometry(trace(omitted,target))),"omitting earlier incoming changes target trace");
        check(!context(all,target,"empty-primary").key().equals(context(List.of(target),target,"empty-primary").key()),
                "same target with different prefix cannot share context identity");
        check(!context(all,target,"empty-primary").key().equals(context(all,target,"different-primary").key()),
                "base context identity must participate");
        return report+"omittedEarlierChangesGeometry=true\n";
    }
    static String baseMergeBypass() {
        var sourceBase=ContextDependencyProbe.built(true,null);
        // Same self-consistent bed/sea fixture as ContextDependencyProbe, source lies on the base trace.
        int anchor=20*33+16;
        WireIn invalid=new WireIn(anchor,anchor+2*33,0,0,20,16,22,16);
        int n=33; double[] bed=new double[n*n],flow=new double[n*n]; int[] parent=new int[n*n];
        boolean[] resolved=new boolean[n*n],lake=new boolean[n*n],sea=new boolean[n*n];
        Arrays.fill(parent,-1); Arrays.fill(resolved,true);
        for(int x=0;x<n;x++)for(int z=0;z<n;z++) {
            int c=x*n+z; bed[c]=100-x+3.0*(z-16)*(z-16); sea[c]=x==32; if(x<32)parent[c]=c+n;
        }
        var result=DeterministicSkeleton.build(bed,parent,resolved,lake,sea,flow,null,n,n,
                ModelHydrologyProvider.productionParams(1),List.of(anchor),List.of(invalid),bed,null,null,
                sourceBase,DeterministicSkeleton.PassRole.SECOND_PASS);
        check(result.mergedIncoming==1 && result.wireRejects.isEmpty(),"baseline base merge bypass reproduces malformed wire acceptance");
        check(result.mergeEvidence.stream().anyMatch(e->e.resolved()),"base merge has local successor evidence despite invalid wire");
        return "REPRODUCED malformedWireBaseMerge=true validationBypass=true NOT_REPAIRED\n";
    }
    public static void main(String[] args) throws Exception {
        String summary=prefixReplay()+baseMergeBypass()+"PREFIX COMPONENT PASS checks="+checks+"\n"
                +"Scope: actual builder with supplied complete synthetic manifest; no independent incoming enumeration.\n"
                +"NOT CERTIFIED: arbitrary worlds, global prefix completeness, publication/shape equivalence, spill history or wet columns.\n";
        Files.writeString(Path.of(args[0]),summary); System.out.print(summary);
    }
}
