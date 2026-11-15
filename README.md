# Immersive Brewing

Brew by hand. Grind reagents with pestle and scraper at the Apothecary's Mortar, tend the reaction with burner and paddle
at the Brewer's Kettle and divert the clear heart of a run at the Alembic. The recipe decides the magic; your
craftsmanship decides the quality.

Immersive Brewing adds a workshop beside the brewing stand rather than a new potion system. Every potion it makes is
exactly the potion the game's own brewing would make, plus a small record of how well it was made. A score of 50 is
the ordinary potion; better work lasts a little longer and drinks a little faster, worse work a little less.

Minecraft 1.20.1, Forge 47 or later, Java 17. Install on both the client and the server.

This branch is in development (the version it builds is set in `gradle.properties`); this document does not announce a
release. The hands-on workshop is described in
[`docs/reports/1.2.0-hands-on-overhaul.md`](docs/reports/1.2.0-hands-on-overhaul.md) and its close-up camera, direct
setup and continuous transfers, with their validation status, in
[`docs/reports/1.2.0-close-up-workshop.md`](docs/reports/1.2.0-close-up-workshop.md).
The texture, model, interface and working-view refinements, with comparison sheets and runtime checks, are recorded in
[`docs/reports/1.2.0-visual-refinement.md`](docs/reports/1.2.0-visual-refinement.md). The follow-up review of vanilla-style
materials, connected geometry, panel borders and HUD framing is in
[`docs/reports/1.2.0-vanilla-style-review.md`](docs/reports/1.2.0-vanilla-style-review.md).

## The workshop

| Station | What it does |
| --- | --- |
| **Apothecary's Mortar** | Prepares one to four units of a reagent at once in the bowl: press and grind with the pestle until the texture is even, fold uneven spots with the scraper, then scrape it into the dose cup. Each material behaves differently (crystalline, fibrous, soft, brittle, liquid); working past the endpoint only makes fines. Each unit becomes a Prepared Reagent Kit carrying an *extraction* score. |
| **Brewer's Kettle** | Brews one to six bottles of one kind as one batch: tip the bottles in, set the burner, tip each dose in from its cup when the liquid is warm, stir with the paddle while anything is undissolved, let the surface settle and fill each bottle from the spout. Each reaction family (foam, sediment, shimmer, streaks, bubbles, whorls, glints, motes) wants its own balance of heat and agitation and shows on the surface what it needs. Its *control* score and the reagents' extraction make the quality. |
| **Alembic** | Purifies three identical potions into two better ones (or six into four), once per potion line, up to quality 70: fill the coolant jacket with water, pour the charge, close the clamp, set the burner and coolant valves and move the diverter so the cloudy heads and hazy tails go to waste and only the clear heart reaches the receiver. Its fitting chooses purification or reclamation (and shows the other's outcome first); reclamation turns unwanted potions into Spent Alchemical Salt and their glass bottles on its own, without a player. |
| **Apothecary Rack** | Stores six exact potion stacks with reservation ghosts and short shelf labels. Take, place, pack and unpack directly at its positions and carrier cradle; it packs matching stacks into a Batch Carrier or restocks from the carrier without merging unlike metadata. |

Every station is worked in place. An ordinary right-click with an empty hand opens its *working view*: the camera moves
from your eyes to a close-up above the apparatus, so you look into the pot, the bowl or the still, and the cursor (or
`Tab`) picks its parts. Your hotbar stays as the supply strip, and your health and food stay in view. Right-clicking a
station while holding something puts one of it where it belongs (bottles in the rack, reagents on the tray or in the
bowl, fuel in the burner, a paddle on its rest, water in the jacket). Every action is a request the server checks
against the station's own part volumes, your reach and line of sight; a refused action changes nothing and says why.

Also: wooden and iron brewing paddles; the Batch Carrier (six exact stacks, a selected slot and named loadout); the
Brewer's Notebook (guided onboarding, server-backed recipe support, favourites, a 32-batch journal, pinned checklist
and practice); Recovery Batches; and Spent Alchemical Salt (with an ink sac: two gray dye; with one glass block or
pane: its light-gray stained counterpart).

## Your first batch

1. Craft a Brewer's Kettle and a brewing paddle. Holding the Kettle or an Apothecary's Mortar for the first time also
   grants the Brewer's Notebook. Its Practice page starts short lessons at a real, idle station (take up and put down a
   tool, the burner, a dose, then a whole batch); practice never consumes ingredients, creates output or awards
   productive milestones.
