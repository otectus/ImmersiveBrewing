package com.otectus.immersivebrewing.client.sound;

import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

/**
 * Screen-side apparatus sounds (spec 24.4): simmer and rolling boil loops that follow heat, a paddle swirl whose pitch
 * follows circulation, and short cue sounds. Every cue is redundant with a written and visual cue. Loops stop when the
 * screen closes, the session ends or the player leaves the level.
 */
public final class BrewingSounds {
    @Nullable
    private static Loop simmer;
    @Nullable
    private static Loop boil;
    @Nullable
    private static Loop swirl;
    private static int grindCooldown;
    private static int dripCooldown;

    private static float apparatus() {
        return (float) (double) ClientConfig.get(ClientConfig.APPARATUS_VOLUME);
    }

    private static float cueVolume() {
        return (float) (double) ClientConfig.get(ClientConfig.CUE_VOLUME);
    }

    /** Kettle loops for the current predicted state. */
    public static void kettle(double heat, double circulation, double foam, boolean countingIn) {
        float a = apparatus();
        simmer = ensure(simmer, ModSounds.KETTLE_SIMMER.get());
        boil = ensure(boil, ModSounds.KETTLE_BOIL.get());
        swirl = ensure(swirl, ModSounds.PADDLE_SWIRL.get());
        simmer.target((float) (a * Math.max(0, Math.min(1, (heat - 0.3) * 3)) * 0.6), 1.0F);
        boil.target((float) (a * Math.max(0, Math.min(1, (heat - 0.66) * 4)) * 0.7), 1.0F);
        double v = Math.abs(circulation);
        swirl.target((float) (a * Math.min(1, v * 1.4) * 0.5), (float) (0.8 + v * 0.6));
        if (foam > 0.5 && grindCooldown-- <= 0) {
            play(ModSounds.FOAM_HISS.get(), a * 0.4F, 1.0F);
            grindCooldown = 30;
        }
    }

    /** Fades the kettle loops out while the game is paused; the next state brings them back. */
    public static void quiet() {
        if (simmer != null) simmer.target(0F, 1.0F);
        if (boil != null) boil.target(0F, 1.0F);
        if (swirl != null) swirl.target(0F, 1.0F);
    }

    /** Mortar: a crunch, grind or mash per stroke of real work, scaled by how much was processed. */
    public static void mortar(double work, int texture, boolean lifted) {
        float a = apparatus();
        if (lifted) {
            play(ModSounds.PESTLE_LIFT.get(), a * 0.6F, 1.0F);
            return;
        }
        if (work <= 0.0005 || grindCooldown-- > 0) return;
        grindCooldown = 4;
        SoundEvent event = texture <= 1 ? ModSounds.MORTAR_CRUSH.get() : texture == 2 ? ModSounds.MORTAR_GRIND.get() : ModSounds.MORTAR_MASH.get();
        play(event, (float) Math.min(1.0, work * 60) * a * 0.7F, 0.9F + texture * 0.08F);
    }

    /** Alembic: drips follow the condensation rate. */
    public static void alembic(double condensed) {
        if (condensed <= 0 || dripCooldown-- > 0) return;
        dripCooldown = (int) Math.max(3, 20 - condensed * 4000);
        play(ModSounds.CONDENSATE_DROP.get(), apparatus() * 0.5F, 0.9F + (float) Math.random() * 0.2F);
    }

    public static void valve() {
        play(ModSounds.VALVE_TURN.get(), apparatus() * 0.6F, 1.0F);
    }

    public static void receiver() {
        play(ModSounds.RECEIVER_SWITCH.get(), apparatus() * 0.7F, 1.0F);
    }

    public static void cue(boolean warning) {
        play(warning ? ModSounds.CUE_WARNING.get() : ModSounds.CUE_READY.get(), cueVolume() * 0.8F, 1.0F);
    }

    public static void complete() {
        play(ModSounds.BREW_COMPLETE.get(), cueVolume() * 0.8F, 1.0F);
    }

    private static void play(SoundEvent event, float volume, float pitch) {
        if (volume <= 0.01F) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }

    public static void stop() {
        if (simmer != null) simmer.finish();
        if (boil != null) boil.finish();
        if (swirl != null) swirl.finish();
        simmer = boil = swirl = null;
    }

    private static Loop ensure(@Nullable Loop loop, SoundEvent event) {
        if (loop != null && !loop.isStopped()) return loop;
        Loop fresh = new Loop(event);
        Minecraft.getInstance().getSoundManager().play(fresh);
        return fresh;
    }

    /** A UI-relative loop easing toward a target volume and pitch. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private float targetVolume;
        private float targetPitch = 1.0F;

        Loop(SoundEvent event) {
            super(event, SoundSource.BLOCKS, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        void target(float volume, float pitch) {
            this.targetVolume = volume;
            this.targetPitch = pitch;
        }

        void finish() {
            stop();
        }

        @Override
        public void tick() {
            volume += (targetVolume - volume) * 0.2F;
            pitch += (targetPitch - pitch) * 0.2F;
            if (volume < 0.0001F) volume = 0.0001F;
        }
    }

    private BrewingSounds() {}
}
