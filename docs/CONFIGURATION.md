# Immersive Brewing configuration

This reference describes the fields registered by development version 1.1.0 for Minecraft 1.20.1 Forge. It is not a
release announcement. Forge creates the files after the relevant side starts once.

| Scope | File | Who controls it | Reload behavior |
| --- | --- | --- | --- |
| Server | `<world>/serverconfig/immersive_brewing-server.toml` | World/server operator; synced presentation values are reflected to joined clients | Forge reloads edits. Effect-policy and plan caches are invalidated on the server thread. New reservations use the new values. |
| Client | `config/immersive_brewing-client.toml` | Each player | Live presentation/control settings; the in-game **Access** panel changes the same values. They do not change score. |
| Common | `config/immersive_brewing-common.toml` | Installation | Optional sister-mod data/lifecycle switches. Change before startup; run `/reload` for recipe-condition changes. A restart is recommended because lifecycle/IMC registration is not replayed by config reload. |

## Active jobs and live settings

A Kettle, Mortar or Alembic reservation moves its owned stacks and fuel into one schema-2 ledger. Simulation parameters,
the seed, resolved plan steps, assistance approval, servings and relevant operation caps are saved with it. Quick
finishing saves its duration; purification saves its cap; reclamation saves its recipe duration. A reload does not
reinterpret those values midway through a job.

Some policies are intentionally live rather than ledger data: station distance and automation gates are checked when
used; quality duration bounds and drink-time enablement affect consumption of existing potion items; the next
session/resume reads current count-in and input-buffer settings. `/reload` seals an interactive session before the new
datapack generation activates. Existing saved jobs either resume from their snapshots or remain held with an
actionable reason when current required data is unavailable.

## Server fields

All server fields are per world.

| TOML field | Default | Allowed values | Gameplay impact / when read |
| --- | ---: | --- | --- |
| `brewing.brewingMode` | `COEXIST` | `COEXIST`, `REPLACE_COVERED`, `DISABLED` | Stand relationship and permission to start new station jobs. Existing jobs remain recoverable/resumable. |
| `brewing.replaceModifiers` | `false` | boolean | In replacement mode, also covers supported modifier/container transformations. Rebuilds plan/coverage caches. |
| `brewing.quickFinishing` | `true` | boolean | Offers server-proven container-only finishing. Does not create a score or milestone. Read before a finishing reservation. |
| `brewing.finishingSeconds` | `7` | 5-10 | Loaded-world duration of finishing; snapshotted into that job ledger. |
| `batches.maxBatchServings` | `6` | 1-6 | Maximum new batch size; narrows recipe/adapter limits and plan staging. |
| `batches.servingsPerVanillaReagent` | `3` | 1-3 | Vanilla-adapter servings per reagent. May make recipes costlier, never cheaper than the adapter contract. Rebuilds plans. |
| `batches.fuelChargesPerBlazePowder` | `20` | 1-100 | Burner charges added by one blaze powder. Read while staging/reserving fuel. |
| `batches.allowWaterFluidFilling` | `true` | boolean | Allows whole empty bottles in an idle Kettle to be filled at 250 mB each. No fluid is stored. |
| `batches.allowAutomationInputs` | `true` | boolean | Live gate for hopper/pipe insertion into public, eligible station slots. Reserved job stacks remain inaccessible. |
| `batches.allowAutomationOutputs` | `true` | boolean | Live gate for extraction of completed, unreserved output. |
| `quality.maxBrewDurationSeconds` | `50` | 20-50 | Hard cap used when building a new unassisted Kettle simulation. Approved assistance may extend it, never past 90 seconds. |
| `quality.qualityDurationMin` | `0.80` | 0.50-1.00 | Lower bound on eligible effect-duration policies. Live for potion effect resolution; invalid inverted pairs fall back to defaults. |
| `quality.qualityDurationMax` | `1.20` | 1.00-2.00 | Upper bound on eligible effect-duration policies. This bound does not change the underlying `(200 + Q) / 250` curve. |
| `quality.qualityAffectsDrinkTime` | `true` | boolean | Live gate for ordinary drinkable potion use-time adjustment. |
| `alembic.allowPurification` | `true` | boolean | Allows new purification reservations. |
| `alembic.purificationQualityCap` | `70` | 35-89 | Narrows the recipe cap and is snapshotted when purification starts. Never reaches Masterwork by default. |
| `alembic.maxPurificationsPerLineage` | `1` | 0-3 | Attempts allowed for one lineage, carried through later transformations and checked before reservation. |
| `alembic.allowReclamation` | `true` | boolean | Allows new passive reclamation jobs. |
| `assistance.allowAssistedControls` | `true` | boolean | Whether the server honors player assistance requests. Assistance retains the full scoring range. |
| `assistance.maxAssistTimeMultiplier` | `1.5` | 1.0-1.5 | Caps slower control/cue timing approved at reservation and saved in the simulation parameters. |
| `assistance.maxAssistBandMultiplier` | `1.25` | 1.0-1.25 | Caps wider physical control bands approved at reservation and saved in the simulation parameters. |
| `discovery.maxDiscoveryPairs` | `4096` | 0-65536 | Bounds potion×ingredient probing while rebuilding non-enumerable registry transformations. It is separate from incremental destination-search work limits. |
| `discovery.maxPlanSteps` | `8` | 1-8 | Maximum transformation steps in a new plan; rebuilds the plan index. |
| `rewards.enableVillagerTrades` | `false` | boolean | Enables the bounded cleric offer registrations when Forge builds trades. Already generated villager offers are not rewritten. Off pending economy validation. |
| `rewards.completionExperience` | `0` | 0-10 | Experience per committed productive brew. Staging, practice, finishing, racks and carriers do not add their own reward. |
| `sessions.maxStationDistance` | `6.0` | 2.0-16.0 | Live distance check for an active controller. Leaving range seals the session. |
| `sessions.countInTicks` | `60` | 20-200 | Count-in read when a session begins or resumes. |
| `sessions.inputBufferTicks` | `3` | 1-10 | Minimum playout buffer selected when a session begins, so ordinary latency does not reduce score. |
| `sessions.maxInputBufferTicks` | `8` | 1-20 | Maximum latency-grown playout buffer selected for a session. Effective maximum is never below the configured minimum. |
| `sessions.debugLogging` | `false` | boolean | Detailed adapter, plan and session logging; no gameplay change. |

