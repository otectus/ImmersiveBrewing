# Immersive Brewing - Architecture

Decisions, their evidence and every deviation from `docs/Immersive_Brewing_Development_Spec.md` (design 1.0) and, for
the hands-on workshop, from `Immersive_Brewing_Hands_On_Overhaul_Handoff.md` (ADR-5). The
dependency-ordered build plan is `docs/IMPLEMENTATION_PLAN.md`; datapack formats are in `docs/DATAPACKS.md`, the Java
API in `docs/API.md`, configuration scope/snapshots in `docs/CONFIGURATION.md`, and test commands/evidence in
`docs/TESTING.md`.

Baseline: Minecraft 1.20.1, Forge 47.4.23, Java 17, ForgeGradle 6.0.54, official mappings (versions only in
`gradle.properties`). The current tree is a development version, not a release claim.

## Package map

| Package | Responsibility |
|---|---|
| `potion` | `BrewingMetadata` (the one compound this mod writes on a stack), `PotionContentsService` (canonical vs effective contents), `EffectPolicies`, `QualityMath`, `Grade`, tooltips, quality inheritance |
| `workshop` | The hands-on domain (ADR-5): pure, deterministic processes (`KettleProcess`, `MortarProcess`, `AlembicProcess`, `FinishingProcess`), reaction families, part volumes and their geometry per facing, bounded tool tracks, leases, workshop profiles and their resolution. No client code |
| `interaction` | Server side of the working view: presence (who is working where), intent validation and budgets, rack actions |
| `minigame.{preparation,brewing,distillation}` | The 1.1 simulations: pure and deterministic, `SimData` long-array encoding. Kept so batches saved by 1.1 finish through their original interface |
| `data` | Datapack definitions (reagent profiles, brew profiles, effect policies, exclusions), strict JSON readers, precedence (`ProfileSelector`), atomic reload (`BrewingDataLoader`) |
| `recipe` | Infusion, distillation and reclamation recipe types, adapters, incremental destination catalogs, direct stable-key replay, compatibility reporting, coverage for replacement mode, container-only finishing proof and the tipped-arrow override |
| `batch` | Job ledgers (schema 3 with a versioned process payload; schema 2 for 1.1 batches): reserved stacks, fuel, owed remainders, output snapshots, recovery packaging |
| `session` | The 1.1 server sessions (controller locks, the tick clock, input validation, sealing and resuming), used only to finish migrated batches |
| `block`, `blockentity`, `menu`, `item` | Stations and their bounded inventories, capabilities, persistence and synced views |
| `network` | Versioned, bounded packets: intent from clients, state and results from the server |
| `client` | Screens, renderers, particles, sounds, key mappings, the synced server catalog and JEI plugin (everything client-only). `client.workshop` is the working view: part picking and keyboard cycling, the local hand, camera framing, first-use teaching and the client-only practice bench |
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

Since 1.2 this governs only the 1.1 sessions that finish batches saved by 1.1. Hands-on work has no session clock: the
station's own tick advances it (ADR-5).

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

## ADR-5: The hands-on workshop (1.2)

**Decision.** New work happens at the apparatus. The 1.1 minigame screens no longer start anything; a transparent
working view lets the player pick the station's parts and sends intents, and the block entity owns the work. The
quality formula, recipes, adapters, potion data, items, carriers, racks and recovery are unchanged.

**Diegesis.** The pinned Diegesis source (commit `c60c89b`) was evaluated as the handoff asks. It has no keyboard target
navigation, always moves the camera, ticks station logic only while a station is in use, and has no published artifact
this toolchain can resolve. Following the handoff's fallback, Brewing owns a small local substitute: server-defined part
volumes per facing (`WorkshopParts`, `WorkshopGeometry`) and the client picking, keyboard cycling and optional camera
framing in `client.workshop`. There is no runtime or build dependency. The `presentation` field of workshop profiles
is reserved for a future presentation layer.

**Single tick owner.** Each process is advanced exactly once per server tick by its block entity
(`StationBlockEntity.workshopTick`), never by packet arrival. Input only moves a held tool's track or sets a control;
`ToolTrack` credits bounded movement per tick (a jump over 7 px or a step over 3 px earns nothing, at most 6 px is banked,
a 10-tick gap ends a stroke, and the rate is smoothed so 10 Hz and 20 Hz senders earn the same). The Mortar adds a
token bucket on work per tick, so frantic input is not faster than steady input.

**Intents.** `WorkshopIntentC2S` carries the dimension, position, job id, a sequence number, part, action, argument,
model-space coordinates and flags (contact, off hand, batch, effort). `WorkshopIntents` checks, in order: alive, same
dimension, increasing sequence, the token budget (40 actions per second with a refill of 2 per tick; tool samples 25
with 1.25 per tick), finite and bounded values, presence (a working view opened by a real block use), reach
(`maxStationDistance` + 1), `mayInteract`, and line of sight to the part's server volume. The station then checks state,
leases and resources. 60 violations close the view. Every intent ends ACCEPTED, REFUSED (with a reason the player sees)
or IGNORED, and a refused intent changes nothing.

