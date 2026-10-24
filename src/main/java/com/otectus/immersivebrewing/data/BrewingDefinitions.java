package com.otectus.immersivebrewing.data;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable, validated brewing dataset (spec 16.6): reagent profiles, brew profiles, effect policies and the merged
 * exclusions, stamped with a generation. The server activates a new generation atomically after a successful reload
 * (keeping the previous one when the candidate is invalid) and syncs it to clients, who keep their own copy for
 * tooltips, JEI and the practice screens.
 */
public record BrewingDefinitions(int generation, List<ReagentProfile> reagentProfiles, Map<ResourceLocation, BrewProfile> brewProfiles,
                                 List<EffectPolicyDefinition> effectPolicies, Exclusions exclusions) {
    public static final ResourceLocation GENERIC_PROFILE = ImmersiveBrewing.id("generic");
    /** Built-in safe core when no data has loaded: every reagent unprofiled, every effect metadata-only. */
    public static final BrewingDefinitions EMPTY = new BrewingDefinitions(0, List.of(), Map.of(), List.of(), Exclusions.NONE);

    private static volatile BrewingDefinitions server = EMPTY;
    private static volatile BrewingDefinitions client = EMPTY;

    public BrewingDefinitions {
        reagentProfiles = List.copyOf(reagentProfiles);
        brewProfiles = Collections.unmodifiableMap(new LinkedHashMap<>(brewProfiles));
        effectPolicies = List.copyOf(effectPolicies);
    }

    public static BrewingDefinitions server() {
        return server;
    }

    public static BrewingDefinitions client() {
        return client;
    }

    /** The dataset for the side asking: the client's synced copy on a logical client, the server's otherwise. */
    public static BrewingDefinitions forSide(boolean clientSide) {
        return clientSide ? client : server;
    }

    static void activateServer(BrewingDefinitions defs) {
        server = defs;
    }

    public static void activateClient(BrewingDefinitions defs) {
        client = defs;
    }

    /** Resets the client copy on disconnect, so a later single-player world never sees a server's data. */
    public static void resetClient() {
        client = EMPTY;
    }

    /**
     * The reagent profile for an item (spec 16.5 precedence): an explicit profile named by the recipe, then recipe
     * selectors, item ids, tags, namespaces. Excluded items have none.
     */
    @Nullable
    public ProfileSelector.Choice<ReagentProfile> reagentProfile(Item item, @Nullable ResourceLocation recipe, @Nullable ResourceLocation explicit) {
        if (exclusions.excludesItem(item)) return null;
        if (explicit != null) {
            for (ReagentProfile p : reagentProfiles) {
                if (p.id().equals(explicit)) return new ProfileSelector.Choice<>(p, p.id(), Selector.Layer.EXPLICIT, null);
            }
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) return null;
        return ProfileSelector.choose(reagentProfiles, ReagentProfile::id, ReagentProfile::selector, ReagentProfile::priority, id,
                tag -> item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tag)), recipe);
    }

    /** A brew profile by id, or the generic fallback. */
    public BrewProfile brewProfile(@Nullable ResourceLocation id) {
        if (id != null) {
            BrewProfile p = brewProfiles.get(id);
            if (p != null) return p;
        }
        BrewProfile generic = brewProfiles.get(GENERIC_PROFILE);
        return generic != null ? generic : BrewProfile.generic(GENERIC_PROFILE);
    }

    public boolean hasBrewProfile(ResourceLocation id) {
        return brewProfiles.containsKey(id);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(generation);
        buf.writeVarInt(reagentProfiles.size());
        for (ReagentProfile p : reagentProfiles) p.write(buf);
        buf.writeVarInt(brewProfiles.size());
        for (BrewProfile p : brewProfiles.values()) p.write(buf);
        buf.writeVarInt(effectPolicies.size());
        for (EffectPolicyDefinition p : effectPolicies) p.write(buf);
        exclusions.write(buf);
    }

    public static BrewingDefinitions read(FriendlyByteBuf buf) {
        int generation = buf.readVarInt();
        int n = bounded(buf.readVarInt());
        List<ReagentProfile> reagents = new ArrayList<>(n);
        for (int i = 0; i < n; i++) reagents.add(ReagentProfile.read(buf));
        n = bounded(buf.readVarInt());
        Map<ResourceLocation, BrewProfile> brews = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            BrewProfile p = BrewProfile.read(buf);
            brews.put(p.id(), p);
        }
        n = bounded(buf.readVarInt());
        List<EffectPolicyDefinition> policies = new ArrayList<>(n);
        for (int i = 0; i < n; i++) policies.add(EffectPolicyDefinition.read(buf));
        return new BrewingDefinitions(generation, reagents, brews, policies, Exclusions.read(buf));
    }

    private static int bounded(int n) {
        if (n < 0 || n > BrewingDataLoader.MAX_DEFINITIONS) throw new IllegalArgumentException("definition count " + n);
        return n;
    }
}
