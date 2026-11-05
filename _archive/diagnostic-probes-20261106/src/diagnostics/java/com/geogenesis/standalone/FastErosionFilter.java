package com.geogenesis.standalone;

/**
 * The approved <b>Fast Erosion + Derivatives</b> erosion core as a reusable, zero-dependency
 * function.
 *
 * <p>Extracted verbatim from the already-approved port in {@link FastErosionOriginalDemo}; the
 * arithmetic, statement order and integer emulation are unchanged, so the demo still reproduces
 * the approved image bit-for-bit. Splitting it out lets a terrain type feed <i>its own</i> height,
 * gradient and second derivative instead of the demo's private simplex noise.</p>
 *
 * <h3>Interface</h3>
 * <p>Callers supply a {@link Fields} callback that answers the first and second derivatives at an
 * integer sample and its two forward neighbours, exactly the three probes the original does
 * ({@code wrapped_snoise2(xz)}, {@code (xz+(1,0))}, {@code (xz+(0,1))}). The filter itself never
 * knows where those numbers come from, so the demo's analytic simplex derivatives and a terrain
 * type's finite differences run through the same code.</p>
 *
 * <h3>Units</h3>
 * <p>Inputs are in "sample units": {@code h} per sample step, matching the original, where the
 * caller has already scaled its terrain into the same working range. The returned erosion is the
 * original's {@code erosion / max_erosion}, i.e. a graded value in roughly {@code [-1, 1]} that the
 * caller adds to its own base height with the original 0.25 amplitude share.</p>
 *
 * <h3>Original behaviour kept on purpose (do not "fix")</h3>
 * <p>{@code directional_gabor2} writes its {@code sloping} out-parameter into the same
 * {@code steepness} array that the rest of the routine keeps reading, so from the second octave on
 * {@code steepness} is gabor slope, not terrain slope; the loop also feeds {@code gabor} back into
 * {@code getRounding}. Both are part of the published effect.</p>
 */
public final class FastErosionFilter {

    /** Original parameter set. Change nothing here without a new approved image. */
    public static final Params ORIGINAL = new Params(
            4, 0.25f, 21.7f, 0.5f, 1.2f,
            (float) (36.0 * Math.PI / 180.0), 0.1f, 0.1f, 1.2f,
            262144f, 2f, 25f);

    /** First/second derivative probe. Writes {gx, gz, laplacian} for one integer sample. */
    public interface Fields {
        void derivatives(int px, int pz, float[] out);
    }

    /**
     * @param octaves        stripe layers
     * @param amplitude      share of the erosion relative to the base height
     * @param frequency      first-octave stripe frequency (samples)
     * @param gain           per-octave amplitude falloff
     * @param lacunarity     per-octave frequency step
     * @param branchAngle    aspect rotation between octaves (radians)
     * @param ridgeRounding  rounding of ridges
     * @param gullyRounding  rounding of gullies
     * @param detail         mask sharpening
     * @param maxHeight      world-height scale applied to steepness/laplacian
     * @param steepnessScale extra steepness gain
     * @param laplacianDiv   laplacian divisor before the fade target
     */
    public record Params(int octaves, float amplitude, float frequency, float gain, float lacunarity,
                         float branchAngle, float ridgeRounding, float gullyRounding, float detail,
                         float maxHeight, float steepnessScale, float laplacianDiv) { }

    /** {@code wrap()} reinterpretation for negative cells; see {@link #wrap(int, int)}. */
    public enum WrapMode { INTENT, ONES, MINUS_ONE }
    public static WrapMode wrapMode = WrapMode.INTENT;

    private static final float TAU = 6.28318530717959f;
    private static final float PI = 3.14159265358979323846f;

    private FastErosionFilter() { }

