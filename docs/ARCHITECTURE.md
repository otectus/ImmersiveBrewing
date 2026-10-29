# Immersive Brewing - Architecture

Decisions, their evidence and every deviation from `docs/Immersive_Brewing_Development_Spec.md` (design 1.0). The
dependency-ordered build plan is `docs/IMPLEMENTATION_PLAN.md`; datapack formats are in `docs/DATAPACKS.md`, the Java
API in `docs/API.md`, configuration scope/snapshots in `docs/CONFIGURATION.md`, and test commands/evidence in
`docs/TESTING.md`.

Baseline: Minecraft 1.20.1, Forge 47.4.23, Java 17, ForgeGradle 6.0.54, official mappings (versions only in
`gradle.properties`). The current tree is development version 1.1.0, not a release claim.

## Package map

| Package | Responsibility |
|---|---|
| `potion` | `BrewingMetadata` (the one compound this mod writes on a stack), `PotionContentsService` (canonical vs effective contents), `EffectPolicies`, `QualityMath`, `Grade`, tooltips, quality inheritance |
| `minigame.{preparation,brewing,distillation}` | Pure, deterministic simulations: no Minecraft imports, `SimData` long-array encoding, shared by client prediction and server scoring |
| `data` | Datapack definitions (reagent profiles, brew profiles, effect policies, exclusions), strict JSON readers, precedence (`ProfileSelector`), atomic reload (`BrewingDataLoader`) |
| `recipe` | Infusion, distillation and reclamation recipe types, adapters, incremental destination catalogs, direct stable-key replay, compatibility reporting, coverage for replacement mode, container-only finishing proof and the tipped-arrow override |
| `batch` | Schema-2 job ledgers: reserved stacks, fuel, owed remainders, output snapshots, recovery packaging |
| `session` | Server sessions: controller locks, the tick clock, input validation, sealing and resuming |
| `block`, `blockentity`, `menu`, `item` | Stations and their bounded inventories, capabilities, persistence and synced views |
| `network` | Versioned, bounded packets: intent from clients, state and results from the server |
| `client` | Screens, renderers, particles, sounds, key mappings, client prediction, the synced server catalog, guided practice and JEI plugin (everything client-only) |
| `compat`, `integration` | Jade (found by annotation); Smithing's exclusion IMC (reached only after a `ModList` check). The smithed paddle and the Tanning strap are conditioned data recipes; Enchanting and Jewelry need no code (coexistence). `PotionMods` names the four third-party potion mods whose data lives under `compat/` (no class of theirs is referenced) |
| `api` | Small public API and events, including additive API-v1 incremental catalog pages and exact stable-key resolution; the legacy bounded `plans(...)` query remains unchanged |
| `guide` | Versioned player Notebook state: preferences, explicit-capacity favourites, pinned authoritative costs and 32 compact journal entries |
| `command`, `advancement`, `config`, `event`, `mixin`, `registry` | As named |

## ADR-1: Where quality is applied (spec 11, 12)

**Decision.** A finished potion keeps its canonical contents exactly as the brewing operation returned them. This mod
adds one compound, `immersive_brewing` (schema 1), holding extraction, control, quality, lineage token, purification
count, brewer UUID and name, refiner, label and note. Every effect path resolves *effective* contents through
`PotionContentsService`, which reads canonical contents plus metadata and never writes, so resolution is idempotent.

Hooks, verified against the 47.4.23 bytecode (`javap -c` on the mapped jar) and Forge sources:

