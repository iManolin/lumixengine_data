$input v_wpos, v_view, v_normal, v_tangent, v_bitangent, v_texcoord0, v_texcoord1 // in...

/*
 * Copyright 2011-2015 Branimir Karadzic. All rights reserved.
 * License: http://www.opensource.org/licenses/BSD-2-Clause
 */

#include "common.sh"

SAMPLER2D(u_texColor0, 1);
SAMPLER2D(u_texColor1, 2);
SAMPLER2D(u_texColor2, 3);
SAMPLER2D(u_texColor3, 4);
SAMPLER2D(u_splatmap, 5);

SAMPLER2D(u_normalmap0, 6);
SAMPLER2D(u_normalmap1, 7);
SAMPLER2D(u_normalmap2, 8);
SAMPLER2D(u_normalmap3, 9);
SAMPLER2D(u_shadowmap, 10);
uniform vec4 u_lightPosRadius;
uniform vec4 u_lightRgbInnerR;
uniform vec4 u_ambientColor;
uniform vec4 u_lightDirFov; 
uniform mat4 u_shadowmapMatrices[4];
uniform vec4 u_fogColorDensity; 

float getFogFactor(float fFogCoord) 
{ 
	float fResult = exp(-pow(u_fogColorDensity.w * fFogCoord, 2.0)); 
	fResult = 1.0-clamp(fResult, 0.0, 1.0); 
	return fResult;
}


vec2 blinn(vec3 _lightDir, vec3 _normal, vec3 _viewDir)
{
	float ndotl = dot(_normal, _lightDir);
	vec3 reflected = _lightDir - 2.0*ndotl*_normal; // reflect(_lightDir, _normal);
	float rdotv = dot(reflected, _viewDir);
	return vec2(ndotl, rdotv);
}

float fresnel(float _ndotl, float _bias, float _pow)
{
	float facing = (1.0 - _ndotl);
	return max(_bias + (1.0 - _bias) * pow(facing, _pow), 0.0);
}

vec4 lit(float _ndotl, float _rdotv, float _m)
{
	float diff = max(0.0, _ndotl);
	float spec = step(0.0, _ndotl) * max(0.0, _rdotv * _m);
	return vec4(1.0, diff, spec, 1.0);
}

vec4 powRgba(vec4 _rgba, float _pow)
{
	vec4 result;
	result.xyz = pow(_rgba.xyz, vec3_splat(_pow) );
	result.w = _rgba.w;
	return result;
}

vec3 calcLight(mat3 _tbn, vec3 _wpos, vec3 _normal, vec3 _view)
{
	vec3 lp = u_lightPosRadius.xyz - _wpos;
	float attn = 1.0 - smoothstep(u_lightRgbInnerR.w, 1.0, length(lp) / u_lightPosRadius.w);
	vec3 lightDir = normalize(lp);
	vec2 bln = blinn(lightDir, _normal, _view);
	vec4 lc = lit(bln.x, bln.y, 1.0);
	vec3 rgb = u_lightRgbInnerR.xyz * saturate(lc.y) * attn;
	return rgb;
}

vec3 calcGlobalLight(vec3 _light_color, vec3 _normal)
{
	return max(0.0, dot(u_lightDirFov.xyz, -_normal)) * _light_color;	
}

float VSM(sampler2D depths, vec2 uv, float compare)
{
	return smoothstep(compare-0.0001, compare, texture2D(depths, uv).x);
}

float getShadowmapValue(vec4 position)
{
	vec3 shadow_coord[4];
	shadow_coord[0] = vec3(mul(u_shadowmapMatrices[0], position));
	shadow_coord[1] = vec3(mul(u_shadowmapMatrices[1], position));
	shadow_coord[2] = vec3(mul(u_shadowmapMatrices[2], position));
	shadow_coord[3] = vec3(mul(u_shadowmapMatrices[3], position));

	vec2 tt[4];
	tt[0] = vec2(shadow_coord[0].x * 0.5, 0.50 + shadow_coord[0].y * 0.5);
	tt[1] = vec2(0.5 + shadow_coord[1].x * 0.5, 0.50 + shadow_coord[1].y * 0.5);
	tt[2] = vec2(shadow_coord[2].x * 0.5, shadow_coord[2].y * 0.5);
	tt[3] = vec2(0.5 + shadow_coord[3].x * 0.5, shadow_coord[3].y * 0.5);

	int split_index = 3;
	if(step(shadow_coord[0].x, 0.99) * step(shadow_coord[0].y, 0.99)
		* step(0.01, shadow_coord[0].x)	* step(0.01, shadow_coord[0].y) > 0.0)
		split_index = 0;
	else if(step(shadow_coord[1].x, 0.99) * step(shadow_coord[1].y, 0.99)
		* step(0.01, shadow_coord[1].x)	* step(0.01, shadow_coord[1].y) > 0.0)
		split_index = 1;
	else if(step(shadow_coord[2].x, 0.99) * step(shadow_coord[2].y, 0.99)
		* step(0.01, shadow_coord[2].x)	* step(0.01, shadow_coord[2].y) > 0.0)
		split_index = 2;
	
	return step(shadow_coord[split_index].z, 1) * VSM(u_shadowmap, tt[split_index], shadow_coord[split_index].z);
}

void main()
{
	mat3 tbn = mat3(
				normalize(v_tangent),
				normalize(v_bitangent),
				normalize(v_normal)
				);

	vec3 normal;
	#ifdef NORMAL_MAPPING
		normal.xy = texture2D(u_texNormal, v_texcoord0).xy * 2.0 - 1.0;
		normal.z = sqrt(1.0 - dot(normal.xy, normal.xy) );
	#else
		normal = vec3(0.0, 0.0, 1.0);
	#endif
	vec3 view = -normalize(v_view);

    vec4 splat = texture2D(u_splatmap, v_texcoord1).rgba;
	vec4 color =                                          
		texture2D(u_texColor0, v_texcoord0).rgba * splat.x
		+ texture2D(u_texColor1, v_texcoord0).rgba * splat.y
		+ texture2D(u_texColor2, v_texcoord0).rgba * splat.z
		+ texture2D(u_texColor3, v_texcoord0).rgba * splat.w;
					
//	vec4 color = /*toLinear*/(texture2D(u_texColor0, v_texcoord0) );
				 
	vec3 diffuse;
	#ifdef POINT_LIGHT
		diffuse = calcLight(tbn, v_wpos, mul(tbn, normal), view);
	#else
		diffuse = u_lightRgbInnerR.rgb; //calcGlobalLight(u_lightRgbInnerR.rgb, mul(tbn, normal));
	#endif
	diffuse = diffuse.xyz * color.rgb;
	diffuse = diffuse * getShadowmapValue(vec4(v_wpos, 1.0)); 

	#ifdef MAIN
		vec3 ambient = u_ambientColor.rgb * color.rgb;
	#else
		vec3 ambient = vec3(0, 0, 0);
	#endif  

    vec4 view_pos = mul(u_view, vec4(v_wpos, 1.0));
    float fog_factor = getFogFactor(view_pos.z / view_pos.w);
    gl_FragColor.xyz = mix(diffuse + ambient, u_fogColorDensity.rgb, fog_factor);
	gl_FragColor.w = 1.0;
	
	//gl_FragColor = toGamma(gl_FragColor);
}