// Compatibility module for early ReSTIR DI harnesses.
//
// The canonical emitter sampler now lives in "light/emitters.slang"; this file
// keeps the old import path working without duplicating the implementation.

public module direct_light;

__exported import "light/emitters.slang";
