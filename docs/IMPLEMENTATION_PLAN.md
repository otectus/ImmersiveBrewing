# Immersive Brewing - Implementation Plan

**Prepared:** 10 October 2026 from `docs/Immersive_Brewing_Development_Spec.md` (design 1.0) and a source audit of the
workspace, the four sister mods and Forge 1.20.1-47.4.23. This is the dependency-ordered plan the spec's section 0 asks
for. Nothing below has been built yet; every "verified" claim names the file it was read from.

## 1. Workspace audit (spec milestone 0, part 1)

| Item | Finding |
|---|---|
| Existing project | `/home/otectus/Projects/ImmersiveBrewing` contains only `docs/Immersive_Brewing_Development_Spec.md` (1540 lines). No source, build files or git repository (`/home/otectus/Projects/.git` is an empty directory, not a repository). |
| Target | New project, so the spec's family baseline applies: Minecraft 1.20.1, Forge 47.4.23, Java 17, ForgeGradle 6.0.54, official mappings. No discrepancy to record. |
| Toolchain on disk | OpenJDK 17.0.19. Gradle 8.8 wrapper in every sister (`ImmersiveJewelry/gradle/wrapper/gradle-wrapper.properties`). Forge 1.20.1-47.4.23 userdev, sources and mapped class jar are already in `~/.gradle/caches/forge_gradle`, as are JEI 15.20.0.135 and Jade (`curse.maven:jade-324717:6271651`). |
| Sister snapshots | Smithing `main` @ `0c5e781f` (the spec's commit), Tanning `main` @ `9101220c` (spec's), Jewelry `main` @ `c6294f0e` (spec's), Enchanting `main` @ `a562ab57` (the spec inspected `035f25a4`; the local head is newer). All four `gradle.properties` now declare `mod_version=1.1.0`, not the `1.0.0` the spec observed. |
| Source lookup caveat | `.mcmod-tools/find_api.py` reports NOT FOUND for every vanilla class because the cache holds no `*_mapped_official-sources.jar` (ForgeGradle never decompiled). Use `javap -p -cp <mapped class jar>` for signatures and the userdev jar's `patches/net/minecraft/**.patch` for Forge hook placement; both were used for this plan. The Forge-only classes are in `forge-1.20.1-47.4.23-sources.jar`. |

## 2. Verified hook strategy (spec milestone 0, part 2; spec 12, 14, 15)

Evidence: `forge-1.20.1-47.4.23-sources.jar` (Forge classes), `forge-1.20.1-47.4.23-userdev.jar!/patches/...` (Forge
patches to vanilla), `javap` on `forge-1.20.1-47.4.23_mapped_official_1.20.1.jar` (vanilla signatures).

### 2.1 Brewing registry and the vanilla stand

- `BrewingRecipeRegistry.getOutput(input, ingredient)` returns the first non-empty `IBrewingRecipe` output and refuses an input stack whose count is not 1. `getRecipes()` is an unmodifiable list; `VanillaBrewingRecipe` is added first (static initializer) and delegates to `PotionBrewing.mix`. `BrewingRecipe` (the finite Forge form) exposes `getInput()`, `getIngredient()`, `getOutput()`; arbitrary `IBrewingRecipe` implementations expose only `isInput`, `isIngredient`, `getOutput`.
- `PotionBrewing.POTION_MIXES` (`f_43494_`) and `CONTAINER_MIXES` (`f_43495_`) are private; `Mix.from`/`to` are public `Holder.Reference` after the Forge patch. **Decision:** no access transformer. Vanilla coverage ships as built-in infusion plans (data) and is verified at runtime by `getOutput` queries; third-party coverage comes from finite `BrewingRecipe` enumeration plus bounded (potion x ingredient) queries (spec 15.3).
- Brewing stand (`BrewingStandBlockEntity.java.patch`): `isBrewable` (private static, `m_155294_`) diverts to `BrewingRecipeRegistry.canBrew`; `serverTick` decrements `fuel` and sets `brewTime = 400` as soon as `isBrewable` is true; `doBrew` is guarded by `ForgeEventFactory.onPotionAttemptBrew` (`PotionBrewEvent.Pre`, cancellable, operates on a copy list) only at completion; `canPlaceItem` uses the registry. `FUEL_USES` is a public constant (expected 20; read it at startup to validate `fuelChargesPerBlazePowder`). Reagent remainders use `hasCraftingRemainingItem()` / `getCraftingRemainingItem()`.
- **Replacement-mode guard (spec 14):** one mixin, `BrewingStandBlockEntityMixin`, `@Redirect` of the `isBrewable` invoke inside `serverTick`, returning false when the stand's three bottles plus ingredient form a covered transformation. `PotionBrewEvent.Pre` cancellation is the backstop. This is the only way to stop fuel and timer consumption; Forge offers no event there.

### 2.2 Effect application paths (spec 12.2)

| Path | Vanilla code (verified) | Forge hook available? | Chosen hook |
|---|---|---|---|
| Drinking | `PotionItem.finishUsingItem` iterates `PotionUtils.getMobEffects(stack)` | `LivingEntityUseItemEvent.Finish` fires after effects are applied; `MobEffectEvent.Applicable` (in `canBeAffected`) can only allow/deny and `MobEffectInstance` has no duration setter | Mixin `PotionItemMixin`: `@Redirect` the `getMobEffects` call to `PotionContentsService.effectiveEffects(stack)` |
| Use time | `LivingEntity.startUsingItem` calls `ForgeEventFactory.onItemUseStart(this, stack, stack.getUseDuration())` on both sides | Yes: `LivingEntityUseItemEvent.Start#setDuration` | Event handler only (no mixin). Baseline read at runtime from `new ItemStack(Items.POTION).getUseDuration()` (expected 32). Cosmetic caveat: the first-person drink animation still uses the item's 32-tick duration. |
| Splash | `ThrownPotion.onHit` reads `getMobEffects(itemstack)`, then `applySplash` (distance factor via `mapDuration`) or `makeAreaOfEffectCloud(stack, potion)` | No Forge patch on `ThrownPotion`; `ProjectileImpactEvent` can only cancel/redirect | Mixin `ThrownPotionMixin`: `@Redirect` of `getMobEffects` in `onHit`; `@Inject` in `makeAreaOfEffectCloud` before `addFreshEntity` to copy brewing metadata into the cloud's `getPersistentData()` |
| Lingering | `AreaEffectCloud.tick` builds `potion.getEffects()` mapped `/4` plus `this.effects` verbatim | No patch | Mixin `AreaEffectCloudMixin`: `@Redirect` of `Potion.getEffects()` in `tick` to the resolved list from persistent data; vanilla then applies `/4` once. Custom effects stay verbatim (documented). |
| Tipped arrow impact | `Arrow.setEffectsFromItem(stack)`; `doPostHurtEffects` applies `potion.getEffects()` mapped `/8` plus custom effects verbatim | No patch | Mixin `ArrowMixin`: `@Inject` tail of `setEffectsFromItem` copying metadata to persistent data; `@Redirect` of `Potion.getEffects()` in `doPostHurtEffects`; optional inject in `getPickupItem` to restore metadata on pickup |
| Tipped arrow crafting | `TippedArrowRecipe.assemble` builds a fresh `tipped_arrow` with `setPotion` + `setCustomEffects`, dropping foreign NBT | `ItemCraftedEvent` fires after assembly and not for the preview | Datapack override `data/minecraft/recipes/tipped_arrow.json` -> `immersive_brewing:tipped_arrow` (serializer whose recipe extends the vanilla behaviour and copies brewing metadata). Confirm the vanilla recipe id in M0. |
| Dispensers | Splash/lingering dispense creates a `ThrownPotion` with `setItem(stack)`; tipped arrows call `Arrow.setEffectsFromItem` | n/a | Covered by the two mixins above; verified by GameTest |
| Rebrewing / modifiers | `BrewingRecipeRegistry.getOutput` on canonical stack | n/a | `PotionContentsService.canonical(stack)` strips only this mod's compound before querying |

Resolution rules: `PotionContentsService.effectiveEffects` returns fresh `MobEffectInstance`s (full constructor preserving
amplifier, ambient, visible, showIcon, hidden effect and factor data; Forge curative items copied), scales only
finite durations (`isInfiniteDuration()` preserved) of effects the active effect policy allows, never mutates
`Potion.getEffects()`, and is idempotent because it reads canonical contents plus metadata and never writes.
Persistent-data copies on entities are the "tagged entities" the spec requires.

Mixin budget: five classes (`PotionItemMixin`, `ThrownPotionMixin`, `AreaEffectCloudMixin`, `ArrowMixin`,
`BrewingStandBlockEntityMixin`). Each gets a GameTest regression (spec 19). Smithing's build wiring
(`mixingradle 0.7.38`, `MixinConfigs` manifest attribute, refmap) is the template.

### 2.3 Clock and session model

Reuse the family's lockstep model (Jewelry `minigame/StepClock.java`, `SessionManager.java`; Smithing
`minigame/SessionTiming.java`; Enchanting `session/SessionClock.java`) with the spec's deliberate change: one logical
step per server tick (50 ms), not Jewelry's 20 ms. Clients send only input frames; the server replays the pure
simulation and owns every score and stack. Record this as ADR-2 in `docs/ARCHITECTURE.md`.

