package com.otectus.immersivebrewing.network.packet;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.menu.StationMenu;
import com.otectus.immersivebrewing.recipe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;
public record SelectPlanC2S(BlockPos pos,PlanKey key,int count) {
    public void encode(FriendlyByteBuf b){b.writeBlockPos(pos);key.write(b);b.writeByte(count);}
    public static SelectPlanC2S decode(FriendlyByteBuf b){return new SelectPlanC2S(b.readBlockPos(),PlanKey.read(b),b.readUnsignedByte());}
    public void handle(Supplier<NetworkEvent.Context> ctx){
        var player=ctx.get().getSender();if(player==null||count<1||count>6)return;
        if(!(StationMenu.openStation(player,pos) instanceof BrewersKettleBlockEntity kettle))return;
        var index=PlanIndex.get(player.server.getRecipeManager());
        CatalogSearch.select(player,kettle,key,index.inputForKey(key,kettle.queryRack(count).input()),count);
    }
}
