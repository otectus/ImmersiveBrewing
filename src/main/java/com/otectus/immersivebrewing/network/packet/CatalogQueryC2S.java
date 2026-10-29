package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.recipe.CatalogSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.List;
import java.util.function.Supplier;

/** Bounded search intent. Localized search aliases are hints, never recipe authority. */
public record CatalogQueryC2S(BlockPos pos, int request, String text, List<String> aliases, int flags,
                              int page, boolean alternatives, int servings) {
    public CatalogQueryC2S {
        if (text.length() > 128 || aliases.size() > 256 || aliases.stream().anyMatch(s -> s.length() > 256)
                || page < 0 || page > 4096 || servings < 1 || servings > 6) throw new IllegalArgumentException("catalog query bounds");
        aliases = List.copyOf(aliases);
    }
    public void encode(FriendlyByteBuf b) {
        b.writeBlockPos(pos); b.writeVarInt(request); b.writeUtf(text,128); b.writeVarInt(aliases.size());
        aliases.forEach(s -> b.writeUtf(s,256)); b.writeVarInt(flags); b.writeVarInt(page); b.writeBoolean(alternatives); b.writeByte(servings);
    }
    public static CatalogQueryC2S decode(FriendlyByteBuf b) {
        BlockPos pos = b.readBlockPos(); int request = b.readVarInt(); String text = b.readUtf(128);
        int n = b.readVarInt(); if (n < 0 || n > 256) throw new IllegalArgumentException("catalog aliases");
        java.util.ArrayList<String> aliases = new java.util.ArrayList<>();
        for (int i=0;i<n;i++) aliases.add(b.readUtf(256));
        return new CatalogQueryC2S(pos,request,text,aliases,b.readVarInt(),b.readVarInt(),b.readBoolean(),b.readUnsignedByte());
    }
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getSender()!=null) CatalogSearch.submit(ctx.get().getSender(),this);
    }
}
