# Immersive Brewing configuration

This reference describes the fields registered by this development version (see `gradle.properties`) for Minecraft
1.20.1 Forge. It is not a release announcement. Forge creates the files after the relevant side starts once.

| Scope | File | Who controls it | Reload behavior |
| --- | --- | --- | --- |
| Server | `<world>/serverconfig/immersive_brewing-server.toml` | World/server operator; synced presentation values are reflected to joined clients | Forge reloads edits. Effect-policy and plan caches are invalidated on the server thread. New reservations use the new values. |
| Client | `config/immersive_brewing-client.toml` | Each player | Live presentation/control settings; the in-game **Access** panel changes the same values. They do not change score. |
| Common | `config/immersive_brewing-common.toml` | Installation | Optional sister-mod data/lifecycle switches. Change before startup; run `/reload` for recipe-condition changes. A restart is recommended because lifecycle/IMC registration is not replayed by config reload. |

## Active jobs and live settings

A Kettle, Mortar or Alembic reservation moves its owned stacks, fuel and (at the Alembic) reserved coolant into one
ledger. A hands-on batch saves as schema 3 with a versioned process payload: the resolved workshop setup (reaction
family, ideal and working heat bands, step and rest timing, preparation endpoint and useful work, distillation cut
points), the live process state, the resolved plan steps, the approved assistance, servings and the relevant operation
caps. Purification saves its cap; reclamation saves its recipe duration. A reload does not reinterpret those values
midway through a job. Batches saved by 1.1 keep schema 2 with their simulation parameters and seed and finish through
their original interface.

Some policies are intentionally live rather than ledger data: station distance, participant range and automation gates
are checked when used; quality duration bounds and drink-time enablement affect consumption of existing potion items;
the count-in and input-buffer settings apply only when a 1.1 batch is finished through its original interface.
`/reload` seals such a 1.1 session before the new datapack generation activates; hands-on batches keep their setup and
check the recipe again when they complete. Existing saved jobs either resume from their snapshots or remain held with an
actionable reason when current required data is unavailable.

## Server fields

All server fields are per world.

