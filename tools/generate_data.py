#!/usr/bin/env python3
"""Generates Immersive Brewing's JSON resources from the tables below.

  python3 tools/generate_data.py            # --check (default): verify every generated file exists and matches
  python3 tools/generate_data.py --write    # write every generated file

Covers: the vanilla infusion plans (every mix of PotionBrewing.bootStrap in Forge 1.20.1-47.4.23, for drinkable,
splash and lingering bottles), the generic container conversions, reagent and brew profiles, Alembic recipes,
crafting recipes, block loot tables, tags, blockstates and block/item models, and the data for the optional potion mods
under compat/ (plans, reagent profiles, effect policies and Alembic recipes, each conditioned on its mod). Hand-written files (lang, the spec's
swiftness example, advancements) are not touched.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'src/main/resources'
DATA = RES / 'data/immersive_brewing'
ASSETS = RES / 'assets/immersive_brewing'
MOD = 'immersive_brewing'

# ---- vanilla mixes (from net.minecraft.world.item.alchemy.PotionBrewing.bootStrap, 1.20.1) ----
# (from potion, reagent item, to potion)
MIXES = [
    ('water', 'glistering_melon_slice', 'mundane'), ('water', 'ghast_tear', 'mundane'), ('water', 'rabbit_foot', 'mundane'),
    ('water', 'blaze_powder', 'mundane'), ('water', 'spider_eye', 'mundane'), ('water', 'sugar', 'mundane'),
    ('water', 'magma_cream', 'mundane'), ('water', 'glowstone_dust', 'thick'), ('water', 'redstone', 'mundane'),
    ('water', 'nether_wart', 'awkward'),
    ('awkward', 'golden_carrot', 'night_vision'), ('night_vision', 'redstone', 'long_night_vision'),
    ('night_vision', 'fermented_spider_eye', 'invisibility'), ('long_night_vision', 'fermented_spider_eye', 'long_invisibility'),
    ('invisibility', 'redstone', 'long_invisibility'),
    ('awkward', 'magma_cream', 'fire_resistance'), ('fire_resistance', 'redstone', 'long_fire_resistance'),
    ('awkward', 'rabbit_foot', 'leaping'), ('leaping', 'redstone', 'long_leaping'), ('leaping', 'glowstone_dust', 'strong_leaping'),
    ('leaping', 'fermented_spider_eye', 'slowness'), ('long_leaping', 'fermented_spider_eye', 'long_slowness'),
    ('slowness', 'redstone', 'long_slowness'), ('slowness', 'glowstone_dust', 'strong_slowness'),
    ('awkward', 'turtle_helmet', 'turtle_master'), ('turtle_master', 'redstone', 'long_turtle_master'),
    ('turtle_master', 'glowstone_dust', 'strong_turtle_master'),
    ('swiftness', 'fermented_spider_eye', 'slowness'), ('long_swiftness', 'fermented_spider_eye', 'long_slowness'),
    ('awkward', 'sugar', 'swiftness'), ('swiftness', 'redstone', 'long_swiftness'), ('swiftness', 'glowstone_dust', 'strong_swiftness'),
    ('awkward', 'pufferfish', 'water_breathing'), ('water_breathing', 'redstone', 'long_water_breathing'),
    ('awkward', 'glistering_melon_slice', 'healing'), ('healing', 'glowstone_dust', 'strong_healing'),
    ('healing', 'fermented_spider_eye', 'harming'), ('strong_healing', 'fermented_spider_eye', 'strong_harming'),
    ('harming', 'glowstone_dust', 'strong_harming'),
    ('poison', 'fermented_spider_eye', 'harming'), ('long_poison', 'fermented_spider_eye', 'harming'),
    ('strong_poison', 'fermented_spider_eye', 'strong_harming'),
    ('awkward', 'spider_eye', 'poison'), ('poison', 'redstone', 'long_poison'), ('poison', 'glowstone_dust', 'strong_poison'),
    ('awkward', 'ghast_tear', 'regeneration'), ('regeneration', 'redstone', 'long_regeneration'),
    ('regeneration', 'glowstone_dust', 'strong_regeneration'),
    ('awkward', 'blaze_powder', 'strength'), ('strength', 'redstone', 'long_strength'), ('strength', 'glowstone_dust', 'strong_strength'),
    ('water', 'fermented_spider_eye', 'weakness'), ('weakness', 'redstone', 'long_weakness'),
    ('awkward', 'phantom_membrane', 'slow_falling'), ('slow_falling', 'redstone', 'long_slow_falling'),
]
CONTAINERS = ['potion', 'splash_potion', 'lingering_potion']
BASES = {'water', 'awkward', 'mundane', 'thick'}

FAMILY = {
    'swiftness': 'light_foam', 'strength': 'dense_sediment', 'turtle_master': 'dense_sediment',
    'healing': 'delicate_shimmer', 'regeneration': 'delicate_shimmer', 'fire_resistance': 'magma_streaks',
    'water_breathing': 'suspended_bubbles', 'poison': 'dark_whorls', 'weakness': 'dark_whorls', 'harming': 'dark_whorls',
    'night_vision': 'aligning_glints', 'invisibility': 'aligning_glints', 'slow_falling': 'floating_motes',
    'leaping': 'floating_motes', 'slowness': 'floating_motes',
}


def family(potion):
    base = potion.removeprefix('long_').removeprefix('strong_')
    return FAMILY.get(base)


def role(src, reagent, dst):
    if src == 'water' and dst in BASES:
        return 'bloom', 'base'
    if reagent in ('redstone', 'glowstone_dust'):
        return 'settle', 'modifier'
    if reagent == 'fermented_spider_eye' and src != 'water':
        return 'infuse', 'corruption'
    return 'infuse', 'effect'


def infusion(src, reagent, dst, container):
    phase, rl = role(src, reagent, dst)
    recipe = {
        'type': f'{MOD}:infusion',
        'schema_version': 1,
        'adapter': f'{MOD}:forge_registry',
        'input': {'item': f'minecraft:{container}', 'potion': f'minecraft:{src}', 'match': 'canonical'},
        'steps': [{'reagent': {'item': f'minecraft:{reagent}'}, 'phase': phase, 'role': rl}],
        'expected_output': {'item': f'minecraft:{container}', 'potion': f'minecraft:{dst}'},
        'batch': {'max_servings': 6, 'servings_per_reagent': 3},
    }
    profile = family(dst)
    if profile:
        recipe['brew_profile'] = f'{MOD}:{profile}'
    if rl in ('effect', 'corruption') and reagent in PREPARABLE:
        recipe['steps'][0]['preparation_profile'] = f'{MOD}:{reagent}'
    return recipe


# ---- reagent profiles ----
# reagent: (mode, duration, endpoint, pressure limit, overwork, reaction profile, cooling, sensitivity)
PREPARABLE = {
    'sugar': ('crystalline', 240, [0.60, 0.78], 0.70, 0.86, 'light_foam', 0.08, 0.65),
    'blaze_powder': ('crystalline', 220, [0.62, 0.80], 0.65, 0.88, 'dense_sediment', 0.05, 1.0),
    'glistering_melon_slice': ('fibrous', 260, [0.55, 0.75], 0.60, 0.85, 'delicate_shimmer', 0.07, 0.8),
    'ghast_tear': ('brittle', 220, [0.58, 0.76], 0.65, 0.86, 'delicate_shimmer', 0.06, 0.7),
    'magma_cream': ('liquid', 260, [0.60, 0.80], 0.80, 0.90, 'magma_streaks', 0.03, 1.0),
    'pufferfish': ('soft', 260, [0.55, 0.75], 0.50, 0.85, 'suspended_bubbles', 0.10, 0.9),
    'golden_carrot': ('fibrous', 260, [0.58, 0.78], 0.60, 0.87, 'aligning_glints', 0.07, 0.8),
    'spider_eye': ('soft', 240, [0.55, 0.75], 0.50, 0.85, 'dark_whorls', 0.07, 1.0),
    'fermented_spider_eye': ('soft', 240, [0.55, 0.75], 0.50, 0.85, 'dark_whorls', 0.07, 1.2),
    'rabbit_foot': ('fibrous', 240, [0.55, 0.75], 0.60, 0.85, 'floating_motes', 0.06, 0.8),
    'phantom_membrane': ('fibrous', 260, [0.58, 0.78], 0.55, 0.87, 'floating_motes', 0.06, 0.9),
    'turtle_helmet': ('brittle', 280, [0.55, 0.72], 0.75, 0.84, 'dense_sediment', 0.10, 1.0),
}
# Catalysts and modifiers: no preparation (spec 6), but they still cool the brew when added.
UNPREPARED = {'nether_wart': 0.04, 'redstone': 0.03, 'glowstone_dust': 0.03, 'gunpowder': 0.04, 'dragon_breath': 0.05}


def reagent_profile(item):
    if item in PREPARABLE:
        mode, duration, endpoint, limit, overwork, profile, cooling, sensitivity = PREPARABLE[item]
        out = {'schema_version': 1, 'priority': 100, 'selector': {'items': [f'minecraft:{item}']},
               'preparation': {'mode': mode, 'duration_ticks': duration, 'endpoint': endpoint, 'pressure_limit': limit,
                               'overwork_threshold': overwork},
               'reaction': {'profile': f'{MOD}:{profile}', 'addition_cooling': cooling, 'foam_sensitivity': sensitivity}}
        return out
    return {'schema_version': 1, 'priority': 100, 'selector': {'items': [f'minecraft:{item}']},
            'reaction': {'addition_cooling': UNPREPARED[item], 'sensitivity': 1.0}}


# ---- brew profiles: infuse (and optional settle) reactions ----
ALL_DISTURBANCES = ['cold_draft', 'burner_flare', 'surge']
BREW = {
    'generic': {'infuse': ('none', [0.46, 0.60], [0.25, 0.55]), 'accent': '#E8EEF8', 'particle': 'steam'},
    'light_foam': {'infuse': ('light_foam', [0.46, 0.60], [0.30, 0.58]), 'accent': '#F4F6FF', 'particle': 'foam'},
    'dense_sediment': {'infuse': ('dense_sediment', [0.50, 0.64], [0.25, 0.52]), 'accent': '#7A1E1E', 'particle': 'steam'},
    'delicate_shimmer': {'infuse': ('delicate_shimmer', [0.38, 0.52], [0.10, 0.36]), 'accent': '#FFE0F0', 'particle': 'glint'},
    'magma_streaks': {'infuse': ('magma_streaks', [0.50, 0.66], [0.40, 0.70]),
                      'settle': ('settle_clear', [0.30, 0.42], [0.0, 0.22]), 'accent': '#FF8A30', 'particle': 'steam'},
    'suspended_bubbles': {'infuse': ('suspended_bubbles', [0.44, 0.58], [0.25, 0.55]), 'accent': '#A0D8FF', 'particle': 'bubble'},
    'dark_whorls': {'infuse': ('dark_whorls', [0.42, 0.56], [0.05, 0.30]), 'accent': '#3A2A40', 'particle': 'steam'},
    'aligning_glints': {'infuse': ('aligning_glints', [0.44, 0.58], [0.20, 0.45]),
                        'settle': ('aligning_glints', [0.36, 0.48], [0.0, 0.22]), 'accent': '#FFF8C0', 'particle': 'glint'},
    'floating_motes': {'infuse': ('floating_motes', [0.44, 0.58], [0.15, 0.40]), 'accent': '#E8F0E0', 'particle': 'mote'},
}


def brew_profile(name):
    spec = BREW[name]
    sig, heat, circ = spec['infuse']
    out = {'schema_version': 1, 'infuse': {'signature': sig, 'heat_band': heat, 'circulation_band': circ}}
    if 'settle' in spec:
        s, h, c = spec['settle']
        out['settle'] = {'signature': s, 'heat_band': h, 'circulation_band': c, 'initial_trouble': 0.3}
    out['disturbances'] = ALL_DISTURBANCES
    out['inertia'] = 1.0
    out['presentation'] = {'accent': spec['accent'], 'particle': spec['particle']}
    return out


# ---- crafting ----
def shaped(pattern, key, result, count=1, conditions=None):
    out = {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': pattern, 'key': key,
           'result': {'item': result, 'count': count} if count != 1 else {'item': result}}
    if conditions:
        out['conditions'] = conditions
    return out


def shapeless(ingredients, result, count=1):
    return {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': ingredients,
            'result': {'item': result, 'count': count} if count != 1 else {'item': result}}


CRAFTING = {
    'apothecary_mortar': shaped(['S S', 'PSP'], {'S': {'tag': 'minecraft:stone_crafting_materials'}, 'P': {'tag': 'minecraft:planks'}},
                                f'{MOD}:apothecary_mortar'),
    'brewers_kettle': shaped(['CBC', 'RLR', 'OOO'], {'C': {'item': 'minecraft:copper_ingot'}, 'B': {'item': 'minecraft:brewing_stand'},
                                                     'R': {'item': 'minecraft:brick'}, 'L': {'item': 'minecraft:cauldron'},
                                                     'O': {'tag': 'forge:cobblestone'}}, f'{MOD}:brewers_kettle'),
    'alembic': shaped(['CGC', 'CUC', 'RIR'], {'C': {'item': 'minecraft:copper_ingot'}, 'G': {'item': 'minecraft:glass_bottle'},
                                              'U': {'item': 'minecraft:bucket'}, 'R': {'item': 'minecraft:brick'},
                                              'I': {'item': 'minecraft:iron_ingot'}}, f'{MOD}:alembic'),
    'wooden_brewing_paddle': shaped(['P', 'P', 'S'], {'P': {'tag': 'minecraft:planks'}, 'S': {'item': 'minecraft:stick'}},
                                    f'{MOD}:wooden_brewing_paddle'),
    'iron_brewing_paddle': shaped(['N', 'N', 'S'], {'N': {'item': 'minecraft:iron_nugget'}, 'S': {'item': 'minecraft:stick'}},
                                  f'{MOD}:iron_brewing_paddle',
                                  conditions=[{'type': f'{MOD}:smithing_fallback'}]),
    'brewers_notebook': shapeless([{'item': 'minecraft:book'}, {'item': 'minecraft:glass_bottle'}], f'{MOD}:brewers_notebook'),
    'batch_carrier': shaped([' P ', 'PBP', ' P '], {'P': {'tag': 'minecraft:planks'}, 'B': {'item': 'minecraft:glass_bottle'}},
                            f'{MOD}:batch_carrier'),
    'apothecary_rack': shaped(['S S', 'PPP', 'S S'], {'S': {'item': 'minecraft:stick'}, 'P': {'tag': 'minecraft:planks'}}, f'{MOD}:apothecary_rack'),
    'light_gray_glass_from_spent_salt': shapeless([{'item': f'{MOD}:spent_alchemical_salt'}, {'item': 'minecraft:glass'}], 'minecraft:light_gray_stained_glass'),
    'light_gray_pane_from_spent_salt': shapeless([{'item': f'{MOD}:spent_alchemical_salt'}, {'item': 'minecraft:glass_pane'}], 'minecraft:light_gray_stained_glass_pane'),
    'gray_dye_from_spent_salt': shapeless([{'item': f'{MOD}:spent_alchemical_salt'}, {'item': 'minecraft:ink_sac'}], 'minecraft:gray_dye', 2),
}

# ---- optional sister-mod recipes: each loads only with its mod present and its integration on (spec 17) ----
INTEGRATION_RECIPES = {
    # Smithing's own recipe format (inspected in immersive-smithing 1.1.0); 2 metal units = the fallback's 2 nuggets.
    'smithing/iron_brewing_paddle': {
        'conditions': [{'type': 'forge:mod_loaded', 'modid': 'immersive_smithing'}, {'type': f'{MOD}:smithing_active'}],
        'type': 'immersive_smithing:smithing', 'material': 'minecraft:iron', 'metal_units': 2,
        'auxiliary': [{'ingredient': {'item': 'minecraft:stick'}, 'count': 1}],
        'result': {'item': f'{MOD}:iron_brewing_paddle'},
        'forge_pattern': 'immersive_smithing:standard', 'anvil_pattern': 'immersive_smithing:shovel'},
    # A leather carrying strap: presentation only. forge:leather is the verified tag Tanning adds Binding Leather to.
    'tanning/strapped_batch_carrier': {
        'conditions': [{'type': 'forge:mod_loaded', 'modid': 'immersive_tanning'}, {'type': f'{MOD}:tanning_enabled'}],
        'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': [' L ', 'PBP', ' P '],
        'key': {'L': {'tag': 'forge:leather'}, 'P': {'tag': 'minecraft:planks'}, 'B': {'item': 'minecraft:glass_bottle'}},
        'result': {'item': f'{MOD}:batch_carrier', 'nbt': '{Strapped:1b}'}},
}

ALEMBIC_INPUT = {'items': ['minecraft:potion', 'minecraft:splash_potion', 'minecraft:lingering_potion'],
                 'potion_namespaces': ['minecraft'], 'requires_effects': True}
ALEMBIC = {
    'distillation/vanilla_potions': {'type': f'{MOD}:distillation', 'schema_version': 1, 'input': ALEMBIC_INPUT,
                                     'group_size': 3, 'yield': 2, 'fuel_charges': 1, 'quality_cap': 70,
                                     'container': {'item': 'minecraft:glass_bottle'}},
    'reclamation/vanilla_potions': {'type': f'{MOD}:reclamation', 'schema_version': 1,
                                    'input': dict(ALEMBIC_INPUT, requires_effects=False),
                                    'group_size': 3, 'result': {'item': f'{MOD}:spent_alchemical_salt', 'count': 1},
                                    'container': {'item': 'minecraft:glass_bottle'}, 'fuel_charges': 1, 'duration_ticks': 200},
}

# ---- optional potion mods (spec 18, "broader mod support"), all under compat/<modid>/ ----
# Plans for the brewing recipes each mod registers with Forge's brewing registry (read from its jar in libs/ at the
# version gradle.properties names, with its config defaults), profiles for its reagents, effect policies for the effects
# whose duration quality may scale, and Alembic recipes for its potions. Every file loads only with its mod
# (forge:mod_loaded), and every plan is still replayed through the live registry when the plan index is built: a changed
# config or another mod answering first for the same reagent leaves a plan unsupported in the report, never offered.
AWKWARD, MUNDANE = 'minecraft:awkward', 'minecraft:mundane'
FSE, REDSTONE, GLOWSTONE = 'minecraft:fermented_spider_eye', 'minecraft:redstone', 'minecraft:glowstone_dust'


def mod_loaded(*mods):
    tests = [{'type': 'forge:mod_loaded', 'modid': m} for m in mods]
    return tests if len(tests) == 1 else [{'type': 'forge:or', 'values': tests}]


def variants(base, reagent, potion, long_id=None, strong_id=None):
    """A potion brewed from base with reagent, then its redstone (long) and glowstone (strong) steps where the mod has them."""
    out = [(base, reagent, potion)]
    if long_id:
        out.append((potion, REDSTONE, long_id))
    if strong_id:
        out.append((potion, GLOWSTONE, strong_id))
    return out


def ap(base, reagent, name, long=True, strong=True):
    a = 'apothecary:'
    return variants(base if ':' in base else a + base, reagent, a + name, long and a + 'long_' + name, strong and a + 'strong_' + name)


def mp(reagent, name, long=True, strong=True):
    p = f'morepotions:{name}_potion'
    return variants(AWKWARD, reagent, p, long and p + '_longer', strong and p + '_amplified')


# Apothecary 1.5.2: net.gobies.apothecary.recipe.ABrewing (strict-input recipes, drinkable bottles only; the ingredients
# are its CommonConfig defaults). Mana Regeneration and Mana Exhaustion need Iron's Spellbooks and are left to contextual
# queries. Its Luck recipes are kept as registered: redstone and glowstone on Luck give long and strong Health Boost.
APOTHECARY_MIXES = (
    ap(AWKWARD, 'minecraft:iron_block', 'iron_skin') + ap(AWKWARD, 'minecraft:diamond_block', 'diamond_skin')
    + ap(AWKWARD, 'minecraft:lapis_block', 'magic_shield') + ap(AWKWARD, 'minecraft:skeleton_skull', 'archery')
    + ap(AWKWARD, 'minecraft:wither_skeleton_skull', 'quick_draw') + ap(AWKWARD, 'minecraft:crimson_fungus', 'wrath')
    + ap(AWKWARD, 'minecraft:dragon_head', 'flight', False, False) + ap(AWKWARD, 'minecraft:end_rod', 'reach')
    + ap(AWKWARD, 'minecraft:anvil', 'repairing') + ap(AWKWARD, 'minecraft:lapis_lazuli', 'magic_power')
    + ap(AWKWARD, 'minecraft:cactus', 'thorns') + ap(AWKWARD, 'minecraft:heart_of_the_sea', 'purification', False, False)
    + ap(AWKWARD, 'minecraft:raw_gold_block', 'spelunker') + ap(AWKWARD, 'minecraft:amethyst_cluster', 'extension', False)
    + ap(AWKWARD, 'minecraft:sniffer_egg', 'confusion', strong=False) + ap(AWKWARD, 'minecraft:magma_block', 'burning')
    + ap(AWKWARD, 'minecraft:copper_block', 'shocked') + ap(AWKWARD, 'minecraft:lightning_rod', 'lightning', False)
    + ap(AWKWARD, 'minecraft:trapped_chest', 'shuffling', False, False) + ap(AWKWARD, 'minecraft:warped_fungus', 'resistance')
    + ap(AWKWARD, 'minecraft:golden_apple', 'health_boost') + ap(AWKWARD, 'minecraft:wither_rose', 'wither')
    + ap('iron_skin', FSE, 'broken_armor') + ap('diamond_skin', FSE, 'ruptured_armor')
    + ap('archery', FSE, 'misfire', strong=False) + ap('quick_draw', FSE, 'lethargy', strong=False)
    + ap('wrath', FSE, 'frail') + ap('resistance', FSE, 'vulnerable') + ap('repairing', FSE, 'corrosion')
    + ap('magic_power', FSE, 'magic_drain') + ap('purification', FSE, 'corruption', False, False)
    + ap('extension', FSE, 'reversion', False)
    + [(AWKWARD, 'minecraft:emerald_block', 'minecraft:luck'), ('minecraft:luck', REDSTONE, 'apothecary:long_health_boost'),
       ('minecraft:luck', GLOWSTONE, 'apothecary:strong_health_boost')]
)
# Potions, Potions And More Potions 1.0.4: net.embersalvatore.MorePotions#commonSetup (matches the potion in any
# container and keeps the container).
MOREPOTIONS_MIXES = (mp('minecraft:iron_nugget', 'weight') + mp('minecraft:flint', 'archery') + mp('minecraft:feather', 'leap', False, False)
                     + mp('minecraft:allium', 'acrobatics') + mp('minecraft:iron_ingot', 'sinking')
                     + mp('minecraft:copper_ingot', 'lightning', False, False) + mp('minecraft:gold_nugget', 'martyr')
                     + mp('minecraft:magma_block', 'fire') + mp('minecraft:cobweb', 'climbing'))
# PotionsMaster 0.6.0: PotionsMaster.setupMod#registerPotions (Mundane plus a calcinated powder; the result is always a
# drinkable bottle). Aluminium Sight is registered as potionsmaster:aluminum_sight.
PM_ORES = ['coal', 'iron', 'redstone', 'lapis', 'gold', 'diamond', 'emerald', 'aluminium', 'copper', 'tin', 'nickel', 'uranium',
           'lead', 'silver', 'zinc', 'osmium', 'quartz', 'bismuth', 'crimsoniron', 'platinum', 'netherite', 'allthemodium',
           'vibranium', 'unobtainium']
POTIONSMASTER_MIXES = [(MUNDANE, f'potionsmaster:calcinated{ore}_powder', 'potionsmaster:' + ('aluminum' if ore == 'aluminium' else ore) + '_sight')
                       for ore in PM_ORES]
# RPG Potions 1.2.0 (its mods.toml says 1.1.3): brewing.PotionBrewingRegistry, Awkward plus one reagent. These are plain
# Forge recipes with a vanilla ingredient, so a stand also accepts any other drinkable potion for them; the Kettle offers
# what they declare (RegistryQuery: loose matches are refused).
RPG_MIXES = [(AWKWARD, f'minecraft:{reagent}', f'rpg_potions:{potion}') for reagent, potion in [
    ('charcoal', 'smoke'), ('carved_pumpkin', 'decoy'), ('tnt', 'knockback'), ('obsidian', 'phantom_armor'),
    ('slime_ball', 'paralysis'), ('arrow', 'projectile_rebound'), ('compass', 'heat_mark'), ('ink_sac', 'black_stain')]]

# mod: (bottles its recipes accept, mixes, brew profile per potion family)
COMPAT = {
    'apothecary': (['potion'], APOTHECARY_MIXES, {
        'iron_skin': 'dense_sediment', 'diamond_skin': 'dense_sediment', 'resistance': 'dense_sediment', 'wrath': 'dense_sediment',
        'repairing': 'dense_sediment', 'magic_shield': 'delicate_shimmer', 'magic_power': 'delicate_shimmer',
        'purification': 'delicate_shimmer', 'extension': 'delicate_shimmer', 'health_boost': 'delicate_shimmer',
        'archery': 'light_foam', 'quick_draw': 'light_foam', 'flight': 'floating_motes', 'reach': 'aligning_glints',
        'spelunker': 'aligning_glints', 'shocked': 'aligning_glints', 'lightning': 'aligning_glints', 'luck': 'aligning_glints',
        'burning': 'magma_streaks', 'thorns': 'dark_whorls', 'broken_armor': 'dark_whorls', 'ruptured_armor': 'dark_whorls',
        'misfire': 'dark_whorls', 'lethargy': 'dark_whorls', 'frail': 'dark_whorls', 'vulnerable': 'dark_whorls',
        'corrosion': 'dark_whorls', 'magic_drain': 'dark_whorls', 'corruption': 'dark_whorls', 'reversion': 'dark_whorls',
        'confusion': 'dark_whorls', 'shuffling': 'dark_whorls', 'wither': 'dark_whorls'}),
    'morepotions': (CONTAINERS, MOREPOTIONS_MIXES, {
        'weight': 'dense_sediment', 'archery': 'light_foam', 'leap': 'floating_motes', 'acrobatics': 'floating_motes',
        'sinking': 'suspended_bubbles', 'lightning': 'aligning_glints', 'martyr': 'delicate_shimmer', 'fire': 'magma_streaks',
        'climbing': 'light_foam'}),
    'potionsmaster': (['potion'], POTIONSMASTER_MIXES, {'sight': 'aligning_glints'}),
    'rpg_potions': (['potion'], RPG_MIXES, {
        'smoke': 'dark_whorls', 'decoy': 'floating_motes', 'knockback': 'dense_sediment', 'phantom_armor': 'delicate_shimmer',
        'paralysis': 'dark_whorls', 'projectile_rebound': 'light_foam', 'heat_mark': 'magma_streaks', 'black_stain': 'dark_whorls'}),
}


def compat_family(mod, potion):
    base = potion.split(':')[1].removeprefix('long_').removeprefix('strong_')
    base = base.removesuffix('_longer').removesuffix('_amplified').removesuffix('_potion')
    return COMPAT[mod][2]['sight' if base.endswith('_sight') else base]


# Preparation presets per mode, taken from the vanilla profiles above: (duration, endpoint, pressure limit, overwork, sensitivity).
PREP = {'crystalline': (240, [0.60, 0.78], 0.70, 0.86, 0.8), 'fibrous': (260, [0.55, 0.75], 0.60, 0.85, 0.8),
        'soft': (240, [0.55, 0.75], 0.50, 0.85, 1.0), 'brittle': (220, [0.58, 0.76], 0.65, 0.86, 0.8),
        'liquid': (260, [0.60, 0.80], 0.80, 0.90, 1.0)}
# reagent: (mods using it, preparation mode or None for a reagent used raw (the Mortar refuses it), addition cooling).
# Organic and crystal reagents are ground; metal blocks, tools, explosives and other crafted blocks go in raw.
COMPAT_REAGENTS = {
    'minecraft:iron_block': (('apothecary',), None, 0.10), 'minecraft:diamond_block': (('apothecary',), None, 0.10),
    'minecraft:lapis_block': (('apothecary',), None, 0.08), 'minecraft:skeleton_skull': (('apothecary',), 'brittle', 0.06),
    'minecraft:wither_skeleton_skull': (('apothecary',), 'brittle', 0.06), 'minecraft:crimson_fungus': (('apothecary',), 'soft', 0.07),
    'minecraft:dragon_head': (('apothecary',), 'brittle', 0.06), 'minecraft:end_rod': (('apothecary',), None, 0.05),
    'minecraft:anvil': (('apothecary',), None, 0.12), 'minecraft:lapis_lazuli': (('apothecary',), 'crystalline', 0.06),
    'minecraft:cactus': (('apothecary',), 'soft', 0.08), 'minecraft:heart_of_the_sea': (('apothecary',), 'brittle', 0.08),
    'minecraft:raw_gold_block': (('apothecary',), None, 0.10), 'minecraft:amethyst_cluster': (('apothecary',), 'crystalline', 0.07),
    'minecraft:sniffer_egg': (('apothecary',), None, 0.06), 'minecraft:copper_block': (('apothecary',), None, 0.10),
    'minecraft:lightning_rod': (('apothecary',), None, 0.06), 'minecraft:trapped_chest': (('apothecary',), None, 0.08),
    'minecraft:warped_fungus': (('apothecary',), 'soft', 0.07), 'minecraft:golden_apple': (('apothecary',), 'soft', 0.07),
    'minecraft:wither_rose': (('apothecary',), 'fibrous', 0.06), 'minecraft:emerald_block': (('apothecary',), None, 0.10),
    'minecraft:magma_block': (('apothecary', 'morepotions'), None, 0.02),
    'minecraft:iron_nugget': (('morepotions',), None, 0.04), 'minecraft:flint': (('morepotions',), 'brittle', 0.05),
    'minecraft:feather': (('morepotions',), 'fibrous', 0.04), 'minecraft:allium': (('morepotions',), 'fibrous', 0.06),
    'minecraft:iron_ingot': (('morepotions',), None, 0.06), 'minecraft:copper_ingot': (('morepotions',), None, 0.06),
    'minecraft:gold_nugget': (('morepotions',), None, 0.04), 'minecraft:cobweb': (('morepotions',), 'fibrous', 0.05),
    'minecraft:charcoal': (('rpg_potions',), 'brittle', 0.04), 'minecraft:carved_pumpkin': (('rpg_potions',), 'soft', 0.08),
    'minecraft:tnt': (('rpg_potions',), None, 0.05), 'minecraft:obsidian': (('rpg_potions',), None, 0.10),
    'minecraft:slime_ball': (('rpg_potions',), 'liquid', 0.06), 'minecraft:arrow': (('rpg_potions',), None, 0.03),
    'minecraft:compass': (('rpg_potions',), None, 0.04), 'minecraft:ink_sac': (('rpg_potions',), 'soft', 0.06),
}
for _ore in PM_ORES:
    COMPAT_REAGENTS[f'potionsmaster:calcinated{_ore}_powder'] = (('potionsmaster',), 'crystalline', 0.05)


def compat_reaction(item):
    """The reaction a reagent decides on its own (contextual queries): the family of the first potion it brews."""
    for mod, (_, mixes, _) in COMPAT.items():
        for src, reagent, dst in mixes:
            if reagent == item:
                return compat_family(mod, dst)
    raise KeyError(item)


def compat_profile_id(item):
    mods = COMPAT_REAGENTS[item][0]
    return f'{MOD}:compat/{mods[0] if len(mods) == 1 else "shared"}/{item.split(":")[1]}'


def compat_reagent_profile(item):
    mods, mode, cooling = COMPAT_REAGENTS[item]
    out = {'schema_version': 1, 'conditions': mod_loaded(*mods), 'priority': 100, 'selector': {'items': [item]}}
    sensitivity = 1.0
    if mode:
        duration, endpoint, limit, overwork, sensitivity = PREP[mode]
        out['preparation'] = {'mode': mode, 'duration_ticks': duration, 'endpoint': endpoint, 'pressure_limit': limit,
                              'overwork_threshold': overwork}
    out['reaction'] = {'profile': f'{MOD}:{compat_reaction(item)}', 'addition_cooling': cooling, 'foam_sensitivity': sensitivity}
    return out


def compat_role(reagent):
    if reagent in (REDSTONE, GLOWSTONE):
        return 'settle', 'modifier'
    if reagent == FSE:
        return 'infuse', 'corruption'
    return 'infuse', 'effect'


def compat_infusion(mod, src, reagent, dst, container):
    phase, rl = compat_role(reagent)
    step = {'reagent': {'item': reagent}, 'phase': phase, 'role': rl}
    if rl in ('effect', 'corruption'):
        if reagent in COMPAT_REAGENTS and COMPAT_REAGENTS[reagent][1]:
            step['preparation_profile'] = compat_profile_id(reagent)
        elif reagent.removeprefix('minecraft:') in PREPARABLE:
            step['preparation_profile'] = f'{MOD}:{reagent.removeprefix("minecraft:")}'
    return {'conditions': mod_loaded(mod), 'type': f'{MOD}:infusion', 'schema_version': 1, 'adapter': f'{MOD}:forge_registry',
            'input': {'item': f'minecraft:{container}', 'potion': src, 'match': 'canonical'}, 'steps': [step],
            'expected_output': {'item': f'minecraft:{container}', 'potion': dst},
            'batch': {'max_servings': 6, 'servings_per_reagent': 3}, 'brew_profile': f'{MOD}:{compat_family(mod, dst)}'}


# Effects whose finite duration quality may scale, per mod: each was read in the mod's source (attribute, per-tick or
# event effects that only need to be active) and is drunk at quality 0 and 100 in CompatGameTests. Not listed, so
# metadata-only: instant effects (they never scale), Apothecary's Mana Regeneration and Mana Exhaustion (Iron's
# Spellbooks attributes, untested here) and Potion Sickness (not a potion effect), More Potions' Leap (it removes itself
# one tick after its jump, so a duration means nothing) and RPG Potions' Smoke (its impact handler takes splash and
# lingering Smoke bottles over and spawns the cloud with the bottle's own duration, so only drinking would follow quality).
COMPAT_EFFECTS = {
    'apothecary': ['iron_skin', 'diamond_skin', 'magic_shield', 'archery', 'quick_draw', 'wrath', 'flight', 'reach', 'repairing',
                   'magic_power', 'thorns', 'spelunker', 'broken_armor', 'ruptured_armor', 'misfire', 'lethargy', 'frail',
                   'vulnerable', 'corrosion', 'magic_drain', 'confusion', 'burning', 'shocked'],
    'morepotions': ['weight', 'archery', 'sinking', 'acrobatics', 'martyr', 'climbing'],
    'potionsmaster': [f'{ore}potioneffect' for ore in PM_ORES],
    'rpg_potions': ['phantom_armor', 'paralysis', 'projectile_rebound', 'heat_mark', 'black_stain'],
}


def compat_effect_policy(mod):
    return {'schema_version': 1, 'conditions': mod_loaded(mod), 'priority': 0, 'effects': [f'{mod}:{e}' for e in COMPAT_EFFECTS[mod]],
            'quality_mode': 'duration', 'minimum_multiplier': 0.80, 'maximum_multiplier': 1.20, 'allow_amplifier_change': False,
            'allow_infinite_duration_change': False}


def compat_alembic(mod):
    inp = dict(ALEMBIC_INPUT, potion_namespaces=[mod])
    return {
        'distillation': {'conditions': mod_loaded(mod), **ALEMBIC['distillation/vanilla_potions'], 'input': inp},
        'reclamation': {'conditions': mod_loaded(mod), **ALEMBIC['reclamation/vanilla_potions'], 'input': dict(inp, requires_effects=False)},
    }


def compat_files():
    out = {}
    for mod, (containers, mixes, _) in COMPAT.items():
        for container in containers:
            for src, reagent, dst in mixes:
                path = DATA / f'recipes/compat/{mod}/{container}/{src.split(":")[1]}__{reagent.split(":")[1]}.json'
                assert path not in out, path
                out[path] = compat_infusion(mod, src, reagent, dst, container)
        for kind, recipe in compat_alembic(mod).items():
            out[DATA / f'recipes/compat/{mod}/{kind}.json'] = recipe
        out[DATA / f'{MOD}/effect_policies/compat/{mod}.json'] = compat_effect_policy(mod)
    for item in COMPAT_REAGENTS:
        out[DATA / f'{MOD}/reagent_profiles/{compat_profile_id(item).split(":")[1]}.json'] = compat_reagent_profile(item)
    return out


# ---- advancements: a root plus one per milestone, each awarded only from a committed server outcome ----
def advancement(name, icon, parent, frame='task', criteria=None, toast=True):
    display = {'icon': {'item': icon}, 'title': {'translate': f'advancements.{MOD}.{name}.title'},
               'description': {'translate': f'advancements.{MOD}.{name}.description'}, 'frame': frame,
               'show_toast': toast, 'announce_to_chat': toast, 'hidden': False}
    topic = {'root': 'start', 'first_brew': 'controls', 'prepare_kit': 'reagents', 'resume': 'recovery', 'fine_batch': 'quality', 'masterwork_batch': 'quality'}.get(name, 'stations')
    display['description']['extra'] = [{'text': ' — '}, {'translate': f'item.{MOD}.brewers_notebook'}, {'text': ': '}, {'translate': f'notebook.{MOD}.tab.{topic}'}]
    if parent is None:
        display['background'] = 'minecraft:textures/block/bricks.png'
    out = {'display': display}
    if parent is not None:
        out['parent'] = f'{MOD}:{parent}'
    out['criteria'] = criteria or {name: {'trigger': f'{MOD}:milestone', 'conditions': {'milestone': name}}}
    return out


ADVANCEMENTS = {
    'root': advancement('root', f'{MOD}:brewers_kettle', None, toast=False, criteria={
        'has_station': {'trigger': 'minecraft:inventory_changed', 'conditions': {'items': [
            {'items': [f'{MOD}:apothecary_mortar', f'{MOD}:brewers_kettle', f'{MOD}:alembic']}]}}}),
    'prepare_kit': advancement('prepare_kit', f'{MOD}:prepared_reagent_kit', 'root'),
    'first_brew': advancement('first_brew', 'minecraft:potion', 'root'),
    'resume': advancement('resume', f'{MOD}:recovery_batch', 'first_brew'),
    'fine_batch': advancement('fine_batch', f'{MOD}:wooden_brewing_paddle', 'first_brew'),
    'masterwork_batch': advancement('masterwork_batch', f'{MOD}:iron_brewing_paddle', 'fine_batch', frame='challenge'),
    'six_servings': advancement('six_servings', f'{MOD}:batch_carrier', 'first_brew', frame='goal'),
    'purify': advancement('purify', f'{MOD}:alembic', 'first_brew'),
    'reclaim': advancement('reclaim', f'{MOD}:spent_alchemical_salt', 'purify'),
}

# ---- tags ----
TAGS = {
    'items/brewing_paddles': [f'{MOD}:wooden_brewing_paddle', f'{MOD}:iron_brewing_paddle'],
    'items/burner_fuel': ['minecraft:blaze_powder'],
    # Shulker boxes need no entry: their items already refuse to fit inside container items.
    'items/carrier_excluded': [f'{MOD}:recovery_batch', f'{MOD}:batch_carrier', 'minecraft:bundle'],
    'items/carrier_storable': [],
}
VANILLA_TAGS = {
    'blocks/mineable/pickaxe': [f'{MOD}:apothecary_mortar', f'{MOD}:brewers_kettle', f'{MOD}:alembic'],
}

STATIONS = ['apothecary_mortar', 'brewers_kettle', 'alembic']


def loot(block):
    return {'type': 'minecraft:block', 'pools': [{
        'rolls': 1, 'bonus_rolls': 0,
        'entries': [{'type': 'minecraft:item', 'name': f'{MOD}:{block}', 'functions': [{
            'function': 'minecraft:copy_nbt', 'source': 'block_entity',
            'ops': [{'source': 'FuelCharges', 'target': 'BlockEntityTag.FuelCharges', 'op': 'replace'}]}]}],
        'conditions': [{'condition': 'minecraft:survives_explosion'}]}]}


# ---- models ----
def box(frm, to, tex, faces='all', uv=None):
    out = {'from': frm, 'to': to, 'faces': {}}
    names = ['north', 'south', 'east', 'west', 'up', 'down'] if faces == 'all' else faces
    for f in names:
        face = {'texture': tex}
        if f in ('north', 'south'):
            face['uv'] = [frm[0], 16 - to[1], to[0], 16 - frm[1]]
        elif f in ('east', 'west'):
            face['uv'] = [frm[2], 16 - to[1], to[2], 16 - frm[1]]
        else:
            face['uv'] = [frm[0], frm[2], to[0], to[2]]
        if f == 'down':
            face['cullface'] = 'down' if frm[1] == 0 else None
            if face['cullface'] is None:
                del face['cullface']
        out['faces'][f] = face
    return out


def kettle_model():
    t = {'particle': f'{MOD}:block/kettle_iron', 'iron': f'{MOD}:block/kettle_iron', 'copper': f'{MOD}:block/kettle_copper',
         'burner': f'{MOD}:block/kettle_burner', 'vent': f'{MOD}:block/kettle_vent', 'wood': f'{MOD}:block/kettle_rack'}
    els = [
        box([1, 0, 1], [15, 5, 15], '#burner', ['south', 'east', 'west', 'up', 'down']),
        box([1, 0, 1], [15, 5, 1.01], '#vent', ['north']),
        box([2, 5, 2], [14, 13, 3], '#iron'), box([2, 5, 11], [14, 13, 12], '#iron'),
        box([2, 5, 3], [3, 13, 11], '#iron'), box([13, 5, 3], [14, 13, 11], '#iron'),
        box([3, 5, 3], [13, 6, 11], '#iron', ['up']),
        box([1, 13, 1], [15, 14, 3], '#copper'), box([1, 13, 10], [15, 14, 12], '#copper'),
        box([1, 13, 3], [3, 14, 10], '#copper'), box([13, 13, 3], [15, 14, 10], '#copper'),
        box([1, 8, 12], [15, 9, 15.5], '#wood'), box([1, 8, 15], [15, 13, 16], '#wood'),
    ]
    return {'parent': 'block/block', 'render_type': 'minecraft:cutout', 'textures': t, 'elements': els}


def mortar_model():
    t = {'particle': f'{MOD}:block/mortar_stone', 'stone': f'{MOD}:block/mortar_stone', 'wood': f'{MOD}:block/mortar_wood',
         'pestle': f'{MOD}:block/mortar_pestle'}
    els = [
        box([3, 0, 3], [13, 3, 13], '#wood'),
        box([2, 3, 2], [14, 9, 4], '#stone'), box([2, 3, 12], [14, 9, 14], '#stone'),
        box([2, 3, 4], [4, 9, 12], '#stone'), box([12, 3, 4], [14, 9, 12], '#stone'),
        box([4, 3, 4], [12, 5, 12], '#stone', ['up']),
        box([9, 5, 5], [11, 15, 7], '#pestle'),
    ]
    return {'parent': 'block/block', 'render_type': 'minecraft:cutout', 'textures': t, 'elements': els}


def alembic_model():
    t = {'particle': f'{MOD}:block/alembic_copper', 'copper': f'{MOD}:block/alembic_copper', 'brick': f'{MOD}:block/alembic_brick',
         'glass': f'{MOD}:block/alembic_glass', 'iron': f'{MOD}:block/kettle_iron'}
    els = [
        box([1, 0, 1], [15, 3, 15], '#brick'),
        box([2, 3, 4], [10, 10, 12], '#copper'),
        box([4, 10, 6], [8, 15, 10], '#copper'),
        box([8, 13, 7], [12, 14, 9], '#copper'),
        box([11, 9, 7], [12, 13, 9], '#copper'),
        box([10, 3, 9], [15, 9, 14], '#glass'),
        box([3, 3, 1], [5, 6, 3], '#iron'),
    ]
    return {'parent': 'block/block', 'render_type': 'minecraft:translucent', 'textures': t, 'elements': els}


def rack_model():
    return {'parent': 'block/block', 'textures': {'particle': 'minecraft:block/spruce_planks', 'wood': 'minecraft:block/spruce_planks', 'edge': 'minecraft:block/dark_oak_planks'}, 'elements': [{'from': [0, 0, 2], 'to': [16, 2, 8], 'faces': {'north': {'texture': '#edge'}, 'south': {'texture': '#wood'}, 'east': {'texture': '#edge'}, 'west': {'texture': '#edge'}, 'up': {'texture': '#wood'}, 'down': {'texture': '#wood'}}}, {'from': [0, 7, 2], 'to': [16, 9, 8], 'faces': {'north': {'texture': '#edge'}, 'south': {'texture': '#wood'}, 'east': {'texture': '#edge'}, 'west': {'texture': '#edge'}, 'up': {'texture': '#wood'}, 'down': {'texture': '#wood'}}}, {'from': [0, 14, 2], 'to': [16, 16, 8], 'faces': {'north': {'texture': '#edge'}, 'south': {'texture': '#wood'}, 'east': {'texture': '#edge'}, 'west': {'texture': '#edge'}, 'up': {'texture': '#wood'}, 'down': {'texture': '#wood'}}}, {'from': [0, 0, 7], 'to': [1, 16, 9], 'faces': {'north': {'texture': '#edge'}, 'south': {'texture': '#wood'}, 'east': {'texture': '#wood'}, 'west': {'texture': '#edge'}, 'up': {'texture': '#wood'}, 'down': {'texture': '#wood'}}}, {'from': [15, 0, 7], 'to': [16, 16, 9], 'faces': {'north': {'texture': '#edge'}, 'south': {'texture': '#wood'}, 'east': {'texture': '#edge'}, 'west': {'texture': '#wood'}, 'up': {'texture': '#wood'}, 'down': {'texture': '#wood'}}}, {'from': [5.2, 2, 6.8], 'to': [5.7, 14, 8], 'faces': {'north': {'texture': '#edge'}, 'south': {'texture': '#wood'}, 'east': {'texture': '#edge'}, 'west': {'texture': '#edge'}}}, {'from': [10.3, 2, 6.8], 'to': [10.8, 14, 8], 'faces': {'north': {'texture': '#edge'}, 'south': {'texture': '#wood'}, 'east': {'texture': '#edge'}, 'west': {'texture': '#edge'}}}]}


def blockstate(block):
    variants = {}
    for facing, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
        for lit in ('false', 'true'):
            v = {'model': f'{MOD}:block/{block}'}
            if y:
                v['y'] = y
            variants[f'facing={facing},lit={lit}'] = v
    return {'variants': variants}


ITEM_MODELS = {
    'wooden_brewing_paddle': {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'{MOD}:item/wooden_brewing_paddle'}},
    'iron_brewing_paddle': {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'{MOD}:item/iron_brewing_paddle'}},
}
for name in ('prepared_reagent_kit', 'recovery_batch', 'batch_carrier', 'batch_carrier_strapped', 'brewers_notebook', 'spent_alchemical_salt'):
    ITEM_MODELS[name] = {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{MOD}:item/{name}'}}
ITEM_MODELS['batch_carrier']['overrides'] = [{'predicate': {f'{MOD}:strapped': 1}, 'model': f'{MOD}:item/batch_carrier_strapped'}]
for block in STATIONS:
    ITEM_MODELS[block] = {'parent': f'{MOD}:block/{block}'}


def files():
    out = {}
    for c in CONTAINERS:
        for src, reagent, dst in MIXES:
            out[DATA / f'recipes/infusion/{c}/{src}__{reagent}.json'] = infusion(src, reagent, dst, c)
    out[DATA / 'recipes/infusion/container/splash.json'] = {
        'type': f'{MOD}:infusion', 'schema_version': 1, 'adapter': f'{MOD}:forge_registry',
        'input': {'item': 'minecraft:potion', 'match': 'item'},
        'steps': [{'reagent': {'item': 'minecraft:gunpowder'}, 'phase': 'settle', 'role': 'container'}],
        'expected_output': {'item': 'minecraft:splash_potion', 'potion': 'same'},
        'batch': {'max_servings': 6, 'servings_per_reagent': 3}}
    out[DATA / 'recipes/infusion/container/lingering.json'] = {
        'type': f'{MOD}:infusion', 'schema_version': 1, 'adapter': f'{MOD}:forge_registry',
        'input': {'item': 'minecraft:splash_potion', 'match': 'item'},
        'steps': [{'reagent': {'item': 'minecraft:dragon_breath'}, 'phase': 'settle', 'role': 'container'}],
        'expected_output': {'item': 'minecraft:lingering_potion', 'potion': 'same'},
        'batch': {'max_servings': 6, 'servings_per_reagent': 3}}
    for item in list(PREPARABLE) + list(UNPREPARED):
        out[DATA / f'{MOD}/reagent_profiles/{item}.json'] = reagent_profile(item)
    for name in BREW:
        out[DATA / f'{MOD}/brew_profiles/{name}.json'] = brew_profile(name)
    for name, recipe in ALEMBIC.items():
        out[DATA / f'recipes/{name}.json'] = recipe
    for name, recipe in CRAFTING.items():
        out[DATA / f'recipes/{name}.json'] = recipe
    for name, recipe in INTEGRATION_RECIPES.items():
        out[DATA / f'recipes/{name}.json'] = recipe
    out.update(compat_files())
    for name, adv in ADVANCEMENTS.items():
        out[DATA / f'advancements/{name}.json'] = adv
    for path, values in TAGS.items():
        out[DATA / f'tags/{path}.json'] = {'replace': False, 'values': values}
    for path, values in VANILLA_TAGS.items():
        out[RES / f'data/minecraft/tags/{path}.json'] = {'replace': False, 'values': values}
    for block in STATIONS:
        out[DATA / f'loot_tables/blocks/{block}.json'] = loot(block)
        out[ASSETS / f'blockstates/{block}.json'] = blockstate(block)
    out[ASSETS / 'models/block/brewers_kettle.json'] = kettle_model()
    out[ASSETS / 'models/block/apothecary_mortar.json'] = mortar_model()
    out[ASSETS / 'models/block/alembic.json'] = alembic_model()
    out[ASSETS / 'models/block/apothecary_rack.json'] = rack_model()
    out[ASSETS / 'models/item/apothecary_rack.json'] = {'parent': f'{MOD}:block/apothecary_rack'}
    rack_loot = loot('apothecary_rack')
    rack_loot['pools'][0]['entries'][0].pop('functions', None)
    out[DATA / 'loot_tables/blocks/apothecary_rack.json'] = rack_loot
    out[ASSETS / 'blockstates/apothecary_rack.json'] = {'variants': {f'facing={facing}': {'model': f'{MOD}:block/apothecary_rack', **({'y': angle} if angle else {})} for facing, angle in [('north', 0), ('east', 90), ('south', 180), ('west', 270)]}}
    for name, model in ITEM_MODELS.items():
        out[ASSETS / f'models/item/{name}.json'] = model
    return out


def render(obj):
    return json.dumps(obj, indent=2) + '\n'


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--write', action='store_true')
    group.add_argument('--check', action='store_true')
    args = parser.parse_args()
    generated = files()
    differing = []
    for path, obj in generated.items():
        text = render(obj)
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        elif not path.exists() or path.read_text() != text:
            differing.append(path.relative_to(ROOT))
    if args.write:
        print(f'wrote {len(generated)} files')
        return 0
    for p in differing:
        print(f'differs {p}')
    print(f'{len(generated) - len(differing)}/{len(generated)} generated files up to date')
    return 1 if differing else 0


if __name__ == '__main__':
    sys.exit(main())
