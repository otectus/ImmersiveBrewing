package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.menu.StationMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.CatalogQueryC2S;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Server-thread search scheduler: at most 256 candidate inspections per tick across all viewers, with cached catalogs.
 * Filters run before paging; contextual handlers are deliberately not advertised as exhaustively enumerable. */
public final class CatalogSearch {
    public static final int PAGE_SIZE = 24;
    private static final class Pending {
        final ServerPlayer player;
        final CatalogQueryC2S query;
        final PlanIndex index;
        final PlanIndex.Catalog catalog;
        final List<BrewPlan> matching = new ArrayList<>();
        final Set<Long> destinations = new HashSet<>();
        int scanned;
        boolean contextualChecked;
        Pending(ServerPlayer player, CatalogQueryC2S query, PlanIndex index, PlanIndex.Catalog catalog) {
            this.player = player; this.query = query; this.index = index; this.catalog = catalog;
        }
        boolean done() { return catalog.done() && scanned >= catalog.plans().size() && contextualChecked; }
    }
    private static final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
    private record Selection(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos pos,PlanKey key,ItemStack input) {}
    private static final Map<UUID,Selection> selected=new HashMap<>();
    public static ItemStack selectedInput(ServerPlayer player,BrewersKettleBlockEntity kettle,PlanKey key,int count) {
        var rack=kettle.queryRack(count);
        if(!kettle.rack().input().isEmpty())return rack.input();
        Selection selection=selected.get(player.getUUID());
        return selection!=null&&selection.dimension().equals(player.level().dimension())&&selection.pos().equals(kettle.getBlockPos())&&selection.key().equals(key)
                ?selection.input().copy():rack.input();
    }
    public static void select(ServerPlayer player,BrewersKettleBlockEntity kettle,PlanKey key,ItemStack input,int servings) {
        var index=PlanIndex.get(player.server.getRecipeManager());
        BrewPlan plan=index.resolve(key,input,kettle.trayItems());
        if(plan==null) { ModNetwork.notice(player,net.minecraft.network.chat.Component.translatable("notice.immersive_brewing.plan_invalid"),true);return; }
        var entry=kettle.describePlan(player,plan,servings);
        if(entry==null)return;
        pending.remove(player.getUUID());
        selected.put(player.getUUID(),new Selection(player.level().dimension(),kettle.getBlockPos(),key,input.copyWithCount(1)));
        ModNetwork.sendTo(player,new PlanListS2C(kettle.getBlockPos(),input.copyWithCount(1),servings,null,List.of(entry),index.generation(),PlanListS2C.SELECTED_ROUTE,0,1,false,0,""));
    }
    public static void repeat(ServerPlayer player,String text,ItemStack input,int servings) {
        if(!(player.containerMenu instanceof com.otectus.immersivebrewing.menu.KettleMenu menu)
                || !(StationMenu.openStation(player,menu.pos()) instanceof BrewersKettleBlockEntity kettle)) {
            ModNetwork.notice(player,net.minecraft.network.chat.Component.translatable("staging.immersive_brewing.open_station"),true);return;
        }
        PlanKey key=PlanKey.parse(text);if(key!=null)select(player,kettle,key,input,servings);
    }
    public static void submit(ServerPlayer player, CatalogQueryC2S query) {
        if (!(StationMenu.openStation(player, query.pos()) instanceof BrewersKettleBlockEntity kettle)) return;
        PlanIndex index = PlanIndex.get(player.server.getRecipeManager());
        var rack = kettle.queryRack(query.servings());
        if (rack.problem()!=null) { ModNetwork.sendTo(player,new PlanListS2C(query.pos(),rack.input(),rack.servings(),rack.problem(),List.of())); return; }
        Pending p = new Pending(player,query,index,index.catalog(rack.input()));
        pending.put(player.getUUID(),p);
    }
    public static void clear() { pending.clear(); selected.clear(); }
    public static void leave(ServerPlayer player) { pending.remove(player.getUUID());selected.remove(player.getUUID()); }
    public static void tick(MinecraftServer server) {
        int remaining = 256;
        var it = pending.entrySet().iterator();
        List<Pending> finished = new ArrayList<>();
        while(it.hasNext() && remaining > 0) {
            Pending p=it.next().getValue();
            if (p.player.isRemoved() || !(StationMenu.openStation(p.player,p.query.pos()) instanceof BrewersKettleBlockEntity)) { it.remove(); continue; }
            if (p.index!=PlanIndex.get(server.getRecipeManager())) { it.remove(); continue; }
            int slice = Math.min(64,remaining); p.catalog.advance(slice); remaining-=slice;
            // Inventory filtering can copy stacks. Bound it separately from graph discovery.
            if (StationMenu.openStation(p.player, p.query.pos()) instanceof BrewersKettleBlockEntity kettle) {
                int stop = Math.min(p.catalog.plans().size(), p.scanned + 32);
                while (p.scanned < stop) consider(p, kettle, p.catalog.plans().get(p.scanned++));
                if (p.catalog.done() && p.scanned == p.catalog.plans().size() && !p.contextualChecked) {
                    var rack = kettle.queryRack(p.query.servings());
                    for (BrewPlan plan : p.index.contextualPlans(rack.input(), kettle.trayItems(), p.catalog.plans())) consider(p, kettle, plan);
                    p.contextualChecked = true;
                }
            }
            if (p.done() || server.getTickCount() % 10 == 0) finished.add(p);
            if (p.done()) it.remove();
        }
        // Rotate waiters, so a large search cannot starve other viewers.
        if(!pending.isEmpty()) { UUID first=pending.keySet().iterator().next(); Pending p=pending.remove(first); pending.put(first,p); }
        for(Pending p:finished) send(p);
    }
    private static void send(Pending p) {
        if (!(StationMenu.openStation(p.player,p.query.pos()) instanceof BrewersKettleBlockEntity kettle)) return;
        var rack=kettle.queryRack(p.query.servings()); if(rack.problem()!=null) return;
        int total=p.matching.size(); int from=Math.min(total,p.query.page()*PAGE_SIZE);
        List<PlanListS2C.Entry> entries=new ArrayList<>();
        for(BrewPlan plan:p.matching.subList(from,Math.min(total,from+PAGE_SIZE))) {
            var entry=kettle.describePlan(p.player,plan,rack.servings()); if(entry!=null) entries.add(entry);
        }
        ModNetwork.sendTo(p.player,new PlanListS2C(kettle.getBlockPos(),rack.input(),rack.servings(),null,entries,
                p.index.generation(),p.query.request(),p.query.page(),total,!p.done() || p.catalog.budgetLimited(),
                p.catalog.omittedAlternatives(),p.query.text()));
    }
    private static void consider(Pending p, BrewersKettleBlockEntity kettle, BrewPlan plan) {
        String q = p.query.text().toLowerCase(Locale.ROOT).trim();
        int servings = kettle.queryRack(p.query.servings()).servings();
        if (!q.isEmpty() && !matches(plan, q, p.query.aliases())) return;
        if ((p.query.flags() & 1) != 0 && kettle.check(plan, servings).missing() != null) return;
        if ((p.query.flags() & 2) != 0 && !com.otectus.immersivebrewing.blockentity.BatchStaging.canPrepare(kettle, p.player, plan, servings)) return;
        if ((p.query.flags() & 4) != 0 && !favorite(p.player, plan.key())) return;
        boolean scales = PotionContentsService.durationCanScale(plan.output(), com.otectus.immersivebrewing.data.BrewingDefinitions.server());
        if ((p.query.flags() & 8) != 0 && !scales || (p.query.flags() & 16) != 0 && scales) return;
        if (!p.query.alternatives() && !p.destinations.add(PotionContentsService.fingerprint(plan.output()))) return;
        p.matching.add(plan);
    }
    private static boolean favorite(ServerPlayer player,PlanKey key) {
        var favorites=com.otectus.immersivebrewing.guide.PlayerGuide.data(player).getList("favorites",10);
        for(int i=0;i<favorites.size();i++) if(favorites.getCompound(i).getString("plan").equals(key.serialize())
                || favorites.getCompound(i).getString("key").equals(key.serialize())) return true;
        return false;
    }
    private static boolean matches(BrewPlan plan,String query,List<String> aliases) {
        StringBuilder text=new StringBuilder(plan.output().getHoverName().getString()).append(' ')
                .append(ForgeRegistries.ITEMS.getKey(plan.output().getItem())).append(' ')
                .append(ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(plan.output())));
        for(var effect:PotionUtils.getMobEffects(plan.output())) text.append(' ').append(ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()));
        for(var step:plan.steps()) text.append(' ').append(step.source()).append(' ').append(ForgeRegistries.ITEMS.getKey(step.reagent()))
                .append(' ').append(new ItemStack(step.reagent()).getHoverName().getString());
        String hay=text.toString().toLowerCase(Locale.ROOT);
        return hay.contains(query) || aliases.stream().anyMatch(hay::contains);
    }
    private CatalogSearch() {}
}
