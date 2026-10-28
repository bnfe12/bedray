// Compute-only scene tracer: one implementation, shared by every rendering tier.
public module software_tracer;
__exported import "traversal/voxel_traversal.slang";
typedef BitmaskVoxelTraversal SoftwareVoxelTracer;
