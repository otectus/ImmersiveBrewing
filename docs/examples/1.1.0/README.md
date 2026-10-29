# Immersive Brewing 1.1.0 datapack examples

This directory is a copyable Minecraft 1.20.1 pack-format-15 datapack. It uses namespace `example_brewing`; change
that namespace consistently for a real pack.

The files demonstrate only implemented schema-1 contracts:

- `cookie.json`: an item-specific reagent profile and optional Mortar preparation. Raw cookies would still count as
  extraction 50 in any real recipe accepting them.
- `calm_foam.json`: a brew profile with valid bands, disturbances and the supported `mote` presentation vocabulary.
- `speed_duration.json`: a safe finite-duration effect policy. It never changes amplifiers or infinite durations.
- `splash_container.json`: a generic potion-to-splash container transformation already performed by Forge's live
  brewing registry. An infusion file describes and validates a real adapter transformation; it cannot invent one.
- `conditional_honey.json`: the exact Forge `mod_loaded` condition shape accepted on Immersive Brewing definitions.
  It uses `forge` so this sample loads in a Forge instance; replace the mod id and selector with the optional
  integration's real registered ids.

Copy the directory contents into a world's `datapacks/<pack-name>/`, then run `/reload`. Use
`/immersivebrewing report`, `/immersivebrewing recipe example_brewing:splash_container`, and
`/immersivebrewing profile minecraft:cookie` to inspect the result. The container recipe deliberately duplicates the
built-in vanilla conversion with the same costs/output; recipe-id ordering chooses one equivalent route.
