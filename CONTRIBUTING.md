# Contributing to Bedray

Bedray is an early alpha. Reproducible bug reports, GPU tests and focused fixes are especially useful.

## Report a problem or test your GPU

Use the [issue forms](https://github.com/bnfe12/bedray/issues/new/choose).
Include your exact build, Minecraft and loader versions, GPU, driver and steps to reproduce.
For visual problems, add a screenshot and the relevant settings. AMD Radeon and Intel Arc reports help expand coverage.

Review logs and diagnostics before sharing them: remove personal paths, account names, server addresses and credentials.
See the [testing guide](docs/TESTING.md) and [privacy policy](PRIVACY.md).
Report security vulnerabilities through the [security policy](SECURITY.md).

## Propose a change

1. Discuss larger changes in an issue before starting implementation.
2. Follow the [build guide](docs/BUILDING.md), create a focused branch and open a pull request against `main`.
3. Explain the problem, the resulting behavior and what you verified. Run the relevant checks;
   Java changes normally include `gradlew :common:runShimTests`.
4. Rendering changes need evidence from a real run. State the build and settings, include a log or screenshot,
   and distinguish source checks from in-game validation. Update [feature status](docs/FEATURE_MATRIX.md) when it changes.
5. Keep binaries, vendor SDK files, credentials, personal paths and internal working notes out of commits.
   Source and documentation files use UTF-8 without BOM.

By contributing, you agree that your contribution is licensed under LGPL-3.0-or-later.
