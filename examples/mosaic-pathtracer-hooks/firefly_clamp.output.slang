// Your path output (path tracer): a softer firefly clamp before Bedray's own - rare very bright paths are compressed.
// mosaic hook user.pathOutput --slang firefly_clamp.output.slang
float l = max(max(radiance.r, radiance.g), radiance.b);
float limit = 24.0;
if (l > limit) radiance *= (limit + log2(1.0 + l - limit)) / l;
