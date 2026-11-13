# Immersive Brewing — Hands-On Workshop Overhaul

**Implementation handoff and design specification**  
**Prepared:** November 12, 2026  
**Suggested release:** 2.0.0, subject to the maintainer's versioning decision  
**Target:** Minecraft 1.20.1, Forge, Java 17

Rebuild Immersive Brewing around physically working at an alchemist's bench. The player should lift ingredients, work a pestle, tip a dose into a vessel, move a paddle through liquid, turn a burner control, redirect condensate, and fill bottles. The apparatus and material should explain what is happening.

This brief authorizes replacement of the existing crafting screens, minigame flow, workstation presentation, and input architecture. Preserve useful recipe, potion, inventory, recovery, and compatibility contracts where they support the new experience. The implementation is a complete interaction overhaul; moving the existing gauges into floating panels does not satisfy it.

The scope remains **Minecraft potion brewing**. New crops, fermentation, alcohol, custom spell mixing, village economies, and a loader port are separate projects. A complete workshop does not require those expansions.

## 1. Evidence and boundaries

The design follows a static review of the repositories' current default branches, including station interactions, simulations, inventory ownership, rendering, recipe contracts, persistence, and the reference mod's interaction framework. No Minecraft client, dedicated server, or build was run for this research. Runtime compatibility and tactile feel remain implementation acceptance tests, not verified results.

| Repository | Inspected revision | Relevant baseline |
| --- | --- | --- |
| `otectus/ImmersiveBrewing` | `12f9048009169069da6498160b3b29a8c5d14286` | Development version 1.1.0; Minecraft 1.20.1; Forge 47.4.23; Java 17 |
| `Pocky-l/handson-delight` | `7d772f0ccf88df32b85f085a7535dc5449bff5ef` | Main-branch version 1.3.0; its inspected build targets 1.20.1 and declares Diegesis 1.0.0-beta.7 |
| `Pocky-l/diegesis` | `c60c89b01f1a8c9bb0052e1f91b593155da4aaa5` | Main-branch version 1.0.0-beta.7; inspected Forge build uses 47.4.26 |

These are repository declarations, not claims about published releases. Hands-On Delight's README also advertises other loader/version combinations; do not infer that its inspected `main` build or Immersive Brewing already targets those platforms. [S1], [S2], [S3]

Every new interaction, numerical target, class name, and data format below is a **proposed implementation requirement**, unless explicitly described as existing. Before editing, compare the working branch against the pinned baseline and adjust paths for changes that have landed since this review. This brief supersedes old product specifications where they require the replaced minigame experience.

### What the current code actually provides

| Existing behavior | Evidence | Overhaul consequence |
| --- | --- | --- |
| Ordinary station use reaches `NetworkHooks.openScreen` after held-item and recovery handling | `StationBlockEntity.use` [S4] | Replace the default entry route; preserve intentional bucket, carrier, and recovery actions |
| Mortar, kettle, and distillation have dedicated screens and deterministic simulations | `PreparationScreen`, `KettleBrewScreen`, `DistillationScreen`, corresponding simulations [S5], [S6] | Extract useful material behavior; replace screen-bound input and timed exercise structure |
| Kettle rendering already depicts bottles, liquid, foam, and a paddle | `KettleRenderer` [S7] | Rebuild those objects as manipulable parts; the present paddle animation follows circulation rather than the exact tool pose |
| A job reserves inputs in a persistent ledger; completion commits outputs and then fires events | `JobLedger`, station completion methods [S8], [S9] | Keep one authoritative owner for every resource during the new gestures |
| Ledger schema 2 reads schema 1 and preserves unknown or missing-item jobs for recovery | `LedgerCodec` [S8] | Add a versioned physical-process representation without misreading old simulation arrays |
| The kettle stores no water reservoir; its fluid handler fills empty bottles in whole 250 mB units | `BrewersKettleBlockEntity.WaterHandler` [S9] | A visible kettle is not permission to introduce an untracked potion fluid tank |
| Quality, brewing inheritance, container finishing, and potion effects have explicit contracts | `QualityMath`, `QualityInheritance`, `QuickFinishing`, architecture documentation [S10], [S11] | Change how workmanship is earned while retaining well-defined output semantics |
| Recipe discovery, staging, Notebook, carrier, rack, JEI, and Jade already exist | Current README, architecture, and API documentation [S1], [S12] | Adapt these to the physical workshop; do not discard their useful services |

### What to learn from Hands-On Delight

Its cooking pot defines a Diegesis station schema, binds scene slots to the actual block inventory, and handles actions through server logic. `PotBehavior` maps the cursor onto the liquid plane and moves the spoon; observers receive the shared pose/state. `StirSkill` judges movement, and the scene describes physical targets and camera composition. These are valuable patterns. Its star progression and bonus-serving economy are particular to that mod and are unnecessary here. [S13], [S14], [S15], [S16]

Borrow the relationship **hand → tool → material → visible consequence**. Build brewing-specific behavior and original apparatus, animation, and sounds. If code or assets are adapted, retain the relevant notices: Hands-On Delight and Diegesis identify their work as MIT; Immersive Brewing identifies its work as GPL-3.0. [S1], [S2], [S3], [S17]

## 2. The intended experience

A first batch should play like this:

1. Put water bottles into the kettle rack and place nether wart and sugar on its ingredient tray. The Notebook rests beside the apparatus and shows the available Swiftness route.
2. Choose the route and quantity. Required doses are arranged on the tray, with missing supplies explained before anything is consumed.
3. Tip the bottles into the kettle. Empty bottle silhouettes remain in their reserved rack positions, ready to receive the brew later.
4. Turn the burner control. The flame strengthens, small bubbles form, and the surface responds after a short thermal delay.
5. Pick up the nether-wart dose and tip it into the warming liquid. It visibly infuses. The next dose becomes available when the base has formed.
6. Add sugar and take the paddle. Broad movements carry the powder through the liquid; violent movement produces excess froth. Slowing the paddle and easing the heat lets the foam settle.
7. Shut the burner and let the surface calm. Open the spout over a reserved bottle. Liquid travels into it, its appearance changes to the finished potion, and the kettle level falls.
8. Collect the batch or place it in a carrier. A brief notebook entry explains one useful observation, such as “The foam settled before bottling.”

For a later batch, prepare the sugar at the mortar first: crush the visible crystals, work the coarse material around the bowl, then tip the prepared dose into a cup. Preparation improves extraction and gives the workshop another meaningful physical activity. It remains optional for ordinary brewing.

A second player should be able to see the same ingredients, tool movement, liquid state, and bottling. She can prepare the next reagent, manage storage, or help at a different control without creating a second copy of the batch.

### Design rules

- **Actions change material.** A stroke counts only when the correct tool contacts a meaningful work surface. Moving the mouse over a decorative model cannot grant quality.
- **Conditions drive progress.** A reagent disperses, a solution warms, or sediment settles. A hidden countdown should not require an arbitrary button press at an arbitrary time.
- **The apparatus explains itself.** Steam, granularity, flow, residue, and sound communicate the same state the server evaluates.
- **Skill is practical judgment.** Apply pressure, stop grinding when the texture is suitable, avoid boiling a delicate brew, and collect clear condensate. More clicks are not better craftsmanship.
- **Batching respects time.** Six bottles share one reaction. Do not repeat an entire interaction sequence six times.
- **Mistakes are legible and usually recoverable.** Cooling, resting, mixing, and redirection are useful responses. Ordinary errors reduce workmanship before they destroy expensive ingredients.
- **Access options preserve the activity.** A keyboard or low-motion user operates the same tools and obtains the same possible quality.

## 3. Product decisions

| Area | Decision |
| --- | --- |
| Default crafting presentation | Interactive objects at the actual block; no mandatory minigame window |
| Workstations | Retain the mortar, kettle, alembic, and rack identities and registry IDs; redesign their parts and arrangement |
| Core implementation | Prefer Diegesis behind a small Brewing-owned adapter, subject to the focused integration checks in section 12 |
| Camera | Optional comfortable focus view; reduced-motion and direct-view alternatives; immediate exit |
| Material simulation | Small deterministic state models; no rigid-body or computational-fluid simulation requirement |
| Potion identity | Resolve and validate against the existing live brewing adapters and recipes |
| Batch size | Retain one to six kettle servings initially; retain preparation batches of up to four reagent units |
| Prep requirement | Optional for ordinary routes; raw preparable reagents retain the existing extraction baseline |
| Quality rewards | Retain current effect limits and quality inheritance; replace the method of earning preparation/control scores |
| Random disturbances | Remove arbitrary scheduled flares/surges from the default physical process; use consequences of actual actions and materials |
| Crafting progression | Better understanding and useful workshop conveniences; no repetition-gated quality ceiling |
| Automation | Preserve staging, supplies, and output handling; do not silently add unattended high-quality production |
| Existing worlds | Preserve IDs, items, metadata, inventories, and recoverable jobs; explicit legacy handling |
| Platform scope | Finish the Forge 1.20.1 overhaul first; no simultaneous loader port |

