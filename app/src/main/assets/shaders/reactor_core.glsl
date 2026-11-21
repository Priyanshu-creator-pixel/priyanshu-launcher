// REACTOR CORE — flagship environment of the Holo Core universe.
// A plasma core inside three counter-rotating segmented mechanical rings, a caged gyroscope,
// orbiting shards, four support pylons and a holographic command floor, all ray-marched.

float gGlow;

float segRing(vec3 p, float R, float n, vec3 b) {
    float a = atan(p.z, p.x), per = 2. * PI / n;
    a = mod(a + per * .5, per) - per * .5;
    float rr = length(p.xz);
    vec3 q = vec3(cos(a) * rr - R, p.y, sin(a) * rr);
    return sdBox(q, b) - .008;
}

float shards(vec3 p) {
    p.xz *= rot(T * .22);
    float a = atan(p.z, p.x), per = 2. * PI / 9.;
    float id = floor((a + per * .5) / per);
    a = mod(a + per * .5, per) - per * .5;
    float rr = length(p.xz);
    vec3 q = vec3(cos(a) * rr - 2.25, p.y - .25 * sin(T * .9 + id * 1.7), sin(a) * rr);
    q.xy *= rot(T * .7 + id); q.yz *= rot(T * .5 + id * 2.);
    vec3 o = abs(q); // octahedron
    return (o.x + o.y + o.z - .11) * .577;
}

// x = distance, y = material (0 core, 1 metal, 2 emissive trim)
vec2 map(vec3 p) {
    float core = length(p) - .46 - .025 * sin(p.y * 22. + T * 4.) * sin(p.x * 18. - T * 3.);
    gGlow += .006 / (.03 + core * core * 6.);

    vec3 p1 = p; p1.xy *= rot(.42); p1.xz *= rot(T * .55);
    float r1 = segRing(p1, 1.02, 30., vec3(.07, .055, .085));

    vec3 p2 = p; p2.yz *= rot(1.15 + .25 * sin(T * .2)); p2.xz *= rot(-T * .38);
    float r2 = segRing(p2, 1.38, 44., vec3(.045, .11, .06));

    vec3 p3 = p; p3.xy *= rot(-.75); p3.yz *= rot(T * .21); p3.xz *= rot(T * .29);
    float r3 = segRing(p3, 1.78, 16., vec3(.09, .035, .22));

    // emissive inner tracks
    vec3 p4 = p; p4.xz *= rot(T * 1.3);
    float trim = length(vec2(length(p4.xy) - .66, p4.z)) - .012;
    vec3 p5 = p; p5.yz *= rot(T * .9);
    trim = min(trim, length(vec2(length(p5.xz) - .74, p5.y)) - .01);

    // cage struts linking the rings (gyroscope frame)
    vec3 c = p; c.xz *= rot(T * .1);
    float a = atan(c.z, c.x), per = 2. * PI / 6.;
    a = mod(a + per * .5, per) - per * .5;
    float rr = length(c.xz);
    vec3 q = vec3(cos(a) * rr, c.y, sin(a) * rr);
    float strut = length(vec2(length(q.xy - vec2(0., 0.)) - 2.02, q.z)) - .018;
    strut = max(strut, abs(c.y) - 1.25);

    float metal = min(min(r1, r2), min(r3, strut));
    metal = min(metal, shards(p));

    vec2 res = vec2(core, 0.);
    if (metal < res.x) res = vec2(metal, 1.);
    if (trim < res.x) res = vec2(trim, 2.);
    return res;
}

vec3 calcNormal(vec3 p) {
    vec2 e = vec2(.0015, 0.);
    return normalize(vec3(map(p + e.xyy).x - map(p - e.xyy).x,
                          map(p + e.yxy).x - map(p - e.yxy).x,
                          map(p + e.yyx).x - map(p - e.yyx).x));
}

