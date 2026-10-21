# Iris post host: options and profiles

The source-only options milestone resolves one immutable configuration for each pass graph. All
editing is in memory. The pack, Iris's config, and saved option files are never rewritten.
Both loader entry points use the same host. No additional Vulkan owner or world-render hooks are added.

## Inputs and precedence

The host reads Java Properties from `shaderpacks/<exact pack filename>.txt`: a folder `MyPack`
uses `MyPack.txt`, a ZIP `MyPack.zip` uses `MyPack.zip.txt`. Encoding is Java Properties' ISO-8859-1
plus Unicode escapes, matching Iris. The file is limited to 64 KiB; symlinks/non-regular files are
refused and the open uses NOFOLLOW_LINKS. A malformed/unreadable existing file refuses activation.

Effective option values, lowest to highest priority:

1. Declarations in the shader source.
2. The optional session profile `-Dbedray.iris.profile=NAME`, including inherited profiles.
3. Explicit values in the saved option file.

Iris normally persists option values rather than a profile selector. The session profile switch is
a Bedray development control. Changing a pack, option file or profile requires a restart/host reprobe;
there is no file watcher or automatic in-game reload in this milestone.

## Source option dialect

- Boolean `#define NAME` / `//#define NAME`, confirmed by a literal `#ifdef NAME` or `#ifndef NAME`.
- Scalar/identifier `#define NAME value // [allowed values]`. The values list identifies an option;
  saved/profile values need not belong to that list, following Iris's config-file behavior.
- `const bool/int/float` options from Iris's configurable const-name table. Numeric consts require
  the same list annotation. Shadow-related names being configurable does not supply shadow rendering.
- Only a single numeric literal or identifier is accepted as an override, at most 128 characters.
  Arbitrary expressions, multiline replacement values and function macros are outside this dialect.
- `GL_`, `MC_`, `IRIS_`, `BEDRAY_` and double-underscore defines are reserved for the engine/compiler.
  Saved options cannot fabricate capability flags through those names.

Discovery covers `.vsh/.fsh/.glsl/.inc` under this shaders root and their literal include closure,
including other file extensions. Block comments are excluded; commented boolean defines remain
discoverable. Conditions are not evaluated during discovery, since enabling an option can introduce
an inactive include. Missing/escaping includes are rejected by native preprocessing if they become live.
Macro-generated include paths outside the indexed closure are unsupported and named in diagnostics.

Cross-file conflicting declarations make an option ambiguous. Requesting it refuses the graph.
This is conservative across the whole root, rather than Iris's connected include components; it can
refuse an otherwise valid pack whose disconnected sources intentionally use different defaults.
Unknown saved keys are ignored with diagnostics. Invalid boolean saved values use the source default
with a diagnostic; invalid profile values refuse the pack. At most 4096 options/overrides are accepted.

## Profiles and conditional properties

Supported profile tokens in `shaders.properties` are `profile.PARENT`, `BOOLEAN`, `!BOOLEAN`,
`OPTION=value`, `OPTION:value` and `!program.compositeN` / `!program.final`. Includes apply in source
order, with later values overriding earlier ones. Missing parents, cycles, unsupported programs and
unknown options are refused. Limits: 256 profiles, depth 32, 16384 expanded-profile tokens.
The most specific matching profile determines disabled programs; ties retain declaration order.
The requested baseline profile can become a custom configuration after saved overrides.

Property `#if/#ifdef/#ifndef/#elif/#else/#endif`, `#define`, `#undef` and `#error` use shaderc's
expression evaluator. Properties themselves remain opaque text, so option names in profile/screen
values and backslashes survive. A trailing backslash joins property lines; unfinished continuations
are refused. Properties includes are unsupported. Metadata is capped at 1 MiB of characters.

Property conditions see numeric/string option values and enabled boolean names as `1`; disabled
boolean names are absent. `true`/`false` are `1`/`0` there. `program.<post pass>.enabled` uses the
same integer expression evaluator. Both profile exclusions and false enabled expressions remove
passes before translation/allocation. Disabling every pass reports DISABLED_FOR_SESSION and keeps
native rendering. Conditional profiles are reevaluated against effective options until stable,
with at most eight evaluations; unresolved/cyclic configuration is refused.

Active keys are restricted to screen/slider metadata, profiles, enabled expressions, optional feature
metadata and an empty required-feature list. Active unsupported renderer properties still refuse
the pack; a property in an inactive branch no longer causes that refusal. The raw capability report
remains an upper bound, and advanced GLSL stages/ray-tracing source can still be refused conservatively
even if an option would disable it.

Only `BEDRAY_VULKAN=1` and `BEDRAY_IRIS_POST=1` are explicitly advertised to both GLSL and properties.
Shaderc also supplies its own language/compiler macros. World/version/vendor defines and Iris feature
parity are pending; this host does not announce missing Iris capabilities as implemented.

## Evidence and next boundary

Java compilation covers common/Fabric/NeoForge. The unpackaged color example compiles to SPIR-V with
source defaults and with BRIGHT, including conditional properties, inherited options and an optional
composite program. No tests, GPU dispatch, game sessions, product JAR or release were produced.
Image equivalence and general pack compatibility remain unverified.

The next implementation boundary is a typed frame/guide contract with the world renderer, followed
by a multi-target resource graph. Options must remain part of any future translation-cache identity:
a pack SHA alone cannot identify a program compiled with different option/engine values.

Format attribution: Iris contributors, LGPL-3.0; see THIRD_PARTY_NOTICES.md and the upstream
[option implementation](https://github.com/IrisShaders/Iris/tree/26.3/common/src/main/java/net/irisshaders/iris/shaderpack/option).
