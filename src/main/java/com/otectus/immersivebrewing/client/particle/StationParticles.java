package com.otectus.immersivebrewing.client.particle;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationView;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.client.presentation.StationAnchors;
import com.otectus.immersivebrewing.block.StationBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;

/**
 * Ambient world effects that follow the synced station view (spec 4, 24): steam and bubbles over a working Kettle,
 * spatter when its reaction is troubled, condensate at a working Alembic, fragments at a working Mortar, and a faint
 * warning mote over a batch held for recovery. Bounded to at most one particle per station per tick, only within 24
 * blocks of the player, scaled by {@code particleDensity} (and cut back under reduced motion); vanilla's particle option
 * applies on top.
 */
public final class StationParticles {
    private static final double RANGE_SQ = 24 * 24;
    private static final int NORMAL_GLOBAL_PER_SECOND = 128, NORMAL_STATION_PER_SECOND = 4;
    private static final int REDUCED_GLOBAL_PER_SECOND = 32, REDUCED_STATION_PER_SECOND = 1;
    private static final Map<Long, Integer> stationSecond = new HashMap<>();
    private static long budgetSecond = Long.MIN_VALUE;
    private static int emittedThisSecond;
    private record Freshness(java.util.UUID incarnation, long revision, long received) {}
    private static final Map<Long, Freshness> FRESH = new HashMap<>();
    private static final ArrayDeque<Particle> LIVE = new ArrayDeque<>();
    private static final int LIVE_NORMAL = 256, LIVE_REDUCED = 64;
    private static final DustParticleOptions HELD = new DustParticleOptions(new Vector3f(0.75F, 0.25F, 0.2F), 0.7F);

