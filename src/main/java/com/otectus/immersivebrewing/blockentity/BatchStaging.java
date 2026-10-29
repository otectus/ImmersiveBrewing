package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.item.*;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.registry.ModTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.Predicate;

/** Copy/validate/commit transaction shared by the plan screen and JEI. No external inventory or capability is mutated
 * during preview; the single server-thread commit runs only against an unchanged inventory fingerprint. */
public final class BatchStaging {
    public enum Kits { RAW, PRESERVE_BEST, SELECTED }
    public record Preview(long revision, Component problem, int extraction, int paddleUses, List<ItemStack> remainders) {
        public Preview { remainders=remainders.stream().map(ItemStack::copy).toList(); }
        public boolean ready() { return problem==null; }
    }
    private record Source(ItemStack stack, boolean station, boolean carrier) {}
    private record Draft(List<ItemStack> station, List<ItemStack> player, List<ItemStack> carrier, Preview preview) {}
    public static long revision(BrewersKettleBlockEntity kettle, ServerPlayer player) {
        long h=17;
        for(int i=0;i<kettle.inventory().getSlots();i++) h=31*h+kettle.inventory().getStackInSlot(i).save(new net.minecraft.nbt.CompoundTag()).hashCode();
        for(int i=0;i<36;i++) h=31*h+player.getInventory().getItem(i).save(new net.minecraft.nbt.CompoundTag()).hashCode();
        return 31*h+kettle.fuelCharges();
    }
    public static Preview preview(BrewersKettleBlockEntity kettle,ServerPlayer player,BrewPlan plan,int count,Kits mode,int carrier) {
        return draft(kettle,player,plan,count,mode,carrier).preview();
    }
    public static boolean canPrepare(BrewersKettleBlockEntity kettle,ServerPlayer player,BrewPlan plan,int count) {
        return preview(kettle,player,plan,count,Kits.RAW,-1).ready();
    }
    public static Preview prepare(BrewersKettleBlockEntity kettle,ServerPlayer player,BrewPlan plan,int count,Kits mode,int carrier,long expected) {
        Draft draft=draft(kettle,player,plan,count,mode,carrier);
        if(!draft.preview().ready()) return draft.preview();
        if(expected!=draft.preview().revision()) return fail(draft.preview().revision(),"staging.immersive_brewing.changed").preview();
        commit(kettle,player,draft,carrier);
        return draft.preview();
    }
    private static Draft fail(long revision,String key,Object... args) {
        return new Draft(List.of(),List.of(),List.of(),new Preview(revision,Component.translatable(key,args),50,0,List.of()));
    }
    private static Draft fail(long revision,List<ItemStack> remainders,String key,Object... args) {
        return new Draft(List.of(),List.of(),List.of(),new Preview(revision,Component.translatable(key,args),50,0,remainders));
    }
    private static Draft draft(BrewersKettleBlockEntity kettle,ServerPlayer player,BrewPlan plan,int count,Kits mode,int carrierSlot) {
        long revision=revision(kettle,player);
        if(kettle.ledger()!=null || com.otectus.immersivebrewing.session.SessionManager.stationBusy(kettle)) return fail(revision,"notice.immersive_brewing.busy");
        if(count<1 || count>Math.min(plan.servingLimit(),ServerConfig.maxBatchServings())) return fail(revision,"plan.immersive_brewing.missing.servings",plan.servingLimit());
        List<ItemStack> station=new ArrayList<>(), inventory=new ArrayList<>();
        for(int i=0;i<kettle.inventory().getSlots();i++) station.add(kettle.inventory().getStackInSlot(i).copy());
        for(int i=0;i<36;i++) inventory.add(player.getInventory().getItem(i).copy());
        boolean finishing = ServerConfig.get(ServerConfig.QUICK_FINISHING) && com.otectus.immersivebrewing.recipe.QuickFinishing.eligible(plan);
        for(int i=0;i<6;i++) if(!finishing && kettle.finished[i] && !station.get(i).isEmpty()) return fail(revision,"plan.immersive_brewing.problem.collect");
        boolean useCarrier=carrierSlot>=0 && carrierSlot<36 && inventory.get(carrierSlot).getItem() instanceof BatchCarrierItem;
        if(carrierSlot>=0 && !useCarrier) return fail(revision,"staging.immersive_brewing.carrier_missing");
        List<ItemStack> carrier=useCarrier?CarrierContents.read(inventory.get(carrierSlot)):new ArrayList<>();
        List<Source> pool=new ArrayList<>();
        for(int i=0;i<=BrewersKettleBlockEntity.PADDLE;i++) { pool.add(new Source(station.get(i),true,false)); station.set(i,ItemStack.EMPTY); }
        for(int i=0;i<inventory.size();i++) if(i!=carrierSlot) pool.add(new Source(inventory.get(i),false,false));
        for(ItemStack stack:carrier) pool.add(new Source(stack,false,true));
        ItemStack first=ItemStack.EMPTY;
        for(int i=0;i<count;i++) {
            final ItemStack group=first;
            ItemStack bottle=take(pool,s -> PotionContentsService.sameCanonical(s,plan.input())
                    && (group.isEmpty() || ItemStack.isSameItemSameTags(group,s)),1,true);
            if(bottle.isEmpty()) return fail(revision,"staging.immersive_brewing.bottles",count,plan.input().getHoverName());
            if(first.isEmpty()) first=bottle.copy(); station.set(i,bottle);
        }
        int extraction=0, units=0;
        List<ItemStack> consumedReagents=new ArrayList<>();
        // Allocate each transformation independently, exactly like the Kettle's reservation pass. A multi-group kit is
        // indivisible: overcoverage belongs to this step and is never silently carried into a later same-item step.
        for(BrewPlan.Step step:plan.steps()) {
            int need=step.batch().reagentCost(count);
            boolean preparable=BrewersKettleBlockEntity.preparable(com.otectus.immersivebrewing.data.BrewingDefinitions.server(),step.reagent(),step.spec().preparationProfile());
            if(mode!=Kits.RAW) {
                List<Source> kits=new ArrayList<>(pool);
                kits.sort(Comparator.comparingInt(s -> KitData.read(s.stack()).map(KitData::extraction).orElse(101)));
                for(Source src:kits) {
                    if(need<=0) break;
                    var kit=KitData.read(src.stack());
                    if(kit.isEmpty() || !kit.get().source().is(step.reagent()) || mode==Kits.SELECTED&&!src.station()) continue;
                    while(need>0&&!src.stack().isEmpty()) {
                        ItemStack one=src.stack().split(1);
                        ItemStack consumed=one.copy();
                        if(!insert(station,one,6,12,64)) return fail(revision,"staging.immersive_brewing.tray_space");
                        consumedReagents.add(consumed);
                        int used=kit.get().groups(); need-=used;
                        if(preparable) { extraction+=used*kit.get().extraction(); units+=used; }
                    }
                }
            }
            while(need>0) {
                ItemStack raw=take(pool,s->s.is(step.reagent())&&KitData.read(s).isEmpty(),need,false);
                if(raw.isEmpty()) return fail(revision,"plan.immersive_brewing.missing.reagent",need,new ItemStack(step.reagent()).getHoverName());
                ItemStack consumed=raw.copy();
                int used=raw.getCount(); if(!insert(station,raw,6,12,64)) return fail(revision,"staging.immersive_brewing.tray_space");
                consumedReagents.add(consumed);
                need-=used; if(preparable) { extraction+=50*used; units+=used; }
            }
        }
        int powder=Math.max(0,(plan.fuelCharges(count)-kettle.fuelCharges()+ServerConfig.fuelChargesPerBlazePowder()-1)/ServerConfig.fuelChargesPerBlazePowder());
        while(powder>0) {
            ItemStack fuel=take(pool,s->s.is(ModTags.BURNER_FUEL),powder,false);
            if(fuel.isEmpty()) return fail(revision,"plan.immersive_brewing.missing.fuel",plan.fuelCharges(count),kettle.fuelCharges());
            powder-=fuel.getCount(); if(!insert(station,fuel,12,13,64)) return fail(revision,"staging.immersive_brewing.tray_space");
        }
        ItemStack paddle=take(pool,s->s.is(ModTags.BREWING_PADDLES)&&(!s.isDamageableItem()||s.getDamageValue()<s.getMaxDamage()),1,false);
        if(paddle.isEmpty() && !finishing) return fail(revision,"plan.immersive_brewing.missing.paddle");
        station.set(13,paddle);
        List<ItemStack> remainders=BrewersKettleBlockEntity.owedRemainders(consumedReagents);
        List<ItemStack> shelf=new ArrayList<>(); for(ItemStack s:station) shelf.add(s.copy());
        for(ItemStack remainder:remainders) if(!insert(shelf,remainder.copy(),14,17,64))
            return fail(revision,remainders,"plan.immersive_brewing.missing.byproduct_room");
        // Preserve every unused station stack. Player stacks were edited only in this private draft.
        for(Source source:pool) if(source.station()&&!source.stack().isEmpty()) {
            ItemStack rest=source.stack().copy();
            if(!insert(inventory,rest,0,36,64)) return fail(revision,"staging.immersive_brewing.inventory_full");
        }
        return new Draft(station,inventory,carrier,new Preview(revision,null,units==0?50:Math.round((float)extraction/units),
                paddle.isDamageableItem()?paddle.getMaxDamage()-paddle.getDamageValue():-1,remainders));
    }
    private static ItemStack take(List<Source> pool,Predicate<ItemStack> match,int count,boolean allowCarrier) {
        for(Source s:pool) if(!s.stack().isEmpty() && (allowCarrier||!s.carrier()) && match.test(s.stack())) return s.stack().split(count);
        return ItemStack.EMPTY;
    }
    private static boolean insert(List<ItemStack> slots,ItemStack stack,int from,int to,int limit) {
        for(int pass=0;pass<2;pass++) for(int i=from;i<to&&!stack.isEmpty();i++) {
            ItemStack existing=slots.get(i);
            if(pass==0 && !existing.isEmpty()&&ItemStack.isSameItemSameTags(existing,stack)) {
                int n=Math.min(stack.getCount(),Math.max(0,Math.min(limit,existing.getMaxStackSize())-existing.getCount())); existing.grow(n); stack.shrink(n);
            } else if(pass==1&&existing.isEmpty()) { int n=Math.min(stack.getCount(),Math.min(limit,stack.getMaxStackSize())); slots.set(i,stack.split(n)); }
        }
        return stack.isEmpty();
    }
    private static void commit(BrewersKettleBlockEntity kettle,ServerPlayer player,Draft draft,int carrierSlot) {
        if(carrierSlot>=0) CarrierContents.write(draft.player().get(carrierSlot),draft.carrier());
        for(int i=0;i<36;i++) player.getInventory().setItem(i,draft.player().get(i));
        for(int i=0;i<draft.station().size();i++) kettle.putDirect(i,draft.station().get(i));
        player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); kettle.changed();
    }
    /** Collection/returns have a fixed, visible destination: the player's ordinary inventory. Full means no move. */
    public static boolean collect(BrewersKettleBlockEntity kettle,ServerPlayer player,boolean unused) {
        if(kettle.ledger()!=null) return false;
        List<ItemStack> slots=new ArrayList<>(), inventory=new ArrayList<>();
        for(int i=0;i<kettle.inventory().getSlots();i++) slots.add(kettle.inventory().getStackInSlot(i).copy());
        for(int i=0;i<36;i++) inventory.add(player.getInventory().getItem(i).copy());
        for(int i=0;i<slots.size();i++) {
            boolean take=unused?i<14&&!kettle.finished[i]:kettle.finished[i]||i>=14;
            if(take&&!slots.get(i).isEmpty() && !insert(inventory,slots.get(i),0,36,64)) return false;
        }
        commit(kettle,player,new Draft(slots,inventory,List.of(),new Preview(0,null,50,0,List.of())),-1); return true;
    }
    private BatchStaging() {}
}
