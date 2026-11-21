#version 300 es
precision highp float;

// Shared header for every Multiverse X environment.
uniform vec2  uRes;       // render size in pixels
uniform float uTime;      // seconds
uniform vec2  uPar;       // parallax from the gyroscope, -1..1
uniform vec3  uTouch;     // last touch: x, y (0..1, y up), seconds since touch
uniform float uBattery;   // 0..1
uniform float uCharging;  // 0 or 1
uniform float uDay;       // 0 = midnight, 0.5 = noon
uniform vec3  uAccent;    // theme accent colour (linear 0..1)
uniform float uQuality;   // 0 = battery saver .. 3 = extreme
out vec4 fragColor;

#define PI 3.14159265
#define T uTime

float h11(float p) { p = fract(p * .1031); p *= p + 33.33; p *= p + p; return fract(p); }
float h21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 h22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(.1031, .1030, .0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
float h31(vec3 p) { p = fract(p * .1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f);
    return mix(mix(h21(i), h21(i + vec2(1, 0)), f.x), mix(h21(i + vec2(0, 1)), h21(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec2 p) {
    float v = 0., a = .5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    for (int i = 0; i < 5; i++) { v += a * noise(p); p = m * p; a *= .5; }
    return v;
}
float noise3(vec3 p) {
    vec3 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f);
    return mix(mix(mix(h31(i), h31(i + vec3(1, 0, 0)), f.x), mix(h31(i + vec3(0, 1, 0)), h31(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h31(i + vec3(0, 0, 1)), h31(i + vec3(1, 0, 1)), f.x), mix(h31(i + vec3(0, 1, 1)), h31(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}
float fbm3(vec3 p) {
    float v = 0., a = .5;
    for (int i = 0; i < 4; i++) { v += a * noise3(p); p = p * 2.03 + vec3(1.7, 9.2, 3.1); a *= .5; }
    return v;
}
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
vec3 camRay(vec2 uv, vec3 ro, vec3 ta, float fl) {
    vec3 f = normalize(ta - ro), r = normalize(cross(vec3(0, 1, 0), f)), u = cross(f, r);
    return normalize(uv.x * r + uv.y * u + fl * f);
}
float sdBox(vec3 p, vec3 b) { vec3 q = abs(p) - b; return length(max(q, 0.)) + min(max(q.x, max(q.y, q.z)), 0.); }
// distance to the nearest edge of a hexagon tiling (0 on the edges)
float hexEdge(vec2 p) {
    vec2 r = vec2(1., 1.7320508), h = r * .5;
    vec2 a = mod(p, r) - h, b = mod(p - h, r) - h;
    vec2 g = dot(a, a) < dot(b, b) ? a : b; g = abs(g);
    return .5 - max(dot(g, normalize(r)), g.x);
}
float starField(vec2 uv, float scale, float thr) {
    vec2 g = uv * scale, id = floor(g), f = fract(g) - .5;
    vec2 o = h22(id) - .5; float r = h21(id + 7.);
    float d = length(f - o * .7);
    return step(thr, r) * smoothstep(.07, 0., d) * (.55 + .45 * sin(T * (1. + r * 3.) + r * 40.));
}
vec3 aces(vec3 x) { return clamp((x * (2.51 * x + .03)) / (x * (2.43 * x + .59) + .14), 0., 1.); }
