# Third-Party Notices

This file lists the third-party components related to Octant and the licences that apply to them.
It exists for **distribution and compliance review**.

Octant's own code and assets are covered by the repository's `LICENSE` (MIT). Third-party components
are **not** covered by MIT; each keeps its own licence.

---

## 1. What ships inside the mod jar

**The mod jar bundles no third-party runtime library at all.**

Its only third-party asset is an embedded **font subset** (see §2). This is not a promise but an
asserted property of the build: `verifyModJar` fails if any PDF or charting package (for example
`org/apache/pdfbox/**`, `org/apache/fontbox/**`, `org/jfree/**`) appears in the mod jar. Measured on
all six loader combinations, each jar is roughly 5.7 MB with 404–411 entries, carrying the shared
core classes and the font subsets only.

## 2. Libraries used by the offline tooling in this repository

The repository's `pipeline` module (analysis, export and the offline report tooling) declares the
following build dependencies. They are used **when running those tools**, not distributed with the
mod jar:

| Component | License | Obligation |
| --- | --- | --- |
| Apache PDFBox | Apache License 2.0 | Keep the `NOTICE` and licence text with any redistribution |
| PDFBox IO | Apache License 2.0 | Same as above |
| FontBox | Apache License 2.0 | Same as above |
| Apache Commons Logging | Apache License 2.0 | Same as above |
| JFreeChart | **LGPL 2.1** | See the note below |

Licence texts kept in this repository: `docs/verification/licenses/` (for example `LGPL-2.1.txt`).

### Note on JFreeChart (LGPL 2.1)

LGPL 2.1 adds two obligations when the library is distributed together with other code: the licence
text must accompany the distribution, and recipients must be able to **replace** the library
(relinking). Merging an LGPL library into a single jar makes replacement hard, which is a common
rejection point on mod distribution platforms.

The handling in this project is therefore:

- the mod jar itself **does not contain** JFreeChart or any other third-party runtime library, so the
  distributed mod artifact carries no LGPL component;
- if you redistribute the offline tooling from this repository yourself, keep the LGPL 2.1 text and
  make sure `jfreechart` remains replaceable.

## 3. Embedded report font

| Component | License | Obligation |
| --- | --- | --- |
| Report font (`ReportFontResolver.BUNDLED_ASSET_FAMILY`) | SIL Open Font License 1.1 | Licence text ships inside the module resources; the report itself contains no external references; only a **subset** is embedded |

The report declares the font's provenance in its metadata (`provenance = bundled-ofl-asset`,
`licence = SIL-OFL-1.1`, `licensedUnderRfnRename = true`, i.e. the font's reserved name rules are
honoured). See the `reportFont` section of an exported `manifest.json`.

## 4. Components that are neither covered nor distributed

- **Minecraft** (client/server) — owned by Mojang / Microsoft, **not** distributed here; users need
  their own legitimate copy.
- **Minecraft Forge / NeoForge / Fabric** and their loader code — under their own licences, **not**
  distributed here.
- **ForgeGradle / NeoGradle / ModDevGradle / Fabric Loom and MCP mappings** — used **at build time**
  only; they never enter an artifact.

The mod jar contains no Minecraft or loader classes; it is remapped, so it holds only this project's
own classes plus the embedded font subset listed above.

## 5. Data and privacy (not a licence matter, but a distribution disclosure)

- The mod **reads only local data** (the save, statistics, quest progress, the player's own map
  markers, mod recipes).
- **No network access at runtime and nothing is uploaded** (an exported `manifest.json` records
  `networkCallsMade: 0` and `uploadedByMod: false`).
- A report is generated **only when the player runs an export**, and consent can be withdrawn at any
  time with `/octant revoke`.
- A report is a **single local file**; deleting it deletes it completely.

## 6. Maintenance discipline

- Any new third-party component that would be distributed must be registered **in this file**
  (licence + obligation) before it may enter an artifact.
- The build asserts that **only whitelisted artifacts may enter the jar**; changing the whitelist
  requires updating this file in the same change.
