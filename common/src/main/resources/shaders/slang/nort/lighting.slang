public module nort_lighting;
import "nort/types.slang";
import "nort/inputs.slang";
import "nort/geometry.slang";
import "nort/materials.slang";
import "nort/traversal.slang";

extension<S : INoRtScene, M : INoRtMaterials, R : INoRtRaster> NoRtRenderer<S,M,R> {
    // ---- Sampling ----
    uint pcg(uint value) {
        uint state=value*747796405u+2891336453u;
        uint word=((state>>((state>>28u)+4u))^state)*277803737u;
        return (word>>22u)^word;
    }
    float random(inout uint seed) { seed=pcg(seed); return float(seed>>8)*(1.0/16777216.0); }
    void basis(float3 n, out float3 t, out float3 b) {
        float s=n.z>=0.0 ? 1.0 : -1.0;
        float a=-1.0/(s+n.z), c=n.x*n.y*a;
        t=float3(1.0+s*n.x*n.x*a,s*c,-s*n.x);
        b=float3(c,s+n.y*n.y*a,-n.y);
    }
    // Cosine-weighted hemisphere direction; pdf = cos(theta)/pi.
    float3 cosineSample(float3 n, inout uint seed) {
        float u=random(seed), v=random(seed);
        float r=sqrt(u), phi=2.0*PI*v;
        float3 t, b; basis(n,t,b);
        return normalize(t*(r*cos(phi))+b*(r*sin(phi))+n*sqrt(max(0.0,1.0-u)));
    }
    // Uniform direction inside a cone; pdf = 1/(2*pi*(1-cosMax)).
    float3 coneSample(float3 axis, float cosMax, inout uint seed) {
        float u=random(seed), v=random(seed);
        float c=1.0-u*(1.0-cosMax), s=sqrt(max(0.0,1.0-c*c)), phi=2.0*PI*v;
        float3 t, b; basis(axis,t,b);
        return normalize(t*(s*cos(phi))+b*(s*sin(phi))+axis*c);
    }
    // ---- Materials and environment ----
    float3 cubeAlbedo(Hit hit, float3 point) {
        uint index;
        uint reference=(hit.material>>8)&255u;
        if (reference==0u || reference>128u || dot(hit.normal,hit.normal)<0.5) return float3(0.65);
        float3 local=clamp(point-float3(hit.cell),float3(0),float3(1));
        uint face; float2 coordinates;
        if (hit.normal.y!=0.0) { face=hit.normal.y<0.0 ? 0u : 1u; coordinates=local.xz; }
        else if (hit.normal.z!=0.0) { face=hit.normal.z<0.0 ? 2u : 3u; coordinates=local.xy; }
        else { face=hit.normal.x<0.0 ? 4u : 5u; coordinates=local.zy; }
        if(scene.streamed()) {
            uint page=sectionPage(hit.cell);
            if(page==0xffffffffu||reference>scene.word(page+10u))return float3(0.65);
            index=scene.word(page+6u)+(reference-1u)*6u+face;
        } else {
            int3 tile=hit.cell/16;
            uint slot=uint(tile.x+3*(tile.y+3*tile.z));
            index=(slot*128u+reference-1u)*6u+face;
        }
        if (index>=uint(textures.faceCount())) return float3(0.65);
        CubeFace surface=textures.face(index);
        float2 base=surface.originDu.xy, du=surface.originDu.zw;
        float2 low=min(min(base,base+du),min(base+surface.dv,base+du+surface.dv));
        float2 high=max(max(base,base+du),max(base+surface.dv,base+du+surface.dv));
        float2 inset=min(float2(0.5)/float2(textures.atlasSize()),(high-low)*0.5);
        float2 uv=clamp(base+du*coordinates.x+surface.dv*coordinates.y,low+inset,high-inset);
        float3 color=srgbToLinear(textures.sampleAtlas(uv).rgb)*tintColor(surface.tint);
        if ((surface.flags&1u)!=0u) color*=cellTint(hit.cell);
        return color;
    }
    float3 hitAlbedo(Hit hit, float3 point) { return (hit.material&255u)==3u ? hit.albedo : cubeAlbedo(hit,point); }
    // Vanilla's light-level curve: response(15)=1, response(0)=0.
    float lightResponse(float level) { float value=clamp(level,0.0,15.0)/15.0; return value/(4.0-3.0*value); }
    // Display sky color decoded to linear, used as uniform sky radiance (pi-normalized irradiance units).
    float3 skyRadiance() { return textures.value(2).rgb*(textures.value(2).w*textures.value(1).w); }
    float3 blockColor() { return textures.value(3).rgb*textures.value(3).w; }
    // Incident light lies outside the visible surface, not inside an opaque cube.
    CellLight lightOutside(float3 point, float3 normal) {
        int3 cell=int3(floor(point+normal*0.003));
        uint light=cellValue(cell);
        bool available=light!=0xffffffffu && (light&(1u<<28))!=0u;
        return CellLight(available,available ? float((light>>16)&15u) : 0.0,available ? float((light>>20)&15u) : 0.0,cell);
    }
    uint emissionLevel(Hit hit) { return max(uint(hit.emission*15.0+0.5),(hit.material>>24)&15u); }
    // Opaque blocker search; translucent layers filter by their coverage-weighted color.
    Hit traceOpaque(float3 origin, float3 direction, float maxT, out float3 filterColor) {
        filterColor=float3(1);
        float nearT=0.001;
        for (int layer=0; layer<8; layer++) {
            Hit blocker=trace(origin,direction,nearT,maxT);
            if (!blocker.found || (blocker.flags&FLAG_TRANSLUCENT)==0u) return blocker;
            filterColor*=lerp(float3(1),blocker.albedo,blocker.alpha);
            nearT=max(nearT,blocker.t)+0.0005;
        }
        filterColor=float3(0); // explicit layer budget: beyond it nothing is transmitted
        return miss();
    }
    // Direct Sun/Moon irradiance from one cone sample. Rays leaving the bounded grid are scaled by the
    // vanilla sky-light response, which removes the key light in caves deeper than the grid.
    float3 keyIrradiance(float3 point, float3 normal, float skyResponse, inout uint seed) {
        float3 key=textures.value(0).xyz;
        float square=dot(key,key);
        if (textures.value(0).w<=0.0 || square<1e-8 || skyResponse<=0.0) return float3(0);
        key*=rsqrt(square);
        float3 direction=coneSample(key,cos(KEY_ANGULAR_RADIUS),seed);
        float cosine=dot(normal,direction);
        if (cosine<=0.0) return float3(0);
        float3 filterColor;
        Hit blocker=traceOpaque(point+normal*0.003,direction,sceneDistance(),filterColor);
        if (blocker.found) return float3(0);
        return textures.value(1).rgb*(textures.value(1).w*skyResponse*cosine)*filterColor;
    }
    // Irradiance at a secondary point without further tracing except one key-light shadow ray:
    // vanilla sky and block light stand in for their own visibility there.
    float3 secondaryIrradiance(float3 point, float3 normal, inout uint seed) {
        CellLight light=lightOutside(point,normal);
        float sky=lightResponse(light.sky);
        return textures.value(4).rgb+skyRadiance()*sky+blockColor()*lightResponse(light.block)
        +keyIrradiance(point,normal,sky,seed);
    }
    // Radiance arriving along a secondary ray (diffuse bounce, reflection, transmission).
    float3 secondaryRadiance(float3 origin, float3 direction, float originSky, inout uint seed, out bool escaped, out float distance) {
        float3 filterColor;
        Hit hit=traceOpaque(origin,direction,sceneDistance(),filterColor);
        escaped=!hit.found;
        distance=hit.found ? hit.t : sceneDistance();
        if (!hit.found) return skyRadiance()*originSky*filterColor;
        if ((hit.flags&FLAG_NO_MATERIAL)!=0u) return float3(0);
        float3 point=origin+hit.t*direction;
        float3 albedo=hitAlbedo(hit,point);
        float3 emitted=blockColor()*lightResponse(float(emissionLevel(hit)));
        return filterColor*albedo*(secondaryIrradiance(point,hit.normal,seed)+emitted);
    }
    // Block-light irradiance: next-event estimation over the bounded emitter list, sampled by reservoir
    // in proportion to each sphere's unshadowed irradiance bound, plus vanilla light that no listed
    // emitter can explain. Vanilla light from one source of level e at Manhattan distance d is at most
    // e-d (open-air flood fill); if the vanilla level exceeds that bound for every listed emitter, an
    // unlisted source (outside the grid or beyond the list) lights the point and its vanilla value is added.
    // Radiance mapping: a level-e emitter delivers vanilla's response(e-1) at one block, i.e. a sphere of
    // radius r has radiance response(e-1)/r^2 and the far-field irradiance falls off as 1/d^2.
    float3 blockIrradiance(float3 point, float3 normal, CellLight light, int3 selfCell, inout uint seed) {
        uint count=min(textures.frameInfo().x,MAX_EMITTERS);
        float explained=0.0, total=0.0;
        int chosen=-1;
        float chosenCosine=0.0;
        for (uint i=0u; i<count; i++) {
            float4 sphere=textures.emitter(2u*i), meta=textures.emitter(2u*i+1u);
            int3 cell=int3(meta.yzw);
            int3 manhattan=abs(cell-light.cell);
            explained=max(explained,meta.x-float(manhattan.x+manhattan.y+manhattan.z));
            if (all((cell==selfCell))) continue;
            float3 toward=sphere.xyz-point;
            float square=dot(toward,toward), radius=sphere.w;
            if (square>(EMITTER_RANGE+radius)*(EMITTER_RANGE+radius)) continue;
            float distance=sqrt(max(square,1e-8));
            float sinSquare=min(1.0,radius*radius/max(square,1e-8));
            float cosMax=sqrt(1.0-sinSquare);
            // Largest cosine toward any point of the sphere: the weight never under-covers the integrand.
            float bound=clamp(dot(normal,toward)/distance+sqrt(sinSquare),0.0,1.0);
            if (bound<=0.0) continue;
            float omega=2.0*PI*(1.0-cosMax);
            float weight=lightResponse(meta.x-1.0)/(radius*radius)*omega/PI*bound;
            total+=weight;
            if (random(seed)*total<weight) { chosen=int(i); chosenCosine=bound; }
        }
        float3 result=float3(0);
        if (chosen>=0 && total>0.0) {
            float4 sphere=textures.emitter(2u*uint(chosen)), meta=textures.emitter(2u*uint(chosen)+1u);
            float3 origin=point+normal*0.003, toward=sphere.xyz-origin;
            float square=dot(toward,toward), radius=sphere.w;
            float distance=sqrt(max(square,1e-8));
            float cosMax=sqrt(1.0-min(1.0,radius*radius/max(square,1e-8)));
            float3 direction=coneSample(toward/distance,cosMax,seed);
            float cosine=dot(normal,direction);
            if (cosine>0.0) {
                float along=dot(direction,toward), disc=along*along-(square-radius*radius);
                float maxT=disc>0.0 ? max(0.001,along-sqrt(disc)) : max(0.001,distance-radius);
                float3 filterColor;
                Hit blocker=traceOpaque(origin,direction,maxT,filterColor);
                bool visible=!blocker.found || all((blocker.cell==int3(meta.yzw)));
                if (visible) result=blockColor()*filterColor*(total*cosine/chosenCosine);
            }
        }
        if (light.available && light.block>explained+0.5) result+=blockColor()*lightResponse(light.block);
        return result;
    }
    // Full primary-surface radiance before presentation (linear, pi-normalized units):
    // ambient + key light + one diffuse bounce/sky sample + block light, times albedo, plus emission.
    float3 surfaceRadiance(Hit hit, float3 point, float3 albedo, inout uint seed) {
        CellLight light=lightOutside(point,hit.normal);
        float sky=lightResponse(light.sky);
        float3 irradiance=textures.value(4).rgb+keyIrradiance(point,hit.normal,sky,seed);
        // One cosine-sampled ray: an escape sees the sky (scaled by vanilla sky openness), a hit returns
        // that surface's outgoing radiance. This is ambient occlusion, sky light and one bounce at once.
        if(diffuseBounceEnabled()) {
            bool escaped; float distance;
            float3 bounceDirection=cosineSample(hit.normal,seed);
            irradiance+=secondaryRadiance(point+hit.normal*0.003,bounceDirection,sky,seed,escaped,distance);
        } else irradiance+=skyRadiance()*sky;
        irradiance+=blockIrradiance(point,hit.normal,light,hit.cell,seed);
        float3 emitted=blockColor()*lightResponse(float(emissionLevel(hit)));
        return albedo*(irradiance+emitted);
    }
    // Water and other translucent surfaces: Schlick Fresnel, one reflection and one transmission ray.
    // Water refracts (Snell) and attenuates with distance by Beer-Lambert. Its extinction is derived from
    // the surface texel: one block of water transmits exactly vanilla's blend weight (1-a)+a*c, and the
    // medium's equilibrium radiance is c times the irradiance at the surface. Other translucent quads are
    // thin filters with the same per-layer transmittance and no bending. False = transmission unresolved.
    bool translucentRadiance(Hit hit, float3 point, float3 ray, inout uint seed, out float3 radiance) {
        radiance=float3(0);
        bool fluid=(hit.flags&FLAG_FLUID)!=0u;
        float3 normal=hit.normal;
        float ior=fluid ? WATER_IOR : GLASS_IOR;
        float f0=(ior-1.0)/(ior+1.0); f0*=f0;
        float cosine=clamp(-dot(ray,normal),0.0,1.0);
        float fresnel=f0+(1.0-f0)*pow(1.0-cosine,5.0);
        CellLight light=lightOutside(point,normal);
        float sky=lightResponse(light.sky);
        bool escaped; float distance;
        float3 reflected=secondaryRadiance(point+normal*0.003,reflect(ray,normal),sky,seed,escaped,distance);
        float3 surface=hit.albedo, perBlock=clamp(float3(1.0-hit.alpha)+hit.alpha*surface,float3(0.001),float3(1));
        float3 direction=fluid ? refract(ray,normal,1.0/ior) : ray;
        if (dot(direction,direction)<0.5) return false;
        float3 origin=point-normal*0.003;
        float3 throughput=fluid ? float3(1) : perBlock;
        float3 scattering=float3(0);
        bool inWater=fluid;
        float3 extinction=fluid ? -log(perBlock) : float3(0);
        float3 medium=fluid ? surface*secondaryIrradiance(point,normal,seed) : float3(0);
        float remaining=sceneDistance();
        for (int layer=0; layer<4; layer++) {
            Hit through=trace(origin,direction,0.001,remaining);
            if (!through.found || (through.flags&FLAG_NO_MATERIAL)!=0u) return false;
            float3 behind=origin+through.t*direction;
            // Only a segment actually inside water contributes optical depth and medium radiance.
            // Air after the exit surface must not inherit the preceding body's extinction.
            if (inWater) {
                float3 transmittance=exp(-extinction*through.t);
                scattering+=throughput*medium*(float3(1)-transmittance);
                throughput*=transmittance;
            }
            if ((through.flags&FLAG_TRANSLUCENT)==0u) {
                float3 transmitted=hitAlbedo(through,behind)*(secondaryIrradiance(behind,through.normal,seed)
                +blockColor()*lightResponse(float(emissionLevel(through))))*throughput+scattering;
                radiance=fresnel*reflected+(1.0-fresnel)*transmitted;
                return true;
            }
            if ((through.flags&FLAG_FLUID)!=0u) {
                // Vanilla culls internal fluid faces. Each remaining fluid boundary enters or exits
                // a body, including when a glass primary ray later enters water.
                float3 refracted=refract(direction,through.normal,inWater ? WATER_IOR : 1.0/WATER_IOR);
                // Internal reflection needs another transport path; retain raster instead of inventing
                // a straight transmitted ray when Snell's law has no transmitted solution.
                if (dot(refracted,refracted)<0.5) return false;
                direction=normalize(refracted);
                inWater=!inWater;
                if (inWater) {
                    float3 transmission=clamp(lerp(float3(1),through.albedo,through.alpha),float3(0.001),float3(1));
                    extinction=-log(transmission);
                    medium=through.albedo*secondaryIrradiance(behind,through.normal,seed);
                }
            } else throughput*=lerp(float3(1),through.albedo,through.alpha);
            remaining-=through.t+0.003;
            if (remaining<=0.001) return false;
            origin=behind+direction*0.003;
        }
        return false; // Explicit interface budget; unresolved transmission retains raster.
    }
    // Fixed LDR presentation; host ambient already includes the actual night-vision color.
    float3 present(float3 color) {
        color*=(1.0+clamp(textures.value(4).w,0.0,1.0))*(1.0-clamp(textures.value(5).x,0.0,1.0));
        color=lerp(color,color*float3(0.7,0.6,0.6),clamp(textures.value(5).z,0.0,1.0));
        return color/(float3(1.0)+color);
    }

}