| TOML field | Default | Allowed values | Gameplay impact / when read |
| --- | ---: | --- | --- |
| `brewing.brewingMode` | `COEXIST` | `COEXIST`, `REPLACE_COVERED`, `DISABLED` | Stand relationship and permission to start new station jobs. Existing jobs remain recoverable/resumable. |
| `brewing.replaceModifiers` | `false` | boolean | In replacement mode, also covers supported modifier/container transformations. Rebuilds plan/coverage caches. |
| `brewing.quickFinishing` | `true` | boolean | Offers server-proven container-only finishing. Does not create a score or milestone. Read before a finishing reservation. |
| `brewing.finishingSeconds` | `7` | 5-10 | How long the bottles settle under the closed finishing fitting (loaded-world time); snapshotted into the batch when it is set out. |
| `batches.maxBatchServings` | `6` | 1-6 | Maximum new batch size; narrows recipe/adapter limits and plan staging. |
| `batches.servingsPerVanillaReagent` | `3` | 1-3 | Vanilla-adapter servings per reagent. May make recipes costlier, never cheaper than the adapter contract. Rebuilds plans. |
| `batches.fuelChargesPerBlazePowder` | `20` | 1-100 | Burner charges added by one blaze powder. Read while staging/reserving fuel. |
| `batches.allowWaterFluidFilling` | `true` | boolean | Allows whole empty bottles in an idle Kettle to be filled at 250 mB each. No fluid is stored. |
| `batches.allowAutomationInputs` | `true` | boolean | Live gate for hopper/pipe insertion into public, eligible station slots. Reserved job stacks remain inaccessible. |
| `batches.allowAutomationOutputs` | `true` | boolean | Live gate for extraction of completed, unreserved output. |
| `quality.maxBrewDurationSeconds` | `50` | 20-50 | Duration cap of the 1.1 Kettle simulation. Hands-on batches are paced by their reaction and workshop profile; a migrated 1.1 batch already carries its own value. |
| `quality.qualityDurationMin` | `0.80` | 0.50-1.00 | Lower bound on eligible effect-duration policies. Live for potion effect resolution; invalid inverted pairs fall back to defaults. |
| `quality.qualityDurationMax` | `1.20` | 1.00-2.00 | Upper bound on eligible effect-duration policies. This bound does not change the underlying `(200 + Q) / 250` curve. |
| `quality.qualityAffectsDrinkTime` | `true` | boolean | Live gate for ordinary drinkable potion use-time adjustment. |
| `alembic.allowPurification` | `true` | boolean | Allows new purification reservations. |
| `alembic.purificationQualityCap` | `70` | 35-89 | Narrows the recipe cap and is snapshotted when purification starts. Never reaches Masterwork by default. |
| `alembic.maxPurificationsPerLineage` | `1` | 0-3 | Attempts allowed for one lineage, carried through later transformations and checked before reservation. |
| `alembic.allowReclamation` | `true` | boolean | Allows new passive reclamation jobs. |
| `alembic.coolantCapacityMb` | `1000` | 0-16000 | Water the Alembic's coolant jacket holds (buckets, water bottles, pipes). 0 disables the jacket and its cost. |
| `alembic.coolantPerGroupMb` | `250` | 0-4000 | Coolant one purification group takes from the jacket when the charge is poured; it is spent by the run and returned only if an untouched charge is withdrawn. 0 removes the cost. |
| `assistance.allowAssistedControls` | `true` | boolean | Whether the server honors player assistance requests. Assistance retains the full scoring range. |
| `assistance.maxAssistTimeMultiplier` | `1.5` | 1.0-1.5 | Caps slower cue timing approved at reservation. It applies to 1.1 sessions; hands-on batches have no cue clock to slow down. |
| `assistance.maxAssistBandMultiplier` | `1.25` | 1.0-1.25 | Caps how much wider the Kettle's ideal heat band may be, approved at reservation and saved with the batch. |
| `discovery.maxDiscoveryPairs` | `4096` | 0-65536 | Bounds potion×ingredient probing while rebuilding non-enumerable registry transformations. It is separate from incremental destination-search work limits. |
| `discovery.maxPlanSteps` | `8` | 1-8 | Maximum transformation steps in a new plan; rebuilds the plan index. |
| `rewards.enableVillagerTrades` | `false` | boolean | Enables the bounded cleric offer registrations when Forge builds trades. Already generated villager offers are not rewritten. Off pending economy validation. |
| `rewards.completionExperience` | `0` | 0-10 | Experience per committed productive brew. Staging, practice, finishing, racks and carriers do not add their own reward. |
| `sessions.maxStationDistance` | `6.0` | 2.0-16.0 | Live reach for station work: the working view closes beyond this distance plus 1.5 blocks and workshop actions are refused beyond it plus 1; station menus allow 2 extra blocks. A 1.1 session seals when its controller leaves range. |
| `sessions.participantRange` | `8.0` | 3.0-32.0 | Live: how near the brewer or a helper who has done productive work must stay for an attended hands-on batch to keep running. With nobody in range it seals itself after about two seconds and loses nothing. |
| `sessions.countInTicks` | `60` | 20-200 | Count-in when a 1.1 batch begins or resumes in its original interface. |
| `sessions.inputBufferTicks` | `3` | 1-10 | Minimum playout buffer of a 1.1 session, so ordinary latency does not reduce score. |
| `sessions.maxInputBufferTicks` | `8` | 1-20 | Maximum latency-grown playout buffer of a 1.1 session. Effective maximum is never below the configured minimum. |
| `sessions.debugLogging` | `false` | boolean | Detailed adapter, plan and session logging; no gameplay change. |

## Client fields

