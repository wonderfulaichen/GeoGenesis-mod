package com.geogenesis.worldgen.hydrology;

public record RiverNode(
    float ax, float ay,
    float bx, float by,
    float ah, float bh,
    float ar, float br,
    float displacement
) {
    public float getProjection(float x, float y) {
        float dx = bx - ax;
        float dy = by - ay;
        float denom = dx * dx + dy * dy;
        if (denom < 1e-10f) return 0f;
        float v = ((x - ax) * dx + (y - ay) * dy) / denom;
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    public float getDistance2(float x, float y, float t) {
        float pad = 0.05f;
        float alpha = map(t, pad, 1f - pad, 1f - pad * 2);
        alpha = alpha < 0.5f ? alpha / 0.5f : (1f - alpha) / 0.5f;
        alpha = smoothstep(alpha);
        alpha *= displacement;

        float tx = getX(t);
        float ty = getY(t);

        float nx = -(by - ay);
        float ny = (bx - ax);
        float px = tx + nx * alpha;
        float py = ty + ny * alpha;

        float ddx = x - px;
        float ddy = y - py;
        return ddx * ddx + ddy * ddy;
    }

    public float getDistance(float x, float y, float t) {
        return (float) Math.sqrt(getDistance2(x, y, t));
    }

    public float getX(float t) { return ax + t * (bx - ax); }
    public float getY(float t) { return ay + t * (by - ay); }
    public float getHeight(float t) { return ah + t * (bh - ah); }
    public float getRadius(float t) { return ar + t * (br - ar); }

    private static float map(float value, float min, float max, float range) {
        if (value <= min) return 0f;
        if (value >= max) return range;
        return (value - min) / (max - min) * range;
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }
}
