public module nort_compose;
import "nort/types.slang";
import "nort/inputs.slang";
import "nort/geometry.slang";
import "nort/materials.slang";
import "nort/traversal.slang";
import "nort/lighting.slang";
import "nort/history.slang";

extension<S : INoRtScene, M : INoRtMaterials, R : INoRtRaster> NoRtRenderer<S,M,R> {
    void render(uint3 dispatchThreadID) {
        int2 pixel=int2(dispatchThreadID.xy); uint2 outputSize; outputImage.GetDimensions(outputSize.x,outputSize.y); int2 resolution=int2(outputSize);
        if (any((pixel>=resolution))) return;
        float2 uv=(float2(pixel)+0.5)/float2(resolution)*2.0-1.0;
        uv.x*=float(resolution.x)/float(resolution.y);
        float3 origin=float3(38,24,43), forward=normalize(float3(16,5,16)-origin);
        float3 right=normalize(cross(forward,float3(0,1,0))), up=cross(right,forward);
        float3 ray=normalize(forward+0.62*uv.x*right-0.62*uv.y*up);
        bool live=(diagnostic.control.x&2u)!=0u;
        float2 ndc=(float2(pixel)+0.5)/float2(resolution)*2.0-1.0;
        if (live) {
            float4 point=mul(diagnostic.inverseViewProjection,float4(ndc,0.5,1.0));
            if (abs(point.w)<1e-8 || any(isnan(point)) || any(isinf(point))) {
                storeHistory(pixel,uint4(0));
                outputImage[pixel]=float4(1,0,1,1); return;
            }
            origin=diagnostic.cameraOrigin.xyz+diagnostic.rayOriginOffset.xyz;
            float3 direction=point.xyz/point.w-diagnostic.rayOriginOffset.xyz;
            float square=dot(direction,direction);
            if (any(isnan(origin)) || any(isinf(origin)) || square<1e-16 || isnan(square) || isinf(square)) {
                storeHistory(pixel,uint4(0));
                outputImage[pixel]=float4(1,0,1,1); return;
            }
            ray=direction*rsqrt(square);
        }
        Hit hit=trace(origin,ray,0.0,sceneDistance());
        if ((diagnostic.control.x&1u)!=0u) {
            storeHistory(pixel,uint4(0));
            uint id=live ? hit.material&255u : hit.material;
            outputImage[pixel]=hit.found ? float4(float3(hit.cell),float(id))/255.0 : float4(0); return;
        }
        float3 color=lerp(float3(0.40,0.57,0.74),float3(0.09,0.16,0.28),clamp(ray.y*0.5+0.5,0.0,1.0));
        float3 p=origin+hit.t*ray;
        bool worldLit=live && textures.available() && (diagnostic.control.x&8u)!=0u;
        uint seed=pcg(uint(pixel.x)+pcg(uint(pixel.y)+pcg(diagnostic.control.z)));
        // A translucent primary seen from inside a model cell (e.g. the camera under water) keeps raster.
        uint cameraCell=cellValue(int3(floor(origin)));
        bool insideModel=cameraCell!=0xffffffffu && (cameraCell&255u)==3u;
        bool translucent=(hit.flags&FLAG_TRANSLUCENT)!=0u;
        bool shadeable=hit.found && (hit.flags&FLAG_NO_MATERIAL)==0u
        && (!translucent || worldLit && !insideModel && translucentOpticsEnabled());
        // Decide composition first: shading is spent only on pixels whose raster surface it replaces.
        bool replace=false;
        float weight=0.0;
        float3 raster=float3(0);
        if (rasterInputs.available() && live && diagnostic.control.y==0u) {
            float3 original=rasterInputs.color(pixel);
            raster=(diagnostic.control.x&16u)!=0u ? original : srgbToLinear(original);
            float depth=rasterInputs.depth(pixel);
            replace=shadeable && depth>0.0 && depth<=1.0;
            if (replace) {
                float4 rasterPoint=mul(diagnostic.inverseViewProjection,float4(ndc,depth,1.0));
                replace=abs(rasterPoint.w)>1e-8 && !any(isnan(rasterPoint)) && !any(isinf(rasterPoint));
                if (replace) {
                    float distance=length(rasterPoint.xyz/rasterPoint.w-diagnostic.rayOriginOffset.xyz);
                    // Require the same visible surface; preserve foreground entities, unsupported/stale
                    // geometry and world beyond the resident grid instead of erasing them.
                    replace=abs(distance-hit.t)<=max(0.025,0.0015*distance);
                }
            }
            if (replace) {
                float3 edge=min(p-boundsMinimum(),boundsMinimum()+float3(scene.dimensions().xyz)-p);
                weight=smoothstep(0.5,2.5,min(edge.x,min(edge.y,edge.z)));
            }
        }
        if (hit.found && diagnostic.control.y==0u) {
            float3 albedo=live ? float3(0.65) : materialColor(hit.material);
            if (live && textures.available()) albedo=hitAlbedo(hit,p);
            if (worldLit) {
                if (rasterInputs.available() ? replace : shadeable) {
                    float3 radiance=float3(0);
                    bool resolved=true;
                    if (translucent) resolved=translucentRadiance(hit,p,ray,seed,radiance);
                    else radiance=surfaceRadiance(hit,p,albedo,seed);
                    if (any(isnan(radiance)) || any(isinf(radiance))) resolved=false;
                    uint4 stored=uint4(0);
                    if (resolved) {
                        float count;
                        radiance=accumulate(pixel,resolution,p,hit.normal,radiance,count);
                        stored=uint4(packHalf2x16(radiance.rg),packHalf2x16(float2(radiance.b,count)),
                        asuint(hit.t),packSnorm2x16(octEncode(hit.normal)));
                    } else weight=0.0;
                    storeHistory(pixel,stored);
                    color=resolved ? present(radiance) : color;
                }
            } else {
                float3 sun=normalize(float3(-0.5,1.0,0.3));
                Hit shadow=trace(p+hit.normal*0.003,sun,0.001,sceneDistance());
                float direct=shadow.found ? 0.0 : max(0.0,dot(hit.normal,sun));
                color=albedo*(0.22+0.78*direct);
            }
        }
        if (!(replace && worldLit && hit.found && diagnostic.control.y==0u)) storeHistory(pixel,uint4(0));
        if (rasterInputs.available() && live && diagnostic.control.y==0u) {
            color=lerp(raster,color,weight);
            // Depth cannot identify translucent particles with depth writes disabled. This bounded
            // hybrid path does not provide complete transparent composition.
        }
        if (diagnostic.control.y==1u) color=hit.found ? hit.normal*0.5+0.5 : float3(0);
        if (diagnostic.control.y==2u) color=hit.found ? float3(clamp(hit.t/sceneDistance(),0.0,1.0)) : float3(0);
        if (diagnostic.control.y==3u) color=!hit.found ? float3(0)
        : live && hit.material==4u ? float3(0.08,0.24,0.85)
        : live && hit.material==2u ? float3(0.75,0.10,0.65)
        : live && hit.material==3u ? float3(0.15,0.8,0.35) : float3(1);
        color=clamp(color,0.0,1.0);
        if ((diagnostic.control.x&4u)==0u) {
            color=textures.available() ? linearToSrgb(color) : pow(color,float3(1.0/2.2));
        }
        outputImage[pixel]=float4(color,1.0);
    }
}