Do not make Hands-On Delight or Farmer's Delight required dependencies. The relevant reusable dependency is Diegesis, not the reference mod's cooking content.

## 4. A consistent interaction language

### Focus and ordinary use

Right-clicking a station with no applicable direct item action enters its working view. The world remains visible. The player is still physically at the workstation; focus grants no invulnerability, remote reach, or permission bypass.

Keep a compact optional inventory drawer and an on-demand book for supply selection. These are supporting interfaces. Work itself happens on the apparatus.

Use this priority order to avoid consuming an item and opening a second interaction in the same click:

1. Existing valid held-item actions: recovery installation, permitted water filling, and carrier exchange.
2. Direct interaction with a visible part or exposed item, when using direct-view controls.
3. Enter the station working view.
4. A deliberate inspection/maintenance fallback, if requested by the player or needed for migration.

Test both hands and consume an accepted use once. An invalid target shows why it refuses the action without eating the held stack. Sneaking must not become a hidden requirement for ordinary crafting.

### Controls

These are design defaults to implement with rebindable actions; verify that Diegesis exposes the necessary binding hooks before relying on its defaults.

| Intent | Mouse interaction | Equivalent accessible interaction |
| --- | --- | --- |
| Take a tool or dose | Click its visible handle/body | Cycle physical targets, activate selected target |
| Put it down | Click its rest or release an active grab | Activate again or explicit Put down action |
| Grind or stir | Move the grabbed tool across its work plane | Hold/toggle a work action; choose gentle/normal/firm effort |
| Pour | Move vessel to the receiving mouth and tilt with a bounded drag | Select destination and hold/toggle Pour |
| Adjust heat/coolant | Drag the visible lever/valve | Increase/decrease selected control |
| Insert or collect | Click the physical slot/item | Activate the selected slot |
| Operate several items | Explicit batch modifier with visible amount | Batch action with announced count |
| Stop safely | Close the lid/clamp or select Seal batch | Rebindable Seal batch |
| Leave focus | Escape, inventory key, or movement cancel | Same, always available |

Use one click-to-grab convention across stations. Allow hold-to-grab as an option. Avoid combining a mandatory held mouse button with a modifier and continuous circular movement.

While a tool is grabbed, input belongs to that tool. It must not also attack, break a block, consume the held potion, scroll a hotbar, or trigger another mod's action. On screen changes, focus loss, death, and disconnect, release all held-input state. Never resume a stale press automatically.

### Targeting and camera

- Give thin handles larger, non-overlapping logical interaction volumes. Target selection should not require clicking one texture pixel.
- Distinguish a disabled part from an available part through a subtle outline and short action label. Avoid permanent floating UI clutter.
- Use constrained work planes, forgiving vessel mouths, and a clear placement preview. The tool follows the hand with a little visual weight but no gameplay delay that impairs control.
- The camera may frame the apparatus, but it must not pass through a wall, hide a threat, or grant a view through obstructing blocks. On cramped benches, use a closer safe framing or direct view.
- Station-to-station camera travel is optional. Disable edge-triggered travel by default while carrying a reagent or tool; offer an explicit Next station action.
- A no-camera-travel option and keyboard target navigation are release requirements, not assumed Diegesis features. Implement an adapter or a small direct-view interaction surface if the pinned library cannot provide them.
- Damage immediately releases focus and active tool input. The normal interruption policy then protects the batch.

## 5. The physical workshop

Use Minecraft-scale forms: stone, dark iron, copper accents, wood, linen, and readable glass. Every important component has a visible purpose. Preserve single-block placement footprints for existing stations unless a separately specified migration is implemented.

| Station | Interactive parts | Main material cues |
| --- | --- | --- |
| Mortar | Bowl, pestle, rim scraper/folding surface, dose cups, output shelf | Whole fragments, coarse particles, pulp, paste, fine powder, oily separation |
| Kettle | Ingredient tray, vessel mouth, paddle/rest, burner door/control, lid, spout, bottle rack | Bubbles, floating pieces, dissolving trails, sediment, foam, steam, settling |
| Alembic | Charge neck, burner valve, coolant fill/control, condenser, diverter, receiver, waste collector | Vapor movement, droplets, cloudy/clear condensate, fill levels |
| Rack | Six bottle positions, reservation markers, shelf labels, carrier cradle | Exact stored bottles, occupied/free spaces, filter ghosts |

Keep the pestle, lid, spout, and alembic diverter as apparatus parts. They do not each need a separately crafted item. Preserve actual wooden/iron paddle items and their durability, enchantments, and Smithing integration. A visual grip never creates a second inventory tool.

Ingredient cups and receivers can be apparatus-owned scene objects. Their contents must still have a real owner in station state. Their visible existence is not an extra ItemStack the player can duplicate by picking up a model.

The rack is a calm finishing area. A player can identify, collect, label, and pack a potion without opening a full inventory grid for every bottle. Labels may still use a small text editor because typing a name benefits from one.

## 6. Mortar: change the ingredient, then stop

Replace the fixed preparation exercise with a persistent material operation. The player chooses an amount by placing one to four units, works that batch together, and packages it when its texture is suitable. Preserve one input reagent unit per prepared kit; cosmetic particle counts never multiply ingredients. [S18]

### Preparation families

| Family | Physical action | Material response | Common correctable mistake |
| --- | --- | --- | --- |
| Crystalline | A few controlled presses, then short grinding arcs | Crystals fracture; large grains become an even powder | Excessive force makes unnecessary fines; stop or work remaining coarse spots gently |
| Fibrous | Press and draw across the bowl; scrape/fold back inward | Strands shorten and form a damp mass | Working one spot leaves long strands elsewhere; redistribute |
| Soft | Mash with moderate pressure and fold | Pieces become pulp, then a consistent paste | Heavy pressure spreads paste up the rim; scrape it back |
| Brittle | Light presses followed by minimal grinding | Shell-like pieces break readily | Too much continued work overprocesses the material; stop promptly |
| Liquid/emulsion | Gentle sweeping and folding | Separate streaks become a uniform mixture | Violent motion aerates it; let it rest, then fold gently |

Use the existing reagent classification as a starting point, not as a command to keep its timed mechanics. A reagent currently marked unpreparable must remain valid for direct brewing. Do not decide preparation behavior from an item name or an automatically sampled texture. Unknown modded ingredients need a neutral fallback. [S19]

### Material model

Track bounded values for processed fraction, distribution/remaining coarse material, clumping or separation, and overwork. A small fixed set of material zones is sufficient if spatial coverage improves the action. Do not create one server object per grain.

The server derives productive work from tool displacement in the bowl, contact, selected effort, and elapsed server steps. It rejects movement through the air or instant jumps between opposite edges. Pressure is a selected effort or hold-derived value; ordinary mice do not supply pressure sensing.

Finish becomes available when a usable result exists, with the current texture clearly described. The best extraction comes from a suitable endpoint and controlled handling. Reaching that endpoint does not start a surprise countdown. Resting the pestle is safe; indefinite grinding is not rewarded.

Packaging is a real final gesture: scrape into the dose cup and tip or collect it. It commits the kit exactly once. Cosmetic remnants clear as part of completion; no mandatory washing chore is added.

Suggested tuning targets: 6–12 seconds of active work for one familiar reagent; 10–18 seconds for four. These are playtest targets, not fixed time requirements or advertised performance measurements. A practiced player can stop sooner when the material is ready.

Keep `prepared_reagent_kit` compatible in saves. Its display model can become a small folded packet or stoppered dose vial while retaining the source reagent, count, extraction, and profile identity. Preparation should not create a new registry item for every reagent.

## 7. Kettle: a reaction the player tends