    public static void tick(StationBlockEntity station) {
        Level level = station.getLevel();
        Minecraft mc = Minecraft.getInstance();
        if (level == null || mc.player == null || mc.isPaused()) return;
        BlockPos pos = station.getBlockPos();
        if (mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > RANGE_SQ) return;
        double distance = Math.sqrt(mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5));
        if (mc.options.particles().get() == ParticleStatus.MINIMAL) return;
        if (mc.options.particles().get() == ParticleStatus.DECREASED && level.random.nextBoolean()) return;
        var policy = PresentationPolicy.current();
        double rate = policy.particleDensity() * policy.decorativeMotion() * (distance > 12 ? Math.max(0, (24 - distance) / 12) : 1);
        if (rate <= 0) return;
        RandomSource random = level.random;
        long second = level.getGameTime() / 20;
        if (budgetSecond != second) { budgetSecond = second; emittedThisSecond = 0; stationSecond.clear(); }
        LIVE.removeIf(p -> !p.isAlive());
        if (LIVE.size() >= (policy.reducedMotion() ? LIVE_REDUCED : LIVE_NORMAL)) return;
        int globalLimit = policy.reducedMotion() ? REDUCED_GLOBAL_PER_SECOND : NORMAL_GLOBAL_PER_SECOND;
        int stationLimit = policy.reducedMotion() ? REDUCED_STATION_PER_SECOND : NORMAL_STATION_PER_SECOND;
        if (emittedThisSecond >= globalLimit || stationSecond.getOrDefault(pos.asLong(), 0) >= stationLimit) return;
        StationView view = station.view();
        StationVisual visual = view.visual();
        if (view.state() == JobState.RECOVERY) {
            if (random.nextFloat() < 0.05 * rate) emit(level, HELD, pos, 0.5, 1.15, 0.5, 0.0, 0.01, random);
            return;
        }
        Freshness old = FRESH.get(pos.asLong());
        long now = net.minecraft.Util.getMillis();
        if (old == null || !old.incarnation().equals(visual.incarnation()) || old.revision() != visual.revision()) {
            old = new Freshness(visual.incarnation(), visual.revision(), now);
            FRESH.put(pos.asLong(), old);
        }
        if (!visual.active() || now - old.received() > 1000) return;
        if (station instanceof BrewersKettleBlockEntity) {
            if (visual.trouble() > 0.6F && random.nextFloat() < 0.25 * rate) {
                emit(level, ParticleTypes.SPLASH, pos, world(station, StationAnchors.KETTLE_RIM), 0.25, 0.0, random);
            } else if (random.nextFloat() < (0.08 + 0.22 * visual.heat()) * rate) {
                ParticleOptions particle=switch(visual.particle()) {
                    case "bubble", "foam" -> ParticleTypes.BUBBLE_POP;
                    case "glint" -> ParticleTypes.END_ROD;
                    case "mote" -> new DustParticleOptions(new Vector3f((visual.accent()>>16&255)/255F,(visual.accent()>>8&255)/255F,(visual.accent()&255)/255F),.65F);
                    default -> visual.heat()>0.55F?ParticleTypes.CLOUD:ParticleTypes.BUBBLE_POP;
                };
                emit(level, particle, pos, world(station, StationAnchors.KETTLE_RIM), visual.particle().equals("foam")?.4:.25, .015, random);
            }
        } else if (station instanceof AlembicBlockEntity) {
            if (view.operation() == Operation.PURIFY) {
                if (visual.alembic().condensedRate() > 0 && random.nextFloat() < .15 * rate * Math.min(1, visual.alembic().condensedRate() * 400))
                    emit(level, ParticleTypes.FALLING_WATER, pos,
                            world(station, visual.alembic().collecting() ? StationAnchors.ALEMBIC_CONDENSER : StationAnchors.ALEMBIC_WASTE), .03, 0.0, random);
            } else if (random.nextFloat() < 0.06 * rate) {
                emit(level, ParticleTypes.WHITE_ASH, pos, 0.3, 0.9, 0.3, 0.15, 0.02, random);
            }
        } else if (station instanceof ApothecaryMortarBlockEntity) {
            var reagent = view.material();
            if (!reagent.isEmpty() && visual.mortar().pressing() && visual.mortar().workRate() > 0 && random.nextFloat() < .2 * rate * Math.min(1, visual.mortar().workRate() * 200)) {
                emit(level, new ItemParticleOption(ParticleTypes.ITEM, reagent), pos, 0.5, 0.5, 0.5, 0.15, 0.04, random);
            }
        }
    }

    private static void emit(Level level, ParticleOptions type, BlockPos pos, double x, double y, double z, double spread, double rise,
                             RandomSource random) {
        if (!claim(pos.asLong())) return;
        spawn(type, pos.getX() + x + (random.nextDouble() - 0.5) * spread, pos.getY() + y, pos.getZ() + z + (random.nextDouble() - 0.5) * spread, rise);
    }

    private static Vec3 world(StationBlockEntity station, Vec3 anchor) {
        return StationAnchors.world(station.getBlockPos(), station.getBlockState().getValue(StationBlock.FACING), anchor);
    }

    private static void emit(Level level, ParticleOptions type, BlockPos station, Vec3 point, double spread, double rise, RandomSource random) {
        if (!claim(station.asLong())) return;
        spawn(type, point.x + (random.nextDouble() - .5) * spread, point.y, point.z + (random.nextDouble() - .5) * spread, rise);
    }

    private static boolean claim(long station) {
        int limit = PresentationPolicy.current().reducedMotion() ? REDUCED_STATION_PER_SECOND : NORMAL_STATION_PER_SECOND;
        if (emittedThisSecond >= (PresentationPolicy.current().reducedMotion() ? REDUCED_GLOBAL_PER_SECOND : NORMAL_GLOBAL_PER_SECOND)
                || stationSecond.getOrDefault(station, 0) >= limit) return false;
        emittedThisSecond++; stationSecond.merge(station, 1, Integer::sum); return true;
    }

    /** Settings and distance were checked above; retain actual live instances to enforce the global cap. */
    private static void spawn(ParticleOptions type, double x, double y, double z, double rise) {
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(type, x, y, z, 0, rise, 0);
        if (particle != null) LIVE.addLast(particle);
    }

    public static int liveCount() { LIVE.removeIf(p -> !p.isAlive()); return LIVE.size(); }
    public static int emittedThisSecond() { return emittedThisSecond; }
    public static void remove(long pos) { FRESH.remove(pos); stationSecond.remove(pos); }
    public static void clear() { LIVE.forEach(Particle::remove); LIVE.clear(); FRESH.clear(); stationSecond.clear(); emittedThisSecond = 0; budgetSecond = Long.MIN_VALUE; }

    private StationParticles() {}
}
