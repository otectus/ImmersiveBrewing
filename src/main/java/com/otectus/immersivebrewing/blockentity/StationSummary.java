package com.otectus.immersivebrewing.blockentity;

import net.minecraft.network.chat.Component;
import java.util.List;

/** Common wording for Jade and the optional vanilla inspection hint. */
public final class StationSummary {
    public static List<Component> lines(StationView view) {
        Component action = switch (view.visual().activity()) {
            case HELP_PAUSED -> Component.translatable("screen.immersive_brewing.help_paused");
            case COUNT_IN, RESUME_COUNT_IN -> Component.translatable("screen.immersive_brewing.count_in", "…");
            case SEALED -> Component.translatable("screen.immersive_brewing.resume");
            case RECOVERY -> Component.translatable(view.recovery() == null ? "recovery.immersive_brewing.unknown" : view.recovery());
            case OUTPUT_READY -> Component.translatable("staging.immersive_brewing.collect");
            default -> view.operation() == null ? Component.translatable("item.immersive_brewing.brewers_notebook")
                    : Component.translatable(view.operation().key());
        };
        return List.of(view.status(), action);
    }
    private StationSummary() {}
}
