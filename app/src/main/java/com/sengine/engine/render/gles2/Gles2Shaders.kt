package com.sengine.engine.render.gles2

import com.sengine.engine.render.api.ShaderDialect
import com.sengine.engine.render.api.ShaderSourceSet
import com.sengine.engine.render.api.PbrShader

/**
 * GLSL ES 1.00 built-in shaders for the OpenGL ES 2.0 compatibility backend.
 * This is the original renderer source, moved here unchanged. Do not use GLSL ES 3 syntax in this file.
 */
object Gles2Shaders : ShaderSourceSet {
    override val dialect = ShaderDialect.GLSL_100_ES

    override val spriteVs = """
uniform mat4 uMVP;
uniform vec4 uUV;
attribute vec2 aPos;
varying vec2 vP;
varying vec2 vUV;
void main() {
  vP = aPos;
  vec2 t = aPos + 0.5;
  vUV = vec2(mix(uUV.x, uUV.z, t.x), mix(uUV.y, uUV.w, t.y));
  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
"""

    override fun spriteFs(effect: String) = """
precision mediump float;
varying vec2 vP;
varying vec2 vUV;
uniform vec4 uColor;
uniform sampler2D uTex;
uniform float uUseTex;
uniform float uShape;
uniform float uAA;
uniform float uTime;
uniform float uParam;
uniform vec2 uResolution;
$effect
void main() {
  float a = 1.0;
  if (uShape > 0.5 && uShape < 1.5) {
    a = clamp((0.5 - length(vP)) * uAA, 0.0, 1.0);
  } else if (uShape > 1.5 && uShape < 2.5) {
    float w = (0.5 - vP.y) * 0.5;
    float e = min(w - abs(vP.x), vP.y + 0.5);
    a = clamp(e * uAA, 0.0, 1.0);
  } else if (uShape > 2.5) {
    float d = length(vP);
    a = clamp((0.5 - d) * uAA, 0.0, 1.0) * clamp((d - 0.40) * uAA, 0.0, 1.0);
  }
  vec4 c = uColor;
  if (uUseTex > 0.5) c *= texture2D(uTex, vUV);
  c.a *= a;
  gl_FragColor = effect(c, vUV);
}
"""

    override val supportsGpuSkin = false
    private fun meshVertex(skinned: Boolean) = """
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat4 uNormalMat;
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aUV;
attribute vec4 aTangent;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUV;
varying vec3 vTangent;
varying float vTangentW;
""" + (if(skinned && false) """uniform mat4 uBones[32];
attribute vec4 aJoints;
attribute vec4 aWeights;
""" else "") + """
void main() {
  vec4 p=vec4(aPos,1.0); vec3 n=aNormal; vec3 t=aTangent.xyz;
""" + (if(skinned && false) """
  mat4 skin=uBones[int(aJoints.x)]*aWeights.x+uBones[int(aJoints.y)]*aWeights.y+uBones[int(aJoints.z)]*aWeights.z+uBones[int(aJoints.w)]*aWeights.w;
  p=skin*p; n=mat3(skin)*n; t=mat3(skin)*t;
""" else "") + """
  vWorld=(uModel*p).xyz;
  vNormal=normalize((uNormalMat*vec4(n,0.0)).xyz);
  vTangent=normalize((uModel*vec4(t,0.0)).xyz); vTangentW=aTangent.w;
  vUV=aUV; gl_Position=uMVP*p;
}
"""
    override val meshVs get() = meshVertex(false)
    override val skinnedMeshVs get() = meshVertex(true)