**Leases and cooperation.** Tools and controls are leased per part (`Leases`, 60 ticks, renewed by use). Other players
can work the free parts of the same batch at the same time; a held part names its holder. Productive actions add the
player to the batch's participants. The original brewer keeps the credit; helpers keep an attended batch alive.

**Attended batches and safe stopping.** A running physical batch advances while a participant is within
`participantRange` (default 8 blocks). After 40 ticks with none in range it seals itself; the Kettle's lid or `L` seal
it by hand, and the lid, clamp or bowl reopen it. Sealing shuts the burner, stills the liquid and resets the tool tracks; every other value is stored and does not
change while sealed. Reopening continues from exactly that state. A RUNNING physical ledger loads SEALED (no offline
progress). Breaking a station flushes the live process into the Recovery Batch, and installing it restores the batch
sealed. A Kettle batch can be withdrawn with everything returned only before its first dose; an Alembic charge only
before it begins to condense (its coolant is returned).

**Persistence.** Ledger schema 3 adds an opaque, versioned `process` payload (at most 32 KiB) holding the resolved setup
and the live state. The payload is replaced only after a successful decode; one that this version can not read holds the
job (`recovery.immersive_brewing.process_unreadable`) and keeps its NBT unchanged. Jobs saved by 1.1 stay schema 2 and
are finished through the original session path (`LEGACY_RESUME` at the route book stand). That path is migration-only:
nothing can create a new 1.1 job.

**Chemistry and scoring.** Q = round((E + 3C) / 4) as before. E comes from the Mortar's evaluation when the batch is
scraped into the cup (texture against the endpoint, fines, clumping and overwork). C comes from the Kettle's conditions
per step: heat against the profile's ideal band, the family's trouble (agitation-driven and heat-driven trouble are
separate, so one mistake is never charged twice), doses tipped into cold liquid and the rest before bottling.
Purification scores the routing of heads, heart and tails and the steadiness of heat and coolant. At chemical
completion the Kettle replays the plan's adapters once, holds the batch on a veto, freezes quality and owes remainders,
paddle wear, events, experience, milestones and the journal entry once. Bottling only moves those finalized outputs; a
full position pauses it.

**Container finishing.** Quick finishing stays a proven, container-only transformation with no score. It is now a short
physical step: the modifier is tipped into the finishing fitting, the fitting is closed and the bottles settle for the
server's finishing time (`brewing.finishingSeconds`), fixed when the batch is set out.

**Presentation.** Snapshots go to the players tracking the chunk at most every 3 ticks (immediately for discrete
changes), quantized to bytes. One apparatus model is drawn from them for idle, focused and spectator views; only the
local player's own held tool follows the local hand. Left-handed mirroring and the focus camera are presentation only.
The hotbar, its status bars and the crosshair are hidden while the working view is open.

**Practice.** The server resolves a practice setup and sends only its description (`OpenPracticeS2C`); the client runs
the same domain classes locally at an idle station of that kind (`PracticeBench`). Practice sends no work to the server,
opens no real drawer or book and changes nothing.

**Performance.** `PerformanceGameTests` tends 32 kettles with 8 observers for 200 ticks and records the workshop's
share of the server tick, snapshot counts and sizes and notices per player (`docs/TESTING.md`). The dense client smoke
(`-Pvariant=dense`) measures frames with 0, 1, 8 and 32 tended kettles.

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
| Handoff 12 Diegesis | A local part-volume and working-view substitute instead of the Diegesis library | Same interaction model; keyboard navigation and a no-camera mode are available | Diegesis lacks both and has no resolvable artifact (ADR-5) |
| Handoff, keyboard tool work | Holding the Work key moves the held tool in a steady circle around the work point at a speed set by the effort (gentle, normal, firm); the arrow and page keys move the work point | Keyboard play reaches the same work rates as the mouse with single keys | No key chords, and the server's bounds apply to both |
| Handoff, datapack reload | `/reload` does not seal hands-on batches; their setup is in the payload and completion revalidates the recipe | A reload mid-batch changes nothing; unresolvable work is held | The handoff's "invalidate only what can no longer be validated" |
| Assistance | The time multiplier has no hands-on counterpart; the band multiplier widens the Kettle's ideal heat band | Assistance keeps the full scoring range | Hands-on work has no cue clock to slow down |

## Deferred roadmap

The authoritative 1.1.0 recommendations leave the following explicitly deferred:

- **P2 / optional later:** direct drinking from a carrier, shareable formula cards, reagent jars, unattended Kettle
  production, additional Alembic salvage uses/group sizes, scripting bridges, broader rack/apparatus cosmetic variants
  and newly advertised mod adapters after their complete journey matrices. (Physical loading and collection arrived
  with the hands-on workshop.)
- **P3 / future expansion:** native blending, coatings, herbalism, fermentation, solvents, multidose flasks, incense,
  village commissions, simultaneous cooperative brewing, full world-space/VR brewing and other loaders.

They are not hidden 1.1.0 features. Existing extension points remain the adapter registry, reagent/brew/effect data,
exact-stack metadata and committed result events.
