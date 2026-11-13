# Native library licences and delivery

[Player download guide](RUNTIME_LIBRARIES.md) · [Third-party notices](https://github.com/bnfe12/bedray/blob/main/THIRD_PARTY_NOTICES.md)

Review date: **2026-11-13**. This records Bedray's current distribution policy and a proposed improvement to library setup.
It does not approve a new vendor binary for release. A changed SDK, binary or licence requires a fresh review.

## Current package policy

| Component | Reviewed source | Current delivery policy |
| :--- | :--- | :--- |
| `bedray_core.dll` | Bedray source and project licence | Distribute with its matching JAR and corresponding source; it is not a vendor runtime. |
| `amd_fidelityfx_vk.dll` | FidelityFX SDK 1.1.4, [MIT licence](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v1.1.4/LICENSE.txt) | May be bundled by the existing opt-in build, with the pinned binary and licence. Preserve attribution. |
| `libxess.dll` | XeSS SDK 3.0.2, [Intel Simplified Software License](https://github.com/intel/xess/blob/v3.0.2/LICENSE.txt) | May be bundled unmodified under the existing build policy. Include its licence and third-party notices; do not imply Intel endorsement. |
| DLSS / NGX runtimes | DLSS 310.9.16, revision `9981c6350dc58c0ce21f024fd65e31e851c31689`, [RTX SDK terms](https://github.com/NVIDIA/DLSS/blob/9981c6350dc58c0ce21f024fd65e31e851c31689/LICENSE.txt) | Official user download and local import. Bedray has not approved redistribution in player packages. |
| NRD runtime | NRD 4.18.0, revision `8f12a22fc90e6a3c7f5da4c9c3103633c842963a`, [RTX SDK terms](https://github.com/NVIDIA-RTX/NRD/blob/8f12a22fc90e6a3c7f5da4c9c3103633c842963a/LICENSE.txt) | Local build/import. The same per-release distribution review is needed before bundling. |
| Streamline | 2.14.1, revision `2122257e0fce486f91b385aa63b9a09b0a34b363`, [core licence](https://github.com/NVIDIA-RTX/Streamline/blob/2122257e0fce486f91b385aa63b9a09b0a34b363/license.txt) | Core code is MIT, with separate terms for some components. Review the exact runtime set, including its [NGX licence](https://github.com/NVIDIA-RTX/Streamline/blob/2122257e0fce486f91b385aa63b9a09b0a34b363/external/ngx-sdk/license.txt) and [third-party notices](https://github.com/NVIDIA-RTX/Streamline/blob/2122257e0fce486f91b385aa63b9a09b0a34b363/3rd-party-licenses.md). The whole SDK archive is not treated as one MIT component. |
| Bedray SDK wrappers | `native/` and the licences of linked SDK material | Not included in current player packages. Review the build inputs and resulting object code before distributing a wrapper. |
| Partner or restricted SDK files | The agreement accompanying the exact SDK | Local setup only unless that agreement expressly permits the intended distribution. Public download availability does not grant rights to a different SDK. |

The AMD and Intel bundle is controlled by [`gradle/loader-module.gradle`](https://github.com/bnfe12/bedray/blob/main/gradle/loader-module.gradle).
It checks SHA-256 for both DLLs and their licence files, and also checks Intel's third-party notice file.
Default developer builds omit the binaries. No vendor binaries are added by this change.

## What the NVIDIA review must establish

The reviewed RTX SDK terms allow some object-code distribution inside an application, subject to conditions.
**Static linking is not, by itself, a blanket prohibition on distribution.** Conversely, a public SDK repository does not
authorise every packaging arrangement. The base agreement's sections 1–2 govern application distribution; section 4
restricts standalone SDK distribution and subjecting SDK material to conflicting open-source obligations.
The supplement adds requirements including attribution and, for specified SDKs, notification before commercial release.
These provisions and their applicability to the exact Bedray distribution must be checked together.
[Source: pinned DLSS licence and supplement](https://github.com/NVIDIA/DLSS/blob/9981c6350dc58c0ce21f024fd65e31e851c31689/LICENSE.txt).

Before shipping SDK-linked objects, resolve how their terms coexist with Bedray's LGPL distribution obligations.
Separate notice files alone do not establish compatibility. Record the exact binaries, source/build revision,
terms, required notices and any vendor correspondence. Review NRD's own pinned agreement as well.
A standalone collection of vendor DLLs must not be assumed to qualify as an application with additional functionality.
User-side downloading reduces redistribution work; it does not remove obligations that apply to the integration itself.

## Delivery plan

The shortest useful setup has three routes:

1. **Included with Bedray.** The matching renderer core and each separately reviewed redistributable runtime arrive
   with the release. Extract them locally with the required licences. Only reviewed hashes enter that package.
2. **Get from the vendor.** For an optional component not included, show its official source, compatible version,
   licence and missing files. Open the browser, then let the user import the downloaded archive.
3. **Local SDK setup.** Explain build or access requirements for components that cannot yet be supplied as player-ready files.
   Keep these separate from ordinary runtime downloads.

The existing source already provides catalogue-based scanning, local ZIP/DLL import, x64 PE checks, archive path rejection,
size limits and staged file placement. The current screen lacks a direct download-guide action and does not reliably
express feature readiness when a project wrapper is missing. These are implementation tasks, not completed UI changes.

### Next manager iteration

- Show the files required for the selected feature, including its wrapper. Hide unrelated planned technologies from normal setup.
- Use clear states: **Included**, **Download needed**, **Build needed**, **Restart needed**, and **Active** after successful runtime initialisation.
- Offer **Official download**, **Import archive** and **Open folder**. Keep licence details accessible without putting SDK internals into the main setup flow.
- Pin each reviewed package in a manifest: feature, version, source revision, official URL, SHA-256, expected files,
  destination, licence source, required notices and delivery route. Bind the manifest to a Bedray release; do not execute a remote manifest.
- Preserve vendor notices alongside imported files. A PE header check establishes file structure, not publisher authenticity;
  verify trusted package hashes before treating a download as reviewed.
- If a direct downloader is added later, review the host's download terms, show the user what will be fetched,
  verify the pinned package, restrict destinations and retain a local-import route. Update the privacy policy for any new network behavior.

No automatic downloader, new network behavior, SDK licence acceptance or vendor-binary redistribution is implemented by this documentation change.