    /**
     * Runs the original erosion chain at one sample.
     *
     * @param px     integer sample x (world samples, not canvas pixels)
     * @param pz     integer sample z
     * @param fields derivative source for this sample position
     * @param params parameter set (normally {@link #ORIGINAL})
     * @param dst    receives {erosion, dErosion/dx, dErosion/dz}, already divided by max erosion
     */
    public static void erosion(int px, int pz, Fields fields, Params params, float[] dst) {
        float[] c = new float[3], dx = new float[3], dy = new float[3];
        fields.derivatives(px, pz, c);
        fields.derivatives(px + 1, pz, dx);
        fields.derivatives(px, pz + 1, dy);

        float cl = (float) StrictMath.hypot(c[0], c[1]);
        float[] steepness = {
                cl * params.maxHeight * params.steepnessScale,
                ((float) StrictMath.hypot(dx[0], dx[1]) - cl) * params.maxHeight
                        * params.steepnessScale,
                ((float) StrictMath.hypot(dy[0], dy[1]) - cl) * params.maxHeight
                        * params.steepnessScale};

        float lap = c[2] * params.maxHeight / params.laplacianDiv;
        float[] fadeTarget = new float[3];
        if (Math.abs(lap) >= 1f) {
            fadeTarget[0] = Math.signum(lap);
        } else {
            fadeTarget[0] = lap;
            fadeTarget[1] = (dx[2] - c[2]) * params.maxHeight / params.laplacianDiv;
            fadeTarget[2] = (dy[2] - c[2]) * params.maxHeight / params.laplacianDiv;
        }

        float[] rounding = getRounding(fadeTarget, params.ridgeRounding, params.gullyRounding);
        float[] slopeMask = easeOutQuad(easeInQuadLinear(steepness, rounding));
        float[] ridgeMask = {1f, 0f, 0f};
        float[] ridgeFade = {0f, 0f, 0f};
        float[] gabor = new float[3], octave = new float[3], scratch = new float[3];

        float freq = params.frequency, amp = 1f;
        // The original rotates the first octave to be perpendicular to the terrain aspect.
        float theta = angleOf(c);
        float[] aspect = {theta + PI / 2f, wrapAngle(angleOf(dx) - theta),
                wrapAngle(angleOf(dy) - theta)};
        directionalGabor2(px, pz, freq, aspect, steepness, gabor);
        mixGraded(fadeTarget, gabor, slopeMask, octave);
        mixGraded(ridgeFade, gabor, ridgeMask, ridgeFade);
        float[] erosion = {octave[0] * amp, octave[1] * amp, octave[2] * amp};
        float maxErosion = amp;

        for (int i = 1; i < params.octaves; i++) {
            amp *= params.gain;
            freq += params.lacunarity;
            aspect[0] += Math.signum(steepness[0]) * -params.branchAngle;

            float mult = (float) (1 << (int) (freq - params.frequency));
            float[] octRounding = getRounding(gabor, params.ridgeRounding, params.gullyRounding);
            for (int k = 0; k < 3; k++) octRounding[k] *= mult;
            float[] octaveSlopeMask = easeOutQuad(easeInQuadLinear(abs3(steepness), octRounding));
            easeOutPow(slopeMask, params.detail, scratch);
            multiplyGraded(scratch, octaveSlopeMask, slopeMask);

            float[] doubled = abs3(steepness);
            for (int k = 0; k < 3; k++) doubled[k] *= 2f;
            float[] octaveRidgeMask = easeOutQuad(easeInQuadLinear(doubled, octRounding));
            multiplyGraded(ridgeMask, octaveRidgeMask, ridgeMask);

            System.arraycopy(octave, 0, fadeTarget, 0, 3);
            mixGraded(ridgeFade, gabor, ridgeMask, ridgeFade);
            directionalGabor2(px, pz, freq, aspect, steepness, gabor);
            mixGraded(fadeTarget, gabor, slopeMask, octave);
            for (int k = 0; k < 3; k++) erosion[k] += octave[k] * amp;
            maxErosion += amp;
        }

        for (int k = 0; k < 3; k++) dst[k] = erosion[k] / maxErosion;
    }

    /** Atan of a derivative pair; the original's {@code atan(c.z, c.y)}. */
    static float angleOf(float[] g) {
        return (float) StrictMath.atan2(g[1], g[0]);
    }

    /** The original wraps an angle difference into {@code [-pi, pi)}. */
    static float wrapAngle(float a) {
        return (fract(a / TAU + 0.5f) - 0.5f) * TAU;
    }

    static float fract(float v) { return v - (float) Math.floor(v); }

    // ================= Common.txt: shaping helpers =================
    static float[] abs3(float[] t) {
        float s = Math.signum(t[0]);
        return new float[]{Math.abs(t[0]), t[1] * s, t[2] * s};
    }

