package com.geogenesis.worldgen.hydrology.model;

/** 隔离候选：wu、秒；a=-G∇bed-c|v|v。没有流量权重、噪声或允许越坡的默认许可。 */
final class TracePhysicsContract {
    record State(TracePathContract.Point point, double vx, double vz) {
        State {
            if (point == null || !Double.isFinite(vx) || !Double.isFinite(vz))
                throw new IllegalArgumentException("invalid state");
        }
    }
    record Params(double gravity, double drag, double dt, double maxDistance, int retries) {
        Params {
            if (!(gravity > 0) || !(drag >= 0) || !(dt > 0) || !(maxDistance > 0)
                    || !Double.isFinite(gravity + drag + dt + maxDistance) || retries < 1 || retries > 32)
                throw new IllegalArgumentException("invalid physical parameters");
        }
    }
    enum Status { MOVE, STALLED_OR_BASIN_PENDING, UNKNOWN_LIMIT }
    record Result(Status status, State state, double headBefore, double headAfter,
                  double frictionHead, java.util.List<TracePathContract.Visit> visits) { }

    static Result step(TraceBedField field, State in, Params params, int visitBudget) {
        if (visitBudget < 1) throw new IllegalArgumentException("invalid visit budget");
        var start = field.at(in.point.x(), in.point.z());
        double speed = Math.hypot(in.vx, in.vz);
        double head = start.height() + speed * speed / (2 * params.gravity);
        double dt = params.dt;
        for (int i = 0; i < params.retries; i++, dt *= 0.5) {
            TracePathContract.checkBudget(Integer.MAX_VALUE);
            double vx = in.vx - params.gravity * start.dx() * dt;
            double vz = in.vz - params.gravity * start.dz() * dt;
            double v = Math.hypot(vx, vz);
            if (v == 0) return stopped(Status.STALLED_OR_BASIN_PENDING, in, head);
            double distance = Math.min(v * dt, params.maxDistance);
            var point = new TracePathContract.Point(in.point.x() + vx / v * distance,
                    in.point.z() + vz / v * distance);
            double height = field.at(point.x(), point.z()).height();
            // 本批禁止端点反坡；不声称这已认证整段真实地形/湖岸。
            if (height > start.height()) continue;
            double loss = params.drag * speed * speed * distance / params.gravity;
            double kineticHead = head - loss - height;
            if (!(kineticHead >= 0)) continue;
            double nextSpeed = Math.sqrt(2 * params.gravity * kineticHead);
            var next = new State(point, vx / v * nextSpeed, vz / v * nextSpeed);
            var visits = TracePathContract.walk(in.point, point, field.step(), visitBudget);
            return new Result(Status.MOVE, next, head, height + nextSpeed * nextSpeed / (2 * params.gravity),
                    loss, visits);
        }
        return stopped(Status.UNKNOWN_LIMIT, in, head);
    }

    private static Result stopped(Status status, State state, double head) {
        return new Result(status, state, head, head, 0, java.util.List.of());
    }

    private TracePhysicsContract() { }
}