The kettle becomes a continuous material process. Replace the old phase clock, scheduled disturbances, “press Add now” prompt, and activity-count scoring. Preserve useful thermal inertia, circulation, and profile concepts where they can be expressed as actual state. The inspected old simulation already contains some of these physical ideas, but it advances through timed phases and measures participation; that structure must change. [S6]

### Process states

| State | What the player does | What advances it |
| --- | --- | --- |
| Staging | Load supplies, select route and quantity, review costs | Server validates ingredients, output space, tools, and route |
| Charged | Pour the staged input portions into the vessel | Authorized portion transfers establish the batch; tools and doses are reserved |
| Heating | Adjust the burner; observe the liquid | Temperature reaches the recipe's broad workable conditions |
| Reacting | Add the correct dose and disperse it | Dissolution/extraction work accumulates under the actual conditions |
| Waiting for next dose | Prepare or take the next ingredient | Player adds the next allowed dose; no silent auto-add |
| Resting/finishing | Reduce heat, stop stirring, optionally use the appropriate lid/fitting | Required suspension/foam/settling conditions become satisfactory |
| Ready to bottle | Open the spout over the assigned containers | Conserved servings move into accessible finished bottles |
| Sealed or held | Resume or resolve a named missing requirement | Existing batch state and resources remain owned |

A route can contain multiple reactions. Water → Awkward → Swiftness should remain one workshop session, without repeated menus or mandatory intermediate bottling. Do not permit physical gestures to skip the registered recipe order.

### Heating

Fuel supplies an actual burner with an obvious control and off position. The flame follows the control promptly; the liquid follows with inertia. Heating and cooling depend on bounded simulated state, not frame rate or how many input packets arrive.

Keep plan-based fuel accounting initially: the validated route reserves its existing number of fuel charges, including its adapter's serving/application policy. Do not also charge a second fuel meter for the same work. Waiting, reading, sealing, and blocked output spend no additional fuel. A visible flame may idle or shut down at a safe boundary. Any later continuous-fuel economy requires an explicit preview and balance change.

A beginner should be able to see the difference between warming, simmering, and boiling. A small optional instrument or tooltip provides a precise reading; the default activity is readable without staring at a horizontal bar.

Do not add random gusts or heat spikes merely to demand activity. A large cold addition can lower heat, a thick mixture can retain it, and a closed lid can alter cooling if the profile explicitly uses those mechanics. Each consequence needs a visible cause and a useful response.

### Dosing

The tray holds the reserved doses in recipe order. A dose is an actual resource reference. The player lifts it and tips it into the vessel; dry ingredients scatter, pastes slide, and liquids pour according to their presentation profile.

The required amount is measured in reagent units according to the existing live plan. A player's pour angle changes the transfer animation and, where useful, the rate of addition. It does not accidentally consume an entire inventory stack.

Use a broad usable condition range. Adding somewhat early can prolong dissolution or lower workmanship; a grossly incompatible ingredient is refused before consumption. Do not create a hidden recipe-discovery puzzle that silently destroys rare ingredients. Experimental mixing is outside this release.

Additions should have causal effects: a crystalline dose leaves visible grains, a fibrous preparation disperses strands, and a cream leaves streaks until mixed. When all reserved units for a step are transferred, disable that cup. Repeated clicks cannot debit the dose twice.

### Stirring

The paddle follows the point of contact on the liquid plane. It pushes a visible wake, carries ingredients around, and disturbs sediment. When released, circulation coasts down rather than vanishing instantly.

Measure useful displacement, radius/coverage, direction changes, and contact at bounded rates. Smooth circular motion is one useful action, but the correct action depends on the mixture: slow folding for froth, broader sweeps for sediment, and gentle incorporation for fragile motes.

Do not make constant circles mandatory throughout heating and settling. Deliberately stopping can be the right action. A stable burner setting can remain correct without a periodic token keypress. Credit comes from recipe-relevant work and resulting conditions, not a count of input events.

The accessible work action generates bounded tool movement through the same simulation. It does not invoke a separate “perfect result” endpoint. The player still chooses effort, when to work, when to stop, and which material operation is appropriate.

### Finishing and bottling

After the final transformation, allow the liquid to settle. The spout becomes available when bottling is sensible. Finished brew does not degrade while the player reads a label or makes room for output.

One spout action fills one reserved bottle; an explicit batch action fills the remaining reserved bottles in a visible sequence. Reject overfilling by stopping flow at a whole serving. Never introduce a twitch challenge for matching the meniscus to a one-pixel mark.

Use a short, satisfying transfer animation with an actual falling fill level. The authoritative transfer is explained in section 14. Players can stop halfway through bottling and continue later without losing the remaining portions or receiving them twice.

## 8. Give reactions distinct physical personalities

Retain the existing reaction vocabulary as a useful design seed. Author new physical profiles for it; do not merely relocate the old signature indicators into the world. The following are proposed interpretations, not claims about the current implementation's exact recipe mapping.

| Reaction family | What the brew visibly does | Useful player response |
| --- | --- | --- |
| Light foam | Fine bubbles gather into a rising froth | Fold slowly, ease the burner, let the head settle |
| Dense sediment | Heavy material settles below a clearer upper layer | Sweep the lower liquid to disperse it, then allow deliberate final settling |
| Delicate shimmer | Thin iridescent streaks break apart under harsh boiling | Use gentle heat and controlled mixing |
| Magma streaks | Thick warm-colored ribbons resist incorporation | Warm the mixture enough to soften them, then fold until uniform |
| Suspended bubbles | Larger bubbles become trapped in a viscous solution | Ease heat and release trapped gas through calm movement/rest |
| Dark whorls | Dark reagent ribbons persist through the liquid | Mix until the ribbons disperse, without interpreting color alone as success |
| Aligning glints | Small bright crystalline flecks remain unevenly suspended | Dissolve remaining grains, then stop agitating when clear |
| Floating motes | Light material stays on the surface or is thrown outward by turbulence | Draw it into the liquid with gentle broad movement |

Use a representative route set during development: Swiftness, Healing, Strength, Regeneration, Fire Resistance, Slow Falling, a corruption route, extended/strong modifiers, splash, lingering, and Turtle Master. Verify actual recipe outputs with the live adapter; the physical profile never authorizes a new potion combination.

Container-only finishing gets a short physical operation. For example, mount the finishing fitting, transfer the registered modifier, and close the fitting. Preserve `QuickFinishing`'s proof of container-only behavior and existing quality. Do not force a full second brewing session or award fresh quality for changing a container. [S11]

## 9. Alembic: collect what the apparatus produces

The alembic should be the most visibly mechanical station: warming charge, moving vapor, condenser droplets, a diverter that physically changes the stream, and a receiver that fills.

1. Place a valid homogeneous group of three or six potions in the charge area. Show the expected yield and returned containers before starting.
2. Pour the charge, close the apparatus, and supply fuel.
3. Fill the coolant jacket if needed and adjust its flow. Turn the burner control to establish a steady condensate stream.
4. Initially direct cloudy condensate toward the waste collector. When the stream becomes clear, move the diverter to the receiver. Redirect it as haze returns.
5. Stop heat and collect the finished output. A short notebook note identifies the main cause of the result.

This retains the existing useful distinction between early, middle, and late condensate while replacing the screen controls with the actual mechanism. The current simulation already tracks these fractions; its conceptual model is worth adapting. Remove its forced input-count participation and fixed exercise timeout where those conflict with the physical process. [S6]

### Rules and economy

- Keep the default three-to-two and six-to-four yield, one purification per lineage, and quality cap 70. These are existing balancing contracts, not opportunities for automatic bonus output. [S10], [S20]
- Incorrect collection reduces purification benefit. Under the current quality rule, a weak purification keeps the input quality; it still pays the previewed material cost.
- The clear fraction has a forgiving plateau and a readable onset. The transition is shown by clarity, droplet behavior, a text cue, and optional sound; color alone is insufficient.
- Waste is a visual/internal part of the known yield loss. It is not a second item-producing salvage path on top of the already calculated output.
- Reclamation remains a separate explicit operation with its existing output rules. Do not automatically reclaim a batch when the player leaves.
- No lethal fumes, accidental explosions, or machinery destruction are required for this overhaul.

### Proposed coolant resource

Use a real water-only coolant tank, separate from potion portions. Suggested initial capacity: 1,000 mB; cost: 250 mB per group of three input potions. The full cost is reserved at start, and actual level/remaining reservation is visible. One bucket therefore supports four groups before refilling.

