package com.geogenesis.worldgen.hydrology.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 隔离协议：完整的声明输入集冻结后才能读；不以缓存先到者决定世界下游。 */
final class TraceSharedStateContract {
    record Identity(long seed, String policy) {
        Identity { if (policy == null || policy.isBlank()) throw new IllegalArgumentException("policy required"); }
    }
    record ReachId(Identity world, long sourceX, long sourceZ) implements Comparable<ReachId> {
        public int compareTo(ReachId other) {
            int c = Long.compare(world.seed, other.world.seed);
            if (c == 0) c = world.policy.compareTo(other.world.policy);
            if (c == 0) c = Long.compare(sourceX, other.sourceX);
            return c == 0 ? Long.compare(sourceZ, other.sourceZ) : c;
        }
    }
    record Reach(ReachId id, List<TracePathContract.Point> path) {
        Reach {
            path = List.copyOf(path);
            if (id == null || path.size() < 2) throw new IllegalArgumentException("reach identity and path required");
        }
    }
    record Port(ReachId reach, int edge, int axis, long boundary,
                int fromX, int fromZ, int toX, int toZ, TracePhysicsContract.State state) { }
    record Join(ReachId receiver, TracePathContract.Hit hit, List<TracePathContract.Point> suffix) { }
    private final Identity identity;
    private final Map<ReachId, Reach> frozen;

    TraceSharedStateContract(Identity identity, List<Reach> completeDeclaredInput) {
        this.identity = identity;
        var map = new TreeMap<ReachId, Reach>();
        for (Reach reach : completeDeclaredInput) {
            TracePathContract.checkBudget(Integer.MAX_VALUE);
            if (!reach.id.world.equals(identity)) throw new IllegalArgumentException("world/policy mismatch");
            Reach before = map.putIfAbsent(reach.id, reach);
            if (before != null && !before.equals(reach)) throw new IllegalStateException("conflicting reach authority");
        }
        frozen = java.util.Collections.unmodifiableMap(map);
    }

    Join join(TracePathContract.Point from, TracePathContract.Point to) {
        Join best = null;
        boolean ambiguous = false;
        for (Reach reach : frozen.values()) {
            var hit = TracePathContract.firstHit(from, to, reach.path);
            if (hit == null) continue;
            if (best == null || hit.along() < best.hit.along()) {
                best = new Join(reach.id, hit, TracePathContract.suffix(hit, reach.path));
                ambiguous = false;
            } else if (hit.along() == best.hit.along() && !reach.id.equals(best.receiver)) {
                ambiguous = true;
            }
        }
        if (ambiguous) throw new IllegalStateException("ambiguous shared receiver; canonical junction required");
        return best;
    }

    Reach require(ReachId id) {
        if (!identity.equals(id.world)) throw new IllegalArgumentException("wrong policy");
        Reach result = frozen.get(id);
        if (result == null) throw new IllegalStateException("receiver input pending; not dry");
        return result;
    }

    /** 声明单个已知边界的权威交点；复制同一连续位置与速度，不在接收方格心重启。 */
    static Port port(Reach reach, int edge, int axis, long boundary, int tract,
                     TracePhysicsContract.State atCrossing) {
        if ((axis != 0 && axis != 1) || tract < 1 || boundary % tract != 0
                || edge < 0 || edge + 1 >= reach.path.size()) throw new IllegalArgumentException("invalid seam");
        var a = reach.path.get(edge); var b = reach.path.get(edge + 1);
        double av = axis == 0 ? a.x() : a.z(), bv = axis == 0 ? b.x() : b.z();
        if (av == bv) throw new IllegalArgumentException("no directed crossing");
        double t = (boundary - av) / (bv - av);
        if (!(t > 0 && t < 1)) throw new IllegalArgumentException("interior crossing required; seam vertex pending");
        var p = new TracePathContract.Point(a.x() + t * (b.x() - a.x()), a.z() + t * (b.z() - a.z()));
        if (!p.equals(atCrossing.point())) throw new IllegalArgumentException("crossing state position mismatch");
        int ax = owner(a.x(), tract), az = owner(a.z(), tract), bx = owner(b.x(), tract), bz = owner(b.z(), tract);
        if (Math.abs(ax - bx) + Math.abs(az - bz) != 1)
            throw new IllegalArgumentException("multi-boundary/corner edge must be split first");
        return new Port(reach.id, edge, axis, boundary, ax, az, bx, bz, atCrossing);
    }

    static TracePhysicsContract.State receive(Port port, ReachId expected, int ownerX, int ownerZ) {
        if (!port.reach.equals(expected) || port.toX != ownerX || port.toZ != ownerZ)
            throw new IllegalArgumentException("wrong port receiver/world/policy");
        return port.state;
    }

    private static int owner(double coordinate, int tract) { return (int) Math.floor(coordinate / tract); }
}
