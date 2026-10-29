# Changelog

## 1.1.0 — Unreleased development

This section describes the current development tree; it is not a release announcement.

- Reworked Kettle plan discovery into an incremental, server-thread search with search-before-paging, stable direct
  plan replay and a server-synced compatibility report. The Notebook distinguishes supported effect scaling,
  metadata-only recipes, exclusions, unsupported recipes, contextual searches and budget-limited alternatives. An
  additive API-v1 catalog exposes the same incomplete/budget state and exact-key resolution without changing the
  legacy 512-route query; operator explain commands show route adapters, versions and effect-policy decisions.
- Added transaction-safe batch staging from the open Kettle, the player's ordinary inventory and one explicitly
  selected Batch Carrier. Preview never mutates an inventory; commit requires the same inventory revision.
- Added a six-slot Apothecary Rack with exact-stack storage, reservation ghosts, short labels, comparator output,
  predictable capability behavior and one-action carrier packing/restocking.
- Expanded the Batch Carrier with a selected slot, named loadout, label color/emblem, missing-template preview,
  optional advance-on-empty behavior and a remappable action that moves the selected real stack to a safe hotbar slot.
- Added server-validated quick finishing for eligible potion-to-splash and splash-to-lingering container changes.
  It preserves the complete potion stack data, snapshots its duration and does not create a new score or milestone.
- Expanded the Brewer's Notebook with raw-first onboarding, dynamic loaded crafting diagrams, four staged Kettle
  lessons, server support explanations, explicit favorite replacement, pinned ingredient checklists and a bounded
  32-batch journal with comparable-attempt and assistance-aware summaries. Favourite practice re-resolves the exact
  route/profile on the server with a fresh seed before opening a client-only session; practice produces no items or
  milestones.
- Added in-game control/accessibility settings, no-chord input, textual heat momentum and next-action priority,
  focused narration, server-approved assistance display, profile accents/particles and the documented station-state
  legend. Accessibility options retain the full scoring range.
- Added Alembic operation previews and clearer guidance around yield, quality caps, lineage attempts, returned
  containers and reclamation.
- Player guide data is now version 2. Known version-1 data gains journal/filter defaults; unknown newer data is left
  untouched. Job ledgers are schema 2; schema-1 ledgers remain readable, while newer/unknown or missing-item ledgers
  are held with their raw payload preserved instead of being rewritten.
- The first-person drinking animation now uses the actual client-side Forge-composed use duration observed at use
  start. Compatibility with other drink-speed mods still requires runtime matrix testing; this changelog makes no
  cross-mod validation claim.

Explicitly deferred from 1.1.0 are the roadmap's P2/P3 systems: direct drinking from a carrier, formula cards, reagent
jars, unattended Kettle production, additional Alembic salvage economy, scripting bridges, physical/VR brewing,
workshop cosmetic variants, native blending/coatings/herbalism/fermentation/solvents, village commissions,
simultaneous cooperative brewing and other mod loaders.

## 1.0.2

- Added Spanish, Brazilian Portuguese, Russian, and Simplified Chinese translations.

## 1.0.1

- Resumed sessions at all three stations advance after the count-in without waiting through their previously elapsed time.
- **Apothecary's Mortar**: recovery and resume check output capacity; jobs held by a full shelf (`no_room`) become
  resumable when space opens, preserving their ingredients and earned extraction.
- **Alembic**: purified ordinary potions share one lineage per batch, so the output bottles can be brewed together.
- **Brewer's Kettle**: the paddle stays locked during count-in and brewing, including resumed count-ins, so another
  player cannot remove it to bypass wear; sealed jobs still allow paddle maintenance.

## 1.0.0

First release, for Minecraft 1.20.1 and Forge 47.

- **Apothecary's Mortar**: reagent preparation into up to four Prepared Reagent Kits per session, five textures of work
  (crystalline, fibrous, soft, brittle, liquid), extraction scoring.
- **Brewer's Kettle**: one to six servings in one hands-on reaction (draft, stirring, timed additions, ten reaction
  signatures, seeded disturbances with warnings), plans that chain from any potion and charge only the remaining
  steps, exact reagent, fuel and remainder accounting, quality from extraction and control.
- **Alembic**: purification of three identical potions into two (six into four) up to quality 70, once per potion
  line; passive reclamation into Spent Alchemical Salt and glass bottles.
- Batch Carrier, Brewer's Notebook (guide, plan search, favourites, last-result explanation, practice), Recovery Batch,
  wooden and iron brewing paddles.
- Quality changes finite durations of policy-listed effects (0.8× to 1.2×) and drinking time; 50 is exactly the
  ordinary potion; drinking, splash, lingering clouds, tipped-arrow crafting, arrows and dispensers all apply it once.
- Sessions seal on every interruption and resume exactly; broken stations package their job into one Recovery Batch;
  missing data holds a job instead of deleting it.
- Brewing stand modes: coexist (default), replace covered transformations, disabled.
- Datapack-driven plans, reagent and brew profiles, effect policies and exclusions with atomic reload; a small public
  API (adapters, effect-quality handlers, queries, events).
- JEI and Jade support; Immersive Smithing (smithed iron paddle), Immersive Tanning (strapped carrier), Immersive
  Enchanting and Immersive Jewelry (coexistence) integrations, all optional.
- Accessibility: remappable keys, hold or toggle alternatives, high-contrast and colour-blind palettes, reduced motion,
  written cues, numeric display, server-approved timing and band assistance.
