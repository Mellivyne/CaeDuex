#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform vec2 Size;
uniform vec4 Radius;
uniform vec4 Glass;
uniform vec4 Rim;
uniform vec2 Flow; // normalized injection progress, elapsed seconds since this entry was created
uniform float Palette; // 0: clear glass; 0..1: blue/pink/white liquid concentration
uniform vec4 ColorModulator;

in vec2 localPos;
in vec4 vertexColor;
out vec4 fragColor;

float roundedBox(vec2 p, vec2 halfSize, vec4 radii) {
    float r = p.x < 0.0 ? (p.y < 0.0 ? radii.x : radii.w) : (p.y < 0.0 ? radii.y : radii.z);
    r = min(r, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float frontAt(float y) {
    float t = clamp(y / max(Size.y, 1.0) * 2.0 - 1.0, -1.0, 1.0);
    float dome = max(0.0, 1.0 - t * t);
    float wave = sin(t * 5.0 + Flow.y * 4.0) * 0.055 * dome;
    wave += sin(t * 13.0 - Flow.y * 5.0) * 0.012 * dome;
    // The advancing meniscus bows outward in the centre. It starts fully outside the left edge.
    return mix(-Size.y * 0.35, Size.x + Size.y * 0.65, clamp(Flow.x, 0.0, 1.0)) + Size.y * (dome * 0.17 + wave);
}

vec3 scene(vec2 uv, float clarity) {
    uv = clamp(uv, vec2(0.001), vec2(0.999));
    return mix(texture(Sampler0, uv).rgb, texture(Sampler1, uv).rgb, clamp(clarity, 0.0, 1.0));
}

// Three viscous volumes are compressed by the advancing meniscus. The height
// field and pigment share the same moving lobes, so light moves with the liquid.
vec4 fluid(vec2 position) {
    vec2 q = position / max(Size, vec2(1.0));
    float t = Flow.y;
    float push = 0.09 * sin(t * 3.0) * exp(-t * 0.38) * (1.0 - clamp(Flow.x, 0.0, 1.0));
    q.x += 0.052 * sin(q.y * 5.0 - t * 2.0) + push * (1.0 - q.x);
    q.y += 0.10 * sin(q.x * 8.0 + t * 2.0);
    vec2 a = vec2(0.19 + 0.08*sin(t*1.0), 0.48 + 0.19*cos(t*2.0));
    vec2 b = vec2(0.53 + 0.09*cos(t*1.0), 0.43 + 0.22*sin(t*2.0));
    vec2 c = vec2(0.81 + 0.06*sin(t*2.0), 0.54 + 0.20*cos(t*1.0+2.0));
    vec2 stretch = vec2(3.7, 1.45);
    vec2 da=(q-a)*stretch, db=(q-b)*stretch, dc=(q-c)*stretch;
    vec3 mass = exp(-vec3(dot(da,da),dot(db,db),dot(dc,dc))*2.5);
    vec3 weight = pow(mass + vec3(0.015), vec3(2.1));
    weight /= max(dot(weight,vec3(1.0)),0.001);
    vec3 dye = weight.x*vec3(0.357,0.808,0.980) + weight.y*vec3(0.961,0.663,0.722) + weight.z*vec3(1.0);
    float height = 0.20 + 0.60*dot(mass,vec3(1.0)) + 0.08*sin(q.x*10.0-t*3.0)*sin(q.y*3.14159);
    return vec4(dye,height);
}

void main() {
    vec2 halfSize = Size * 0.5;
    vec2 p = localPos - halfSize;
    float distance = roundedBox(p, halfSize, Radius);
    float aa = max(fwidth(distance), 0.001);
    float coverage = smoothstep(0.0, max(Rim.x, aa), -distance + aa * 0.5);
    if (coverage <= 0.0) discard;

    vec2 e = vec2(0.65, 0.0);
    vec2 normal = vec2(roundedBox(p + e.xy, halfSize, Radius) - roundedBox(p - e.xy, halfSize, Radius),
                       roundedBox(p + e.yx, halfSize, Radius) - roundedBox(p - e.yx, halfSize, Radius));
    normal = length(normal) > 0.001 ? normalize(normal) : vec2(0.0, -1.0);
    float edge = clamp(1.0 + distance / max(Glass.y, 1.0), 0.0, 1.0);
    float rimLens = edge * edge * (3.0 - 2.0 * edge);
    rimLens *= rimLens;

    float frontDistance = localPos.x - frontAt(localPos.y);
    float frontAA = max(fwidth(frontDistance), 0.7);
    float filled = 1.0 - smoothstep(-frontAA, frontAA, frontDistance);
    float derivative = frontAt(localPos.y + 0.5) - frontAt(localPos.y - 0.5);
    vec2 frontNormal = normalize(vec2(1.0, -derivative));
    float meniscus = exp(-abs(frontDistance) / max(2.0, Size.y * 0.22)) * filled;
    float dome = max(0.0, 1.0 - pow(p.y / max(halfSize.y, 1.0), 2.0));
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec2 inward = vec2(-normal.x, normal.y) / ScreenSize;
    vec2 frontInward = vec2(-frontNormal.x, frontNormal.y) / ScreenSize;

    // The empty vessel stays softly frosted. Injected liquid is clearer, deeper and more convex.
    vec2 emptyShift = inward * Glass.x * rimLens * 0.72;
    vec2 liquidShift = inward * Glass.x * rimLens * 1.55;
    liquidShift += frontInward * Glass.x * (meniscus * 1.7 + dome * 0.16);
    liquidShift.y += (p.y / max(halfSize.y, 1.0)) * Glass.x * 0.18 * dome / ScreenSize.y;
    // A small, coherent surface wave refracts the scene along with the pigments.
    float wave = sin(localPos.x / max(Size.x, 1.0) * 9.0 - Flow.y * 2.0 + localPos.y / max(Size.y, 1.0) * 2.7);
    liquidShift.y += wave * dome * Glass.x * 0.07 * Palette / ScreenSize.y;
    vec3 emptyGlass = scene(uv + emptyShift, 0.12 + edge * Rim.w * 0.55);
    vec3 liquidGlass = scene(uv + liquidShift, 0.56 + meniscus * 0.27 + edge * 0.12);
    vec3 color = mix(emptyGlass, liquidGlass, filled);

    float glint = exp(-pow(frontDistance / max(1.1, Size.y * 0.05), 2.0)) * dome;
    float whiteLift = vertexColor.a * mix(0.30, 1.35, filled) + glint * 0.10;
    color = mix(color, vec3(1.0), clamp(whiteLift + Rim.z * mix(0.5, 1.0, filled), 0.0, 0.28));
    if (Palette > 0.001) {
        vec4 material=fluid(localPos);
        // Convex liquid surfaces: finite differences follow the actual pigment lobes.
        float dx=(fluid(localPos+vec2(1.0,0.0)).a-fluid(localPos-vec2(1.0,0.0)).a)*Size.x*0.018;
        float dy=(fluid(localPos+vec2(0.0,1.0)).a-fluid(localPos-vec2(0.0,1.0)).a)*Size.y*0.024;
        vec3 n=normalize(vec3(-dx,-dy,0.32));
        vec3 light=normalize(vec3(-0.45,-0.70,1.0));
        float concentration=clamp(Palette,0.0,1.0)*filled;
        vec2 lens=n.xy*Glass.x*0.50/ScreenSize;
        vec3 transmitted=scene(uv+liquidShift+vec2(lens.x,-lens.y),0.88);
        vec3 absorption=exp(-(vec3(1.0)-material.rgb)*(0.8+material.a)*2.0);
        vec3 liquid=mix(transmitted*absorption,material.rgb,0.42+0.14*dome);
        liquid*=0.72+0.24*max(0.0,dot(n,light));
        float specular=pow(max(0.0,dot(n,normalize(light+vec3(0,0,1)))),30.0);
        float edgeReflection=pow(1.0-max(0.0,n.z),3.0)*0.25+rimLens*0.13;
        liquid+=vec3(1.0)*specular*0.65+material.rgb*edgeReflection;
        liquid=mix(liquid,vec3(1.0),clamp(glint*0.35+pow(edge,3.0)*0.18,0.0,0.55));
        color=mix(color,liquid,concentration);
    }
    fragColor = vec4(min(color, vec3(1.0)), coverage) * ColorModulator;
}