| Path | Vanilla code | Hook | Why not a Forge event |
|---|---|---|---|
| Drinking | `PotionItem.finishUsingItem` calls `PotionUtils.getMobEffects(stack)` once, server side | `PotionItemMixin` `@Redirect` of that call | `LivingEntityUseItemEvent.Finish` fires after the effects are applied; `MobEffectEvent.Applicable` can only allow or deny |
| Drinking time | `LivingEntity.startUsingItem` asks `ForgeEventFactory.onItemUseStart` on both sides | `PotionUseEvents` (`LivingEntityUseItemEvent.Start#setDuration`), no mixin | Forge event exists |
| First-person use animation | `ItemInHandRenderer.applyEatTransform` asks the stack for its fixed use duration | `ClientDrinkTiming` observes the final client `Start` event at lowest priority; `ItemInHandRendererMixin` redirects the animation denominator to that observed duration for the matching stack | The renderer does not expose a Forge event for this denominator |
| Splash | `ThrownPotion.onHit` reads `getMobEffects(stack)` once and passes it to `applySplash` (distance attenuation) | `ThrownPotionMixin` `@Redirect` | No Forge hook; `ProjectileImpactEvent` can only cancel |
| Lingering | `ThrownPotion.makeAreaOfEffectCloud` sets the potion id; `AreaEffectCloud.tick` maps `potion.getEffects()` to a quarter and adds custom effects verbatim | `ThrownPotionMixin` `@ModifyArg` on `addFreshEntity` tags the cloud's persistent data; `AreaEffectCloudMixin` `@Redirect` of `Potion.getEffects()` in `tick` | No hook |
| Tipped-arrow crafting | `TippedArrowRecipe.assemble` builds a fresh stack from potion id and custom effects | Data override of `minecraft:tipped_arrow` with `immersive_brewing:tipped_arrow` (`BrewingTippedArrowRecipe` extends the vanilla class and copies the metadata) | `ItemCraftedEvent` fires after assembly and not for the result preview |
| Arrow impact | `Arrow.setEffectsFromItem` stores the potion; `doPostHurtEffects` maps `potion.getEffects()` to an eighth | `ArrowMixin`: `@Inject` at the tail of `setEffectsFromItem` (tag), `@Redirect` in `doPostHurtEffects`, `@Inject` at the return of `getPickupItem` (restore) | No hook |
| Dispensers | Splash/lingering behaviours call `ThrownPotion.setItem`; tipped arrows call `setEffectsFromItem` | Covered by the hooks above | n/a |

Rules that follow from the hooks:

- Quality scales only a potion's **registered** effects (`Potion.getEffects()`). `CustomPotionEffects` are foreign data
  and stay exact on every path; this keeps drinking, splash, cloud and arrow consistent, because vanilla applies cloud
  and arrow custom effects verbatim.
- Only finite durations of effects an effect policy names scale; instant effects, infinite durations and unknown modded
  effects are metadata-only. A potion whose own effects mix beneficial and harmful categories (Turtle Master) is a
  bundle: metadata and drinking time only.
- Effective instances are fresh: they go through the instance's own NBT round trip, which keeps amplifier, ambient,
  particles, icon, hidden-effect chain, factor data and Forge curative items. Registry potions are never mutated.
- Only the vanilla potion, splash, lingering and tipped-arrow items are hooked. Other potion-like items keep their own
  behaviour; their quality is recorded but changes nothing (spec 11.3).
- Clouds and arrows carry the metadata in `getPersistentData()` (saved with the entity), which makes them the "tagged
  entities" the spec requires; they never hold a second active base list.

**Evidence.** `PotionPathGameTests` (13 tests, all passing in the first M0 run): drinking at quality 0/50/100,
untagged vanilla, use time 36/32/28, instant health once, Turtle Master bundle, idempotent resolution and save/load,
custom effects exact, splash at two distances, instant damage once, lingering cloud quarter once, tipped-arrow crafting,
arrow eighth and pickup, dispenser throw.

**Consequences and limits.**

- The first-person animation now follows the final client-side duration observed after Forge's use-start listeners.
  This is designed to compose rather than apply quality twice. Runtime coexistence with other drink-speed mods has not
  been claimed without the separate compatibility matrix.
- Another mod that redirects the same calls will fail loudly at load (`defaultRequire = 1`).
- If another datapack replaces `minecraft:tipped_arrow`, arrows crafted from graded potions are ordinary; the server logs
  this at start (`CommonEvents.checkTippedArrowOverride`) and the report command lists it.

## ADR-2: Clock policy (spec 21.2)