    static float[] easeInQuadLinear(float[] t, float[] smoothing) {
        float s = Math.max(smoothing[0], 0.0001f);
        boolean quad = t[0] < s;
        float val = quad ? 0.5f * t[0] * t[0] / s : t[0] - 0.5f * s;
        float dfdt = quad ? t[0] / s : 1f;
        float dfds = quad ? -0.5f * t[0] * t[0] / (s * s) : -0.5f;
        return new float[]{val, dfdt * t[1] + dfds * smoothing[1], dfdt * t[2] + dfds * smoothing[2]};
    }

    static float[] easeOutQuad(float[] t) {
        float v = 1f - clamp01(t[0]);
        float mask = (t[0] >= 0f && t[0] <= 1f) ? 1f : 0f;
        return new float[]{1f - v * v, (2f * v * mask) * t[1], (2f * v * mask) * t[2]};
    }

    static void easeOutPow(float[] t, float power, float[] dst) {
        float b = 1f - clamp01(t[0]);
        float deriv = power * (float) StrictMath.pow(Math.max(b, 0.0001f), power - 1f);
        float newH = 1f - (float) StrictMath.pow(b, power);
        dst[1] = t[1] * deriv;
        dst[2] = t[2] * deriv;
        dst[0] = newH;
    }

    static void mixGraded(float[] a, float[] b, float[] t, float[] dst) {
        float tt = t[0], ty = t[1], tz = t[2];
        if (tt > 1f) { tt = 1f; ty = 0f; tz = 0f; }
        float a0 = a[0], a1 = a[1], a2 = a[2];
        float delta = b[0] - a0;
        dst[1] = a1 + (b[1] - a1) * tt + delta * ty;
        dst[2] = a2 + (b[2] - a2) * tt + delta * tz;
        dst[0] = a0 + delta * tt;
    }

    static void multiplyGraded(float[] a, float[] b, float[] dst) {
        float a0 = a[0], a1 = a[1], a2 = a[2];
        dst[1] = a0 * b[1] + b[0] * a1;
        dst[2] = a0 * b[2] + b[0] * a2;
        dst[0] = a0 * b[0];
    }

    static float[] getRounding(float[] fadeTarget, float ridge, float gully) {
        float u = fadeTarget[0] + 0.5f;
        float c = clamp01(u);
        float grad = (u >= 0f && u <= 1f) ? (ridge - gully) : 0f;
        return new float[]{gully + (ridge - gully) * c, grad * fadeTarget[1], grad * fadeTarget[2]};
    }

