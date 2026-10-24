package com.ldmaster.sinuca.render

/**
 * Every GLSL program used by the game. Keeping them out of GameRenderer makes
 * the renderer readable and lets the shader contract be reviewed in one place.
 *
 * Colour convention: the scene pass writes **linear** radiance and never
 * applies gamma. ACES tone mapping and the sRGB encode happen once, in the
 * composite pass. Data maps (height/roughness/AO) are sampled without a
 * pow(2.2) decode; colour textures are uploaded as sRGB.
 */
object Shaders {

    const val SCENE_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec2 aUv;
layout(location = 4) in vec3 aMaterial;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vNormal;
out vec3 vColor;
out vec3 vLocal;
out vec3 vWorld;
out vec2 vUv;
out vec3 vMaterial;
void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorld = world.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor;
    vLocal = aPos;
    vUv = aUv;
    vMaterial = aMaterial;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val SCENE_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;

in vec3 vNormal;
in vec3 vColor;
in vec3 vLocal;
in vec3 vWorld;
in vec2 vUv;
in vec3 vMaterial;

uniform vec3 uColor;
uniform float uUseColor;
uniform float uUseTexture;
uniform float uStyle;
uniform float uUseLiteral;
uniform float uUsePattern;
uniform float uDetailScale;
uniform float uDetailBase;
uniform sampler2D uTexture;
uniform sampler2D uDetailMap;
uniform sampler2D uShadowMap;
uniform mat4 uLightVP;
uniform vec3 uEye;
uniform vec3 uLightDir;
uniform float uShadowTexel;
uniform float uShadowStrength;

out vec4 fragColor;

const float PI = 3.14159265359;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 5; i++) {
        value += amplitude * vnoise(p);
        p *= 2.03;
        amplitude *= 0.5;
    }
    return value;
}

vec3 aces(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

vec3 envColor(vec3 dir) {
    float height = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 sky = mix(vec3(0.010, 0.014, 0.022), vec3(0.085, 0.105, 0.145), pow(height, 1.4));
    sky += vec3(0.055, 0.045, 0.035) * pow(1.0 - height, 3.0);
    float box = max(dot(dir, normalize(vec3(0.38, 0.82, 0.42))), 0.0);
    sky += vec3(1.0, 0.94, 0.82) * pow(box, 40.0) * 6.0;
    float strip = max(dot(dir, normalize(vec3(-0.72, 0.34, 0.60))), 0.0);
    sky += vec3(0.45, 0.55, 0.75) * pow(strip, 18.0) * 1.4;
    return sky;
}

float shadowing(vec3 world, vec3 n, float NoL) {
    if (uShadowStrength < 0.001) return 1.0;
    vec3 offset = world + n * (uShadowTexel * 2.0);
    vec4 lightSpace = uLightVP * vec4(offset, 1.0);
    vec3 p = lightSpace.xyz / lightSpace.w * 0.5 + 0.5;
    if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0 || p.z > 1.0) return 1.0;
    float bias = 0.0010 + 0.0026 * (1.0 - NoL);
    float sum = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            float depth = texture(
                uShadowMap,
                p.xy + vec2(float(x), float(y)) * uShadowTexel * 3.0
            ).r;
            sum += (p.z - bias <= depth) ? 1.0 : 0.0;
        }
    }
    float edge = smoothstep(0.0, 0.12, p.x) * smoothstep(1.0, 0.88, p.x)
               * smoothstep(0.0, 0.12, p.y) * smoothstep(1.0, 0.88, p.y);
    return mix(1.0, mix(1.0, sum / 25.0, uShadowStrength), edge);
}

vec2 surfaceUv(vec3 p, vec3 n) {
    vec3 an = abs(n);
    if (an.y > 0.6) return vec2(p.x, p.z);
    if (an.z > an.x) return vec2(p.x, p.y);
    return vec2(p.z, p.y);
}

struct WoodStyle {
    float contrast;
    float bands;
    float poreAmount;
    float macroAmount;
    float scratchAmount;
    float lacquer;
    float tint;
};

