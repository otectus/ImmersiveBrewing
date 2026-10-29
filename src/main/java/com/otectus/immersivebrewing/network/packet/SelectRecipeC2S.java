package com.otectus.immersivebrewing.network.packet;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.menu.StationMenu;
import com.otectus.immersivebrewing.recipe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import java.util.*;
import java.util.function.Supplier;
public record SelectRecipeC2S(BlockPos pos,ResourceLocation recipe,int count) {
    public void encode(FriendlyByteBuf b){b.writeBlockPos(pos);b.writeResourceLocation(recipe);b.writeByte(count);}
    public static SelectRecipeC2S decode(FriendlyByteBuf b){return new SelectRecipeC2S(b.readBlockPos(),b.readResourceLocation(),b.readUnsignedByte());}
    public void handle(Supplier<NetworkEvent.Context> ctx){
        var player=ctx.get().getSender();if(player==null||count<1||count>6)return;
        if(!(StationMenu.openStation(player,pos) instanceof BrewersKettleBlockEntity kettle))return;
        var found=player.server.getRecipeManager().byKey(recipe);
        if(found.isEmpty()||!(found.get() instanceof InfusionRecipe infusion))return;
        var input=infusion.generic()?kettle.queryRack(count).input():infusion.input().stack(null);
        var key=new PlanKey(List.of(new PlanKey.Part(recipe,0,infusion.steps().size(),recipe)));
        CatalogSearch.select(player,kettle,key,input,count);
    }
}
