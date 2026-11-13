package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.testing.LegacyBatches;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.FakePlayer;

/** GameTest reloads of the 1.1-format batches {@link LegacyBatches} builds (handoff 17). */
final class LegacyFixtures {
    /** A 1.1 kettle brew of {@code plan}, its simulation advanced {@code steps} steps by the reference bot, sealed. */
    static BrewersKettleBlockEntity sealedBrew(GameTestHelper h, BrewersKettleBlockEntity kettle, FakePlayer player, BrewPlan plan, int steps) {
        return (BrewersKettleBlockEntity) reloadWith(h, kettle, LegacyBatches.brew(kettle, player, plan, steps));
    }

    /** A 1.1 mortar preparation of the bowl's first {@code kits} units, simulated {@code steps} steps, sealed. */
    static ApothecaryMortarBlockEntity sealedPreparation(GameTestHelper h, ApothecaryMortarBlockEntity mortar, FakePlayer player, int kits, int steps) {
        return (ApothecaryMortarBlockEntity) reloadWith(h, mortar, LegacyBatches.preparation(mortar, player, kits, steps));
    }

    /** A 1.1 Alembic purification of the loaded inputs (they must form a valid group), simulated, sealed. */
    static AlembicBlockEntity sealedPurification(GameTestHelper h, AlembicBlockEntity alembic, FakePlayer player, int steps) {
        return (AlembicBlockEntity) reloadWith(h, alembic, LegacyBatches.purification(alembic, player, steps));
    }

    /** Replaces the block entity with one loaded from its NBT carrying the 1.1 ledger, the way an old world arrives. */
    static StationBlockEntity reloadWith(GameTestHelper h, StationBlockEntity station, JobLedger ledger) {
        BlockPos pos = station.getBlockPos();
        h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, station.getBlockState(), LegacyBatches.withLedger(station, ledger)));
        StationBlockEntity loaded = (StationBlockEntity) h.getLevel().getBlockEntity(pos);
        if (loaded == null || loaded.ledger() == null || !loaded.ledger().legacy()) throw new IllegalStateException("the legacy fixture did not load as legacy");
        return loaded;
    }

    private LegacyFixtures() {}
}
