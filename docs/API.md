# Immersive Brewing API

API version **1** (`ImmersiveBrewingAPI.VERSION`), package `com.otectus.immersivebrewing.api`. Only the classes in that
package and in `api.event` are public contract; everything else in the mod is internal and may change in any release.
Every call and event below runs on the **server thread** unless stated otherwise.

## Registration

Register during common setup, or send IMC to `immersive_brewing` before IMC processing ends. Adapters freeze when
Immersive Brewing processes its IMC; a later `registerAdapter` call is refused and logged.

```java
// In your mod's InterModEnqueueEvent handler:
InterModComms.sendTo("immersive_brewing", ImmersiveBrewingAPI.IMC_ADAPTER, () -> new MyBrewingAdapter());
InterModComms.sendTo("immersive_brewing", ImmersiveBrewingAPI.IMC_EFFECT_HANDLER, () -> new MyEffectHandler());

// Or directly, during FMLCommonSetupEvent:
ImmersiveBrewingAPI.registerAdapter(new MyBrewingAdapter());   // false if the id is already owned
ImmersiveBrewingAPI.registerEffectHandler(new MyEffectHandler());
```

| IMC method | Payload |
| --- | --- |
| `register_adapter` | a `BrewingAdapter` (a `Supplier` of one is also accepted) |
| `register_effect_handler` | an `IEffectQualityHandler` (likewise) |

## `BrewingAdapter`

An adapter lets the Brewer's Kettle delegate one transformation step to another brewing system. That system decides the
result; Immersive Brewing only reserves materials, runs the reaction game and records quality.

| Method | Contract |
| --- | --- |
| `id()` | Unique id, stored with every job. |
| `version()` | Contract version, stored with every job; a changed version holds sealed jobs for recovery instead of resuming them. |
| `priority()` | Higher runs first among adapters that apply; ties fall to id order. |
| `applies(input, ingredient)` | Cheap check. No heavy allocation. |
| `preview(input, ingredient)` | The result for **one serving**, or `Preview.unsupported(reason)`. Works on copies; must not consume, reroll or mutate anything. |
| `batchPolicy(input, ingredient)` | `BatchPolicy(servingsPerReagent, maxServings, reagentsPerApplication, sharedReaction)`. A step costs `ceil(servings / servingsPerReagent) × reagentsPerApplication` reagents and `ceil(servings / servingsPerReagent)` fuel charges. A recipe may declare fewer servings per reagent than its adapter, never more. |
| `commit(input, ingredient)` | Performs the step for one serving, exactly once. It must return what `preview` returned for the same inputs; otherwise the job is held for recovery with every material intact. |
| `contentsPolicy(output)` | `EFFECTS`: quality changes effect durations and drinking time through the effect policies (vanilla potion items only). `METADATA_ONLY`: quality is recorded and shown, the output behaves exactly as its source made it. |

An adapter that cannot keep the preview/commit promise cannot support queued multi-step plans and should not be
registered. Inputs are canonical (Immersive Brewing's compound removed) count-1 stacks.

The built-in adapter is `immersive_brewing:forge_registry` (version 1, priority 0). It delegates to
`BrewingRecipeRegistry.getOutput`, which covers vanilla mixing and every registered Forge `IBrewingRecipe`; one reagent
serves up to three bottles, as at the brewing stand.

Exclusion files can disable an adapter by id (see [DATAPACKS.md](DATAPACKS.md)).

## `IEffectQualityHandler`

Declares how quality applies to another mod's potion-like item. Without a handler, items other than the vanilla potion,
splash potion, lingering potion and tipped arrow are metadata-only.

| Method | Contract |
| --- | --- |
| `handles(stack)` | Whether this handler resolves the stack. |
| `paths()` | The application paths it resolves: `DRINK`, `SPLASH`, `LINGERING`, `ARROW`. |
| `resolve(stack, quality)` | Effective effects at `quality` (0-100). Must not mutate the stack or any shared list, and must be idempotent. Results are copied before use. |

API version 1 consults handlers on **`DRINK`** (finishing `PotionItem` use) and **`SPLASH`** (splash impact), the two
paths that apply the stack itself. Lingering clouds and arrows keep only the potion, so on `LINGERING` and `ARROW` such
items stay metadata-only. The path is taken from the item class: `LingeringPotionItem`, other `ThrowablePotionItem`,
`TippedArrowItem`, otherwise drinking. Ordinary (ungraded) stacks and quality 50 are never passed to a handler. A handler
that throws is logged and the item's own effects apply.

## Queries

| Call | Returns |
| --- | --- |
| `metadata(stack)` | `Optional<BrewingMetadataView>`: extraction, control, quality, grade (`crude`, `standard`, `fine`, `masterwork`), lineage, purification count, brewer UUID and name, refiner, label, note. Empty for ordinary potions. |
| `quality(stack)` | The recorded quality, or 50 for an ordinary potion. |
| `canonical(stack)` | A copy without Immersive Brewing's compound: what brewing queries and recipe identity use. |
| `plans(server, input, servings)` | Every supported catalog plan from `input` with exact reagent counts, fuel charges and remainders for `servings`, as the server would charge them. |

Read brewing data through `metadata(stack)` rather than the stack's NBT. For reference, it lives in the stack tag under
`immersive_brewing` (schema 1): `schema`, `E`, `C`, `Q` (bytes), optional `lineage` (long), `purified` (byte),
`brewer` (UUID), `brewerName`, `refiner`, `label`, `note` (strings). A compound with a newer schema is preserved and never
rewritten.

## Events

All are posted on `MinecraftForge.EVENT_BUS`, server thread.

| Event | When | Cancel / modify |
| --- | --- | --- |
| `BrewPlanEvent` | `preview() == true`: once per plan listed at a Kettle. `false`: a plan is about to be reserved. No inventory has changed. | Cancelable. A preview cancel hides the plan; a start cancel refuses to reserve anything. |
| `BrewResultEvent.Pre` | A Kettle brew finished and every serving was replayed through its adapters; before any output exists. | Cancelable: the job is held for recovery (`vetoed`) with every material intact; nothing already charged is lost. `narrowQuality(q)` can lower the quality, never raise it. |
| `BrewResultEvent.Post` | Once, after the outputs are in the rack. `output()` carries the final metadata. | Not cancelable. |
| `ReagentPreparedEvent` | Once, after a Mortar placed its prepared kits. | Not cancelable. |
| `BatchRefinedEvent` | Once, after an Alembic purification (`purified() == true`, with quality before and after) or reclamation (`false`) placed its output. | Not cancelable. |

Inventory changes always belong to the station's transaction; listeners must not move station items. Use the Post
events for progression and rewards; they fire exactly once per committed result.

## Stability

Within API version 1: the classes and members above keep their signatures and meaning; new members may be added. A
breaking change raises `ImmersiveBrewingAPI.VERSION`.