**Decision.** All three games advance in fixed 50 ms logical steps, exactly one per server game tick. Immersive Jewelry
uses 20 ms steps; brewing reads slower physical cues (heat inertia, foam, condensate), so one step per tick is enough and
lets the server's own tick, not the client's frame rate or wall clock, authorise every elapsed step.

- The client records its input every client tick and sends batches of at most four frames tagged with session id,
  sequence and step. Clients never send outcomes.
- The server simulates step *k* a playout buffer after it was due (three ticks by default, growing with the player's
  measured latency up to `maxInputBufferTicks`). A frame that has not arrived by then is neutral: stirring stops,
  settings (draft, valves, receiver) stay where they were. The buffer is also the bounded grace before finalisation;
  it never adds scored time.
- The client predicts with the same pure simulation and reconciles to the server's periodic snapshots. When the server
  falls behind (low TPS, a paused integrated server), the client stops at most a few steps ahead: the brew slows down
  instead of skipping a stage.
- While the controller has the help overlay open the session is paused: the client stops stepping and sends a pause,
  and the server's clock stops when it arrives (count-in included). Closing the help mid-run counts in again on the
  client; the resume then says which step the client simulates next and after how many ticks, and the server
  restarts its clock from that: the step falls due that many ticks after the resume arrives, plus two ticks of slack,
  and is simulated a playout buffer later, however late the pause was handled. Repeated, impossible or flooding
  toggles are violations.
- GameTests drive sessions tick by tick, so they need no real-time clock (Enchanting's `SessionClock` problem does not
  arise).

## ADR-3: Persistent ownership (spec 20)

**Decision.** Every station keeps a versioned job ledger in its block entity. Starting a job *moves* the reserved stacks
(bottles, reagents, kits) out of the public slots into the ledger and moves the needed fuel charges from the station's
charge counter into it, so the ledger is their only owner. While a job is reserved, running, sealed or held for recovery,
the bottle rack and ingredient tray refuse insertion, so cancelling before the reaction starts can always put every
stack back where it came from.

- Completion is one server-thread transition: the adapter's authoritative result (checked against the snapshot taken
  at start) replaces the bottles in the rack, remainders go to the station's by-product slots (capacity checked at
  reservation), the ledger closes and only then do events fire. A second completion finds no running job.
- Sealing stores the full simulation state, parameters and seed; resuming never rerolls.
- A job snapshots its plan, simulation parameters and caps when it starts, so no configuration change invalidates it
  (spec 23.1 seals only on changes that would); a config edit just rebuilds the plan and effect-policy caches.
- Breaking a station with an open ledger drops one Recovery Batch item holding the ledger, plus the unrelated
  inventory and the block itself. A job still counting in is packaged as it is rather than released, so the drops are
  the same whether the block's loot is computed before its removal (explosions, `destroyBlock`) or after (a player
  breaking it). Installing the batch at an empty compatible station consumes the item and transfers the ledger
  atomically: a sealed job resumes, a passive job carries on, and a reservation that never started is released into
  that station (cancel before start).
- Minecraft's save is not a transaction across chunks and player files: the guarantee is that every material has one
  owner at every save point, not protection against filesystem rollback.

Ledger schema 2 adds the operation kind needed by quick finishing and retains the bounded schema-1 fields. Schema-1
ledgers load through the same reader. A schema newer than 2, an invalid older marker, or a ledger naming an item that is
not in the current registry becomes a held recovery job whose original compound is saved byte-for-byte. It is never
partially interpreted and rewritten by this version.

## ADR-4: 1.1 staging, transport and journal authority

**Decision.** Convenience features do not become alternate authorities or score sources.

- `BatchStaging` builds a private draft from the open Kettle, the player's 36 ordinary inventory slots and at most one
  explicitly selected carrier. The carrier contributes exact bottle inputs only; it is not guessed as a source of
  reagents, fuel or tools. The draft checks canonical bottles, reagent/kit choice, fuel, paddle durability,
  remainder space and serving limits without mutation. Commit occurs once on the server thread only if the inventory
  revision still matches.
