package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.blockentity.*;
import com.otectus.immersivebrewing.menu.StationMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.recipe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record BatchActionC2S(BlockPos pos,Action action,String plan,int count,BatchStaging.Kits kits,int carrier,long revision) {
    public enum Action { PREVIEW, PREPARE, FINISH, COLLECT, RETURN }
    public void encode(FriendlyByteBuf b) { b.writeBlockPos(pos);b.writeEnum(action);b.writeUtf(plan,2048);b.writeByte(count);b.writeEnum(kits);b.writeVarInt(carrier);b.writeLong(revision); }
    public static BatchActionC2S decode(FriendlyByteBuf b) { return new BatchActionC2S(b.readBlockPos(),b.readEnum(Action.class),b.readUtf(2048),b.readUnsignedByte(),b.readEnum(BatchStaging.Kits.class),b.readVarInt(),b.readLong()); }
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player=ctx.get().getSender(); if(player==null||count<1||count>6||carrier< -1||carrier>=36) return;
        if(!(StationMenu.openStation(player,pos) instanceof BrewersKettleBlockEntity kettle))return;
        if(action==Action.COLLECT||action==Action.RETURN) {
            boolean ok=BatchStaging.collect(kettle,player,action==Action.RETURN);
            ModNetwork.notice(player,Component.translatable(ok?"staging.immersive_brewing.collected":"staging.immersive_brewing.inventory_full"),!ok);return;
        }
        PlanKey key=PlanKey.parse(plan); if(key==null)return;
        var rack=kettle.queryRack(count);
        BrewPlan resolved=PlanIndex.get(player.server.getRecipeManager()).resolve(key,CatalogSearch.selectedInput(player,kettle,key,count),kettle.trayItems());
        if(resolved==null) { ModNetwork.notice(player,Component.translatable("notice.immersive_brewing.plan_invalid"),true);return; }
        if(action==Action.FINISH) {
            if(revision!=BatchStaging.revision(kettle,player)) { ModNetwork.notice(player,Component.translatable("staging.immersive_brewing.changed"),true);return; }
            kettle.startFinishing(player,key);return;
        }
        var preview=action==Action.PREPARE?BatchStaging.prepare(kettle,player,resolved,count,kits,carrier,revision)
                :BatchStaging.preview(kettle,player,resolved,count,kits,carrier);
        ModNetwork.sendTo(player,new BatchPreviewS2C(pos,plan,preview.revision(),preview.problem(),preview.extraction(),preview.paddleUses(),preview.remainders()));
        if(action==Action.PREPARE) {
            ModNetwork.notice(player,preview.problem()==null?Component.translatable("staging.immersive_brewing.prepared"):preview.problem(),!preview.ready());
            if(preview.ready()) CatalogSearch.select(player,kettle,key,resolved.input(),count);
        }
    }
}
