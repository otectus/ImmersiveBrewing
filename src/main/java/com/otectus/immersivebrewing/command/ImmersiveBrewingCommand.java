package com.otectus.immersivebrewing.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.SlotStack;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.OpenPracticeS2C;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.CoverageReport;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.session.ActiveSession;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code /immersivebrewing} (spec 25.3). Public: {@code help}, {@code practice}. Operators (level 2): {@code report},
 * {@code recipe}, {@code profile}, {@code inspect}, {@code quality}, {@code session list|seal}, {@code recover}.
 * Diagnostics go only to the caller, never to public chat, and never print private labels or foreign NBT. There is no
 * command that completes work for a reward.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID)
public final class ImmersiveBrewingCommand {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> practice = Commands.literal("practice");
        for (SessionKind kind : SessionKind.values()) {
            String name = switch (kind) {
                case PREPARATION -> "preparation";
                case BREWING -> "brewing";
                case DISTILLATION -> "distillation";
            };
            practice.then(Commands.literal(name).executes(ctx -> practice(ctx, kind, null))
                    .then(Commands.argument("profile", ResourceLocationArgument.id())
                            .executes(ctx -> practice(ctx, kind, ResourceLocationArgument.getId(ctx, "profile")))));
        }
        dispatcher.register(Commands.literal("immersivebrewing")
                .then(Commands.literal("help").executes(ImmersiveBrewingCommand::help))
                .then(practice)
                .then(Commands.literal("report").requires(s -> s.hasPermission(2)).executes(ImmersiveBrewingCommand::report))
                .then(Commands.literal("recipe").requires(s -> s.hasPermission(2)).executes(ImmersiveBrewingCommand::recipeHeld)
                        .then(Commands.argument("id", ResourceLocationArgument.id()).executes(ImmersiveBrewingCommand::recipeById)))
                .then(Commands.literal("profile").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("item", ItemArgument.item(context)).executes(ImmersiveBrewingCommand::profile)))
                .then(Commands.literal("inspect").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ImmersiveBrewingCommand::inspect)))
                .then(Commands.literal("quality").requires(s -> s.hasPermission(2)).executes(ImmersiveBrewingCommand::quality))
                .then(Commands.literal("explain").requires(s -> s.hasPermission(2)).executes(ImmersiveBrewingCommand::explainPotion)
                        .then(Commands.literal("plan").then(Commands.argument("key", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ImmersiveBrewingCommand::explainPlan))))
                .then(Commands.literal("session").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("list").executes(ImmersiveBrewingCommand::sessionList))
                        .then(Commands.literal("seal").then(Commands.argument("player", EntityArgument.player()).executes(ImmersiveBrewingCommand::sessionSeal))))
                .then(Commands.literal("recover").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ImmersiveBrewingCommand::recover))));
    }

    private static void say(CommandContext<CommandSourceStack> ctx, Component text) {
        ctx.getSource().sendSuccess(() -> text, false);
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String text) {
        say(ctx, Component.literal(text));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        say(ctx, Component.translatable("command.immersive_brewing.help.1"));
        if (ctx.getSource().hasPermission(2)) say(ctx, Component.translatable("command.immersive_brewing.help.2"));
        return 1;
    }

    private static int practice(CommandContext<CommandSourceStack> ctx, SessionKind kind, ResourceLocation profile) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ModNetwork.sendTo(player, new OpenPracticeS2C(kind, profile));
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<String> lines = CoverageReport.build(server);
        Path path = server.getWorldPath(LevelResource.ROOT).resolve("immersive_brewing").resolve("coverage-report.txt");
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Could not write the report: " + e.getMessage()));
            return 0;
        }
        for (String line : lines) {
            if (line.startsWith("Infusion recipes") || line.startsWith("Discovery") || line.startsWith("Mode") || line.startsWith("Brewing data")) say(ctx, line);
        }
        say(ctx, "Full report: " + path.toAbsolutePath());
        return 1;
    }

    private static int recipeHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Hold a potion (the plan input) or give a recipe id."));
            return 0;
        }
        List<BrewPlan> plans = PlanIndex.get(ctx.getSource().getServer().getRecipeManager()).plansFrom(held);
        say(ctx, plans.size() + " plans from " + held.getHoverName().getString() + " (costs for 3 servings):");
        for (int i = 0; i < Math.min(15, plans.size()); i++) {
            BrewPlan p = plans.get(i);
            StringBuilder costs = new StringBuilder();
            for (BrewPlan.Cost c : p.costs(3)) costs.append(c.count()).append(' ').append(ForgeRegistries.ITEMS.getKey(c.item()).getPath()).append(", ");
            say(ctx, " - " + p.output().getHoverName().getString() + ": " + costs + p.fuelCharges(3) + " fuel" + (p.contextual() ? " [contextual]" : ""));
        }
        if (plans.size() > 15) say(ctx, " ... " + (plans.size() - 15) + " more (see /immersivebrewing report)");
        return plans.size();
    }

    private static int recipeById(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
        PlanIndex index = PlanIndex.get(ctx.getSource().getServer().getRecipeManager());
        for (PlanIndex.Entry e : index.report()) {
            if (!e.recipe().equals(id)) continue;
            say(ctx, id + ": " + e.status().name().toLowerCase(java.util.Locale.ROOT) + " (" + e.reason().replace('|', ' ') + ")");
            e.warnings().forEach(w -> say(ctx, "  warning: " + w));
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("No infusion recipe " + id));
        return 0;
    }

    private static int profile(CommandContext<CommandSourceStack> ctx) {
        Item item = ItemArgument.getItem(ctx, "item").getItem();
        BrewingDefinitions defs = BrewingDefinitions.server();
        if (defs.exclusions().excludesItem(item)) {
            say(ctx, ForgeRegistries.ITEMS.getKey(item) + " is excluded by the active exclusions.");
            return 1;
        }
        ProfileSelector.Choice<ReagentProfile> choice = defs.reagentProfile(item, null, null);
        if (choice == null) {
            say(ctx, ForgeRegistries.ITEMS.getKey(item) + ": no reagent profile (unprofiled: no preparation, raw extraction 50).");
            return 1;
        }
        ReagentProfile p = choice.value();
        say(ctx, ForgeRegistries.ITEMS.getKey(item) + ": profile " + p.id() + " via " + choice.layer().name().toLowerCase(java.util.Locale.ROOT)
                + " selector, priority " + p.priority() + (choice.tiedWith() != null ? " (tied with " + choice.tiedWith() + "; lowest id wins)" : ""));
        say(ctx, "  preparation: " + (p.preparation() == null ? "none" : p.preparation().mode().serialized() + ", endpoint "
                + p.preparation().endpointLow() + "-" + p.preparation().endpointHigh() + ", " + p.preparation().durationTicks() + " ticks"));
        say(ctx, "  reaction: " + (p.reaction().profile() == null ? "plan's profile" : p.reaction().profile()) + ", cooling "
                + p.reaction().additionCooling() + ", sensitivity " + p.reaction().sensitivity());
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        if (!(ctx.getSource().getLevel().getBlockEntity(pos) instanceof StationBlockEntity station)) {
            ctx.getSource().sendFailure(Component.literal("No Immersive Brewing station at " + pos.toShortString()));
            return 0;
        }
        say(ctx, Component.literal(station.stationId() + " at " + pos.toShortString() + ": ").append(station.view().status()));
        say(ctx, "  burner charges: " + station.fuelCharges() + ", controller: " + Optional.ofNullable(station.controllerName()).orElse("none"));
        JobLedger l = station.ledger();
        if (l == null) {
            say(ctx, "  no job");
            return 1;
        }
        say(ctx, "  job " + l.jobId() + ": " + l.operation() + " " + l.state() + (l.recoveryReason() != null ? " (" + l.recoveryReason() + ")" : ""));
        say(ctx, "  holds " + l.inputs().size() + " batch stack(s), " + l.reserved().size() + " reserved reagent stack(s), " + l.fuelCharges() + " fuel charges");
        for (ItemStack s : l.inputs()) say(ctx, "    " + s.getCount() + "x " + ForgeRegistries.ITEMS.getKey(s.getItem()) + potion(s));
        for (SlotStack s : l.reserved()) say(ctx, "    slot " + s.slot() + ": " + s.stack().getCount() + "x " + ForgeRegistries.ITEMS.getKey(s.stack().getItem()));
        say(ctx, "  resumed " + l.resumes() + " time(s), data generation " + l.dataGeneration() + (l.unknownSchema() != null ? ", held raw" : ""));
        return 1;
    }

    private static String potion(ItemStack s) {
        var key = ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(s));
        return key == null || !(s.getItem() instanceof net.minecraft.world.item.PotionItem) ? "" : " [" + key + "]";
    }

    private static int quality(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        Optional<BrewingMetadata> meta = BrewingMetadata.read(held);
        if (meta.isEmpty()) {
            say(ctx, BrewingMetadata.hasUnreadableCompound(held) ? "Brewing data from a newer version (kept unchanged; behaves as quality 50)."
                    : "No brewing data: an ordinary potion behaves as quality 50.");
        } else {
            BrewingMetadata m = meta.get();
            say(ctx, "Q " + m.quality() + " (" + m.grade().name().toLowerCase(java.util.Locale.ROOT) + "), E " + m.extraction() + ", C " + m.control()
                    + ", purified " + m.purifications() + "x, lineage " + Long.toHexString(m.lineage()) + (m.label() != null ? ", labelled" : ""));
        }
        List<MobEffectInstance> canonical = PotionUtils.getMobEffects(held);
        List<MobEffectInstance> effective = PotionContentsService.effectiveEffects(held);
        for (int i = 0; i < canonical.size(); i++) {
            MobEffectInstance c = canonical.get(i);
            MobEffectInstance e = i < effective.size() ? effective.get(i) : c;
            say(ctx, "  " + ForgeRegistries.MOB_EFFECTS.getKey(c.getEffect()) + " amp " + c.getAmplifier() + ": canonical "
                    + MobEffectUtil.formatDuration(c, 1F).getString() + ", effective " + MobEffectUtil.formatDuration(e, 1F).getString());
        }
        if (!PotionContentsService.effectsSupported(held) && meta.isPresent()) say(ctx, "  this item's effects are its own (quality recorded only)");
        return 1;
    }

    private static int explainPotion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ItemStack held = ctx.getSource().getPlayerOrException().getMainHandItem();
        quality(ctx);
        boolean supported = PotionContentsService.effectsSupported(held);
        boolean bundle = com.otectus.immersivebrewing.potion.EffectPolicies.isBundle(PotionUtils.getPotion(held));
        for (MobEffectInstance effect : PotionUtils.getPotion(held).getEffects()) {
            var treatment = com.otectus.immersivebrewing.potion.EffectPolicies.treatment(effect.getEffect(), BrewingDefinitions.server());
            say(ctx, ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()) + ": policy " + treatment.policy() + ", selector " + treatment.layer()
                    + ", " + (!supported ? "item's own delivery / metadata only" : bundle ? "mixed bundle: unchanged"
                    : effect.isInfiniteDuration() ? "infinite duration: unchanged" : treatment.scales()
                    ? "duration curve 0.80 + 0.004Q, clamped to " + treatment.minimum() + ".." + treatment.maximum() : "unchanged duration and amplifier"));
        }
        if (!PotionUtils.getCustomEffects(held).isEmpty()) say(ctx, "Foreign custom effects are preserved exactly, without quality scaling.");
        return 1;
    }

    private static int explainPlan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String text = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "key");
        var key = com.otectus.immersivebrewing.recipe.PlanKey.parse(text);
        if (key == null) { say(ctx, Component.translatable("notice.immersive_brewing.plan_invalid")); return 0; }
        PlanIndex index = PlanIndex.get(player.server.getRecipeManager());
        ItemStack input = index.inputForKey(key, player.getMainHandItem());
        List<ItemStack> tray = player.containerMenu instanceof com.otectus.immersivebrewing.menu.KettleMenu menu
                && com.otectus.immersivebrewing.menu.StationMenu.openStation(player, menu.pos()) instanceof com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity kettle
                ? kettle.trayItems() : List.of();
        BrewPlan plan = index.resolve(key, input, tray);
        if (plan == null) {
            say(ctx, Component.translatable("notice.immersive_brewing.plan_invalid"));
            for (var part : key.parts()) index.report().stream().filter(e -> e.recipe().equals(part.source()))
                    .forEach(e -> say(ctx, e.recipe() + ": " + e.status() + " — " + e.reason()));
            say(ctx, "Contextual steps require the currently open tray; missing or changed transformations are never guessed.");
            return 0;
        }
        for (var step : plan.steps()) {
            var adapter = com.otectus.immersivebrewing.recipe.adapter.AdapterRegistry.get(step.adapter(), BrewingDefinitions.server());
            say(ctx, step.source() + ": adapter " + step.adapter() + " v" + step.adapterVersion() + "; "
                    + ForgeRegistries.ITEMS.getKey(step.before().getItem()) + potion(step.before()) + " + " + ForgeRegistries.ITEMS.getKey(step.reagent())
                    + " -> " + ForgeRegistries.ITEMS.getKey(step.after().getItem()) + potion(step.after())
                    + "; quality " + (adapter == null ? "adapter unavailable" : adapter.contentsPolicy(step.after())));
        }
        say(ctx, "Quick finishing: " + com.otectus.immersivebrewing.recipe.QuickFinishing.eligible(plan)
                + "; contextual: " + plan.contextual() + "; generation: " + index.generation());
        return 1;
    }

    private static int sessionList(CommandContext<CommandSourceStack> ctx) {
        List<ActiveSession> sessions = SessionManager.all();
        say(ctx, sessions.size() + " active session(s)");
        for (ActiveSession s : sessions) {
            say(ctx, " - " + s.playerName() + ": " + s.sim().kind() + " at " + s.pos().toShortString() + " in " + s.station().dimension().location()
                    + ", step " + s.sim().step() + "/" + s.sim().totalSteps() + (s.running() ? "" : " (count-in)") + (s.paused() ? " (paused)" : "")
                    + ", buffer " + s.bufferTicks());
        }
        return sessions.size();
    }

    private static int sessionSeal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (SessionManager.byPlayer(target.getUUID()).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(target.getGameProfile().getName() + " has no active session"));
            return 0;
        }
        SessionManager.sealPlayer(target, "session.immersive_brewing.end.operator");
        say(ctx, "Sealed " + target.getGameProfile().getName() + "'s session.");
        return 1;
    }

    private static int recover(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        if (!(ctx.getSource().getLevel().getBlockEntity(pos) instanceof StationBlockEntity station) || station.ledger() == null) {
            ctx.getSource().sendFailure(Component.literal("No held job at " + pos.toShortString()));
            return 0;
        }
        station.revalidateRecovery();
        JobLedger l = station.ledger();
        say(ctx, "Job " + l.jobId() + " is " + l.state() + (l.recoveryReason() != null ? " (" + l.recoveryReason() + ")" : "")
                + ". Nothing was issued; a sealed job resumes from the station.");
        return 1;
    }

    private ImmersiveBrewingCommand() {}
}