    static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ================= lmage.txt: directional gabor stripes =================
    static void directionalGabor2(int px, int pz, float inFreq, float[] aspect, float[] slopingOut,
                                  float[] dst) {
        final float rootTwo = 1.4142135624f;
        final float disFallOff = 4f;
        int shift = Math.max(28 - (int) Math.floor(inFreq), 0);
        float frequency = (float) (1 << shift) * rootTwo;
        int baseCellX = px >> shift, baseCellZ = pz >> shift;
        float invFrequency = 1f / frequency;
        float stripeFreq = TAU * rootTwo * (1f + fract(inFreq));

        float dirX = (float) StrictMath.cos(aspect[0]);
        float dirZ = (float) StrictMath.sin(aspect[0]);
        float perpX = -dirZ, perpZ = dirX;

        float asx = 0, asy = 0, asz = 0, acx = 0, acy = 0, acz = 0, atx = 0, aty = 0, atz = 0;
        int[] cellPos = new int[2];
        for (int j = -2; j <= 2; j++) {
            for (int i = -2; i <= 2; i++) {
                getCellPosition(baseCellX + i, baseCellZ + j, shift, cellPos);
                float cdx = (px - cellPos[0]) * invFrequency;
                float cdz = (pz - cellPos[1]) * invFrequency;

                float sqrDis = cdx * cdx + cdz * cdz;
                float alignment = cdx * (dirX * stripeFreq) + cdz * (dirZ * stripeFreq);
                float weight = (float) StrictMath.exp(-disFallOff * sqrDis);
                float cosA = (float) StrictMath.cos(alignment);
                float sinA = (float) StrictMath.sin(alignment);
                float deltaProj = cdx * perpX + cdz * perpZ;
                float gradX = (dirX + aspect[1] * deltaProj * frequency) * stripeFreq;
                float gradZ = (dirZ + aspect[2] * deltaProj * frequency) * stripeFreq;

                asx += weight * sinA;
                asy += weight * (-2f * disFallOff * cdx * sinA + cosA * gradX);
                asz += weight * (-2f * disFallOff * cdz * sinA + cosA * gradZ);

                acx += weight * cosA;
                acy += weight * (-2f * disFallOff * cdx * cosA - sinA * gradX);
                acz += weight * (-2f * disFallOff * cdz * cosA - sinA * gradZ);

                atx += weight;
                aty += weight * (-2f * disFallOff * cdx);
                atz += weight * (-2f * disFallOff * cdz);
            }
        }

        float ocx = acx / atx;
        float ocy = (acy - acx * aty / atx) * invFrequency / atx;
        float ocz = (acz - acx * atz / atx) * invFrequency / atx;
        float osx = asx / atx;
        float osy = (asy - asx * aty / atx) * invFrequency / atx;
        float osz = (asz - asx * atz / atx) * invFrequency / atx;

        float mag = 0.5f * (ocx * ocx + osx * osx) + 0.5f;
        float gradY = ocx * ocy + osx * osy;
        float gradZ = ocx * ocz + osx * osz;
        float invMag = 1f / (mag * mag);

        dst[0] = ocx / mag;
        dst[1] = (mag * ocy - ocx * gradY) * invMag;
        dst[2] = (mag * ocz - ocx * gradZ) * invMag;

        slopingOut[0] = osx / mag;
        slopingOut[1] = (mag * osy - osx * gradY) * invMag;
        slopingOut[2] = (mag * osz - osx * gradZ) * invMag;
    }

    /**
     * GLSL {@code wrap(cell, frequency_shift)}.
     *
     * <p>The original reads {@code is_negative*(wrap_at+cell) + ~is_negative*(cell&(wrap_at-1))}
     * where {@code is_negative} comes from {@code ivec2(lessThan(...))}. GLSL ES lets that
     * conversion yield 0/1 or 0/-1 depending on the compiler, and {@code ~} then swaps which term
     * survives, so the published expression is ambiguous for negative cells. {@code INTENT} is the
     * documented intent and the default; negative cells only occur at the world wrap seam.</p>
     */
    static int wrap(int cell, int wrapAt) {
        if (cell >= 0) return cell & (wrapAt - 1);
        return switch (wrapMode) {
            case INTENT -> wrapAt + cell;
            case ONES -> (wrapAt + cell) - 2 * (cell & (wrapAt - 1));
            case MINUS_ONE -> -(wrapAt + cell);
        };
    }

    /** GLSL {@code get_cell_position}: wrap for hashing, then origin plus hashed offset. */
    static void getCellPosition(int cellX, int cellZ, int shift, int[] dst) {
        int mask = (1 << shift) - 1;
        int wx = wrap(cellX, (1 << 29) >>> shift);
        int wz = wrap(cellZ, (1 << 28) >>> shift);
        int offX = hashIvec2(wx, wz) & mask;
        // The original adds VORONOI_SALT to the ivec2, i.e. to BOTH components.
        int offZ = hashIvec2(wx + 938443, wz + 938443) & mask;
        dst[0] = (cellX << shift) + offX;
        dst[1] = (cellZ << shift) + offZ;
    }

    /** GLSL {@code hash_ivec2}: 32-bit unsigned arithmetic reproduced on Java int. */
    static int hashIvec2(int a, int b) {
        int hash = 8;
        hash += a & 0xFFFF;
        hash = (hash << 16) ^ (((a >>> 16) << 11) ^ hash);
        hash += hash >>> 11;
        hash += b & 0xFFFF;
        hash = (hash << 16) ^ (((b >>> 16) << 11) ^ hash);
        hash += hash >>> 11;
        hash ^= hash << 3;
        hash += hash >>> 5;
        hash ^= hash << 4;
        hash += hash >>> 17;
        hash ^= hash << 25;
        hash += hash >>> 6;
        return hash;
    }
}