## 3. Approach and architecture

- **Baseline:** copy the Jewelry/Smithing build skeleton (`build.gradle` with ForgeGradle 6.0.54, mixingradle,
  source sets `main`/`test`/`testShared`/`gameTest`/`smokeTest`, JEI/Jade/optional sister deps, `-PnoSmithing`-style
  toggles, `processResources` expansion of `mods.toml` from `gradle.properties`).
- **Packages** (spec 19, sister naming): `registry`, `block`, `blockentity`, `item`, `recipe`, `data`, `batch`,
  `minigame.{preparation,brewing,distillation}` (pure, `StrictMath`, no Minecraft imports), `session`, `potion`,
  `network.packet`, `client.{screen,render,compat.jei}`, `compat.jade`, `integration.{smithing,tanning}`, `api.event`,
  `command`, `guide`, `config`, `advancement`, `mixin`.
- **Authoritative data:** every station has a persisted `JobLedger` (versioned codec like Smithing
  `workpiece/WorkpieceCodec.java`); the client receives a small `StationView`. Block entity update tags are never
  empty (Smithing MODMAP decision on `ClientboundBlockEntityDataPacket`).
- **Datapacks from the first slice:** infusion/distillation/reclamation are real `RecipeType`s under
  `data/<ns>/recipes/` (sister decision; syncs to clients and JEI); profiles and policies are
  `SimpleJsonResourceReloadListener`s with Enchanting's precedence layers (`data/ProfileSelector.java`) and
  keep-previous-on-failure rebuild (`data/EnchantmentProfileManager.java:48-63`), synced to clients like Jewelry
  `data/JewelryDefinitions.java` on `OnDatapackSyncEvent`.