- The Apothecary Rack and Batch Carrier store independent exact `ItemStack`s. Filters/templates are ghosts, not owned
  ingredients. Simulation is non-mutating; packing commits each known remainder. Neither container changes quality,
  lineage, labels or foreign tags.
- Quick finishing is permitted only when every resolved step is a proven vanilla-winner container conversion and the
  canonical potion contents remain equivalent. It preserves the complete source tag/capabilities, consumes the live
  plan's costs, returns remainders, snapshots its loaded-world duration and produces no new craftsmanship score or
  completion milestone.
- Journal repeat and pin requests carry stable plan identities as hints. The server directly replays current authored
  segments and recalculates costs. Repeat is accepted only for the currently open nearby Kettle; saved input identity
  and quantity do not grant access to an unopened inventory.
- Player guide data version 2 adds bounded history and view preferences. Known older data receives defaults; a payload
  with a newer data version is not migrated. Favourite capacity requires an explicit replacement target.
- Guided generic practice is client-only. Favourite practice first asks the server to re-resolve the exact route and
  build fresh Kettle parameters/profile display with a fresh seed; the returned description then opens a client-only
  practice. Neither path creates a productive server session, output, advancement or result event.

These paths deliberately reuse existing quality and milestone rules; 1.1.0 does not add a second score curve, an
automation score or new progression rewards.

## Third-party potion mods (spec 18)

Apothecary 1.5.2, Potions, Potions And More Potions 1.0.4, PotionsMaster 0.6.0 and RPG Potions 1.2.0 (jars in `libs/`,
compile-only, joined to the dev runtime by Gradle flags) all brew through Forge's brewing registry, so the built-in
adapter already performs their steps. Their support is data, generated by `tools/generate_data.py` from their source:
plans for every recipe they register (Apothecary's config defaults; its six that need Iron's Spellbooks stay contextual),
reagent profiles, effect policies and Alembic recipes, all under `compat/<modid>/` and conditioned on `forge:mod_loaded`
(definition files accept Forge conditions for this). Every plan is still replayed through the live registry, so a
changed config or another mod answering first leaves a plan reported and never offered.

- **Loose matches.** A plain Forge `BrewingRecipe` with a vanilla `Ingredient` input compares items only, so RPG
  Potions' Awkward-based recipes also turn Water or any drinkable potion into theirs at a stand. `RegistryQuery` finds
  the recipe that answered and the Forge adapter refuses a match against a declared input that names other contents
  (`NbtUtils.compareNbt`, partial); subclasses with their own `isInput` decide for themselves. The Kettle brews what a
  recipe declares, as JEI shows it; discovery reports such matches as loose.
