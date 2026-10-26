// bedray.slang — Root module. Re-exports all Bedray shader modules.
// Entry-point shaders (rt_path.slang, rt_rays.slang, etc.) import this
// single file to get access to everything.

module bedray;

public import types;
public import bindings;
public import sampling;
public import tonemap;
public import brdf;
public import ray_backend;
public import backend_query;
// public import backend_ser;  // Tier 3 — uncomment when implemented

// Future modules (uncomment as they are ported):
// public import sky;
// public import light;
// public import surface;
// public import shade;
// public import emitters;
// public import temporal;
// public import water;
// public import transparency;
// public import caustics;
// public import denoise_pack;