- **Feedback:** station messages through persistent `StationNotices` + `StationNoticePacket` (Smithing/Tanning
  `client/StationNotices.java`), never action-bar only (series audit brief, section 2).
- **Assets:** editable `art/brewing_art.py` + `tools/generate_textures.py --check/--write`, `art/brewing_audio.py`
  + `tools/generate_sounds.py`, as in Jewelry; vanilla-style pixel art (spec 24).

## 4. Ordered steps

Each step lists the files it creates or changes. Steps inside a milestone are in dependency order.

### M0 - Repository, build and hook spike

Gate: clean `compileJava`, a GameTest proving canonical potion data and the resolved duration survive
drink -> splash -> lingering -> arrow with no double application, and ADRs written.

1. `git init`; `.gitignore` (copy Smithing's, includes `run-gametest/`, `src/generated/resources/.cache/`); `LICENSE` (GPL-3.0, as the family).
2. `gradle/wrapper/gradle-wrapper.{jar,properties}` (8.8), `gradlew`, `gradlew.bat`, `settings.gradle` (foojay 0.5.0, `rootProject.name = 'immersive-brewing'`).
3. `gradle.properties`: `mod_id=immersive_brewing`, `mod_name=Immersive Brewing`, `mod_version=1.0.0`, `mod_group=com.otectus.immersivebrewing`, `mod_archive=immersive-brewing`, `mod_license=GPL-3.0`, `minecraft_version=1.20.1`, ranges, `forge_version=47.4.23`, `jei_version=15.20.0.135`, `jade_curse_file=6271651`, `junit_version=5.10.2`, `immersive_smithing_version=1.1.0`, `immersive_tanning_version=1.1.0`.
4. `build.gradle`: from Jewelry's, plus mixin config `immersive_brewing.mixins.json`, `flatDir libs` with `libs/immersive-smithing-1.1.0.jar` and `libs/immersive-tanning-1.1.0.jar` copied from the sisters' `build/libs` (compileOnly; runtimeOnly unless `-PnoSmithing`/`-PnoTanning`), Jade runtime only with `-PsmokeTest` (Jewelry's reason), `test { useJUnitPlatform() }`, `MixinConfigs` manifest attribute.
5. `src/main/resources/META-INF/mods.toml` (optional deps: jei, jade, immersive_smithing, immersive_tanning, immersive_enchanting, immersive_jewelry, curios none), `pack.mcmeta` (format 15), `immersive_brewing.mixins.json`, `assets/immersive_brewing/lang/en_us.json` (seed), `logo.png`.
6. `ImmersiveBrewing.java` (no-arg constructor, mod bus registrations, `ServerConfig`/`ClientConfig` registration, `DistExecutor` client setup), `registry/{ModBlocks,ModItems,ModBlockEntities,ModMenus,ModRecipeTypes,ModRecipeSerializers,ModSounds,ModParticles,ModCreativeTabs}.java` (empty registers), `config/ServerConfig.java` (spec 23.1 table, Smithing helper style), `config/ClientConfig.java`.
7. Potion identity spike: `potion/BrewingMetadata.java` (record + versioned NBT codec under root `immersive_brewing`: extraction, control, quality, lineage token, purified flag, brewer UUID/name, label, note, schema), `potion/PotionContentsService.java` (`canonical`, `metadata`, `effectiveEffects`, `effectiveUseTicks`, fingerprint), `potion/EffectPolicy.java` (built-in default: finite vanilla durations scale, instant/infinite/unknown preserved, Turtle Master bundle metadata-only), `potion/QualityMath.java` (grade bands 35/70/90, `0.80 + 0.004Q`, `base + round(4 - 0.08Q)`).
8. Hooks: `mixin/PotionItemMixin.java`, `mixin/ThrownPotionMixin.java`, `mixin/AreaEffectCloudMixin.java`, `mixin/ArrowMixin.java`, `event/PotionUseEvents.java` (`LivingEntityUseItemEvent.Start`), `recipe/BrewingTippedArrowRecipe.java` + serializer + `data/minecraft/recipes/tipped_arrow.json` override.
9. `src/gameTest/.../PotionPathGameTests.java` with structure `data/immersive_brewing/structures/empty.nbt`: tag a Swiftness potion with Q=0/50/100, assert drink duration via `LivingEntity#getEffect`, splash at two distances, lingering cloud application once, tipped-arrow crafting keeps metadata, arrow hit duration `/8`, dispenser throw, instant health applied once, repeated `resolve` and save/load produce identical effects, untagged vanilla potions unchanged.
10. `src/test/.../QualityMathTest.java` (boundary values from spec 11), `docs/ARCHITECTURE.md` with ADR-1 quality application (table in section 2.2), ADR-2 clock policy, ADR-3 persistent ownership; `README.md` stub; `CHANGELOG.md` stub; `MODMAP.md` via `.mcmod-tools/modmap.py`.

### M1 - Standalone vertical slice (water -> Awkward -> Swiftness, three bottles)

Gate: in a real client, craft three potions through Mortar and Kettle, drink one, confirm the tooltip's effective
duration equals the server score's duration and the applied effect; seal and resume once.

11. Blocks and items: `block/{ApothecaryMortarBlock,BrewersKettleBlock}.java` (horizontal facing, shapes), `blockentity/{StationBlockEntity,ApothecaryMortarBlockEntity,BrewersKettleBlockEntity}.java` (persisted ledger, bounded slots: bottles 6, ingredients, fuel, paddle, output buffer), `item/{BrewingPaddleItem,PreparedReagentKitItem}.java`, blockstates/models/textures/loot tables/lang/recipes for mortar, kettle, wooden and iron paddle (spec 4 recipes), creative tab entry.
12. Data layer: `data/{ReagentProfile,BrewProfile,EffectPolicyDefinition,ProfileSelector,BrewingDefinitions,DefinitionsSyncPacket}.java`, loaders for `immersive_brewing/reagent_profiles`, `brew_profiles`, `effect_policies`; built-in `data/immersive_brewing/immersive_brewing/reagent_profiles/sugar.json`, `brew_profiles/light_foam.json`, `effect_policies/vanilla_durations.json`.
13. Recipe layer: `recipe/{InfusionRecipe,InfusionRecipeSerializer,BrewPlan,PlanStep,PlanResolver}.java`, `recipe/adapter/{BrewingAdapter,ForgeRegistryAdapter,AdapterRegistry}.java` (preview/validateStart/fingerprint/batchPolicy/contentsPolicy/commit on copies), built-in `data/immersive_brewing/recipes/infusion/swiftness.json` (spec 16.2 example).
14. Batch accounting: `batch/{JobLedger,JobState,Reservation,FuelLedger,ServingGroup,LedgerCodec}.java` (reagent cost `ceil(servings/3)`, fuel charge per application, remainders owed, output snapshot).
15. Pure simulations: `minigame/preparation/{PreparationParams,PreparationSimulation,PreparationScore}.java` (pressure, endpoint window, restraint, per-step motion cap), `minigame/brewing/{KettleParams,KettleSimulation,KettlePhase,DisturbanceSchedule,BrewScore}.java` (H/V integration from spec 8.1, Bloom/Infuse/Settle, additions, late-add penalty, seeded schedule), `minigame/StepClock.java` (50 ms).
16. Sessions and network: `session/{SessionManager,BrewingSession,PreparationSession,InputFrame,InputValidator,SessionTiming}.java`, `network/ModNetwork.java` + packets `OpenKettlePlanS2C`, `SelectPlanC2S`, `StartSessionC2S`, `InputFramesC2S`, `SessionStartS2C`, `StateSnapshotS2C`, `SessionResultS2C`, `SealResumeC2S`, `StationNoticeS2C`, `CollectOutputC2S`.
17. Client: `client/ClientSetup.java`, `client/screen/{KettlePlanScreen,KettleBrewScreen,MortarScreen,ResultOverlay}.java`, `client/render/{KettleRenderer,MortarRenderer}.java`, `client/StationNotices.java`, key mappings, `client/compat/` placeholder. Menus in `menu/{KettleMenu,MortarMenu}.java` (common).
18. Tests: `src/test/.../{KettleSimulationTest,PreparationSimulationTest,LedgerCodecTest}.java`, `src/testShared/.../{KettleBot,MortarBot}.java` (competent, idle, poor traces), `src/gameTest/.../KettleGameTests.java` (three servings, fuel 20->18, nether wart and sugar consumed, output identity equals `BrewingRecipeRegistry.getOutput` chain), `src/smokeTest` agent (fresh flat world, plays a brew through the real screen with `KettleBot`, screenshots, `smoketest-result.json`).

### M2 - Transactions and multiplayer

Gate: save/load, disconnect, double completion, station break and two-player contention GameTests pass.

19. `batch/{RecoveryBatchItem,LedgerTransfer}.java`, `item/RecoveryBatchItem.java` (install-once, excluded from carriers/handlers/recipes), station `onRemove` dropping one recovery item plus unrelated inventory.
20. `session/ControllerLock.java` (persisted controller UUID, resume rules), `SessionManager` lifecycle Ready/Reserved/Running/Sealed/Resolved/Recovery with seal on close, disconnect, death, dimension change, range, chunk unload, reload, config change; count-in on resume; no reseed.
21. `network/InputValidator.java` bounds (20 frames/s, 4 per packet, 3-tick playout buffer, monotonic sequence/step, stale/future rejection, max sizes), `session/GraceWindow.java`.
22. `blockentity/StationItemHandler.java` and `StationFluidHandler.java` (`IItemHandler`/`IFluidHandler` with reservation enforcement, 250 mB per empty bottle, simulate vs execute), `invalidateCaps`/`reviveCaps`, comparator modes.
23. GameTests `LifecycleGameTests.java`, `ContentionGameTests.java`, `AutomationGameTests.java`, `ReplayGameTests.java` (duplicate, reordered, oversized packets; forged completion from a viewer); `docs/TESTING.md` started with commands and evidence paths.

### M3 - Vanilla breadth and batch economy

Gate: automated comparison of every shipped plan against `BrewingRecipeRegistry.getOutput` passes for 1-6
servings; costs, remainders and fuel conserved.

24. Built-in infusion plans for every family in spec 13 (`data/immersive_brewing/recipes/infusion/*.json`), including corruptions, Weakness from water, Turtle Master, container steps, redstone/glowstone variants; built-in reagent profiles (crystalline sugar, soft spider eye, fibrous/brittle/liquid profiles as declared) and brew profiles for each reaction signature (spec 7).
25. `recipe/PlanResolver` intermediate entry points, modifier-only plans (10-16 s), path choice preview, cycle detection, `maxPlanSteps`, `recipe/DiscoveryIndex.java` (finite `BrewingRecipe` enumeration plus bounded queries, `maxDiscoveryPairs`, cache by data generation), `recipe/CoverageReport.java`.
26. Difficulty tiers `minigame/brewing/ComplexityProfile.java` (spec 8.3), large-batch inertia, 50 s cap, assisted-pacing cap 90 s.
27. Replacement mode: `mixin/BrewingStandBlockEntityMixin.java`, `event/BrewingStandGuard.java` (`PotionBrewEvent.Pre` backstop, coverage set, `replaceModifiers`, mixed-cycle pass-through), Jade/notice explanation.
28. Quality inheritance in `PotionContentsService` (spec 12.3: `min(Q_in, candidate)`, lineage, purification flag retained through modifiers, tipped arrows).
29. GameTests `VanillaCoverageGameTests.java` (generated from the plan set, compares identity, amplifiers, remainders, dragon's breath bottle once, blaze powder as fuel and reagent), `ReplacementModeGameTests.java`, `ServingEconomyGameTests.java` (1-6 servings, kits replacing reagents exactly, four-kit preparation).

### M4 - Alembic and workshop conveniences

Gate: 3->2 purification, 3->1 reclamation, lineage cap and interrupted Alembic jobs with no duplicate material.

30. `block/AlembicBlock.java`, `blockentity/AlembicBlockEntity.java`, `minigame/distillation/{DistillationParams,DistillationSimulation,DistillationScore}.java`, `recipe/{DistillationRecipe,ReclamationRecipe}` + serializers + built-in data, `item/SpentAlchemicalSaltItem.java`, gray dye recipe, Alembic crafting recipe (bucket consumed, declared).
31. `item/BatchCarrierItem.java` (six stacks, exact transfer, no nesting, narrow item handler), `item/BrewersNotebookItem.java`, `guide/{NotebookContent,Favorites,LastResult,GrantFlag}.java`, labeling packet `SetLabelC2S` with validation (48/120 code points, control characters), tooltip builder `potion/BrewingTooltips.java`.
32. Practice mode: `session/PracticeSession.java` (same simulation, no inventories/outputs/events), `command/ImmersiveBrewingCommand.java` (`help`, `practice`, operator `report`, `recipe`, `profile`, `inspect`, `quality`, `session list|seal`, `recover`).
33. Tests: `DistillationSimulationTest`, `AlembicGameTests`, `CarrierGameTests`, `LabelGameTests`, `NotebookTest`.

### M5 - Datapacks, API and compatibility

Gate: a third-party namespace adds a working infusion plan and profile without Java; an excluded item stays excluded;
all optional mods absent still loads.

34. `api/{ImmersiveBrewingAPI,BrewingAdapter,IEffectQualityHandler,BrewingMetadataView,PlanQuery}.java`, `api/event/{BrewPlanEvent,BrewResultEvent,ReagentPreparedEvent,BatchRefinedEvent}.java`, IMC names; `docs/API.md`, `docs/DATAPACKS.md` with every field marked accepted/reserved/rejected (`allow_amplifier_change=true` rejected).
35. Reload hardening: candidate dataset validation (references, cycles, ranges, sizes), sealing of active jobs with snapshots, revalidation on registry change, client sync in bounded chunks, exclusions by item/recipe/policy/adapter.
36. JEI: `client/compat/jei/{ImmersiveBrewingJeiPlugin,PreparationCategory,BrewingCategory,DistillationCategory,ReclamationCategory,InfoPages,TransferHandlers}.java`; Jade: `compat/jade/BrewingJadePlugin.java` with server data providers and `config.jade.plugin_immersive_brewing.*` lang keys (dev-runtime requirement noted in Jewelry's known issues).
37. Sister integrations behind `ModList` checks: `integration/smithing/SmithingIntegration.java` (`ISmithingRecipeProvider` iron paddle, `ItemSmithedEvent` durability metadata, exclusion predicate for carriers and recovery batches, fallback crafting recipe condition), `integration/tanning/TanningIntegration.java` (optional carrier strap recipe on a verified Binding Leather tag, `immersive_tanning:binding_leather`), Enchanting (paddle enchantable: Unbreaking/Mending only), Jewelry coexistence test only.
38. Example datapack under `src/gameTest/resources/data/ib_test/` loaded by `DataGameTests.java` (valid plan, invalid plan, excluded item, tie-break diagnostic); compatibility GameTests run with `-PnoSmithing -PnoTanning` and with the jars.

### M6 - Presentation and accessibility

Gate: screenshot review at GUI scales 1-4 and the 320x240 viewport; keyboard-only, reduced-motion, colour-blind and
sound-off trials pass.

39. `art/brewing_art.py`, `tools/generate_textures.py`, `art/brewing_audio.py`, `tools/generate_sounds.py`, `sounds.json` with subtitles, particles (`client/particle`), station renderers for every state (empty, loaded, active, sealed, finished, recovery-blocked), world effects bounded per station.
40. Screens: adaptive layout, remappable keys with visible names, hold/toggle alternatives, high-contrast palette, numeric display option, tutorial overlay (`guide/Tutorial.java`), result explanation with improvement tip, Escape semantics; input transform equals render transform.
41. Advancements (`data/immersive_brewing/advancements/*.json`, `advancement/ModAdvancements.java`) triggered once from committed outcomes; optional cleric trades off by default (`compat/villager`).
42. Smoke test extended: every station state, GUI scale runs, screenshot naming; `check_mod.py` clean.

### M7 - Release verification and documentation

Gate: buildable release jar, evidence recorded, truthful compatibility report.

43. Release matrix runs (spec 26.5) and recorded results in `docs/TESTING.md`; `/immersivebrewing report` output committed under `docs/reports/`.
44. `README.md` (install, first batch, tested compatibility), `CHANGELOG.md`, final `docs/ARCHITECTURE.md` deviations table, asset licensing notes in `art/README.md`.
45. Jar inspection (mixins json, refmap, `MixinConfigs`, `mods.toml` deps, no test agents), `MODMAP.md` refresh, remove debug bypasses.

## 5. Dependencies

- M0.7-M0.9 (hook spike) gate everything that touches potions; M1 cannot start its quality work before the spike passes.
- M1.12 (data layer) precedes M1.13 (plans reference profiles) and M1.15 (simulations read profile parameters).
- M1.14 (ledger) precedes M1.16 (sessions reserve through the ledger) and all of M2.
- M2 (controller locks, packet bounds) precedes M3.27 (replacement mode changes vanilla behaviour) and M4 (Alembic reuses the lifecycle).
- M3.25 (discovery) precedes M5.36 (JEI lists what discovery validated).
- M5.34 (API) precedes M5.37 (integrations consume it) and M6.41 (advancements listen to result events).
- Optional sister jars (`libs/`) are needed only for M5.37 compile; everything else builds without them.
- External: Jade in the dev runtime requires the plugin lang keys (M5.36) or it throws on the title screen.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Mixin targets drift or another mod replaces `PotionItem`/`ThrownPotion` behaviour | Five narrow redirects, each with a GameTest; `require = 1` so a failed apply is loud in dev; document in ADR-1 that unknown potion items are metadata-only. |
| `LivingEntityUseItemEvent.Start` duration also passes Forge's own minimum check in `startUsingItem` | Minimum effective use time is 28 ticks; verify against `ForgeConfig.SERVER` threshold in the M0 GameTest. |
| First-person drink animation mismatch (item's 32 ticks vs event duration) | Accept as cosmetic; note in README known limitations; revisit only if playtesting objects. |
| Tipped-arrow recipe override collides with another mod's override of `minecraft:tipped_arrow` | Detect at `RecipesUpdatedEvent`/`ServerStarted`: if the loaded recipe is not ours, log and fall back to metadata loss with a report line. |
| `BrewingRecipeRegistry.getOutput` returns a new stack each call; modded `IBrewingRecipe`s may be non-deterministic | Fingerprint preview outputs; `validateStart` re-queries and refuses the plan if the output differs. |
| Discovery cost on large packs | Finite enumeration first, query budget `maxDiscoveryPairs`, rebuild only on data generation change, never per tooltip. |
| GameTest server runs faster than real time | Use the Enchanting `SessionClock` pattern: sessions follow game ticks, so tests advance deterministically. |
| Dedicated-server classloading of client code | `client/` package only reached through `DistExecutor`; GameTest server run (dedicated dist) is the gate. |
| Timing targets unmet in play | Spec forbids claiming them from simulation; schedule hands-on trials in M6 and tune profiles, not formulas. |
| Scope creep from spec section 30 | Not in this plan; `docs/ARCHITECTURE.md` keeps a "later" list. |

## 7. Proposed checks (commands)

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/ImmersiveBrewing compileJava
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/ImmersiveBrewing build          # JUnit + reobf jar
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/ImmersiveBrewing runGameTestServer
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/ImmersiveBrewing runGameTestServer -PnoSmithing -PnoTanning
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/ImmersiveBrewing runClient -PsmokeTest
python3 -I /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/ImmersiveBrewing
python3 -I /home/otectus/Projects/.mcmod-tools/modmap.py /home/otectus/Projects/ImmersiveBrewing
python3 tools/generate_textures.py --check && python3 tools/generate_sounds.py --check
```

Manual, recorded in `docs/TESTING.md`: dedicated server (`runServer`, needs `run/eula.txt`) with two clients for
contention, forged packets, disconnect/death/dimension tests, 0/100/200 ms latency and reduced TPS; screenshot
review per spec 26.6.

## 8. Unresolved decisions (yours)

1. **Remote repository.** Initialise git locally now; push to `github.com/otectus/ImmersiveBrewing` like the sisters, or keep local until M1? Default if unanswered: local only.
2. **Optional sister jars in `libs/`.** Copy `immersive-smithing-1.1.0.jar` and `immersive-tanning-1.1.0.jar` from the sisters' `build/libs` (as Jewelry does) or depend on a published artifact? Default: copy, versioned in `gradle.properties`.
3. **Tipped-arrow recipe override.** Accept overriding `minecraft:tipped_arrow` via this mod's data, with the collision fallback above? Alternative is to document tipped arrows as metadata-losing. Default: override.
4. **Mixin acceptance.** Five vanilla mixins are the minimum for spec 12.2 (evidence in section 2.2). Confirm or accept a narrower metadata-only release for splash/lingering/arrow paths.
5. **Cosmetic drink-animation mismatch.** Accept, or add a sixth mixin on `PotionItem.getUseDuration` for exact animation timing. Default: accept.

Everything else in the spec is treated as decided; deviations will be recorded in `docs/ARCHITECTURE.md` as they arise.
