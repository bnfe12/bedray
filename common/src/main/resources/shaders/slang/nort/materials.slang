public module nort_materials;
import "nort/types.slang";
import "nort/inputs.slang";
import "nort/geometry.slang";

extension<S : INoRtScene, M : INoRtMaterials, R : INoRtRaster> NoRtRenderer<S,M,R> {
    float3 srgbToLinear(float3 value) {
        return select(value>0.04045,pow((value+0.055)/1.055,float3(2.4)),value/12.92);
    }
    float3 linearToSrgb(float3 value) {
        return select(value>0.0031308,1.055*pow(value,float3(1.0/2.4))-0.055,value*12.92);
    }
    float3 tintColor(uint tint) {
        return srgbToLinear(float3(float((tint>>16)&255u),float((tint>>8)&255u),float(tint&255u))/255.0);
    }
    float3 cellTint(int3 cell) {
        uint value; uint page;
        if(scene.streamed()) {
            page=sectionPage(cell);
            if(page==0xffffffffu||scene.word(page+3u)!=2u)return float3(1);
            int3 local=cell&int3(15);
            value=scene.word(scene.word(page+5u)+uint(local.x+16*(local.y+16*local.z)));
        } else {
            uint index=cellIndex(cell);
            if (index==0xffffffffu) return float3(1);
            value=scene.cell(scene.dimensions().w+index);
        }
        return value==0u ? float3(1) : tintColor(value);
    }
    float quadComponent(ModelQuad quad, int index) { return quad.vertices[index/4][index%4]; }
    float3 vertexPosition(ModelQuad quad, int vertex) {
        int i=vertex*5;
        return float3(quadComponent(quad,i),quadComponent(quad,i+1),quadComponent(quad,i+2));
    }
    float2 vertexUv(ModelQuad quad, int vertex) { return float2(quadComponent(quad,vertex*5+3),quadComponent(quad,vertex*5+4)); }
    bool triangle(float3 origin, float3 ray, float3 a, float3 b, float3 c, float2 ta, float2 tb, float2 tc,
    float minT, float maxT, out float t, out float2 uv, out float3 normal) {
        t=0.0; uv=float2(0); normal=float3(0);
        float3 e1=b-a, e2=c-a, p=cross(ray,e2);
        float determinant=dot(e1,p);
        if (abs(determinant)<1e-9) return false;
        float inverse=1.0/determinant;
        float3 s=origin-a, q=cross(s,e1);
        float u=dot(s,p)*inverse, v=dot(ray,q)*inverse;
        if (u < -1e-6 || v < -1e-6 || u+v > 1.000001) return false;
        t=dot(e2,q)*inverse;
        // Preserve geometry exactly on a DDA cell boundary despite floating-point roundoff.
        if (t < max(0.0,minT-0.0002) || t > maxT+0.0002) return false;
        normal=cross(e1,e2);
        float square=dot(normal,normal);
        if (square<1e-16) return false;
        normal*=rsqrt(square);
        if (dot(normal,ray)>0.0) normal=-normal;
        uv=ta+(tb-ta)*u+(tc-ta)*v;
        return true;
    }
    Hit modelHit(float3 origin, float3 ray, int3 cell, uint material, float minT, float maxT) {
        uint page=0,slot=0; uint4 header=uint4(0);
        uint reference=(material>>8)&255u;
        if (reference==0u || reference>128u) return miss();
        if(scene.streamed()) {
            page=sectionPage(cell);
            if(page==0xffffffffu||reference>scene.word(page+9u))return miss();
            uint headerAddress=scene.word(page+7u)+(reference-1u)*4u;
            if(headerAddress+3u>=uint(textures.modelWordCount()))return miss();
            header=uint4(textures.modelWord(headerAddress),textures.modelWord(headerAddress+1u),textures.modelWord(headerAddress+2u),textures.modelWord(headerAddress+3u));
            if(header.y==0u||header.y>64u)return miss();
        } else {
            int3 tile=cell/16;
            slot=uint(tile.x+3*(tile.y+3*tile.z));
            header=textures.modelHeader(slot*128u+reference-1u);
            if (header.y==0u || header.y>64u || header.x+header.y>8192u) return miss();
        }
        Hit best=miss();
        float nearest=maxT;
        for (uint index=0u; index<header.y; index++) {
            ModelQuad quad;
            if(scene.streamed()) {
                uint address=scene.word(page+7u)+scene.word(page+8u)+(header.x+index)*24u;
                if(address+23u>=uint(textures.modelWordCount()))return miss();
                for(int word=0;word<20;word++)quad.vertices[word/4][word%4]=asfloat(textures.modelWord(address+uint(word)));
                quad.metadata=uint4(textures.modelWord(address+20u),textures.modelWord(address+21u),textures.modelWord(address+22u),textures.modelWord(address+23u));
            } else {
                uint address=slot*8192u+header.x+index;
                if (address>=uint(textures.denseQuadCount())) return miss();
                quad=textures.modelQuad(address);
            }
            float3 a=vertexPosition(quad,0), b=vertexPosition(quad,1), c=vertexPosition(quad,2), d=vertexPosition(quad,3);
            float2 ta=vertexUv(quad,0), tb=vertexUv(quad,1), tc=vertexUv(quad,2), td=vertexUv(quad,3);
            float2 low=min(min(ta,tb),min(tc,td)), high=max(max(ta,tb),max(tc,td));
            float2 inset=min(float2(0.5)/float2(textures.atlasSize()),(high-low)*0.5);
            for (int part=0; part<2; part++) {
                float t; float2 uv; float3 normal;
                bool accepted=part==0
                ? triangle(origin-float3(cell),ray,a,b,c,ta,tb,tc,minT,maxT,t,uv,normal)
                : triangle(origin-float3(cell),ray,c,d,a,tc,td,ta,minT,maxT,t,uv,normal);
                if (!accepted || (best.found && t>nearest+0.00001)) continue;
                float4 texel=textures.sampleAtlas(clamp(uv,low+inset,high-inset));
                // Test each triangle's alpha before nearest selection, including nonplanar quads.
                // Later accepted coplanar overlays win; transparent overlays leave the base intact.
                if (texel.a<0.1) continue;
                float3 tint=tintColor(quad.metadata.x);
                if ((quad.metadata.y&FLAG_CELL_TINT)!=0u) tint*=cellTint(cell);
                best=Hit(true,cell,material,max(0.0,t),normal,srgbToLinear(texel.rgb)*tint,
                quad.metadata.y,float(quad.metadata.z)/15.0,texel.a);
                nearest=t;
            }
        }
        return best;
    }

}
