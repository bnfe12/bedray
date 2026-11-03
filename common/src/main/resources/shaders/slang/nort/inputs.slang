public module nort_inputs;
import "nort/types.slang";

// The live packed producer is deliberately 32-bit and independent of hardware RT.
public interface INoRtScene {
    bool streamed();
    uint4 dimensions();
    int4 boundsMin();
    int4 originSection();
    uint4 pageInfo();
    uint word(uint index);
    uint cell(uint index);
    uint wordCount();
};
public struct DenseNoRtScene : INoRtScene {
    ByteAddressBuffer data;
    [ForceInline]
    bool streamed() { return false; }
    [ForceInline]
    uint4 dimensions() { return data.Load4(0); }
    [ForceInline]
    int4 boundsMin() { return int4(0); }
    [ForceInline]
    int4 originSection() { return int4(0); }
    [ForceInline]
    uint4 pageInfo() { return uint4(0); }
    [ForceInline]
    uint word(uint index) { return 0; }
    [ForceInline]
    uint cell(uint index) { return data.Load(16+4*index); }
    [ForceInline]
    uint wordCount() { return 0; }
};
public struct StreamedNoRtScene : INoRtScene {
    ByteAddressBuffer data;
    [ForceInline]
    bool streamed() { return true; }
    [ForceInline]
    uint4 dimensions() { return data.Load4(0); }
    [ForceInline]
    int4 boundsMin() { return asint(data.Load4(16)); }
    [ForceInline]
    int4 originSection() { return asint(data.Load4(32)); }
    [ForceInline]
    uint4 pageInfo() { return data.Load4(48); }
    [ForceInline]
    uint word(uint index) { return data.Load(64+4*index); }
    [ForceInline]
    uint cell(uint index) { return 0; }
    [ForceInline]
    uint wordCount() { uint bytes; data.GetDimensions(bytes); return (bytes-64)/4; }
};

public interface INoRtMaterials {
    bool available();
    uint faceCount();
    CubeFace face(uint index);
    uint2 atlasSize();
    float4 sampleAtlas(float2 uv);
    uint modelWord(uint index);
    uint modelWordCount();
    uint4 modelHeader(uint index);
    uint denseQuadCount();
    ModelQuad modelQuad(uint index);
    float4 value(uint index);
    float4x4 previousViewProjection();
    float4 previousCamera();
    float4 previousEye();
    uint4 frameInfo();
    float4 emitter(uint index);
};
public struct TexturedNoRtMaterials : INoRtMaterials {
    StructuredBuffer<CubeFace> palette;
    Sampler2D atlas;
    ByteAddressBuffer models;
    StructuredBuffer<NoRtEnvironment> environment;
    [ForceInline]
    bool available() { return true; }
    [ForceInline]
    uint faceCount() { uint count,stride; palette.GetDimensions(count,stride); return count; }
    [ForceInline]
    CubeFace face(uint index) { return palette[index]; }
    [ForceInline]
    uint2 atlasSize() { uint2 size; atlas.GetDimensions(size.x,size.y); return size; }
    [ForceInline]
    float4 sampleAtlas(float2 uv) { return atlas.SampleLevel(uv,0.0); }
    [ForceInline]
    uint modelWord(uint index) { return models.Load(index*4); }
    [ForceInline]
    uint modelWordCount() { uint bytes; models.GetDimensions(bytes); return bytes/4; }
    [ForceInline]
    uint4 modelHeader(uint index) { return models.Load4(index*16); }
    [ForceInline]
    uint denseQuadCount() { uint bytes; models.GetDimensions(bytes); return (bytes-55296)/96; }
    [ForceInline]
    ModelQuad modelQuad(uint index) {
        ModelQuad quad; uint base=55296+index*96;
        for(uint i=0;i<5;i++) quad.vertices[i]=asfloat(models.Load4(base+i*16));
        quad.metadata=models.Load4(base+80); return quad;
    }
    [ForceInline]
    float4 value(uint index) { return environment[0].values[index]; }
    [ForceInline]
    float4x4 previousViewProjection() { return environment[0].previousViewProjection; }
    [ForceInline]
    float4 previousCamera() { return environment[0].previousCamera; }
    [ForceInline]
    float4 previousEye() { return environment[0].previousEye; }
    [ForceInline]
    uint4 frameInfo() { return environment[0].info; }
    [ForceInline]
    float4 emitter(uint index) { return environment[0].emitters[index]; }
};
// Synthetic and untextured diagnostics allocate no dummy textures or frame buffers.
public struct SyntheticNoRtMaterials : INoRtMaterials {
    [ForceInline]
    bool available() { return false; }
    [ForceInline]
    uint faceCount() { return 0; }
    [ForceInline]
    CubeFace face(uint index) { return CubeFace(float4(0),float2(0),0,0); }
    [ForceInline]
    uint2 atlasSize() { return uint2(1); }
    [ForceInline]
    float4 sampleAtlas(float2 uv) { return float4(0); }
    [ForceInline]
    uint modelWord(uint index) { return 0; }
    [ForceInline]
    uint modelWordCount() { return 0; }
    [ForceInline]
    uint4 modelHeader(uint index) { return uint4(0); }
    [ForceInline]
    uint denseQuadCount() { return 0; }
    [ForceInline]
    ModelQuad modelQuad(uint index) { ModelQuad q; for(uint i=0;i<5;i++)q.vertices[i]=float4(0); q.metadata=uint4(0);return q; }
    [ForceInline]
    float4 value(uint index) { return float4(0); }
    [ForceInline]
    float4x4 previousViewProjection() { return float4x4(0); }
    [ForceInline]
    float4 previousCamera() { return float4(0); }
    [ForceInline]
    float4 previousEye() { return float4(0); }
    [ForceInline]
    uint4 frameInfo() { return uint4(0); }
    [ForceInline]
    float4 emitter(uint index) { return float4(0); }
};

public interface INoRtRaster {
    bool available();
    float3 color(int2 pixel);
    float depth(int2 pixel);
    uint4 history(bool parity, int2 pixel);
    void store(bool parity, int2 pixel, uint4 value);
};
public struct PreservedNoRtRaster : INoRtRaster {
    Sampler2D rasterColor;
    Sampler2D rasterDepth;
    RWTexture2D<uint4> historyA;
    RWTexture2D<uint4> historyB;
    [ForceInline]
    bool available() { return true; }
    [ForceInline]
    float3 color(int2 pixel) { return rasterColor.Load(int3(pixel,0)).rgb; }
    [ForceInline]
    float depth(int2 pixel) { return rasterDepth.Load(int3(pixel,0)).r; }
    [ForceInline]
    uint4 history(bool parity, int2 pixel) { return parity ? historyB[pixel] : historyA[pixel]; }
    [ForceInline]
    void store(bool parity, int2 pixel, uint4 value) { if(parity) historyA[pixel]=value; else historyB[pixel]=value; }
};
public struct NoRtRasterAbsent : INoRtRaster {
    [ForceInline]
    bool available() { return false; }
    [ForceInline]
    float3 color(int2 pixel) { return float3(0); }
    [ForceInline]
    float depth(int2 pixel) { return 0.0; }
    [ForceInline]
    uint4 history(bool parity, int2 pixel) { return uint4(0); }
    [ForceInline]
    void store(bool parity, int2 pixel, uint4 value) {}
};

public struct NoRtRenderer<S : INoRtScene, M : INoRtMaterials, R : INoRtRaster> {
    S scene;
    M textures;
    R rasterInputs;
    NoRtPush diagnostic;
    RWTexture2D<float4> outputImage;
};