These numbers are new tuning defaults. Expose them through server configuration/data, allow a zero-cost setting for packs, and document the change. Buckets return normally; partial fills and simulated capability calls must conserve fluid. Existing in-progress distillation jobs finish under their old costs rather than acquiring a new coolant charge during migration.

If a filled receiver or coolant requirement blocks progress, the apparatus enters a clear waiting condition. It must not silently spend fuel forever or lose an already earned output.

## 10. Notebook, storage, and teaching

### Notebook

Keep the Notebook as a book players consult for recipes, material observations, route selection, support status, and history. Place a readable representation on a small stand beside the focused station. An expanded book view is appropriate for searching many recipes; it must not become the crafting minigame.

Recipe staging should put the selected supplies into real slots and dose holders. Ghosts explain missing items. Clicking a recipe may organize ingredients, but it does not grind, pour, stir, or complete the reaction for the player.

Replace technical instructions such as phase names and signature thresholds with observable guidance:

- “Warm until small bubbles rise.”
- “Work the remaining coarse pieces around the bowl.”
- “Slow the paddle while the foam settles.”
- “Collect the clear stream.”

Retain an optional detailed readout for players who want numbers and pack authors diagnosing profiles. Keep server-backed support reporting: a recipe viewer or pretty page cannot make an unsupported transformation valid.

### Rack and carrier

Expose all six rack positions as physical bottle targets. Preserve exact stack data and reservation filters. Hovering/focusing a bottle shows its potion, grade, and short label. Taking one, taking the matching group, packing a carrier, and unpacking a selected carrier are distinct actions with visible quantities.

Keep the carrier's selected slot and loadout preferences. It should not reach into nearby storage without explicit authorization. Labels, quality, lineage, custom effects, and foreign metadata travel with the exact item.

### First-use teaching

Teach through three small demonstrations: take/put down a tool, control the burner, and transfer a dose. Then guide one real, useful batch. Point at the object concerned, and remove the prompt after successful use.

Practice remains available without consuming resources. Use a clearly labeled client-only demonstration or isolated nonproductive practice context that cannot create items, quality metadata, XP, or advancement events. It should use the same tool behavior as production, so learning transfers.

Do not interrupt every batch with a result screen. Use a brief completion cue and a persistent book entry. Explain the largest actionable mistake rather than presenting a wall of component scores.

## 11. Quality, pacing, and accessibility

### Preserve a comprehensible quality contract

Keep the existing output-quality formula as the initial compatibility target:

`Q = round((E + 3C) / 4)`

`E` measures the prepared material's endpoint and handling. `C` measures recipe-relevant thermal treatment, incorporation, and dose handling. Rebuild those measurements around the new process. The old timing windows and “any input during this phase” checks must not determine the new result.

Quality 50 remains the ordinary potion baseline. Keep finite-duration scaling, drinking-time changes, effect-policy exclusions, and anti-rebrewing inheritance rules unless a separately documented balance change is deliberately made. Raw extraction 50 with control 100 yields quality 88 under the existing integer rounding; do not promise Masterwork from raw preparable reagents under this formula. [S10]

Compute workmanship over useful reaction progress or bounded process segments, not arbitrary idle time. Holding a finished batch indefinitely must neither raise its score nor dilute a past error. Once the required treatment has occurred, extra stirring cannot farm quality. Each error has one primary penalty; avoid charging both a heat error and every visual symptom it causes as independent losses.

Preserve recipe results, amplifiers, instant effects, custom potion data, and mixed-effect policy behavior. Turtle Master is an explicit regression case. Keep the old result and event contracts where practical; new diagnostic data should be additive and bounded. [S10], [S12]

### Target pacing

| Activity | Suggested active-work target | Pacing rule |
| --- | --- | --- |
| Prepare one reagent | 6–12 seconds | Stop when the material is ready |
| Prepare four reagent units | 10–18 seconds | Shared work, no four repeated exercises |
| Familiar base/effect batch | 25–45 seconds | Include short safe moments to handle supplies |
| Longer multi-step route | Usually below 75 seconds | Scale with meaningful transformations, not bottle count |
| Container-only finishing | 5–10 seconds | Preserve quality; no new reaction score |
| Purification | 20–35 seconds | Readable collection phases, little dead waiting |
| Bottle six servings | 4–8 seconds with batch fill | Can interrupt and resume |

These are starting playtest targets. Tune against the actual number of gestures and time spent doing nothing. Do not advertise a target as measured until it has been tested.

### Accessibility requirements

- Click/hold/toggle variants; keyboard-only targeting and operation; no required chords.
- Large logical targets, adjustable tool sensitivity, dead zones, and bounded smoothing.
- Reduced camera movement, no forced shake, independent particle and animation intensity.
- Text/shape equivalents for color, narration of selected target and action, subtitles for meaningful audio.
- Precision and normal presets with the same maximum attainable quality. Assistance changes input expression and tolerances, not eligibility for rewards.
- Pause/seal access at any time, including while a tool is held; safely restore it to its apparatus or source owner.
- A left-handed or mirrored tool presentation option that never changes authoritative slot identity.
- GUI scale, aspect ratio, FOV, and screen-edge checks so the book, tray, controls, and exit instructions remain reachable.

Existing client accessibility settings are a useful migration source. Rename or translate obsolete minigame options rather than silently discarding a player's chosen reduced-input or reduced-motion preferences. [S21]

## 12. Implementation architecture and the Diegesis decision

### Adopt the library for presentation and interaction

Diegesis is a strong fit because its public API already describes station schemas, physical scene nodes, controls, grabbables, liquid presentation, camera composition, server actions, and binding scene slots to an existing inventory. Its documentation specifically distinguishes temporary interaction logic from persistent block-owned state. [S22], [S23]

Make the initial engineering task a small integration prototype with one existing kettle block:

1. Register a server-authoritative station with the existing block's facing.
2. Bind one real bottle slot and one real ingredient slot without copying their ownership.
3. Render and grab a paddle; show its movement to a second client.
4. Route one semantic action through the current station validation boundary.
5. Save, close, reload, break, and recover the station.
6. Prove direct-view/reduced-motion access, input cancellation, client/server separation, and coexistence with Hands-On Delight using the same library.

Pin the inspected API/build or a specifically tested release; resolve the actual published dependency coordinate from the author's current distribution or build the pinned source for development. Do not invent an artifact ID from a documentation placeholder or use a wildcard beta dependency. Test the chosen Forge patch; the two reviewed projects declare different 47.4.x build versions.

If the prototype passes, make Diegesis a declared dependency for the overhaul. Do not maintain two equally complex default interaction engines. If a concrete required feature is blocked, record the missing API and implement the smallest integration extension or local substitute necessary. This is an engineering fallback, not a reason to ship the old minigame as the new experience.

### Keep Brewing in charge of Brewing

| Responsibility | Owner |
| --- | --- |
| Recipe validity, costs, transformations, quality and effect rules | Existing Brewing services, extended where necessary |
| Resource ownership, job state, save data, completion, recovery | Brewing station domain model and ledger |
| Tool targeting, focus view, scene transforms, local feedback | Diegesis adapter and Brewing client behaviors |
| Accepted material work and physical process advancement | Brewing server simulation |
| Cosmetic fragments, wakes, droplets, sound interpolation | Client renderer/behaviors |
| Hoppers, pipes, direct use, book staging, scene transfers | Shared Brewing transaction rules |

Suggested new modules are `workshop/`, `interaction/`, and `client/workshop/`, with a narrow `integration/diegesis/` boundary. These names are proposals, not existing packages.

Avoid two inventories. Use `SlotStorage` bindings or an equivalent validated adapter over `StationInventory`; bind only public slots. Internal ledger inputs, pending outputs, and reserved containers must not appear as extractable library slots. A scene node can depict them without owning them.

Avoid two persistence systems. A Diegesis `StationState` may hold small presentation/control fields and views over inventory. The Brewing ledger remains authoritative for productive resources. Do not serialize a second copy of the same potions into the library's kept slots or tanks.

Avoid two tick owners. The block entity advances productive physical state exactly once per server tick. Library logic submits actions and mirrors state; it must not also run the simulation. Client idle logic, if used, is strictly read-only presentation. The library's normal `StationLogic.tick` only runs while a station is in use, so it is insufficient as the sole owner of an in-world process. [S22]

