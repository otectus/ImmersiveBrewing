# Immersive Brewing 1.1.0 — Recommendations and Development Roadmap

**Proposed theme:** The Apothecary's Workshop  
**Review date:** October 28, 2026  
**Repository:** [otectus/ImmersiveBrewing](https://github.com/otectus/ImmersiveBrewing)  
**Reviewed baseline:** main at [53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48](https://github.com/otectus/ImmersiveBrewing/commit/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48), declaring version 1.0.2; Minecraft 1.20.1, Forge 47.4.23, Java 17. [R1]

## 1. Recommended direction

Make 1.1.0 the release that turns an accomplished brewing minigame into a complete, comfortable apothecary workflow.

The central experience should be:

1. Decide what an expedition or workshop needs.
2. Find a supported route and see the exact cost.
3. Prepare ingredients when the extra craftsmanship is worthwhile.
4. Brew through clear, responsive, enjoyable controls.
5. Understand what went well and what the resulting potion actually does.
6. Label, display, transport, and use the batch without excessive inventory work.
7. Repeat a familiar recipe with fewer setup steps, while retaining the satisfying parts of brewing.

The strongest opportunities are **recipe discoverability, repeat-batch convenience, teaching and feedback, physical storage, and interoperability that preserves the value of craftsmanship**. A large catalog of new status effects is less urgent: the mod already supports substantial potion expansion through other mods.

A focused 1.1.0 should deliver those improvements, one distinctive new workshop furnishing—the **Apothecary Rack**—and a modest expansion of the Notebook into a practical brewing journal. Broader alchemy systems belong in clearly bounded optional modules or later releases.

### Recommended release boundary

| Priority | Meaning | Recommended contents |
| --- | --- | --- |
| **P0 — release gate** | Resolve or verify before calling 1.1.0 ready | Recipe-list completeness; real-player control trials; multiplayer latency trials; save migration and material accounting; preservation of existing potion paths; reproducible core build |
| **P1 — core 1.1.0** | The intended release experience | Guided first batch; improved plan search and previews; Notebook history and repeat preparation; quick container finishing; Apothecary Rack; clearer quality and Alembic tradeoffs; presentation and accessibility polish |
| **P2 — optional 1.1.x** | Ship only after core acceptance criteria pass | Reagent jars; shareable formula cards; selected new mod adapters; configurable basic unattended production; extended batch labeling and cosmetic variants |
| **P3 — future expansion** | Preserve a design direction without committing it to 1.1.0 | Native blending, coatings, herbalism, fermentation, solvents, multidose flasks, incense, village commissions, simultaneous cooperative brewing, other loaders |

These priorities are recommendations, not findings that every listed feature is currently broken. Effort ratings below are relative: **S** is localized work, **M** crosses several existing systems, and **L** adds a substantial interaction, persistence, or compatibility contract. They are not calendar estimates.

### Five decisions to make explicit

- Keep the existing potion system authoritative for the core workshop. New transformations need a real registered recipe or an explicitly separate extension system.
- Keep raw reagents usable. Preparing every ingredient must remain a choice, not an entrance fee for ordinary brewing.
- Keep accessibility assistance separate from unattended production. Players using accessible controls should retain access to the same craftsmanship grades.
- Preserve the current bounded quality model until human trials demonstrate a reason to change it.
- Retain Minecraft 1.20.1 / Forge as the 1.1.0 target. A simultaneous loader or Minecraft-version port would multiply the validation work.

## 2. Review scope and evidence

This report combines a static review of the repository's current documentation, build configuration, recipe planner, station implementation, quality model, screens, carrier, persistence interfaces, networking, and representative simulations with research into other mods' author-maintained pages, documentation, and selected source code.

The repository tree and recent history were inspected. The GitHub queries returned no issues, pull requests, or GitHub Releases at review time; this does not establish whether feedback or releases exist on other platforms. The recommendations therefore do not pretend to represent collected player complaints.

**No new Minecraft client, dedicated server, or Gradle test run was performed for this review.** Test counts and past results below are attributed to the repository's recorded evidence. Visual recommendations are design proposals, not claims based on hands-on animation review. Compatibility candidates are not certified integrations.

The most useful existing evidence is unusually candid: the testing document records substantial automated coverage and also identifies human enjoyment, actual network timing, other mods' pipes, resource packs, and animation in motion as unfinished validation. That distinction should remain visible in the 1.1.0 release process. [R3]

### What already exists and should be improved rather than reintroduced

| Existing capability | Implication for 1.1.0 |
| --- | --- |
| Mortar preparation with five texture families and up to four kits per session | Improve instruction, comparison, and repeat setup; do not pitch “ingredient preparation” as new |
| Kettle batches of one to six bottles, chained plans, heat, circulation, timed additions, reaction signatures, and seeded disturbances | Refine readability and pacing before adding more simultaneous controls |
| Alembic purification and passive reclamation | Explain yield and quality tradeoffs; expand utility selectively |
| Quality, brewer attribution, lineage, labels, notes, and purification limits | Extend presentation and workflows while preserving these semantics |
| Notebook guide, search, favorites, last-result explanation, and free practice | Add history, direct actions, and better discovery rather than a second guidebook |
| Batch Carrier with six exact potion-container stacks and station transfers | Add selection and loadout convenience without duplicating its storage function |
| Seal/resume, Recovery Batches, single-owner job ledgers, and held jobs when data is missing | Treat these as architectural strengths and enforce them for every new feature |
| Keyboard controls, remapping, hold/toggle options, contrast palettes, reduced motion, written cues, and server-approved assistance | Extend reach and usability; “add accessibility” is too imprecise |
| Hopper/pipe item handling, bottle water filling, comparators, JEI, Jade, datapacks, and a public API | Expand tested interoperability; basic automation and recipe-viewer integration are already present |
| Four potion-mod integrations and optional sister-mod integration | Improve compatibility depth before adding another long list of nominally supported mods |
| English, Spanish, Brazilian Portuguese, Russian, and Simplified Chinese language files | Maintain translation coverage for new features; also check layout and terminology in each language |

Sources: current README, changelog, architecture, and implementation files. [R2] [R4] [R5]

## 3. Findings that should shape the release

| Finding | Evidence and confidence | Recommended response |
| --- | --- | --- |
| **Large catalogs can hide routes before the player searches.** | Confirmed limits: PlanIndex caps catalog generation at 512; PlanListS2C carries at most 256 entries. KettlePlanScreen filters the received list. The test report records a potion-mod run where the transmitted Water list contained only one- and two-step plans. Tray-aware search mitigates this but does not give an unloaded recipe a reliable discovery path. [R3] [R6] | Query the full validated catalog before pagination; add targeted destination resolution and explicit incomplete-search states |
| **Notebook and JEI are built from client-validated infusion recipes.** | Confirmed implementation; contextual tray-only plans are a distinct path. This is a parity risk when server-only recipe configuration differs, not proof of an observed mismatch. [R7] | Share server-derived support and recipe identity; distinguish catalog recipes, contextual recipes, and unknown coverage |
| **The seventeenth favorite silently removes the oldest.** | PlayerGuide.MAX_FAVORITES is 16 and ADD_FAVORITE removes entry zero at capacity. [R8] | Keep a bounded limit but expose it and require an explicit replacement choice |
| **Result feedback exists, but the journal retains only the last result.** | ResultScreen already shows thermal, circulation, dosing, addition outcomes, and a tip; PlayerGuide stores lastResult. [R8] [R9] | Add bounded recent history and comparison; improve the existing explanation instead of rebuilding it |
| **Excellent raw-reagent brewing can already reach Fine quality.** | With raw extraction 50 and control 100, QualityMath yields Q88. [R10] | Teach the optional preparation route clearly and preserve this useful shortcut |
| **Purification trades total stock for a better individual dose.** | Default 3→2, cap Q70, and a purification attempt consumes a lineage allowance even when it does not improve quality. [R10] [R11] | Preview both the upside and opportunity cost before starting |
| **Pure container conversion can be disproportionately fussy.** | Kettle transformations of finished potions cannot raise quality and may lower it; ordinary stand pass-through preserves quality when contents remain equivalent. [R12] | Add a constrained quick-finishing path for verified container-only changes |
| **Authored brew presentation data is not fully used.** | Architecture explicitly says presentation.accent and presentation.particle are validated and synced but not drawn. [R4] [R13] | Complete the existing presentation contract before adding another visual customization format |
| **The drinking animation can disagree with the use duration.** | Documented accepted limitation: the base animation follows 32 ticks while graded use takes 28–36 ticks before other modifiers. [R4] | Revisit synchronization and test interaction with other drink-speed mods |
| **Quality compatibility has intentional boundaries.** | Foreign CustomPotionEffects remain exact; unknown potion items are metadata-only; API v1 custom handlers are consulted for drink/splash, not custom lingering/arrow paths. [R4] [R14] | Expose these distinctions to players and extend the API deliberately where needed |
| **Tipped-arrow quality depends on a recipe override.** | Another datapack replacing minecraft:tipped_arrow can remove quality inheritance; the mod detects and reports the conflict. [R4] [R15] | Provide an explicit integration contract and player-visible diagnostics; test replacement recipes |
| **Fluid conversion is a likely metadata-loss boundary.** | Inspected Create 1.20.1 development-source functions build fluid/bottle results from potion effects and bottle type, without carrying Immersive Brewing metadata. This is a static interoperability risk, not a live reproduction with a released Create jar. [M9] | Test drain→tank→spout and mixing paths before advertising support; preserve full provenance or clearly refuse unsupported transfers |
| **Runtime-optional integrations complicate the core build.** | README and build.gradle require local compile-only jars, including data-only potion integrations, even when their runtime flags are off. [R2] [R16] | Remove unused compile dependencies and isolate genuinely required integration APIs |
| **Automated success is not evidence of enjoyable play.** | Recorded 24 JUnit tests and 120 GameTests pass in the documented runs; human trials, real two-client latency, and animation-in-motion review remain open. [R3] | Make these explicit release gates, with measurable tasks and observations |

## 4. What similar mods suggest

The useful question is what each design makes enjoyable or convenient. Adopt the underlying lesson and adapt it to Immersive Brewing's own recipe, quality, and persistence rules.

The feature summaries below are from primary project sources. Cross-version examples are inspiration; they are not proposals to install incompatible builds into the current Forge target.

| Project | Verified design reference | Recommendation for Immersive Brewing | Boundary |
| --- | --- | --- | --- |
| **Potion Blender** [M1] | Combines potion effects through a cauldron; supports several output forms and applies diminishing duration returns for repeated effects | Make combination previews and conservation rules central to any future blending feature; prioritize a tested integration first | Its combined effects must not accidentally receive a second quality multiplier |
| **Dave's Potioneering** [M2] | Emphasizes potion stacking, efficient brewing, weapon coatings, milk bottles, and more convenient potion application | Treat carrying and using a potion as part of the brewing experience; test stack-size changes and reserve coatings as a distinct expansion | Do not change global stack limits or combat behavior just to imitate the feature list |
| **Extra Alchemy** [M3] | Potion bags, stackable vials, configurable potion rings, and heat-based brewing-stand fueling | Improve carrier selection, explain alternative heat choices, and make added effects individually configurable | The inspected project explicitly targets Fabric for modern versions; use it as design inspiration, not a Forge 1.20.1 dependency |
| **Hexerei** [M4] | Mixing Cauldron, herb storage jars, and a strongly furnished witchcraft setting | Give the workshop an attractive, useful storage vocabulary: visible bottles, labeled shelves, and ingredient jars | Keep the initial furnishing set small; a new biome or large worldgen system is unnecessary for 1.1.0 |
| **Rustic** [M5] | Combines alchemy, agriculture, decoration, beverages, and an in-game guidebook | Make the brewing room a place players want to build; reserve herbalism and agricultural supply chains for a later coherent expansion | Historical inspiration from the project's 1.12-era design |
| **Brewin' and Chewin'** [M6] | Keg-based fermentation and advancement-led onboarding | Give long processes a useful background role, and connect each progression milestone to a concrete next action | It is a food/fermentation addon; importing alcohol mechanics is not necessary |
| **Botania** [M7] | Multidose brew containers and incense-based delivery, with different resource costs and eligibility rules | Consider travel flasks and area support as distinct delivery formats with explicit dose budgets | Its Lexicon can describe development features; do not assume its custom brews fit vanilla potion APIs |
| **ImmersiveMC** [M8] | Direct in-world interaction with workstations, including brewing stands, alongside VR use | Prototype optional world-space loading, selection, and collection; keep an equivalent accessible screen | Full gesture/VR integration is a separate project, not a requirement for brewing immersion |
| **Create** [M9] | Official 1.20.1 source generates heated potion-mixing recipes and converts potion bottles to/from fluids | Support physical workshop logistics while auditing exact preservation at every item/fluid boundary | Fluid compatibility needs more than accepting water or moving item stacks |
| **Toil and Trouble** [M10] | Cauldron brewing, arrow dipping, configurable heating, data-driven recipes, and recipe-viewer support | Let physical preparation and bottle finishing feel natural; clearly show the loaded recipe's economy | The inspected project supports newer Fabric/NeoForge versions, not this 1.20.1 Forge baseline |
| **Shule's Potions** [M11] | Ingredient-driven cauldron alchemy with configurable ingredient properties and an expanded effect catalog | A future experimentation mode should make ingredient properties inspectable and combinations learnable | Keep such a mode distinct from the current promise that the registered brewing recipe determines the result |

**Synthesis:** three recurring strengths are worth adopting: useful physical workspaces, convenient potion handling, and understandable choices about ingredients or delivery. Immersive Brewing's existing manual craft and recovery systems provide its own identity; the missing work is connecting them into an effortless routine.

## 5. Core 1.1.0 recommendations

### IB-110-01 — Make every supported brew discoverable

**Priority:** P0 · **Effort:** L · **Depends on:** stable recipe identities and reload generation tracking

Replace the “generate, truncate, then search” experience with bounded, server-authoritative catalog queries.

Required behavior:

- Search by potion name, contained effect, ingredient, source mod, and drinkable/splash/lingering form. Use localized names for display and stable IDs for resolution.
- Filter by “can brew with loaded tray,” “can prepare from my inventory,” favorites, and quality treatment.
- Group alternative routes to the same destination rather than filling the first page with nearly identical permutations. Let players expand alternatives.
- Show meaningful route comparisons: ingredient counts, fuel applications, required starting potion, steps, and estimated active time. Do not invent a universal monetary value for ingredients.
- Resolve a selected destination directly, including long routes that never appeared in an early catalog page.
- Search and filter before applying the page-size limit. A 256-entry packet ceiling can remain a safety limit without becoming a discovery limit.
- Keep registry queries bounded and server-thread safe. Debounce requests, cap work per request/tick, cache by recipe/data/config generation, and return a continuation token or “search incomplete” state when a budget is reached.
- Treat an arbitrary custom brewing handler as potentially non-enumerable. Distinguish “no route found in this search” from “unsupported.” Preserve tray-context probing.
- Invalidate pages and selections on reload; retain the user's search text and tell them when the selected route changed.
- Extend the existing coverage report with separate counts for supported destinations, alternative routes, unresolved contextual steps, and omitted/budget-limited results.

**Acceptance criteria:** in the existing combined potion-mod fixture, a player starting with Water can find and select a supported long lingering Swiftness route without first knowing which ingredients to put in the tray. Search, Notebook, and JEI explain the same support status. Exhausting a search budget never displays a false claim of complete coverage.

### IB-110-02 — Teach a successful first brew in stages

**Priority:** P1 · **Effort:** M · **Depends on:** IB-110-01

The first lesson should demonstrate why the workshop is pleasant, without making the player master all three stations.

- Offer “First brew,” “Improve a brew,” and “Explore freely” entry points in the existing Notebook.
- Teach a simple brew with raw ingredients first; introduce the Mortar as the route toward higher quality and the Alembic as an optional rescue or concentration trade.
- Use the actual loaded crafting and brewing recipes for every diagram, ingredient checklist, and cost. The current Notebook already resolves the Kettle's crafting recipe dynamically; extend that approach to the other necessary items.
- Allow a short, optional guided practice that introduces heat, then circulation, then additions separately. Existing unrestricted practice remains available.
- Show the next meaningful action near the relevant control, rather than several paragraphs while the clock is running.
- Before the first productive job, explain Seal, Resume, and Recovery Batch in one compact panel.
- Add a recoverable teaching moment: deliberately drift outside a band in practice, recover, and see that one error does not ruin the entire batch.
- Link each advancement to a Notebook topic or actionable recipe. Avoid achievements based only on repeating dozens of identical brews.
- Keep tutorials skippable and replayable. Do not make practice produce items or become an ingredient-generation loop.
- Provide complete keyboard and mouse-only routes through the tutorial; account for changed key bindings.

**Acceptance criteria:** a new player can make a useful potion using only the in-game guide, explain how to pause safely, and understand that grinding and distillation are optional.

### IB-110-03 — Improve control readability, fairness, and pacing

**Priority:** P0/P1 · **Effort:** M–L · **Depends on:** real-player trials

Preserve the deterministic server simulation, seeded disturbances, and existing prediction/reconciliation architecture. Refine the player's understanding of it.

1. **Communicate momentum.** Show whether temperature is rising or falling and whether the burner remains hotter than the liquid. This teaches the existing two-stage heat inertia.
2. **Preview transitions.** Give a short, consistent cue before the next phase or addition, including an icon and text. The warning should explain what changes, not just announce danger.
3. **Coordinate disturbances.** Audit seeds for impossible combinations, contradictory instructions, and disturbances overlapping narrow addition windows. Add invariant tests as well as sampled seeds.
4. **Prioritize cues.** “Add now” should take precedence over a decorative reaction description. Avoid text churn that forces the player to reread the whole screen.
5. **Reduce repetitive inputs.** Surface the existing stir/pressure toggle options inside the help/settings flow. Make held mouse buttons or equivalent controls usable without requiring circular gestures.
6. **Review participation scoring.** The current simulation can withhold a phase's thermal/circulation credit when there was no input during or shortly before it. Test whether correct deliberate waiting is misread as nonparticipation; reward meaningful engagement without incentivizing pointless tapping. Do not simply delete anti-AFK protection.
7. **Respect interruptions.** Focus loss, opening help, and reconnecting must not leave a held input stuck. Resume should reveal the current phase and next action before the count-in ends.
8. **Protect the short loop.** Keep routine unassisted reactions within the current intended cap. Measure total interaction time including loading, preparation, results, and collection; optimizing only the simulation misses most repetitive friction.
9. **Keep challenge purposeful.** Special reactions can ask for different control patterns, but ordinary familiar potions should not acquire more mandatory controls merely because a new version exists.

**Acceptance criteria:** participants can explain why their temperature overshot, recognize an upcoming addition without sound or color, and improve after a failed attempt. Equivalent server-accepted input sequences produce identical results independent of rendering frame rate.

### IB-110-04 — Make the Notebook a practical brewing journal

**Priority:** P1 · **Effort:** M · **Depends on:** IB-110-01

Build on the current guide, favorites, practice, and last-result implementation.

- Store a bounded recent history, initially **32 compact result summaries per player**, with a visible clear-history action. Save summary metrics, recipe identity, timestamp, output identity, and one or two useful observations; do not retain entire input recordings by default.
- Compare a result with the player's previous attempt at the same recipe and batch size. Distinguish changed assistance settings so comparisons are understandable.
- Turn favorites into actionable entries: inspect recipe, pin ingredient list, open suitable practice, and select at a nearby open station.
- Preserve the existing favorites bound initially if needed, but display capacity and offer explicit replacement. Never silently evict an entry.
- Add a “Repeat setup” action that selects the last valid route and quantity. It must re-resolve recipes after reload and must not reproduce a previous score or seed.
- Show a pinned ingredient checklist outside the Notebook, with an easy dismiss action and minimal HUD footprint.
- Remember the player's last station tab, search/filter preferences, and preferred batch size.
- Make history and favorites survive normal death/relog through the existing persisted-player model, while keeping server-specific recipe data separate.
- Let invalidated favorites remain visible as unavailable, with a reason and a replacement route when one can be resolved.

**Acceptance criteria:** after brewing once, a returning player can stage the same batch without remembering the ingredient order, and a datapack reload cannot turn a favorite into stale authority.

### IB-110-05 — Remove inventory friction from repeated batches

**Priority:** P1 · **Effort:** M–L · **Depends on:** IB-110-01 and existing transaction rules

Add an explicit **Prepare this batch** action to the station's plan view.

- Show a transaction preview: bottles, raw reagents or prepared kits, fuel, paddle condition, expected remainders, and output space.
- Draw only from the open station, the player's accessible inventory, and an explicitly selected carrier. Nearby chest access should be a separate opt-in integration, never an invisible default.
- Give the player control over kit selection: raw, preserve best kits, or use selected kits. Display the resulting extraction estimate; do not silently spend a prized reagent kit.
- Move items atomically, or move nothing and explain the missing requirement. Preserve counts, item tags, capabilities where applicable, and byproducts.
- Keep existing JEI transfer as a first-class entry point to the same validation/service.
- Add “Collect batch” and “Return unused inputs” actions with a clear destination. If the destination is full, retain items safely.
- Refresh stale previews before starting. Starting the brew remains a separate explicit action.
- Show the cost breakpoints at one-to-three and four-to-six servings so a player understands why adding a fourth bottle can require another reagent application.
- Add a small “bottles filled / water still required” message to the existing bucket/pipe filling route.

**Acceptance criteria:** repeating a six-bottle batch takes materially fewer inventory movements, while simultaneous hopper access, a second viewer, or a full inventory cannot duplicate, drop, or consume unreserved items.

### IB-110-06 — Add quick finishing for container-only changes

**Priority:** P1 · **Effort:** M · **Depends on:** explicit transformation classification and transaction tests

Let players turn a completed batch into its supported splash or lingering form without replaying an entire craftsmanship test.

Recommended design:

- A **Finish batch** tab offers only transformations proven to change the container while preserving canonical effect contents.
- Use the real registered transformation, exact reagent/fuel policy, and real remainders. It is not a free crafting shortcut.
- Run as a short, interruptible station operation with visible progress; begin with a tunable 5–10 second prototype and validate the pacing.
- Preserve quality, extraction/control provenance, lineage, and purification count. A container conversion must neither improve quality nor force a new low score.
- Do not automatically apply this rule to redstone, glowstone, corruption, or arbitrary custom transformations. Those can change effect contents and need their existing brewing/inheritance rules.
- Expose the behavior as a server policy if an expert pack deliberately wants every operation to remain interactive.
- Test a complete chain from drinkable through splash to lingering, including reload, station break, and foreign custom effects.
- Keep recipe-derived arrow tipping available through ordinary crafting. An optional physical dipping action can be added later, using the loaded recipe's real consumption and output rather than creating a second, cheaper economy.

This is a deliberate behavior change from the current Kettle inheritance path, and should be documented as such. It is consistent with the existing stand behavior for equivalent-content container changes. [R12]

**Acceptance criteria:** a Masterwork potion can change supported container form without quality loss, free reagents, renewed purification eligibility, or altered foreign effects.

### IB-110-07 — Add the Apothecary Rack

**Priority:** P1 · **Effort:** M–L · **Depends on:** exact-stack storage and protection handling

This is the recommended visual flagship for 1.1.0: a useful, attractive place for finished brewing work.

Initial scope:

- One compact wall or tabletop rack with **six visible slots**, matching a Kettle batch.
- Direct insert/extract interaction, highlighted target slots, and a conventional accessible inventory screen.
- Show the actual bottle shape, potion tint, a small grade marker, and an optional short shelf label. Color must not be the only distinguishing feature.
- Support one-action transfer from/to the existing Batch Carrier.
- Add slot filters or reservation ghosts so a player can dedicate a rack to expedition supplies without destroying the original stacks.
- Preserve every stored stack independently. Visually group similar potions without merging different scores, labels, lineages, or custom data.
- Respect ordinary container access and protection hooks. A rack must not expose a locked workshop's contents through an alternate interaction path.
- Drop contents exactly once when broken; do not introduce another filled-container nesting loophole.
- Make hopper behavior predictable, with insertion/extraction rules documented in the Notebook.
- Offer a small initial material palette and a resource-pack-friendly model. Additional wood variants are polish work after storage behavior is correct.
- Avoid per-tick inventory scanning or excessive particles when idle; update render summaries on content changes.

**Acceptance criteria:** a player can see, select, and pack six different potions without opening several chests; a full rack retains mixed metadata through save/load and block removal.

### IB-110-08 — Improve the Batch Carrier as an expedition tool

**Priority:** P1 for selection/packing; P2 for direct drinking · **Effort:** M

The existing carrier already transports six exact stacks and loads or collects at stations. Keep that behavior and add:

- A selected slot, named loadouts such as “Caves” or “Nether,” compact contents preview, and “missing from loadout” indicators.
- Configurable label color or a small emblem, preserving the current leather strap as cosmetic.
- A remappable **equip selected potion** action that moves a real item to a free hotbar slot or performs an explicit safe swap. This is the preferred 1.1.0 implementation.
- Predictable empty-slot behavior: stop on the exhausted selection by default; let players opt into advancing. Never silently select a harmful potion.
- One-click packing from an open rack, subject to its filters and permissions.
- No forced global potion stacking change. Respect another mod's item stack limits and preserve exact tags.

Direct use from inside the carrier is a later convenience option. If implemented, it must invoke the actual item's use behavior, preserve use time, cancellation, cooldowns, events, and container returns, and explicitly reject unsupported custom items. Applying effect lists directly would bypass too much of other mods' behavior.

**Acceptance criteria:** hotbar selection works with full inventories, stacked potion mods, either hand, and death/relog, without losing the carrier or bypassing drinking time.

### IB-110-09 — Explain quality in terms of the actual benefit

**Priority:** P1 · **Effort:** S–M

Keep the existing formula initially and make its consequence legible.

| Example at default settings | Q | Eligible duration multiplier | Vanilla-baseline drinking time |
| --- | ---: | ---: | ---: |
| Crude lower bound | 0 | 0.80× | 36 ticks |
| Exact ordinary baseline | 50 | 1.00× | 32 ticks |
| Default purification ceiling | 70 | 1.08× | 30 ticks |
| Perfect control with raw extraction | 88 | 1.152× | 29 ticks |
| Masterwork threshold | 90 | 1.16× | 29 ticks |
| Perfect craftsmanship | 100 | 1.20× | 28 ticks |

These are calculations from QualityMath, not new measured balance results. Duration treatment applies only where the existing effect policy permits it; other mods may alter the use-time baseline. [R10] [R14]

Recommended presentation:

- Lead with the actual effect, amplifier, and effective duration; show quality and detailed scoring below.
- Add a concise comparison such as “+36 seconds versus ordinary” for a three-minute eligible potion at Q100, using the actual rounded resolver result rather than hand-coded tooltip math.
- Explain that Standard is a grade band; only Q50 exactly equals ordinary strength.
- Explain the raw-reagent ceiling before a player tries to reach Masterwork without preparation.
- For instant, mixed beneficial/harmful, unsupported, or custom effects, state what quality changes and what it leaves alone. A Fine label must not imply increased instant healing.
- Keep custom labels subordinate to the real potion identity and effects.
- Expand the existing result breakdown with the most important moment or phase to improve, not a long list of every error.
- Consider a compact phase timeline stored only for the most recent result, with a lower-detail text alternative.
- Resolve the animation/use-time mismatch and verify drink-speed composition with Immersive Jewelry and other relevant mods.

**Acceptance criteria:** the tooltip, preview, result, and actual consumption agree across Q0/50/100, every supported delivery form, and configured quality bounds.

### IB-110-10 — Give the Alembic a clearer purpose

**Priority:** P1 for preview and guidance; P2 for additional salvage uses · **Effort:** S–M

Purification should be presented as a choice about the quality of each remaining dose, not an efficiency upgrade.

At the defaults, three Q50 potions represent 3.00 ordinary-duration equivalents; two Q70 outputs represent 2.16. Even perfect purification sacrifices 28% of aggregate eligible duration to produce better individual doses. Instant effects may receive no power increase at all. [R10] [R11]

Improve the operation view:

- Show input count, output count, returned containers, fuel, possible quality range, and which effects can benefit.
- Explain the one-attempt lineage allowance before starting, including that an unsuccessful attempt still uses it.
- Show a comparison between keeping, purifying, and reclaiming the batch.
- Explain exact mismatch reasons: potion contents, labels/metadata, quality, purification history, or grouping. Do not call all of these simply “mixed.”
- Offer safe grouping assistance that selects compatible stacks without stripping labels or resetting lineage.
- Let the player practice the relevant distillation pattern directly from this preview.
- Preserve passive reclamation as the comfortable sink for surplus.
- Add a few non-power uses for Spent Alchemical Salt, such as decorative etched glass or cosmetic workshop materials. Keep their economic value low enough that purchased potions cannot create a profitable recycle loop.
- Prototype additional group sizes only after the 3→2 economy is understood. A 2→1 option may help occasional brewers, but should be a separate recipe with clear costs, not an undocumented exception.

**Acceptance criteria:** players can explain why they would purify a batch and can predict exactly what they will sacrifice before the operation begins.

### IB-110-11 — Complete the sensory and accessibility polish

**Priority:** P1 · **Effort:** M · **Depends on:** IB-110-03

The existing presentation already includes apparatus animation, particles, sound, text cues, contrast options, and reduced motion. Focus on consistency and completeness.

- Wire the existing brew-profile accent and particle fields into rendering, with a supported vocabulary, safe fallback, and client density controls.
- Make visual reaction signatures distinguishable through motion, shape, and pattern, not only hue. Keep the configured accessible palette authoritative for critical controls.
- Synchronize apparatus sounds with actual state changes: grinding pressure, a stable simmer, an approaching addition, successful dosing, and condensate collection.
- Verify that every actionable sound has a subtitle or equivalent persistent written cue. Add event-based narration for the active phase, next addition, focused controls, and completion; avoid narrating rapidly changing numbers every tick.
- Expose a compact in-game settings page for existing client options. Show which requested assistance settings the server approved and explain a server restriction before a productive brew starts.
- Add an optional reduced-input control layout that can be used without simultaneous key chords. Keep ordinary hold/toggle alternatives.
- Retain full scoring opportunities for players using assistance. Never put a stigmatizing “assisted” suffix on their potion names.
- Provide focusable, narrated alternatives for custom-drawn lists and controls, rather than assuming visible text makes a screen reader usable.
- Audit the smallest supported GUI size, large text, five existing languages, long translated names, and non-Latin labels.
- Review animations as video, including reduced motion and fast camera movement; still screenshots cannot demonstrate motion comfort.
- Test common rendering/resource-pack combinations on the target platform before promising shader compatibility.
- Add a station-state legend to the Notebook and Jade: idle, ready, running, sealed, held, and output blocked should be visually and verbally distinct.

**Acceptance criteria:** all core tasks can be completed using keyboard only, mouse only, no sound, and an accessible palette. Critical state remains readable at the smallest supported GUI size and in every maintained language.

### IB-110-12 — Improve pack-author control and diagnostics

**Priority:** P1 for existing-contract polish; P2 for scripting bridges · **Effort:** M

The mod already has substantial datapack and API support. The next improvement is making it easy to understand and maintain. [R14] [R23]

- Add a concise configuration reference explaining gameplay impact, defaults, restart/reload behavior, and whether existing jobs retain a snapshot.
- Provide named, documented configuration examples: **Artisan** for existing manual behavior, **Relaxed controls** for accessibility, and **Workshop production** only if optional unattended brewing is implemented. Do not equate the relaxed preset with lower-quality output.
- Expose a player-friendly compatibility page in the Notebook, backed by the existing report: effect scaling, metadata-only support, unsupported recipe, conflicting recipe, and discovery-budget limit are different states.
- Add an operator “explain this potion/plan” diagnostic with adapter identity, actual matched transformation, effective quality treatment, and the reason for a refusal.
- Validate missing registry IDs, invalid ranges, conflicting selectors, obsolete schema versions, and excessive recipe expansion with precise file paths.
- Include small working datapack examples for an ingredient profile, a brew profile, a safe effect policy, a container transformation, and a conditional integration.
- Keep atomic reload behavior and last-known valid data. Ensure server-derived descriptions and recipe browser results refresh together.
- Review configuration labels whose bounds can be mistaken for a different formula. For example, raising a duration upper clamp does not itself change the current 0.80 + 0.004Q curve. [R10]
- Add versioned documentation for any new data fields and preserve existing valid files.
- Prioritize a small KubeJS/CraftTweaker bridge only when it serves an actual pack workflow. Expose registration and queries through the public API rather than encouraging scripts to edit private NBT or station inventories directly.
- Supply translation keys for error explanations; keep raw technical details available to operators without putting them in the ordinary brewing flow.

**Acceptance criteria:** a pack author can add a supported custom ingredient and understand why an invalid recipe was rejected, without reading Java source or losing an in-progress batch.

### IB-110-13 — Test compatibility as a set of complete journeys

**Priority:** P0 for existing integrations; P2 for newly advertised integrations · **Effort:** L

A potion mod integration is complete only when its output can be found, brewed, carried, converted, consumed, saved, and recovered correctly.

#### Maintain and deepen current integrations

- Repeat the documented matrix for Apothecary, More Potions, PotionsMaster, RPG Potions, JEI, Jade, and the sister mods against explicit jar versions.
- Include Apothecary with a changed ingredient configuration and, if supported, its Iron's Spellbooks-dependent recipes. These are acknowledged gaps in the current test evidence. [R3]
- Retest the Apothecary/More Potions recipe collision using the live registry's actual winner.
- Exercise genuine potion-stacking settings from a compatible mod, rather than relying only on a test that edits item limits directly.
- Test recipe-viewer reloads and dedicated-server/client recipe-configuration differences.
- Preserve the current conservative treatment of unknown effects, custom effects, and mixed beneficial/harmful bundles until specific adapters prove broader support.

#### Prioritize new integrations

| Candidate | First useful deliverable | What must pass before advertising support |
| --- | --- | --- |
| Potion Blender | Recognize combined outputs and preserve trustworthy metadata through the supported combination path | No doubled quality scaling; correct custom effects; no lineage reset; splash/lingering/arrow behavior; defined handling of differently graded sources |
| Dave's Potioneering | Stack-size and storage coexistence first; delivery/coating support only after separate verification | Exact carrier/rack counts, use-time correctness, and no direct-effect application that bypasses its own item mechanics |
| Create | Item-based workshop logistics, followed by a limited fluid-adapter prototype | Drain, tank split/merge, mixer, spout, and save/load preserve the intended quality/provenance policy |
| EMI/REI or alternate overlays | Only where a maintained build exists for the target platform and users need it | Same supported recipe set, costs, remainders, and transfer checks as the native browser/JEI |
| Quest and advancement tools | Events for first valid brew, recipe family, quality milestone, and completed recovery | Rewards fire exactly once and cannot be farmed through replayed completion or container conversion |
| Claims/protection and storage mods | Respect ordinary permissions and item capability contracts | Direct interaction, automation, and batch refill cannot bypass a protected inventory |

#### Define a quality-preservation contract

Classify each external transformation:

1. **Exact transport:** preserve the complete stack.
2. **Container-only change:** preserve quality and lineage if contents remain equivalent.
3. **Effect-changing brewing:** follow the documented inheritance policy; do not improve quality by metadata loss.
4. **Blending multiple sources:** require an explicit multi-source rule.
5. **Unsupported transformation:** state the limitation or refuse the dedicated integration path; do not advertise fidelity.

For fluids, full metadata must survive every participating operation, not merely the first drain and final spout. Merging fluid with different grades or lineage cannot silently choose the better input or reset purification. A prototype should initially permit only exact-compatible fluid identities, with conservative refusal otherwise.

The inspected Create conversion methods are a concrete reason for this work: they reconstruct data from potion contents and bottle form. A custom field added at the edge alone is insufficient if a mixer later regenerates the fluid without it. [M9]

**Acceptance criteria:** each advertised integration has a versioned result matrix, including round trips and negative cases. “Loads without crashing” is not the compatibility standard.

### IB-110-14 — Make reliability and distribution easy to reproduce

**Priority:** P0 · **Effort:** M–L

Preserve the existing single-owner ledger and server-authoritative input model. New journal, rack, quick-finishing, and integration features must fit those rules.

- Keep the one-commit operation model: reserve, validate, commit once, then emit result events.
- Version new item, block-entity, player-guide, and job data deliberately. Keep schema-1 potions readable; preserve unknown newer payloads.
- Resume old jobs using the parameters and rules they actually saved. Do not reinterpret an old simulation under new scoring midway through a brew.
- If a state cannot safely migrate, hold it with an actionable recovery explanation. Return unspent inputs only where ownership and consumption are known.
- Cover reload, restart, chunk unload, death, dimension change, block break/explosion, full outputs, controller handoff, and repeated packets for every new job type.
- Retain caps on packet size, label length, recipe queries, stored history, and nested item data.
- Apply permissions, distance checks, inventory revisions, and session ownership on the server for new UI actions.
- Test simulation-versus-execution behavior of item and fluid capabilities. A simulated transfer must never mutate a carrier, rack, or job.
- Separate the core build from optional integration fixtures. Audit unused compile-only dependencies first; keep genuinely needed API dependencies in clearly named source sets or optional tasks.
- Add a clean CI path for unit tests, generated-resource checks, and core GameTests. Add a separately provisioned integration matrix with explicit versions and checksums where distribution permits.
- Keep the scripted client smoke suite, but supplement it with human play and recorded animation review.
- Publish a versioned changelog, migration notes, exact target platform, and honest compatibility table alongside the jar.

**Acceptance criteria:** a clean checkout can build the core without manually acquiring unrelated potion-mod jars, and a 1.0.2 world fixture survives upgrade with its items and interrupted jobs intact.

## 6. Optional features with strong potential

The following are worthwhile designs, but should not displace the core work. Each needs an explicit owner, acceptance criteria, and a decision about whether it ships in 1.1.x or a later themed release.

### A. Shareable formula cards

A formula card is a physical copy of a plan, quantity, preferred ingredient choices, and a short note. It is useful in a multiplayer workshop or on a rack.

- The card records instructions, not a guaranteed quality, captured simulation, or free recipe permission.
- The server resolves its recipe and checks current policy every time it is used.
- A missing mod or changed recipe produces a readable unavailable card.
- Copying a card copies knowledge only; it never copies input items or output.
- Begin with pinning and sharing existing recipes. Experimental recipe discovery can use the same presentation later.

**Recommendation:** P2. The journal and direct-repeat workflow should come first.

### B. Reagent jars and preparation staging

Add a compact jar for one exact ingredient or prepared-kit identity, with visible fill, a label, and predictable transfer.

- Use tags or explicit policies to determine eligible reagents.
- Keep differently prepared kits separate unless a carefully defined merge operation exists.
- Preserve preparation extraction, source identity, and all foreign data.
- Allow batch staging from a jar through an explicit open-workshop action.
- Do not require a new container for every ingredient in an otherwise simple recipe.

**Recommendation:** P2; a natural companion to the rack after its interaction code is stable.

### C. Basic unattended brewing

There is legitimate demand for ordinary potion production after the novelty of a minigame wears off, especially on servers. The existing stand already serves that purpose in COEXIST mode; start by teaching that option well.

If a Kettle production mode is added:

- Make it separately server-configurable and clearly distinct from accessibility assistance.
- Produce baseline-quality newly created potions, with no automatic Masterwork replay.
- Preserve lower inherited quality and purification history when processing existing potions; automation must not cleanse bad metadata.
- Use exactly the live recipe's resource and remainder policy, and a throughput benchmark against the ordinary stand.
- Allow a bounded queue of formula requests rather than pre-consuming many future batches.
- Reserve only the active batch; check inputs and output space again at every job boundary.
- Pause when its chunk is unloaded unless the platform already provides legitimate loaded-chunk ticking; do not simulate wall-clock production on reload.
- Expose redstone stop/start, state output, and one-result pulses without making comparator modes ambiguous.
- If a pack wants an unlock, use a simple demonstrated recipe milestone rather than a large grind.

**Recommendation:** P2. Validate that it complements manual brewing and does not become mandatory.

### D. Optional physical interaction

Let players insert bottles, set a displayed formula, inspect an ingredient, and collect a result directly on the apparatus.

Use generous target regions and clear outlines. Retain the full screen and controls. Do not require dragging individual droplets, drawing perfect circles, or physically reenacting every stir. Consider immersive input/VR adapters only after the ordinary interaction model is stable.

**Recommendation:** P2 prototype for loading/collection; P3 for a complete world-space minigame.

### E. Workshop personalization

Add batch stamps, wax-seal colors, shelf plaques, interchangeable apparatus finishes, and a few grounded decorative items.

Extend the existing labels and notes. Keep cosmetic differences from changing recipe identity, score, or gameplay effects. Make labels configurable for multiplayer moderation and length limits. Cosmetic marks should not obscure the real potion name or consume so much metadata that identical batches fragment unnecessarily.

**Recommendation:** P2 with the rack; prefer a coherent small set over many nearly identical blocks.

## 7. Future alchemy expansion: design it as connected systems

### 7.1 Blending and compound potions

**Potential:** high · **Complexity:** high · **Recommendation:** compatibility first, native module later

A native blending system would expand the current product promise: a new multi-effect result is more than a craftsmanship overlay. Treat it as an explicit optional recipe family with its own data/API contract.

A safe first native design would:

- Accept **two single-effect drinkable doses** and one documented stabilizer to produce **one combined dose**.
- Limit the initial system to a curated set of compatible, finite-duration effects.
- Show both input effects, resulting durations, consumed doses, and the stabilizer before committing.
- Never increase amplifiers through duplicate inputs.
- Initially reject duplicate effects instead of creating an easy duration-stacking exploit.
- Reject instant effects, infinite effects, unsupported custom delivery, and inherently coupled beneficial/harmful bundles until each has a deliberate policy.
- Set a global slot budget, initially two effects, and let datapacks lower it or restrict combinations.
- Carry conservative provenance and the most restrictive purification history. A flat output score alone is insufficient when sources differ.
- Preserve each source's effective-duration budget through an explicit per-effect base/quality representation or a well-defined compiled result. Whichever representation is chosen, apply quality exactly once.
- Test reblending, dilution, container conversion, distillation, crafting, fluid transport, and arrow creation as potential laundering loops.

A later version could support useful recipes such as an underwater exploration blend of Water Breathing and Night Vision, or a deliberate risk/reward blend. Do not generate arbitrary stronger amplifiers or turn any pair of potions into a superior universal buff.

### 7.2 Weapon coatings and ammunition preparation

**Potential:** high for combat · **Complexity:** high · **Recommendation:** integration before a native system

A coating should trade duration and charges for convenience.

- Begin with explicit eligible weapon and effect tags.
- Specify charges per input dose, short effect duration per hit, and whether a missed attack consumes a charge.
- Consume at most one charge for one eligible attack event; define sweeping attacks, multihit weapons, projectiles, offhand attacks, and invulnerable hits.
- Prevent one coated weapon from generating unlimited lingering clouds or repeatedly triggering instant damage.
- Retain provenance and effects through repair, anvil use, smithing, death, and item transfer.
- Respect PvP, team, and protection rules.
- Provide cleaning/removal through an intentional operation.
- Avoid stacking with another mod's coating system unless a tested adapter owns the behavior.

Arrow dipping is a smaller related feature. Use the loaded arrow recipe's actual input/output accounting and preserve quality; do not make it an accidental cheap route around the game's intended lingering-potion progression.

### 7.3 Multidose flasks and field kits

**Potential:** high for exploration · **Complexity:** medium–high

A flask should solve repeated inventory handling without creating potion value.

- Fill it from a fixed number of real doses and display remaining doses.
- Start with identical complete potion identities; do not mix grades or labels implicitly.
- Match ordinary drinking time and item-use events initially.
- Return the correct containers or account for their retention at filling time.
- Keep potions in a dedicated flask representation until empty; do not round-trip through averaged quality.
- Prevent nesting with carriers or other inventory-bearing items.
- Let users refill from a rack or station, not automatically from every potion in their inventory.
- Keep a field kit focused on storage and preparation. Instant high-quality brewing in the wilderness would erase the workshop's purpose.

### 7.4 Poultices, antidotes, and utility preparations

**Potential:** medium–high · **Complexity:** medium

Consider a few distinctive non-combat uses before adding many numerical buffs.

| Candidate | Useful role | Necessary constraint |
| --- | --- | --- |
| Poultice | Slow, stationary recovery or a modest regeneration effect | Taking damage interrupts application; does not outclass instant healing during combat |
| Selective antidote | Removes a named class of harmful effects while preserving beneficial ones | Explicit effect allowlist; not a universal boss-debuff remover |
| Antitoxin preparation | Limited protection against a specific poison application | Clear charge/duration rule; no permanent immunity |
| Cooling or warming draught | Compatibility with temperature/survival packs | Require a real adapter; no hidden hard dependency or invented vanilla temperature mechanic |
| Revealing wash | Short-lived visibility against a specific supported concealment effect | No universal entity wallhack; define range, target rules, and counterplay |
| Botanical tonic | Utility for a curated agricultural interaction | Bounded growth rules and costs; no trivial crop/ingredient duplication cycle |

Prefer recipes using existing ingredients at first. Each new effect should answer a distinct player need, have a readable duration and counterplay, support configuration, and justify its maintenance cost.

### 7.5 Herbalism and ingredient processing

**Potential:** high for a themed expansion · **Complexity:** high

Connect gathering and preparation through a small set of ingredients with meaningful uses.

- Introduce only a few herbs, each useful in multiple recipes.
- Provide renewable cultivation and common alternatives so a rare biome is not a permanent bottleneck.
- Add drying, crushing, or steeping only when the process changes a choice: shelf capacity, preparation behavior, recipe suitability, or yield with a real tradeoff.
- Let drying run passively with visible progress and safe unload behavior.
- Use item tags to integrate crops from other mods.
- Keep essential vanilla brewing possible without the herbal chain.
- Avoid generic spoilage timers, daily watering chores, or mandatory tool-cleaning unless a survival pack explicitly opts in.
- Consider location-based flavor or cosmetic variation before adding biome-dependent quality bonuses that encourage relocating every workstation.

### 7.6 Fermentation and maturation

**Potential:** medium–high · **Complexity:** high

Use fermentation for a different pace of play: prepare a batch, leave it working, and return later.

- Make recipe outcomes deterministic and visible before committing.
- Use temperature bands or vessel choice for intentional recipes, not hidden random spoilage.
- Preserve partially completed work across save/load and data changes.
- Cap quality gain and prevent repeated aging/reset cycles.
- Require meaningful input transformations and distinct output uses; simply waiting for a longer potion is not enough.
- Choose whether aging consumes loaded game time or a controlled server clock, document it, and test clock changes. Default to ordinary loaded-world ticking.
- Keep alcoholic beverages optional and separate from the core potion loop if introduced.

### 7.7 Solvents, concentration, and effect separation

**Potential:** high conceptual depth · **Complexity:** very high

These mechanics are powerful because they change the economy of effects.

- Concentration should trade duration or number of doses for another explicitly bounded property; it must not freely increase amplifier.
- Dilution must conserve total effective duration or another documented value budget and must not multiply instant-effect applications.
- A solvent that removes one unwanted effect should have curated eligibility and cost.
- Do not let players separate the beneficial component of a deliberately balanced harmful/beneficial bundle by default.
- Unknown custom effects stay intact or are rejected.
- Reclaimed essences must not recreate the original inputs at a profit.
- Add all of these only after the metadata model can represent per-effect origins and transformations safely.

### 7.8 Incense, vaporizers, traps, and support devices

**Potential:** high for builders and groups · **Complexity:** high

A small apothecary brazier could deliver a curated non-instant effect around a workshop or campsite.

Define:

- Eligible effects, radius, line-of-sight behavior, target count, and fuel/dose drain.
- A total effect budget that scales with recipients, or an explicitly balanced area-support cost.
- Reapplication cadence without rapidly flashing status effects.
- Team, pet, PvP, and protection rules.
- No stacking multiple devices to multiply amplifier or bypass duration limits.
- Redstone shutdown and predictable unload behavior.
- Separate permissions and defaults for harmful traps; friendly support is the better initial use case.

Botania provides useful evidence that alternative delivery can have its own costs and exclusions. [M7] Immersive Brewing should establish its own scale rather than inheriting another mod's radius or duration.

### 7.9 Research, experimentation, and discovery

**Potential:** high for long-term engagement · **Complexity:** high

Start with learning and optional collections, not locked basic recipes.

- Track discovered reaction families, successful techniques, and personal improvements.
- Let experimentation happen in a clearly marked mode with previewable ingredient traits.
- Use deterministic compatibility rules so repeated reasoning works.
- Keep failures bounded and informative; a failed experiment can produce a usable low-grade result or a defined salvage item.
- Do not make an unfamiliar ingredient silently explode the player's workshop.
- Unlock cosmetic notebook annotations, display plaques, or optional special recipes.
- Preserve the default open recipe catalog. Research-gated packs can opt into restrictions through events/data.
- Distinguish a formula someone shared from a technique the player has personally demonstrated.

A large spatial alchemy map is an interesting separate game system, but it would compete with the current recipe-backed workshop and is too much for 1.1.0.

### 7.10 Villages, commissions, and cooperative workshops

**Potential:** high for social play · **Complexity:** high

The current optional cleric trades sell simple supplies and are off by default pending economy testing. Build from that restraint. [R24]

For commissions:

- Request a bounded quantity, potion identity, and minimum quality.
- Prefer varied practical orders over repeatedly buying the easiest potion.
- Consume exact matching items once and pay only after the transaction commits.
- Include stock/restock limits and costs that cannot be exploited through buying, rebottling, recycling, and reselling.
- Do not require a named player as original brewer unless the design explicitly means a personal craftsmanship challenge.
- Offer useful non-power rewards such as apparatus cosmetics, formula cards, or supply access.
- Make village structures and wandering apothecaries optional; update existing worlds through non-worldgen content first.

For cooperation:

- Ship safe observation and explicit handoff before simultaneous control.
- A later cooperative mode can assign heat, stirring, and dosing roles with server-owned permissions.
- Define what happens when a partner leaves, opens help, or loses connection.
- Record contributors separately from the original brewer; distribute rewards once.
- Keep single-player recipes and quality attainable without a team.

## 8. Coverage map: what a complete potion-brewing experience should address

This map prevents expansion ideas from overshadowing ordinary expected functionality.

| Player or pack-author need | Current baseline | Recommended disposition |
| --- | --- | --- |
| Ordinary registered potion recipes | Supported through plans and registry adapter | Preserve and verify |
| Start from Water, bases, or existing potions | Chained routes and suffix entry points | Improve route discovery in 1.1.0 |
| Drinkable, splash, lingering forms | Supported | Add quick finishing and clearer previews |
| Tipped arrows and dispensers | Existing quality paths | Test override conflicts and complete round trips |
| Modded potion effects | Four explicit integrations plus registry support | Report exact coverage and support policy |
| Arbitrary custom potion items | Conservative metadata-only fallback/API boundaries | Tested adapters, not blanket promises |
| Compound effects/blending | Not a native creation system | Integration first; optional future module |
| Stronger/longer/corrupted variants | Governed by source recipes | Keep legal transformations and inheritance |
| Ingredient preparation | Mortar and kits | Better guidance and staging |
| Ingredient experimentation | Not a native freeform system | Future optional module |
| Multiple servings | One to six | Improve cost breakpoints and staging |
| Very large production | Stand coexistence and item automation | Optional bounded production mode; no immediate batch-size inflation |
| Fuel and water supply | Blaze charges and whole-bottle water filling | Clear feedback; alternative heat only as a separate balanced policy |
| Storage and display | Carrier; ordinary inventories | Apothecary Rack in 1.1.0 |
| Field use | Ordinary potion use; carrier transport | Selected-slot hotbar access |
| Multidose containers | No native flask | Future exact-dose container |
| Labels and provenance | Already present | Better batch workflow and shelf presentation |
| Quality and mistakes | Bounded score and result feedback | Clear benefits, history, and targeted advice |
| Salvage and purification | Alembic | Explain total cost; conservative additional salt uses |
| Weapon coatings | No native system | Adapter or later combat module |
| Selective remedies | No native remedy family | Small optional utility module |
| Area application | Vanilla lingering paths | Future curated support device |
| World-space interaction | In-world visuals; screen-based games | Optional loading/collection prototype |
| Accessibility and localization | Strong initial feature set; five languages | In-game settings, narration, alternate input, human trials |
| Automation and redstone | Existing item handling and comparators | Better documented states and tested logistics |
| Fluid systems | Water filling; no general quality-preserving potion-fluid contract | Explicit adapter work |
| Recovery and portability | Sealed jobs and Recovery Batches | Preserve and extend |
| Multiplayer assistance | Controller locks and later resume by another player | Better handoff; simultaneous roles later |
| Progression and purpose | Guide, advancements, optional cleric trades | Optional collections/commissions after economy testing |
| Pack customization | Datapacks, profiles, exclusions, API/events | Diagnostics and examples; targeted scripting bridge |
| Dedicated servers | Supported architecture and GameTests | Real two-client network validation |
| Performance and large packs | Existing bounded search and tests | Pagination, budgets, realistic large-pack profiling |
| Upgrades and downgrades | Versioned metadata and conservative handling | Explicit 1.0.2 migration fixtures; no blanket downgrade promise |
| Other loaders/Minecraft versions | Current Forge 1.20.1 target | Separate port after 1.1 behavior and data contracts stabilize |

## 9. Balance principles and proposed defaults

These are starting policies for implementation and playtesting, not validated outcomes.

| Topic | Recommended default |
| --- | --- |
| Relationship to vanilla | Retain COEXIST |
| Raw ingredient access | Retain extraction 50 and ordinary recipe access |
| Quality curve | Retain existing curve and amplifier protections |
| Masterwork | Earned through manual craftsmanship, including accessible controls |
| New native effects | None required for core 1.1.0 |
| Quick finishing | Enabled for verified equivalent-content container changes |
| Batch size | Retain six; improve handling before raising capacity |
| Automatic production | Off until separately designed and tested |
| Passive quality gain | No unlimited aging, repeated refinement, or stored-perfect-score replay |
| Reagent refunds | No quality-based extra ingredient yield in the initial release |
| Completion XP | Retain zero until loop testing supports another value |
| Item stacking | Respect the item/provider; do not globally overwrite potion limits |
| Shelf life/spoilage | No default spoilage |
| Equipment upkeep | Preserve meaningful paddle wear; avoid mandatory cleaning chores |
| Recipe access | Open catalog; optional pack-specific research restrictions |
| Recovery | Safe sealing, no rerolling, one owner for each item |
| New world generation | Not required for existing worlds to enjoy 1.1.0 |

### Avoid these traps

- Adding more mandatory stations to every ordinary potion.
- Making stronger tools automatically produce better scores without better choices.
- Hiding recipes behind repeated blind failures.
- Extending minigame length to make a recipe feel “advanced.”
- Changing instant-effect power through a duration-quality system.
- Letting diluted or rebottled potions reset lineage and purification.
- Rewarding low-quality production with more valuable salvage than the original inputs.
- Treating accessible input as a lower skill tier.
- Adding a second recipe source that disagrees with the live brewing registry.
- Advertising broad compatibility after only a startup test.

## 10. Implementation sequence and handoff

### Phase A — Establish evidence and fix discoverability

Implement IB-110-01, make the core build reproducible, create 1.0.2 migration fixtures, and run an initial human playtest of the unchanged baseline.

**Exit:** large-catalog searches work; baseline friction is documented; automated core verification can be repeated by another developer.

### Phase B — Improve the existing loop

Implement staged onboarding, targeted control feedback, approved-assistance display, quality explanations, Alembic previews, and bounded journal history.

**Exit:** new players can complete a brew and learn from the result; experienced players understand where preparation and purification help.

### Phase C — Deliver the workshop experience

Implement repeat setup and atomic staging, quick finishing, the rack, and selected-slot carrier access. Complete the existing presentation fields.

**Exit:** the full “choose → brew → label → rack → pack → use” journey works with mixed potion metadata and interruption recovery.

### Phase D — Validate and stabilize

Run migration, real-network, localization, animation, performance, and integration matrices. Publish only the compatibility claims supported by these results.

**Exit:** all P0 gates and core P1 acceptance criteria pass.

### Phase E — Choose one optional extension

After stabilization, select either reagent jars/formula sharing, a targeted potion-mod adapter, or basic production. Do not bundle all three with native blending and herbalism.

If capacity is constrained, cut optional integrations, decorative variants, direct carrier drinking, and extra production systems first. Keep recipe completeness, recovery, clear controls, and the coherent rack/journal workflow.

### Code areas to use

Paths below are relative to the repository's Java package com.otectus.immersivebrewing unless otherwise stated.

| Workstream | Existing entry points | Implementation guidance |
| --- | --- | --- |
| Catalog and search | recipe/PlanIndex, recipe/DiscoveryIndex, blockentity/BrewersKettleBlockEntity, network/packet/PlanListS2C, client/screen/KettlePlanScreen | Separate destination discovery, route resolution, availability checks, and page transport without removing budgets |
| Notebook | guide/PlayerGuide, client/ClientGuide, client/screen/NotebookScreen, network/packet/NotebookPrefsC2S | Add bounded summary records and stable actions; retain server validation |
| Repeat staging | station menus, StationInventory, existing JEI transfer, JobLedger ownership model | Share one transactional service; avoid separate competing transfer code paths |
| Quick finishing | QualityInheritance, BrewPlan/PlanStep classification, station job state | Prove equivalent contents; retain cost and lineage invariants |
| Rack and carrier | item/CarrierContents, CarrierItemHandler, BatchCarrierMenu, StationBlock interaction patterns | Reuse exact-stack policies; add dedicated access checks and save/load tests |
| Controls and feedback | minigame packages, session/SessionManager, client/ClientSession, GameScreen and station screens | Keep pure deterministic simulations and server-owned scoring |
| Presentation | data/BrewProfile, client/render, client/particle, client/sound, resource language files | Complete existing data fields and accessible presentation paths |
| Quality and integrations | PotionContentsService, BrewingMetadata, API handlers/events, adapter registry, tipped-arrow recipe | Preserve canonical contents; extend contracts additively where possible |
| Verification | src/test, src/gameTest, src/smokeTest, tools/run_smoke_matrix.sh, docs/TESTING.md | Extend existing meaningful fixtures; record new results separately |
| Build and release | build.gradle, gradle.properties, generated-resource tools, documentation | Isolate optional fixtures and publish a reproducible core build |

Any public API extension should preserve API v1 meaning and signatures where possible. A new per-effect or entity-delivery contract should be an opt-in interface/version, not a silent reinterpretation of existing handlers.

## 11. Acceptance plan

### Human experience trials

Use a small formative group of approximately 8–12 participants across Minecraft newcomers to brewing, experienced modded players, and players who use relevant accessibility options. This is a practical usability exercise, not a statistically representative study.

Test these tasks:

1. Find and brew a simple useful potion using only the Notebook.
2. Explain why the temperature overshot and improve on a second attempt.
3. Complete a six-bottle batch with prepared and raw ingredient options.
4. Find a long route in the combined potion-mod catalog.
5. Seal, leave, return, and resume a job.
6. Decide whether to keep or purify a batch.
7. Repeat the batch, label it, store it on the rack, and pack an expedition loadout.
8. Complete the same basic flow without sound and with a keyboard-only route.

Record time, errors, requests for help, missed cues, unnecessary inventory movements, perceived effort, and whether the participant wants to brew again.

Provisional release targets:

- At least 80% of the formative group completes the first productive brew without external instructions.
- At least 80% can explain preparation's purpose and purification's sacrifice afterward.
- Repeating an already-understood batch requires at least 30% fewer setup inventory interactions than the measured 1.0.2 baseline.
- No critical action depends solely on color, sound, or a rapid key chord.
- Every reported loss of ingredients, unrecoverable job, or false unsupported-recipe result is investigated before release.

### Automated and real-environment checks

| Area | Required cases |
| --- | --- |
| Recipe catalog | Large registries; long routes; equivalent outputs; contextual recipes; exclusions; stale pages; budget exhaustion; reload; changed source-mod config |
| Material accounting | Batch sizes 1–6; exact reagent breakpoints; bottle/remainder returns; full inventories; simulated transfers; simultaneous viewers and hoppers |
| Quality | Q0/50/100; raw-reagent ceiling; mixed grades; purification allowance; container finishing; instant and infinite effects; harmful/beneficial bundles; foreign custom effects |
| Delivery | Drinking, splash near/far, lingering, arrows, dispensers, interrupted use, custom handler fallback, other drink-time modifiers |
| Persistence | Idle/reserved/running/sealed/held/completed states; world restart; unload; break/explosion; death; dimension travel; recovery-item reinstall; missing/reintroduced data |
| Migration | 1.0.2 potions, kits, carriers, notebooks, jobs, labels, purification history, and Recovery Batches |
| Networking | Two actual clients; 0/100/200 ms added round-trip latency; jitter and limited packet loss; healthy and reduced TPS; reconnect; handoff; malformed/replayed requests |
| UI and accessibility | Small GUI, high DPI, alternate bindings, keyboard/mouse-only, no sound, reduced motion, accessible palettes, narration, all maintained translations |
| Performance | Representative large pack; multiple simultaneous stations; many idle racks; search bursts; reload time; client FPS; memory and save-size growth |
| Integrations | Each currently supported potion mod separately and together; changed config; real stack-size setting; exact new provider versions; fluid cycles only if supported |
| Distribution | Clean build; generated resources current; correct version metadata; no development-only tests or optional dependency jars in release artifact |

For latency tests, distinguish algorithmic determinism from perceived fairness: identical accepted input is a correctness requirement, while comparable human performance across network conditions is a separate usability target.

For performance, establish a reference machine and pack before assigning budgets. A reasonable initial engineering target is cached search responses that do not create a visible server hitch, with expensive discovery split into bounded work. Measure p95/p99 tick cost and worst-case reload work; do not rely only on a mean.

### Economy and exploit checks

Trace complete cycles, not isolated recipes:

- Brew → modify → purify → convert → reclaim.
- Buy ingredients/potions → brew or reclaim → trade.
- Drain → merge/split → bottle → refine.
- Blend → dilute → separate → blend again.
- Carrier/rack transfer during block break, disconnect, or inventory replacement.
- Replay completion, claim a quest reward, then reopen/recover the same job.

The acceptance rule is conservation of defined resources, bounded effect value, and exactly-once rewards. Not every conversion preserves total duration, but none should create unintended value or erase restrictions.

## 12. Final recommended 1.1.0 package

Ship a release players can describe in one sentence:

**“Brewing is easier to learn and repeat, recipes are easier to find, and my finished potions finally have a proper place in the workshop.”**

The concrete package is:

- Reliable recipe discovery and destination search.
- Guided practice and clearer live control feedback.
- A useful brewing journal with bounded history and repeat setup.
- Atomic batch staging and collection.
- Quick, quality-preserving container finishing.
- Apothecary Rack and improved carrier selection.
- Clear quality benefits and Alembic tradeoffs.
- Completed presentation settings and stronger accessibility.
- Reproducible builds, migration coverage, and real multiplayer testing.

Keep blending, coatings, fermentation, herbalism, and area support as well-specified extensions. This preserves a coherent 1.1.0 while leaving a substantial future for the mod.

## 13. Sources and audit trail

Repository references are pinned to the reviewed commit. External feature pages were consulted on October 28, 2026; they can change and do not substitute for a tested jar-specific integration.

### Immersive Brewing

- **R1 — Version and platform:** [gradle.properties][R1].
- **R2 — Current feature set, workflows, and integration claims:** [README][R2].
- **R3 — Recorded test results and explicitly unverified scenarios:** [TESTING][R3].
- **R4 — Architectural decisions, limitations, and deferred systems:** [ARCHITECTURE][R4].
- **R5 — Recorded version changes and localization work:** [CHANGELOG][R5].
- **R6 — Recipe catalog limits and route generation:** [PlanIndex][R6]; related [PlanListS2C][R25], [Kettle plan screen][R26], and [Kettle block entity][R27].
- **R7 — Notebook recipe catalog and existing actions:** [NotebookScreen][R7]; related [JEI plugin][R28].
- **R8 — Favorites capacity and persisted guide state:** [PlayerGuide][R8].
- **R9 — Existing score explanation:** [ResultScreen][R9].
- **R10 — Quality, duration, drinking time, and purification formulae:** [QualityMath][R10].
- **R11 — Alembic eligibility, metadata equality, yield, and attempt handling:** [AlembicBlockEntity][R11].
- **R12 — Quality inheritance and external pass-through:** [QualityInheritance][R12].
- **R13 — Authored reaction presentation fields:** [BrewProfile][R13].
- **R14 — Public API and custom delivery limits:** [API][R14]; related [PotionContentsService][R29].
- **R15 — Tipped-arrow metadata inheritance:** [BrewingTippedArrowRecipe][R15].
- **R16 — Build dependency configuration:** [build.gradle][R16].
- **R17 — Server defaults and policies:** [ServerConfig][R17].
- **R18 — Existing accessibility and presentation options:** [ClientConfig][R18].
- **R19 — Exact-stack carrier storage:** [CarrierContents][R19]; related [BatchCarrierItem][R30].
- **R20 — Station ownership and persistence implementation:** [StationBlockEntity][R20].
- **R21 — Potion metadata schema and existing labels:** [BrewingMetadata][R21].
- **R22 — Participation scoring and reaction simulation:** [KettleSimulation][R22].
- **R23 — Existing datapack contract:** [DATAPACKS][R23].
- **R24 — Optional cleric trade economy:** [ClericTrades][R24].

### Comparable projects

- **M1 — Potion Blender:** [Author's project description][M1].
- **M2 — Dave's Potioneering:** [Author's project description][M2].
- **M3 — Extra Alchemy:** [Author's project description][M3] and [official repository][M3a].
- **M4 — Hexerei:** [Author's project description][M4].
- **M5 — Rustic:** [Author's project description][M5].
- **M6 — Brewin' and Chewin':** [Project page][M6].
- **M7 — Botania:** [Official Lexicon][M7], especially Botanical Brewery, Brew Incenses, Brewing Flasks, and Complex Brews.
- **M8 — ImmersiveMC:** [Project page][M8].
- **M9 — Create:** official mc1.20.1/dev source inspected at commit 588124239141029c5321afd38d20c2eaf872b32b: [PotionFluidHandler][M9] and [PotionMixingRecipes][M9a]. Development source is not evidence that a specific released jar has been integration-tested.
- **M10 — Toil and Trouble:** [Author-maintained project page][M10].
- **M11 — Shule's Potions:** [Author's project description][M11].

[R1]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/gradle.properties
[R2]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/README.md
[R3]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/docs/TESTING.md
[R4]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/docs/ARCHITECTURE.md
[R5]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/CHANGELOG.md
[R6]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/recipe/PlanIndex.java
[R7]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/client/screen/NotebookScreen.java
[R8]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/guide/PlayerGuide.java
[R9]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/client/screen/ResultScreen.java
[R10]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/potion/QualityMath.java
[R11]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/blockentity/AlembicBlockEntity.java
[R12]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/potion/QualityInheritance.java
[R13]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/data/BrewProfile.java
[R14]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/docs/API.md
[R15]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/recipe/BrewingTippedArrowRecipe.java
[R16]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/build.gradle
[R17]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/config/ServerConfig.java
[R18]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/config/ClientConfig.java
[R19]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/item/CarrierContents.java
[R20]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/blockentity/StationBlockEntity.java
[R21]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/potion/BrewingMetadata.java
[R22]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/minigame/brewing/KettleSimulation.java
[R23]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/docs/DATAPACKS.md
[R24]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/event/ClericTrades.java
[R25]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/network/packet/PlanListS2C.java
[R26]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/client/screen/KettlePlanScreen.java
[R27]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/blockentity/BrewersKettleBlockEntity.java
[R28]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/client/compat/jei/ImmersiveBrewingJeiPlugin.java
[R29]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/potion/PotionContentsService.java
[R30]: https://github.com/otectus/ImmersiveBrewing/blob/53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48/src/main/java/com/otectus/immersivebrewing/item/BatchCarrierItem.java
[M1]: https://www.curseforge.com/minecraft/mc-mods/potion-blender
[M2]: https://www.curseforge.com/minecraft/mc-mods/daves-potioneering
[M3]: https://www.curseforge.com/minecraft/mc-mods/extra-alchemy
[M3a]: https://github.com/zabi94/ExtraAlchemy
[M4]: https://www.curseforge.com/minecraft/mc-mods/hexerei
[M5]: https://www.curseforge.com/minecraft/mc-mods/rustic
[M6]: https://modrinth.com/project/brewin-and-chewin
[M7]: https://botaniamod.net/lexicon.html
[M8]: https://modrinth.com/mod/immersivemc
[M9]: https://github.com/Creators-of-Create/Create/blob/588124239141029c5321afd38d20c2eaf872b32b/src/main/java/com/simibubi/create/content/fluids/potion/PotionFluidHandler.java
[M9a]: https://github.com/Creators-of-Create/Create/blob/588124239141029c5321afd38d20c2eaf872b32b/src/main/java/com/simibubi/create/content/fluids/potion/PotionMixingRecipes.java
[M10]: https://modrinth.com/mod/toil-and-trouble
[M11]: https://www.curseforge.com/minecraft/mc-mods/shules-potions



