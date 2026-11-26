// Your material (path tracer): the tops of blocks are wetter and polished - shiny stone and grass after rain.
// mosaic hook user.material --slang wet_tops.material.slang
float wet = smoothstep(0.6, 0.95, normal.y);
roughness = lerp(roughness, roughness * 0.3, wet);
albedo = albedo * lerp(1.0, 0.75, wet);
