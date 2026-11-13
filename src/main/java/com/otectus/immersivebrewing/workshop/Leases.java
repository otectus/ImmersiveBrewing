package com.otectus.immersivebrewing.workshop;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short server leases on a station's tools and live controls (handoff 16): one active hand per pestle, paddle or
 * scraper, and one live drag per control. A lease lasts while its holder keeps using the part and expires on its own
 * after {@link #EXPIRY_TICKS} of silence, so a holder who vanished never blocks the next one. Transient: leases clear on
 * load, seal, logout, death, damage and dimension change; the material state they touched persists.
 */
public final class Leases {
    public static final int EXPIRY_TICKS = 60;

    public record Lease(UUID holder, String name, long expires) {}

    private final Map<Integer, Lease> byPart = new HashMap<>();

    /** Takes the part for {@code player}, or returns the other holder's lease when it is still live. */
    @Nullable
    public Lease acquire(int part, UUID player, String name, long now) {
        Lease current = byPart.get(part);
        if (current != null && current.expires() >= now && !current.holder().equals(player)) return current;
        byPart.put(part, new Lease(player, name, now + EXPIRY_TICKS));
        return null;
    }

    /** Whether {@code player} holds the part now; renews the lease when they do. */
    public boolean holds(int part, UUID player, long now) {
        Lease current = byPart.get(part);
        if (current == null || current.expires() < now || !current.holder().equals(player)) return false;
        byPart.put(part, new Lease(player, current.name(), now + EXPIRY_TICKS));
        return true;
    }

    /** The live holder of a part, or null. */
    @Nullable
    public Lease holder(int part, long now) {
        Lease current = byPart.get(part);
        return current == null || current.expires() < now ? null : current;
    }

    /** Whether another player has a live lease on the part (for discrete changes to a dragged control). */
    public boolean heldByOther(int part, UUID player, long now) {
        Lease current = holder(part, now);
        return current != null && !current.holder().equals(player);
    }

    public boolean release(int part, UUID player) {
        Lease current = byPart.get(part);
        if (current == null || !current.holder().equals(player)) return false;
        byPart.remove(part);
        return true;
    }

    /** Drops every lease of one player (logout, death, damage, leaving). Returns whether any was held. */
    public boolean releaseAll(UUID player) {
        return byPart.values().removeIf(l -> l.holder().equals(player));
    }

    public void clear() {
        byPart.clear();
    }

    /** Forgets expired leases; returns the parts whose lease lapsed. */
    public java.util.List<Integer> expire(long now) {
        java.util.List<Integer> lapsed = new java.util.ArrayList<>();
        byPart.entrySet().removeIf(e -> {
            if (e.getValue().expires() < now) {
                lapsed.add(e.getKey());
                return true;
            }
            return false;
        });
        return lapsed;
    }

    public Map<Integer, Lease> all() {
        return byPart;
    }
}
