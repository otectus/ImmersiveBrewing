package com.otectus.immersivebrewing.client.sound;

import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.presentation.StationAnchors;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.network.packet.StationEffectEvent;
import com.otectus.immersivebrewing.registry.ModSounds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

import java.util.*;

/** One owner for productive apparatus audio. Entries contain values only, never retained world objects. */
public final class StationSoundManager {
    private record Key(ResourceLocation dimension, long pos, UUID incarnation, UUID job) {}
    private static final Map<Key, Entry> ENTRIES = new HashMap<>();
    private static final Map<UUID, Long> SEQUENCES = new LinkedHashMap<>();
    private static final Map<UUID, EnumMap<StationEffectEvent.Kind, Long>> ACKS = new HashMap<>();
    private static ResourceLocation dimension;
    private static Object worldIdentity;
    private static long nextSelection;
    private static final Set<Key> SELECTED = new HashSet<>();
    private static final class Entry {
        StationVisual visual;
        Vec3 anchor;
        long seen, fresh, revision = -1, contactStep = -1, nextDrip;
        Loop body, paddle;
        void stop() { if (body != null) body.finish(); if (paddle != null) paddle.finish(); }
    }

    public static void observe(StationBlockEntity station) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || station.isRemoved()) return;
        ensureWorld();
        var v = station.visual();
        if (v.incarnation().equals(StationVisual.NONE)) return;
        Key key = new Key(dimension, station.getBlockPos().asLong(), v.incarnation(), v.jobId());
        long now = Util.getMillis();
        Entry e = ENTRIES.computeIfAbsent(key, ignored -> new Entry());
        e.seen = now;
        if (e.revision != v.revision()) { e.fresh = now; e.revision = v.revision(); }
        e.visual = v;
        var local = v.operation() == Operation.BREW || v.operation() == Operation.FINISH ? StationAnchors.KETTLE_RIM
                : v.operation() == Operation.PREPARE ? StationAnchors.MORTAR_CENTER
                : v.alembic().collecting() ? StationAnchors.ALEMBIC_RECEIVER : StationAnchors.ALEMBIC_WASTE;
        e.anchor = StationAnchors.world(station.getBlockPos(), station.getBlockState().getValue(StationBlock.FACING), local);
    }

    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { clear(); return; }
        ensureWorld();
        long now = Util.getMillis();
        ENTRIES.entrySet().removeIf(entry -> {
            if (now - entry.getValue().seen <= 1000 && mc.level.hasChunkAt(BlockPos.of(entry.getKey().pos()))) return false;
            entry.getValue().stop(); SELECTED.remove(entry.getKey()); ACKS.remove(entry.getKey().incarnation()); return true;
        });
        if (now >= nextSelection) {
            nextSelection = now + 250;
            List<Key> candidates = ENTRIES.entrySet().stream().filter(e -> eligible(e.getValue(), now))
                    .filter(e -> mc.player.distanceToSqr(e.getValue().anchor) <= 24 * 24)
                    .sorted(Comparator.comparingDouble(e -> priority(e.getKey(), e.getValue())))
                    .limit(4).map(Map.Entry::getKey).toList();
            SELECTED.clear(); SELECTED.addAll(candidates);
        }
        int secondaryVoices = 0;
        for (var item : ENTRIES.entrySet()) {
            Entry e = item.getValue(); var v = e.visual;
            boolean audible = !mc.isPaused() && SELECTED.contains(item.getKey()) && eligible(e, now);
            float volume = audible ? (float) (double) ClientConfig.get(ClientConfig.APPARATUS_VOLUME) : 0;
            if (volume <= 0) { if (e.body != null) e.body.target(0, 1); if (e.paddle != null) e.paddle.target(0, 1); continue; }
            if (v.operation() == Operation.BREW || v.operation() == Operation.FINISH) {
                e.body = ensure(e.body, ModSounds.KETTLE_SIMMER.get(), e.anchor);
                if (e.body != null) e.body.target(volume * Math.max(0, v.heat() - .3F) * .6F, .8F + v.heat() * .4F);
                if (Math.abs(v.circulation()) > .03 && secondaryVoices++ < 2) {
                    e.paddle = ensure(e.paddle, ModSounds.PADDLE_SWIRL.get(), e.anchor);
                    if (e.paddle != null) e.paddle.target(volume * Math.abs(v.circulation()) * .3F, .8F + Math.abs(v.circulation()) * .3F);
                } else if (e.paddle != null) e.paddle.target(0, 1);
            } else if (v.operation() == Operation.PREPARE && v.mortar().workRate() > .0005 && e.contactStep != v.simulationStep()) {
                e.contactStep = v.simulationStep();
                SoundEvent sound = switch (v.mortar().mode()) { case 2, 4 -> ModSounds.MORTAR_MASH.get(); case 1 -> ModSounds.MORTAR_GRIND.get(); default -> ModSounds.MORTAR_CRUSH.get(); };
                play(e.anchor, sound, volume * .4F, .94F + (v.simulationStep() % 3) * .05F);
            } else if (v.operation() == Operation.PURIFY && v.alembic().condensedRate() > 0 && now >= e.nextDrip) {
                e.nextDrip = now + Math.max(250, (long) (1000 - v.alembic().condensedRate() * 200000));
                play(e.anchor, ModSounds.CONDENSATE_DROP.get(), volume * .35F, .95F + (v.simulationStep() % 3) * .05F);
            }
        }
        // Retiring voices finish their short fade before replacements are created: hard cap of six loops.
    }
    private static boolean eligible(Entry e, long now) {
        return e.visual != null && e.visual.activity() == StationVisual.Activity.WORKING && now - e.fresh <= 1000;
    }
    private static double priority(Key key, Entry e) {
        var mc = Minecraft.getInstance(); var session = ClientSession.active();
        return mc.player.distanceToSqr(e.anchor) - (SELECTED.contains(key) ? 9 : 0)
                - (session != null && !session.practice && session.pos.asLong() == key.pos() ? 10000 : 0);
    }
    private static void ensureWorld() {
        var level = Minecraft.getInstance().level;
        if (worldIdentity != level) { clear(); worldIdentity = level; dimension = level.dimension().location(); }
    }

    public static void accepted(StationEffectEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.hasChunkAt(event.pos())) return;
        ensureWorld();
        if (!(mc.level.getBlockEntity(event.pos()) instanceof StationBlockEntity station)) return;
        var visual = station.visual();
        if (!visual.incarnation().equals(event.incarnation()) || event.sequence() <= SEQUENCES.getOrDefault(event.incarnation(), -1L)) return;
        if (Math.abs(mc.level.getGameTime() - event.sampleTime()) > 40) return;
        if (!visual.jobId().equals(event.jobId()) && event.kind() != StationEffectEvent.Kind.COMPLETE
                && event.kind() != StationEffectEvent.Kind.LOAD && event.kind() != StationEffectEvent.Kind.COLLECT
                && event.kind() != StationEffectEvent.Kind.WATER && event.kind() != StationEffectEvent.Kind.GRAB
                && event.kind() != StationEffectEvent.Kind.DROP) return;
        SEQUENCES.put(event.incarnation(), event.sequence());
        while (SEQUENCES.size() > 256) { UUID oldest = SEQUENCES.keySet().iterator().next(); SEQUENCES.remove(oldest); ACKS.remove(oldest); }
        ACKS.computeIfAbsent(event.incarnation(), ignored -> new EnumMap<>(StationEffectEvent.Kind.class)).put(event.kind(), Util.getMillis());
        var session = ClientSession.active();
        if (event.kind() == StationEffectEvent.Kind.ADDITION && session != null && !session.practice && session.pos.equals(event.pos())
                && session.display.hasUUID("job") && event.jobId().equals(session.display.getUUID("job"))) session.acceptedAddition(event.index());
        if (mc.player.distanceToSqr(event.pos().getCenter()) > 24 * 24) return;
        SoundEvent sound = switch (event.kind()) {
            case ADDITION -> ModSounds.INGREDIENT_ADD.get();
            case FOLD -> ModSounds.PESTLE_LIFT.get();
            case RECEIVER -> ModSounds.RECEIVER_SWITCH.get();
            case VALVE -> ModSounds.VALVE_TURN.get();
            case COMPLETE -> ModSounds.BREW_COMPLETE.get();
            case SEAL, RESUME, GRAB, DROP -> ModSounds.PESTLE_LIFT.get();
            case PRESS -> ModSounds.MORTAR_CRUSH.get();
            case POUR -> ModSounds.KETTLE_POUR.get();
            case FILL -> ModSounds.BOTTLE_FILL.get();
            default -> ModSounds.BOTTLE_CLINK.get();
        };
        float slider = (float) (double) ClientConfig.get(event.kind() == StationEffectEvent.Kind.COMPLETE ? ClientConfig.CUE_VOLUME : ClientConfig.APPARATUS_VOLUME);
        Vec3 anchor = StationAnchors.world(event.pos(), station.getBlockState().getValue(StationBlock.FACING),
                event.kind() == StationEffectEvent.Kind.ADDITION || event.kind() == StationEffectEvent.Kind.WATER
                        || event.kind() == StationEffectEvent.Kind.POUR ? StationAnchors.KETTLE_ENTRY
                        : event.kind() == StationEffectEvent.Kind.RECEIVER ? StationAnchors.ALEMBIC_CONDENSER : new Vec3(.5, .6, .5));
        play(anchor, sound, slider * .5F, .97F + (event.sequence() % 3) * .03F);
    }
    public static long acknowledgementAge(StationBlockEntity station, StationEffectEvent.Kind kind) {
        var events = ACKS.get(station.visual().incarnation());
        return events == null || !events.containsKey(kind) ? Long.MAX_VALUE : Util.getMillis() - events.get(kind);
    }
    private static void play(Vec3 anchor, SoundEvent sound, float volume, float pitch) {
        var level = Minecraft.getInstance().level;
        if (level != null && volume > 0) level.playLocalSound(anchor.x, anchor.y, anchor.z, sound, SoundSource.BLOCKS, volume, pitch, false);
    }
    private static Loop ensure(Loop old, SoundEvent sound, Vec3 anchor) {
        if (old != null && !old.isStopped()) return old;
        if (Loop.live >= 6) return null;
        Loop loop = new Loop(sound, anchor); Minecraft.getInstance().getSoundManager().play(loop); return loop;
    }
    public static void remove(BlockPos pos) {
        ENTRIES.entrySet().removeIf(entry -> {
            if (entry.getKey().pos() != pos.asLong()) return false;
            entry.getValue().stop(); SELECTED.remove(entry.getKey()); ACKS.remove(entry.getKey().incarnation());
            return true;
        });
    }
    public static int trackedStations() { return ENTRIES.size(); }
    public static int loopVoices() { return Loop.live; }
    public static void clear() {
        ENTRIES.values().forEach(Entry::stop); ENTRIES.clear(); SEQUENCES.clear(); ACKS.clear(); SELECTED.clear();
        dimension = null; worldIdentity = null; nextSelection = 0;
    }
    private static final class Loop extends AbstractTickableSoundInstance {
        static int live;
        float targetVolume, targetPitch = 1;
        boolean released;
        Loop(SoundEvent sound, Vec3 anchor) {
            super(sound, SoundSource.BLOCKS, RandomSource.create());
            looping = true; delay = 0; volume = .0001F; relative = false; attenuation = SoundInstance.Attenuation.LINEAR;
            x = anchor.x; y = anchor.y; z = anchor.z; live++;
        }
        void target(float volume, float pitch) { targetVolume = volume; targetPitch = pitch; }
        void finish() { if (!released) { released = true; live = Math.max(0, live - 1); } stop(); }
        public void tick() {
            volume += (targetVolume - volume) * .4F; pitch += (targetPitch - pitch) * .3F;
            if (targetVolume <= 0 && volume < .002F) finish();
        }
    }
    private StationSoundManager() {}
}
