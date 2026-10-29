# Immersive Brewing showcase screenshots

Captured 2026-10-24. All 20 PNGs are **1920 × 1080 (16:9)**, copied byte-for-byte from Minecraft’s native screenshot output. Every file was decoded, dimension-checked and visually reviewed. No image generation, shaders, resource-pack replacements, resizing, cropping or compositing was used.

| File | Resolution | What it shows | Suggested use |
| --- | --- | --- | --- |
| [brewers-workshop-hero.png](brewers-workshop-hero.png) | 1920 × 1080 | The Brewer’s Kettle, mortar and alembic in a timber workshop with daylight, lanterns and a lit furnace. HUD hidden. | Cover / listing hero |
| [brewing-stations-and-tools.png](brewing-stations-and-tools.png) | 1920 × 1080 | All three stations with framed wooden/iron paddles, Notebook, prepared reagent kit, batch carrier and spent salt. HUD hidden. | Gallery / README overview |
| [brewing-minigame-heat-control.png](brewing-minigame-heat-control.png) | 1920 × 1080 | A real three-bottle Swiftness brew mid-play: W/S draft controls, heat, circulation, stirring and addition timing. | Gallery / README gameplay |
| [alembic-distillation-minigame.png](alembic-distillation-minigame.png) | 1920 × 1080 | Strength purification mid-play: still heat, coil cooling, receiver and collection/waste controls. | Gallery / README gameplay |
| [brewers-notebook-cover-item.png](brewers-notebook-cover-item.png) | 1920 × 1080 | The actual Notebook cover texture displayed in an in-game item frame, beside the iron paddle. HUD hidden. | Gallery / guide introduction |
| [notebook-getting-started.png](notebook-getting-started.png) | 1920 × 1080 | The Notebook’s opening page with the Kettle crafting grid and first-batch instructions. | README / guide introduction |
| [notebook-station-guide.png](notebook-station-guide.png) | 1920 × 1080 | The Notebook’s Stations guide describing the mortar, kettle and alembic. | Gallery / README guide |
| [notebook-recipe-catalog.png](notebook-recipe-catalog.png) | 1920 × 1080 | The searchable Notebook recipe catalog, showing actual vanilla brewing paths and costs. | Gallery / README recipes |
| [jei-brewing.png](jei-brewing.png) | 1920 × 1080 | The mod’s Brewing category in real JEI, titled Brewer’s Kettle, with potion transformations and quality information. | Gallery / README JEI integration |
| [jei-preparation.png](jei-preparation.png) | 1920 × 1080 | The mod’s Preparation category in real JEI, titled Apothecary’s Mortar, showing reagent-to-kit recipes. | Gallery / README JEI integration |
| [brewed-potion-in-action.png](brewed-potion-in-action.png) | 1920 × 1080 | The player holds a Swiftness potion produced by the captured kettle batch; another bottle has been consumed, with its effect icon and particles visible. | Gallery / README potion effects |
| [kettle-texture-detail.png](kettle-texture-detail.png) | 1920 × 1080 | Close-up of the kettle’s copper rim, iron body, brick base, paddle and finished bottle rack. HUD hidden. | Gallery / texture detail |
| [mortar-and-pestle-texture-detail.png](mortar-and-pestle-texture-detail.png) | 1920 × 1080 | Close-up of the stone mortar, pale pestle and wooden base. HUD hidden. | Gallery / texture detail |
| [alembic-texture-detail.png](alembic-texture-detail.png) | 1920 × 1080 | Close-up of the alembic’s copper still, receiver, spout and brick base. HUD hidden. | Gallery / texture detail |
| [paddles-and-notebook-item-detail.png](paddles-and-notebook-item-detail.png) | 1920 × 1080 | Wooden paddle, iron paddle and Notebook textures in actual item frames. HUD hidden. | Gallery / item detail |
| [prepared-kit-and-carrier-item-detail.png](prepared-kit-and-carrier-item-detail.png) | 1920 × 1080 | Prepared reagent kit, batch carrier and spent alchemical salt textures in actual item frames. HUD hidden. | Gallery / item detail |
| [kettle-inventory.png](kettle-inventory.png) | 1920 × 1080 | Loaded Kettle inventory with water bottles, nether wart, sugar, blaze powder and a paddle. | README / setup instructions |
| [brewing-plan-selection.png](brewing-plan-selection.png) | 1920 × 1080 | The real plan picker with available and unavailable paths, exact reagent requirements and fuel. | README / brewing instructions |
| [brewed-swiftness-result.png](brewed-swiftness-result.png) | 1920 × 1080 | The completed three-bottle batch: quality 87, Fine, brewing score 99 and timing feedback. | Gallery / README quality system |
| [alembic-purification-result.png](alembic-purification-result.png) | 1920 × 1080 | Completed purification: distillation score 99, two Strength bottles upgraded from quality 50 to 70. | Gallery / README purification |

## Capture setup

- Minecraft 1.20.1, Forge 47.4.23, Immersive Brewing 1.0.2, JEI 15.20.0.135, and the test-only `immersive_brewing_smoketest` driver. The driver adds no showcase content. Loaded-mod IDs were recorded from the running client; no optional potion mods, sister mods or Jade were loaded.
- Vanilla rendering, English, GUI scale 3 (640 × 360 logical UI), clear daytime fixed at 4000 ticks. Scenery and texture shots use the same HUD-hidden option as F1; UI and potion shots keep the HUD. Spectator positioning was used for the three level item-frame views.
- Reused the existing `ClientSmokeTest`, `PlanAccess`, `VisualJeiPlugin`, reference bots and Forge client-run mechanism through an adapted driver under `/tmp/immersive-brewing-showcase/`. Helpers and the Gradle init script are outside the project sources.
- The integrated server placed real blocks/items in a fresh creative world. Reference bots played real brewing and distillation sessions through the mod’s normal input packets. Kettle mid-play capture: simulation step 164; alembic mid-play capture: step 146. Both sessions completed normally.
- Captures use `Screenshot.grab(..., mc.getMainRenderTarget(), ...)` after rendered frames, matching the native in-game screenshot mechanism. Original files remain under `run/screenshots/showcase-*.png`.
- Saved staging world: `run/saves/BrewingShowcase1792848240099/`. No existing worlds were edited.

Launch command, completed successfully after a normal client shutdown:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/ImmersiveBrewing runClient -I /tmp/immersive-brewing-showcase/showcase.init.gradle -PnoSmithing -PnoTanning
```

Launch/build log: `/tmp/gradle-ImmersiveBrewing-runClient-20261024-092339.log`. Runtime loaded-mod record: `/tmp/immersive-brewing-showcase/control/ready.json`. Scratch scene and capture scripts: `stage.py`, `ui-pass.py` and `finish-pass.py` in `/tmp/immersive-brewing-showcase/`, with additional framing retakes issued through its `control.py`.

## Review notes and limits

The UI captures were straightforward with the existing runtime hooks. Window-manager sizing required floating and explicitly resizing the client; the initial non-16:9 frame was rejected. Item-frame supports, a floating decoration and camera framing needed staging corrections and retakes. The alembic UI was retaken after clearing the held potion and its particles. Notebook button automation encountered a widget-list rebuild after changing tabs; the driver handled it and the selected pages were checked visually.

The Notebook has no separate cover screen: its real opening page is “Getting started.” The cover image therefore shows its actual item texture in a frame; three genuine UI pages are included separately. The catalog retains its normal scrolling viewport. No other requested subject was unavailable.

Only these PNGs and this manifest are deliverables. Project sources, version and CHANGELOG are unchanged; nothing was committed or pushed.
