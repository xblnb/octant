# Report font assets — provenance, licence, and build recipe

| Field | Value |
| --- | --- |
| Maintained by | the vendor registry (`pipeline/src/main/resources/**`, **assets only, no Java**) |
| Consumers | `analytics-engineer` (`PdfWriter` / `PdfReport` — `**/*.java` in this module) |
| Files here | `NotoSansSC-Report-wide.ttf` (**current**), `NotoSansSC-Regular-subset.ttf` (**transitional**), `OFL-1.1-LICENSE.txt`, `OFL-1.1-canonical-template.txt`, `README.md` |

## 0a. Transition state — WHY TWO FONTS ARE PRESENT

**Both assets are currently in this directory on purpose. Do not delete either without reading
this.**

| Asset | Size | SHA-256 (first 16) | Role |
| --- | --- | --- | --- |
| `NotoSansSC-Report-wide.ttf` | 7,608,592 | `d8fc8e27c6e27d14` | **current** — wide coverage, subset by the library at export time |
| `NotoSansSC-Regular-subset.ttf` | 370,896 | `eca8a1345dbdff00` | **transitional** — still named by the unchanged classpath path |

The wide asset is the ruled design. But the Java loading path still resolves the **narrow**
filename:
```java
// ReportFontResolver.java
"/com/octant/pipeline/report/font/NotoSansSC-Regular-subset.ttf"
```
`pipeline/src/**/*.java` is **not** this subtree's to change. While the narrow asset was
removed under the "retire it" instruction, that left the classpath resource **absent** and the
font load failing. It was therefore restored, and **both files stay until the writer switches
its path to the wide asset**.

**When the switch happens**, delete the narrow asset in the same change — otherwise the
directory keeps two overlapping fonts with no rule for which one is authoritative.

### Named transition item — owner, trigger, and expiry

| Field | Value |
| --- | --- |
| Item | the narrow asset's coexistence with the wide asset (this section) |
| Owner | **`analytics-engineer`** (the loading path is `**/*.java` in this module, not this subtree's) |
| Trigger | **the library switch lands** — the moment PDFBox starts loading the wide asset |
| Required in ONE change | ① switch the loading path · ② **delete `NotoSansSC-Regular-subset.ttf`** · ③ update the display text in `ReportDocument.java` (currently line 281) · ④ update this §0a |

> **该状态到期未清 ⇒ 视为未完成。**
> Trigger reached but this section still describes two fonts ⇒ the work is **incomplete**, not
> "accepted as-is". This is a temporary measure, and temporary measures that outlive their
> trigger are how a codebase accumulates fossils — the rule exists so "we'll clean it up later"
> cannot quietly become permanent.

**Authoritative pin:** `d8fc8e27…` (the **wide** asset). The narrow asset's `eca8a134…` is a
**transitional** value only — it must not be copied into rulings or other documents, or it
becomes another stale pin (the failure mode this repository has already hit twice).

## 0. Design change: wide coverage, subset at export time

The asset is **no longer keyed to the generator vocabulary**. A vocabulary-keyed subset had
to be rebuilt whenever an implementer added a character, and that rebuild cycle fired twice.
The shipped font now covers a wide, fixed range and the **PDF library subsets it per
document at export time** (`PDType0Font.load(doc, stream, embedSubset=true)` generates
`/W`, `/CIDToGIDMap`, `/ToUnicode`, `FontDescriptor` + `FontFile2` itself).

> **The retired approach earned its keep before retiring.** It is the project's first
> build-time gate that genuinely FAILED on a real change: an implementer added
> `冷 剖 又 叫 哑 堆 左 梯 槽 浅 矢 答 纵 考 铃 青 颜` and the assertion failed at build time,
> preventing tofu on a player's machine. That failure — not the mechanism — was the point.

**`NotoSansSC-Regular-subset.ttf` was decommissioned** (its 1,599-glyph coverage is a strict
subset of this asset). It is recorded here for provenance only; it is no longer loaded.

## 1. What the font is

`NotoSansSC-Report-wide.ttf` is a **wide-range static instance of Noto Sans SC**, built for
embedding in mc-insight PDF reports.

| Property | Value |
| --- | --- |
| Size | 7,608,592 bytes (7.26 MB) |
| Glyphs | 22,362 |
| cmap entries | 22,021 |
| Outline format | TrueType `glyf` (embeddable as `CIDFontType2`) |
| Tables | 18, including required `head` `maxp` `cmap` `loca` `glyf` `hmtx` **and `post`** |
| unitsPerEm | 1000 |
| SHA-256 | `d8fc8e27c6e27d143e4dde151094477fbb1e74afc6cdd98c510b7c0958b5fa72` |

> **This is the single source of truth for the pin value.** Do NOT copy the hash into rulings,
> docs or build scripts — the previous pin expired the moment the asset was rebuilt.

> **The build is byte-reproducible** (verified: two independent runs → identical SHA-256).
> That required two fixes, and the first one alone does NOT work: the `head` compiler stamps
> `modified` with the current time whenever `ttFont.recalcTimestamp` is true, so assigning
> `head.modified` is silently discarded. The script clears that flag **and** pins
> `modified` to a constant that is ≥ the font's `created` (a `modified` earlier than `created`
> is an incoherent pair — fontTools warns and a strict validator may object).

