# Immersive Brewing

Brew by hand. Prepare reagents in the Apothecary's Mortar, tend a living reaction in the Brewer's Kettle and catch the
heart of a batch in the Alembic. The recipe decides the magic; your craftsmanship decides the quality.

Immersive Brewing adds a workshop beside the brewing stand rather than a new potion system. Every potion it makes is
exactly the potion the game's own brewing would make, plus a small record of how well it was made. A score of 50 is
the ordinary potion; better work lasts a little longer and drinks a little faster, worse work a little less.

Minecraft 1.20.1, Forge 47 or later, Java 17. Install on both the client and the server.

## The workshop

| Station | What it does |
| --- | --- |
| **Apothecary's Mortar** | Grinds a reagent into up to four Prepared Reagent Kits in one short session. Each reagent has its own texture to reach (crystalline, fibrous, soft, brittle, liquid). The kits carry an *extraction* score. |
| **Brewer's Kettle** | Brews one to six bottles of one kind in one hands-on reaction: set the burner draft, stir to keep the circulation where the brew wants it, and add each reagent when the tray says so. Each reaction shows its own surface (foam, sediment, shimmer, streaks, bubbles, whorls, glints, motes) and seeded disturbances (cold drafts, burner flares, surges) arrive with a warning. Its *control* score and the reagents' extraction make the quality. |
| **Alembic** | Purifies three identical potions into two better ones (or six into four), once per potion line, up to quality 70. Reclaims unwanted potions into Spent Alchemical Salt and their glass bottles, on its own, without a player. |

Also: wooden and iron brewing paddles, the Batch Carrier (moves up to six potion stacks exactly), the Brewer's
Notebook (guide, searchable plans, favourites, last-result explanation, practice sessions), Recovery Batches and Spent
Alchemical Salt (with an ink sac: two gray dye).

## Your first batch

1. Craft an Apothecary's Mortar and a Brewer's Kettle. The first time you hold one you also receive a Brewer's
   Notebook; its Practice page tries any station's game without using or making anything.
2. Put sugar in the Mortar, open it, choose how many kits (one to four) and press *Prepare*. Steer the pestle, press until the
   texture matches the marked endpoint, then stop.
3. Fill the Kettle's rack with water bottles (or put empty glass bottles in it and use a water bucket on the Kettle:
   one bucket fills four), put nether wart and the sugar kits in the tray, blaze powder in the burner slot and a paddle
   in the paddle slot.
4. Open the Kettle, press *Choose plan*, pick Potion of Swiftness and press *Brew*. The plan shows every step, what it costs
   (one reagent per three bottles per step, one fuel charge per application) and what is missing.
5. Keep the heat marker in the shaded band with the draft keys, stir to hold circulation in its band, and add each
   reagent while the tray says *Add now*. The written cues on the right say what the brew needs.
6. The bottles come back in the rack with their grade. Hover one: the tooltip shows the effective duration.

Plans chain: from water the Kettle offers Awkward, Swiftness through Awkward, splash and lingering versions, and so
on. Starting from a potion you already have charges only the remaining steps.

## Quality

* Extraction (E) comes from preparation (an unprepared reagent counts 50); control (C) from the Kettle.
  Quality Q = (E + 3C) / 4, graded Crude (0-34), Standard (35-69), Fine (70-89), Masterwork (90-100).
* At Q 50 the potion is exactly the ordinary one. Finite durations of the effects an effect policy lists scale from
  0.8× (Q 0) to 1.2× (Q 100); amplifiers, instant effects, infinite durations and mixed bundles such as Turtle Master
  never change. Drinking takes up to four ticks more or less.
* Quality cannot be raised by brewing a finished potion further (a modifier keeps the lower of the two scores); only
  the Alembic can raise it, once.

## Controls (defaults; all rebindable under Controls > Immersive Brewing)

| Station | Keys |
| --- | --- |
| Kettle | `W` `S` raise/lower the draft (more or less heat), `A` `D` stir (hold), `Left Shift` vigorous stir, `Space` add, or circle the mouse over the liquid |
| Mortar | mouse or `W` `A` `S` `D` steer the pestle, left button or `Space` press, right button or `F` fold, `Enter` finish |
| Alembic | `Q` `E` heat, `Z` `C` coolant, `R` switch the receiver between waste and collect |
| All | `H` help overlay (pauses the game while open, practice too; `←` `→` turn its pages), `Esc` or *Seal* stores the batch exactly as it is |

Client options (`config/immersive_brewing-client.toml`) include hold/toggle stirring and pressure, mouse stirring,
standard, high-contrast and colour-blind palettes, large markers, reduced motion, particle density, an optional small
screen shake (off by default), written cues, a numeric display, sound volumes and a request for slower timing and wider
bands (the server decides how much it allows).

