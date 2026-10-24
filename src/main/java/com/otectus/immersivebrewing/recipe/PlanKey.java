package com.otectus.immersivebrewing.recipe;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * A stable, server-known reference to a plan: the chain of recipe segments it uses, or the contextual adapter query it
 * came from. Clients send keys, never steps or outputs; the server re-resolves every key against the station's actual
 * bottles before reserving anything.
 */
public record PlanKey(List<Part> parts) {
    public static final int MAX_PARTS = 8;

    /**
     * A recipe segment {@code [from, to)} of recipe {@code source}, or a contextual query: {@code source} is the adapter
     * id, {@code item} the ingredient and {@code from = to = -1}.
     */
    public record Part(ResourceLocation source, int from, int to, ResourceLocation item) {
        public boolean contextual() {
            return from < 0;
        }
    }

    public PlanKey {
        parts = List.copyOf(parts);
        if (parts.isEmpty() || parts.size() > MAX_PARTS) throw new IllegalArgumentException("plan key parts");
    }

    public PlanKey append(Part part) {
        List<Part> list = new ArrayList<>(parts);
        list.add(part);
        return new PlanKey(list);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(parts.size());
        for (Part p : parts) {
            buf.writeResourceLocation(p.source);
            buf.writeVarInt(p.from + 1);
            buf.writeVarInt(p.to + 1);
            buf.writeResourceLocation(p.item);
        }
    }

    public static PlanKey read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 1 || n > MAX_PARTS) throw new IllegalArgumentException("plan key length " + n);
        List<Part> parts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ResourceLocation source = buf.readResourceLocation();
            int from = buf.readVarInt() - 1;
            int to = buf.readVarInt() - 1;
            ResourceLocation item = buf.readResourceLocation();
            if (from < -1 || to < -1 || from > InfusionRecipe.MAX_STEPS || to > InfusionRecipe.MAX_STEPS) throw new IllegalArgumentException("plan key range");
            parts.add(new Part(source, from, to, item));
        }
        return new PlanKey(parts);
    }

    /** Compact text form for player data (favourites): {@code source|from|to|item} parts joined by {@code ;}. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(p.source).append('|').append(p.from).append('|').append(p.to).append('|').append(p.item);
        }
        return sb.toString();
    }

    @org.jetbrains.annotations.Nullable
    public static PlanKey parse(String text) {
        try {
            List<Part> parts = new ArrayList<>();
            for (String piece : text.split(";")) {
                String[] f = piece.split("\\|");
                if (f.length != 4) return null;
                ResourceLocation source = ResourceLocation.tryParse(f[0]);
                ResourceLocation item = ResourceLocation.tryParse(f[3]);
                if (source == null || item == null) return null;
                parts.add(new Part(source, Integer.parseInt(f[1]), Integer.parseInt(f[2]), item));
            }
            return parts.isEmpty() || parts.size() > MAX_PARTS ? null : new PlanKey(parts);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append(p.contextual() ? "query " + p.source + " with " + p.item : p.source + "[" + p.from + ".." + p.to + ")");
        }
        return sb.toString();
    }
}