Avoid two permission paths. A valid schema slot or finite control value does not prove that the player may use the resource. Brewing validates the job, permissions, tool lease, current phase, and available materials after the library validates structural input. Custom actions need their own bounded payload validation.

### Refactor points

| Existing code | Required change |
| --- | --- |
| `blockentity/StationBlockEntity.java`, `block/StationBlock.java` | Route ordinary use to physical work; share lifecycle/transaction entry points; keep special use precedence |
| `ApothecaryMortarBlockEntity`, `BrewersKettleBlockEntity`, `AlembicBlockEntity` | Move new process operations out of screen-specific action handlers; make pouring and collection explicit |
| `minigame/preparation`, `minigame/brewing`, `minigame/distillation` | Extract reusable bounded material calculations; build condition-driven physical simulations; isolate legacy engines |
| `session/SessionManager`, `ActiveSession`, `SimHandle` | Separate job lifetime from a focused screen; replace whole-station input ownership with deliberate tool/control leases |
| `batch/JobLedger`, `LedgerCodec`, `JobState` | Add versioned physical process state, portion ownership, stage commit records, and resumable bottling |
| `client/ClientSession`, `Keys`, `network/packet/*` | Replace screen-only input frames for new jobs with validated tool/control intents; keep migration protocol isolated |
| `client/render/*`, `StationVisual`, `StationView` | Replace decorative-only poses with synchronized interaction/material views; render one apparatus in each mode |
| `client/screen/*` | Retire productive preparation/brew/distillation/result screens for new jobs; retain book, labels, settings, and explicit legacy support |
| `blockentity/BatchStaging`, plan/catalog services | Reuse exact preview/commit behavior; physical holders show the staged resources |
| `ApothecaryRackBlockEntity`, rack renderer, carrier handlers | Expose physical slot actions through the same exact-stack rules |
| `data/*`, generators, examples, JEI/Jade, guides | Add physical profiles, update instructions, and remove contradictory old UI descriptions |

Keep public APIs source-compatible where feasible. If a public contract must break, version and document it explicitly. Do not move the public API wholesale simply because implementation packages change.

## 13. Data-driven processes

Keep three distinct layers:

1. **Recipe authority:** existing adapters/plans decide legal inputs, costs, steps, canonical outputs, and container behavior.
2. **Physical process:** server data specifies how a legitimate transformation is prepared and tended.
3. **Presentation:** resource-pack assets and client preferences choose models, colors, animation, and sound for that state.

A resource-pack edit must never change recipe cost, required work, hit validation, or quality. A new physical profile must never create a potion result that the registered recipe adapter rejects.

### New profile resources

Add a separate, versioned `workshop_profiles` data family rather than silently extending schema-1 files with fields that the current parser ignores. Retain old reagent and brew profile IDs for compatibility; a deterministic translator builds a reasonable physical fallback when a new profile is absent. The new family can use `kind: preparation`, `brewing`, or `distillation`, with explicit target references.

Suggested location: `data/<namespace>/immersive_brewing/workshop_profiles/<path>.json`.

The following is a **proposed schema example**, not a file supported by today's mod:

```json
{
  "schema_version": 1,
  "kind": "preparation",
  "priority": 100,
  "selector": { "items": ["minecraft:sugar"] },
  "source_profile": "immersive_brewing:sugar",
  "material": "crystalline",
  "work": {
    "operations": ["press", "grind"],
    "required_work": 1.0,
    "max_work_per_tick": 0.02,
    "useful_effort": [0.25, 0.70],
    "endpoint": [0.60, 0.78],
    "overwork_starts": 0.86
  },
  "presentation": "immersive_brewing:sugar_preparation"
}
```

The example's normalized work units are not seconds. Implement and document their conversion from server-authorized tool contact. Bulk scaling is a separate bounded function, with tests for one and four reagent units.

A brewing profile needs explicit rules for workable temperature, treatment progress, dose readiness, incorporation, rest conditions, and quality sampling. It references the existing reaction family. For example, light foam can require controlled incorporation and a calm final surface; it must not demand the same arbitrary circular action for the entire batch.

Data validation must cover:

- Known schema and operation kinds; valid resource IDs and selectors.
- Finite bounded numeric values, ordered intervals, positive work rates, and achievable endpoints.
- A capped number of operations and bounded process state; no unbounded scripts or particle definitions from server data.
- Conditions for optional mods, deterministic priority/ID tie-breaking, and a clear fallback for unmatched inputs.
- Cycle/deadlock detection: every valid process has a reachable completion or explicit recoverable waiting condition.
- Reproducible snapshots: a job records the resolved profile identity, data generation, and required process parameters.
- Resource reload behavior: model reload updates presentation; recipe/data reload revalidates productive dependencies without rewriting the batch into a different recipe.

Extend the report command to show the selected physical profile, whether a fallback is in use, and why a route is unavailable. Update generated examples and validation tools together with the loader.

### Generic compatibility behavior

An unsupported presentation profile is not an unsupported recipe. If the live brewing adapter supports an item but no custom physical profile exists, use a conservative generic dissolve/stir/rest interaction. Unknown preparation is optional and contributes its existing baseline; do not grind an anvil because its recipe ingredient happens to be an anvil.

Keep existing custom potion/effect policy distinctions. “Brews successfully,” “records workmanship,” and “scales effects” are separate support levels.

## 14. Conservation and transaction design

Physical interaction makes ownership harder to see in code. Specify it before writing animations.

### Batch portions, not an unrestricted potion tank

Represent a kettle charge as one batch containing a bounded collection of **discrete serving records**. Each record retains its authoritative source stack and reserved container relationship. The liquid surface represents those records; it is not an independently drainable second inventory.

Use the current kettle's homogeneous canonical-input and metadata checks as a starting point. Preserve exact per-item foreign tags/capabilities where the actual adapter preserves them; apply the same transformations the canonical recipe would apply. Never homogenize all bottle tags by cloning the first bottle solely because they have the same potion color or display name. [S9]

A record can move through states such as `STAGED`, `CHARGED`, `TRANSFORMED`, and `BOTTLED`. Its visual amount may interpolate, but its authoritative material is owned once. The empty glass shell depicted in the rack is reserved while its serving is in the kettle; it cannot also be extracted and replaced for another free bottle.

Existing bucket/pipe filling remains a supported way to prepare water bottles. A future open water reservoir would require a separate resource model; this overhaul does not need one for the kettle. The alembic's new water-only coolant tank is separate and may use ordinary fluid capabilities.

### Transaction boundaries

| Action | Required atomic result |
| --- | --- |
| Stage plan | Validate revisions and move exact costs into accessible staging slots once; no chemical progress |
| Begin productive batch | Reserve recipe, inputs, tools/costs, output capacity, and container ownership |
| Pour an input serving | Move that portion from staged ownership to charged ownership; no duplicate empty bottle |
| Add a reagent dose | Mark the identified reserved unit as added once; preserve its by-product obligation |
| Finish chemical processing | Validate actual recipe outputs and event vetoes; freeze quality; persist the resulting ready portions once |
| Fill a bottle | Move one ready portion into its reserved finished-container slot; leave remaining portions owned |
| Collect a finished bottle | Transfer the exact finished stack; no second completion, XP, or quality award |
| Purify | Consume the previewed group, produce the recipe yield, and return the sacrificed portions' containers exactly once |
| Break a working station | Drop unrelated public inventory and one recovery representation of still-owned internal resources |

Predicted pours are presentation only until accepted. Use an explicit transaction identifier and current job/slot revision. A rejection restores the displayed pose and quantity from the server; it never deletes a real client inventory stack to make an animation look right.

For tipping a discrete bottle/dose, animate flow but commit a whole valid unit at a defined transfer boundary. If the player stops before that boundary, ownership stays at the source. If it has crossed, ownership stays in the batch. If fractional material transfer is later introduced, it needs exact persistent units and all the same conservation tests; do not fake it with a floating-point ratio.

At chemical completion, persist a finalized ready-output set before exposing bottle transfers. After that point, recipe reloads cannot transform those finalized outputs again. A full output slot pauses dispensing; it does not cause another recipe commit. Award a batch's completion event/XP once at the defined chemical completion boundary, not once per bottle or once per reopening. Record the batch journal once and update collection information without awarding it again.

