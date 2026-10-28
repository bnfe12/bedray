// Compile-only harness for the Radiance Cascades environment policy.
//
// P1-10: environment is opt-in.  A terminal-cascade miss is only allowed to
// become sky when the host asserts a positive world boundary and the trace's
// far window reaches it.  Zero is the absence of proof, not trust.
//
// Usage (same arguments as gradle/slang.gradle):
//   slangc -target spirv -profile cs_6_5 -entry main ^
//     -matrix-layout-column-major -I common/src/main/resources/shaders/slang ^
//     -o <temp>/verify_rc_environment.spv ^
//     common/src/test/resources/shaders/slang/verify_rc_environment.slang
//
// This checks that the policy helpers compile and stay reachable.  It does not
// execute a dispatch or prove that a host supplies the right boundary.

import "radiance_cascades.slang";
import "radiance_cascades/rc_trace.slang";

[[vk::binding(0, 0)]] RWStructuredBuffer<float4> sink;

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    RCTraceOptions options = defaultRCTraceOptions();
    bool defaultDisabled = options.environmentPolicy == uint(RCEnvironmentPolicy.Disabled);
    bool defaultNoEnvironment = !rcEnvironmentAllowed(options, RADIANCE_CASCADE_COUNT - 1u, 1.0e6);
    bool defaultValid = rcTraceOptionsValid(options);

    options.environmentPolicy = uint(RCEnvironmentPolicy.TerminalCascade);
    options.worldBoundaryDistance = 1024.0;
    bool shortWindow = rcEnvironmentAllowed(options, RADIANCE_CASCADE_COUNT - 1u, 512.0);
    bool reachesBoundary = rcEnvironmentAllowed(options, RADIANCE_CASCADE_COUNT - 1u, 2048.0);
    bool positiveBoundaryValid = rcTraceOptionsValid(options);

    options.worldBoundaryDistance = 0.0;
    bool zeroRejected = !rcTraceOptionsValid(options);
    bool zeroNoEnvironment = !rcEnvironmentAllowed(options, RADIANCE_CASCADE_COUNT - 1u, 1.0e6);

    sink[0] = float4(defaultDisabled ? 1.0 : 0.0, defaultNoEnvironment ? 1.0 : 0.0,
                     defaultValid ? 1.0 : 0.0, shortWindow ? 1.0 : 0.0);
    sink[1] = float4(reachesBoundary ? 1.0 : 0.0, positiveBoundaryValid ? 1.0 : 0.0,
                     zeroRejected ? 1.0 : 0.0, zeroNoEnvironment ? 1.0 : 0.0);
}
