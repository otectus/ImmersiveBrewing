# Testing

This file separates the **current 1.1.0 development commands and pending gates** from historical 1.0.0/1.0.2 results.
The implementation-to-recommendation map and final validation results are in
[`docs/reports/1.1.0-implementation.md`](reports/1.1.0-implementation.md). A historical pass does not establish that the
current tree passed after later fixes. Logs from `gradlew-quiet.sh` are written to
`/tmp/gradle-ImmersiveBrewing-<task>-<timestamp>.log`; durable current matrix output uses the paths under
[Artifacts](#artifacts).

## Commands

```sh
Q=/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh
P=/home/otectus/Projects/ImmersiveBrewing
$Q $P test                                   # JUnit: pure simulations and quality maths
$Q $P runGameTestServer                      # core GameTests; no optional local jars required
$Q $P clean build runGameTestServer          # clean core CI path
python3 tools/run_compat_matrix.py            # serial provisioned matrix; verifies recorded fixture hashes
python3 tools/run_compat_matrix.py core apothecary_config  # selected cases
tools/run_smoke_matrix.sh default keyboard accessible colorblind gui1 gui2 gui3 gui4
tools/run_smoke_matrix.sh visual             # five locales at 320x240; status states, countdowns, lessons and localized carrier labels
SMOKE_GRADLE_ARGS=-PwithPotionMods tools/run_smoke_matrix.sh default        # the client with the four potion mods
$Q $P build                                  # JUnit + mod jar (build/libs)
python3 -I /home/otectus/Projects/.mcmod-tools/check_mod.py $P
python3 tools/generate_data.py --check
python3 tools/generate_textures.py --check && python3 tools/generate_textures.py --project-icon --check
python3 tools/generate_sounds.py --check
python3 tools/check_release.py                # source-locale key/placeholder parity; rejects CJK in non-Chinese locales
python3 tools/check_release.py --fixtures     # separately provisioned optional-jar checksums
python3 tools/check_release.py --jar build/libs/immersive-brewing-1.1.0.jar
```

`tools/run_compat_matrix.py` reads already provisioned jars from `libs/`, or copies them from
`IB_COMPAT_FIXTURES_DIR`; it never downloads or redistributes them. It runs cases serially and restores the changed
Apothecary configuration byte-for-byte even after failure.

## Suites

### JUnit (29 tests declared in the current source, `src/test`)

| Class | Covers |
| --- | --- |
| `QualityMathTest` | Grade bands, component scores, the quality formula, the duration multiplier (exact at 50), drinking time, purification |
| `KettleSimulationTest` | Determinism, seal and resume, competent play reaching Masterwork on every signature, every generated schedule playable, idle and poor play scoring lower, bounded state under arbitrary input, tier durations, addition windows and disturbances following the contract, assistance limits, automatic additions scoring nothing |
| `PreparationSimulationTest` | Competent, poor and idle preparation, determinism with seal and resume, capped motion, parameter encoding |
| `DistillationSimulationTest` | Competent routing scores high and finishes in time; sloppy and idle score lower |
| `GuidedPracticeTest` | Staged heat/circulation/addition/recovery practice parameters, progression and non-productive result contract |

### GameTests (144 tests declared in the current source, `src/gameTest`)

| Class | Tests | Covers (spec) |
| --- | --- | --- |
| `PotionPathGameTests` | 13 | Every quality application path, once: drinking (untagged potions unchanged), use time, instant effects, bundles, idempotence, custom effects, splash attenuation and instant damage, lingering clouds, tipped-arrow crafting, arrow impact, dispensers; each of the five mixins has a test here or in replacement mode (12) |
| `KettleGameTests` | 4 | Three-serving Swiftness with exact reagent and fuel accounting, prepared kits, seal and resume at the same step, count-in cancel (milestone 1) |
| `LifecycleGameTests` | 9 | Save mid-run loads sealed; save during the count-in loads released; break while running drops one Recovery Batch, installs once and finishes; break during the count-in packages the reservation; a second completion issues nothing; disconnect, death, range and dimension change seal with progress kept (20) |
| `ContentionGameTests` | 3 | Two players at one Kettle (refused start, locked slots, foreign input ignored, resume as assistant, brewer credited); one session per player; packets need the station's open menu (20.1, 21.1) |
| `AutomationGameTests` | 7 | Face rules for insertion and extraction, nothing reachable during a job, comparator modes, water filling (simulate equals execute, whole bottles, one bucket fills four), real hoppers, Mortar and Alembic faces (22) |
| `ReplayGameTests` | 4 | Duplicate, reordered, oversized, invalid, far-future and stale packets; the wire decoder; a viewer cannot end or steer another session; a flood seals with progress kept; a late addition is carried into the next step (21, ADR-2) |
| `VanillaCoverageGameTests` | 8 | Every vanilla plan replayed through `BrewingRecipeRegistry` at 1-6 servings with costs, fuel and remainders; every registry transformation covered; blaze powder as reagent or fuel; dragon's breath bottle once; Turtle Master metadata-only; Weakness from water; quality inheritance through modifiers and corruptions (13, 15.3, 12.3) |
| `ServingEconomyGameTests` | 9 | One to six servings, kits replacing reagents exactly, kit and raw extraction averaging, four-kit preparation (5.2) |
| `ReplacementModeGameTests` | 8 | COEXIST untouched; stand metadata pass-through; REPLACE_COVERED stops covered stands before fuel or time (mixin) with the `PotionBrewEvent.Pre` backstop, modifiers and mixed cycles pass; DISABLED refuses new jobs but resumes sealed ones (14) |
| `AlembicGameTests` | 11 | 3→2 and 6→4 purification, baseline start, cap and once-per-line refusals, interrupted and broken purifications without duplicates, reclamation conservation, cancel refund, Recovery Batch handling, safe grouping and exact mismatch reasons (11) |
| `CarrierGameTests` | 7 | Exact transfer, overflow kept, collection, no nesting, narrow handler, never a recipe or station item, Alembic exchange, safe selected-slot equip, empty-selection behavior and newer preference data (10) |
| `LabelGameTests` | 2 | Code-point limits, control and formatting characters rejected whole, literal JSON, identity and power unchanged, batch labelling (10) |
| `NotebookGameTests` | 4 | Explicit bounded favourites, compact 32-entry journal/clear history, guide-v2 unknown-data preservation and notebook granted once (10, 25) |
| `DataGameTests` | 4 | The `ib_test` example pack: a datapack plan and brew profile brew at the Kettle, a datapack reagent profile prepares, equal profiles tie-break with a diagnostic, invalid data is reported and never offered, an excluded item stays out of every path (16) |
| `IntegrationGameTests` | 4 | Exactly one iron-paddle recipe (smithed or crafted), Smithing never treats carriers or recovery batches as metal, Smithing quality reduces paddle wear, the Tanning strap is presentation only, paddles take Unbreaking and Mending only (17) |
| `ContentGameTests` | 2 | Advancement tree (root plus one per milestone, no rewards), cleric trades off by default (23.4, 25) |
| `PerformanceGameTests` | 1 | 32 Kettles brewing at once with live input: session time per tick, messages per controller, no entities (26.6) |
| `PauseGameTests` | 3 | The help overlay pauses a session, count-in included, and the resume restarts the clock from the client's timing; only the controller pauses, repeated or impossible toggles are violations, a flood seals |
| `WorkshopRegressionGameTests` | 7 | Regressions from the workshop audit: clock offsets, output recovery, batch identity and tool locks |
| `CompatGameTests` | 16 | The four potion mods (18, 26.5), each only when loaded: their `compat/` data loads exactly with them; every plan validates (Apothecary's Burning is reported once More Potions' Fire claims the magma block); a complete registry probe finds no uncovered transformation; one real brew per mod; every listed effect scales on drinking at quality 0 and 100, the rest keep their duration; Mortar profiles and Alembic recipes; RPG Potions' loose matches refused; stackable potions; the bounded catalog completed by the tray |
| `ApothecaryRackGameTests` | 3 | Six independent mixed-metadata stacks through save/load, capacity/filter carrier transfers, non-mutating automation simulation, access security and exactly-once drops |
| `Migration110GameTests` | 5 | Runtime-extracted 1.0.2 potion, kit, guide, Notebook and schema-1 ledger data; exact first-resave accounting; clearly labeled baseline-derived non-empty carrier fixture; provenance manifest |
| `Workflow110GameTests` | 10 | Atomic staging and failures, grouped-kit ownership/remainders, quick finishing and recovery, schema-1 finishing proof, and long-route catalog replay without tray seeding |

The GameTest world also loads the example pack `src/gameTest/resources/data/ib_test/`, which adds a plan, profiles, an
invalid recipe, an invalid definition and an exclusion; the coverage report written during GameTests
(`docs/reports/gametest-coverage-report.txt`) reflects that pack.

### Client smoke test (`src/smokeTest`, `runClient -PsmokeTest`)

A test-only companion mod drives a development client from the title screen into a fresh flat world and plays the
workshop through the real screens and packets with the reference bots: a preparation at the Mortar, a three-serving
Swiftness brew sealed halfway and resumed, a drink whose applied duration must equal the tooltip, a purification, the
Notebook, the operator report, then a gallery of station states and a refused plan. It checks JEI received every
category, closes each tutorial overlay with the real key, records results in `run/smoketest/smoketest-result.json` and
saves screenshots to `run/screenshots/`.

Variants (`tools/run_smoke_matrix.sh`): `keyboard` plays all three games through the key bindings only (no injected
frames); `accessible` uses the high-contrast palette, reduced motion, numbers, large markers and master volume 0;
`colorblind` the colour-blind palette; `gui1`-`gui4` set the GUI scale in a 1280×960 window, giving GUI sizes 1280×960,
640×480, 427×320 and exactly 320×240, the smallest Minecraft allows.

## Current 1.1.0 validation status

Validated on 2026-10-28. All automated gates pass; human release gates remain explicitly pending. Exact commands,
fixture versions, log paths, migration provenance and interpretation limits are in the
[1.1.0 implementation report](reports/1.1.0-implementation.md).

| Current-tree gate | Result | Durable evidence |
| --- | --- | --- |
| Build + 29 unit tests | **PASS; 0 failures/errors/skips** | `build/test-results/test/`, `build/reports/tests/test/`, quiet-wrapper log |
| Core 144 GameTests | **PASS, including fresh build without optional jars** | `build/compat-matrix/core-server.log`, `docs/reports/gametest-coverage-report.txt` |
| Isolated potion mods, collision, combined optional mods | **PASS; 144 tests per case** | `build/compat-matrix/results.json` and per-case logs/reports |
| Changed Apothecary configuration | **PASS; stack size 16 and changed Iron Skin reagent** | `build/compat-matrix/apothecary_config-server.log`; configuration restored afterward |
| Migration fixtures and material accounting | **PASS** | `src/gameTest/resources/migration/1_0_2/provenance.json`, core GameTest log |
| Generated resources, locales and packaged-jar audit | **PASS; data 507, textures 20, sounds 17, icons 3, locale keys 785** | `build/ib110/validation.json`, `build/libs/immersive-brewing-1.1.0.jar` |
| Original core GUI 4 + combined keyboard/accessibility/colorblind/five-language clients | **PASS; 177/183/179/179/1,076 check entries** | `run/smoketest-matrix/*.json`; original combined visual result: `build/ib110/visual-combined-before-ui-fix.json` |
| Status/locale fix: core five-language client at 320x240 | **PASS; 1,314 boolean assertions, 287 genuine captures** | `build/ui-fix/validation.json`, `run/smoketest-matrix/visual.json`; all heat states/palettes, start/resume countdowns, lessons and localized carrier fields |

Human formative trials, two-client latency fairness, native-language review and video/rendering-environment review
remain **PENDING**. The report supplies their checklist and provisional targets. The sections below are historical.

## Historical results: 1.0.0 (2026-10-10)

Environment: Linux 7.2.7 (CachyOS), Intel Core i7-13700HX (24 threads), 31 GB RAM, NVIDIA GeForce RTX 4050 Laptop GPU,
OpenJDK 17.0.19, Gradle 8.8, Forge 47.4.23, JEI 15.20.0.135, Jade 11.13 (CurseForge file 6271651), Immersive Smithing,
Tanning, Enchanting and Jewelry 1.1.0 (jars in `libs/`). Hyprland 0.56 desktop.

### JUnit

`build` (16:04): 24 tests, 0 failures, 0 errors, 0 skipped (`build/test-results/test/`).

### GameTests and the compatibility matrix

`runGameTestServer` with each sister-mod set (16:00-16:04). The loaded mods were confirmed from each log, not assumed
from the flags.

| Configuration | Flags | Loaded sisters | Result |
| --- | --- | --- | --- |
| Brewing + Smithing + Tanning (default dev run) | none | Smithing, Tanning | 94/94 passed |
| Brewing alone | `-PnoSmithing -PnoTanning` | none | 94/94 passed |
| All four sisters | `-PwithJewelry -PwithEnchanting` | Smithing, Tanning, Enchanting, Jewelry | 94/94 passed |
| Smithing only | `-PnoTanning` | Smithing | 94/94 passed |
| Tanning only | `-PnoSmithing` | Tanning | 94/94 passed |
| Jewelry only | `-PnoSmithing -PnoTanning -PwithJewelry` | Jewelry | 94/94 passed |
| Enchanting only | `-PnoSmithing -PnoTanning -PwithEnchanting` | Enchanting | 94/94 passed |

Vanilla coverage (`VanillaCoverageGameTests`): 939 plans from the vanilla potions (963 before the test pack excludes
phantom membrane) replayed through `BrewingRecipeRegistry` with 5634 serving checks at 1-6 servings, 43 output
families, no mismatch; discovery made 2193 registry queries, found 251 transformations and none without a plan.

### Performance (spec 26.6)

`PerformanceGameTests.thirtyTwoActiveKettles`, in its own batch on the headless GameTest server (no rendering):
32 Kettles brewing at once, each controller sending one input packet per tick, measured over 200 server ticks after
the count-in.

| Run | Session work per tick, mean | Worst tick | Messages per controller in 200 ticks |
| --- | --- | --- | --- |
| Seven matrix runs above | 36-44 µs | 185-515 µs | at most 20 (one snapshot per ten steps) |

No entities were created. Idle stations do no work beyond view sync (code review: `StationBlockEntity.serverTick`),
station sync is a compact view at most twice a second, discovery is bounded by `maxDiscoveryPairs` and cached per
dataset, and client particles are capped at one per station per tick within 24 blocks, scaled by `particleDensity` and
vanilla's particle setting. Client frame rate was not measured; there is no meaningful number to claim from these runs.

### Client smoke test

`tools/run_smoke_matrix.sh default keyboard accessible colorblind gui1 gui2 gui3 gui4` (15:41-16:00), window floated
and sized to 1280×960 by PID. Every run passed every check (63 checks, 67 in the keyboard trial) and captured all 23
screenshots.

| Run | GUI size | Notes |
| --- | --- | --- |
| default | 640×480 | Preparation E 100; brew E 100, C 99, Q 99 (Masterwork), sealed halfway and resumed; drink applied 4306 ticks = tooltip 03:35; purification D 99, quality 70, 3→2; JEI categories brewing 168, preparation 12, distillation 1, reclamation 1; gallery states empty, running, sealed, held and reclaiming all reached; operator report written |
| keyboard | 640×480 | Same scores with every game played through the key bindings only (401 key presses by the end of the brew) |
| accessible | 640×480 | High contrast, reduced motion, numbers beside each cue, large markers, master volume 0; all games completed |
| colorblind | 640×480 | Colour-blind palette; all games completed |
| gui1 / gui2 / gui3 / gui4 | 1280×960 / 640×480 / 427×320 / 320×240 | Layout review at every scale, down to the smallest GUI Minecraft allows |

Screenshot review (`run/screenshots/`, 184 images) found and fixed during this work: overlapping Kettle labels, a
clipped stir hint, buttons under vanilla toasts, invisible plan-list text, unclipped search hints, numbers running under
the tray at 320×240, a misleading empty-tank overlay in Jade, an empty Kettle shown while its batch was sealed, and a
"0× Air" title on refused plans.

### Repository checks

`check_mod.py`: 0 errors, 0 warnings, 1 note (the strapped carrier's override model has no item of its own, by design).
`generate_data.py --check` 239/239, `generate_textures.py --check` 20/20, `generate_sounds.py --check` 17/17 up to date.

### Release jar

`build/libs/immersive-brewing-1.0.0.jar` (952,606 bytes, 654 entries): `MixinConfigs: immersive_brewing.mixins.json`
in the manifest; the mixin config lists the five mixins and its refmap maps all of them with a `searge` block;
`mods.toml` fully expanded with every optional dependency `mandatory=false`; no smoke-test agent, test bots, GameTest
structure or `ib_test` data inside.

## Historical results: 1.0.2 optional potion mods (2026-10-23)

Environment as above, now on Linux 7.2.11 (CachyOS) with OpenJDK 17.0.20.1, plus Apothecary 1.5.2, Potions, Potions And
More Potions 1.0.4, PotionsMaster 0.6.0 and RPG Potions 1.2.0 (its `mods.toml` reports 1.1.3), jars in `libs/`.

### GameTests and the compatibility matrix

`runGameTestServer` per configuration (11:42-11:49), driven by one script; the loaded mods were read from the
"Loaded mods" section of the coverage report each run wrote, not assumed from the flags. JEI is in every run.

| Configuration | Flags | Loaded optional mods | Result |
| --- | --- | --- | --- |
| Default | none | Smithing, Tanning | 120/120 passed |
| Brewing alone | `-PnoSmithing -PnoTanning` | none | 120/120 passed |
| Apothecary | `-PnoSmithing -PnoTanning -PwithApothecary` | Apothecary | 120/120 passed |
| More Potions | `-PnoSmithing -PnoTanning -PwithMorePotions` | More Potions | 120/120 passed |
| PotionsMaster | `-PnoSmithing -PnoTanning -PwithPotionsMaster` | PotionsMaster | 120/120 passed |
| RPG Potions | `-PnoSmithing -PnoTanning -PwithRpgPotions` | RPG Potions | 120/120 passed |
| Apothecary + More Potions | `-PnoSmithing -PnoTanning -PwithApothecary -PwithMorePotions` | both | 120/120 passed |
| Everything | `-PwithJewelry -PwithEnchanting -PwithPotionMods` | the four sisters and the four potion mods | 120/120 passed |

The default and brewing-alone runs reproduce the 1.0.0 vanilla numbers exactly (939 plans, 5634 serving checks, 43
output families). Per potion-mod run (`[compat]` and `[coverage]` log lines):

| Run | Plans validated | Every plan replayed through the registry | Complete probe (queries, time, transformations, loose) | Effects that scale on drinking / keep their duration |
| --- | --- | --- | --- | --- |
| Apothecary | 85 | 1944 plans, 11664 serving checks | 15720, 123 ms, 512, 0 | 26 / Mana Regeneration, Mana Exhaustion |
| More Potions | 69 | 1368, 8208 | 5148, 52 ms, 366, 0 | 6 / Leap; Fire and Lightning are instant |
| PotionsMaster | 24 | 1437, 8622 | 8241, 94 ms, 371, 0 (48 splash or lingering Mundane queries stay contextual) | 24 / none |
| RPG Potions | 8 | 1011, 6066 | 3825, 22 ms, 667, 392 | 5 / Smoke; Decoy is instant |
| Apothecary + More Potions | 84 + 69 (Burning reported: Fire answers first) | 2355, 14130 | 22176, 231 ms, 626, 0 | 32 / as above |
| Everything | 84 + 69 + 24 + 8 | 2586, 15516 | 44640, 821 ms, 2242, 1472 | 61 / the 7 above |

"Effects that scale" counts each distinct effect in a mod's own potions, so Apothecary's include the vanilla Health
Boost, Resistance and Wither (vanilla policy); each was drunk at quality 0 and 100 and matched `round(base x (200 +
Q) / 250)` with its amplifier kept. The complete probe uses `DiscoveryIndex.withBudget(..., 1000000)`; the server's
own discovery keeps the 4096-query default and reports "(budget reached)" with these mods
(`docs/reports/gametest-coverage-report-potion-mods.txt`).

Plans from Water: 158 with vanilla only; the catalog reaches its 512 bound with PotionsMaster (Mundane becomes a hub)
and with everything, where the tray search adds 1 and 5 loaded paths (the five-step lingering Long Swiftness among
them). Building the Kettle's plan list on the server, per press of the Plans button, five warm calls per run
(`loadedPlansSurviveTheCatalogBound`): 4-23 ms in the two vanilla runs, 9-20 ms in the potion-mod runs. 32 sessions: mean
74-115 us per tick across the runs, worst 635-1615 us except one 5852 us tick in the Apothecary run.

### Client smoke test

`tools/run_smoke_matrix.sh default`, then `SMOKE_GRADLE_ARGS=-PwithPotionMods tools/run_smoke_matrix.sh default`
(11:59-12:05, after the harness change below): both passed every check (150 recorded, 104 pass/fail checks) with the
same results, preparation E 100, brew E 100, C 99, Q 99, the drink applying 4306 ticks as the tooltip's 03:35,
purification D 99 to quality 70. With the potion mods the client also loaded their client code (renderers, overlays) next to JEI and Jade,
received 80 reagent profiles instead of 17, and JEI listed brewing 353, preparation 55, distillation 5 and reclamation 5
(168, 12, 1, 1 without them). The first run with the mods failed one check, `scroll_reaches_end_plans_long`: it selects
the listed plan with the most steps and requires its detail panel to overflow, and with the mods the 256-entry list from
Water holds only one- and two-step plans. The overflow requirement now applies without the potion mods (`plans_long_steps`
records 7 there, 2 with them); the scroll itself is still checked in both.

### Repository checks and release jar

`build` (11:50): JUnit 24/24, `build/libs/immersive-brewing-1.0.2.jar` (1,191,891 bytes, 847 entries): no class or jar of
the four mods inside and none of them in `mods.toml`; 194 compat recipes and 67 compat definitions; `MixinConfigs` and
the refmap's five mixins with a `searge` block as before. `check_mod.py`: 0 errors, 0 warnings, the 1 note above.
`generate_data.py --check` 500/500, `generate_textures.py --check` 20/20, `generate_sounds.py --check` 17/17.

## Artifacts

| Path | Content |
| --- | --- |
| `docs/reports/1.1.0-implementation.md` | Implementation traceability, decisions, automated results, deferred scope and pending human gates |
| `docs/reports/coverage-report.txt` | `/immersivebrewing report` from the clean smoke-test world (no test pack): 168 supported recipes, 251 registry transformations, 0 uncovered |
| `docs/reports/gametest-coverage-report.txt` | The same report from the GameTest world, which includes the `ib_test` pack (its invalid file, unsupported recipe and exclusion appear there on purpose) |
| `docs/reports/gametest-coverage-report-potion-mods.txt` | The GameTest world's report with every optional mod (the "Everything" run): each potion mod's plans by status, the reported Burning plan, loose matches |
| `build/compat-matrix/results.json` | Current serial compatibility case outcomes; per-case Gradle/server logs and copied coverage reports are beside it (not committed) |
| `src/gameTest/resources/migration/1_0_2/provenance.json` | Origin/checksums for data extracted from retained real 1.0.2 worlds; identifies the baseline-derived non-empty carrier separately |
| `run/smoketest-matrix/<run>.json` | Every client check per run (not committed: `run/` is ignored) |
| `run/screenshots/smoketest_*.png` | Screenshots per run (not committed) |

## Human and real-environment gates not closed by automation

These are explicitly **PENDING**, not passed:

- A genuine 8–12-player formative study covering the recommendations' eight onboarding/repeat-workflow tasks and
  provisional targets: 80% first-brew completion without external help, 80% understanding of preparation/purification,
  at least 30% fewer repeat-setup inventory interactions than the measured 1.0.2 baseline, and no color/sound/chord-only
  critical action. Record time, errors, help, missed cues, movements, perceived effort and desire to brew again.
- A dedicated server with two real clients at 0/100/200 ms added round-trip latency, jitter and limited packet loss,
  under healthy and degraded TPS. Test correctness and perceived fairness separately, plus disconnect/rejoin,
  controller handoff, simultaneous viewers and interruption recovery.
- Native-language, in-context review as appropriate for all five maintained locales, including placeholders, narration,
  long/non-Latin labels and the 320×240 GUI. Key parity is not native-language validation.
- Moving-video review of apparatus/reaction animation, reduced motion and fast camera movement, plus maintained
  resource-pack/rendering combinations before any shader-compatibility claim.
