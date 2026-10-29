package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.client.tutorial.GuidedPractice;
import com.otectus.immersivebrewing.data.BrewProfile;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.minigame.brewing.KettlePhase;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.minigame.distillation.DistillationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.session.SessionKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Practice sessions (spec 23.2): the same simulations with the synced profile parameters, run entirely on the client.
 * There is no server session, inventory, fuel, event or reward, so nothing about a practice run can reach the server.
 */
public final class PracticeSetup {
    public static final ResourceLocation DEFAULT_BREW = ImmersiveBrewing.id("light_foam");
    public static final ResourceLocation DEFAULT_REAGENT = ImmersiveBrewing.id("sugar");

    @Nullable
    public static ClientSession create(SessionKind kind, @Nullable ResourceLocation profile) {
        BrewingDefinitions defs = BrewingDefinitions.client();
        CompoundTag display = new CompoundTag();
        long seed = ThreadLocalRandom.current().nextLong();
        return switch (kind) {
            case BREWING -> {
                BrewProfile brew = defs.brewProfile(profile != null ? profile : DEFAULT_BREW);
                List<KettleSetup.Step> steps = List.of(
                        new KettleSetup.Step(KettlePhase.BLOOM, KettleSetup.Role.BASE, "minecraft:nether_wart", 0.04, 0.15, false),
                        new KettleSetup.Step(KettlePhase.INFUSE, KettleSetup.Role.EFFECT, "minecraft:sugar", 0.08,
                                brew.infuse().signature().kick, false));
                KettleSetup.Plan plan = new KettleSetup.Plan(steps, brew.bloomOrDefault(), brew.infuse(), brew.settleOrDefault(), brew.disturbances(),
                        brew.inertia(), 3);
                display.put("output", LedgerCodec.saveStack(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS)));
                display.putInt("servings", 3);
                yield ClientSession.practice(kind, KettleSetup.build(plan, seed, 50, KettleSetup.Assist.NONE).encode(), display);
            }
            case PREPARATION -> {
                ReagentProfile reagent = null;
                ResourceLocation wanted = profile != null ? profile : DEFAULT_REAGENT;
                for (ReagentProfile p : defs.reagentProfiles()) {
                    if (p.preparation() == null) continue;
                    if (reagent == null || p.id().equals(wanted)) reagent = p;
                }
                if (reagent == null) yield null;
                ReagentProfile.Preparation prep = reagent.preparation();
                ItemStack shown = new ItemStack(Items.SUGAR);
                if (!reagent.selector().ids().isEmpty()) {
                    var item = ForgeRegistries.ITEMS.getValue(reagent.selector().ids().iterator().next());
                    if (item != null) shown = new ItemStack(item);
                }
                display.put("reagent", LedgerCodec.saveStack(shown));
                display.putString("profile", reagent.id().toString());
                display.putInt("kits", 1);
                yield ClientSession.practice(kind, PreparationParams.of(prep.mode(), prep.durationTicks(), prep.endpointLow(), prep.endpointHigh(),
                        prep.pressureLimit(), prep.overworkThreshold(), 1, 1.0).encode(), display);
            }
            case DISTILLATION -> {
                display.put("input", LedgerCodec.saveStack(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS)));
                yield ClientSession.practice(kind, DistillationParams.create(seed, 1.0, 1.0).encode(), display);
            }
        };
    }

    /** Creates a staged kettle lesson locally. Practice never sends input or produces a stack. */
    public static ClientSession createGuided(GuidedPractice.Stage stage) {
        CompoundTag display = new CompoundTag();
        display.putBoolean("guided", true);
        display.putString("guidedStage", GuidedPractice.id(stage));
        display.put("output", LedgerCodec.saveStack(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS)));
        display.putInt("servings", 0);
        return ClientSession.practice(SessionKind.BREWING, GuidedPractice.params(stage).encode(), display);
    }

    private PracticeSetup() {}
}