2. Right-click the Kettle with an empty hand. The camera moves above it: the opening and the liquid in the middle, the
   bottle rail in front, the dose tray and book stand on the left, the paddle on the right, the burner dial and fuel
   hatch at the front. Put in three water bottles, raw nether wart, raw sugar, blaze powder and the paddle from your
   hotbar: choose the item with its hotbar key or the mouse wheel and click where it goes (bottles on the rail, the
   reagents on the tray, the powder in the fuel hatch, the paddle on its rest). A click places one; shift-click, or `C`
   and a click, places the rest of the stack. The Mortar is optional for this batch: raw preparable reagents contribute
   extraction 50.
3. The small page on the book stand shows what the staged supplies make: Swiftness, three servings, the wart then the
   sugar, and the fuel it takes. A faint ghost shows where anything missing belongs. If several routes fit, click the
   book to turn to the next page.
4. Click a bottle to lift it, bring it over the opening and click to tip it in. That starts the batch the page shows,
   exactly as shown; nothing is used before. Shift-click another bottle (or `C`, then click it) to pour the rest, one
   after the other.
5. Scroll over the burner dial to warm the liquid until small bubbles rise. Click the first dose cup, carry it over
   the opening and click to tip it in. Click the paddle and move the cursor in circles through the liquid while pieces
   still float; where sediment settles, hold the left button to sweep the bottom. The surface shows what the brew
   needs; with guidance on, a short line says it too.
6. When the reaction settles, tip in the next dose (clicking its cup while you hold the paddle puts the paddle back on
   its rest). After the last one, turn the burner down, right-click to rest the paddle and let the surface calm. When
   it is ready, shift-click the spout to fill every bottle in turn. The bottles carry their grade (hover one for the
   effective duration); shift-click one to take them all.
7. Prepare sugar at the Mortar on a later batch when you want extraction above the raw baseline and a route toward
   Masterwork: put up to four units in the bowl, take up the pestle, press and grind until the texture is even, fold
   with the scraper where it is uneven and click the dose cup to scrape it in.

No menu is part of this. The full recipe search (right-click the book stand, or `K`) stays available: it searches every
route, can stage exact bottles from the Kettle, your inventory or a selected Batch Carrier, and a route chosen there
becomes the page.

Plans chain: from water the Kettle offers Awkward, Swiftness through Awkward, splash and lingering versions, and so
on. Starting from a potion you already have charges only the remaining steps.

Practice runs on your own client at an idle station of the right kind, with the same tools, heat and timing as a real
batch. Practising a favourite is route-specific: the server re-resolves that exact plan against current recipes and
sends only its description; press `P` in any idle station's working view for a default practice. Practice never
reserves a station, sends work to the server, consumes ingredients, creates output or fires productive milestones, and
the real drawer and route book stay closed while it runs.

## Quality

* Extraction (E) comes from preparation (an unprepared reagent counts 50); control (C) from the Kettle.
  Quality Q = (E + 3C) / 4, graded Crude (0-34), Standard (35-69), Fine (70-89), Masterwork (90-100).
* At Q 50 the potion is exactly the ordinary one. Finite durations of the effects an effect policy lists scale from
  0.8× (Q 0) to 1.2× (Q 100); amplifiers, instant effects, infinite durations and mixed bundles such as Turtle Master
  never change. Drinking takes up to four ticks more or less.
* Quality cannot be raised by brewing a finished potion further (a modifier keeps the lower of the two scores); only
  the Alembic can raise it, once.

## Controls (defaults; all rebindable under Controls > Immersive Brewing: Workshop)

