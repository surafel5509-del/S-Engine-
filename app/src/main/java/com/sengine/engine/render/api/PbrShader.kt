package com.sengine.engine.render.api

/** Dialect-independent GLSL functions shared by the ES 2 and ES 3 forward renderers. */
object PbrShader {
    const val uniforms = """
uniform float uPBR;
uniform float uMetallic;
uniform float uRoughness;
uniform float uNormalStrength;
uniform float uAOStrength;
uniform float uAlphaMode;
uniform float uAlphaCutoff;
uniform vec4 uUVTransform;
uniform vec3 uEmissiveColor;
uniform float uEmissiveStrength;
uniform sampler2D uNormalMap;
uniform sampler2D uMRMap;
uniform sampler2D uAOMap;
uniform sampler2D uEmissiveMap;
uniform vec4 uMapFlags;
"""
    const val functions = """
const float PI = 3.14159265359;
vec3 fresnelSchlick(float cosTheta, vec3 f0) {
  return f0 + (1.0-f0)*pow(1.0-cosTheta,5.0);
}
float distributionGGX(vec3 n, vec3 h, float rough) {
  float a=rough*rough; float a2=a*a; float nh=max(dot(n,h),0.0);
  float d=nh*nh*(a2-1.0)+1.0;
  return a2/max(PI*d*d,0.0001);
}
float geometrySchlick(float nv, float rough) {
  float k=(rough+1.0); k=k*k/8.0;
  return nv/max(nv*(1.0-k)+k,0.0001);
}
vec3 brdf(vec3 n, vec3 v, vec3 l, vec3 radiance, vec3 albedo, float metal, float rough) {
  vec3 h=normalize(v+l); float nl=max(dot(n,l),0.0); float nv=max(dot(n,v),0.001);
  vec3 f0=mix(vec3(0.04),albedo,metal); vec3 f=fresnelSchlick(max(dot(h,v),0.0),f0);
  float d=distributionGGX(n,h,rough);
  float g=geometrySchlick(nv,rough)*geometrySchlick(max(dot(n,l),0.001),rough);
  vec3 spec=d*g*f/max(4.0*nv*nl,0.001);
  vec3 kd=(vec3(1.0)-f)*(1.0-metal);
  return (kd*albedo/PI+spec)*radiance*nl;
}
vec3 toneMap(vec3 x) {
  return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0);
}
"""
    const val lighting = """
if (uPBR > 0.5 && uUnlit < 0.5) {
    vec3 n=normalize(vNormal);
    if (!gl_FrontFacing) n=-n;
    if (uMapFlags.x > 0.5) {
      vec3 t=normalize(vTangent-n*dot(n,vTangent));
      vec3 b=normalize(cross(n,t))*vTangentW;
      vec3 map=texture2D(uNormalMap,uv).xyz*2.0-1.0;
      map.xy*=uNormalStrength;
      n=normalize(mat3(t,b,n)*normalize(map));
    }
    vec3 v=normalize(uCamPos-vWorld);
    float metal=clamp(uMetallic,0.0,1.0); float rough=clamp(uRoughness,0.04,1.0);
    if(uMapFlags.y>0.5) { vec2 mr=texture2D(uMRMap,uv).gb; rough*=mr.x; metal*=mr.y; }
    float ao=1.0;
    if(uMapFlags.z>0.5) ao=mix(1.0,texture2D(uAOMap,uv).r,uAOStrength);
    vec3 albedo=pow(max(base.rgb,vec3(0.0)),vec3(2.2));
    col=albedo*uAmbient*ao*0.6;
    col+=brdf(n,v,normalize(-uDirDir),uDirColor*3.0,albedo,metal,rough);
    for(int i=0;i<4;i++) {
      vec3 delta=uPointPos[i].xyz-vWorld; float dist=length(delta);
      float att=pow(clamp(1.0-dist/max(uPointPos[i].w,0.001),0.0,1.0),2.0)/max(dist*dist*0.15,1.0);
      col+=brdf(n,v,delta/max(dist,0.001),uPointColor[i]*att*4.0,albedo,metal,rough);
    }
    vec3 emission=uEmissiveColor*uEmissiveStrength;
    if(uMapFlags.w>0.5) emission*=pow(texture2D(uEmissiveMap,uv).rgb,vec3(2.2));
    col+=emission;
    col=pow(toneMap(col),vec3(1.0/2.2));
}
"""
    fun skinDeclarations(es3: Boolean) = if (!es3) "" else """
uniform mat4 uBones[32];
uniform float uSkinned;
${if(es3) "in" else "attribute"} vec4 aJoints;
${if(es3) "in" else "attribute"} vec4 aWeights;
"""
}