WoodStyle woodStyle(float style) {
    WoodStyle s;
    if (style < 0.5) {          // scan fidelity
        s.contrast = 0.66; s.bands = 44.0; s.poreAmount = 0.26;
        s.macroAmount = 0.16; s.scratchAmount = 0.0; s.lacquer = 0.55; s.tint = 1.00;
    } else if (style < 1.5) {   // cinematic wear
        s.contrast = 0.80; s.bands = 34.0; s.poreAmount = 0.36;
        s.macroAmount = 0.30; s.scratchAmount = 1.0; s.lacquer = 0.90; s.tint = 0.86;
    } else if (style < 2.5) {   // clean premium
        s.contrast = 0.38; s.bands = 28.0; s.poreAmount = 0.06;
        s.macroAmount = 0.08; s.scratchAmount = 0.0; s.lacquer = 0.30; s.tint = 1.14;
    } else if (style < 3.5) {   // procedural infinite
        s.contrast = 0.88; s.bands = 60.0; s.poreAmount = 0.20;
        s.macroAmount = 0.90; s.scratchAmount = 0.0; s.lacquer = 0.50; s.tint = 1.00;
    } else {                    // mobile atlas
        s.contrast = 0.70; s.bands = 40.0; s.poreAmount = 0.28;
        s.macroAmount = 0.22; s.scratchAmount = 0.15; s.lacquer = 0.60; s.tint = 1.02;
    }
    return s;
}

vec3 woodSurface(vec3 p, vec3 n, vec3 base, float style, inout float rough) {
    WoodStyle s = woodStyle(style);
    vec2 uv = surfaceUv(p, n);

    float warp = fbm(uv * vec2(1.3, 4.0));
    float phase = uv.y * s.bands + warp * 4.0;
    float band = 0.5 + 0.5 * sin(phase * 6.2831853);
    float fine = fbm(vec2(uv.x * 1.4, uv.y * 26.0));

    float poreCoord = uv.y * 150.0;
    float poreFade = clamp(1.0 - fwidth(poreCoord) * 0.6, 0.0, 1.0);
    float pores = mix(0.5, vnoise(vec2(uv.x * 28.0, poreCoord)), poreFade);

    float macro = (fbm(uv * 0.35) - 0.5) * s.macroAmount * 2.0;
    float scratches = s.scratchAmount *
        smoothstep(0.58, 0.86, fbm(vec2(uv.x * 0.9, uv.y * 24.0)));

    vec3 dark = base * (1.0 - s.contrast * 0.85);
    vec3 light = base * (1.0 + s.contrast * 1.05);
    vec3 color = mix(dark, light, clamp(band * 0.70 + fine * 0.30 + macro, 0.0, 1.0));
    color *= 1.0 - s.poreAmount * (pores - 0.5);
    color += vec3(0.08, 0.075, 0.065) * scratches;
    color *= s.tint;

    rough = clamp(rough * s.lacquer + 0.10 * (1.0 - band) + 0.05 * pores, 0.06, 0.80);
    return color;
}

vec3 clothSurface(vec3 p, vec3 n, vec3 base, float style, inout float rough) {
    vec2 uv = surfaceUv(p, n);
    float weaveCoord = (uv.x + uv.y) * 900.0;
    float fade = clamp(1.0 - fwidth(weaveCoord) * 0.7, 0.0, 1.0);
    float weave = mix(0.5, vnoise(uv * 900.0), fade);
    float pile = fbm(uv * 22.0);

    float tint = 1.0;
    float chalk = 0.0;
    if (style > 0.5 && style < 1.5) {
        tint = 1.10;
        chalk = smoothstep(0.62, 0.88, fbm(vec2(uv.x * 0.7, uv.y * 30.0))) * 0.12;
    } else if (style > 2.5 && style < 3.5) {
        tint = 0.97;
    } else if (style > 3.5) {
        tint = 1.03;
    }
    rough = 0.94;
    return base * tint * (0.94 + 0.10 * pile + 0.06 * (weave - 0.5) * fade) + vec3(chalk);
}

vec3 metalSurface(vec3 p, vec3 n, vec3 base, float style, inout float rough) {
    vec2 uv = surfaceUv(p, n);
    float brushCoord = uv.y * 380.0;
    float fade = clamp(1.0 - fwidth(brushCoord) * 0.8, 0.0, 1.0);
    float brush = mix(0.5, fbm(vec2(uv.x * 2.5, brushCoord)), fade);

    float brushAmount = 0.18;
    float patina = 0.0;
    float polished = 0.0;
    if (style < 0.5) {
        brushAmount = 0.18;
    } else if (style < 1.5) {
        brushAmount = 0.32; patina = 0.40;
    } else if (style < 2.5) {
        brushAmount = 0.06; polished = 1.0;
    } else if (style < 3.5) {
        brushAmount = 0.22; patina = 0.70;
    } else {
        brushAmount = 0.15; patina = 0.12;
    }

    float macro = (fbm(uv * 1.2 + p.y * 0.8) - 0.5) * patina;
    vec3 color = base * (1.0 + brushAmount * (brush - 0.5) * 2.0 + macro);
    rough = clamp(rough * mix(1.0, 0.55, polished) * (0.90 + 0.35 * brush), 0.04, 0.95);
    return color;
}

