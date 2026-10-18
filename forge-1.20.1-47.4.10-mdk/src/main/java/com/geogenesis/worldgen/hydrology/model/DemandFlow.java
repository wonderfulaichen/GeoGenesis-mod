package com.geogenesis.worldgen.hydrology.model;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Monotone lower-bound evaluation on a single-successor runoff graph. */
final class DemandFlow<K> {
    enum Kind { EXACT, AT_LEAST, UNKNOWN }
    record Amount(Kind kind, double bound, String reason) {
        boolean known() { return kind != Kind.UNKNOWN; }
        boolean exact() { return kind == Kind.EXACT; }
        boolean reaches(double demand) { return known() && bound >= demand; }
        double exactValue() { return exact() ? bound : Double.NaN; }
    }
    record Node<K>(double source, double loss, int cells, List<K> inputs) {
        Node {
            if (!Double.isFinite(source) || source < 0 || !Double.isFinite(loss) || loss < 0 || cells < 1)
                throw new IllegalArgumentException("finite nonnegative runoff node");
            inputs = List.copyOf(inputs);
            if (new HashSet<>(inputs).size() != inputs.size())throw new IllegalArgumentException("duplicate runoff input");
        }
    }
    record Result(Amount amount, int cells, int expanded, int peak) { }
    static final class Unresolved extends RuntimeException {
        Unresolved(String reason) { super(reason); }
    }
    private final Function<K, Node<K>> graph;
    private final int limit;

    DemandFlow(Function<K, Node<K>> graph, int limit) {
        if (limit < 1)throw new IllegalArgumentException("positive demand budget");
        this.graph = graph; this.limit = limit;
    }
    private static final class State<K> {
        final K key;
        Node<K> node;
        final List<State<K>> inputs = new ArrayList<>();
        double lower;
        boolean exact, enumerated;
        State(K key) { this.key = key; }
    }
    Result query(K key, double demand) {
        if (Double.isNaN(demand) || demand < 0)throw new IllegalArgumentException("nonnegative demand");
        var root = new State<K>(key);
        var states = new HashMap<K, State<K>>(); states.put(key, root);
        var queue = new ArrayDeque<State<K>>(); queue.add(root);
        int cells = 0, expanded = 0, peak = 1, checkpoint = 1;
        var order = new ArrayList<State<K>>();
        try {
            while (!queue.isEmpty()) {
                cancelled(); var s = queue.remove();
                if (cells >= limit)return bounded(order,root,demand,"demand cells budget",cells,expanded,peak);
                Node<K> node = graph.apply(s.key);
                if (node.cells() > limit - cells)return bounded(order,root,demand,"demand cells budget",cells,expanded,peak);
                cells += node.cells(); expanded++; s.node = node;order.add(s);
                for (K input : node.inputs()) {
                    if (states.containsKey(input))throw new Unresolved("cycle or shared input violates unique successor");
                    if (states.size() >= limit)
                        return bounded(order,root,demand,"demand workset budget",cells,expanded,states.size());
                    var child = new State<K>(input);states.put(input, child);s.inputs.add(child);queue.add(child);
                }
                s.enumerated=true;
                peak = Math.max(peak, states.size());
                if(expanded>=checkpoint || queue.isEmpty()) {
                    evaluate(order);checkpoint=Math.min(limit,Math.max(checkpoint+1,checkpoint*2));
                    if (root.exact || root.lower >= demand)
                        return new Result(new Amount(root.exact ? Kind.EXACT : Kind.AT_LEAST, root.lower, ""), cells, expanded, peak);
                }
            }
            throw new IllegalStateException("closed demand frontier without exact root");
        } catch (Unresolved pending) {
            return unknown(pending.getMessage(), cells, expanded, peak);
        }
    }
    private Result bounded(List<State<K>> order,State<K> root,double demand,String reason,int cells,int expanded,int peak) {
        evaluate(order);
        if(root.lower>=demand)return new Result(new Amount(Kind.AT_LEAST,root.lower,""),cells,expanded,peak);
        return unknown(reason,cells,expanded,peak);
    }
    private void evaluate(List<State<K>> order) {
        for(int i=order.size()-1;i>=0;i--) {
            cancelled();var s=order.get(i);double total=s.node.source();boolean exact=s.enumerated;
            for(var child:s.inputs){total+=child.lower;exact&=child.exact;}
            s.lower=Math.max(0,total-s.node.loss());s.exact=exact;
        }
    }
    private Result unknown(String reason, int cells, int expanded, int peak) {
        return new Result(new Amount(Kind.UNKNOWN, Double.NaN, reason), cells, expanded, peak);
    }
    private static void cancelled() {
        if (Thread.currentThread().isInterrupted())throw new CancellationException("demand runoff");
    }
}
