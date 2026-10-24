package com.otectus.immersivebrewing.advancement;

import com.google.gson.JsonObject;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;

/** {@code immersive_brewing:milestone} with {@code "milestone": "<name>"}: fired from committed server outcomes only. */
public final class MilestoneTrigger extends SimpleCriterionTrigger<MilestoneTrigger.Instance> {
    public static final ResourceLocation ID = ImmersiveBrewing.id("milestone");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        return new Instance(player, GsonHelper.getAsString(json, "milestone"));
    }

    public void trigger(ServerPlayer player, Milestone milestone) {
        trigger(player, instance -> instance.milestone.equals(milestone.id()));
    }

    public static final class Instance extends AbstractCriterionTriggerInstance {
        private final String milestone;

        public Instance(ContextAwarePredicate player, String milestone) {
            super(ID, player);
            this.milestone = milestone;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("milestone", milestone);
            return json;
        }
    }
}
