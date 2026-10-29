package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.recipe.PlanIndex;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.*;
import java.util.function.Supplier;

/** Server recipe support, delivered atomically in bounded chunks, independent of a client's source-mod config. */
public record CatalogSupportS2C(int generation,int page,int pages,boolean arrowConflict,List<PlanIndex.Entry> entries) {
    public void encode(FriendlyByteBuf b) {
        b.writeVarInt(generation); b.writeVarInt(page); b.writeVarInt(pages); b.writeBoolean(arrowConflict); b.writeVarInt(entries.size());
        for(var e:entries) { b.writeResourceLocation(e.recipe()); b.writeEnum(e.status()); b.writeUtf(e.reason(),2048); }
    }
    public static CatalogSupportS2C decode(FriendlyByteBuf b) {
        int generation=b.readVarInt(),page=b.readVarInt(),pages=b.readVarInt(); boolean conflict=b.readBoolean(); int n=b.readVarInt();
        if(page<0||pages<1||pages>256||page>=pages||n<0||n>256) throw new IllegalArgumentException("catalog support bounds");
        List<PlanIndex.Entry> entries=new ArrayList<>();
        for(int i=0;i<n;i++) entries.add(new PlanIndex.Entry(b.readResourceLocation(),b.readEnum(PlanIndex.Status.class),b.readUtf(2048),List.of()));
        return new CatalogSupportS2C(generation,page,pages,conflict,entries);
    }
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.otectus.immersivebrewing.client.ServerCatalog.accept(this));
    }
}