// holographic command floor and room, analytic (cheap)
vec3 room(vec3 ro, vec3 rd, vec3 coreCol) {
    vec3 col = mix(vec3(.001, .002, .005), vec3(.006, .014, .025), smoothstep(-.3, .6, rd.y));
    // tall wall panels around the chamber
    float ang = atan(rd.x, rd.z);
    float panel = smoothstep(.02, .0, abs(fract(ang * 6. / PI) - .5) - .47);
    col += coreCol * panel * .05 * smoothstep(-.1, .5, rd.y) * smoothstep(1., .2, rd.y);
    float bands = step(.92, fract(rd.y * 14. - T * .05));
    col += coreCol * bands * .03 * smoothstep(.7, 0., abs(rd.y - .15));
    // light pillars rising from the core
    col += coreCol * .05 * exp(-abs(rd.x) * 14.) * smoothstep(-.2, .9, rd.y);

    if (rd.y < 0.) {
        float t = -(ro.y + 1.75) / rd.y;
        vec3 p = ro + rd * t;
        float r = length(p.xz);
        float fade = exp(-t * .14);
        float hx = hexEdge(p.xz * 1.6);
        float line = smoothstep(.035, .0, hx);
        // pulse rings travelling outward from the reactor
        float pulse = exp(-pow(fract(r * .18 - T * .25) - .5, 2.) * 90.);
        vec3 fl = vec3(.006, .012, .02);
        fl += coreCol * line * (.04 + .6 * pulse) * fade;
        // radial light pool under the core
        fl += coreCol * .22 * exp(-r * 1.1);
        // inner ring engravings
        float eng = smoothstep(.03, .0, abs(fract(r * 1.2) - .5) - .46) * step(r, 4.);
        fl += coreCol * eng * .15 * (.6 + .4 * sin(atan(p.z, p.x) * 24. + T * 2.));
        // glossy reflection of the core
        fl += coreCol * .25 * exp(-abs(p.x) * 3.) * exp(-abs(p.z) * .8) * fade;
        col = mix(col, fl, smoothstep(0., -.02, rd.y));
    }
    return col;
}

vec3 scene(vec2 uv, vec2 frag) {
    vec3 coreCol = uAccent * (.8 + .15 * sin(T * 2.3) + .06 * sin(T * 7.1));
    vec3 ro = vec3(0., 1.2, -10.5);
    ro.xz *= rot(.35 * sin(T * .07) + uPar.x * .3);
    ro.y += uPar.y * 1.2;
    vec3 rd = camRay(uv + vec2(0., -.03), ro, vec3(0., -.15, 0.), 1.55);

    vec3 col = room(ro, rd, coreCol);

    // only march where the machine can be (bounding sphere)
    float b = dot(ro, rd), cc = dot(ro, ro) - 2.6 * 2.6, disc = b * b - cc;
    gGlow = 0.;
    if (disc > 0.) {
        float t = max(-b - sqrt(disc), 0.), tEnd = -b + sqrt(disc);
        int steps = uQuality < .5 ? 48 : (uQuality < 1.5 ? 64 : 90);
        vec2 hit = vec2(-1.);
        for (int i = 0; i < 90; i++) {
            if (i >= steps) break;
            vec3 p = ro + rd * t;
            vec2 d = map(p);
            if (d.x < .0015 * t) { hit = vec2(t, d.y); break; }
            t += d.x * .85;
            if (t > tEnd) break;
        }
        if (hit.x > 0.) {
            vec3 p = ro + rd * hit.x, n = calcNormal(p);
            float fres = pow(1. - abs(dot(n, -rd)), 3.);
            if (hit.y < .5) {
                float f = fbm3(p * 3.5 + vec3(0., T * .9, T * .3));
                float f2 = fbm3(p * 9. - vec3(T * 1.4));
                col = coreCol * (1.2 + 3.5 * f * f + 1.2 * f2) + vec3(1.) * (.3 + 1.6 * (1. - fres) * f * f);
            } else if (hit.y < 1.5) {
                vec3 ld = normalize(-p);
                float dist = length(p);
                float dif = max(dot(n, ld), 0.) / (1. + dist * dist * .5);
                vec3 key = normalize(vec3(.5, 1., -.6));
                float k = max(dot(n, key), 0.);
                vec3 refl = reflect(rd, n);
                vec3 env = room(p, refl, coreCol);
                float spec = pow(max(dot(refl, ld), 0.), 32.);
                vec3 metal = vec3(.16, .18, .22);
                col = metal * (.04 + k * .5) + coreCol * dif * .9 + coreCol * spec * 1.6 + env * (.25 + .75 * fres) * .6;
                // panel lines and running lights on the metal
                float seam = smoothstep(.015, .0, abs(fract(p.y * 9.) - .5) - .47);
                col += coreCol * seam * .12;
                col += coreCol * step(.985, fract(atan(p.z, p.x) * 6. - T * 1.5)) * 1.5;
            } else {
                col = coreCol * 2.2 + vec3(.4);
            }
            col = mix(col, room(ro, rd, coreCol), 1. - exp(-hit.x * .02));
        }
    }
    col += coreCol * gGlow * .06;

    // halo around the core
    float dc = length(ro - rd * dot(ro, rd));
    col += coreCol * .018 / (dc * dc + .02);
    // floating holographic dust
    vec2 dp = uv * 34. + vec2(T * .2, T * .45);
    col += coreCol * .9 * step(.975, h21(floor(dp))) * smoothstep(.25, 0., length(fract(dp) - .5)) * (.5 + .5 * sin(T * 3. + h21(floor(dp)) * 40.));
    return col;
}
