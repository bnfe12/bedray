# Sodium decode recording

`bedray_core_decode_sodium` is an additive 56-byte ABI-v2 call. Java supplies
borrowed input/output addresses, quad count, registered program and the host
command buffer. Native validates the unsigned, aligned, nonwrapping, disjoint
ranges (80/112/optional 4 bytes per quad), actual enabled BDA/Int64 capabilities,
program shape and device dispatch limit. It owns push packing, dispatch groups
and entry/exit memory dependencies. The existing host AS builder still orders
the decoded geometry before acceleration-structure construction.

Several section decodes may share one command buffer. Core records retain their
programs until exact host completion/discard. The adapter borrows its cached
program from SlangRuntime and does not close it independently. A failed call
requires discarding the whole recording; native never submits or waits.

## Verification (2026-10-29)

Native Windows CI [40884435049](https://github.com/bnfe12/bedray/actions/runs/40884435049)
passed on source `1cc8ce657ffec41fb9580a0b933731fb55b025eb`: 22 core tests,
one scene test and one lifetime test. The pinned baseline passed its 15+1+1.
The new no-GPU test covers layout, range rejection, adjacent staging flags,
high-bit device addresses, dispatch rounding/limits, barrier order and failure.

The full prepared Java tree passed 794 tests: 785 passed, nine skipped,
zero failures/errors (`:common:test`, 59 seconds). Two new FFM tests cover the
descriptor and range checks. Shader math is unchanged. These are L1/L2 results;
no new GPU or game execution is claimed.
