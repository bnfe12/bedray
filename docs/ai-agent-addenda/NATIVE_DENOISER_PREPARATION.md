# Native AOT denoiser preparation

The active velocity pass and NRD/RR pack/compose no longer compile GLSL at
runtime. Their Slang modules preserve the existing live formulas and formats.
Velocity keeps its 368-byte input, rigid transforms, BDA inputs and RG16F output;
its native owner holds a distinct upload for every dispatch, including several
on the same command buffer. Pack uses format-specific R10/SNORM/RR entries;
compose remains a separate phase after the external denoiser. Native owns
descriptor/push packing and entry/exit barriers. Host images and vendor calls
keep their existing owners.

Extended storage image formats have an additive capability bit, queried from
physical support and intersected with actual enabled features. The optional
feature request is independent of primary tracing. Reflection checks the
368-byte velocity uniform, 56-byte pack push and sparse 11/9/6 binding sets.

SlangRuntimePool leases share one core per actual VulkanDevice identity across
path, atmosphere and upscaler lifetimes. The last lease closes the runtime only
after its native close succeeds; refusal preserves the lease for retry. Cached
programs remain runtime-owned. Exact recording callbacks capture the relevant
denoiser/velocity adapter. Native recording failures propagate to whole-command
discard; uncertain execute/enqueue outcomes remain pinned. Upscaler cleanup
submits pending encoder work and waits on its fence before freeing producer
inputs. Denoisers are destroyed before releasing their runtime lease.

## Verification (2026-10-29)

Windows native CI [40888830243](https://github.com/bnfe12/bedray/actions/runs/40888830243)
passed 24 core, one scene and one lifetime test on
`b8048c8c6f1ab0b44622b31ba0e085505d664fcf`. The final native changes in this
transfer are identical to that source. The pinned baseline passed 15+1+1.

The complete prepared Java tree, including independent RC numerical/source
work, passed 830 tests: 821 passed, nine skipped, zero failures/errors
(`:common:test`, 1m57s). All 26 prepared AOT entries compiled with Slang 2026.20.
These are L1/L2 checks; vendor dispatch, GPU format/driver behavior, visual parity
and game acceptance are not established by them. Guide-image allocation,
spatial upscalers and frame-generation preparation are separate migration work.

After synchronization with audit revision `24b0617`, the clean committed tree
`1d7a04bbea7003bf031b77360c894198f488f97f` passed **834 tests: 825 passed,
nine skipped**, all 24 committed AOT entries, in 1m46s. Windows native CI
[40936173315](https://github.com/bnfe12/bedray/actions/runs/40936173315)
passed 24+1+1 on that exact source. The release-every helper includes velocity;
the frozen Shaderc inventory now excludes the migrated velocity compiler.
This clean-tree result supersedes the mixed prepared-tree result above.
