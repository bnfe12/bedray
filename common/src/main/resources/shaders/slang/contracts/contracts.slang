// Aggregate import for the Bedray 2.0 data contracts.
//
// Passes may import this single module.  Modules that need one concern only
// (for example a traversal backend that never touches materials) should import
// the individual contract instead, to keep the dependency graph honest.

public module contracts;

__exported import "contracts/math.slang";
__exported import "contracts/frame.slang";
__exported import "contracts/material.slang";
__exported import "contracts/geometry.slang";
__exported import "contracts/hit.slang";
__exported import "contracts/history.slang";
__exported import "contracts/light.slang";
__exported import "contracts/scene_tracer.slang";
__exported import "contracts/octahedral.slang";
