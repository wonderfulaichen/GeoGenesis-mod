import math, random
from det_scan import dsample, gidx, GRAD, F, SCALE, herm
def lerp(a, b, t): return a + t * (b - a)

def sample(seed, u, v):
    x2 = math.floor(u); y2 = math.floor(v)
    t = u-x2; s = v-y2
    g00 = GRAD[gidx(seed,x2,y2)]; g10 = GRAD[gidx(seed,x2+1,y2)]
    g01 = GRAD[gidx(seed,x2,y2+1)]; g11 = GRAD[gidx(seed,x2+1,y2+1)]
    f0 = lerp(g00[0]*t+g00[1]*s, g10[0]*(t-1)+g10[1]*s, herm(t))
    f2 = lerp(g01[0]*t+g01[1]*(s-1), g11[0]*(t-1)+g11[1]*(s-1), herm(t))
    return lerp(f0, f2, herm(s))

if __name__ == '__main__':
    rng = random.Random(20261101)
    worst = 0; ex = None
    for _ in range(300000):
        u = rng.uniform(-40, 40); v = rng.uniform(-40, 40)
        du, dv = dsample(12345, u, v)
        h = 1e-6
        nu = (sample(12345, u+h, v) - sample(12345, u-h, v)) / (2*h)
        nv = (sample(12345, u, v+h) - sample(12345, u, v-h)) / (2*h)
        err = max(abs(du-nu), abs(dv-nv))
        if err > worst: worst = err; ex = (u, v, du, nu, dv, nv)
    print('max analytic-vs-finite-difference error', worst, 'at', ex)
    big = 0; arg = None
    for _ in range(1000000):
        u = rng.uniform(-40, 40); v = rng.uniform(-40, 40)
        du, dv = dsample(778899, u, v)
        m = max(abs(du), abs(dv))
        if m > big: big = m; arg = (u, v, du, dv)
    print('random max |dsample| over 1e6 points', big, arg, '-> |do/dx| max', big*SCALE)
    # min det over fine random sampling
    for worldSeed in (9139912035078620160, 0, -419, 12345):
        b = (worldSeed ^ (worldSeed >> 32)) & 0xFFFFFFFF
        b = b - 0x100000000 if b >= 0x80000000 else b
        sx, sz = b+21, b+22
        mn = 1e9; args = None; neg = 0
        for _ in range(400000):
            x = rng.uniform(-1e6, 1e6); z = rng.uniform(-1e6, 1e6)
            u, v = x*F, z*F
            a1, b1 = dsample(sx, u, v); c1, d1 = dsample(sz, u, v)
            a1*=SCALE; b1*=SCALE; c1*=SCALE; d1*=SCALE
            d = (1+a1)*(1+d1) - b1*c1
            if d < 0: neg += 1
            if d < mn: mn = d; args = (a1,b1,c1,d1)
        print('seed %d: min det %.6f  det<0 count %d  arg %s' % (worldSeed, mn, neg, args if args and mn < 0.5 else 'n/a'))
