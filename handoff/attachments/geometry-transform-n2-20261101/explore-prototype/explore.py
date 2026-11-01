import math, random
GRAD = [(-1.0,-1.0),(1.0,-1.0),(-1.0,1.0),(1.0,1.0),(0.0,-1.0),(-1.0,0.0),(0.0,1.0),(1.0,0.0)]

def h32(v):
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v

def grad(seed, x, y, xd, yd):
    h = seed
    h = h32(h ^ h32(1619 * x))
    h = h32(h ^ h32(31337 * y))
    h = h32(h * h * h * 60493)
    h = h32(h ^ (h >> 13))
    g = GRAD[h & 7]
    return xd * g[0] + yd * g[1]

def gvec(seed, x, y):
    h = seed
    h = h32(h ^ h32(1619 * x))
    h = h32(h ^ h32(31337 * y))
    h = h32(h * h * h * 60493)
    h = h32(h ^ (h >> 13))
    return GRAD[h & 7]

def herm(t): return t * t * (3 - 2 * t)
def dherm(t): return 6 * t * (1 - t)
def lerp(a, b, t): return a + t * (b - a)

def der(t, s, g00, g10, g01, g11):
    """d/dxd of the bilinear-hermite interpolant, given four gradient DOT values."""
    m0 = g10 - g00
    m2 = g11 - g01
    xs = herm(t); ys = herm(s)
    dxs = dherm(t)
    # f0 = g00 + xs*m0 ; f2 = g01 + xs*m2
    df0 = dxs * m0
    df2 = dxs * m2
    return df0 + ys * (df2 - df0)

def exhaustive(step=40):
    """All 8^4 gradient combos, fine (t,s) grid with discretisation guard 1e-9."""
    best = 0.0; arg = None
    ts = [i / step for i in range(step + 1)]
    for a in range(8):
        for b in range(8):
            for c in range(8):
                for d in range(8):
                    gx = [GRAD[a][0], GRAD[b][0], GRAD[c][0], GRAD[d][0]]
                    for ti, t in enumerate(ts):
                        for si, s in enumerate(ts):
                            g00 = gx[0] * t + (GRAD[a][1]) * s
                            v = der(t, s, g00, gx[1] * (t - 1) + GRAD[b][1] * s,
                                    gx[2] * t + GRAD[c][1] * (s - 1),
                                    gx[3] * (t - 1) + GRAD[d][1] * (s - 1))
                            if abs(v) > best:
                                best = abs(v); arg = (a, b, c, d, t, s, v)
    return best, arg

if __name__ == '__main__':
    print('grads', GRAD)
    b, a = exhaustive(20)
    print('exhaustive coarse max |d/dt| =', b, 'at', a)
    b2, a2 = exhaustive(40)
    print('exhaustive fine   max |d/dt| =', b2, 'at', a2)
    F = 1.0 / 400.0
    gain = 0.5; rng = 0.9
    scale = 200.0 * gain / rng * F
    print('world derivative scale =', scale)
    print('max |do/dx| ~', scale * b2)
    print('sup-row-sum Lipschitz L ~', 2 * scale * b2)
    # float quantization context
    for v in (1e4, 1e5, 1e6, 1e7, 29998000.0):
        import struct
        f = struct.unpack('f', struct.pack('f', v))[0]
        nxt = struct.unpack('f', struct.pack('f', math.nextafter(f, math.inf)))[0]
        print('x=%g float ulp=%g phase cells lost at 400 = %g' % (v, nxt - v, (nxt - v) / 400.0))
