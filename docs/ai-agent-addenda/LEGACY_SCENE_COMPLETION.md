# Legacy scene completion boundary

The reference Java scene shares mapped staging, instances, its section table,
TLAS and scratch. Its old three/five-frame retirement rules did not establish
that the GPU had finished using them. Before opening a new local command buffer,
the host now submits/drains its existing encoder with an exact fence and runs
the recording callbacks. One completed drain permits one legacy world update.
An outstanding or unknown recording cannot authorize another update.

Section geometry, replaced entity BLAS and unused opacity micromaps are collected
at that boundary. Micromap reference counts still retain objects used by live
BLAS. Growing micromap scratch retires the old buffer until the next proof;
it no longer waits/frees inside an open command buffer. Frame counters still
select staging capacity and throttle work; they do not prove completion.

The gate follows the actual world owner, including native-scene startup fallback.
The native scene retains its existing per-recording resource leases and does
not enter this serial reference boundary. This intentionally trades reference
path concurrency for correct shared-input ownership.

## Verification (2026-10-29)

Five focused no-GPU tests passed through a standalone JUnit harness. They cover
delayed completion, wrong/reused command-buffer identities, one-use drain
permission, failed collection, unknown/submitted/discarded tickets and the
before-command-buffer integration order. Java source review covers the three
retirement queues. No GPU timing, game run or visual parity is claimed.