    override fun meshFs(effect: String) = """
precision highp float;
varying vec3 vTangent;
varying float vTangentW;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUV;
${PbrShader.uniforms}
uniform vec4 uColor;
uniform sampler2D uTex;
uniform float uUseTex;
uniform float uTiling;
uniform vec3 uCamPos;
uniform vec3 uAmbient;
uniform vec3 uDirDir;
uniform vec3 uDirColor;
uniform vec4 uPointPos[4];
uniform vec3 uPointColor[4];
uniform float uSpec;
uniform float uShine;
uniform float uEmission;
uniform float uUnlit;
uniform vec3 uFogColor;
uniform vec3 uFog;
uniform float uTime;
uniform float uParam;
uniform vec2 uResolution;
${PbrShader.functions}
$effect
void main() {
  vec4 base = uColor;
  vec2 uv = vUV * uTiling;
  if(uPBR>0.5) uv=vUV*uUVTransform.xy+uUVTransform.zw;
  if (uUseTex > 0.5) base *= texture2D(uTex, uv);
  if(uAlphaMode>0.5 && uAlphaMode<1.5 && base.a<uAlphaCutoff) discard;
  vec3 col = base.rgb;
  if (uUnlit < 0.5 && uPBR < 0.5) {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(uCamPos - vWorld);
    vec3 l = normalize(-uDirDir);
    float diff = max(dot(n, l), 0.0);
    vec3 h = normalize(l + v);
    float spec = pow(max(dot(n, h), 0.0), uShine) * uSpec;
    vec3 light = uAmbient + uDirColor * diff;
    vec3 specular = uDirColor * spec * step(0.0001, diff);
    for (int i = 0; i < 4; i++) {
      vec3 d = uPointPos[i].xyz - vWorld;
      float dist = length(d);
      float att = clamp(1.0 - dist / max(uPointPos[i].w, 0.001), 0.0, 1.0);
      att *= att;
      vec3 pl = d / max(dist, 0.0001);
      float pd = max(dot(n, pl), 0.0);
      light += uPointColor[i] * pd * att;
      specular += uPointColor[i] * pow(max(dot(n, normalize(pl + v)), 0.0), uShine) * uSpec * att * step(0.0001, pd);
    }
    col = col * light + specular;
  }
  if(uPBR>0.5) {
    ${PbrShader.lighting}
  } else col += base.rgb * uEmission;
  if (uFog.z > 0.5) {
    float f = clamp((length(uCamPos - vWorld) - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    col = mix(col, uFogColor, f);
  }
  gl_FragColor = effect(vec4(col, base.a), uv);
}
"""

    override val postVs = """
attribute vec2 aPos;
varying vec2 vUV;
void main() { vUV = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    override fun postFs(effect: String) = """
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform float uTime;
uniform float uParam;
uniform vec2 uResolution;
$effect
void main() { gl_FragColor = effect(texture2D(uTex, vUV), vUV); }
"""

    override val lineVs = """
uniform mat4 uMVP;
attribute vec3 aPos;
attribute vec4 aColor;
varying vec4 vColor;
void main() { vColor = aColor; gl_Position = uMVP * vec4(aPos, 1.0); }
"""

    override val lineFs = """
precision mediump float;
varying vec4 vColor;
void main() { gl_FragColor = vColor; }
"""

    override val skyVs = """
attribute vec2 aPos;
varying vec2 vNdc;
void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.9999, 1.0); }
"""

    override val skyFs = """
precision mediump float;
varying vec2 vNdc;
uniform mat4 uInvVP;
uniform vec3 uTop;
uniform vec3 uHorizon;
uniform vec3 uGround;
void main() {
  vec4 a = uInvVP * vec4(vNdc, -1.0, 1.0);
  vec4 b = uInvVP * vec4(vNdc, 1.0, 1.0);
  vec3 d = normalize(b.xyz / b.w - a.xyz / a.w);
  vec3 c;
  if (d.y >= 0.0) c = mix(uHorizon, uTop, pow(d.y, 0.6));
  else c = mix(uHorizon, uGround, clamp(-d.y * 4.0, 0.0, 1.0));
  gl_FragColor = vec4(c, 1.0);
}
"""

    override val lightVs = """
uniform mat4 uViewProj;
uniform vec2 uCenter;
uniform vec2 uSize;
attribute vec2 aPos;
varying vec2 vWorld;
void main() {
  vWorld = uCenter + aPos * uSize;
  gl_Position = uViewProj * vec4(vWorld, 0.0, 1.0);
}
"""

    override val lightFs = """
precision mediump float;
varying vec2 vWorld;
uniform vec2 uLightPos;
uniform vec4 uColor;
uniform float uRadius;
uniform float uInner;
uniform float uFalloff;
uniform vec2 uSpotDir;
uniform float uSpotCos;
uniform float uSpotSoft;
uniform float uKind;
uniform float uUseTex;
uniform sampler2D uTex;
void main() {
  float r = max(uRadius, 0.001);
  float atten = 1.0;
  if (uKind < 1.5) {
    float dist = length(vWorld - uLightPos);
    atten = clamp(1.0 - (dist - uInner) / max(r - uInner, 0.001), 0.0, 1.0);
    atten = pow(atten, max(uFalloff, 0.05));
  } else if (uKind < 2.5) {
    vec2 d = vWorld - uLightPos;
    float dist = length(d);
    atten = clamp(1.0 - (dist - uInner) / max(r - uInner, 0.001), 0.0, 1.0);
    atten = pow(atten, max(uFalloff, 0.05));
    float c = dot(d / max(dist, 0.0001), uSpotDir);
    atten *= smoothstep(uSpotCos - uSpotSoft, uSpotCos + uSpotSoft, c);
  }
  vec3 col = uColor.rgb * atten;
  if (uUseTex > 0.5) col *= texture2D(uTex, (vWorld - uLightPos) / (2.0 * r) + 0.5).rgb;
  gl_FragColor = vec4(col, 1.0);
}
"""
}
