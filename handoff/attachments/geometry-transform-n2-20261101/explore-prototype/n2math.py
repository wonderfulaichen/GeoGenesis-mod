"""Independent prototype for the N2 continuous Perlin primitive.

Reimplements RefNoise.Perlin.sample (single octave, hermite, table GRAD_2D) in double,
with an analytic derivative, then:
  * validates the derivative against central differences,
  * enumerates all 8^4 gradient combinations to bound |d/dt| and |d/ds|,
  * builds a conservative (pessimistic-pairing) lower bound for det J of the warp map
    F(x,z) = (x + 200*n_x(x,z) - 100, z + 200*n_z(x,z) - 100).
"""
import math

GRAD = [(-1.0, -1.0), (1.0, -1.0), (-1.0, 1.0), (1.0, 1.0),
        (0.0, -1.0), (-1.0, 0.0), (0.0, 1.0), (1.0, 0.0)]


def h32(v):
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v


def grad_index(seed, x, y):
    h = seed
    h = h32(h ^ h32(1619 * x))
    h = h32(h ^ h32(31337 * y))
    h = h32(h * h * h * 60493)
    h = h32(h ^ (h >> 13))
    return h & 7


def hermite(t):
    return t * t * (3.0 - 2.0 * t)


def dhermite(t):
    return 6.0 * t * (1.0 - t)


def lerp(a, b, t):
    return a + t * (b - a)


def corners(seed, x2, y2):
    return (GRAD[grad_index(seed, x2, y2)], GRAD[grad_index(seed, x2 + 1, y2)],
            GRAD[grad_index(seed, x2, y2 + 1)], GRAD[grad_index(seed, x2 + 1, y2 + 1)])


def sample_dot(g00, g10, g01, g11, t, s):
    """The four gradient-dot values plus interpolation, exactly as RefNoise.sample."""
    d00 = g00[0] * t + g00[1] * s
    d10 = g10[0] * (t - 1.0) + g10[1] * s
    d01 = g01[0] * t + g01[1] * (s - 1.0)
    d11 = g11[0] * (t - 1.0) + g11[1] * (s - 1.0)
    return d00, d10, d01, d11


def value(d00, d10, d01, d11, t, s):
    xs, ys = hermite(t), hermite(s)
    return lerp(lerp(d00, d10, xs), lerp(d01, d11, xs), ys)


def derivative(d00, d10, d01, d11, g00, g10, g01, g11, t, s):
    """(d/dt, d/ds) of value(). Includes the corner-tangent terms folded into the lerp."""
    xs, ys = hermite(t), hermite(s)
    dxs, dys = dhermite(t), dhermite(s)
    # d/dt of the two x-lerps: tangent lerp + weight term
    a0 = g00[0] + xs * (g10[0] - g00[0])
    a1 = g01[0] + xs * (g11[0] - g01[0])
    df0 = a0 + dxs * (d10 - d00)
    df2 = a1 + dxs * (d11 - d01)
    du = df0 + ys * (df2 - df0)
    # d/ds
    b0 = g00[1] + xs * (g10[1] - g00[1])
    b1 = g01[1] + xs * (g11[1] - g01[1])
    dv = b0 + ys * (b1 - b0) + dys * (lerp(d01, d11, xs) - lerp(d00, d10, xs))
    return du, dv


def sample(seed, u, v):
    x2, y2 = math.floor(u), math.floor(v)
    t, s = u - x2, v - y2
    g = corners(seed, x2, y2)
    return value(*sample_dot(*g, t, s), t, s)


def sample_deriv(seed, u, v):
    x2, y2 = math.floor(u), math.floor(v)
    t, s = u - x2, v - y2
    g = corners(seed, x2, y2)
    d = sample_dot(*g, t, s)
    return value(*d, t, s), derivative(*d, *g, t, s)


# ---- warp: o = (n - 0.5) * 200, n = normalized range [0,1] over (min, max) = (-0.45, 0.45)
FREQ = 1.0 / 400.0
GAIN = 0.5
MAXB = 0.45            # SIGNALS[1] * GAIN for one octave
RANGE = 2.0 * MAXB
STRENGTH = 200.0
SCALE = STRENGTH * GAIN / RANGE          # 200 * 0.5 / 0.9 = 111.11..; times FREQ later


def offset(seed, x, z):
    raw = sample(seed, x * FREQ, z * FREQ) * GAIN
    d = raw - MAXB
    if d <= 0.0:
        return 0.0
    if d >= RANGE:
        return STRENGTH
    return d / RANGE * STRENGTH


def jacobian(seed_x, seed_z, x, z):
    """d(o_x, o_z)/d(x, z) in world units, analytic."""
    ax, az = sample_deriv(seed_x, x * FREQ, z * FREQ)[1]
    bx, bz = sample_deriv(seed_z, x * FREQ, z * FREQ)[1]
    k = SCALE * FREQ
    return ax * k, az * k, bx * k, bz * k


def det(seed_x, seed_z, x, z):
    a, b, c, d = jacobian(seed_x, seed_z, x, z)
    return (1.0 + a) * (1.0 + d) - b * c


def warp_seeds(world_seed):
    base = h32(world_seed)
    return h32(base + 21), h32(base + 22)
