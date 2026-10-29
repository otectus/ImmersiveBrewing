package com.otectus.immersivebrewing.network.packet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record BatchPreviewS2C(BlockPos pos,String plan,long revision,Component problem,int extraction,int paddleUses,List<ItemStack> remainders) {
    private static final int MAX_REMAINDERS=32;
    public BatchPreviewS2C {
        if(remainders.size()>MAX_REMAINDERS)throw new IllegalArgumentException("batch remainder count");
        remainders=remainders.stream().map(ItemStack::copy).toList();
    }
    public void encode(FriendlyByteBuf b) {
        b.writeBlockPos(pos); b.writeUtf(plan,2048); b.writeLong(revision); b.writeBoolean(problem!=null); if(problem!=null)b.writeComponent(problem);
        b.writeVarInt(extraction); b.writeVarInt(paddleUses); b.writeVarInt(remainders.size()); for(ItemStack stack:remainders)b.writeItem(stack);
    }
    public static BatchPreviewS2C decode(FriendlyByteBuf b) {
        BlockPos pos=b.readBlockPos(); String plan=b.readUtf(2048); long revision=b.readLong(); Component problem=b.readBoolean()?b.readComponent():null;
        int extraction=b.readVarInt(), paddleUses=b.readVarInt(), count=b.readVarInt();
        if(count<0||count>MAX_REMAINDERS)throw new IllegalArgumentException("batch remainder count");
        List<ItemStack> remainders=new ArrayList<>(count); for(int i=0;i<count;i++)remainders.add(b.readItem());
        return new BatchPreviewS2C(pos,plan,revision,problem,extraction,paddleUses,remainders);
    }
    public void handle(Supplier<NetworkEvent.Context> ctx) { DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->{
        if(net.minecraft.client.Minecraft.getInstance().screen instanceof com.otectus.immersivebrewing.client.screen.KettlePlanScreen screen) screen.preview(this);
    }); }
}