For a chained route, preserve a working canonical stack for each serving and the committed recipe-step index. Evaluate only steps not already applied. Final output preparation must not replay already committed adapters or emit intermediate completion events. A preview is pure; an event veto leaves a recoverable pending state and does not repeatedly debit costs on retry. Use the existing adapters' actual commit/purity contracts rather than assuming that calling an arbitrary third-party adapter twice is harmless.

Cancellation before irreversible processing returns exact staged resources. After processing has begun, seal/recover the batch or use a supported reclamation path. Do not offer a cancellation that refunds fresh reagents while retaining their processed result or earned score.

### Required numerical examples

| Scenario | Conserved result |
| --- | --- |
| Three filled inputs, ordinary three-serving brew | Three finished filled outputs; no extra loose empty bottles |
| Six inputs, bottle two, break station | Two collected outputs plus one recovery batch owning four remaining portions and their containers |
| Three inputs, standard purification | Two filled outputs plus one empty returned container |
| Four raw reagents prepared together | Four prepared kits representing four source reagent units |
| Ingredient with a crafting remainder | Its remainder once at the defined commit; no animation-based duplication |
| Disconnect while tipping a dose | Either source still owns the dose or the batch owns it, according to the committed boundary |
| Empty output space disappears between preview and transfer | No debit; retain the ready portion and explain that the receiving slot is occupied |

Preserve the existing caveat that Minecraft does not provide cross-file transactional saves against arbitrary filesystem rollback. The guarantee is correct game-state ownership and idempotent transitions, not recovery from every external rollback. [S12]

## 15. Networking and authority

The client sends **intent**, never workmanship, recipes, finished items, or elapsed productive time. Use a compact action envelope with station dimension/position, job ID, action sequence, relevant revision, tool/control ID, and bounded action-specific data.

The server checks:

- Sender is alive, in the correct dimension, within configured interaction reach, and permitted to use the block.
- The station exists in a loaded chunk; a packet cannot load or generate distant chunks.
- The selected physical part is compatible with the current phase and the tool/ingredient actually owned.
- The action matches the current lease and is not stale, duplicated, or already committed.
- All numeric input is finite; normalized positions, deltas, work rates, and sample counts are bounded.
- Meaningful line of sight/obstruction and station-facing geometry, with tolerances appropriate to the focus camera.
- Sufficient resources, remainder space, container ownership, and compatible live recipe state.

Server bounds must derive from server-defined interaction geometry, not client resource-pack hitboxes. A client may make a control easier to see; it cannot move its usable target through a wall.

Sample movement at a modest fixed rate, initially around 10–20 updates per second while actively moving, with bounded batches if needed. Interpolate locally at render frame rate. Use one coordinated action budget: Diegesis documents a 40-actions-per-second limit, so ingredient transfers, control changes, and pose updates must not compete in an uncontrolled flood. Measure actual library traffic in the prototype before setting final rates. [S22]

Never accrue work from repeated packets inside one server step. Client timestamps may help smooth a gesture but cannot advance more simulated work than the server budget permits. After a long input gap, stop productive motion. Do not connect the last valid point to a distant new point and award a giant grinding stroke.

Send compact material/control snapshots and accepted actions to tracking clients. Do not broadcast the whole ledger or every particle each tick. Nearby spectators need the visible item subset, process state, bounded tool poses, and public attribution; they do not need another player's inventory or private notebook notes.

An explicit version handshake must reject incompatible productive protocols clearly. The reference library's client-only capabilities do not mean an Immersive Brewing client can productively craft against an unmodified or mismatched server.

## 16. Multiplayer, interruptions, and safe stopping

Separate focus from process ownership. Leaving a close-up view is not automatically cancellation. The simulation belongs to the block, not to a screen instance.

### Cooperative work

Allow shared observation and useful cooperation. Use short server leases for individual tools and conflicting controls rather than a single unrestricted shared cursor.

| Resource | Concurrency rule |
| --- | --- |
| Pestle or paddle | One active hand; a second user sees who holds it |
| Burner/control assembly | One live drag lease; discrete changes serialize |
| Reserved ingredient dose | Exactly one transfer transaction |
| Bottle/receiver slot | Atomic extraction/fill; stale requests fail harmlessly |
| Different stations | Independent work, subject to the usual player interaction limits |
| Ready rack contents | Shared only according to normal access/protection rules |

One player can stir while another adds the next allowed dose or operates a different unleased control. For the first integration prototype, single-operator work is acceptable; full release requires the defined contention behavior and at least the useful cooperative cases above. Reusing an exclusive old session as a permanent restriction is not the full specification.

Keep the original brewer credited and record a bounded assistant list for productive contribution. Opening a view or taking a bottle is not productive assistance. A helper's disconnect does not end another player's valid work.

### Interruption policy

Default to a forgiving attended workshop:

- A player leaving focus while remaining a participant nearby can walk to the adjacent mortar or rack. The kettle continues through safe current treatment, then waits at an attention boundary.
- If no authorized participant remains in range, use a short grace period (initial target: 2 seconds) and seal the job. Close the burner/lid visually, save exact process state, and stop further cost or penalty accumulation.
- A passerby is not automatically a participant and does not keep someone else's brew running.
- Manual sealing is immediate and has an obvious lid/clamp interaction.
- Logout, death, dimension change, shutdown, chunk unload, or lost dependencies release leases and preserve the batch. No offline progression or force-loaded chunks.
- A recipe/data reload invalidates only what can no longer be validated. Hold unresolved work with a specific reason; never substitute another route or reroll its state.
- Once output is chemically ready, it waits safely. Collection does not have a freshness countdown.

This is a deliberate game convenience rather than a claim of realistic chemistry. It lets players manage the workshop and interruptions without losing expensive ingredients.

Pause/seal/resume cannot reset cumulative overwork, penalties, committed dose flags, or quality. On resume, neutralize old tool input and provide a short readable return to the apparatus. Do not require the player's original screen or original connection to exist.

## 17. Persistence and upgrade handling

Keep all existing block and item registry IDs. A new model or new physical interaction does not justify replacing a placed block with a new ID. Preserve inventories, fuel, prepared kits, rack filters, carrier contents/preferences, labels, notebooks, and potion metadata.

Introduce an explicitly versioned physical-process payload. Store its process kind, resolved profile parameters, portion states, committed doses, accumulated material/quality values, ready outputs, and finalized-event state. Keep sizes bounded. Tool leases are transient and clear on load; the material state persists.

### Legacy jobs

The current schema stores old simulations as parameter/state arrays. Those arrays cannot safely be relabeled as the new material state. [S8]

Use this concrete migration policy:

1. Idle stations and finished inventory adopt the new presentation immediately.
2. Unstarted legacy reservations cancel into their original slots through existing exact-return logic.
3. Active/sealed legacy jobs retain their original payload and execute through a versioned legacy-resume path until completed. A clearly labeled **Finish existing batch** option may open the old interface for these jobs only. No new job can enter it.
4. Legacy passive reclamation/container-finishing work retains its snapshotted costs and completes through the matching legacy path.
5. Missing dependency, invalid, or newer-schema payloads remain held and preserved. Do not partially deserialize and overwrite them.
6. Recovery Batch items use the same dispatcher. Installing one transfers ownership once, and the destination must be empty and compatible.

This migration-only exception avoids manufacturing a false conversion between unrelated simulations. Retain the old decoder for a documented support period; do not leave two selectable product modes for new brewing. If a later lossless migration is implemented, prove it with real fixtures before removing the legacy-resume path.

Maintain separate versioning for job data, potion metadata, guide data, and network protocol. Increasing one is not permission to reinterpret the others. Include save/load and break/install fixtures from the reviewed 1.1.0 format, not merely files created by the new implementation.

## 18. Compatibility and automation

