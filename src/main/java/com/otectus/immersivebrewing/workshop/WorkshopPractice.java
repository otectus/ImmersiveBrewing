package com.otectus.immersivebrewing.workshop;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.network.packet.OpenPracticeS2C;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.session.SessionKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;


/**
 * Builds practice setups on the server (handoff 10): the exact physical parameters a real batch of the route or reagent
 * would get from the active data, plus display-only stacks reduced to their item and potion. The client runs them
 * locally; nothing comes back.
 */
public final class WorkshopPractice {
    public static final ResourceLocation DEFAULT_REAGENT = ImmersiveBrewing.id("sugar");

    /** Practice for a resolved route: its reaction family, bands, doses and the look of each stage. */
    public static OpenPracticeS2C forPlan(BrewPlan plan, int servings) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        var brewing = WorkshopProfiles.brewing(plan.brewProfile(), defs);
        KettleProcess.Setup setup = BrewersKettleBlockEntity.kettleSetup(plan, brewing.value(), defs, 1.0);
        CompoundTag t = new CompoundTag();
        t.put("setup", setup.save());
        t.putInt("servings", Math.max(1, Math.min(KettleProcess.MAX_SERVINGS, servings)));
        ListTag doses = new ListTag();
        for (BrewPlan.Step s : plan.steps()) doses.add(StringTag.valueOf(String.valueOf(ForgeRegistries.ITEMS.getKey(s.reagent()))));
        t.put("doseItems", doses);
        int[] colors = new int[plan.steps().size() + 1];
        colors[0] = PotionUtils.getColor(plan.input());
        for (int i = 0; i < plan.steps().size(); i++) colors[i + 1] = PotionUtils.getColor(plan.steps().get(i).after());
        t.putIntArray("stageColors", colors);
        t.put("input", displayOnly(plan.input()));
        t.put("output", displayOnly(plan.output()));
        t.putString("source", brewing.source());
        return new OpenPracticeS2C(SessionKind.BREWING, t);
    }

    /** Practice from the operator command or the Notebook: a default route, reagent or still for that kind. */
    @Nullable
    public static OpenPracticeS2C defaultFor(MinecraftServer server, SessionKind kind, @Nullable ResourceLocation profile) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        return switch (kind) {
            case BREWING -> {
                // Water, Awkward, Swiftness: the first batch the guide teaches. A named brew profile changes the reaction.
                PlanIndex index = PlanIndex.get(server.getRecipeManager());
                ItemStack water = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
                ItemStack swiftness = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
                BrewPlan plan = index.plansFrom(water).stream().filter(p -> ItemStack.isSameItemSameTags(p.output(), swiftness))
                        .min(java.util.Comparator.comparingInt(p -> p.steps().size())).orElse(null);
                if (plan == null) yield null;
                OpenPracticeS2C practice = forPlan(plan, 3);
                if (profile != null && defs.hasBrewProfile(profile)) {
                    var brewing = WorkshopProfiles.brewing(profile, defs);
                    practice.practice().put("setup", BrewersKettleBlockEntity.kettleSetup(plan, brewing.value(), defs, 1.0).save());
                    practice.practice().putString("source", brewing.source());
                }
                yield practice;
            }
            case PREPARATION -> {
                ReagentProfile reagent = null;
                ResourceLocation wanted = profile != null ? profile : DEFAULT_REAGENT;
                for (ReagentProfile p : defs.reagentProfiles()) {
                    if (p.preparation() == null) continue;
                    if (reagent == null || p.id().equals(wanted)) reagent = p;
                }
                if (reagent == null) yield null;
                Item item = Items.SUGAR;
                if (!reagent.selector().ids().isEmpty()) {
                    Item found = ForgeRegistries.ITEMS.getValue(reagent.selector().ids().iterator().next());
                    if (found != null && found != Items.AIR) item = found;
                }
                yield forReagent(item, reagent, defs);
            }
            case DISTILLATION -> {
                var profileFor = WorkshopProfiles.distillation(ImmersiveBrewing.id("distillation/vanilla_potions"));
                var d = profileFor.value();
                AlembicProcess.Setup setup = new AlembicProcess.Setup(1, 3, 2, 70, 50, 0, d.headsEnd(), d.tailsStart(), d.runSeconds(), profileFor.source());
                CompoundTag t = new CompoundTag();
                t.put("setup", setup.save());
                t.put("input", displayOnly(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH)));
                t.putString("source", profileFor.source());
                yield new OpenPracticeS2C(SessionKind.DISTILLATION, t);
            }
        };
    }

    /** Practice preparing one unit of a reagent with its real profile. */
    @Nullable
    public static OpenPracticeS2C forReagent(Item item, @Nullable ReagentProfile reagent, BrewingDefinitions defs) {
        if (reagent == null) {
            ProfileSelector.Choice<ReagentProfile> choice = defs.reagentProfile(item, null, null);
            reagent = choice == null ? null : choice.value();
        }
        var prep = WorkshopProfiles.preparation(item, reagent);
        if (prep == null) return null;
        CompoundTag t = new CompoundTag();
        t.put("setup", WorkshopProfiles.mortarSetup(prep, 1, defs.generation()).save());
        t.put("reagent", displayOnly(new ItemStack(item)));
        t.putString("source", prep.source());
        return new OpenPracticeS2C(SessionKind.PREPARATION, t);
    }

    /** A display stack reduced to its item and potion id: practice never carries real stack data. */
    static CompoundTag displayOnly(ItemStack stack) {
        ItemStack shown = new ItemStack(stack.getItem());
        var potion = PotionUtils.getPotion(stack);
        if (potion != Potions.EMPTY) PotionUtils.setPotion(shown, potion);
        CompoundTag tag = LedgerCodec.saveStack(shown);
        tag.remove("FullCount");
        return tag;
    }

    private WorkshopPractice() {}
}
