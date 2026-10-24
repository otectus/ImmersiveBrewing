package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.minigame.brewing.BrewScore;
import com.otectus.immersivebrewing.minigame.distillation.DistillationScore;
import com.otectus.immersivebrewing.minigame.preparation.PreparationScore;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/**
 * A practice result in the same shape as a server result (spec 23.2): computed locally from the same simulation, marked
 * as practice, and never sent anywhere.
 */
public final class PracticeResults {

    public static CompoundTag describe(SimHandle sim, CompoundTag tag) {
        tag.putBoolean("practice", true);
        if (sim instanceof SimHandles.Brewing b) {
            BrewScore score = b.sim().score();
            tag.putString("kind", "brewing");
            tag.putInt("E", QualityMath.RAW_EXTRACTION);
            tag.putInt("C", score.control());
            tag.putInt("Q", QualityMath.quality(QualityMath.RAW_EXTRACTION, score.control()));
            tag.putDouble("thermal", score.thermal());
            tag.putDouble("circulation", score.circulation());
            tag.putDouble("dosing", score.dosing());
            ListTag additions = new ListTag();
            for (int i = 0; i < score.outcomes().length; i++) {
                CompoundTag o = new CompoundTag();
                o.putString("item", score.additionItems()[i]);
                o.putString("outcome", score.outcomes()[i].key());
                o.putDouble("credit", score.additionCredits()[i]);
                additions.add(o);
            }
            tag.put("additions", additions);
            ListTag phases = new ListTag();
            for (int i = 0; i < score.phases().length; i++) {
                CompoundTag p = new CompoundTag();
                p.putString("phase", "phase.immersive_brewing." + score.phases()[i].serialized());
                p.putDouble("thermal", score.phaseThermal()[i]);
                p.putDouble("circulation", score.phaseCirculation()[i]);
                p.putBoolean("counted", score.phaseCounted()[i]);
                phases.add(p);
            }
            tag.put("phases", phases);
            BrewScore.Tip tip = score.tip();
            tag.putString("tip", tip.key());
            ListTag args = new ListTag();
            for (String a : tip.args()) args.add(StringTag.valueOf(a));
            tag.put("tipArgs", args);
        } else if (sim instanceof SimHandles.Preparation p) {
            PreparationScore score = p.sim().score();
            tag.putString("kind", "preparation");
            tag.putInt("E", score.extraction());
            tag.putDouble("accuracy", score.accuracy());
            tag.putDouble("restraint", score.restraint());
            tag.putString("texture", "texture.immersive_brewing." + score.texture().name().toLowerCase(java.util.Locale.ROOT));
            tag.putInt("folds", score.folds());
            tag.putString("tip", score.tipKey());
        } else if (sim instanceof SimHandles.Distillation d) {
            DistillationScore score = d.sim().score();
            tag.putString("kind", "distillation");
            tag.putInt("D", score.distillation());
            tag.putDouble("collection", score.collection());
            tag.putDouble("thermal", score.thermal());
            tag.putDouble("cooling", score.cooling());
            tag.putInt("Qin", QualityMath.BASELINE);
            tag.putInt("Q", QualityMath.purified(QualityMath.BASELINE, score.distillation(), 70));
            tag.putString("tip", score.tipKey());
        }
        return tag;
    }

    private PracticeResults() {}
}