| Integration | Required behavior |
| --- | --- |
| Vanilla brewing | Preserve `COEXIST`, `REPLACE_COVERED`, modifier policy, and disabled-mode recovery semantics |
| Forge brewing adapters | Live recipe validation remains authoritative; physical profiles cannot invent results |
| JEI | Update categories to explain actual tools/actions; transfer stages supplies but does not execute work |
| Jade | Show useful stage, contents, heat description, active user, ready output, and recovery reason; avoid duplicate overlays in focus view |
| Optional potion mods | Replay the existing Apothecary, More Potions, PotionsMaster, and RPG Potions journey matrix; support labels remain evidence-based |
| Immersive Smithing | Preserve iron paddle recipe/wear behavior and prevent double application of quality-based durability |
| Immersive Tanning | Preserve strapped carrier identity and exact contents |
| Enchanting/Jewelry | Preserve supported paddle enchantments and composable drinking behavior |
| Hoppers/pipes | Existing accessible slots remain usable; reserved materials and unfinished portions remain inaccessible |
| Redstone/comparators | Document loaded, working, waiting, sealed, and ready states; do not confuse an empty public rack with an empty batch |
| Hands-On Delight | Both mods may share Diegesis without stealing each other's controls, station registrations, resource names, or focus |
| Rendering mods/shaders | Test chosen Forge combinations, including Embeddium/Oculus; do not infer support from the reference mod's README |
| Claims/protection | Respect established permission hooks and ordinary world interaction rules for both direct and focused use |

Baseline staging automation may supply bottles, reagents, and fuel and extract completed output. It does not forge tool gestures or grant workmanship. Keep reclamation passive where it already is passive. Do not enable unattended kettle production merely because a library can tick a station while nobody is focused.

For future automation, define a separate deliberate process policy with explicit quality/cost behavior. It is not part of the completion criteria for this overhaul.

Every simulation-mode item or fluid capability call must be non-mutating. Test side-specific insertion, exact metadata matching, full output, fluid remainders, and changes during an active job. No client-only class may be referenced from dedicated-server initialization.

## 19. Art, animation, and sound requirements

### State-driven presentation

| State change | Required visual feedback | Useful sound |
| --- | --- | --- |
| Ingredient placed | Exact ingredient appears at its real holder | Soft material-specific placement |
| Pestle contacts material | Local compression/fracture and changing coarse pieces | Crunch, scrape, or wet mash |
| Dose transfers | Particles/paste/stream travel from the actual source to destination | Granules, plop, or short pour |
| Paddle moves | Tool contacts liquid; wake and ingredients follow plausibly | Low stir/swish matched to movement |
| Heat increases | Flame changes first, then bubbles/steam respond | Burner and simmer layers |
| Foam/sediment develops | Readable material condition with a useful correction | Restrained cue, never required on its own |
| Alembic works | Vapor path, condenser beads, actual diverter position | Quiet hiss and drip rhythm |
| Bottle fills | Continuous apparent transfer and declining batch level | Fill and glass placement |
| Batch sealed | Tools rest; lid/cloth/clamp closes; flame stops | Short closure |
| Batch ready | Calm stable appearance and accessible output | One restrained completion cue |

Use ingredient-specific models when they exist; provide a simple fallback when they do not. Keep floating fragments bounded and visually subordinate to the tool. Do not promise an expensive voxelization system for every modded item.

Where player hands are shown, align the held tool with an appropriate arm/grip pose and respect normal/slim player models and handedness. Keep the contact point stable. Nearby players must see the actual tool action even if detailed arm posing is simplified; never render a second tool in the hand while its authoritative item is already shown in use at the vessel.

Build the idle, focused, and spectator representations from shared assets. If a scene hides the base model, hide only the correct drawing path and restore it on exit, resource reload, and error. Avoid double-rendered glass, duplicate liquid planes, and invisible blocks when no user is focused.

Transparent surfaces need testing from all sides and with multiple nearby stations. Make liquid, glass, particles, and item outlines sort acceptably without relying on a particular shader. A low-effects preset still shows all meaningful state changes.

Provide source models/textures, animation definitions, icons, and sound references in the repository's established art workflow. Update or replace generator checks deliberately; hand-edited assets must not be overwritten on the next generator run. Record which outputs are generated and which are authored.

### Performance

- Use fixed-size material state, pooled/bounded cosmetic objects, distance/frustum culling, and effect budgets per client.
- Do not scan recipes, sample textures, allocate meshes, or rebuild full scene graphs in every render frame.
- Stop off-screen cosmetic work; sleep idle process logic when no state can change.
- Cache ingredient presentation and invalidate it on resource reload.
- Interpolate tool pose and fluid appearance from sparse snapshots rather than syncing particles.
- Cap simultaneous sound layers by distance and activity; ten bubbling kettles must not become ten full-volume loops.

Measure a quiet workshop, several active stations, and a stress scene with approximately 32 active stations/nearby observers. On recorded reference hardware, target a small measurable server tick cost and stable frame times; set the actual release budget after the first prototype measurement. Compare against the baseline scene and report median/p95 costs and packet volume. Do not claim an unmeasured FPS increase.

## 20. Implementation sequence and deliverables

Do this work on an isolated overhaul branch. Keep the old release buildable while the new path is being introduced. Milestones are an engineering sequence; all required stations and lifecycle cases must be finished before the overhaul is called complete.

| Work package | Deliverable | Exit condition |
| --- | --- | --- |
| IB-HO-01: baseline and dependency prototype | Revision record, recipe/metadata fixture set, one physical kettle scene, pinned dependency decision | Two clients see the same tool; slots remain exact; dedicated server starts; required access modes have a viable path |
| IB-HO-02: ownership and commands | Domain actions, revisions, portion records, tool leases, authoritative tick owner | Duplicate/replayed actions and competing transfers cannot create or delete resources |
| IB-HO-03: complete first-batch slice | Water → Awkward → Swiftness with load, charge, heat, dose, stir, rest, and bottle | Useful finished potions, no productive minigame screen, observer visibility, save/recovery works |
| IB-HO-04: physical preparation | Five material families, batching, extraction, kit compatibility | Materials visibly change; endpoint readable; keyboard path reaches same maximum quality |
| IB-HO-05: reaction coverage | Physical profiles/fallbacks, multi-step routes, modifiers, splash/lingering finishing | Every currently supported route remains available or has an explicit verified reason for exclusion |
| IB-HO-06: physical alembic | Charge, coolant, heat, condenser/diverter, receiver, purification and reclamation | Exact yield/quality/remainders, clear feedback, interruption safety |
| IB-HO-07: whole-workshop flow | Physical rack/carrier actions, book staging, labels, onboarding, practice, journal | A new player completes the full journey and packs the result without relying on old crafting screens |
| IB-HO-08: cooperative behavior | Shared viewing, per-tool leases, assistance attribution, conflict feedback | Two users can help without fighting ownership or multiplying outputs |
| IB-HO-09: upgrade and integrations | Legacy dispatcher, old-world fixtures, compatibility journey matrix | No lost saved inventory/jobs/metadata; live recipe and effect semantics remain correct |
| IB-HO-10: art and accessibility pass | Final apparatus assets, sound, camera, text/shape/narration, scale/FOV support | Tactile/readable at normal and reduced settings; no input trap or required precise gesture |
| IB-HO-11: performance and release | Profiles, updated tests/tools/docs, release notes, evidence | Required checks pass and remaining limitations are stated precisely |

### Required repository documentation

- Rewrite the main player guide/README around physical actions and a real first-batch walkthrough.
- Update architecture decisions for tick ownership, portions/containers, physical inputs, and safe stopping.
- Update datapack/API/configuration documentation with profile schemas and compatibility guarantees.
- Add a concise migration guide explaining the legacy existing-batch exception.
- Replace obsolete key tables, timed-minigame descriptions, screenshots, and tutorial hints.
- Include a coverage report mapping all former features to retained, replaced, or deliberately removed behavior.
- Include an implementation report with tested revision, dependency versions, commands, results, screenshots/video references, and known limitations.

## 21. Verification plan

Tests should exercise real failure boundaries and gameplay contracts. Do not replace old assertions with weaker ones merely because the UI changes. Update simulation-specific tests where the intended process changed; retain independent economy, metadata, recipe, recovery, and effect tests.

### Deterministic domain tests

- Tool movement within contact produces bounded work; motion outside the material produces none.
- Different packet rates and frame rates replay to equivalent state within documented tolerances.
- Stationary input, repeated identical samples, teleporting tool points, invalid values, and excessive speed do not farm progress.
- Appropriate material work reaches a usable endpoint; continued excessive work does not improve it indefinitely.
- Correct heating/incorporation/resting completes every process; required attention states remain reachable and recoverable.
- Sealing preserves every cumulative score contribution, dose flag, portion state, and profile parameter.
- Accessible tool actions and mouse gestures can reach the same valid endpoint and quality range.
- Container-only finishing retains its proof and inherited metadata; ordinary modifiers cannot raise a finished potion's quality.

### GameTests and server integration

