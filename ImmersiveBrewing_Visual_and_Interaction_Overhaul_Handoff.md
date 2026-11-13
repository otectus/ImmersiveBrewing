# Immersive Brewing — Visual and Interaction Overhaul

Implementation handoff for a coding agent · 2 November 2026

## 1. Assignment and reviewed baseline

Build a cohesive overhaul of Immersive Brewing’s presentation and interaction feedback. Make the workshop feel like a place where materials change under the player’s hands: ingredients fracture and fold, a kettle develops a readable reaction, and an alembic visibly separates and routes condensate. Every effect must explain a real state, acknowledge an action, or provide restrained atmosphere.

Preserve the existing crafting economy, recipe authority, quality rules, controls, accessibility assistance, recovery guarantees, exact-stack transport, and optional integrations. The core deliverable is a presentation overhaul, not a new potion system or a rebalance.

**Reviewed repository:** [otectus/ImmersiveBrewing](https://github.com/otectus/ImmersiveBrewing), `main`, commit [`12f9048009169069da6498160b3b29a8c5d14286`](https://github.com/otectus/ImmersiveBrewing/commit/12f9048009169069da6498160b3b29a8c5d14286), dated 29 October 2026. The branch identifies itself as **1.1.0 in development**, targeting Minecraft **1.20.1**, Forge **47.4.23**, Java **17**. Recheck the branch before implementation and reconcile intervening changes. Do not assume 1.1.0 is a released build. [S01]

**Evidence boundary:** this review inspected current source paths, models, generators, profile data, UI and rendering implementations, networking/lifecycle code, integration surfaces, and test documentation. All 19 workstation/item PNG textures were inspected and verified as 16×16. Repository screenshots were visually inspected, but their manifest identifies them as **1.0.2 captures from 24 October**, not screenshots of the reviewed 1.1.0 head. No current Minecraft client was launched, no listening test was performed, and no build, runtime compatibility, FPS, or usability result is claimed here. Audio findings concern synthesis and dispatch code. Existing test results below are repository-reported evidence, not newly reproduced results. [S02] [S03] [S38]

Use these labels throughout implementation reviews:

- **Confirmed:** directly observable in the reviewed source/assets. This may be an implementation limitation, not necessarily a player-visible bug in every situation.
- **Proposed:** a design decision for this overhaul.
- **Validate live:** a consequence or subjective judgment requiring a fresh client capture, recording, measurement, or participant trial.

Paths below use `J = src/main/java/com/otectus/immersivebrewing/`, `A = src/main/resources/assets/immersive_brewing/`, and `D = src/main/resources/data/immersive_brewing/`. Paths such as `client/render/KettleRenderer.java` are relative to `J`. Source links at the end are pinned to the reviewed commit.

## 2. Preserve the strengths already implemented

Do not implement the following as though they were missing:

| Existing system | Preserve and build upon |
| --- | --- |
| Three distinct interactive processes | Mortar preparation; kettle draft/circulation/addition control; alembic heat/coolant/receiver control. Keep their different rhythms. |
| Kettle reaction signatures | Foam, sediment, shimmer, streaks, suspended bubbles, whorls, glints, motes, and phase-specific simmer/settling behavior already exist. Improve their presentation and correspondence across GUI/world views. |
| Server-authoritative sessions | Pure simulations, validated input frames, client prediction/reconciliation, count-ins, pause/resume, and authoritative final results. |
| Recovery | Reserved/running/sealed/recovery states, station-owned job ledgers, Recovery Batch items, original brewer attribution, and safe handling of missing/newer data. |
| Accessibility | Rebinding, hold/toggle modes, reduced input, palettes, large markers, reduced motion, optional shake, intensity controls, written/numeric cues, narration, and server-approved assistance. |
| Guidance and batch workflow | Notebook onboarding/practice, server-backed catalog, recipe staging preview/commit, favorites, journal, pinned checklist, batch collection, racks, carriers, and labels. |
| Integration | JEI recipe categories/transfer, Jade station information, existing potion profiles/policies, and sister-mod compatibility. |
| Asset pipeline | Original generated pixel art/audio, deterministic generation checks, model generation, five maintained locales, and real-client smoke fixtures. |

Maintain the current quality model: raw extraction baseline 50, `Q = (E + 3C) / 4` through the existing rounding implementation, existing grade boundaries, and the alembic’s configured purification rules. Use `QualityMath`, `PotionContentsService`, and the server’s actual result; do not duplicate formulas in visual widgets. A high grade must not imply changed amplifiers, instant effects, or unsupported effect scaling. [S01] [S35]

## 3. Findings that justify the work

| ID | Evidence and classification | Required response |
| --- | --- | --- |
| F01 | **Confirmed:** `mortar_model()` bakes the pestle into the static block model. `MortarRenderer` renders ingredients and kits but does not animate the pestle. Its comment describes more motion than it implements. [S07] [S10] | Separate the movable pestle, retain an item-display variant, and drive its world pose from preparation state. |
| F02 | **Confirmed:** `SimHandles.Distillation.visual()` sets `progress` to total distilled **fraction**. `AlembicRenderer` uses that value as receiver fill whenever `visual.active()` is true. It does not know whether liquid was routed to waste or collected. This is processed fraction, not elapsed time and not collected volume. Reclamation also emits an active visual and therefore enters the same fill branch. [S05] [S08] [S25] | Give the renderer explicit operation, collected fraction, routed destination, and condensate state. Separate purification from reclamation. |
| F03 | **Confirmed:** world alembic drips use fixed offsets in `StationParticles.emit()`; the offsets do not rotate with `FACING`, while the model does. [S09] | Use one station-local transform for geometry, particles, sound anchors, and optional interaction hints. |
| F04 | **Confirmed:** the kettle liquid quad spans approximately 2.56–13.44 model units in both X and Z. The generated bowl interior is roughly X 3–13, Z 3–11, with a rack behind it. The visual surface does not match the bowl bounds. The sealed cloth has similarly independent bounds. [S06] [S10] | Define shared anchors/bounds; constrain liquid, foam, cloth, and the paddle to the actual vessel. Validate exposure/clipping from all directions. |
| F05 | **Confirmed:** the world paddle angle is `(worldTime + partialTick) × circulation × constant`. Changing circulation changes the entire accumulated angle. The renderer directly consumes the latest sparse values; there is no per-station interpolation history despite `StationVisual`’s comment. [S04] [S06] | Integrate angular velocity into a continuous client pose and interpolate bounded snapshots. Validate the visible severity of current discontinuities. |
| F06 | **Confirmed:** `animationIntensity` is not applied by the world kettle renderer or profile-mote motion. In the GUI, reduced motion chooses `0.3` instead of respecting an intensity of zero. Preparation and distillation do not share a presentation policy. [S06] [S13] [S14] [S15] [S19] | Centralize motion policy. Zero must remain zero; reduced motion must have a documented behavior for every channel. |
| F07 | **Confirmed:** the same kettle vent texture/model is selected for lit and unlit blockstates; its pixels contain orange/yellow fire. Actual light emission already changes through `LIT` (kettle 9, alembic 7). [S10] [S30] [S32] | Add a cold vent appearance and a separate lit appearance. Retain coarse, state-based block light. |
| F08 | **Confirmed:** the entire alembic model, including copper and brick, declares `minecraft:translucent`. [S10] | Split opaque housing from receiver glass/liquid. **Validate live:** sorting artifacts and performance on supported renderers; do not claim an observed shader defect. |
| F09 | **Confirmed:** the GUI mortar uses mode-wide fixed colors and repeated rectangular fragments; the world view shrinks copies of the original item according to processed amount. [S07] [S14] | Give each preparation family distinct silhouettes and evolving material structure; preserve real reagent identity. |
| F10 | **Confirmed:** kettle GUI liquid color blends from a fixed water blue toward final output using three phase-dependent constants. The world surface can already display the target output color. Neither is a reliable depiction of an intermediate potion on a long route. [S06] [S13] | Use explicit presentation colors for the current accepted transformation stage, without pretending color determines recipe validity. |
| F11 | **Confirmed:** the GUI has substantially richer signature depiction than the world renderer, which mainly shows a tinted liquid plane and a generic trouble overlay. [S06] [S13] [S26] | Share signature presentation definitions across both views. Avoid making every reaction look like generic foam. |
| F12 | **Confirmed:** apparatus loops are screen-relative; nearby observers do not get equivalent continuous apparatus audio from this class. `INGREDIENT_ADD` exists as an asset/registration but is not dispatched by the reviewed production addition flow. Kettle completion has both a world sound and a result-screen cue path. [S18] [S31] | Add bounded spatial apparatus audio and accepted-action sound dispatch. Deduplicate completion for the controller; verify actual audibility in game. |
| F13 | **Confirmed:** the pinned HUD advances rows by 11 GUI pixels but renders 16-pixel item sprites, so adjacent sprite rectangles overlap by 5 pixels. It shows at most eight cost entries without an overflow summary. [S20] | Use 18-pixel item rows or intentionally smaller dedicated icons, plus an explicit overflow indication and position controls. |
| F14 | **Confirmed:** world rack rendering skips empty slots before drawing their labels and uses a six-code-point label cutoff. Thus reserved empty shelves and labels are not represented there; equal character counts do not guarantee equal rendered widths. [S21] | Draw empty reservation markers/labels without fake full bottles; truncate by font width and expose full text when targeted. |
| F15 | **Confirmed:** most apparatus/UI art is drawn as flat rectangles, lines, and generic gray panels. Kettle loading lacks visible slot-group captions; plan/notebook screens expose many closely packed actions. [S11] [S12] [S13] [S14] [S15] [S16] [S17] [S33] | **Proposed:** introduce material-specific apparatus art and stronger hierarchy. **Validate live:** task comprehension and action density; do not call every compact layout broken. |
| F16 | **Confirmed:** pausing a session stops server simulation, but `SessionManager.pause()` does not publish a paused world presentation state. Observers can keep seeing the previous active visual, and particles key off that flag. Sealing resets visuals to `IDLE`, losing intermediate depiction. [S09] [S23] | Send explicit paused/count-in activity and reconstruct a frozen material snapshot when sealed. |
| F17 | **Confirmed:** many semantic colors outside `Ui` are hardcoded, including heat markers, grade text, carrier warnings, and HUD accents. Existing glyph/text redundancy is valuable but does not make all surfaces palette-aware. [S13] [S14] [S15] [S20] [S34] [S40] | Audit semantic colors and marker sizes through shared tokens; keep potion/material color separate from instruction color. |
| F18 | **Confirmed:** documentation reports substantial automated coverage, but human onboarding, two-client latency fairness, native-language review, and motion/rendering-environment gates remain pending. Showcase PNGs were not recaptured for 1.1.0. [S02] [S03] [S38] | Generate fresh evidence for this overhaul and report pending gates honestly. |

## 4. Unified art direction

### 4.1 Material language

Use a compact apothecary workshop vocabulary: worn gray stone, pale pestle stone, warm wood, dark iron, copper edges, brick burners, clear glass, and modest cloth labels. Preserve the recognizable silhouettes and original palettes. All existing workstation/item textures are already 16×16; this project is a refinement of those assets, not a conversion from high-resolution art.

| Station | Visual identity | Main movement | Primary player question |
| --- | --- | --- | --- |
| Mortar | Heavy stone bowl, pale pestle, ingredient-specific fragments, small kit shelf | Press, grind, lift, fold | “Has this reached the right texture?” |
| Kettle | Dark iron vessel, copper rim, controlled burner, readable liquid, real paddle | Thermal drift, circulation, timed addition, settling | “What does the reaction need now?” |
| Alembic | Copper still, visible coil, glass inspection section, two routed destinations | Vapor condenses, a valve routes drops, receiver fills | “Which part of the run should I keep?” |
| Rack/carrier | Wooden compartments, bottle silhouettes, cloth tags, restrained grade stamps | Short acknowledged placement/removal, otherwise still | “What is here, what is reserved, and what will move?” |
| Notebook | Familiar readable Minecraft panel with paper-like page area and copper/cloth chapter accents | Immediate navigation; optional brief page-edge shift | “How do I prepare, improve, or repeat this batch?” |

Keep block/item pixels crisp. Use integer GUI coordinates, nearest-neighbor sampling, stepped highlights, and limited ramps. Large smooth gradients, blur, bloom, chromatic aberration, cinematic depth of field, and high-poly machinery are outside the chosen style. A recipe’s particles must not resemble combat spells continuously firing from a domestic workstation.

### 4.2 Common visual grammar

- **Solid object:** material actually present or reserved by the job.
- **Outlined/hatched object:** a recipe requirement, empty reservation, or preview. Always label it; never render it like collectable output.
- **Inset band + boundary ticks:** acceptable range. Keep boundaries visible in grayscale.
- **Triangle/stem:** present value. **Small directional arrow:** tendency, not an additional target.
- **Check + short verb:** accepted/satisfied. **Exclamation + instruction:** corrective action. **Pause bars:** temporarily paused. **Tied-cloth symbol:** sealed. **Crate/wrench symbol:** held for recovery.
- **Grade stamp:** use a sprite and localized grade name; retain score/details on demand. Do not make every potion permanently sparkle.

Normal menus retain the vanilla gray panel/bevel. Material accents frame the apparatus or identify sections; they do not sit behind dense text. Use the existing Minecraft font at its normal scale. Prefer measured wrapping and reflow over shrinking text. Keep decorative texture noise out of text wells.

### 4.3 Effect timing rules

These are proposed presentation timings, not changes to simulation time:

| Feedback | Trigger | Duration/transition | Reduced motion / zero decorative intensity |
| --- | --- | --- | --- |
| Slot acceptance | Server acknowledges successful inventory/staging change | 120–180 ms border settle; no delayed item availability | Static check/updated item |
| Valve/draft change | A valid input changes its discrete level | 100–150 ms mechanical detent | Immediate new position |
| Reagent addition | Simulation records an accepted addition outcome | 200–300 ms icon dip/pour; ripple decays by 450 ms | Item updates immediately; static “Added” acknowledgment |
| Corrective warning | Relevant state crosses a warning boundary | Immediate symbol/text; minimum readable residence unless superseded by a more urgent cue | Identical text/symbol, no shake |
| Endpoint reached | Actual preparation texture enters endpoint range | One 150 ms rim highlight, then steady readiness mark | Steady mark only |
| Completion | Authoritative result commits | 250–400 ms apparatus settle, one quiet cue | Result available immediately with static grade stamp |
| Seal/pause | Server confirms lifecycle transition | Moving apparatus stops within 150 ms; materials remain in place | Stop immediately |
| Resume | Existing count-in ends | Start from saved pose/state; 100–200 ms motion ramp | Immediate pose updates, unchanged count-in |

UI animations never block input, extend a cue window, consume material, or delay output collection. Timing meters and hit detection use the current simulation state, not eased decorative geometry.

## 5. Architecture: trustworthy presentation first

### 5.1 Extend the compact view instead of exposing the ledger

`StationVisual` currently overloads the same fields for different processes: mortar pressure is called heat, processed fraction is called trouble, and alembic coolant is called circulation. Keep a small synchronization contract, but make its meaning explicit. Add a proposed common `StationPresentationSnapshot` with operation-specific payloads, or equivalent typed records behind an adapter. These names are new design names, not existing APIs. [S04] [S05] [S22]

| Payload | Required data | Derivation |
| --- | --- | --- |
| Common | Schema, station incarnation, job ID if present, monotonic revision, server sample time, simulation step, operation, lifecycle/activity, controller identity needed for local ownership, servings, presentation/profile key, selected material/item display | Authoritative station/session; no full inventory or ledger embedded in high-frequency visuals |
| Mortar | Preparation mode, processed fraction, pressure, clump, texture enum, pestle X/Z, pressing flag, work rate, fold counter | Read-only getters on the existing preparation simulation; bounded values |
| Kettle | Phase/signature, draft, burner value, heat, circulation, trouble, accepted addition index/outcome, current display color, next display color, phase progress | Current simulation and immutable plan snapshot; retain actual item tint support |
| Alembic purification | Heat/coolant settings and values, total distilled fraction, **collected fraction**, condensate enum, collecting flag, last-condensed rate | Read-only simulation access; collected fraction is existing accumulated heads + heart + tails |
| Reclamation / quick finish | Operation-specific progress, active/stalled state, input/output display, return-container/byproduct preview | Existing passive job state; never borrow purification receiver semantics |
| Idle/output/sealed/recovery | Owned material summary, actual output availability, retained process appearance when readable, recovery reason | Current public slots plus safe ledger summary; opaque unknown data gets a generic sealed/recovery appearance |

Add getters for existing accumulators rather than reconstructing collected volume by integrating client particles. If merely exposing existing state, avoid changing simulation serialization or score behavior. Reuse the job UUID already present in `JobLedger`; add a separate station-incarnation discriminator for stale packets after break/replacement. Do not persist renderer caches inside potion metadata.

Represent presentation activity separately from `JobState`: `COUNT_IN`, `WORKING`, `HELP_PAUSED`, `RESUME_COUNT_IN`, `SEALED`, `RECOVERY`, `OUTPUT_READY`, `IDLE`. These are display states, not new ledger ownership states. `READY`/`RESOLVED` are conceptual lifecycle moments in existing documentation; they are not current `JobState` enum members.

When a running job seals, build a frozen presentation from its saved simulation. Preserve liquid height, processed material, collected fraction, and pose. On missing/unreadable data, show a restrained sealed representation and the recovery message instead of trying to interpret unknown arrays.

### 5.2 Controller and observer rendering

The controller continues to use `ClientSession` prediction for responsive local play. Observers use server snapshots. Both pass through the same presentation adapter and visual definitions, so the same signature has the same visual meaning. Keep scores and committed outcomes authoritative. [S24]

For observers:

1. Keep previous/current snapshots in a **per-station client cache**, keyed by dimension, position, incarnation, and job ID. BER instances are shared by type; do not store an individual kettle’s angle directly in the shared renderer. See [Forge BER guidance](https://docs.minecraftforge.net/en/1.20.x/blockentities/ber/).
2. Retain approximately 2 Hz continuous observer updates initially, matching the current ten-tick cadence. Interpolate continuous values over the observed sample interval, bounded to 250–500 ms. Critical lifecycle/routing transitions and accepted one-shot actions get immediate bounded updates.
3. Integrate paddle phase from angular velocity. Never recompute accumulated angle by multiplying the current velocity by absolute world age. Preserve phase on velocity changes and reverse smoothly; wrap the angle periodically to avoid precision loss.
4. Discrete states switch promptly. Do not interpolate a receiver between waste and collection while telling the player it is already collecting; only the small mechanical movement may ease.
5. Extrapolate decorative motion for at most one sample interval. After roughly one second without a valid fresh sample, stop work bursts and fade loops; keep the last material pose. Do not fabricate progress during a stall.
6. Clear caches on unload, disconnect, dimension change, station replacement, job change, and resource reload where resources are cached. Late joiners receive a complete current visual baseline without replaying historical bursts.

Separate continuous presentation packets from whole block-entity updates if profiling confirms unnecessary inventory/NBT traffic: the current `getUpdateTag()` includes inventory, finished flags, fuel, and the view. Initial chunk tracking and inventory changes still need the full relevant state. Do not solve smooth animation by sending full block-entity NBT every tick.

### 5.3 One-shot event contract

Introduce a small `StationEffectEvent` for server-accepted loading, addition, fold, receiver change, completion, seal, recovery install, and collection where continuous state is insufficient. Suggested identity: `(incarnation, jobId, sequence, kind)`; include the relevant simulation step and a bounded item/material descriptor only when needed.

- Generate effects at the accepted action/commit, not on every render frame or every received snapshot.
- Keep a bounded deduplication window. Reconciliation must not replay a splash, chime, fold, or narration.
- A locally predicted addition may show immediate subdued motion. Confirmed feedback upgrades that event once; rejection returns to the pending tray with a specific explanation. Never mark inventory consumed or output finished from animation alone.
- If automatic late additions occur in the existing simulation, show “Added automatically — timing missed,” with the actual outcome. Do not give a perfect-timing flourish.
- Coalesce bulk inventory moves into one placement/clink event. Hopper extraction does not play a fanfare for each item.
- Completion follows the committed job result; reconstructing a station with finished inventory is not a new completion.
- Practice events stay local and visibly labeled as practice. They create no world effects, items, milestones, or spatial sounds at `BlockPos.ZERO`.

All C2S messages remain requests validated on the server thread: loaded chunk, correct station/menu or live session, player reach, ownership/controller rules, input bounds, current revision, and rate limits. Preserve existing transaction guards. Update the channel protocol if wire compatibility changes; provide a clear mismatch refusal instead of weakening strict version checks. Add no unauthenticated packet that instructs observers to play arbitrary resources. [S23] [S24]

## 6. Shared layout and interaction specification

### 6.1 Components and sizing

Refactor `Ui` and repeated screen code into a small shared set: `StationHeader`, `PhaseStrip`, `TargetGauge`, `ActionTray`, `PriorityCue`, `MaterialPreview`, `SlotGroup`, `StatusBadge`, and `RecipeStepRow`. Use the existing `Screen`, `AbstractContainerScreen`, buttons, tooltips, and focus system. No new GUI framework is needed. [S11] [S12]

Use 8 GUI pixels outer padding, 4 pixels between related elements, 8 between groups, 18-pixel inventory slot spacing, normal-size font with 10–12-pixel line pitch, and at least 18-pixel button height where possible. Main actions use 20–22 pixels. Small icon buttons need an adequate hitbox, visible keyboard focus, localized narration, and a tooltip. Use authored arrow/check icons where font-glyph availability is uncertain; their narrated names remain text.

There are two layouts:

- **Compact:** logical viewport below 480×300. It must work at **320×240** without reducing the font size. Only decorative canvas area shrinks; action cues and target bands remain visible.
- **Comfortable:** viewport at least 480×300. Expand the apparatus and expose secondary explanation/history beside it. Cap the core panel near 440×280 so it remains a focused task rather than filling an ultrawide monitor.

Reference compact minigame geometry at 320×240: top toolbar at `(4,4)` with height 18; panel at `(6,26)` sized `308×208`. Positions below are panel-local:

| Region | Rectangle `(x,y,w,h)` | Contract |
| --- | --- | --- |
| Header | `(8,6,292,26)` | Actual reagent/output identity, quantity, phase/count-in/status. Long names have a full tooltip and focused description. |
| Phase / endpoint / run strip | `(8,36,292,12)` | Process context; time remaining uses a clock icon, distinct from material progress. |
| Apparatus and direct controls | `(8,52,292,96)` | Station-specific layout below. |
| Priority cue | `(8,152,292,22)` | Two readable lines; no decorative drawing over it. |
| Action row | `(8,178,292,22)` | Current ingredient/addition, mortar actions, or receiver control. |

For taller screens, assign extra height to apparatus and explanation before adding padding. Derive draw rectangles, mouse hit tests, focus rectangles, tooltips, and scissor bounds from one layout object. Do not move the complete panel with screen shake; a decorative vessel offset must never move a control away from its hitbox. [Forge screen guidance](https://docs.minecraftforge.net/en/1.20.x/gui/screens/)

Containers must keep slot positions and `AbstractContainerScreen` transforms consistent. Reposition slots deliberately if expanding the station area; update menu constants, client layout, JEI transfer geometry, and tests together. Do not visually move a slot while leaving its click target at the old coordinate.

### 6.2 Information hierarchy

Show information in this order: **what is being made → what to do now → current material/target → upcoming step → optional numbers and scoring detail**.

The primary action is always explicit: “Choose recipe,” “Prepare materials,” “Start brewing,” “Resume batch,” “Finish preparation,” “Collect output,” or “View recovery reason.” Explain disabled actions beside them or in an accessible focused tooltip; no silent disabled button.

Use a deterministic priority resolver rather than “the last cue added to a list.” Order: connection/recovery obstruction; imminent required addition or routing decision; dangerous/strong corrective deviation; announced disturbance; phase/endpoint transition; routine status. Keep an active timed action visible even when a disturbance also appears. The second cue line carries that disturbance; less urgent facts stay on their gauges. Never overwrite “Add now” with a generic “Circulation steady.”

Use existing thresholds from simulation/profile data. Add small **presentation-only hysteresis** for repetitive noncritical text and audio: enter/exit edges may differ slightly and routine status can persist 300–500 ms. Never delay addition windows, receiver guidance, target markers, or endpoint eligibility.

### 6.3 Loading, staging, and recipe selection

In `KettleScreen`, visibly group **Bottles**, **Reagents**, **Fuel**, **Paddle**, and **Returns**. Empty slots get a low-contrast role icon; a requirement preview gets a hatched frame, not a normal item stack. Slot hover/focus explains accepted content and exact current reason for refusal. Read counts from real state.

Put secondary actions in a bounded workshop utility area, with a compact “More” disclosure when needed. Preserve collection, return-unused, comparator mode, and Notebook access. The comparator button should read “Comparator: output” / “Comparator: job” in its tooltip and narration rather than relying on `≡` versus `☰` alone.

For `KettlePlanScreen`, keep server search, favorites, alternatives, exact route keys, generation handling, selected carrier, and preview revision checks. Reorganize the flow into:

1. **Choose result:** searchable list, quantity 1–6, meaningful filter labels, alternatives count.
2. **Review route:** ordered input → intermediate results → final result; show actual item icons and per-step reagents. Do not collapse alternative routes that spend different materials.
3. **Stage materials:** sources explicitly labeled “Station,” “Inventory,” and the chosen carrier/loadout. Show raw/prepared policy, shortages, returned containers, fuel charges, and paddle wear information where available.
4. **Start:** “Prepare materials” stages the server-approved preview; “Start brewing” begins the existing interactive job. A changed revision refreshes the preview with “Materials changed; review the updated plan.”

At compact width, result list and route detail become two pages with preserved selection, search, and back navigation. Use a stable footer with the current primary action and Back. Avoid compressing every control into tiny neighboring buttons. At comfortable width, keep list/detail side by side.

Distinguish existing quick finishing (`Operation.FINISH`) from interactive brewing: label it **“Finish potion”**, explain the specific supported conversion and preserved quality rule, and offer it only where the server marks it eligible. The visual overhaul must not turn it into a universal “skip minigame” action.

Water filling gets an acknowledged short pour/surface response only after the existing transaction succeeds. State “One bucket fills up to four empty bottles” with actual remaining capacity. An empty kettle has no persistent imaginary water reservoir.

**Acceptance:** a player can identify every slot role without inserting trial items; all six bottle quantities show correct costs; stale previews never commit; switching carriers never moves items before confirmation; the compact route view exposes every step and shortage through scrolling/focus.

## 7. Mortar — make texture the central reward

### 7.1 World model and animation

Move the pestle cuboid out of the static placed-block model. Create a movable baked part rendered by `MortarRenderer`, with a model-unit pivot and a safe bowl envelope. Keep an assembled inventory/item model showing a resting pestle. Update `StationShape` deliberately: the fixed bowl remains the interaction/collision volume; a moving decorative pestle should not cause changing collision geometry every frame.

Map normalized pestle X/Z to the bowl interior, then clamp the complete tip—not just its center—inside the rim. Pressure changes the vertical contact depth by roughly 1–1.5 model units. Lifting raises it 2–3 units, with a short inward folding arc. The shaft tilts at most about 12° from the working pose. These amplitudes are art defaults, bounded by the real mesh.

Working motion follows actual pressing/work. Idle hovering does not emit grit. A fold follows the fold counter/accepted state and lasts about 250 ms; a repeated click that the simulation refuses does not create a successful fold animation. On pause/seal, freeze the material and settle the pestle into a non-obscuring rest pose. Resume begins at the saved working position without replaying previous strokes.

### 7.2 Material transformations

Keep the real input icon in the header/world inset. Use the existing preparation mode and processed/pressure/clump values to choose the evolving workpiece. Reagent-specific appearance may be supplied by a **client resource-pack presentation mapping**; fall back to the mode palette and real item icon for unknown modded inputs. Do not infer chemistry, preparation eligibility, or score from item names, colors, or tags invented by this overhaul. [S14] [S28]

| Existing mode | Raw appearance | Working / endpoint appearance | Mistake and corrective feedback |
| --- | --- | --- | --- |
| Crystalline | 5–9 angular chips with short pale highlights | Smaller angular grains; an even bed at endpoint | Excess pressure gives one sharp crack cue and an outlined pressure warning; overworked material becomes fine dusty grains. |
| Fibrous | Short crossed strands and uneven tufts | Strands shorten and distribute evenly; folding visibly spreads a clump | Clumping gathers fibers at the tip; cue “Lift and fold to loosen.” Excess pressure never becomes an unrelated explosion. |
| Soft | Irregular moist pieces with a small shaded contact mark | Pieces merge into a coherent mash with restrained streaks | Dense clump sits under the pestle; overwork shows a flattened smear. Use the actual endpoint/overwork thresholds. |
| Brittle | Thin flakes with clear broken edges | Flakes fragment into a loose, even powder | Tiny edge fragments acknowledge real work; an overworked dry halo stays within the bowl. |
| Liquid | Small pool with two separated tonal patches | Folding makes the pool uniform; a few large, slow ripples | Overwork reveals a separated surface streak where the existing state indicates it. No invented viscosity or spoilage mechanic. |

Use 3–4 authored material frames per family with deterministic fragment placement and small state-driven transforms. State changes, not time alone, select raw/coarse/endpoint/overworked appearance. Blend only decorative boundaries; endpoint cues switch immediately when the simulation does.

### 7.3 Mortar screen

Compact apparatus: left 128-pixel region contains an approximately 88-pixel bowl; right 156-pixel region contains processed and pressure gauges plus a small target-texture swatch. Keep a labeled **“Aim for this texture”** reference beside the current material. It must be a target illustration, never a fake second inventory item.

The processed gauge labels the endpoint band and overwork boundary. The time strip is explicitly “Time left”; reaching the end of that strip is not presented as successful preparation. Show “Ready — finish preparation” when appropriate, followed by “Keep working” or a corrective instruction otherwise. Keep Finish available according to existing gameplay rules, with an honest description of the likely result; do not add an automatic success cutoff.

Action row: Press/hold or toggle-state indicator, Fold, Finish. Default mouse/keyboard paths remain equal. The pressure marker and target cursor always reflect actual input/state even when decorative motion is disabled. Release all held/toggled inputs on overlays, focus loss, screen replacement, and session end.

**Acceptance:** five modes are distinguishable in grayscale at compact scale; the world pestle and controller view agree on working versus idle; fragments evolve from processed fraction; fold/endpoint cues happen once; no dust is emitted without work; zero intensity leaves all gauges and endpoint guidance usable; old sealed batches resume without changing extraction.

## 8. Kettle — a reaction with understandable causes

### 8.1 Apparatus and thermal behavior

Retain the iron/copper silhouette. Define shared model-space anchors for liquid bounds, rim, burner vent, draft control, paddle pivot, reagent-entry point, and six bottle positions. Against the current mesh, use an interior starting envelope around X `3.15–12.85`, Z `3.15–10.85` and Y `6.2–12.6` model units; revise these constants together with any model change. Liquid level represents owned servings, not elapsed time. Empty/loaded bottles remain bottles until a job owns them. [S06] [S10]

Add cold and lit vent textures. Draft changes a small shutter/lever and the localized ember appearance; heat changes boiling behavior with the existing thermal inertia. Retain current coarse light levels initially. Warm vent pixels can brighten smoothly in the client, but do not alter block light every frame or promise dynamic-light support.

Place the real wooden/iron paddle as a model part or the existing item representation with a correct pivot. Its direction/speed follows circulation, not which key was most recently pressed. When input stops, let the existing simulated circulation decay drive the movement. A sealed cloth sits over the actual rim; the paddle parks beside it instead of clipping through it.

### 8.2 Phase and accepted-addition presentation

The phase strip shows **Bloom → Infuse → Settle**, marking the actual current phase. Respect plans whose configuration differs; do not invent missing phases. Give a short preview when the existing phase transition is imminent. The GUI vessel uses authored iron walls/copper rim and a small visible paddle, with liquid, material, and state markers inside a clipped apparatus region.

For colors, derive current/intermediate display stacks from the validated plan’s accepted steps. Start from the actual input potion, including non-water inputs. Store enough stable display data in the session description to handle long routes; do not guess by replaying arbitrary recipes client-side. If a route has no meaningful intermediate color, keep its input tint until the accepted transformation and label the reaction state explicitly. Use a 200–400 ms local mix toward the next tint after acceptance. Color is atmosphere; recipe correctness still comes from server data.

The addition tray shows real reagent/kit identity, quantity owed for the batch, scheduled phase, window status, and rebound Add key. A short ghost path may connect tray to vessel while a window is open; it disappears in reduced motion. An accepted addition drops a tiny display-only material representation into the correct anchor, sends a ripple across the surface, and acknowledges “Added.” A refused input leaves the material visible and states why. A missed/automatic addition uses restrained feedback and the actual scoring explanation.

### 8.3 Signature behavior matrix

Share these mappings between the GUI and world presentation. The world can use fewer elements, but must preserve the same meaning. Use the existing simulation’s signature channel, trouble, phase bands, and reversal conditions. **Heat does not generate signature trouble in the current model:** keep thermal feedback distinct so players are not taught a false cause. [S26] [S27]

| Signature | Healthy appearance | Trouble appearance | Guidance and response |
| --- | --- | --- | --- |
| None | Clear tinted surface with minimal circulation marks | Use actual heat/circulation deviations, without a fabricated chemical defect | Show target bands directly. |
| Bloom simmer | Small evenly spaced bubbles | Uneven bubble clusters as signature trouble rises | Steady circulation redistributes them; thermal boil intensity is a separate layer. |
| Settle clear | Clearing center and fading haze | Broken clarity/remaining suspended material | Calm the brew within its actual phase band; fade haze with state. |
| Light foam | Thin broken foam islands near rim | Islands thicken into a connected foam layer | Match circulation; excess agitation visibly spreads foam. Keep heat warnings separate. |
| Dense sediment | Small particles remain lifted/distributed | Dark bed thickens at the bottom of the cutaway | Show “Lift sediment with a brief strong stir” when appropriate; once lifted, guide back toward the normal band. |
| Delicate shimmer | A few connected bright fragments on a calm surface | Shimmer breaks into disconnected fragments | Gentle movement preserves it. Never use a strobing full-surface flash. |
| Magma streaks | Short warm ribbons disperse through the liquid | Thick distinct streaks remain | Sustained circulation stretches/disperses them according to trouble, with no fire outside the vessel. |
| Suspended bubbles | Bubbles release after the announced reversal | A persistent cluster remains below the surface | Show the reversal instruction only at the existing reversal condition; guide toward the opposite of the accumulated dominant direction. |
| Dark whorls | Distinct dark addition clouds slowly clarify | Dark patches gather after additions/agitation | Brief calm clears them; do not render permanent smoke implying damage or poison exposure. |
| Aligning glints | Sparse glints form short ordered arcs | Glints scatter, especially with late vigorous stirring | Follow the phase’s calm band; low motion shows arrangement rather than orbiting points. |
| Floating motes | A few flecks are drawn beneath the surface | Flecks gather on top | Gentle circulation draws them under; vertical placement carries meaning without relying on color. |

All frame selection, density, and amount parameters are bounded. At trouble zero, only the quiet healthy motif remains. At trouble one, the liquid must still be readable; particles may not cover the target marker or action tray.

### 8.4 Disturbances and precision

Use the seeded disturbance schedule already present. Before a cold draft, show the existing warning and a slight directional plume/vent cue; after it, show the actual heat response. A burner flare briefly strengthens the vent ember, not the whole screen. A surge changes the surface only when the simulation changes. No random weather penalties, burns, block damage, or lost ingredients are introduced.

Keep a clear heat trend arrow based on burner-versus-liquid heat, which the current screen already calculates. The player should understand why reducing draft does not instantly cool a hot brew. Place the trend beside heat rather than hiding it in a long header string.

Compact apparatus columns: 56 pixels for draft controls; 164 pixels for vessel and circulation gauge; 56 pixels for stir controls, with 8-pixel gaps. Put the current heat gauge at the vessel edge. The priority cue and addition tray stay in their dedicated rows. Comfortable layout adds short explanations and a visible upcoming reagent list.

**Acceptance:** all eleven signature enum values have a deliberate visual treatment; low/high heat can change without falsely changing signature trouble; intermediate potion tint follows accepted route state; circling the mouse and using keys produce equivalent state and feedback; the paddle never jumps because velocity changed; observers see accepted additions once; the liquid/cloth never extends onto the rear rack; help, sealing, and reduced motion stop the appropriate effects.

## 9. Alembic — show the liquid’s route

### 9.1 Separate three different quantities

The current simulation already distinguishes total distilled fraction, routed collection, and condensate quality. Make that distinction visible. [S15] [S25]

| Quantity | Presentation | Must not imply |
| --- | --- | --- |
| Total distilled fraction | A horizontal **Run progress** strip, with approximate heads/heart/tails regions | That all processed liquid is in the receiver |
| Collected fraction | Actual level in the collection vessel; rises only when collecting and condensation is positive | Guaranteed output count or purity |
| Condensate character | Inspection section showing cloudy → clearing → clear → haze rising → hazy, with matching words | A perfectly predictable fixed-time collection window |

Retain nominal region markings as **approximate guidance**. Use the actual `Condensate` enum for precise guidance. Do not reveal hidden exact cut parameters beyond information the current game intentionally exposes. Add a read-only `collectedFraction()` getter from existing heads/heart/tails accumulators; preserve their saved state and score formula.

### 9.2 World apparatus

Keep the footprint and recognizable copper still. Add a readable pipe/coil connection and small inspection window. Split the opaque copper/brick housing into solid/cutout geometry; isolate receiver glass and liquid into the appropriate layer. Use sparse glass edges/highlights and a mostly clear center so the liquid remains legible. Check item rendering separately from placed-block rendering. [S08] [S10]

Provide named model-space anchors: vapor outlet, coil inlet/outlet, diverter pivot, waste outlet, receiver mouth, receiver fill bounds, and output shelf. Rotate all through the same facing transform. Start with waste and collection outlets visibly separated by at least 3 model units; exact placement must remain inside the one-block apparatus envelope.

- Vapor movement begins only when the simulation produces vapor/condensation. Low heat or ineffective cooling reduces the corresponding flow.
- Coolant level changes a valve position and a bounded coil accent; use a coarse visual transition rather than repainting the complete copper coil blue.
- Condensate drops appear at a capped rate derived from `lastCondensed`, travel through the inspection section, and follow the **current accepted** diverter state.
- Collection liquid rises from collected fraction. Routing to waste stops that rise, even while run progress continues.
- Cloudy material has a few solid pale flecks; clear liquid has an open center; rising haze introduces a restrained amber edge. These are shape/opacity differences as well as colors.
- Sealed jobs retain collected liquid and apparatus pose. Do not empty and refill the receiver when reopening the screen.
- Actual output bottles appear only after the server commits them; the distillation liquid illustration is not an extractable inventory object.

Default visual droplets are local particles or pooled small quads, never spawned item entities or physical fluid blocks. They cause no collision, damage, or liquid placement.

### 9.3 Distillation screen

In the compact apparatus area, use three 92-pixel columns separated by 8 pixels: **Still**, **Coil**, **Receiver**. The still and coil have clear target gauges and paired minus/plus controls. Show their current detent values as labeled pips. A short pipe path connects them; the receiver includes a small inspection sample and explicit waste/collect route symbol.

The action row shows both current state and action: **“Routing: waste — [R] Collect”** or **“Routing: receiver — [R] Send to waste.”** Use the rebound key and adapt text width; at compact scale split the current state into the cue row and keep a full-width action button. This avoids ambiguity between a button’s current status and what clicking it will do.

Show “Clear condensate — collect” or “Haze rising — switch to waste” in the priority cue. Do not replace these with a general timer. Heat/coolant corrections appear at their gauges unless more urgent than ordinary status. The collected vessel and run strip must remain visually separate at every GUI scale.

### 9.4 Purification preview, reclamation, and finishing

Preserve the existing distinct purification/reclamation previews and exact group rules. Improve their hierarchy: **input group → operation → outputs/returns**, with actual fuel and quality-cap information. For the default recipe, “3 identical potions → 2 purified” is a useful example, but render values from the loaded recipe/config rather than hardcoding them. The preview must explain incompatible metadata and already-purified exclusions.

Reclamation has its own modest visual: the still works steadily, a faint residue settles, and the **salt/returned-bottle output area** is highlighted at completion. Its receiver does not falsely fill with purified liquid. Controls that belong to interactive purification are absent. Show passive progress and the existing stop/seal behavior with its real semantics.

Kettle quick finishing similarly uses a short finishing state with its actual output/container conversion and passive progress. It receives no invented precision grade or mastery flourish.

**Acceptance:** waste routing at positive condensation leaves collection fill unchanged; collection at zero condensation leaves fill unchanged; resuming preserves fill; all five condensate states remain distinguishable with sound/color disabled; four station facings place drops correctly; reclamation never renders purification’s filled receiver; output count and quality match the existing server implementation.

## 10. Complete the workshop journey

### 10.1 Approach and inspect

At normal walking distance, silhouette, contents, restrained motion, and lit state communicate what a station does. Within normal interaction range, looking at it can show a compact two-line prompt: station/job state and next action. Use the current interaction reach; do not add a longer gameplay reach merely because a hint is visible.

With Jade present, prefer its existing station information surface for detailed state. Add concise operation/status/next-action data there instead of stacking two large panels. Without Jade, the mod’s own optional inspection hint provides the essentials. Both consume the same presentation summary, including sealed/recovery reason and controller status. The rack is a separate block/entity family; explicitly add rack handling if extending Jade coverage rather than assuming the current `StationBlock` registration covers it. [S36]

World hints are opt-out and never appear through walls. Full GUIs remain the reliable interaction surface. The initial overhaul does not require new pixel-precise world clicking or a replacement first-person control system; accepted actions in existing menus still produce physical workstation feedback.

### 10.2 Rack and carrier

Preserve six exact stack slots, reservations/templates, labels, selected slot, loadout name, strapped variant, and metadata-safe movement. These are logistics tools; they do not improve quality or generate experience.

For the world rack:

- Real slots show actual item models and potion tints. A stack is represented by one displayed bottle; targeted information states its count. Do not render one bottle per item in a stack.
- Empty reserved slots show a small outlined bottle **tag/plaque**, not a translucent full-size bottle that can be mistaken for inventory.
- Draw labels for empty reserved shelves too. Use pixel-width truncation based on the physical label area, and display the full label on target/Jade. Cache width/layout until the label, locale, or font resources change.
- Show a grade stamp only for genuine readable brewing metadata. An ungraded vanilla potion does not acquire a fictional Standard quality record.
- At close range, a placement acknowledgment settles the existing display by at most 0.5 model units over 150 ms. It never delays inventory state and never loops while idle.
- Make the world’s two rows of three correspond to the rack GUI’s arrangement. Keep stable server slot indices; only remap the client layout and selection labels deliberately.

For rack/carrier screens, give the selected compartment a frame and textual slot number; reserve color for secondary identity. A template mismatch shows “Missing,” “Different potion/quality/label,” or “Count short” from an exact comparison, without dumping NBT into normal player UI. Pack/restock buttons state what will move and which carrier is targeted. Show a short acknowledged result such as “Packed 4 stacks; 2 stayed on shelf.” Derive counts from the transaction result.

Carrier color/emblem controls need a visible preview of the chosen tag, named options, and saved-state acknowledgment. Preserve the existing strapped model override. If adding item tint/overlay rendering, restrict it to this mod’s carrier/kit assets; do not overwrite the native models of third-party potions.

### 10.3 Completion, collection, and mistakes

Give the result screen a concise first view:

1. Actual output and count, grade name/stamp, and where the output is waiting.
2. One practical outcome statement using existing duration/drink-time policy services.
3. One specific improvement tip derived from recorded contributors.
4. Details, label/note editing, collect, or repeat navigation as secondary actions where supported.

Keep the existing detailed score explanation available in an expandable/scrolling area. Do not describe an in-progress predicted score as final. Practice results say **“Practice complete — no items produced.”** Sealed/interrupted sessions say **“Batch sealed — resume at the station”** and do not play the completion flourish. Recovery states explain the missing requirement in plain language. [S34]

Make output-ready a stable state: real bottles/kits, an output shelf accent, and a readable prompt. A short completion cue may play once, but the player can still identify output after returning ten minutes later. Failure/correction is material-specific: too much pressure, heat still rising, missed timing, cloudy condensate collected. Avoid global red-screen flashes, random shattering, or effects suggesting ingredients were destroyed when the ledger preserved them.

Collecting output uses the existing exact-stack transaction. If inventory/carrier space is insufficient, leave remaining stacks visible and explain what stayed. An animation must never temporarily duplicate an item between station and cursor/inventory.

### 10.4 Notebook, tooltips, and pinned HUD

Keep all eleven existing Notebook destinations available. At compact scale, replace the dense permanent chapter column with a chapter selector and a stable Back action; comfortable layout can show the chapter rail. Preserve last page, search, selected favorite/history row, and scroll position when inspecting or practicing a route. Keep recipe data server-backed and the existing crafting recipe lookup responsive to datapacks. [S17]

Add illustrated, still-first instruction cards for each station:

- Mortar: raw → target texture, pressure band, Fold, Finish.
- Kettle: burner inertia, circulation direction/band, addition tray, and the actual signature glossary.
- Alembic: cloudy/waste → clear/collect → hazy/waste, with run progress distinct from collected amount.
- Recovery: seal → stored station batch or Recovery Batch → resume at matching station.

Reuse the same icons and visual definitions as the live screens. A small optional replay illustrates a card only while explicitly activated; reduced motion shows a sequence of still frames. Preserve free guided practice and route-specific server resolution; no research/unlock gate is added.

Tooltips retain native potion identity and true effective effects. Put grade/benefit in a short section, with extraction/control, brewer/refiner, note, and technical provenance in expanded detail. Do not hide a custom name, erase another mod’s tooltip lines, or claim a duration benefit on metadata-only/unchanged effects. The existing `PotionTooltips` policy logic is the authority. [S35]

Pinned HUD: use 18-pixel rows for 16-pixel item icons; show at most five rows by default plus **“+N more — open Notebook”**. Keep complete costs in the Notebook. Display unavailable/stale-generation state explicitly. Add left/right anchor, vertical offset, compact/full mode, and hide-while-screen-open options. Default to a narrow right-side reminder outside active minigames. Do not overlap the crosshair, hotbar/status area, subtitles, or the mod’s own notices in the tested layouts. Provide offsets for other mods; no universal collision-free claim is possible. [S20]

Station notices remain readable and narrated. Keep actionable error details in the relevant screen until resolved; a timed notice is supplementary. Truncated notices need an accessible complete message/history or relevant-screen explanation, especially when a two-line cap hides the recovery instruction.

### 10.5 Using the finished potion

Preserve the existing quality-aware first-person drink timing: `ClientDrinkTiming` observes the Forge-composed use duration, and `ItemInHandRendererMixin` uses that duration for the animation denominator. This compatibility work already exists; do not apply the quality modifier a second time or replace it with a fixed 32-tick animation. Verify low/baseline/high quality, another mod modifying duration, main/off hand, cancellation, and ordinary unmodified potions. Keep native bottle use and completion sounds recognizable. Do not add a forced camera movement or avatar-arm animation to acknowledge crafting; the workstation tools already carry that feedback. [S41] [S42]

## 11. Sound, particles, and lighting

### 11.1 Sound ownership and mix

Retain the existing sound identities and deterministic generation pipeline. Improve layering only after listening in the actual game. Add 2–3 subtle variants for frequently repeated crush/grind/drip/clink events; variation comes from event identity/local cosmetic randomness, never the gameplay random stream. [S18] [S31]

| Sound family | Drive it from | Mix and stop behavior |
| --- | --- | --- |
| Mortar contact | Positive accepted work and mode/texture | Dry crunch, fine grind, or wet mash; no repeated contact while hovering/paused. Fold has one lift/settle sound. |
| Kettle bed | Actual heat and activity | Quiet simmer to rolling boil; smooth crossfade, muted while sealed/paused. |
| Paddle | Absolute circulation | Low swish, direction-independent pitch; audible work rate without a harsh high-speed whine. |
| Addition | Accepted addition event | One plop/grain fall, scaled for a batch without multiplying volume by six. Missed/automatic input does not play the precision accent. |
| Alembic | Condensed rate and routed outlet | Capped drip rhythm; brief valve detent; cloudy/clear identification remains visual/textual. |
| Handling | Committed transfer | One soft clink/wood contact for a grouped move; suppress automation spam. |
| Cue | New actionable window/warning | Distinct short tones under cue-volume control; do not require hearing to succeed. |
| Completion | One committed result event | One restrained cue per job; no replay on open/rejoin/resource reload. |

Introduce a client `StationSoundManager` keyed per station/job. Nearby observers hear spatial, distance-attenuated apparatus sounds. The controller may receive a modest focus mix, but the same station must not produce duplicate spatial and screen-relative loops. Route a single accepted completion event through the manager and retire duplicate dispatch paths rather than merely lowering their volume.

Start with a maximum of **four audible stations and six simultaneous apparatus loop voices**, using nearest/recently interacted stations with hysteresis to avoid rapid swapping. Fade ownership changes over 150–300 ms. Prioritize the active station and actionable cues. Stop/remove sounds on pause/seal/recovery, unload, disconnect, dimension change, world loss, and stale snapshot timeout. Preserve Minecraft’s Blocks/master categories and the mod’s apparatus/cue sliders.

Every new semantic event needs a localized subtitle. Avoid repetitive subtitle floods for every single grain or droplet; group sustained apparatus sound sensibly. Verify mono downmix, volume zero, headphones, speakers, and multiple active stations. The intended result is a readable workshop, not increasing loudness as a proxy for polish.

### 11.2 Particle and decorative-geometry budgets

Replace per-station-only budgeting with a client-global effect budget while retaining distance checks and Minecraft particle settings. Proposed initial limits, to be tuned using measurements:

| Setting | Full | Reduced / low preset | Off |
| --- | --- | --- | --- |
| New ambient particles | At most 4/second/station and 128/second globally | At most 1/second/station and 32/second globally | 0 |
| Event burst | Up to 8 particles/event, within global budget | Up to 2, or static acknowledgment | 0 |
| Live mod particles | 256 globally | 64 globally | 0 |
| GUI decorative fragments/motes | 80 total across the apparatus; precomputed placement | 24 or static sprites | Static material state only |

Use a distance policy: full nearby detail within 12 blocks, reduced density at 12–24, no decorative particle emission beyond 24. Do not allocate particle objects before checking distance, policy, visibility eligibility, and budget. Prefer already visible/recently interacted stations; avoid an unbounded raycast per particle. Respect engine frustum/distance culling and do not enable offscreen rendering to keep decoration alive.

Steam rises from the actual mouth, ash/residue belongs to the appropriate operation, and condensation appears at the rotated spout. All effects remain local to the apparatus envelope plus a small plume. Rain or nearby blocks must not acquire new mechanical effects. Any optional environmental plume response is cosmetic and must not obscure the source state.

State information cannot depend on successful particle allocation. With the budget exhausted, solid liquid/material forms, gauges, symbols, and text still explain the process.

### 11.3 Render compatibility and light

Preserve vanilla-compatible rendering as the required baseline. Use baked geometry for static bodies and BERs only for moving parts, stateful liquid/material, and item displays. Prefer cutout glass-edge art where suitable; keep genuinely translucent surfaces few, separated, and correctly ordered. Do not put opaque copper/brick in a translucent-only mesh. Forge’s model `render_type` guidance is the starting point, not a guarantee that any shader pack will behave correctly. [Forge render types](https://docs.minecraftforge.net/en/1.20.x/rendering/modelextensions/rendertypes/)

Use scoped pose pushes/pops and supplied buffers. Do not clear or flush global buffers per station, leave altered blending/depth/scissor state behind, or read back the framebuffer. Cache baked parts/sprite references through the resource lifecycle; invalidate them on reload. Item models, translucent glass, liquid, and labels need deliberate depth separation to avoid coplanar shimmer.

Keep current state-based block light unless a separate gameplay decision authorizes changing it: the kettle emits 9 when lit and the alembic 7. Confine full-bright/emissive treatment, if used, to tiny ember/glint surfaces; the entire apparatus should still respond to darkness and skylight. Shader-specific emissive/PBR assets are optional enhancements with a complete vanilla fallback.

## 12. Accessibility and localization contract

Keep existing config keys and user preferences. Extend the current Access UI with scrollable **Controls**, **Presentation**, and **Sound** groups so all relevant settings can be changed without editing TOML. Show which assistance factors the server actually approved, and explain when a changed assistance request takes effect. No presentation preset silently changes server scoring or requests assistance. [S12] [S19]

Introduce one `PresentationPolicy` consumed by screens, renderers, particles, audio, and Notebook illustrations:

| Option | Required behavior |
| --- | --- |
| `animationIntensity = 0` | No autonomous decorative swirl, bob, camera movement, page motion, or flourish. Material/state and direct tool pose remain readable through immediate updates. |
| Reduced motion | No shake, lateral panel motion, orbiting/decorative swirls, or fast repeated flashing. Use stationary material motifs, static trend arrows, and necessary direct tool/gauge state. If a retained small animation uses an intensity multiplier, it must also respect zero. |
| Particle density 0 | No decorative world/GUI particle emission, including profile motes. Solid state illustration remains. |
| Large markers | Every gauge family, including custom kettle circulation, honors the setting; markers never cover target boundaries. |
| High contrast / colorblind safe | Semantic warnings, success, target bands, grade labels, carrier mismatch, and HUD indicators use tested tokens plus text/shape. Actual potion/material tint is not forcibly recolored. |
| Written cues off | Hide supplementary descriptive prose only. Required action labels, error reasons, current routing, endpoint readiness, and timing state remain accessible. |
| Numeric display | Optional exact values supplement the apparatus; they do not displace the only actionable cue. |
| Sound muted | Every actionable cue still has visible and narrated equivalents. |
| Narrator | Announce changed actionable states and focused widgets; coalesce routine updates and do not interrupt urgent instructions with constant metric narration. |
| Keyboard only / reduced input | All loading, planning, minigames, labels, storage, help, and results are reachable; toggle states are visible and released safely on focus loss. |

Preserve rebindable controls and distinguish key-binding labels from hardcoded help text. A change to focus navigation must not steal the existing bound Finish action or send movement/attack to the world. Check both key and mouse bindings, held-on-open keys, focus loss, menu transitions, and return from Help/Access. Fix all apparatus pointer handlers to honor the same overlay-blocked predicate; testing only whether Help is open is insufficient when Access is also modal.

Target 4.5:1 contrast for small explanatory text and 3:1 for large markers/essential boundaries against their immediate backgrounds, then verify actual rasterized appearance in game. Treat these as design targets, not a certification claim. Avoid periodic warning flashes; a steady symbol and one brief acknowledgment are sufficient.

Maintain `en_us`, `es_es`, `pt_br`, `ru_ru`, and `zh_cn`. Translate semantic phrases as complete components with placeholders; avoid English word-order concatenation. Preserve style/bidirectional ordering in fitted text. Test long player labels, multibyte characters, custom fonts, and 30–50% pseudo-localized expansion. Full instructions must be reachable by focus/narration, not only mouse hover.

## 13. Assets and reproducible production

Continue editing generator sources as the source of truth. Updating only generated PNG/JSON/OGG files will be undone by the existing checks. Add deliberate hand-authored source assets only with a documented generation/copy path and clear ownership; do not scrape another mod’s art. [S10] [S30] [S31]

| Asset group | Concrete deliverables | Source / output approach |
| --- | --- | --- |
| Shared GUI | 8×8/16×16 state/action icons; 16×16 slot-role icons; target ticks/marker variants; gray beveled panel parts; small material accent strips | Extend `art/brewing_art.py` and `tools/generate_textures.py`; new `A/textures/gui/` resources with explicit atlas metadata if using an atlas |
| Mortar | Separate pale pestle part, assembled item model, 5 preparation families × 4 material stages, target-texture swatches | Extend `mortar_model()`/model generation; small 16×16 tiles, state-driven placement; no hundreds of per-reagent textures required |
| Kettle | Cold/lit vent, readable draft shutter, paddle pivot, liquid/foam/cloth bounds, rim/body GUI cutaway | Preserve/refine existing 16×16 block textures; update blockstates and model parts together |
| Reaction motifs | Eleven signature treatments, including none/bloom/settle; foam islands, sediment, glints, motes, streaks, bubbles, haze | A compact shared sprite set; shape/arrangement driven by the signature, not eleven full animated texture sheets |
| Alembic | Opaque housing, clear-edge receiver, visible coil/inspection section, diverter, waste/collect paths, five condensate swatches | Generated model parts and 16×16 material tiles; avoid opaque/translucent material mixing |
| Rack/carrier | Empty reservation plaque, grade stamps, label plate, selected-slot and missing-template marks, visible color/emblem preview | Preserve actual bottle item models and strapped carrier override; small overlays and width-aware text |
| Notebook | Station illustrations, process stills, signature glossary cards, recovery diagram | Reuse apparatus parts/icons and loaded recipe item icons; avoid baked English labels in images |
| Sound | Existing identities retained; a few contact/drip/clink variants; accepted-addition wiring; grouped spatial loops | Extend `art/brewing_audio.py`, `tools/generate_sounds.py`, `ModSounds`, `A/sounds.json`, and all subtitle translations |
| Validation assets | Still-state scenes, four-facing geometry gallery, compact/comfortable screens, low-motion recordings | Extend existing smoke-test fixtures; capture real engine output with a manifest naming commit/settings/mods |

Use a new client-only visual mapping file, for example `A/presentation/materials.json`, only if needed for reagent overrides. Document its schema and fallbacks. Keys may select appearance by a known reagent/profile resource ID; values may select bounded visual presets, sprites, and color accents. The mapping cannot modify recipe eligibility, duration, score, target bands, or inventory behavior. A missing/invalid override falls back to the default mode and logs once.

The current `BrewProfile.presentation.particle` parser accepts `steam`, `foam`, `bubble`, `glint`, and `mote`. Do not silently depend on new strings because a renderer happens to recognize a substring. If extending that data contract, change parser, validation, synchronization, docs, and fallback tests together. Prefer the existing signature and a client visual definition for richer motifs. [S29]

Model/generator changes must preserve facing consistency, block/item models, loot/recovery behavior, collision/outline shapes, and resource-pack replacement. Maintain recognizable item silhouettes in hotbar, inventory, hand, item frame, and workstation renders. Leave project branding icons alone unless a workstation change makes them materially inaccurate.

## 14. Performance targets and measurement

The following are **proposed engineering budgets**, not measured results or promises for every modpack:

| Area | Initial target / guardrail |
| --- | --- |
| Continuous observer networking | About 2 Hz per active station; target ≤192 bytes of compact visual payload per sample, excluding transport framing. Send item descriptors on changes, not every sample. Measure actual total traffic. |
| Idle networking | No periodic visual packets for an unchanged idle station. Inventory/status mutations still synchronize. |
| One-shot effects | Bounded/coalesced; never scale with rendered frames or repeated snapshot receipt. Flooded decoration may be dropped without dropping gameplay input. |
| Rendering | Static body remains chunk-baked; no new entity per fragment/bottle/drop; no mesh creation, JSON parsing, recipe traversal, or NBT decoding in the render loop. |
| Cache growth | Proportional to tracked stations; unloaded stations removed. No retained world references across disconnect. |
| Client benchmark | Compare 0, 1, 8, 32 active stations and 128 idle/loaded racks against the same baseline scene. Provisional goal: ≤1 ms added p95 CPU render cost for 32 visible active stations on the recorded reference system, with no >2 ms recurring animation-related spikes. If missed, reduce detail/cost and remeasure. |
| Server benchmark | Existing session throughput remains stable; presentation snapshot construction has a bounded cost. Treat >5% p95 regression in the controlled session benchmark as an investigation trigger, not an acceptable automatic tradeoff. |

Record CPU/GPU, JVM, heap, driver, Minecraft/Forge, renderer/shader versions, resource packs, resolution/GUI scale, view distance, particle options, station count, and camera position. Warm the scene and measure a repeatable 60-second sample. Report median/p95/p99 frame time, server tick/session timing, allocations, particle counts, sound voices, and network bytes. A headless server test does not establish client FPS.

Use detail reduction in this order: distant decorative motes → distant fragment count → secondary liquid overlays → distant label rendering. Retain target gauges, primary material state, active controller feedback, real item displays needed to understand inventory, and recovery/output state. Do not hide essential information to meet a budget.

## 15. Implementation work packages and dependencies

P0 establishes trustworthy state and feedback. P1 delivers the full visual/interaction overhaul. P2 completes environmental polish and release validation. **All packages below belong to the requested overhaul**; P2 is not shorthand for silently leaving them unfinished.

| Package | Priority / depends on | Concrete implementation surface | Acceptance / handoff evidence |
| --- | --- | --- | --- |
| VIS-01 Baseline and fixtures | P0 / none | Current-head comparison; `docs/TESTING.md`; smoke `ClientSmokeTest`, `VisualChecks`, `StationGallery`; new state gallery manifest | Fresh captures distinguish current behavior from the old showcase. Record reproducible scenarios for F01–F18 and preserve existing test results as historical. |
| VIS-02 Presentation state | P0 / VIS-01 | `StationVisual`, `StationView`, `StationBlockEntity`, `SimHandles`, simulation getters, `SessionManager`, relevant packets | Explicit collected volume/operation/paused state; sealed state reconstructs; sparse continuous sync and once-only events; no scoring/economy changes. |
| VIS-03 Shared policy and semantic UI | P0 / VIS-01 | `ClientConfig`, `Ui`, `GameScreen`, screen/BER policy adapters | Intensity zero works everywhere; modal input is neutralized; all semantic markers honor palettes/large mode; compact geometry is shared by rendering/input. |
| VIS-04 Model anchors and layers | P0 / VIS-01 | `tools/generate_data.py`, block/item models, `StationShape`, `RenderBits`, renderers/particles | Four facings align; no liquid outside kettle; cold/lit appearance differs; alembic material layers separated; no doubled pestle. |
| VIS-05 Mortar overhaul | P1 / VIS-02–04 | `PreparationScreen`, `MortarRenderer`, material definitions, preparation display data, art/audio | Five material families, accurate pestle work/fold, target texture preview, correct endpoint/overwork cues; acceptance in §7. |
| VIS-06 Kettle overhaul | P1 / VIS-02–04 | `KettleBrewScreen`, `KettleRenderer`, shared signature definitions, addition events, profile resources | Continuous paddle, meaningful intermediate tint, eleven signatures, usable timed tray and heat trend; acceptance in §8. |
| VIS-07 Alembic overhaul | P1 / VIS-02–04 | `DistillationScreen`, `AlembicScreen`, `AlembicRenderer`, distillation getters, passive operation views | Collection amount and run progress separated; routed droplets; distinct reclamation; acceptance in §9. |
| VIS-08 Loading and recipe workflow | P1 / VIS-03, VIS-04 | Station screens/menus, `KettlePlanScreen`, `BatchStaging` preview presentation, `BatchPreviewS2C`, JEI transfer UI | Slot roles, exact costs, primary action hierarchy, compact list/detail pages, stale preview recovery; existing transactions remain authoritative. |
| VIS-09 Guidance and results | P1 / VIS-03, VIS-05–08 | `NotebookScreen`, `ResultScreen`, `PotionTooltips`, `PinnedChecklistHud`, `StationNotices` | Same visuals teach and explain play; result actionable at a glance; HUD icon bounds do not overlap; all content reachable at 320×240. |
| VIS-10 Storage presentation | P1 / VIS-03, VIS-04, VIS-08 | Rack/carrier screens/menus, `ApothecaryRackRenderer`, item overlays, labels | Empty reservations visible and clearly non-real; world/GUI slot arrangement matches; exact metadata preserved; transfer acknowledgment truthful. |
| VIS-11 Spatial audio and effect budgets | P2 / VIS-02, VIS-05–10 | `BrewingSounds`, new sound/effect managers, `StationParticles`, `ClientSetup`, sounds/generators | Bounded voices/particles, correct rotated anchors, accepted-action audio, no double completion, clean stop/reload/unload. |
| VIS-12 Integration and resource lifecycle | P2 / VIS-08–11 | JEI classes, `BrewingJadePlugin`, data/resource reload handlers, fallback art, dedicated-server classloading | Native/optional items still render; no duplicate HUD; recipe/schema reload safe; no client class on dedicated-server path. |
| VIS-13 Validation and optimization | P2 / VIS-01–12 | Existing tests plus targeted new tests, benchmark scenes, real two-client run, locale/motion review | Evidence matrix in §16 complete; regressions fixed; remaining unverified environments explicitly listed. |
| VIS-14 Documentation and release evidence | P2 / VIS-13 | README/config/datapack/API docs where changed, changelog, asset manifest, fresh screenshots/video manifest | Describe final implemented behavior, exact tested builds, new settings, known limitations, and current-head evidence. |

Implementation sequence: build the state/policy/anchor foundation, then complete **one productive kettle batch** from loading through result as a vertical slice. Use it to settle shared components. Complete mortar and alembic, then storage/guidance, then spatial effects and integration validation. Do not roll out a new panel style independently across screens before the compact layout and semantics are settled.

Avoid a simulation rewrite, mandatory animation library, new rendering framework, or a platform port. If a dependency becomes necessary, justify it with a concrete missing capability and measure its cost. The reviewed code already provides the required foundation.

## 16. Validation scenarios

### 16.1 Meaningful automated regressions

Extend existing suites rather than creating tests that merely assert constants equal themselves:

- **Presentation adapter invariants:** total fraction may rise while collected fraction remains constant; zero condensation never adds receiver fill; reclamation never masquerades as purification; sealed views preserve saved material state.
- **Event/reconciliation behavior:** repeated or stale snapshots/events, late joins, controller reopen, rollback/replay, and packet reordering do not duplicate effects; a changed station incarnation rejects old effects.
- **Resource ownership:** an animation cannot create/move/delete items; before/after exact stacks and ledger ownership match existing behavior through prepare, collect, return, seal, break, and recovery install.
- **Geometry:** sampled vertices/particle anchors remain inside authored vessel envelopes, four facings transform consistently, static and dynamic pestles are not both emitted.
- **UI interaction:** use the real screen/widget tree and simulated input to assert important bounds, focus reachability, correct slot click geometry, held-input release, and visibility of simultaneous critical cues. Include the pinned HUD’s 16-pixel icons with 18-pixel row spacing.
- **Presentation settings:** test all-zero decoration and reduced-motion combinations; changing visual settings cannot change deterministic simulation results for the same input trace.
- **Cleanup:** sound/cache/budget managers release tracked state on unload/disconnect/reload and do not retain old world objects.

Retain the existing preparation, kettle, distillation, and quality JUnit tests. Expand relevant `PauseGameTests`, `ReplayGameTests`, `LifecycleGameTests`, `ContentionGameTests`, `Workflow110GameTests`, `ApothecaryRackGameTests`, and `PerformanceGameTests` for changed behavior. These test files exist in the repository; fetch/read their current versions before editing them. Add client visual cases to the existing smoke framework and check actual pixels/video as well as boolean assertions.

### 16.2 Practical scenario matrix

| ID | Setup and actions | Pass condition |
| --- | --- | --- |
| V01 First productive brew | Fresh player, Notebook only; load water/nether wart/sugar/fuel/paddle, select Swiftness, use raw reagents, brew and collect | Every slot/action is identifiable; costs/inputs/outputs match; grade and improvement explanation are truthful; raw baseline is not portrayed as a broken choice. |
| V02 Preparation families | One profiled reagent from each of crystalline/fibrous/soft/brittle/liquid; normal work, no work, excess pressure, clump/fold, endpoint and overwork | Material/pose/cues correspond to state; no fake work bursts; finish availability and score remain unchanged. |
| V03 Reaction gallery | All eleven signature enum values at low/mid/high trouble with independently varied heat; accepted, refused, late/automatic additions | Distinct readable motifs; correct causes/instructions; no misleading success feedback or heat-to-trouble inference. |
| V04 Thermal inertia | Raise draft, lower it before overshoot, compare burner trend and liquid heat; repeat through cold draft/flare/surge | The player can see momentum and real response; no random extra penalties; controls/hitboxes stay fixed. |
| V05 Long routes and mods | Start from water and an existing potion; raw/prepared mixes; 1, 3, 4, 6 servings; alternatives; explicit carrier; optional potion recipes | Intermediate displays and costs are route-correct; no water-color reset for a non-water input; exact metadata/quality policy preserved. |
| V06 Alembic routing | Purify default groups; route entire run to waste, entire run to collect, then correct cuts; zero/low/excess cooling | Collected level matches actual accumulated collection, not total run; guidance follows condensate; outputs/score follow server. |
| V07 Passive operations | Reclamation and eligible quick finishing; insufficient fuel/output capacity; seal/resume where supported | Distinct operation appearance and status; no fictitious receiver/purity; no unreachable reserved output. |
| V08 Interruptions | Help, Access, focus loss, Escape, death, disconnect, walking out of range, chunk unload, restart, `/reload`, dimension change | Existing pause/seal/recovery semantics hold; no motion/audio claiming continued work while paused; no lost/duplicated resources. |
| V09 Recovery Batch | Break active/sealed stations; restore to correct/incorrect/nonempty station; temporarily remove a required provider | One preserved job; honest recovery reason; unknown data not discarded; no repeated completion on restoration. |
| V10 Two real clients | Controller and observer; all four facings; swap controller; late join; 0/100/200 ms added RTT with jitter and limited loss; healthy and reduced TPS | Same operation/material state within sync tolerance; prompt cues remain fair; no double effects, stale controller loop, teleported paddle, or client-created result. |
| V11 Compact/large layouts | GUI scales 1–4 and Auto; 320×240, 427×320, 640×360, 640×480 logical sizes; 16:9, 16:10, ultrawide and resized windows | Essential content/actions reachable, text readable, no clipping or slot/input mismatch; all overlays/modal flows work after resize. |
| V12 Accessibility | Keyboard only; mouse only where existing control paths support it; toggles/reduced input; large markers; three palettes; no sound; zero particles/intensity; reduced motion; narrator | Same available score for equivalent inputs; current goal/action always understandable; no critical cue only in color/audio/motion/hover. |
| V13 Locales/labels | Five maintained locales, long potion names, long player labels, Unicode, font resource pack, pseudo-localization | Correct glyphs/order/placeholders, measured truncation with full accessible text; no missing controls or overlapping icons. |
| V14 Logistics | Six different exact potion stacks; empty reservations; mismatching quality/label; full carrier/player inventory; simulated automation; actual hopper transfer | Correct labels/ghosts/counts; no misleading grade; simulated operations create no effects/mutations; excess output stays visible. |
| V15 Renderer/resources | Forge baseline Fast/Fancy/Fabulous; a maintained Forge performance-renderer setup; a maintained shader setup if claimed; texture/font packs; resource reload mid-session | No opaque-part sorting errors, invisible liquid, stale sprites, broken labels, global state leaks, or missing required cues. Record exact versions rather than asserting universal support. |
| V16 Dense workshop | 1/8/32 working stations; 128 idle/loaded racks; repeated camera turns and entering/leaving range | Measured budgets, bounded particles/voices/cache, no escalating memory, stable idle cost; controller feedback preserved under load. |
| V17 Result/collection/use | Fine/crude/masterwork examples, metadata-only potion, instant/mixed effects, interrupted session, practice; collect with partial space; drink at low/baseline/high quality with modified duration and cancellation | Right result/cue type, genuine benefits, accurate remaining inventory, no celebration for an uncommitted or sealed job; first-person drink timing matches the composed use duration without double scaling. |
| V18 Reload and fallback | Missing optional mod, unknown presentation preset, replaced art, invalid client mapping, data generation change while choosing a plan | Safe fallback and explicit state; no crash or stale preview commit; presentation changes do not rewrite old job metadata. |

### 16.3 Commands and test environments

On a complete checkout with the required Java/Gradle dependencies, start with the repository’s current commands:

```sh
./gradlew build
./gradlew runGameTestServer
python3 tools/generate_data.py --check
python3 tools/generate_textures.py --check
python3 tools/generate_sounds.py --check
python3 tools/check_release.py --jar build/libs/immersive-brewing-1.1.0.jar
```

Adjust the jar path to the actual implemented version. Do not edit the version merely to satisfy this example. Generator requirements include Pillow, NumPy, and the expected ffmpeg/libvorbis encoder; a missing tool is a blocked check, not a pass.

The existing client matrix is useful:

```sh
tools/run_smoke_matrix.sh default keyboard accessible colorblind gui1 gui2 gui3 gui4
tools/run_smoke_matrix.sh visual
```

**Portability caveat confirmed in source:** that script currently refers to `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh`. If unavailable, give the runner an explicit portable Gradle fallback/configuration while preserving its result collection. Do not silently skip the client matrix. The underlying supported entry point is `./gradlew runClient -PsmokeTest`, with existing `-Pvariant`, `-PguiScale`, and window options. Run clients serially against their own test worlds; the current runner warns against concurrent project compilation.

Provision optional fixtures according to README and `tools/compat-fixtures.json`, then run the existing compatibility matrix and selected client combinations. Do not redistribute third-party jars or claim optional builds were tested when fixtures were missing. For Forge rendering/shader combinations, select current compatible 1.20.1 builds at implementation time and record exact versions; this handoff does not certify a particular future combination.

### 16.4 Human observation and evidence

Reuse the repository’s pending formative trial instead of treating screenshots as usability proof. Observe 8–12 participants across newcomers, experienced modded players, and relevant accessibility needs where available. Use the same task script before/after when possible. Record first-brew success, missed cues, mistaken actions, explanation of thermal inertia/preparation/purification, setup interactions, and willingness to repeat.

Keep the existing provisional goals: at least 80% complete a first productive brew using the Notebook without external instructions; at least 80% can explain preparation and purification; investigate every lost item, unrecoverable job, critical inaccessible action, and false unsupported result. Compare repeat-batch setup to a measured baseline rather than promising a percentage from this source review. If participants are not available, mark the human gate pending and describe the automated/author review separately. [S03]

Capture the real engine at exact states: idle/loaded; count-in; healthy/troubled; imminent addition; accepted/refused/automatic addition; endpoint; clear/hazy condensate; waste versus collection; paused/sealed/recovery; output ready. Include four-facing world views and a dense workshop. Record at least one normal-motion and one reduced-motion clip per minigame, plus observer footage. A still image cannot prove smoothness, audio ownership, or reduced-motion behavior.

## 17. Definition of done

- [ ] The handoff’s required P0, P1, and P2 packages are implemented; any blocked package keeps implementation completion open. No placeholder renderer or decorative fake state is presented as complete.
- [ ] Mortar, kettle, and alembic retain distinct hands-on mechanics and now show the corresponding materials, tools, transformations, and decisions coherently in GUI and world views.
- [ ] Every lifecycle state, including count-in, Help/Access pause, seal, recovery, passive operation, and output ready, has a truthful presentation and clean transition.
- [ ] All eleven kettle signatures, five preparation modes, and five condensate states have authored readable behavior and fallback appearances.
- [ ] Liquid geometry, receiver fill, world-facing particle anchors, static pestle, unlit vent appearance, pinned-HUD overlap, and inconsistent intensity handling identified in this review are addressed.
- [ ] Loading, selecting/staging a recipe, minigame play, result inspection, collection, labeling, rack storage, carrier use, and repeat/practice workflows form one coherent experience.
- [ ] At 320×240 logical resolution, core actions, critical cues, input targets, and focus/narration remain usable in all maintained locales and accessibility configurations.
- [ ] Decoration can be reduced to zero without changing scoring, recipe costs, output, or access to required information.
- [ ] Server authority, exact metadata, recipe/provider behavior, automation boundaries, recovery/migration semantics, and original attribution are preserved.
- [ ] Effects are deduplicated across prediction, snapshots, observers, result screens, reopen/rejoin, and reload; caches and sound instances clean up correctly.
- [ ] Resource generation, appropriate unit/GameTests/client checks, four-facing geometry review, dedicated-server loading, and selected integration tests pass on the implemented commit.
- [ ] Dense-workshop profiling includes client rendering as well as server timing; actual measurements and reference hardware are recorded, with regressions resolved or explicitly explained.
- [ ] Fresh screenshot/video manifests name the implemented commit and tested settings; old 1.0.2 screenshots are not used as evidence of the new implementation.
- [ ] Documentation explains new presentation controls and final behavior; any pending human, language, audio, or untested-renderer gate is stated plainly rather than recorded as passed.

The coding agent’s completion report should list implemented packages, concrete before/after behavior, changed file groups, commands and observed results, links to captures/measurements, and remaining limitations. It should distinguish “implemented,” “automatically verified,” “visually reviewed,” and “tested by participants.”

## 18. Source map

All repository references below resolve to the reviewed commit. Read the current checkout before editing; prefer implementation over stale inventory documentation. In particular, the current repository includes the Apothecary Rack and 1.1.0 workflow additions even where older generated summaries omit them.

| Area | Primary files to read/change |
| --- | --- |
| Lifecycle/view | `J/batch/JobLedger.java`, `JobState.java`, `Operation.java`; `J/blockentity/StationBlockEntity.java`, `StationView.java`, `StationVisual.java`, `StationResult.java`; all three station block entities |
| Simulation/prediction | `J/session/SessionManager.java`, `SimHandles.java`; `J/client/ClientSession.java`; `J/minigame/{preparation,brewing,distillation}/`; `J/network/ModNetwork.java`, relevant packets |
| GUI foundation | `J/client/screen/Ui.java`, `GameScreen.java`, `StationScreen.java`; `J/config/ClientConfig.java`; `J/client/Keys.java` |
| Crafting screens | `PreparationScreen.java`, `KettleBrewScreen.java`, `DistillationScreen.java`, `MortarScreen.java`, `KettleScreen.java`, `AlembicScreen.java`, `KettlePlanScreen.java`; corresponding `J/menu/` classes |
| Guidance/results | `J/client/screen/NotebookScreen.java`, `ResultScreen.java`, `LabelScreen.java`; `J/client/PotionTooltips.java`, `StationNotices.java`; `J/client/journal/PinnedChecklistHud.java` |
| Finished-item use | `J/client/ClientDrinkTiming.java`, `J/mixin/ItemInHandRendererMixin.java`; existing potion use-duration policy/event handling |
| Rendering/effects | `J/client/render/{KettleRenderer,MortarRenderer,AlembicRenderer,ApothecaryRackRenderer,RenderBits}.java`; `J/client/particle/StationParticles.java`; `J/client/sound/BrewingSounds.java`; `J/client/ClientSetup.java` |
| Storage/staging | `J/blockentity/BatchStaging.java`, `ApothecaryRackBlockEntity.java`; rack/carrier menus/screens; `J/item/CarrierContents.java`, `BatchCarrierItem.java`, `KitData.java` |
| Profiles/resources | `J/data/BrewProfile.java`, `ReagentProfile.java`, `BrewingDefinitions.java`; `D/immersive_brewing/{brew_profiles,reagent_profiles}/`; `A/models/`, `blockstates/`, `textures/`, `lang/`, `sounds.json` |
| Asset source | `art/brewing_art.py`, `art/brewing_audio.py`, `art/README.md`; `tools/generate_data.py`, `generate_textures.py`, `generate_sounds.py` |
| Integration | `J/client/compat/jei/`; `J/compat/jade/BrewingJadePlugin.java`; existing policy/adapter services; do not duplicate recipe rules in presentation code |
| Validation | `src/test/`, `src/gameTest/`, `src/smokeTest/`; `tools/run_smoke_matrix.sh`, `run_compat_matrix.py`, `check_release.py`; `docs/TESTING.md`, `docs/reports/1.1.0-implementation.md` |

### Pinned evidence links

The linked identifiers used throughout this document refer to the exact inspected files; external Forge links describe implementation APIs rather than proving a particular in-game appearance.

- [S01] — `README.md`
- [S02] — `docs/TESTING.md`
- [S03] — `docs/reports/1.1.0-implementation.md`
- [S04] — `J/blockentity/StationVisual.java`
- [S05] — `J/session/SimHandles.java`
- [S06] — `J/client/render/KettleRenderer.java`
- [S07] — `J/client/render/MortarRenderer.java`
- [S08] — `J/client/render/AlembicRenderer.java`
- [S09] — `J/client/particle/StationParticles.java`
- [S10] — `tools/generate_data.py`
- [S11] — `J/client/screen/Ui.java`
- [S12] — `J/client/screen/GameScreen.java`
- [S13] — `J/client/screen/KettleBrewScreen.java`
- [S14] — `J/client/screen/PreparationScreen.java`
- [S15] — `J/client/screen/DistillationScreen.java`
- [S16] — `J/client/screen/KettlePlanScreen.java`
- [S17] — `J/client/screen/NotebookScreen.java`
- [S18] — `J/client/sound/BrewingSounds.java`
- [S19] — `J/config/ClientConfig.java`
- [S20] — `J/client/journal/PinnedChecklistHud.java`
- [S21] — `J/client/render/ApothecaryRackRenderer.java`
- [S22] — `J/blockentity/StationBlockEntity.java`
- [S23] — `J/session/SessionManager.java`
- [S24] — `J/client/ClientSession.java`
- [S25] — `J/minigame/distillation/DistillationSimulation.java`
- [S26] — `J/minigame/brewing/Signature.java`
- [S27] — `J/minigame/brewing/KettleSimulation.java`
- [S28] — `J/data/ReagentProfile.java`
- [S29] — `J/data/BrewProfile.java`
- [S30] — `art/brewing_art.py`
- [S31] — `art/brewing_audio.py`
- [S32] — `J/registry/ModBlocks.java`
- [S33] — `J/client/screen/KettleScreen.java`
- [S34] — `J/client/screen/ResultScreen.java`
- [S35] — `J/client/PotionTooltips.java`
- [S36] — `J/compat/jade/BrewingJadePlugin.java`
- [S37] — `J/client/compat/jei/BrewingCategory.java`
- [S38] — `docs/screenshots/manifest.md`
- [S39] — `J/client/screen/ApothecaryRackScreen.java`
- [S40] — `J/client/screen/BatchCarrierScreen.java`
- [S41] — `J/client/ClientDrinkTiming.java`
- [S42] — `J/mixin/ItemInHandRendererMixin.java`

[S01]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/README.md
[S02]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/docs/TESTING.md
[S03]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/docs/reports/1.1.0-implementation.md
[S04]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/blockentity/StationVisual.java
[S05]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/session/SimHandles.java
[S06]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/render/KettleRenderer.java
[S07]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/render/MortarRenderer.java
[S08]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/render/AlembicRenderer.java
[S09]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/particle/StationParticles.java
[S10]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/tools/generate_data.py
[S11]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/Ui.java
[S12]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/GameScreen.java
[S13]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/KettleBrewScreen.java
[S14]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/PreparationScreen.java
[S15]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/DistillationScreen.java
[S16]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/KettlePlanScreen.java
[S17]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/NotebookScreen.java
[S18]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/sound/BrewingSounds.java
[S19]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/config/ClientConfig.java
[S20]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/journal/PinnedChecklistHud.java
[S21]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/render/ApothecaryRackRenderer.java
[S22]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/blockentity/StationBlockEntity.java
[S23]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/session/SessionManager.java
[S24]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/ClientSession.java
[S25]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/minigame/distillation/DistillationSimulation.java
[S26]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/minigame/brewing/Signature.java
[S27]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/minigame/brewing/KettleSimulation.java
[S28]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/data/ReagentProfile.java
[S29]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/data/BrewProfile.java
[S30]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/art/brewing_art.py
[S31]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/art/brewing_audio.py
[S32]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/registry/ModBlocks.java
[S33]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/KettleScreen.java
[S34]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/ResultScreen.java
[S35]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/PotionTooltips.java
[S36]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/compat/jade/BrewingJadePlugin.java
[S37]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/compat/jei/BrewingCategory.java
[S38]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/docs/screenshots/manifest.md
[S39]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/ApothecaryRackScreen.java
[S40]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen/BatchCarrierScreen.java
[S41]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/ClientDrinkTiming.java
[S42]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/mixin/ItemInHandRendererMixin.java
