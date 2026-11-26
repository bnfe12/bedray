// Your light (path tracer): a warmer sun and a bluer, weaker moon in every bounce; torches untouched.
// mosaic hook user.light --slang golden_sun.light.slang
if (lightKind == 1.0) radiance *= float3(1.12, 1.0, 0.82);
if (lightKind == 2.0) radiance *= float3(0.7, 0.8, 1.15);
