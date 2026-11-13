package com.otectus.immersivebrewing.interaction;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The outcome of one intent. A refusal names why (shown to the acting player); an ignored intent was stale, repeated or
 * cosmetic and is dropped silently, never debiting anything.
 */
public record WorkshopResult(Kind kind, @Nullable Component reason) {
    public enum Kind { ACCEPTED, REFUSED, IGNORED }

    public static final WorkshopResult ACCEPTED = new WorkshopResult(Kind.ACCEPTED, null);
    public static final WorkshopResult IGNORED = new WorkshopResult(Kind.IGNORED, null);

    public static WorkshopResult refuse(String key, Object... args) {
        return new WorkshopResult(Kind.REFUSED, Component.translatable(key, args));
    }

    public static WorkshopResult refuse(Component reason) {
        return new WorkshopResult(Kind.REFUSED, reason);
    }

    public boolean accepted() {
        return kind == Kind.ACCEPTED;
    }
}
