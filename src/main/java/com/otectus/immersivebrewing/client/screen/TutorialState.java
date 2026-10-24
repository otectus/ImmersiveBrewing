package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.client.ClientGuide;
import com.otectus.immersivebrewing.client.Keys;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import net.minecraft.world.item.ItemStack;

/** Whether the help overlay opens by itself for a game: until the player closes it once (stored with their notebook data). */
public final class TutorialState {

    public static boolean seen(Keys.Game game) {
        return (ClientGuide.tutorialFlags() & (1 << game.ordinal())) != 0;
    }

    public static void markSeen(Keys.Game game) {
        if (seen(game)) return;
        ClientGuide.markTutorial(game.ordinal());
        ModNetwork.sendToServer(new NotebookPrefsC2S(NotebookPrefsC2S.Action.TUTORIAL_STEP, "", ItemStack.EMPTY, game.ordinal()));
    }

    private TutorialState() {}
}