| Action | Mouse | Keyboard |
| --- | --- | --- |
| Pick a part | point at it | `Tab` / `Backspace` cycle the parts, the ones the work needs now first |
| Choose the supply in hand | the wheel (with empty hands, away from a control) | the hotbar keys |
| Use the picked part (take a tool, lift a dose or bottle, tip it, open the spout, close the clamp, move the diverter, turn the Alembic's fitting) | left click | `Enter` |
| Work a held tool (stir, grind, fold) | move it over the liquid or the bowl | hold `Space` (or toggle it), move it with the arrow keys, `Page Up`, `Page Down` |
| Press with the pestle | click the bowl where you want to press | select the bowl, `Enter` (it presses where the pestle is) |
| Sweep the bottom with the paddle | hold the left button while stirring | `V` toggles |
| Put a tool down, or return a carried dose or bottle | right click, or click its rest | `X` |
| Turn a burner or valve | scroll over it, or drag it | select it, `Up` / `Down` |
| Effort (gentle, normal, firm) | the wheel while holding a tool | `F` |
| Batch action (place, pour, fill or take all in turn) | shift-click | `C`, then use the part |
| Look closer, and back | middle click | `Z` |
| Full control help | | `H` |
| Seal the batch | the Kettle's lid | `L` (any station) |
| Reopen a sealed batch | the lid, clamp or bowl | select it, `Enter` |
| Drawer, full recipe search, the next station, withdraw an untouched batch, practise | | `I`, `K`, `N`, `Delete`, `P` |
| Leave | | `Esc` (or the inventory key, or a movement key with empty hands) |

No action needs two keys at once. Taking damage, dying, changing dimension, walking out of reach or disconnecting
closes the working view and puts every tool down; losing window focus releases any held key or button. Nothing is
resumed automatically.

Client options (`config/immersive_brewing-client.toml`) include the camera style (the close-up glide, the same view as a
cut, or your own eyes with no camera movement), how much the view says (minimal, guided or detailed), whether your arm
is drawn at the grip, hold-to-grab, toggle work, keyboard-only tool movement, tool sensitivity, dead zone and visual
smoothing, a left-handed (mirrored) presentation, part labels, standard/high-contrast/colour-blind palettes, reduced
motion, particle and animation intensity, an optional small screen shake (off by default), written cues, numeric
readings, narration through Minecraft's narrator and sound volumes. None of them changes what the server accepts or how
work is scored. See `docs/CONFIGURATION.md`.

## Interruptions and recovery

A running batch is attended: it advances while its brewer or a helper who has done productive work stays within the
server's participant range (8 blocks by default). When nobody does for two seconds it seals itself, and a batch that
was running when its chunk unloaded or the server stopped comes back sealed: nothing progresses offline. Close the lid
(or press `L` at any station) to seal it yourself at any time. Sealing shuts the burner and rests the tools; nothing
else changes until the lid (or the clamp, or the bowl) is used again, and the batch carries on exactly where it
stopped. Another player may reopen it; the original brewer stays credited.
Leaving the working view does not stop a batch you are still standing next to. A Kettle batch whose first dose is not
yet in, or an Alembic charge that has not begun to condense, can be withdrawn with everything returned.

A datapack `/reload` does not interrupt a hands-on batch: its setup was fixed when it was set out, and finishing checks
the recipe again. Work that can no longer be validated is held with a reason instead of being changed into something
else. (A batch from 1.1 that is open in its original interface is sealed by a reload, as before.)

Breaking a station with a job inside drops one Recovery Batch holding the whole job, including any portions not yet
bottled. Install it at an idle station of the same kind to continue. If a mod or datapack the batch needs is missing,
the batch is held instead of deleted and comes back when the data does.

## The brewing stand

`brewingMode` in the world's server config:

* `COEXIST` (default): stands keep working; graded potions brewed further at a stand keep their line but fall to quality
  50 unless only the container changes.
* `REPLACE_COVERED`: a stand holding only transformations the Kettle covers does not start (no fuel or time spent);
  modifiers stay at the stand unless `replaceModifiers` is on, and a stand mixing covered and uncovered bottles brews
  as usual.
* `DISABLED`: no new jobs; sealed ones can still be resumed.

## Configuration

See [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) for every actual field, default, range, scope and reload/snapshot
rule, plus the **Artisan** and **Relaxed controls** examples. Server settings are per world; client accessibility and
presentation settings never alter scoring. This development version does not add unattended production or extra
score/reward milestones.

## Automation

Hoppers and pipes may insert bottles and reagents from above while a station is idle (the Mortar also accepts its
reagent from the sides), fuel from the sides at any time, and take finished, unreserved output from below. A job's
materials are never reachable. Water pipes fill empty bottles in an
idle rack, 250 mB per bottle; the Kettle stores no water. A comparator reads how full the finished output is; at
the Kettle, the menu's comparator toggle makes it report the job's state instead.

The Apothecary Rack exposes six exact slots and honors its reservation ghosts for insertion. A simulated capability
operation does not mutate it. The rack and carrier are transport/staging tools only: they preserve complete stacks and
never award quality, experience or brewing milestones.

## Compatibility

Tested together in a development environment (Forge 47.4.23); see `docs/TESTING.md` for the runs and their results.

| Mod | Version tested | Integration |
| --- | --- | --- |
| JEI | 15.20.0.135 | Brewing, preparation, purification and reclamation categories with fuel, servings and quality notes; transfer into the Kettle |
| Jade | 11.13 | Station state, contents, fuel, controller and recovery reason |
| Immersive Smithing | 1.1.0 | The iron paddle is smithed (its crafting recipe steps aside); Smithing quality changes paddle wear; carriers and recovery batches are never meltable |
| Immersive Tanning | 1.1.0 | Binding Leather (or any `forge:leather`) makes a strapped Batch Carrier: a different look, nothing more |
| Immersive Enchanting | 1.1.0 | Paddles take Unbreaking and Mending only; no conflict |
| Immersive Jewelry | 1.1.0 | Coexists: jewelry effects and drink-speed changes apply on top of Brewing's |
| Apothecary | 1.5.2 | 85 Kettle plans for its recipes (its config's default ingredients), Mortar profiles for its organic and crystal reagents, quality on 23 effects, Alembic purification and reclamation |
| Potions, Potions And More Potions | 1.0.4 | 69 plans (drinkable, splash and lingering), Mortar profiles, quality on 6 effects, Alembic |
| PotionsMaster | 0.6.0 | 24 ore-sight plans from Mundane, its calcinated powders ground at the Mortar, quality on every sight, Alembic |
| RPG Potions | 1.2.0 | 8 plans from Awkward, Mortar profiles, quality on 5 effects, Alembic |

The four potion mods are optional: their data (`compat/<modid>/`) loads only with them, nothing of theirs is
referenced in code, and every plan is replayed through the live brewing registry before it is offered. Instant effects,
More Potions' Leap, RPG Potions' Smoke (which that mod's own splash handling sets from the bottle) and Apothecary's
two Iron's Spellbooks mana effects keep their native durations; quality is still recorded and changes drinking time. Two registry behaviours are kept as they
are: with Apothecary and More Potions together, a magma block on Awkward makes More Potions' Fire (its recipe registers
first, at a stand too), so Apothecary's Burning plan is reported unsupported; and a changed Apothecary ingredient leaves
that plan unsupported while the Kettle still offers the new step when the ingredient is in the tray. RPG Potions'
recipes also accept any drinkable potion at a stand (Water + Charcoal makes Smoke); the Kettle brews only what they
declare.

Other mods' potions brew at the Kettle when their recipes are registered with Forge's brewing registry; quality is
recorded for them, and changes their effects only for the four vanilla potion items or through an effect-quality handler
(`docs/API.md`). `/immersivebrewing report` lists exactly what is supported in a given pack.

The Notebook's Compatibility page uses the server's report, not a client guess. It distinguishes supported effect
scaling, metadata-only support, exclusions and unsupported recipes, and warns about a tipped-arrow recipe conflict.
Kettle destination search is incremental and server-thread bounded; contextual transformations may be checked from an
open tray without being exhaustively enumerable. A budget-limited result can omit alternatives without declaring the
underlying recipe unsupported.

## Datapacks and API

* `docs/DATAPACKS.md`: infusion plans, purification and reclamation recipes, reagent and brew profiles, effect
  policies, workshop profiles for the hands-on work, exclusions, precedence and reload rules.
* `docs/CONFIGURATION.md`: server/client/common fields, reload behavior, active-job snapshots and named examples.
* `docs/API.md`: brewing adapters, effect-quality handlers, metadata and plan queries, events.
* `docs/ARCHITECTURE.md`: design decisions and every deviation from the specification.

## Migrating worlds from 1.1 to 1.2

Back up a world before trying a development build.

- New batches are worked by hand at the apparatus; the 1.1 minigame screens no longer start new work. A batch that a
  1.1 world saved mid-way (running or sealed) is kept exactly as it was and finishes through its original interface:
  open the station's working view and use the route book stand (*Finish the existing batch*). Its outputs, quality
  rules, remainders and Recovery Batches are unchanged.
- Job ledgers of new batches save as schema 3 with a versioned hands-on process payload; batches from 1.1 stay schema
  2. A process payload this version can not read is held for recovery (`process_unreadable`) instead of being reset.
- A hands-on batch that was running when the world saved loads sealed: there is no offline progress.
- Recipes, reagent and brew profiles, potion data, item and block IDs, carrier and rack payloads and the quality
  formula are unchanged. Datapacks may add `workshop_profiles` to tune the hands-on work (`docs/DATAPACKS.md`).
- Client options: the old stirring, pressure and reduced-input toggles keep their keys and now control the working
  view's held tools (see `docs/CONFIGURATION.md`). The camera is chosen by the new `cameraStyle`; while it is `AUTO`, a
  `cameraMode` of `DIRECT` from an earlier development build keeps your own view, so nobody who chose it is given a
  moving camera. The network protocol changed (version 6); client and server need the same build.
- Hands-on batches set out before the close-up update keep their original Kettle rules (process version 1); only
  batches set out with this build use the paddle's posture (process version 2).

## Migration notes: 1.0.x to 1.1.0

Back up a world before trying a development build.

- Player Notebook data migrates to guide data version 2 by adding bounded journal/filter defaults. Existing grant,
  favourites, quantity and tutorial data remain. If the stored version is newer than this build understands, it is
  left untouched instead of being downgraded.
- Job ledgers save as schema 2. Schema-1 station and Recovery Batch jobs remain readable. A newer/invalid schema or a
  job naming a currently missing item is held for recovery and its original NBT compound is saved unchanged; it is not
  partially converted.
- Existing carrier contents stay under their original list key. New selected-slot/loadout preferences are a separate
  versioned payload; a newer preference payload is not rewritten. Racks likewise retain an unreadable newer payload
  rather than exposing or rewriting it.
- The new journal, staging, rack/carrier and quick-finishing paths reuse existing quality/provenance rules. They add no
  score formula or progression milestone.

## Building

### Optional local compatibility dependencies

The core build does not require third-party jars in `libs/`. They are local, excluded from Git and used only when an
explicit compatibility flag is supplied. For a compatibility run, create `libs/`, obtain the matching **Minecraft
1.20.1 Forge** files from the authors' project pages and keep these filenames so Gradle can resolve them locally.

| Project / download source | Filename in `libs/` | License declared by the inspected jar |
| --- | --- | --- |
| [Apothecary](https://www.curseforge.com/minecraft/mc-mods/apothecary-potion-expansion) | `apothecary-1.5.2-1.20.1.jar` | All Rights Reserved |
| [Potions, Potions And More Potions](https://www.curseforge.com/minecraft/mc-mods/potions-potions-and-more-potions/files/all) | `morepotions-1.0.4.jar` | All Rights Reserved |
| [Potions Master](https://www.curseforge.com/minecraft/mc-mods/potionsmaster/files/all) | `potionsmaster-1.20.1-47.1.70-0.6.0.jar` | All Rights Reserved |
| [RPG Potions](https://www.curseforge.com/minecraft/mc-mods/rpg-potions) | `RPG Potions - Forge - MC 1.20.1 - 1.2.0.jar` | MIT (no license notice bundled) |
| [Immersive Smithing](https://github.com/otectus/ImmersiveSmithing) | `immersive-smithing-1.1.0.jar` | GPL-3.0 |
| [Immersive Tanning](https://github.com/otectus/ImmersiveTanning) | `immersive-tanning-1.1.0.jar` | GPL-3.0 |
| [Immersive Jewelry](https://github.com/otectus/ImmersiveJewelry) | `immersive-jewelry-1.1.0.jar` | GPL-3.0 |
| [Immersive Enchanting](https://github.com/otectus/ImmersiveEnchanting) | `immersive-enchanting-1.1.0.jar` | GPL-3.0 |

For the sister mods, use the matching version 1.1.0 from their source repositories and copy the built jar from
`build/libs/`. Version declarations for this build live in `gradle.properties`.

Licenses above were read from each jar's `META-INF/mods.toml`. Apothecary, More Potions and PotionsMaster do not
clearly permit redistribution of these binaries. More Potions' project page lists MIT, conflicting with the inspected
jar's All Rights Reserved declaration; this repository does not redistribute it. RPG Potions declares MIT but bundles
no license/copyright notice, so it is also obtained separately rather than redistributed here. The GPL sister-mod
binaries are likewise kept local; their source and build instructions remain in their own repositories. These
dependency licenses are separate from Immersive Brewing's license. RPG Potions' 1.2.0 download reports 1.1.3 in its
internal mod metadata; keep the download filename shown above.

### Commands

```sh
./gradlew build                      # JUnit tests and the mod jar in build/libs
./gradlew runGameTestServer          # registered GameTests
./gradlew runClient -PsmokeTest      # scripted hands-on client walkthrough with screenshots
python3 tools/generate_data.py --check
python3 tools/generate_textures.py --check
python3 tools/generate_sounds.py --check
```

Optional runtime switches: `-PwithSmithing`, `-PwithTanning`, `-PwithJewelry`, `-PwithEnchanting` (the sister jars are
in `libs/`), `-PwithApothecary`, `-PwithMorePotions`, `-PwithPotionsMaster`, `-PwithRpgPotions` or `-PwithPotionMods` for
all four potion mods (also in `libs/`, compile-only/runtime-only for development and never shipped). The smoke client
also accepts `-PguiScale=N`, `-Pwindow=WxH` and `-Pvariant=<name>`; `-Pvariant=journey` plays the real-input journeys
and records them to `run/footage/` (needs `ffmpeg`).

## License

GPL-3.0 (see `LICENSE`). All textures and sounds are generated from code in `art/` and are part of the same work.