| TOML field | Default | Allowed values | Effect |
| --- | ---: | --- | --- |
| `controls.toggleStir` | `false` | boolean | Tap the Work key to start or stop working a held tool (stirring, grinding) instead of holding it. In 1.1 this toggled minigame stirring. |
| `controls.togglePressure` | `false` | boolean | Firm pestle pressure toggles with the press key instead of following a held button. In 1.1 this toggled minigame pressure. |
| `controls.mouseStirring` | `true` | boolean | A held tool follows the mouse over its work surface. Off: tools move only with the keys and the Work action. |
| `controls.reducedInput` | `false` | boolean | One-key paths everywhere: every hold becomes a toggle and nothing needs two keys at once. |
| `controls.holdToGrab` | `false` | boolean | Hold the mouse button to keep a tool in hand (releasing puts it down). Off: click to take, click its rest or right-click to put down. |
| `controls.toolSensitivity` | `1.0` | 0.25-3.0 | How far a held tool moves for a given mouse movement or key press. |
| `controls.toolDeadZone` | `0.0` | 0.0-0.2 | Mouse movement (as a part of the work surface) ignored before a held tool moves; steadies shaky hands. |
| `controls.toolSmoothing` | `0.35` | 0.0-0.9 | Visual weight of a held tool following the hand. It never delays what the server receives. |
| `workshop.cameraMode` | `FOCUS` | `FOCUS`, `DIRECT` | `FOCUS` turns your view toward the apparatus when you start working; `DIRECT` keeps your own view. Neither moves the camera through walls or away from your eyes; reduced motion makes the turn instant. |
| `workshop.mirrorTools` | `false` | boolean | Left-handed presentation: rests and held tools are drawn mirrored. The parts you act on stay the same. |
| `workshop.partHints` | `true` | boolean | Shows the short action label beside the hovered or selected part. |
| `workshop.tutorialStage` | `0` | 0-3 | First-use demonstrations already seen (tool, burner, dose). Set 0 to see them again; the Notebook's guided lessons also reset it. |
| `display.palette` | `STANDARD` | `STANDARD`, `HIGH_CONTRAST`, `COLORBLIND_SAFE` | Selects the semantic UI palette; critical state also retains text/shape. |
| `display.largeMarkers` | `false` | boolean | Enlarges apparatus markers and target-band indicators. |
| `display.reducedMotion` | `false` | boolean | Calms liquid/profile animation and disables camera effects. |
| `display.screenShake` | `false` | boolean | Enables the optional small boil/flare shake; reduced motion overrides it. |
| `display.particleDensity` | `1.0` | 0.0-1.0 | Scales station/profile particle emission; zero disables it. |
| `display.animationIntensity` | `1.0` | 0.0-1.0 | Scales apparatus animation intensity. |
| `display.pinnedHudAnchor` | `RIGHT` | `LEFT`, `RIGHT` | Pinned Notebook reminder edge. |
| `display.pinnedHudYOffset` | `0` | -120–120 | Vertical reminder offset. |
| `display.pinnedHudCompact` | `false` | boolean | Uses icon/count reminder rows. |
| `display.pinnedHudHideWhileScreenOpen` | `true` | boolean | Hides reminder behind open screens. |
| `display.inspectionHints` | `true` | boolean | Enables the fallback station hint when Jade is absent. |
| `display.writtenCues` | `true` | boolean | Shows textual control/signature/warning cues. |
| `display.numericDisplay` | `false` | boolean | Adds numeric readings (heat, circulation, reaction, texture, coolant, run) to the working view and numeric quality values elsewhere. |
| `display.showTutorial` | `true` | boolean | Shows the working view's first-use demonstrations (and the 1.1 help overlay for a migrated batch). Guided Notebook lessons remain replayable. |
| `display.stationNotices` | `true` | boolean | Uses the readable notice area above the hotbar instead of the vanilla action bar. |
| `display.noticeSeconds` | `6` | 2-30 | Notice display duration. |
| `sound.apparatusVolume` | `1.0` | 0.0-1.0 | Bubble/grind/condensate sound volume. |
| `sound.cueVolume` | `1.0` | 0.0-1.0 | Addition, warning and completion cue volume. |
| `assistance.requestAssist` | `false` | boolean | Requests assistance for the next batch you set out; the server remains authoritative. |
| `assistance.assistTime` | `1.25` | 1.0-1.5 | Requested timing multiplier, capped by the server (1.1 sessions only). |
| `assistance.assistBand` | `1.15` | 1.0-1.25 | Requested widening of the Kettle's ideal heat band, capped by the server. |

## Common fields

| TOML field | Default | Effect |
| --- | ---: | --- |
| `integrations.smithing` | `true` | With Immersive Smithing loaded, enables its iron-paddle path; otherwise the conditioned crafting fallback is used. |
| `integrations.tanning` | `true` | With Immersive Tanning loaded, enables the conditioned carrier-strap recipe. |
| `integrations.enchanting` | `true` | Allows paddles to accept Unbreaking and Mending, including the sister mod's ritual path when installed. |

## Named examples

These snippets contain actual field names. Merge them into the generated files; omitted fields keep their defaults.

### Artisan — existing fully manual container work

This keeps ordinary stands available, disables the new quick-finishing shortcut and adds no completion experience.
Accessibility remains available and does not reduce quality.

`<world>/serverconfig/immersive_brewing-server.toml`:

```toml
[brewing]
brewingMode = "COEXIST"
replaceModifiers = false
quickFinishing = false

[assistance]
allowAssistedControls = true

[rewards]
completionExperience = 0
```

### Relaxed controls — full scoring with reduced input

Server approval caps, in `<world>/serverconfig/immersive_brewing-server.toml`:

```toml
[assistance]
allowAssistedControls = true
maxAssistTimeMultiplier = 1.5
maxAssistBandMultiplier = 1.25
```

Player preferences, in `config/immersive_brewing-client.toml`:

```toml
[controls]
toggleStir = true
togglePressure = true
mouseStirring = true
reducedInput = true
toolSensitivity = 0.75
toolDeadZone = 0.05

[workshop]
cameraMode = "FOCUS"
partHints = true

[display]
palette = "HIGH_CONTRAST"
largeMarkers = true
reducedMotion = true
screenShake = false
writtenCues = true
stationNotices = true

[assistance]
requestAssist = true
assistTime = 1.5
assistBand = 1.25
```

This preset changes control presentation and requests server-capped band assistance. It does not change the quality
formula, cap the result grade or add an “assisted” suffix.

There is intentionally no **Workshop production** preset: unattended Kettle production is a deferred roadmap item, not
a hidden configuration. A hands-on batch needs someone at the apparatus and seals itself when nobody stays near.
