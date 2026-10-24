package com.otectus.immersivebrewing.client.particle;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationView;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.item.KitData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

/**
 * Ambient world effects that follow the synced station view (spec 4, 24): steam and bubbles over a working Kettle,
 * spatter when its reaction is troubled, condensate at a working Alembic, fragments at a working Mortar, and a faint
 * warning mote over a batch held for recovery. Bounded to at most one particle per station per tick, only within 24
 * blocks of the player, scaled by {@code particleDensity} (and cut back under reduced motion); vanilla's particle option
 * applies on top.
 */
public final class StationParticles {
    private static final double RANGE_SQ = 24 * 24;
    private static final DustParticleOptions HELD = new DustParticleOptions(new Vector3f(0.75F, 0.25F, 0.2F), 0.7F);

    public static void tick(StationBlockEntity station) {
        Level level = station.getLevel();
        Minecraft mc = Minecraft.getInstance();
        if (level == null || mc.player == null || mc.isPaused()) return;
        BlockPos pos = station.getBlockPos();
        if (mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > RANGE_SQ) return;
        double rate = ClientConfig.get(ClientConfig.PARTICLE_DENSITY) * (ClientConfig.get(ClientConfig.REDUCED_MOTION) ? 0.3 : 1.0);
        if (rate <= 0) return;
        RandomSource random = level.random;
        StationView view = station.view();
        StationVisual visual = view.visual();
        if (view.state() == JobState.RECOVERY) {
            if (random.nextFloat() < 0.05 * rate) emit(level, HELD, pos, 0.5, 1.15, 0.5, 0.0, 0.01, random);
            return;
        }
        if (!visual.active()) return;
        if (station instanceof BrewersKettleBlockEntity) {
            if (visual.trouble() > 0.6F && random.nextFloat() < 0.25 * rate) {
                emit(level, ParticleTypes.SPLASH, pos, 0.5, 0.95, 0.5, 0.25, 0.0, random);
            } else if (random.nextFloat() < (0.08 + 0.22 * visual.heat()) * rate) {
                emit(level, visual.heat() > 0.55F ? ParticleTypes.CLOUD : ParticleTypes.BUBBLE_POP, pos, 0.5, 0.92, 0.5, 0.25, 0.015, random);
            }
        } else if (station instanceof AlembicBlockEntity) {
            if (view.operation() == Operation.PURIFY) {
                if (random.nextFloat() < 0.15 * rate) emit(level, ParticleTypes.FALLING_WATER, pos, 0.78, 0.62, 0.71, 0.03, 0.0, random);
            } else if (random.nextFloat() < 0.06 * rate) {
                emit(level, ParticleTypes.WHITE_ASH, pos, 0.3, 0.9, 0.3, 0.15, 0.02, random);
            }
        } else if (station instanceof ApothecaryMortarBlockEntity) {
            var reagent = KitData.read(view.output()).map(KitData::source);
            if (reagent.isPresent() && random.nextFloat() < 0.2 * rate) {
                emit(level, new ItemParticleOption(ParticleTypes.ITEM, reagent.get()), pos, 0.5, 0.5, 0.5, 0.15, 0.04, random);
            }
        }
    }

    private static void emit(Level level, ParticleOptions type, BlockPos pos, double x, double y, double z, double spread, double rise,
                             RandomSource random) {
        level.addParticle(type, pos.getX() + x + (random.nextDouble() - 0.5) * spread, pos.getY() + y, pos.getZ() + z + (random.nextDouble() - 0.5) * spread,
                0.0, rise, 0.0);
    }

    private StationParticles() {}
}
