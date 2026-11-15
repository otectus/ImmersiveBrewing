package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.KettleSetupService;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The one useful next action (handoff 10, Guided level): read from the material's own cue or the recipe page, said in a
 * few words and placed at the part it concerns. Never a dashboard: one hint at a time, and none at the Minimal level
 * (where the apparatus, the hovered part's label and refusals carry the work). {@link #urgent} is shown at every level.
 */
public final class WorkshopGuidance {
    /** The hint, or null when the apparatus says enough by itself. */
    @Nullable
    public static Component hint(WorkshopTargets.View v, ClientWorkshop.Hand hand) {
        if (v.sealed() || v.state() == JobState.RECOVERY || v.legacy()) return null;
        if (hand.carried() >= 0) return Component.translatable("workshop.immersive_brewing.guide.tip_over_mouth");
        return switch (v.kind()) {
            case KETTLE -> kettle(v);
            case MORTAR -> v.mortar().contains("cue") ? Component.translatable(v.mortar().getString("cue")) : null;
            case ALEMBIC -> v.alembic().contains("cue") ? Component.translatable(v.alembic().getString("cue"))
                    : v.state() == null && v.snapshot().contains("previewIn") ? Component.translatable("workshop.immersive_brewing.guide.charge") : null;
            case RACK -> null;
        };
    }

    @Nullable
    private static Component kettle(WorkshopTargets.View v) {
        CompoundTag k = v.kettle();
        if (k.contains("cue")) return Component.translatable(k.getString("cue"));
        if (v.finishing().contains("cue")) return Component.translatable(v.finishing().getString("cue"));
        if (v.state() != null) return null;
        KettleSetupService.Status status = status(v);
        return switch (status) {
            case READY -> Component.translatable("workshop.immersive_brewing.guide.tip_to_begin");
            case CHOOSE -> Component.translatable("workshop.immersive_brewing.guide.choose_page", v.setup().getByte("pages"));
            case MISSING -> v.setup().contains("problem") ? Component.Serializer.fromJson(v.setup().getString("problem"))
                    : Component.translatable("workshop.immersive_brewing.guide.missing");
            case NEED_BOTTLES -> Component.translatable("workshop.immersive_brewing.guide.need_bottles");
            case NEED_INGREDIENTS -> Component.translatable("workshop.immersive_brewing.guide.need_ingredients");
            case UNSUPPORTED -> Component.translatable("workshop.immersive_brewing.setup.unsupported");
            case COLLECT -> Component.translatable("workshop.immersive_brewing.guide.collect");
            case EMPTY -> Component.translatable("workshop.immersive_brewing.guide.empty");
            default -> null;
        };
    }

    /** The part a hint concerns (where it is shown). */
    public static int anchor(WorkshopTargets.View v, ClientWorkshop.Hand hand) {
        if (hand.carried() >= 0) return v.kind() == WorkshopKind.KETTLE ? WorkshopParts.K_VESSEL : 0;
        switch (v.kind()) {
            case KETTLE -> {
                CompoundTag k = v.kettle();
                if (k.contains("cue")) {
                    String cue = k.getString("cue");
                    if (cue.endsWith(".charge")) return firstStaged(k);
                    if (cue.endsWith(".dose")) return WorkshopParts.K_CUP + k.getByte("doses");
                    if (cue.endsWith(".ready")) return WorkshopParts.K_SPOUT;
                    if (cue.endsWith("warm") || cue.endsWith("too_cold") || cue.endsWith("too_hot") || cue.endsWith("warmer") || cue.endsWith("ease_heat")) {
                        return WorkshopParts.K_BURNER;
                    }
                    return WorkshopParts.K_VESSEL;
                }
                if (v.state() != null) return WorkshopParts.K_VESSEL;
                return switch (status(v)) {
                    case READY -> WorkshopParts.K_RACK + firstBottle(v);
                    case CHOOSE, UNSUPPORTED -> WorkshopParts.K_BOOK;
                    case MISSING -> firstGhost(v);
                    case NEED_INGREDIENTS -> WorkshopParts.K_TRAY;
                    default -> WorkshopParts.K_RACK;
                };
            }
            case MORTAR -> {
                return v.mortar().getBoolean("usable") ? WorkshopParts.M_CUP : WorkshopParts.M_BOWL;
            }
            case ALEMBIC -> {
                if (v.state() == null) return WorkshopParts.A_CHARGE;
                String cue = v.alembic().getString("cue");
                if (cue.contains("clamp")) return WorkshopParts.A_CLAMP;
                if (cue.contains("cool")) return WorkshopParts.A_VALVE;
                if (cue.contains("heat") || cue.contains("warm")) return WorkshopParts.A_BURNER;
                return WorkshopParts.A_DIVERTER;
            }
            default -> {
                return 0;
            }
        }
    }

    /** What must never be missed: a held or sealed batch, an earlier version's batch, and danger to the player. */
    @Nullable
    public static Component urgent(WorkshopTargets.View v) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            if (player.isOnFire()) return Component.translatable("workshop.immersive_brewing.urgent.fire");
            if (player.getAirSupply() < player.getMaxAirSupply() * 0.4) return Component.translatable("workshop.immersive_brewing.urgent.air");
            if (player.getHealth() <= player.getMaxHealth() * 0.3F) return Component.translatable("workshop.immersive_brewing.urgent.health");
        }
        if (v.sealed()) return Component.translatable("cue.immersive_brewing.sealed");
        if (v.state() == JobState.RECOVERY) return Component.translatable("cue.immersive_brewing.held");
        if (v.legacy()) return Component.translatable("cue.immersive_brewing.legacy");
        return null;
    }

    static KettleSetupService.Status status(WorkshopTargets.View v) {
        var all = KettleSetupService.Status.values();
        return all[Math.max(0, Math.min(all.length - 1, v.setup().getByte("status")))];
    }

    private static int firstStaged(CompoundTag k) {
        byte[] slots = k.getByteArray("slots"), portions = k.getByteArray("portions");
        for (int i = 0; i < Math.min(slots.length, portions.length); i++) {
            if (portions[i] == KettleProcess.Portion.STAGED.ordinal()) return WorkshopParts.K_RACK + slots[i];
        }
        return WorkshopParts.K_VESSEL;
    }

    private static int firstBottle(WorkshopTargets.View v) {
        if (v.be() instanceof com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity kettle) {
            for (int i = 0; i < com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK_SIZE; i++) {
                if (!kettle.inventory().getStackInSlot(com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK + i).isEmpty()) return i;
            }
        }
        return 0;
    }

    private static int firstGhost(WorkshopTargets.View v) {
        var ghosts = v.setup().getList("ghosts", net.minecraft.nbt.Tag.TAG_COMPOUND);
        return ghosts.isEmpty() ? WorkshopParts.K_BOOK : ghosts.getCompound(0).getByte("part");
    }

    private WorkshopGuidance() {}
}
