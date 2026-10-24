# Changelog

## 1.0.2

- Added Spanish, Brazilian Portuguese, Russian, and Simplified Chinese translations.

## 1.0.1

- Resumed sessions at all three stations advance after the count-in without waiting through their previously elapsed time.
- **Apothecary's Mortar**: recovery and resume check output capacity; jobs held by a full shelf (`no_room`) become
  resumable when space opens, preserving their ingredients and earned extraction.
- **Alembic**: purified ordinary potions share one lineage per batch, so the output bottles can be brewed together.
- **Brewer's Kettle**: the paddle stays locked during count-in and brewing, including resumed count-ins, so another
  player cannot remove it to bypass wear; sealed jobs still allow paddle maintenance.

## 1.0.0

First release, for Minecraft 1.20.1 and Forge 47.

- **Apothecary's Mortar**: reagent preparation into up to four Prepared Reagent Kits per session, five textures of work
  (crystalline, fibrous, soft, brittle, liquid), extraction scoring.
- **Brewer's Kettle**: one to six servings in one hands-on reaction (draft, stirring, timed additions, ten reaction
  signatures, seeded disturbances with warnings), plans that chain from any potion and charge only the remaining
  steps, exact reagent, fuel and remainder accounting, quality from extraction and control.
- **Alembic**: purification of three identical potions into two (six into four) up to quality 70, once per potion
  line; passive reclamation into Spent Alchemical Salt and glass bottles.
- Batch Carrier, Brewer's Notebook (guide, plan search, favourites, last-result explanation, practice), Recovery Batch,
  wooden and iron brewing paddles.
- Quality changes finite durations of policy-listed effects (0.8× to 1.2×) and drinking time; 50 is exactly the
  ordinary potion; drinking, splash, lingering clouds, tipped-arrow crafting, arrows and dispensers all apply it once.
- Sessions seal on every interruption and resume exactly; broken stations package their job into one Recovery Batch;
  missing data holds a job instead of deleting it.
- Brewing stand modes: coexist (default), replace covered transformations, disabled.
- Datapack-driven plans, reagent and brew profiles, effect policies and exclusions with atomic reload; a small public
  API (adapters, effect-quality handlers, queries, events).
- JEI and Jade support; Immersive Smithing (smithed iron paddle), Immersive Tanning (strapped carrier), Immersive
  Enchanting and Immersive Jewelry (coexistence) integrations, all optional.
- Accessibility: remappable keys, hold or toggle alternatives, high-contrast and colour-blind palettes, reduced motion,
  written cues, numeric display, server-approved timing and band assistance.
