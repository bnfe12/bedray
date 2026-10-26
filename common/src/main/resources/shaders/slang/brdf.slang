// Legacy-compatible name for the microfacet BRDF module.
//
// New code imports "shading/microfacet.slang" directly.  This file remains so
// the old scaffold and any early harness keep compiling without pulling the
// legacy types/bindings include chain.

public module brdf;

__exported import "shading/microfacet.slang";
