
void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 uv = (frag - .5 * uRes) / uRes.y;
    vec3 c = scene(uv, frag);

    // touch shockwave
    vec2 tp = (uTouch.xy * uRes - .5 * uRes) / uRes.y;
    float age = uTouch.z;
    if (age < 1.6) {
        float d = length(uv - tp), r = age * .75;
        float ring = exp(-pow((d - r) * 28., 2.)) * (1. - age / 1.6);
        c += ring * mix(uAccent, vec3(1.), .3) * 1.2;
        c += uAccent * exp(-d * 14.) * (1. - age / 1.6) * .6;
    }
    // charging surge rising from the bottom edge
    c += uCharging * uAccent * .35 * exp(-(uv.y + .5) * 7.) * (.65 + .35 * sin(T * 3. - uv.x * 6.));

    c = aces(c * 1.05);
    c = pow(c, vec3(.75));
    c *= 1. - .45 * dot(uv * vec2(.9, .55), uv * vec2(.9, .55)); // vignette
    c += (h21(frag + fract(T * 7.1) * 100.) - .5) / 255.;            // dither
    fragColor = vec4(c, 1.);
}
