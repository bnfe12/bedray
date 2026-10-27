# Bedray native render graph — source candidate

This isolated C++ planner describes semantic resources, independent passes and
explicit dependencies. It rejects cycles, duplicate resources/uses, unsupported
memory aliasing, unordered read/write or write/write access, unordered image
layout changes (even between reads), and reads without
an initialized entry resource or an ordered producer. Its output is an ordered
pass list and conservative pairwise resource hazards.
Plans carry their owning graph and its revision. After graph mutation they must
be discarded; plans cannot outlive the graph and must be checked with
`is_current` and the current enabled capability mask before the owner records
them. Device generation and resource generation still require validation by
the eventual Vulkan/scene owners; a capability mask alone is not device identity.

Passes may require renderer capability bits. The caller supplies features
actually enabled on the logical device; missing requirements fail compilation
with the pass ID and missing mask. Optional passes must be selected explicitly
when constructing the graph, rather than silently disappearing afterwards.

`semantic_bindings` validates a build-time-reflected list against pass resource
declarations and returns a semantic-to-descriptor mapping. It explicitly
rejects descriptor arrays and nonzero sets because the current native core
ABI does not support them. It does not parse reflection files, prove shader
access qualifiers, allocate descriptors or create Vulkan resources; these are
still required integration steps. Multiple descriptor views of the same logical
resource are also rejected by this first contract; future support needs explicit
view/subresource identity and alias-aware access validation.

The opt-in `bedray_graph_vulkan` target contains a source candidate for a
single-queue recording adapter. It validates the graph order and records broad
same-queue memory barriers plus colour-image layout transitions based on the
immediately preceding state. It does not submit, wait, allocate, own a Vulkan
device, or automatically connect to the renderer. Its callback must record
every pass without changing tracked image layouts, and failure requires the
entire command buffer recording to be discarded. It deliberately uses broad
`ALL_COMMANDS` barriers as a conservative starting point; performance is not
measured. The host must establish prior cross-queue semaphore dependencies,
resource ownership and lifetime, non-aliasing, actual image ranges, descriptor
access, device generation and final transitions for external consumers.
Resources declared as written before first read must be fully initialized by
the writer. The adapter supports only explicitly ranged colour images and a
limited set of layouts; it does not handle ownership transfers, presentation,
subresource aliasing, or arbitrary external image layouts. Its public recording
plan is internal trusted data and must not be modified between preparation and
recording. The adapter and its fake-command-buffer test compile and pass at L1;
no driver or live-renderer validation has been performed.

Pairwise hazards are transitive constraints, not a sequence of barriers:
emitting every listed image transition would use stale old-layouts. The graph
lists minimal and maximal resource uses
as entry/exit obligations, including multiple unordered readers; these lists do
not themselves provide the host's previous/next access or image layout. The graph must not schedule a
pass until those mappings and its descriptor/pipeline contract are defined.
External entry/exit synchronization is not implemented yet. Cross-frame history,
GPU completion, aliasing and multi-queue synchronization are not implemented.

The history subsystem's `write_after` and `read_producer` are external
submission dependencies. This planner does not consume them or schedule queue
waits. Until an integration layer supplies actual synchronization and ownership
transfer, history reuse must not rely on this graph to fulfil
`same_queue_barrier` or `cross_queue_semaphore` promises.

Allocation ID zero means unknown, not proof that resources do not alias. Before
recording, the owner must establish physical image/buffer identity and reject
or correctly synchronize overlapping memory. All planned passes currently
assume a single host-owned queue; a future multi-queue implementation requires
explicit queue assignment, ownership transfer and submission synchronization.

The opt-in `bedray_graph_frame` target adds `FrameScheduler`, a frame-level
transaction over the recording adapter. It leases a bounded set of transient
slots whose reuse requires a discard, host-proven completion or a proven idle
or lost device; rejects stale schedules, stale tickets and regressing serials;
records at most once per ticket; and holds a one-shot release callback for the
core recording. It refuses history reads whose producer is not proven complete
on the supplied timeline. It does not allocate GPU memory, insert cross-queue
semaphores, submit or wait, and it is not constructed by the current frame path.

The graph uses whole-resource uses only; it therefore may reject independent
subresources, and its pairwise hazards can be redundant. Adding finer ranges
must preserve explicit ordering and avoid deriving correctness from declaration
order. The graph, adapter, history bridge and frame scheduler build as part of
the native core, and four focused L1 contract tests pass (see
`RENDER_GRAPH_NOTES.md`). The core frame path still does not construct or
execute a graph, Slang reflection is not connected to pipeline creation, and no
Vulkan/Minecraft validation has been performed.
