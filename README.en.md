# Octant

A **local, voluntary, revocable** player-behaviour insight mod for Minecraft: it turns a stretch of
gameplay in one save into a **single-file HTML report** generated on the player's own machine, so
modpack authors can see *what players actually played, where they got stuck, and how they fought*.

> 中文: [README.md](README.md)

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

## 2. Privacy design (checkable, not a promise)

| Mechanism | How |
|---|---|---|
| Off by default | The consent gate defaults to closed; a missing or corrupt consent file is refused |
| No network at runtime | A build-time guard scans every source file and fails on network APIs |
| Player-triggered only | Export hangs off a command and an ESC-menu button; no timers |
| Single file, no external references | Styles and a font subset are **inlined**; the report opens offline and can be forwarded whole |
| Revocable | `/octant revoke` stops collection immediately; a wipe covers events, ledgers and the salt, and returns a **deletion receipt** |
| Identity minimisation | Player key = salt-derived pseudonym; opponent key = a **session-scoped ordinal** `<entityType>#<n>` (deriving it from an entity UUID or a custom name is forbidden) |
| Unregistered fields are dropped | The redaction pipeline clears the value of any key absent from the field registry and records a violation (fail-closed) |
| Suppress instead of invent | Metrics with too small a sample are reported as "undetermined (insufficient sample)" with a reason code — never drawn as 0 |

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

## 4. Usage

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

## 5. What the report looks like

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

## 6. Building from source

Module layout: `common` (platform-neutral collection model and contracts), `pipeline` (second-stage
processing and three output forms), `capture-core` (**the platform-neutral capture core shared by all
six combinations** — not a single line references `net.minecraft`, enforced by `assertNoMinecraftRefs`),
and `<loader>/<MC version>` (the platform adapters). Platform modules are excluded from the build by
default (so pure-Java unit tests stay offline); enable them with `-Poctant.platforms=true`.

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

## 7. License and third parties

- This mod: **MIT** 
- The **report font** shipped inside the jar: SIL OFL-1.1, licence text included
  (`…/font/OFL-1.1-LICENSE.txt`).
- The mod jar **bundles no third-party runtime library** .
- No Mojang assets are bundled.
- Third-party inventory and licence texts: `THIRD-PARTY-NOTICES.md`, `docs/verification/licenses/`.