> **`post` is required.** If CIDs are taken from glyph ids, the writer needs the `post` table
> to resolve glyph *names* (`TrueType.getGlyphID`, and `PDType0Font`'s embedded-subset path).
> Without it that path throws. This asset carries it.

### Coverage

| Range | Block |
| --- | --- |
| `U+0020–007E` | ASCII |
| `U+00A0–00FF` | Latin-1 supplement |
| `U+0100–017F` | Latin Extended-A |
| `U+0370–03FF` | Greek (report strings use `Δ` / `Σ`) |
| `U+0400–04FF` | Cyrillic |
| `U+2000–206F` | general punctuation |
| `U+2190–21FF` | arrows (report strings use `⇒`) |
| `U+2200–22FF` | mathematical operators |
| `U+2460–24FF` | enclosed alphanumerics |
| `U+2500–257F` | box drawing |
| `U+25A0–25FF` | geometric shapes |
| `U+2600–26FF` | misc symbols |
| `U+2700–27BF` | dingbats |
| `U+3000–303F` | CJK punctuation |
| `U+4E00–9FFF` | CJK unified ideographs |
| `U+FF00–FF60` | fullwidth forms |

The Greek, arrow and box-drawing blocks were added after measurement, not by guess: the
generator's own feature strings contain `Δ` and `Σ` (e.g. `M3C_SESSION_CADENCE = quantiles(p50
of Δ session start, hour)`), and its comments use `⇒` and `──`, so a narrower asset would have
failed on emittable text.

## 2. Verification (the assertion changed proposition, it was not deleted)

Coverage is still asserted, but the proposition changed. Under wide coverage,
"generator vocabulary ⊆ font coverage" is almost necessarily true, so the guard now asserts
the **boundary**:

> text that falls **outside** the shipped font's coverage must fail **explicitly**
> (`reasonCode=FONT_SUBSET_MISSING`), never render as tofu.

This is the build/test-side counterpart of the runtime `gid == 0` contract. Keeping it means
swapping in a narrower font fails immediately.

```powershell
python docs\verification\tools\_font_coverage_assert.py --vocab      # generator vocabulary
python docs\verification\tools\_font_coverage_assert.py --text-file <report.md>
python docs\verification\tools\_font_coverage_assert.py --bmp-gap    # informational
```

**Three exclusions, each with its reason — all three are load-bearing**, because a gate that
fails on a correct artifact gets switched off:

1. **Space / format / control** (`Zs`, `Cf`, `Zl`, `Zp`, `Cc`) have no glyph in any base cmap
   by design (variation selectors live in cmap format 14; bidi controls are zero-width;
   spaces are metrics-only).
2. **Comment-only characters** cannot reach a report. This matters here: the source documents
   the *old* corruption inside comments, so it contains rare ideographs (`㝜䑶頼行鈅`) that are
   descriptions of a bug — demanding the font render them would be demanding it render a bug
   report. Currently 7 such code points: `U+2714 U+375C U+37E3 U+3EB2 U+4476 U+4938 U+FE0F`.
3. **`U+2212` MINUS SIGN** is normalised to `-` (the source font carries no `U+2212`).

**Negative control (verified):** pointing the assertion at the decommissioned narrow asset
fails with `missing: 2 U+2286 U+8BCD` → `VERDICT: FAIL — reasonCode=FONT_SUBSET_MISSING`,
exit 1. The assertion can fail, so a PASS means something.

## 3. Legacy: the vocabulary-keyed asset (decommissioned)
> report-keyed subset was missing `效 广 离 散 程 好 偏 向 沉 冗 杂` — the words in the metric
> names themselves (有效进度 / 广度 / 离散程度 / 内容喜好偏向 / 沉冗复杂).

The vocabulary is **recomputed from source on every build** — never hard-coded, because it
changes as implementers edit the generators. Observed drift is real: it measured 1,149
distinct characters at one point earlier in the session and 1,062 later.

## 4. Licensing (SIL OFL 1.1) and why the font is renamed

The upstream declares SIL OFL 1.1 in its `nameID 13` / `nameID 14` records:
*"This Font Software is licensed under the SIL Open Font License, Version 1.1."*

**The subset is a Modified Version of the Font Software, so OFL §3 applies.** Verbatim from
`OFL-1.1-LICENSE.txt`:

> 3) No Modified Version of the Font Software may use the Reserved Font Name(s) unless
> explicit written permission is granted by the corresponding Copyright Holder. This
> restriction only applies to the primary font name as presented to the users.

The upstream carries `nameID 0` = `© 2014-2021 Adobe (http://www.adobe.com/), with Reserved
Font Name 'Source'.` — i.e. its RFN is `Source`. Accordingly:

| nameID | Content | Treatment |
| --- | --- | --- |
| 0 | copyright + RFN statement | **kept verbatim** (OFL requires the copyright notice to travel with the font) |
| 13 | OFL 1.1 notice | **kept verbatim** |
| 14 | `http://scripts.sil.org/OFL` | **kept verbatim** |
| 1 / 4 | `Noto Sans SC` | **renamed** to `Octant Report Sans` |
| 6 | `NotoSansSC-Thin` | **renamed** to `Octant-Report-Sans-Regular` |
| 3 | `2.004;ADBO;NotoSansSC-Thin;ADOBE` | rewritten; no RFN, no `-Thin` |
| 7 | `Source is a trademark of Adobe…` | rewritten; removes the RFN from the trademark slot |
| 5 | `Version 2.04;…;non-release` | rewritten to state the actual instance |

**Verified:** the string `Source` occurs in **no** name record other than the retained
0 / 13 / 14. **Two further defects in the upstream metadata were corrected at the same
time:** `nameID 3`/`6` claimed `NotoSansSC-Thin` while the instance is `wght=400` (the font
misidentified itself), and the version string described a `non-release` variable font
rather than the static instance actually shipped.

OFL §2 also requires that each copy of the font carries the copyright notice and the
licence, so both licence files here are part of the font's distribution, not decoration.

### What the licence obligation actually rests on

> **The basis is the licence text SHIPPED ALONGSIDE, not what the font file happens to
> embed.** Do not treat "the embedded subset has no `name` table" as licence-satisfied, and
> equally do not treat "the font carries an OFL notice inside it" as sufficient on its own.

OFL §2 explicitly allows the copyright notice and licence to be provided **"either as
stand-alone text files, human-readable headers or in the appropriate machine-readable
metadata fields within text or binary files as long as those fields can be easily viewed by
the user."** A stand-alone text file is therefore a *complete* discharge of that duty — which
is what makes it acceptable for an export-time subsetter to strip the `name` table from the
embedded copy. The two files in this directory are the discharge.

OFL §3's reserved-font-name restriction, separately, is limited to **"the primary font name as
presented to the users"**. After the rename in the table above, every name a user can see
(`Octant Report Sans`, `Octant-Report-Sans-Regular`) is free of the reserved name, so a
subset that drops the whole `name` table does not create an RFN problem either.

**What this means in practice:** keep `OFL-1.1-LICENSE.txt` and the canonical template with
the shipped artifact, and keep `nameID 0` intact in any build you produce. Those two things
are the obligation; everything else is implementation detail that a different PDF library may
legitimately change.

## 5. Provenance of the licence files

| File | Source URL | Retrieved |
| --- | --- | --- |
| `OFL-1.1-LICENSE.txt` | `https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/LICENSE` | 2026-09-27 |
| `OFL-1.1-canonical-template.txt` | `https://openfontlicense.org/documents/OFL.txt` | 2026-09-27 |

`OFL-1.1-LICENSE.txt` is the licence shipped **by the very project the source font came
from**, and it quotes the same `http://scripts.sil.org/OFL` URL that the font's own
`nameID 14` records. `OFL-1.1-canonical-template.txt` is the upstream canonical text from
the SIL/OpenFontLicense site (it uses `<dates>` / `<Copyright Holder>` placeholders and
quotes `https://openfontlicense.org`). Both are the **complete** OFL 1.1 of 26 February
2007 — verified clause by clause (`PERMISSION & CONDITIONS`, the reserved-font-name clause,
`TERMINATION`, `DISCLAIMER`), wrap-insensitively, so that line wrapping is not mistaken for
missing text.

SHA-256: `OFL-1.1-LICENSE.txt` = `6a73f9541c2de741…`, `OFL-1.1-canonical-template.txt` = `1d361a8f8e8ce6e6…`

## 6. Rebuilding

```powershell
python docs\verification\tools\_build_report_font.py   # instances, subsets to the wide ranges, rewrites names, re-verifies
```

The build script: instances the variable source at `wght=400` (the variable default is
`wght=100` = Thin, which would ship an unreadably light face), subsets to the wide ranges in §1, rewrites the name records as in §4, then reports glyph
count, cmap coverage, the RFN check, and sfnt structural sanity.

**This script is provenance evidence and is deliberately NOT part of the Gradle build**: it
depends on this machine source font and fontTools, neither of which is a build prerequisite.
Its presence is what makes the landed asset reproducible (§18.3).

## 7. Required companion checks (contract with the consumer)

1. **Build-time**: every character the generators can emit must have a glyph. A missing one
   must fail the build, not surface as tofu on a player's machine.
2. **Runtime**: a glyph lookup returning `gid == 0` must fail **explicitly, with a
   `reasonCode`** — never silently render a blank box.
3. **Default-ignorable code points must be excluded from any coverage assertion**:
   `U+FE00–FE0F`, `U+200B–200D`, `U+2060`, `U+FEFF`, and the tag/variation-selector blocks.
   They have no glyph in any base cmap **by design** (variation selectors live in `cmap`
   format 14). An assertion that fails on a correct font will eventually be switched off.
4. `U+2212` MINUS SIGN is normalised to `-` (the source font has no `U+2212` glyph).
5. **If CIDs are renumbered to dense indices, `/W` and `/CIDToGIDMap` must both be keyed by
   the NEW CID, not by glyph id or code point.** Two concrete traps:
   - `/W` is indexed by **CID**; giving it in code-point or glyph order silently mis-sizes
     every glyph whose index differs from its CID;
   - `/CIDToGIDMap /Identity` is only valid when `CID == glyph id`. After dense renumbering
     they differ, so the **explicit mapping stream is mandatory** — and its length must be
     `2 × (maxCID + 1)` bytes, little-endian `uint16` per entry (`maxCID` ≤ 65,534).
   Using the explicit stream is also the more robust choice here: it survives glyph-id gaps
   in the subset and keeps CIDs dense, which is what makes `/W` compact.
   Verified for this asset: `numGlyphs = 1,599` (max glyph id 1,598), so every glyph id fits
   the 16-bit CID space — the mapping approach is not blocked by the format's limit.
6. A font-correctness check that rasterises the **font file** proves the font is sound; it
   does **not** prove the PDF maps CIDs to the right code points. Those need the text-layer
   reconciliation (`_pdf_oracle_cmp.py`) as well. Both are required.
