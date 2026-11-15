# Immersive Brewing datapacks

Everything Immersive Brewing knows about potions, reagents and reactions is data. A pack can add plans for potions from
other mods, give reagents their own preparation, change how a reaction behaves, decide which effects quality may
lengthen, and exclude items, recipes, policies or whole adapters, all without Java.

Data never invents a transformation. A plan only describes a step that a brewing system (an *adapter*; the built-in one
is Forge's brewing registry) already performs; the server checks every step against that system when it builds the plan
index and refuses plans whose result differs.

**Field status.** *Accepted*: read and used. *Reserved*: read but not used by development version 1.1.0. *Rejected*:
the file fails to load with an error naming the field. Unknown fields are ignored with a warning. This is development
documentation, not a release announcement.

## Where files go

| Kind | Path |
| --- | --- |
| Infusion plan (recipe) | `data/<ns>/recipes/<path>.json`, `"type": "immersive_brewing:infusion"` |
| Purification (recipe) | `data/<ns>/recipes/<path>.json`, `"type": "immersive_brewing:distillation"` |
| Reclamation (recipe) | `data/<ns>/recipes/<path>.json`, `"type": "immersive_brewing:reclamation"` |
| Reagent profile | `data/<ns>/immersive_brewing/reagent_profiles/<path>.json` |
| Brew profile | `data/<ns>/immersive_brewing/brew_profiles/<path>.json` |
| Effect policy | `data/<ns>/immersive_brewing/effect_policies/<path>.json` |
| Exclusions | `data/<ns>/immersive_brewing/exclusions/<path>.json` |
| Workshop profile (hands-on work) | `data/<ns>/immersive_brewing/workshop_profiles/<path>.json` |

A definition's id is `<ns>:<path>`. At most 2048 files of each definition kind are read; lists hold at most 256 entries.
Every file has `schema_version` (default 1); a newer version than 1 is rejected. Definition files accept Forge load
conditions exactly as recipes do (`"conditions": [{"type": "forge:mod_loaded", "modid": "..."}]`): when they fail, the
file is not loaded at all, with no error or warning.

Immersive Brewing's own data for optional mods lives under `compat/<modid>/` (recipes, reagent profiles, effect policies)
and is conditioned on that mod. Its recipes and policies are excluded by id like other built-in data
(`"recipes": ["immersive_brewing:compat/apothecary/potion/awkward__anvil"]`,
`"effect_policies": ["immersive_brewing:compat/apothecary"]`); a reagent profile is outranked by a higher `priority`.

## Loading and reloading

* **First load** (server start): invalid definition files are skipped and reported; the valid rest activates.
* **Reload** (`/reload`): the new definitions are validated as a whole. If any file is invalid, the whole previous
  dataset stays active and the log names every problem. Otherwise the new dataset activates atomically and clients
  receive it. Either way, a batch from 1.1 that is open in its original interface is sealed first (nothing is lost; it
  resumes afterwards, revalidated against the active data). Hands-on batches keep running: the workshop setup they were
  set out with is saved in their process payload, and the recipe is checked again when they complete. Work that no
  longer validates is held with a reason; it is never moved to another route or re-rolled.
* Recipes follow Minecraft's own rules; an infusion recipe that parses but does not validate is listed as unsupported
  rather than offered. The server support report is sent to clients atomically in pages of at most 256 entries after
  login and reload; a partial transfer never replaces the client's previous complete report.

Diagnostics, for operators: `/immersivebrewing report` (writes `<world>/immersive_brewing/coverage-report.txt` with
every recipe's status, data errors and warnings, and transformations no plan covers), `/immersivebrewing recipe <id>`,
`/immersivebrewing profile <item>` (which reagent profile wins, at which layer, and any tie), and
`/immersivebrewing explain` for the held potion's effect-policy decisions. Given a stable key from a journal/report,
`/immersivebrewing explain plan <key>` re-resolves that route and shows each source, adapter id/version, contents policy,
quick-finishing eligibility and an actionable refusal when the route no longer resolves.

### Server support statuses and destination search

The Notebook Compatibility page and favourite availability use the current server report, not a client reconstruction:

| Status | Meaning |
| --- | --- |
| `SUPPORTED` | The authored recipe validated through its live adapter and its registered effects may use the quality policy. |
| `METADATA_ONLY` | The recipe validated, but quality is recorded without scaling its effects. |
| `EXCLUDED` | A current item, recipe, policy or adapter exclusion deliberately removed it. |
| `UNSUPPORTED` | Validation failed, for example because an adapter/reagent is missing, live output differs, the path cycles or declared input is too loose. The translated player reason and operator report retain the distinction. |

These statuses describe recipes. Destination search describes reachable *chains* and has separate limits. Search runs
incrementally on the server thread, shortest paths first: at most 256 graph edges per server tick globally and 64 per
active request slice. A cached catalog stops after 32,768 examined edges or 8,192 pending nodes, keeps at most 32 input
catalogs, and obeys `maxPlanSteps` (1-8). Results are filtered before 24-entry paging. A response says when traversal
is incomplete or budget-limited and reports omitted alternatives; an omitted route is not relabeled unsupported.
Contextual adapters are checked against the currently open Kettle tray and are deliberately not advertised as fully
enumerable. Selecting, repeating or pinning a stable authored plan directly replays its current segments, independent
of search pages and tray-list caps.

## Infusion plans: `immersive_brewing:infusion`

```json
{
  "type": "immersive_brewing:infusion",
  "schema_version": 1,
  "adapter": "immersive_brewing:forge_registry",
  "input": { "item": "minecraft:potion", "potion": "minecraft:awkward", "match": "canonical" },
  "steps": [
    { "reagent": { "item": "minecraft:sugar" }, "phase": "infuse", "role": "effect",
      "preparation_profile": "immersive_brewing:sugar" }
  ],
  "expected_output": { "item": "minecraft:potion", "potion": "minecraft:swiftness" },
  "batch": { "max_servings": 6, "servings_per_reagent": 3 },
  "brew_profile": "immersive_brewing:light_foam"
}
```

| Field | Status | Meaning |
| --- | --- | --- |
| `adapter` | accepted | Adapter id; default `immersive_brewing:forge_registry`. Unknown or excluded adapter: the plan is unsupported/excluded. |
| `input.item` | accepted, required | Container item. |
| `input.potion` | accepted | Potion id. |
| `input.match` | accepted | `canonical` (default with a potion): item, potion and every other tag equal the plain potion, except a `display` compound. `potion`: item and potion id; other tags allowed. `item` (default without a potion): any potion in that container, a container conversion. |
| `steps` | accepted, required | 1 to 8 steps, applied in order. The server's `maxPlanSteps` also caps chained plans. |
| `steps[].reagent` | accepted, required | A standard ingredient (`item` or `tag`). Up to 16 non-excluded items are tried; all must give the same result. |
| `steps[].phase` | accepted, required | `bloom`, `infuse` or `settle`: the reaction phase that receives the reagent. |
| `steps[].role` | accepted | `base`, `effect`, `corruption`, `modifier` or `container`. Default: `base` for bloom, `effect` for infuse, `modifier` for settle. Effect and corruption steps create a new score; modifier and container steps keep the input's extraction and cap quality at the input's. |
| `steps[].preparation_profile` | accepted | Reagent profile id for this step (the explicit precedence layer). |
| `expected_output.item` | accepted, required | Container item of the result. |
| `expected_output.potion` | accepted | Potion id, or `same` (keep the input's potion; container conversions). Omitted: any potion. |
| `expected_output.match` | reserved | Parsed; outputs are compared by item and potion. |
| `batch.max_servings` | accepted | 1 to 6, default 6; the server's `maxBatchServings` narrows it. |
| `batch.servings_per_reagent` | accepted | 1 to 6, default 3. It can only make a plan costlier: the adapter's own value caps it. |
| `brew_profile` | accepted | Brew profile for the reaction. Default: the profile of the reagent in the last effect step, else `immersive_brewing:generic`. |
| `conditions` | accepted | Forge load conditions. |
| `group` | reserved | Ignored. |

The server validates every plan through its adapter on copies: each step's real result must equal the next step's
input, a step may not return to a potion already in the chain, and the final result must match `expected_output`.
Plans that fail are reported (`/immersivebrewing recipe <id>`) and never offered. Plans chain: from any potion the
Kettle searches validated paths up to `maxPlanSteps`, deduplicated by result and reagents. When two recipes describe
the same result with the same reagents, the one whose id sorts first is offered. Search is incremental and bounded as
described above; current tray context is appended before filtering and paging.

A plain Forge brewing recipe whose declared input names a potion (an input stack with that potion's NBT) brews only that
potion at the Kettle, even where its vanilla ingredient lets a brewing stand accept any potion in the same container; a
plan or query that relies on such a match is unsupported (`loose_input`). A recipe class with its own input test
decides its inputs itself.

Costs: a step consumes `ceil(servings / servings_per_reagent)` reagents and as many fuel charges. A Prepared Reagent Kit
replaces one reagent unit. Reagents with a crafting remainder (dragon's breath) return it once per unit consumed.
Raw items remain valid wherever the underlying recipe accepts them. A raw preparable reagent contributes extraction
50; a reagent profile never makes Mortar preparation mandatory for an otherwise valid brew.

### Container-only plans and quick finishing

A `role: "container"` label does not by itself authorize data preservation or finishing. Development version 1.1.0
offers quick finishing only when every resolved step is a proven potion→splash or splash→lingering conversion, the
live winning registry recipe is the trusted vanilla conversion, and canonical potion contents remain equivalent. The
server still resolves the live plan, consumes its real reagents/fuel, returns crafting remainders and checks output
space. The complete source stack data is preserved. Finishing adds no quality score or milestone and its configured
loaded-world duration is snapshotted when the ledger starts. A normal interactive reaction remains available when the
proof or server setting does not allow finishing.

## Purification: `immersive_brewing:distillation`

| Field | Status | Meaning |
| --- | --- | --- |
| `input.items` | accepted, required | Container items accepted (at least one). |
| `input.potions` | accepted | Potion ids accepted. With `potion_namespaces`, a potion matching either is accepted; with neither, any potion is. |
| `input.potion_namespaces` | accepted | Potion namespaces accepted. |
| `input.exclude_potions` | accepted | Potion ids refused. |
| `input.requires_effects` | accepted | Default `true`: potions without effects are refused. |
| `group_size` | accepted | 2 to 6, default 3. One or two groups per run. |
| `yield` | accepted | 1 to 6, default 2; must be smaller than `group_size`. |
| `fuel_charges` | accepted | 1 to 20 per group, default 1. |
| `quality_cap` | accepted | 35 to 89, default 70; the server's `purificationQualityCap` narrows it. |
| `container` | accepted, required | `{ "item": ... }` returned for each sacrificed potion. |

A purification needs identical potions (contents and brewing data) that are below the cap and have fewer purifications
than `maxPurificationsPerLineage`. Ordinary potions start from quality 50.

## Reclamation: `immersive_brewing:reclamation`

| Field | Status | Meaning |
| --- | --- | --- |
| `input` | accepted, required | As for distillation; `requires_effects` defaults to `true`. |
| `group_size` | accepted | 1 to 6, default 3. |
| `result` | accepted, required | `{ "item": ..., "count": 1-4 }` per group. |
| `container` | accepted, required | Returned once per potion consumed. |
| `fuel_charges` | accepted | 0 to 20 per group, default 1. |
| `duration_ticks` | accepted | 20 to 1200, default 200. Passive; it keeps running without a player. |

## Reagent profiles

```json
{
  "schema_version": 1,
  "priority": 100,
  "selector": { "items": ["minecraft:sugar"] },
  "preparation": { "mode": "crystalline", "duration_ticks": 240, "endpoint": [0.6, 0.78],
                   "pressure_limit": 0.7, "overwork_threshold": 0.86 },
  "reaction": { "profile": "immersive_brewing:light_foam", "addition_cooling": 0.08, "foam_sensitivity": 0.65 }
}
```

| Field | Status | Meaning |
| --- | --- | --- |
| `priority` | accepted | -100000 to 100000, default 0; decides within a precedence layer. |
| `selector` | accepted, required | `items`, `tags`, `namespaces`, `recipes` (see Selectors). An empty selector is rejected. |
| `preparation` | accepted | Omitted: the reagent is used raw (extraction 50) and the Mortar refuses it. |
| `preparation.mode` | accepted | `crystalline`, `fibrous`, `soft`, `brittle`, `liquid`, or `none` (same as omitting `preparation`). |
| `preparation.duration_ticks` | accepted | 100 to 400, default 240. |
| `preparation.endpoint` | accepted, required | `[low, high]` within 0.05 to 0.95, low not above high: the texture to stop at. |
| `preparation.pressure_limit` | accepted | 0.1 to 1.0, default 0.70. |
| `preparation.overwork_threshold` | accepted | 0.06 to 0.99 and above the endpoint's high end; default `min(0.97, high + 0.08)`. |
| `reaction.profile` | accepted | Brew profile used when this reagent's step decides the reaction; it must exist, or the file is invalid. |
| `reaction.addition_cooling` | accepted | 0 to 0.3, default 0.06: heat lost when the reagent goes in. |
| `reaction.foam_sensitivity` | accepted | 0 to 2, default 1.0 (alias `sensitivity`): how strongly the addition stirs up the reaction's signature. |

## Brew profiles

```json
{
  "schema_version": 1,
  "infuse": { "signature": "light_foam", "heat_band": [0.46, 0.6], "circulation_band": [0.25, 0.55] },
  "bloom": { "signature": "bloom_simmer", "heat_band": [0.48, 0.62], "circulation_band": [0.08, 0.40] },
  "disturbances": ["cold_draft", "burner_flare"],
  "inertia": 1.0,
  "presentation": { "accent": "#E8EEF8", "particle": "steam" }
}
```

| Field | Status | Meaning |
| --- | --- | --- |
| `infuse` | accepted, required | The main reaction phase. |
| `bloom`, `settle` | accepted | Optional phases. Defaults: bloom `bloom_simmer`, heat 0.48-0.62, circulation 0.08-0.40, initial trouble 0.25; settle `settle_clear`, heat 0.36-0.48, circulation 0-0.22, initial trouble 0.30. |
| `<phase>.signature` | accepted | `none`, `bloom_simmer`, `settle_clear`, `light_foam`, `dense_sediment`, `delicate_shimmer`, `magma_streaks`, `suspended_bubbles`, `dark_whorls`, `aligning_glints`, `floating_motes`. |
| `<phase>.heat_band` | accepted, required | `[low, high]` within 0 to 1, at least 0.06 wide. |
| `<phase>.circulation_band` | accepted, required | `[low, high]` within 0 to 1, at least 0.10 wide. |
| `<phase>.initial_trouble` | accepted | 0 to 0.6, default 0. |
| `disturbances` | accepted | Any of `cold_draft`, `burner_flare`, `surge`. Omitted: all three; `[]`: none. |
| `inertia` | accepted | 0.5 to 2.0, default 1.0: how slowly the liquid follows the burner. |
| `presentation.accent` | accepted | `#RRGGBB`, validated and synced for client/world presentation. Accessible palettes remain authoritative for critical UI colors. |
| `presentation.particle` | accepted | One of `steam`, `foam`, `bubble`, `glint`, `mote`; default `steam`. An unknown value warns and falls back to `steam`. Client density and reduced-motion settings bound emission. |

Difficulty tier, phase lengths and disturbance timing come from the plan and the seeded schedule, not from the profile.
The phase bands, circulation and disturbances describe the 1.1 Kettle simulation (still used to finish a batch saved by
1.1). Hands-on batches take their reaction from a workshop profile, or translate the `infuse` phase when there is none
(next section).

## Workshop profiles

How the hands-on work feels at each apparatus. Every value is optional tuning on top of the existing data: a pack that
ships no workshop profiles gets a translation of its reagent and brew profiles, so existing datapacks keep working. The
built-in profiles live in `data/immersive_brewing/immersive_brewing/workshop_profiles/{preparation,brewing,distillation}/`.
`schema_version` is 1; a newer version is rejected. Unknown fields are reported as warnings and ignored.
`presentation` (a resource location, optional) names the client presentation profile the batch is shown with (see
Presentation profiles below). The server only passes the id along in the station's snapshot; it never decides a cost,
a yield or a rule. The built-in brewing profiles name `immersive_brewing:brewing/<reaction>`.

### Preparation (`"kind": "preparation"`)

```json
{
  "schema_version": 1,
  "kind": "preparation",
  "priority": 100,
  "selector": { "items": ["minecraft:sugar"] },
  "source_profile": "immersive_brewing:sugar",
  "material": "crystalline",
  "work": {
    "operations": ["press", "grind", "fold"],
    "required_work": 1.0,
    "max_work_per_tick": 0.05,
    "useful_effort": [0.25, 0.7],
    "endpoint": [0.6, 0.78],
    "overwork_starts": 0.86
  }
}
```

| Field | Status | Meaning |
| --- | --- | --- |
| `selector` | accepted | `items`, `tags` or `namespaces` the profile covers, matched as described under Selectors and precedence (`recipes` is not accepted). A profile needs a non-empty `selector` or a `source_profile`. |
| `source_profile` | accepted | A reagent profile id. A workshop profile naming the reagent profile that matched the item wins over any selector match. |
| `priority` | accepted | Integer, default 0; the highest wins among profiles of the same layer. |
| `material` | accepted, required | `crystalline`, `fibrous`, `soft`, `brittle` or `liquid`: how the material looks and resists in the bowl. |
| `work.operations` | accepted | 1 to 4 of `press`, `grind`, `fold`, `sweep`. Default `press`, `grind`, `fold`. A material that is only swept and folded refuses presses. |
| `work.required_work` | accepted | 0.25 to 4.0, default 1.0: how much useful work one batch needs (higher is longer). |
| `work.max_work_per_tick` | accepted | 0.005 to 0.2, default 0.05: the most work the server credits per tick, so frantic input is not faster. |
| `work.useful_effort` | accepted | `[low, high]` within 0.05 to 1.0, at least 0.05 wide, default `[0.25, 0.7]`: pressure that does useful work. Harder strokes add fines and clumping. |
| `work.endpoint` | accepted, required | `[low, high]` within 0.05 to 0.95, at least 0.04 wide: the processed fraction where the texture is right. |
| `work.overwork_starts` | accepted | 0.06 to 0.99 and above the endpoint's high end; default the high end + 0.08 (at most 0.98). Work past it lowers extraction. |

Resolution for an item: a profile whose `source_profile` names the item's winning reagent profile; else the best
selector match (the most specific layer, then the highest priority; see Selectors and precedence); else a translation of the reagent profile's
`preparation` block (same material, endpoint and overwork start; required work from its duration, useful effort from
its pressure limit). An item with neither is not preparable and brews at the raw extraction baseline.

### Brewing (`"kind": "brewing"`)

```json
{
  "schema_version": 1,
  "kind": "brewing",
  "target": "immersive_brewing:light_foam",
  "reaction": "light_foam",
  "temperature": { "ideal": [0.46, 0.6], "workable": [0.34, 0.74], "inertia": 1.0 },
  "treatment": { "step_seconds": 9.0, "rest_seconds": 3.0 }
}
```

| Field | Status | Meaning |
| --- | --- | --- |
| `target` | accepted, required | The brew profile id this reaction applies to. The highest `priority` wins. |
| `reaction` | accepted | Reaction family, default `generic`: `generic`, `light_foam`, `dense_sediment`, `delicate_shimmer`, `magma_streaks`, `suspended_bubbles`, `dark_whorls`, `aligning_glints`, `floating_motes`. The family decides which mistakes trouble the batch (too much agitation, too much heat, settling) and what the surface shows. |
| `temperature.ideal` | accepted | `[low, high]` within 0.2 to 0.95, at least 0.06 wide; default the family's band. Reacting inside it earns full heat control. |
| `temperature.workable` | accepted | `[low, high]` within 0.15 to 0.98 containing `ideal`; default `ideal` widened by 0.12 below and 0.14 above. Below it the reaction slows toward a stop and doses tipped in score less; above it the brew boils hard and reacts more slowly. |
| `temperature.inertia` | accepted | 0.5 to 2.0, default 1.0: how slowly the liquid follows the burner. |
| `treatment.step_seconds` | accepted | 3 to 30, default 9: how long one dose's reaction takes once it is dissolved and the heat is workable. |
| `treatment.rest_seconds` | accepted | 1 to 10, default 3: how long the calm surface must rest before bottling. |

Without a brewing workshop profile, a brew profile translates as: the `infuse` signature as the family, its heat band as
the ideal band (clamped to 0.2-0.92, at least 0.06 wide), the workable band as above, its inertia, 9 s steps and a 3 s
rest (4 s for dense sediment). Approved assistance widens the ideal band (by at most the server cap).

### Distillation (`"kind": "distillation"`)

```json
{
  "schema_version": 1,
  "kind": "distillation",
  "target": "immersive_brewing:distillation/vanilla_potions",
  "fractions": [0.2, 0.78],
  "run_seconds": 27.0
}
```

| Field | Status | Meaning |
| --- | --- | --- |
| `target` | accepted, required | The purification recipe id. The highest `priority` wins. |
| `fractions` | accepted | `[heads end, tails start]` within 0.08 to 0.92, default `[0.20, 0.78]`. The heads must end by 0.40 and the clear heart must span at least 0.25 of the run. |
| `run_seconds` | accepted | 10 to 60, default 27: the length of a run at a steady middle heat. |

A recipe without a distillation profile uses the defaults.

### Validation, reload and running batches

Workshop profiles are validated with the rest of the definitions and activate atomically with them; an invalid file
keeps the previous dataset on `/reload`, as for other definitions. When a batch is set out, the resolved setup and the
profile it came from (or `translated:<id>` for a fallback) are saved in its process payload, so a later reload never
changes a running batch. `/immersivebrewing profile <item>` and `/immersivebrewing explain plan <key>` show which workshop
profile, or which translation, applies.

## Presentation profiles (resource packs)

How a batch looks in the close-up working view, in client resources rather than server data:
`assets/<namespace>/immersive_brewing/presentation/<path>.json`, named `<namespace>:<path>` by a workshop profile's
`presentation` field. They are reloaded with the client's resources (F3+T); changing one never needs a world migration.
A presentation profile can not change a cost, a yield, reach, permissions or quality, and an unsupported or missing
profile never makes a recipe unusable: anything missing or invalid falls back to the generic scene, with a warning in
the client log. In this version the Kettle uses a profile for its liquid, dose arrivals, surface accents and vapour; the
close-up camera applies a profile's small angle adjustments at any station.

```json
{
  "schema_version": 1,
  "station": "kettle",
  "material": "crystal",
  "liquid": { "tint": "#6FB7E0", "clarity": 0.9, "foam": "#FFF8E0" },
  "fragments": { "count": 3, "scale": 1.0, "item": "minecraft:sugar" },
  "effects": { "glints": 1.0, "bubbles": 0.3, "motes": 0.0, "steam": 0.5 },
  "camera": { "pitch": 0, "yaw": 0 }
}
```

| Field | Meaning |
| --- | --- |
| `schema_version` | 1 (the default). A newer or older version is rejected and the generic scene is used. |
| `station`, `sounds` | Accepted and currently unused. Any other unknown field is reported as a warning and ignored. |
| `material` | The arrival family for a dose that neither the reagent table below nor the dose's reagent profile describes: `crystal` (grains or a short stream, brief cloudy trails), `fibrous` (a few recognisable pieces that soften and leave infusion trails), `paste` (a cohesive dollop folding in), `liquid` (a stream and a spreading plume), `particulate` (a restrained cluster of flakes or motes) or `generic` (the default: a neutral piece). |
| `liquid.tint` | `#RRGGBB`, mixed into the reaction's own stage colours (up to 35 % as each dose's reaction proceeds). Without it the liquid shows the stage colours alone. |
| `liquid.clarity` | 0.2-1.0, default 0.75: how strongly the liquid's colour reads over the vessel (its opacity; lower is more see-through). |
| `liquid.foam` | `#RRGGBB`, default `#F4F6FF`: the colour of froth on the surface. |
| `fragments.count` | 0-6, default 3: scales how many pieces each dose arrives as, relative to its arrival family's usual number (3 or 0 keep it, 6 doubles it, rounded down and within the surface's limit). |
| `fragments.scale` | 0.5-2.0, default 1.0: the size of those pieces. |
| `fragments.item` | An item id drawn for the pieces; an id that is not a registered item is reported and the dose's own item is used. |
| `effects.glints` | 0-1, default 0: up to six glints riding the flow, on top of what the reaction family shows. |
| `effects.motes` | 0-1, default 0: up to eight motes on the surface. |
| `effects.bubbles` | 0-1, default 0.5: scales the bubbles that break at the surface with heat (0.5 keeps the usual number, 1 is half as many again, 0 half as many). |
| `effects.steam` | 0-1, default 0.5: scales the vapour over a working kettle in the same way. |
| `camera.pitch` | -10 to 10 degrees added to the station's authored close-up pitch. |
| `camera.yaw` | -20 to 20 degrees added to its yaw. |

A value outside its range rejects that profile (the generic scene is used). The reagent table
`assets/immersive_brewing/immersive_brewing/presentation/reagents.json` says how each known ingredient arrives:

```json
{ "schema_version": 1, "reagents": { "minecraft:sugar": { "material": "crystal", "color": "#F4F1EA", "size": 0.6 } } }
```

`size` is 0.5-2.0 (default 1). Entries for items that are not installed are skipped silently; an invalid entry is
reported and skipped. An ingredient without an entry arrives as the family its reagent profile prepares as (crystalline
and brittle as `crystal`, fibrous, soft as `paste`, liquid), else as a neutral generic piece drawn with its own item.

## Effect policies

Which effects quality may lengthen, and by how much. An effect no policy selects is metadata-only: quality is recorded
and shown but never changes it.

```json
{
  "schema_version": 1,
  "priority": 0,
  "effects": ["minecraft:speed", "minecraft:strength"],
  "quality_mode": "duration",
  "minimum_multiplier": 0.8,
  "maximum_multiplier": 1.2
}
```

| Field | Status | Meaning |
| --- | --- | --- |
| `effects`, `tags`, `namespaces` | accepted | The selector, at the top level: effect ids, mob-effect tags, namespaces. An empty selector is rejected; `recipes` is rejected here. |
| `priority` | accepted | As for reagent profiles. |
| `quality_mode` | accepted | `duration` (default) or `metadata_only`. |
| `minimum_multiplier` | accepted | 0 to 1, default 0.80: the duration multiplier at quality 0. The server's `qualityDurationMin` raises it. |
| `maximum_multiplier` | accepted | 1 to 4, default 1.20: the multiplier at quality 100. The server's `qualityDurationMax` lowers it. |
| `allow_amplifier_change` | rejected when `true` | Quality never changes an amplifier. `false` is accepted. |
| `allow_infinite_duration_change` | rejected when `true` (reserved) | Infinite durations stay unchanged. `false` is accepted. |

Durations scale only for finite effects of a policy-selected effect, only for registered potion effects (never custom
effects on the stack), and never for instant effects or for potions whose effects fall in different categories (Turtle
Master). The multiplier is `(200 + Q) / 250` within the allowed range.

## Exclusions

```json
{ "schema_version": 1, "items": ["minecraft:phantom_membrane"], "item_tags": ["#forge:mushrooms"],
  "recipes": ["immersive_brewing:infusion/potion/awkward__sugar"], "effect_policies": [], "adapters": [] }
```

Every exclusions file is merged, and an exclusion always wins over any selector. An excluded item gets no reagent
profile, appears in no plan or contextual query, cannot be prepared, and is refused by the Alembic. A recipe whose input
or output is excluded, or whose reagent matches only excluded items, is reported as excluded, as are excluded recipes
and recipes naming an excluded adapter; an excluded effect policy is dropped. The coverage report counts registry
transformations that exclusions removed separately from uncovered ones.

## Selectors and precedence

A selector combines its kinds with AND and the entries of one list with OR: `{"items": ["a", "b"], "namespaces":
["x"]}` matches a or b, provided its namespace is x. The most specific kind present decides the layer:

1. **Explicit**: a profile a recipe step names with `preparation_profile`.
2. **Recipe**: `recipes` (reagent profiles only).
3. **Id**: `items` or `effects`.
4. **Tag**: `tags`.
5. **Namespace**: `namespaces`.
6. **Fallback**: the built-in default.

The most specific matching layer wins; inside a layer the highest `priority` wins; equal priority falls to the
lexicographically smallest id, and the tie is reported by `/immersivebrewing profile`. Profiles are never merged field
by field.

## Tags and load conditions

| Tag (items) | Meaning |
| --- | --- |
| `immersive_brewing:burner_fuel` | Fuel for the Kettle and Alembic burners (blaze powder, 20 charges each by default). |
| `immersive_brewing:brewing_paddles` | Items accepted in the Kettle's paddle slot. |
| `immersive_brewing:carrier_excluded` | Items no station slot or Batch Carrier accepts (carriers, recovery batches, bundles). |
| `immersive_brewing:carrier_storable` | Extra items a Batch Carrier may hold besides potion containers. |

| Condition | True when |
| --- | --- |
| Forge `forge:mod_loaded` | The named mod is loaded. Valid on recipes and Immersive Brewing definition files through their `conditions` array. |
| `immersive_brewing:smithing_fallback` | Immersive Smithing is absent or its integration is off. |
| `immersive_brewing:smithing_active` | Immersive Smithing is present and its integration is on. |
| `immersive_brewing:tanning_enabled` | Immersive Tanning is present and its integration is on. |

## A complete small pack

[`docs/examples/1.1.0/`](examples/1.1.0/) is a copyable pack-format-15 example for Minecraft 1.20.1. It contains one
small valid file for an ingredient/reagent profile, brew presentation, duration effect policy, generic container
transformation and Forge `mod_loaded` condition. The examples use only existing schema-1 fields and registered vanilla
ids; their README explains that an infusion plan describes a transformation the live adapter already performs.

The GameTest resources carry a working example under `src/gameTest/resources/data/ib_test/`: a plan with its own brew
profile (`recipes/infusion/mundane_from_redstone.json`, `immersive_brewing/brew_profiles/still_water.json`), two equal
reagent profiles that resolve by id with a tie diagnostic, a recipe whose registry result differs (reported, never
offered), a definition with an inverted range (skipped on first load), and an exclusion. `DataGameTests` checks each.