vec3 ceramicSurface(vec3 p, vec3 n, vec3 base, float style, inout float rough) {
    float speck = fbm(p.xz * 34.0) * fbm(p.yx * 34.0);
    float speckAmount = 0.06;
    float macroAmount = 0.02;
    float gloss = 1.0;
    if (style > 0.5 && style < 1.5) {
        speckAmount = 0.18; macroAmount = 0.08; gloss = 1.40;
    } else if (style > 1.5 && style < 2.5) {
        speckAmount = 0.02; gloss = 0.60;
    } else if (style > 2.5 && style < 3.5) {
        speckAmount = 0.10; macroAmount = 0.14;
    } else if (style > 3.5) {
        speckAmount = 0.08;
    }
    vec3 color = base * (1.0 + speckAmount * (speck - 0.5) * 2.0
                        + macroAmount * (fbm(p.xz * 0.8) - 0.5) * 2.0);
    rough = clamp(rough * gloss, 0.06, 0.90);
    return color;
}

void main() {
    vec3 n = normalize(vNormal);
    vec3 p = vWorld;

    int surfaceClass = int(vMaterial.z + 0.5);
    float metallic = clamp(vMaterial.x, 0.0, 1.0);
    float roughness = clamp(vMaterial.y, 0.04, 1.0);
    float ao = 1.0;
    vec3 albedo;

    if (uUseTexture > 0.5) {
        albedo = texture(uTexture, vUv).rgb;
        metallic = 0.0;
        roughness = 0.14;
    } else {
        albedo = mix(vColor, uColor, uUseColor);
        if (surfaceClass == 0) {
            albedo = woodSurface(p, n, albedo, uStyle, roughness);
        } else if (surfaceClass == 1) {
            albedo = metalSurface(p, n, albedo, uStyle, roughness);
            metallic = max(metallic, 0.85);
        } else if (surfaceClass == 2) {
            albedo = clothSurface(p, n, albedo, uStyle, roughness);
            metallic = 0.0;
        } else if (surfaceClass == 3) {
            albedo = ceramicSurface(p, n, albedo, uStyle, roughness);
        } else if (surfaceClass == 4) {
            roughness = clamp(roughness, 0.7, 0.95);
            if (abs(n.y) > 0.5) {
                float radial = length(p.xz);
                albedo *= mix(0.95, 0.30, smoothstep(1.0, 5.5, radial));
            } else {
                albedo *= 0.42 + 0.78 * clamp((p.y + 0.4) / 3.6, 0.0, 1.0);
            }
        }
    }

    if (uUseLiteral > 0.5 && uDetailBase > 0.001) {
        vec3 blend = abs(n);
        blend /= max(blend.x + blend.y + blend.z, 1e-4);
        vec3 q = p * (uDetailScale * 6.0);
        vec3 detail = texture(uDetailMap, q.zy).rgb * blend.x
                    + texture(uDetailMap, q.xz).rgb * blend.y
                    + texture(uDetailMap, q.xy).rgb * blend.z;
        ao *= mix(1.0, detail.b, 0.55 * uDetailBase);
        if (surfaceClass != 2) {
            float mapped = clamp(detail.g * 1.6 + 0.15, 0.05, 1.0);
            roughness = clamp(mix(roughness, mapped, 0.30 * uDetailBase), 0.05, 1.0);
        }
    }

    if (uUsePattern > 0.5 && surfaceClass == 0) {
        float macro = fbm(p.xz * 0.9);
        albedo *= 0.90 + 0.20 * macro;
    }

    vec3 v = normalize(uEye - p);
    vec3 l = normalize(uLightDir);
    vec3 h = normalize(l + v);
    float NoL = max(dot(n, l), 0.0);
    float NoV = max(dot(n, v), 1e-4);
    float NoH = max(dot(n, h), 0.0);
    float VoH = max(dot(v, h), 0.0);

    float alpha = max(roughness * roughness, 1e-4);
    float alpha2 = alpha * alpha;
    float denom = NoH * NoH * (alpha2 - 1.0) + 1.0;
    float distribution = alpha2 / max(PI * denom * denom, 1e-6);
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float geometry = (NoV / (NoV * (1.0 - k) + k)) * (NoL / (NoL * (1.0 - k) + k));
    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - VoH, 5.0);
    vec3 specular = distribution * geometry * fresnel / max(4.0 * NoV * NoL, 1e-4);

    vec3 kd = (1.0 - fresnel) * (1.0 - metallic);
    float shadow = shadowing(p, n, NoL);
    vec3 radiance = vec3(1.0, 0.955, 0.90) * 3.6;
    vec3 direct = (kd * albedo / PI + specular) * radiance * NoL * shadow;

    vec3 reflection = reflect(-v, n);
    vec3 envSpecular = envColor(reflection) * f0;
    vec3 irradiance = envColor(n) * 0.40;
    vec3 ambient = (kd * albedo * irradiance + envSpecular * (1.0 - 0.65 * roughness)) * ao;

    float grazing = pow(1.0 - NoV, 4.0);
    ambient += envColor(reflection) * grazing * (0.30 + 0.70 * metallic) *
               (0.18 + 0.55 * (1.0 - roughness)) * ao;

    vec3 color = direct + ambient;

    if (surfaceClass == 4 && abs(n.y) > 0.5) {
        color *= 1.0 / (1.0 + 0.055 * dot(p.xz, p.xz));
    }

    vec3 fillDirection = normalize(vec3(-0.55, 0.38, -0.72));
    float fill = max(dot(n, fillDirection), 0.0);
    color += kd * albedo * fill * vec3(0.34, 0.40, 0.52) * ao * 0.85;

    if (surfaceClass == 2) {
        color += albedo * pow(1.0 - NoV, 3.0) * 0.20 * (0.35 + 0.65 * shadow);
    } else if (surfaceClass == 5) {
        color += albedo * 0.30;
    }

    fragColor = vec4(max(color, vec3(0.0)), 1.0);
}
"""

    const val DEPTH_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val DEPTH_FRAGMENT = """#version 300 es
