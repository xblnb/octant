# Octant

A **local, voluntary, revocable** player-behaviour insight mod for Minecraft: it turns a stretch of
gameplay in one save into a **single-file HTML report** generated on the player's own machine, so
modpack authors can see *what players actually played, where they got stuck, and how they fought*.

> 中文: [README.md](README.md)

| | |
|---|---|
| Supported combinations | **Forge / NeoForge / Fabric × Minecraft 1.20.1 / 1.21.1** — all six build and pass artifact checks (see §4) |
| Runtime dependencies | **None** — the report is hand-drawn inline SVG plus an embedded font subset |
| Network | **Zero network at runtime**, enforced by a build-time guard that scans the sources for network APIs (a hit fails the build) |
| Privacy default | **Collection is off by default** (fail-closed: a missing or unreadable consent file means "do not collect") |
| License | **MIT** |
| What this repository is | **Source and build scripts only** — no test data, no build artifacts, no sample reports |

---

## 1. What it is

A diagnostic tool for **modpack authors**. A player, on their own computer, exports a report about
their own playthrough of one save. Every statement in that report is computed **locally**, and the
player can revoke consent or delete all local data at any time.

The report answers four questions:

1. **What you played** — pacing of progression, breadth of content reached, what was never touched
2. **Where you got stuck** — long stretches without progress, repeatedly failed steps
3. **How you fought** — engagement durations and outcomes, where the damage came from
4. **Numeric balance** — how each numeric threshold actually behaved (including "not enough sample ⇒ undetermined")

Plus a **privacy statement** section and a **collapsed-by-default machine appendix** (sources for
every number, algorithm specs, suppression details, machine-readable fragments).

## 2. What it deliberately does **not** do (please read this part first)

- **It uploads nothing.** Artifacts are written to the local disk only; the mod makes no network request.
- **It never collects or exports automatically.** Collection requires explicit player consent, and an
  export only happens when the player **runs a command in game**. There are no timers and no automatic
  triggers in the code — "no automatic export" is *unrepresentable*, not merely promised.
- **It is not server administration and not multiplayer monitoring.** It is a tool for players to look
  at their own playtime.
- **It does not speak for anyone else's experience.** A report describes behaviour observed in **one
  save, on one machine**. Where the sample is too small it says "undetermined" instead of guessing a number.

## 3. Privacy design (checkable, not a promise)

| Mechanism | How | Where you can verify it |
|---|---|---|
| Off by default | The consent gate defaults to closed; a missing or corrupt consent file is refused | `common/privacy/…`; in-game `/octant status` |
| No network at runtime | A build-time guard scans every source file and fails on network APIs | `assertNoNetworkApis` in `pipeline/build.gradle` |
| Player-triggered only | Export hangs off a command and an ESC-menu button; no timers | `/octant export`; the ESC-menu button |
| Single file, no external references | Styles and a font subset are **inlined**; the report opens offline and can be forwarded whole | the report's `<style>` and font assets; the self-check also asserts "no remote references" |
| Revocable | `/octant revoke` stops collection immediately; a wipe covers events, ledgers and the salt, and returns a **deletion receipt** | `/octant revoke`; the wipe action |
| Identity minimisation | Player key = salt-derived pseudonym; opponent key = a **session-scoped ordinal** `<entityType>#<n>` (deriving it from an entity UUID or a custom name is forbidden) | `OpponentKeyAllocator`; `docs/design/data-contracts.md` §2.4 |
| Unregistered fields are dropped | The redaction pipeline clears the value of any key absent from the field registry and records a violation (fail-closed) | `FieldRegistry` + `docs/privacy/schema/` (kept in sync by tests) |
| Suppress instead of invent | Metrics with too small a sample are reported as "undetermined (insufficient sample)" with a reason code — never drawn as 0 | the report's suppression detail and machine appendix |

Where local data lives (players can inspect and delete it themselves):

```
<save>/octant/events/raw/      collected raw events (local only, never exported)
<save>/octant/meta/salt.bin    per-save pseudonymisation salt (never exported)
<save>/octant/state/           session and truncation ledgers
<gameDir>/config/octant/       consent state and consent history ledger
<gameDir>/octant/exports/      export artifacts (the report lands here)
```

> Upgrading from an older build (from the `mcinsight` naming era) migrates these directories
> **automatically, once**; existing consent is not lost. The `mcinsight` strings still present in the
> code are therefore **not leftovers**: they are the legacy directory name and the criteria used by
> the cleanup rules (`OctantPaths.LEGACY_NAME`, `DataEraser` deleting `mcinsight*.log`). Renaming them
> would break the migration.

## 4. Supported platforms and installation

All six combinations build and pass artifact verification (`verifyModJar` asserts entry by entry).
Pick the row that matches your instance:

