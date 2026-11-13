// Author-host blit fragment shader (Slang). Samples a color texture and outputs it unchanged.
Texture2D colorTex : register(t0);
SamplerState colorSamp : register(s0);

[shader("fragment")]
float4 main(float4 pos : SV_Position, float2 uv : TEXCOORD0) : SV_Target {
    return colorTex.Sample(colorSamp, uv);
}