precision mediump float;
void main() {
}
"""

    const val QUAD_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val BRIGHT_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform float uThreshold;
out vec4 fragColor;
void main() {
    vec3 color = texture(uScene, vUv).rgb;
    float brightness = max(color.r, max(color.g, color.b));
    float knee = uThreshold * 0.6 + 1e-4;
    float soft = clamp(brightness - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee);
    float contribution = max(soft, brightness - uThreshold) / max(brightness, 1e-4);
    fragColor = vec4(color * contribution, 1.0);
}
"""

    const val BLUR_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uImage;
uniform vec2 uDirection;
out vec4 fragColor;
void main() {
    vec3 sum = texture(uImage, vUv).rgb * 0.227027;
    sum += (texture(uImage, vUv + uDirection * 1.384615).rgb +
            texture(uImage, vUv - uDirection * 1.384615).rgb) * 0.316216;
    sum += (texture(uImage, vUv + uDirection * 3.230769).rgb +
            texture(uImage, vUv - uDirection * 3.230769).rgb) * 0.070270;
    fragColor = vec4(sum, 1.0);
}
"""

    const val COMPOSITE_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uBloom0;
uniform sampler2D uBloom1;
uniform sampler2D uBloom2;
uniform float uBloomStrength;
uniform float uExposure;
uniform float uVignette;
uniform float uGrain;
uniform float uTime;
out vec4 fragColor;

vec3 aces(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 color = texture(uScene, vUv).rgb;
    vec3 bloom = texture(uBloom0, vUv).rgb * 0.45
               + texture(uBloom1, vUv).rgb * 0.33
               + texture(uBloom2, vUv).rgb * 0.22;
    color += bloom * uBloomStrength;
    color *= uExposure;
    color = aces(color);

    vec2 centered = vUv - 0.5;
    float vignette = 1.0 - uVignette * dot(centered, centered) * 1.6;
    color *= clamp(vignette, 0.0, 1.0);

    float noise = hash(vUv * 1024.0 + uTime) - 0.5;
    color += noise * uGrain;

    color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
    fragColor = vec4(color, 1.0);
}
"""

    const val COPY_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uImage;
out vec4 fragColor;
void main() {
    vec3 color = texture(uImage, vUv).rgb;
    fragColor = vec4(pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
"""

    const val LINE_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val LINE_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
"""
}
