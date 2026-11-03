public module nort_history;
import "nort/types.slang";
import "nort/inputs.slang";

extension<S : INoRtScene, M : INoRtMaterials, R : INoRtRaster> NoRtRenderer<S,M,R> {
    float2 octEncode(float3 n) {
        float sum=abs(n.x)+abs(n.y)+abs(n.z);
        if (sum<1e-6) return float2(0);
        n/=sum;
        float2 e=n.xy;
        if (n.z<0.0) e=(float2(1.0)-abs(n.yx))*float2(n.x>=0.0 ? 1.0 : -1.0,n.y>=0.0 ? 1.0 : -1.0);
        return e;
    }
    float3 octDecode(float2 e) {
        float3 n=float3(e,1.0-abs(e.x)-abs(e.y));
        float t=max(-n.z,0.0);
        n.x+=n.x>=0.0 ? -t : t;
        n.y+=n.y>=0.0 ? -t : t;
        return normalize(n);
    }
    void storeHistory(int2 pixel, uint4 value) {
        rasterInputs.store((diagnostic.control.x&32u)!=0u,pixel,value);
    }
    uint4 loadHistory(int2 pixel) {
        return rasterInputs.history((diagnostic.control.x&32u)!=0u,pixel);
    }
    // Bilinear reprojection; each tap is accepted only if its stored distance from the previous eye and
    // its normal match this surface point (validation tolerances, not physical constants).
    float3 accumulate(int2 pixel, int2 resolution, float3 point, float3 normal, float3 radiance, out float count) {
        count=1.0;
        if (!rasterInputs.available()) return radiance;
        if (textures.frameInfo().y==0u) return radiance;
        float4 clip=mul(textures.previousViewProjection(),float4(point-textures.previousCamera().xyz,1.0));
        if (clip.w<=1e-6) return radiance;
        float2 position=(clip.xy/clip.w*0.5+0.5)*float2(resolution)-0.5;
        if (any(isnan(position)) || any(isinf(position))) return radiance;
        int2 base=int2(floor(position));
        float2 fraction=position-float2(base);
        float expected=length(point-textures.previousEye().xyz);
        float3 sum=float3(0); float weights=0.0, counts=0.0;
        for (int tap=0; tap<4; tap++) {
            int2 offset=int2(tap&1,tap>>1), q=base+offset;
            if (any((q<int2(0))) || any((q>=resolution))) continue;
            uint4 stored=loadHistory(q);
            float2 rg=unpackHalf2x16ToFloat(stored.x), bc=unpackHalf2x16ToFloat(stored.y);
            if (bc.y<0.5) continue;
            float distance=asfloat(stored.z);
            if (abs(distance-expected)>0.02*expected+0.05) continue;
            if (dot(octDecode(unpackSnorm2x16ToFloat(stored.w)),normal)<0.9) continue;
            float w=(offset.x==1 ? fraction.x : 1.0-fraction.x)*(offset.y==1 ? fraction.y : 1.0-fraction.y);
            sum+=w*float3(rg,bc.x); counts+=w*bc.y; weights+=w;
        }
        if (weights<0.05) return radiance;
        count=min(counts/weights+1.0,float(max(textures.frameInfo().z,1u)));
        return lerp(sum/weights,radiance,1.0/count);
    }
}
