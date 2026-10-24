package com.otectus.immersivebrewing.smoketest;

import com.otectus.immersivebrewing.client.Keys;
import com.otectus.immersivebrewing.client.screen.DistillationScreen;
import com.otectus.immersivebrewing.client.screen.GameScreen;
import com.otectus.immersivebrewing.client.screen.KettleBrewScreen;
import com.otectus.immersivebrewing.client.screen.PreparationScreen;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.distillation.DistillationInput;
import com.otectus.immersivebrewing.minigame.preparation.PreparationInput;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.DistillBot;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The keyboard-only trial (spec 24.3): plays whichever game screen is open with the reference bots, but only through the
 * screen's own key handling (held keys and key-down edges of the default bindings), never by injecting frames. A good
 * score here means every control the games need is reachable from the keyboard.
 */
final class KeyboardDriver {
    private final KettleBot kettle = KettleBot.competent();
    private final MortarBot mortar = MortarBot.competent();
    private final DistillBot alembic = DistillBot.competent();
    private Set<KeyMapping> held = new HashSet<>();
    private GameScreen current;
    private int presses;

    /** Key-down events sent so far (evidence that the keys, not injected frames, played the games). */
    int presses() {
        return presses;
    }

    /** The help opened: the screen dropped every held key, so they are pressed again once play continues. */
    void paused() {
        held = new HashSet<>();
    }

    void tick(GameScreen screen, SimHandle sim) {
        if (screen != current) {
            // A new screen starts with nothing held (the old one released everything when it closed).
            current = screen;
            held = new HashSet<>();
        }
        Set<KeyMapping> want = new HashSet<>();
        List<KeyMapping> taps = new ArrayList<>();
        if (sim instanceof SimHandles.Brewing b && screen instanceof KettleBrewScreen brew) {
            KettleInput in = kettle.decide(b.sim());
            // The draft keys step one level per press, so a jump of two levels is two taps in this tick.
            for (int d = brew.draftLevel(); d < in.draft(); d++) taps.add(Keys.DRAFT_UP);
            for (int d = brew.draftLevel(); d > in.draft(); d--) taps.add(Keys.DRAFT_DOWN);
            if (in.stir() >= 3) want.add(Keys.STIR_CW);
            else if (in.stir() <= -3) want.add(Keys.STIR_CCW);
            if (Math.abs(in.stir()) >= 7) want.add(Keys.VIGOROUS);
            if (in.add()) taps.add(Keys.ADD);
        } else if (sim instanceof SimHandles.Preparation p && screen instanceof PreparationScreen prep) {
            PreparationInput in = mortar.decide(p.sim());
            double dx = in.targetX() - prep.targetX();
            double dy = in.targetY() - prep.targetY();
            if (dx > 0.05) want.add(Keys.PESTLE_RIGHT);
            else if (dx < -0.05) want.add(Keys.PESTLE_LEFT);
            if (dy > 0.05) want.add(Keys.PESTLE_DOWN);
            else if (dy < -0.05) want.add(Keys.PESTLE_UP);
            if (in.press()) want.add(Keys.PRESS);
            if (in.fold()) taps.add(Keys.FOLD);
            if (in.finish()) taps.add(Keys.FINISH);
        } else if (sim instanceof SimHandles.Distillation d && screen instanceof DistillationScreen still) {
            DistillationInput in = alembic.decide(d.sim());
            if (in.heat() > still.heatLevel()) taps.add(Keys.HEAT_UP);
            else if (in.heat() < still.heatLevel()) taps.add(Keys.HEAT_DOWN);
            if (in.coolant() > still.coolantLevel()) taps.add(Keys.COOL_UP);
            else if (in.coolant() < still.coolantLevel()) taps.add(Keys.COOL_DOWN);
            if (in.collect() != still.collecting()) taps.add(Keys.RECEIVER);
        }
        for (KeyMapping k : held) if (!want.contains(k)) screen.keyReleased(k.getKey().getValue(), 0, 0);
        for (KeyMapping k : want) {
            if (held.contains(k)) continue;
            screen.keyPressed(k.getKey().getValue(), 0, 0);
            presses++;
        }
        held = want;
        for (KeyMapping k : taps) {
            if (held.contains(k)) continue;
            screen.keyPressed(k.getKey().getValue(), 0, 0);
            screen.keyReleased(k.getKey().getValue(), 0, 0);
            presses++;
        }
    }

}
