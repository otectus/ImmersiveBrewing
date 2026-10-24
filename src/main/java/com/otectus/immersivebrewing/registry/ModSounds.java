package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The original sound palette (spec 24.4), synthesised by {@code art/brewing_audio.py}. Every sound has a subtitle; every
 * cue sound is redundant with a visual and written cue.
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ImmersiveBrewing.MOD_ID);

    public static final RegistryObject<SoundEvent> MORTAR_CRUSH = register("mortar.crush");
    public static final RegistryObject<SoundEvent> MORTAR_GRIND = register("mortar.grind");
    public static final RegistryObject<SoundEvent> MORTAR_MASH = register("mortar.mash");
    public static final RegistryObject<SoundEvent> PESTLE_LIFT = register("mortar.lift");
    public static final RegistryObject<SoundEvent> KETTLE_SIMMER = register("kettle.simmer");
    public static final RegistryObject<SoundEvent> KETTLE_BOIL = register("kettle.boil");
    public static final RegistryObject<SoundEvent> FOAM_HISS = register("kettle.foam");
    public static final RegistryObject<SoundEvent> PADDLE_SWIRL = register("kettle.swirl");
    public static final RegistryObject<SoundEvent> INGREDIENT_ADD = register("kettle.add");
    public static final RegistryObject<SoundEvent> CONDENSATE_DROP = register("alembic.drop");
    public static final RegistryObject<SoundEvent> VALVE_TURN = register("alembic.valve");
    public static final RegistryObject<SoundEvent> RECEIVER_SWITCH = register("alembic.receiver");
    public static final RegistryObject<SoundEvent> BOTTLE_CLINK = register("bottle.clink");
    public static final RegistryObject<SoundEvent> BREW_COMPLETE = register("brew.complete");
    public static final RegistryObject<SoundEvent> CUE_READY = register("cue.ready");
    public static final RegistryObject<SoundEvent> CUE_WARNING = register("cue.warning");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(ImmersiveBrewing.id(name)));
    }

    private ModSounds() {}
}
