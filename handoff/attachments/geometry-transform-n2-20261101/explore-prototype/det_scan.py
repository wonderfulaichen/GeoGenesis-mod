import math, random
GRAD = [(-1.0,-1.0),(1.0,-1.0),(-1.0,1.0),(1.0,1.0),(0.0,-1.0),(-1.0,0.0),(0.0,1.0),(1.0,0.0)]
def h32(v):
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v
def gidx(seed, x, y):
    h = seed
    h = h32(h ^ h32(1619 * x))
    h = h32(h ^ h32(31337 * y))
    h = h32(h * h * h * 60493)
    h = h32(h ^ (h >> 13))
    return h & 7
def herm(t): return t*t*(3-2*t)
def dherm(t): return 6*t*(1-t)
def dot(g, t, s): return g[0]*t + g[1]*s
def dsample(seed, u, v):
    """analytic (d/du, d/dv) of single-octave RefNoise.Perlin.sample"""
    x2 = math.floor(u); y2 = math.floor(v)
    t = u-x2; s = v-y2
    G = {(0,0):GRAD[gidx(seed,x2,y2)], (1,0):GRAD[gidx(seed,x2+1,y2)],
         (0,1):GRAD[gidx(seed,x2,y2+1)], (1,1):GRAD[gidx(seed,x2+1,y2+1)]}
    g00 = dot(G[(0,0)], t, s)
    g10 = dot(G[(1,0)], t-1, s)
    g01 = dot(G[(0,1)], t, s-1)
    g11 = dot(G[(1,1)], t-1, s-1)
    xs = herm(t); ys = herm(s); dxs = dherm(t); dys = dherm(s)
    f0 = g00 + xs*(g10-g00)
    f2 = g01 + xs*(g11-g01)
    # d/dt
    du = dxs*(g10-g00) + ys*(dxs*(g11-g01) - dxs*(g10-g00))
    # d/ds: f0 and f2 vary with s; also ys varies
    df0 = G[(0,0)][1] + xs*(G[(1,0)][1]-G[(0,0)][1])
    df2 = G[(0,1)][1] + xs*(G[(1,1)][1]-G[(0,1)][1])
    dv = df0 + dys*(f2-f0) + ys*(df2-df0)
    return du, dv
F = 1.0/400.0; SCALE = 200.0*(0.5/0.9)*F      # 0.2777...
def jac(seedX, seedZ, x, z):
    u, v = x*F, z*F
    ax, bx = dsample(seedX, u, v)
    cz, dz = dsample(seedZ, u, v)
    return (SCALE*ax, SCALE*bx, SCALE*cz, SCALE*dz)
def det(seedX, seedZ, x, z):
    a,b,c,d = jac(seedX,seedZ,x,z)
    return (1+a)*(1+d) - b*c
if __name__ == '__main__':
    print('SCALE', SCALE, 'per-axis bound', 3*SCALE, 'row-sum L', 6*SCALE)
    for worldSeed in (9139912035078620160, 0, -419, 12345):
        b = (worldSeed ^ (worldSeed >> 32)) & 0xFFFFFFFF
        b = b - 0x100000000 if b >= 0x80000000 else b
        sx, sz = h32(b+21), h32(b+22)
        worst = 1e9; wx = None; neg = 0; n = 0; mindiag = 1e9
        for i in range(300):
            for j in range(300):
                x = -60000 + i*400.0; z = -60000 + j*400.0
                v = det(sx, sz, x, z); n += 1
                a,bb,c,d = jac(sx,sz,x,z)
                mindiag = min(mindiag, 1+a, 1+d)
                if v < 0: neg += 1
                if v < worst: worst = v; wx = (x,z)
        print('world seed %d  warp seeds %d/%d  cells=%d  det<0=%d  min det=%.4f at %s  min diagonal=%.4f'
              % (worldSeed, sx, sz, n, neg, worst, wx, mindiag))
