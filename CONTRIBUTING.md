# Contributing

Thanks for helping. Bedray is an alpha, so the most useful contributions are **test reports** and **clear bug reports**.

## Testing and bug reports

* Read `docs/TESTING.md` and use the issue forms ("Bug report", "GPU test report").
* Only an NVIDIA GeForce RTX 5060 Ti has been tested. AMD Radeon (RDNA 2+) and Intel Arc reports are especially wanted.
* Remove personal paths and names from logs before sharing them.

## Code changes

1. Open an issue first for anything larger than a small fix, so we can agree on the direction.
2. Build as described in `docs/BUILDING.md` and run `gradlew :common:runShimTests`. The tests check Java-level logic only; they
   are **not** evidence that a visual feature works in the game.
3. State what you verified: the log line or screenshot that shows the feature working. "It compiles" is not enough for graphics
   changes. Changes to presentation, resolution or matrices need before/after screenshots.
4. Do not add binaries (DLLs, jars, SDK files) to the repository. Vendor SDKs and their headers are not redistributed here.
5. Keep user-facing text honest: a feature that is not implemented must be hidden or labelled "not implemented", and
   `docs/FEATURE_MATRIX.md` must match.
6. Files are UTF-8 without BOM.

By contributing you agree that your contribution is licensed under LGPL-3.0-or-later.