## Client fields

| TOML field | Default | Allowed values | Effect |
| --- | ---: | --- | --- |
| `controls.toggleStir` | `false` | boolean | Tap a direction to start/stop stirring instead of holding it. |
| `controls.togglePressure` | `false` | boolean | Tap pestle pressure to hold/release it. |
| `controls.mouseStirring` | `true` | boolean | Enables bounded circular mouse stirring. |
| `controls.reducedInput` | `false` | boolean | Makes vigorous stirring a toggle so simultaneous chords are optional. |
| `display.palette` | `STANDARD` | `STANDARD`, `HIGH_CONTRAST`, `COLORBLIND_SAFE` | Selects the semantic UI palette; critical state also retains text/shape. |
| `display.largeMarkers` | `false` | boolean | Enlarges apparatus markers and target-band indicators. |
| `display.reducedMotion` | `false` | boolean | Calms liquid/profile animation and disables camera effects. |
| `display.screenShake` | `false` | boolean | Enables the optional small boil/flare shake; reduced motion overrides it. |
| `display.particleDensity` | `1.0` | 0.0-1.0 | Scales station/profile particle emission; zero disables it. |
| `display.animationIntensity` | `1.0` | 0.0-1.0 | Scales apparatus animation intensity. |
| `display.writtenCues` | `true` | boolean | Shows textual control/signature/warning cues. |
| `display.numericDisplay` | `false` | boolean | Adds numeric heat, circulation and quality values. |
| `display.showTutorial` | `true` | boolean | Offers the station help overlay until completed. Guided Notebook practice remains replayable. |
| `display.stationNotices` | `true` | boolean | Uses the readable notice area above the hotbar instead of the vanilla action bar. |
| `display.noticeSeconds` | `6` | 2-30 | Notice display duration. |
| `sound.apparatusVolume` | `1.0` | 0.0-1.0 | Bubble/grind/condensate sound volume. |
| `sound.cueVolume` | `1.0` | 0.0-1.0 | Addition, warning and completion cue volume. |
| `assistance.requestAssist` | `false` | boolean | Requests assistance for the next productive session; the server remains authoritative. |
| `assistance.assistTime` | `1.25` | 1.0-1.5 | Requested timing multiplier, capped by the server. |
| `assistance.assistBand` | `1.15` | 1.0-1.25 | Requested band multiplier, capped by the server. |

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

This preset changes control presentation and requests server-capped timing/band assistance. It does not change the
quality formula, cap the result grade or add an “assisted” suffix.

There is intentionally no **Workshop production** preset: unattended Kettle production is a deferred P2 roadmap item,
not a hidden 1.1.0 configuration.