| Combination | Loader version (pinned at build time) | Java | Artifact |
|---|---|---|---|
| Forge × 1.20.1 | Forge 47.4.23 (official mappings) | 17 | `forge/1.20.1/build/libs/octant-0.1.0.jar` |
| Forge × 1.21.1 | Forge 52.1.16 | 21 | `forge/1.21.1/build/libs/octant-0.1.0.jar` |
| NeoForge × 1.20.1 | NeoForge line 47.1.106 (`net.neoforged:forge`, legacy) | 17 | `neoforge/1.20.1/build/libs/octant-0.1.0.jar` |
| NeoForge × 1.21.1 | NeoForge 21.1.251 | 21 | `neoforge/1.21.1/build/libs/octant-0.1.0.jar` |
| Fabric × 1.20.1 | Loader 0.19.5 / Fabric API 0.92.12+1.20.1 | 17 | `fabric/1.20.1/build/libs/octant-0.1.0.jar` |
| Fabric × 1.21.1 | Loader 0.19.5 / Fabric API 0.116.17+1.21.1 | 21 | `fabric/1.21.1/build/libs/octant-0.1.0.jar` |

Installation: download the jar matching your instance from
**[Releases](https://github.com/xblnb/octant/releases/latest)** (release file names already carry the
loader and Minecraft version) and drop it into the instance's `mods/` folder. If you build from source
instead, all six modules produce a jar with the **same name** (`octant-0.1.0.jar`), so renaming it to
something like `octant-forge-1.20.1.jar` is recommended for self-hosting.

**Client-side is enough, server-side also works**: the mod registers no network channel and changes no
protocol (`displayTest = "IGNORE_ALL_VERSION"`), so a client with it can join a server without it, and
vice versa.

## 5. Usage

**Entry point**: the **"Octant 报告"** button at the bottom of the ESC (pause) menu — it opens the
export panel. Commands work too:

| Command | Effect |
|---|---|
| `/octant status` | Show consent state, collection counters and artifact locations (read-only) |
| `/octant grant` | Explicitly allow collection and local export (tells you what is *not* done, revocable anytime) |
| `/octant revoke` | Revoke consent (stops collection immediately) |
| `/octant export` | Export one report bundle **now** (the only action that produces artifacts) |
| `/octant verify` | Headless self-check: proves the engine runs from inside the shipped jar |

Artifacts after an export:

```
<gameDir>/octant/exports/<exportId>/
├── report.html              ← double-click this: the final report (single file, works offline)
├── report.md                plain-text version
├── analysis.json            metrics and conclusions (machine-readable)
├── metrics.json / conclusions.json
├── events.anonymized.jsonl  anonymised event stream
├── redaction_report.json    redaction detail
├── PRIVACY-README.txt       what this bundle contains and what it does not
└── manifest.json            provenance and version declarations
```

## 6. What the report looks like

Four content sections, a privacy section, and a **collapsed-by-default** machine appendix; the whole
report is one **single file with no remote references**. All figures are hand-drawn inline SVG:

- **Cumulative progression**: ridge lines per content group plus cumulative lines, with hover values
- **Player profile**: a seven-axis radar with four concentric grid rings and hoverable axis points.
  **Axes without a score are not drawn into the polygon** and are labelled "undetermined (insufficient
  sample)" below the chart and in the table — drawing "not measured" as 0 is forbidden in this project
- Bars for **stalls / deaths / economy and thresholds**, each carrying its sample basis and suppression reason

Every **tip** in the report comes from a checkable library
(`pipeline/src/main/resources/content/tips.tsv`, with sources and applicability conditions), and is
phrased as a remark from someone nearby rather than a lecture.

## 7. Building from source

Module layout: `common` (platform-neutral collection model and contracts), `pipeline` (second-stage
processing and three output forms), `capture-core` (**the platform-neutral capture core shared by all
six combinations** — not a single line references `net.minecraft`, enforced by `assertNoMinecraftRefs`),
and `<loader>/<MC version>` (the platform adapters). Platform modules are excluded from the build by
default (so pure-Java unit tests stay offline); enable them with `-Poctant.platforms=true`.

**Two wrappers, not one** — this is not fastidiousness, it is forced by plugin version constraints:

| Wrapper | Gradle | Used for |
|---|---|---|
| `./gradlew` (this repository also ships a serialising `gradlew-locked.bat`) | **8.9** | Forge × 1.20.1, NeoForge × 2, Fabric × 2 |
| `tools/gradle96/gradlew` | **9.6.1** | **Forge × 1.21.1** |

