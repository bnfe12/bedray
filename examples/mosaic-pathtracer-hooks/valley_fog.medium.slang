// Your medium (path tracer): ground fog in air - thicker low down, lit by a soft sky colour, inside the light transport.
// mosaic hook user.medium --slang valley_fog.medium.slang
if (inWater < 0.5) {
    float height = start.y + direction.y * segmentLength * 0.5;      // camera-relative height of the segment's middle
    float density = 0.004 * exp(-max(height + 4.0, 0.0) * 0.08);   // per block, fading upward
    float k = 1.0 - exp(-segmentLength * density);
    inScatter += float3(0.55, 0.62, 0.72) * k * 0.6;
    transmittance *= 1.0 - k;
}