| Test family | Required cases |
| --- | --- |
| Complete routes | One/three/six servings; raw and prepared reagents; multistep route; existing potion input; corruption; modifiers; splash/lingering |
| Conservation | Every numerical example in section 14; fuel, crafting remainders, coolant, source/receiver capacity |
| Contention | Two users grab one paddle, pour one dose, collect one bottle, stage against stale inventory, and change a leased control |
| Lifecycle | Close focus, leave range, damage, death, logout, dimension change, unload, restart, `/reload`, break/install at every major stage |
| Partial output | Bottle some servings, stop, save, recover, resume; verify no duplicate completion event or XP |
| Upgrade | Schema-1/schema-2 jobs, prepared kits, recovery items, rack/carrier/Notebook data, unknown/newer fields, missing mods |
| Recipe changes | Changed winner, removed recipe, unavailable reagent profile, event veto, budget-limited discovery |
| Potion semantics | Quality 0/50/100 drinking, splash, lingering, arrows, dispensers, custom effects, instant effects, Turtle Master |
| Automation | Insert/extract from every exposed side; simulation non-mutation; reservation protection; output backpressure |
| Protocol validation | Stale IDs, duplicate sequence, replayed transfer, invalid floats, oversized payloads, bad dimension, obstruction, remote position |

### Client and multiplayer playtests

Record a genuine end-to-end playtest, not only a staged visual gallery. Cover:

- New player completing a batch without an external guide; experienced player making repeated batches without excessive gestures.
- Each material family with visible before/after state and recognizable tool contact.
- All station facings, cramped benches, adjacent stations, ordinary first-person and focused views.
- Mouse-only, keyboard-only, toggle, reduced-input, reduced-motion, high-contrast, and narration paths.
- Common GUI scales, wide/narrow aspect ratios, altered FOV, and resource reload while working.
- Dedicated-server play with two clients, helper disconnect, late observer join, and station break during a transfer.
- Approximate latency cases of 0/100/250 ms plus jitter, and lower-TPS behavior; these are test conditions, not claims of perfect equivalence under every network.
- Full player inventory, blocked output, empty fuel, missing coolant, damaged/exhausted paddle, wrong ingredient, unsupported recipe.
- Shader/rendering combinations selected for the release, particularly overlapping glass/liquid and spectator rendering.

### Existing commands to keep operational

The inspected repository documents these commands. Adapt their fixtures to the new interaction path and record the actual results:

```sh
./gradlew build
./gradlew runGameTestServer
./gradlew runClient -PsmokeTest
python3 tools/generate_data.py --check
python3 tools/generate_textures.py --check
python3 tools/generate_sounds.py --check
```

The current smoke suite is built around the old client journey. Rewrite relevant controls/assertions so it proves physical loading, tool contact, reaction state, and bottling. Retain a smaller migration-only smoke path for an old saved batch. Run the existing compatibility switches with the exact available test dependencies; a missing optional jar is an untested configuration, not a passing test. [S24]

## 22. Definition of done

The overhaul is ready only when all of the following are true:

- A new productive batch can be prepared, brewed, distilled if desired, bottled, labeled, stored, and carried through physical interactions.
- The mortar, kettle, alembic, and rack each have a useful complete player journey.
- Tool position and material response matter to the server-authorized process.
- Normal crafting uses no legacy preparation/brewing/distillation minigame screen.
- The process can be understood from apparatus/material cues, supported by optional accessible information.
- Batch convenience removes repeated handling without doing the skilled work automatically.
- Nearby players see the same work and defined cooperative actions are safe.
- Every input, empty container, ready portion, by-product, tool, and output has one owner through all transitions.
- Existing recipe/effect/quality contracts and supported integrations pass their regression checks.
- Existing worlds and unfinished batches have a tested recovery or completion path.
- Accessibility settings do not impose a lower quality ceiling.
- Real playtests establish that the actions feel responsive, satisfying, and worth repeating.
- Documentation and screenshots describe the delivered behavior, and the final report distinguishes tested results from remaining limitations.

The defining success is simple: a player remembers making a potion by working with a bubbling vessel, ingredients, and tools. The software infrastructure supports that experience without becoming the activity itself.

## Source index

The links below point to the inspected revisions. Repository documentation is evidence of declared behavior; source inspection establishes implementation structure. Test success described in upstream documentation was not independently rerun for this handoff.

| Reference | Evidence |
| --- | --- |
| [S1] | Current player flow and declared feature contracts |
| [S2] | Reference mod version and dependencies |
| [S3] | Interaction library version and platform |
| [S4] | Station use, resource lifecycle, and shared block-entity infrastructure |
| [S5] | Current productive screens |
| [S6] | Current preparation, brewing, and distillation simulations |
| [S7] | Current in-world kettle rendering |
| [S8] | Ledger schema, serialization, and recovery handling |
| [S9] | Batch validation, canonical commits, and bottle-based water handling |
| [S10] | Quality formula, rounding, effect-duration and purification bounds |
| [S11] | Container-only conversion proof and metadata preservation |
| [S12] | Existing ownership, quality, discovery, and compatibility decisions |
| [S13] | Reference station schema and opening behavior |
| [S14] | Reference inventory bindings and server station logic |
| [S15] | Reference tool manipulation, liquid response, and spectator poses |
| [S16] | Reference movement evaluation and recipe-specific star progression |
| [S17] | Reference project's MIT notice |
| [S18] | Preparation input count, kit production, and job ownership |
| [S19] | Existing reagent schema and fallback behavior |
| [S20] | Purification eligibility, output and container accounting |
| [S21] | Existing accessibility and presentation preferences |
| [S22] | Public station API, bound storage, state lifetime, and action limits |
| [S23] | Public behavior, scene-event, and tool-presentation APIs |
| [S24] | Existing verification commands, fixtures, and compatibility matrix |

[S1]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/README.md
[S2]: https://github.com/Pocky-l/handson-delight/blob/7d772f0ccf88df32b85f085a7535dc5449bff5ef/gradle.properties
[S3]: https://github.com/Pocky-l/diegesis/blob/c60c89b01f1a8c9bb0052e1f91b593155da4aaa5/gradle.properties
[S4]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/blockentity/StationBlockEntity.java
[S5]: https://github.com/otectus/ImmersiveBrewing/tree/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/screen
[S6]: https://github.com/otectus/ImmersiveBrewing/tree/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/minigame
[S7]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/client/render/KettleRenderer.java
[S8]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/batch/LedgerCodec.java
[S9]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/blockentity/BrewersKettleBlockEntity.java
[S10]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/potion/QualityMath.java
[S11]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/recipe/QuickFinishing.java
[S12]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/docs/ARCHITECTURE.md
[S13]: https://github.com/Pocky-l/handson-delight/blob/7d772f0ccf88df32b85f085a7535dc5449bff5ef/common/src/main/java/com/pockyl/handson_delight/station/CookingPotStation.java
[S14]: https://github.com/Pocky-l/handson-delight/blob/7d772f0ccf88df32b85f085a7535dc5449bff5ef/common/src/main/java/com/pockyl/handson_delight/station/PotLogic.java
[S15]: https://github.com/Pocky-l/handson-delight/blob/7d772f0ccf88df32b85f085a7535dc5449bff5ef/common/src/main/java/com/pockyl/handson_delight/client/PotBehavior.java
[S16]: https://github.com/Pocky-l/handson-delight/blob/7d772f0ccf88df32b85f085a7535dc5449bff5ef/common/src/main/java/com/pockyl/handson_delight/station/StirSkill.java
[S17]: https://github.com/Pocky-l/handson-delight/blob/7d772f0ccf88df32b85f085a7535dc5449bff5ef/LICENSE
[S18]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/blockentity/ApothecaryMortarBlockEntity.java
[S19]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/data/ReagentProfile.java
[S20]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/blockentity/AlembicBlockEntity.java
[S21]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/src/main/java/com/otectus/immersivebrewing/config/ClientConfig.java
[S22]: https://github.com/Pocky-l/diegesis/blob/c60c89b01f1a8c9bb0052e1f91b593155da4aaa5/docs/stations.md
[S23]: https://github.com/Pocky-l/diegesis/blob/c60c89b01f1a8c9bb0052e1f91b593155da4aaa5/docs/client.md
[S24]: https://github.com/otectus/ImmersiveBrewing/blob/12f9048009169069da6498160b3b29a8c5d14286/docs/TESTING.md