Why (all measured coordinates, not inference): ForgeGradle 6 hard-rejects Gradle ≥ 9; ForgeGradle 7
(the line used for 1.21.1) requires Gradle ≥ 9.3; Fabric Loom 1.7.4 fails on Gradle 9 with
`LoomGradleExtensionImpl`. So Forge × 1.21.1 runs through the second wrapper, and both wrappers
**share one repository-level lock** so they cannot step on each other.

```bash
# 1) five combinations (Gradle 8.9)
./gradlew -Poctant.platforms=true \
  :forge:mc1201:verifyModJar :fabric:mc1201:verifyModJar :fabric:mc1211:verifyModJar \
  :neoforge:mc1201:verifyModJar :neoforge:mc1211:verifyModJar

# 2) Forge × 1.21.1 (Gradle 9.6.1, separate switch)
tools/gradle96/gradlew -Poctant.platforms=true -Poctant.platforms.forge1211=true :forge:mc1211:verifyModJar

# 3) pure-Java tests (no -Poctant.platforms needed, runs offline)
./gradlew :common:test :capture-core:test :pipeline:test
```

What `verifyModJar` means: it asserts **entry by entry** what a jar must contain (platform metadata,
icon, `content/tips.tsv`, font assets, platform entry class, the `capture/*` shared-core classes) and
what it must **not** contain (the PDF/charting libraries). Measured on all six combinations: roughly
**5.7 MB / 404–411 entries** each, every jar carrying the same 26 shared-core classes and 2 font subsets.

Test baselines, re-measured in a **clean clone of this published repository**: `:common:test` 248
cases / 0 failures, `:capture-core:test` 30 cases / 0 failures, `:pipeline:test` **103 cases / 6
failures** (the six pre-existing red criteria described in §8.4).

> This repository does not publish test data (`samples/` and friends), so the guards that sample
> exported artifacts (the "measured" part of `:checkContractConformance`) report **undetermined** and
> exit with code 3. That is the project's three-valued semantics working as intended
> (missing ≠ passing), not a build failure.

## 8. Compatibility and known issues

**Compatibility**: the six combinations in §4. Anything else (earlier MC versions, Quilt, …) is
unverified and outside what this release claims.

**Known issues** (listed honestly; anything unfinished is not described as finished):

1. **10 of 24 event types are wired.** Wired: session start/heartbeat/end, advancement gained, player
   death, biome visited, item action, container snapshot, combat started/ended. The other 14 (quests,
   machines, stall segments, …) are **explicitly registered as "not wired"** in code
   (`ObservationAdapter.unwiredTypes()`, with a test asserting the two sides partition the contract
   catalogue) rather than quietly pretended to be covered.
2. **The export location does not match the "game directory" semantics.** The implementation treats the
   **parent** of the save directory as `gameDir` (`worldDir.getParent()`), so artifacts land in
   `<instance>/saves/octant/exports/` where the strict semantics would be `<instance>/octant/exports`.
   The displayed path and the actual location now share a single source, but the root cause is unfixed.
3. **The report UI is currently Chinese-only.**
4. **Six `:pipeline` criteria are red** (PDF-era expectations, a pinned fingerprint, and two genuine but
   **artifact-neutral** wording/font issues). Measured against real artifacts: none of the six changes
   anything a reader sees in the HTML product. Reproduced identically in a clean clone of this repository.
5. **Two pieces of NeoForge × 1.21.1 metadata rest on documentation only, with no first-hand artifact
   evidence**: the manifest file name `neoforge.mods.toml` and the dependency field `type` (rather than
   `mandatory`). Getting either wrong means "the build is green and nothing happens in game", so this
   item **can only be closed by one real game launch**; that launch was not performed for this
   delivery, so it is registered as unverified.
6. **Not published** on CurseForge / Modrinth yet; distributed via GitHub releases and local builds.

## 9. License and third parties

- This mod: **MIT** (with a scope note: Mojang assets are not covered; third-party dependencies are not
  relicensed).
- The **report font** shipped inside the jar: SIL OFL-1.1, licence text included
  (`…/font/OFL-1.1-LICENSE.txt`).
- The mod jar **bundles no third-party runtime library** (the PDF / charting libraries were moved off the
  product path and now live only on the offline export-tool side).
- No Mojang assets are bundled.
- Third-party inventory and licence texts: `THIRD-PARTY-NOTICES.md`, `docs/verification/licenses/`.

## 10. Further reading

- [`docs/design/data-contracts.md`](docs/design/data-contracts.md) — the data contract (build-time guards parse its closed sets live)
- [`docs/privacy/schema/`](docs/privacy/schema) — export JSON Schemas and suppression reason codes
- [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) · [`LICENSE`](LICENSE)

> This is a **source release**: the internal design/verification ledgers, test data and build artifacts
> are not published with the source, so comments in the code do reference `docs/…` paths that point at
> those unpublished internal documents.
