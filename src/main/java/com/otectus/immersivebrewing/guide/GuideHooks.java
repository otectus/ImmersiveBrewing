package com.otectus.immersivebrewing.guide;

import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Common code's door to the client-only guide screens. {@code ClientSetup} installs the opener; on a dedicated server it
 * stays a no-op, so common classes never load client classes.
 */
public final class GuideHooks {
    public enum JournalAction { INSPECT, SELECT_AT_STATION, REPEAT_SETUP }
    /** A client request only. The station/catalog integration must re-resolve {@code plan} on the server before acting. */
    public record JournalRequest(JournalAction action, String plan, int servings) {}
    @FunctionalInterface
    public interface RepeatHandler {
        void request(ServerPlayer player, String plan, ItemStack savedInput, int servings);
    }

    private static volatile BiConsumer<ItemStack, InteractionHand> notebookOpener = (stack, hand) -> {};
    private static volatile Consumer<JournalRequest> journalHandler = request -> {};
    private static volatile RepeatHandler repeatHandler = (player, plan, input, servings) -> {};

    public static void install(BiConsumer<ItemStack, InteractionHand> opener) {
        notebookOpener = opener;
    }

    public static void installJournalHandler(Consumer<JournalRequest> handler) {
        journalHandler = handler == null ? request -> {} : handler;
    }

    public static void installRepeatHandler(RepeatHandler handler) {
        repeatHandler = handler == null ? (player, plan, input, servings) -> {} : handler;
    }

    public static void openNotebook(ItemStack otherHand, InteractionHand hand) {
        notebookOpener.accept(otherHand, hand);
    }

    public static void request(JournalAction action, String plan, int servings) {
        if (PlanKeyGuard.valid(plan)) journalHandler.accept(new JournalRequest(action, plan, Math.max(1, Math.min(6, servings))));
    }

    public static void repeat(ServerPlayer player, String plan, ItemStack savedInput, int servings) {
        if (PlanKeyGuard.valid(plan)) repeatHandler.request(player, plan, savedInput.copyWithCount(1), Math.max(1, Math.min(6, servings)));
    }

    /** Keeps this common hook independent from client recipe classes while still rejecting arbitrary strings. */
    private static final class PlanKeyGuard {
        private static boolean valid(String value) {
            return com.otectus.immersivebrewing.recipe.PlanKey.parse(value) != null;
        }
    }

    private GuideHooks() {}
}