## Interruptions and recovery

Closing the screen, logging out, dying, changing dimension, walking away, an unloading chunk, a server stop or a
`/reload` seals a running batch: everything stays in the station, nothing rerolls, and *Resume* continues where it
stopped after a short count-in. Another player may resume it; the original brewer stays credited. A batch that was
still counting in is simply released.

Breaking a station with a job inside drops one Recovery Batch holding the whole job. Install it at an idle station of
the same kind to continue (or, if it never started, to get everything back). If a mod or datapack the batch needs is
missing, the batch is held instead of deleted and comes back when the data does.

## The brewing stand

`brewingMode` in the world's server config:

* `COEXIST` (default): stands keep working; graded potions brewed further at a stand keep their line but fall to quality
  50 unless only the container changes.
* `REPLACE_COVERED`: a stand holding only transformations the Kettle covers does not start (no fuel or time spent);
  modifiers stay at the stand unless `replaceModifiers` is on, and a stand mixing covered and uncovered bottles brews
  as usual.
* `DISABLED`: no new jobs; sealed ones can still be resumed.

## Configuration

Server, per world (`serverconfig/immersive_brewing-server.toml`): brewing mode, batch size (up to 6), servings per
reagent, fuel charges per blaze powder (20), water filling and automation switches, maximum brew length, quality
duration range, drink-time effect, purification cap and limit, reclamation, assistance limits, discovery and plan-step
limits, optional cleric trades (off), completion experience (0), station distance, count-in and input buffering, debug
logging. Common (`config/immersive_brewing-common.toml`): the Smithing, Tanning and Enchanting integrations.

## Automation

Hoppers and pipes may insert bottles and reagents from above while a station is idle (the Mortar also accepts its
reagent from the sides), fuel from the sides at any time, and take finished, unreserved output from below. A job's
materials are never reachable. Water pipes fill empty bottles in an
idle rack, 250 mB per bottle; the Kettle stores no water. A comparator reads how full the finished output is; at
the Kettle, the menu's comparator toggle makes it report the job's state instead.

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

## Datapacks and API

* `docs/DATAPACKS.md`: infusion plans, purification and reclamation recipes, reagent and brew profiles, effect
  policies, exclusions, precedence and reload rules.
* `docs/API.md`: brewing adapters, effect-quality handlers, metadata and plan queries, events.
* `docs/ARCHITECTURE.md`: design decisions and every deviation from the specification.

## Building

### Local compatibility dependencies

Mod jars in `libs/` are local development dependencies and are excluded from Git. Before building, create `libs/`
and obtain the following **Minecraft 1.20.1 Forge** files from the authors' project pages. Keep these filenames;
Gradle resolves them locally. The six compile-only dependencies (Smithing, Tanning and all four potion mods) are
required to build even when their runtime switches are disabled. Jewelry and Enchanting are needed only for their
respective runtime switches.

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

For the sister mods, use the matching release or build version 1.1.0 from their source repositories and copy the
release jar from `build/libs/`. Version declarations for this build live in `gradle.properties`.

Licenses above were read from each jar's `META-INF/mods.toml`. Apothecary, More Potions and PotionsMaster do not
clearly permit redistribution of these binaries. More Potions' project page lists MIT, conflicting with the inspected
jar's All Rights Reserved declaration; this repository does not redistribute it. RPG Potions declares MIT but bundles
no license/copyright notice, so it is also obtained separately rather than redistributed here. The GPL sister-mod
binaries are likewise kept local; their source and build instructions remain in their own repositories. These
dependency licenses are separate from Immersive Brewing's license. RPG Potions' 1.2.0 download reports 1.1.3 in its
internal mod metadata; keep the download filename shown above.

### Commands

```sh
./gradlew build                      # JUnit tests and the release jar in build/libs
./gradlew runGameTestServer          # 120 GameTests
./gradlew runClient -PsmokeTest      # scripted client walkthrough with screenshots
python3 tools/generate_data.py --check
python3 tools/generate_textures.py --check
python3 tools/generate_sounds.py --check
```

Optional runtime switches: `-PnoSmithing`, `-PnoTanning`, `-PwithJewelry`, `-PwithEnchanting` (the sister jars are in
`libs/`), `-PwithApothecary`, `-PwithMorePotions`, `-PwithPotionsMaster`, `-PwithRpgPotions` or `-PwithPotionMods` for
all four (also in `libs/`, compile-only and never shipped), and for the smoke test `-PguiScale=N`, `-Pwindow=WxH`.

## License

GPL-3.0 (see `LICENSE`). All textures and sounds are generated from code in `art/` and are part of the same work.