- **Effects.** A policy lists an effect only after its mechanic was read: attribute, per-tick or event effects that
  just need to be active scale like vanilla ones. Instant effects never scale; Leap (removes itself after its jump),
  Smoke (RPG Potions spawns splash and lingering smoke from the bottle's own duration) and the Iron's Spellbooks mana
  effects are metadata-only.
- **Plan search.** Destination search is a shortest-first incremental graph traversal on the server thread, cached for
  32 canonical inputs. The scheduler spends at most 256 edges per server tick and 64 per active request slice. One
  catalog stops at 32,768 examined edges or 8,192 pending nodes, searches at most the configured eight transformation
  steps, and reports incomplete/budget-limited results rather than treating omitted alternatives as unsupported.
  Contextual adapters are queried from the currently open tray and are intentionally not promised to be enumerable.
  A selected stable key is replayed directly against current recipes, independent of page and tray-list limits.
- **Notebook.** The recipe, favourite and Compatibility pages consume the authoritative server report in atomic pages
  of at most 256 entries. Statuses distinguish effect-scaling support, metadata-only output, exclusion and validation
  failure. Client recipe data supplies presentation, never recipe authority.
- **Additive API v1.** `catalog(...)` advances bounded discovery work and returns 24-route pages with generation,
  incomplete, budget-limited and omitted-alternative fields. Callers schedule repeated server-thread calls instead of
  spinning. `resolve(...)` validates one stable key directly. The existing `plans(...)` method retains its legacy
  512-route cap and behavior for existing callers.

## Deviations from the spec

| Spec | Deviation | Effect | Reason |
|---|---|---|---|
| 8.1 heat model | Two-stage lag: a burner value follows the draft target and the liquid follows the burner (same form as the spec's formula with the target replaced by the burner) | High draft held too long overshoots, as spec 7 describes | The spec's single-stage form approaches the target exponentially and can never overshoot |
| 8.1 "must not add the same error penalty twice" | Signature trouble (foam, sediment, ...) is driven only by circulation and additions; heat errors are scored only by the thermal term | Visual heat effects (boil size, shimmer) follow heat without costing circulation credit | Avoids counting one heat mistake in two terms |
| 8.2 participation | A phase's thermal and circulation credit counts only if the player gave any input during it or in the 3 s before it began; no input at all gives C = 0 | A default setting that happens to fit one phase earns nothing | The spec's "low activity must not be accidentally rewarded" |
| 14 replacement guard | `@Inject` at the head of the private `isBrewable` instead of a `@Redirect` of its call in `serverTick` | Same behaviour: `isBrewable` has exactly one caller | Simpler and immune to call-site drift |
| 11.2 drinking time | Baseline is the duration the use event carries (32 for vanilla), shifted by `round(4 - 0.08Q)`; a client render redirect uses the final observed event duration for the first-person animation | Intended to compose with other use-time listeners without applying quality twice; other-mod runtime behavior is not claimed until matrix-tested | The gameplay duration remains event-based; only the renderer denominator needs the client mixin |
| 16.7 effect-quality handlers | Handlers are consulted on `DRINK` and `SPLASH` only; on `LINGERING` and `ARROW` other mods' items stay metadata-only | A handler declaring those paths is not called there | Clouds and arrows keep only the potion, not the item, so there is no stack to hand a handler; documented in `docs/API.md` |
| 5.1 water filling | The Kettle's fluid handler reports no tanks and only fills whole bottles | Pipes and buckets fill bottles; overlays show no reservoir | The Kettle stores no water; a tank would invite overlays and pipes to treat it as storage |
| 16.1 brew profiles | A profile sets phases, bands, disturbances and inertia; phase timing comes from the plan's difficulty tier (spec 8.3), not the profile. `presentation.accent` and the bounded `steam`/`foam`/`bubble`/`glint`/`mote` vocabulary are synced and rendered | Packs tune a reaction's feel and safe presentation, not its length; accessible palettes and particle/reduced-motion settings remain authoritative | One timing rule for every plan keeps durations within the spec's caps while presentation stays cosmetic |
| 17 Smithing durability | No durability multiplier of our own: Smithing's `ItemStack.hurt` hook scales paddle wear from its quality data | Tested: a masterwork smithed paddle wears less | The spec forbids a second, conflicting multiplier |
| Plan file names | `ControllerLock`, `InputValidator`, `GraceWindow` live in `SessionManager`/`ActiveSession`; `StationItemHandler`/`StationFluidHandler` are `AutomationHandler` and the Kettle's `WaterHandler`; `integration/tanning` is data only | None | Fewer, smaller classes with the same responsibilities |

## Deferred roadmap

The authoritative 1.1.0 recommendations leave the following explicitly deferred:

- **P2 / optional later 1.1.x:** direct drinking from a carrier, shareable formula cards, reagent jars, unattended
  Kettle production, additional Alembic salvage uses/group sizes, scripting bridges, physical loading/collection,
  broader rack/apparatus cosmetic variants and newly advertised mod adapters after their complete journey matrices.
- **P3 / future expansion:** native blending, coatings, herbalism, fermentation, solvents, multidose flasks, incense,
  village commissions, simultaneous cooperative brewing, full world-space/VR brewing and other loaders.

They are not hidden 1.1.0 features. Existing extension points remain the adapter registry, reagent/brew/effect data,
exact-stack metadata and committed result events.
