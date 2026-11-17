# Bedray — agent instructions

## Public API policy

Everything intended for other mods (world/scene data, material overrides, settings, light providers, compute
services) must be designed against the public `renderer-api` module, not reached into `common` internals from
outside. Internals of `common` may change without notice; only `renderer-api` and the documented file contracts
(see `docs/`) are stable.

- `common/src/main/java/...` is host implementation detail. Add-on-facing types live in `renderer-api`.
- Sensor/resource-pack file contracts (`*.bedray.json` sidecars, `shaders/bedray/...` entries) are user-facing
  contracts too: any new field or file is a compatibility promise — document it in `docs/` before landing it.
- Before proposing changes to game behavior, check `docs/KNOWN_ISSUES.md` and the latest entries in
  `CHANGELOG.md` so a fix does not reopen a documented dead end.
