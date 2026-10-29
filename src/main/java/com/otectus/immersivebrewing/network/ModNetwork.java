package com.otectus.immersivebrewing.network;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.network.packet.DefinitionsSyncS2C;
import com.otectus.immersivebrewing.network.packet.GuideSyncS2C;
import com.otectus.immersivebrewing.network.packet.InputFramesC2S;
import com.otectus.immersivebrewing.network.packet.LeaveSessionC2S;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import com.otectus.immersivebrewing.network.packet.OpenPracticeS2C;
import com.otectus.immersivebrewing.network.packet.PauseSessionC2S;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.network.packet.SessionEndS2C;
import com.otectus.immersivebrewing.network.packet.SessionResultS2C;
import com.otectus.immersivebrewing.network.packet.SessionStartS2C;
import com.otectus.immersivebrewing.network.packet.SetLabelC2S;
import com.otectus.immersivebrewing.network.packet.StartBrewC2S;
import com.otectus.immersivebrewing.network.packet.StateSnapshotS2C;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.network.packet.StationNoticeS2C;
import com.otectus.immersivebrewing.network.packet.CatalogQueryC2S;
import com.otectus.immersivebrewing.network.packet.SelectRecipeC2S;
import com.otectus.immersivebrewing.network.packet.SelectPlanC2S;
import com.otectus.immersivebrewing.network.packet.AlembicGroupC2S;
import com.otectus.immersivebrewing.network.packet.CatalogSupportS2C;
import com.otectus.immersivebrewing.network.packet.BatchActionC2S;
import com.otectus.immersivebrewing.network.packet.BatchPreviewS2C;
import com.otectus.immersivebrewing.network.packet.CarrierSettingsC2S;
import com.otectus.immersivebrewing.network.packet.EquipCarrierSelectedC2S;
import com.otectus.immersivebrewing.network.packet.RackLabelC2S;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Small, purpose-specific packets (spec 21). Clients send intent: a plan key, a station button, input frames, a pause,
 * a label. The server sends validated plans, negotiated parameters, snapshots, results and reasons. No client packet
 * carries an item, a score, an outcome or an effect list. The protocol is required on both sides.
 */
public final class ModNetwork {
    public static final String VERSION = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ImmersiveBrewing.id("main"), () -> VERSION,
            VERSION::equals, VERSION::equals);

    /** Test hook: receives every server-to-client message sent to a fake player (automated tests only). */
    public static volatile BiConsumer<ServerPlayer, Object> fakePlayerSink;

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(SelectPlanC2S.class,id++,NetworkDirection.PLAY_TO_SERVER).encoder(SelectPlanC2S::encode).decoder(SelectPlanC2S::decode).consumerMainThread(SelectPlanC2S::handle).add();
        CHANNEL.messageBuilder(AlembicGroupC2S.class,id++,NetworkDirection.PLAY_TO_SERVER).encoder(AlembicGroupC2S::encode).decoder(AlembicGroupC2S::decode).consumerMainThread(AlembicGroupC2S::handle).add();
        CHANNEL.messageBuilder(SelectRecipeC2S.class,id++,NetworkDirection.PLAY_TO_SERVER).encoder(SelectRecipeC2S::encode).decoder(SelectRecipeC2S::decode).consumerMainThread(SelectRecipeC2S::handle).add();
        CHANNEL.messageBuilder(CatalogQueryC2S.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(CatalogQueryC2S::encode).decoder(CatalogQueryC2S::decode).consumerMainThread(CatalogQueryC2S::handle).add();
        CHANNEL.messageBuilder(CatalogSupportS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(CatalogSupportS2C::encode).decoder(CatalogSupportS2C::decode).consumerMainThread(CatalogSupportS2C::handle).add();
        CHANNEL.messageBuilder(BatchActionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(BatchActionC2S::encode).decoder(BatchActionC2S::decode).consumerMainThread(BatchActionC2S::handle).add();
        CHANNEL.messageBuilder(BatchPreviewS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(BatchPreviewS2C::encode).decoder(BatchPreviewS2C::decode).consumerMainThread(BatchPreviewS2C::handle).add();
        CHANNEL.messageBuilder(CarrierSettingsC2S.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(CarrierSettingsC2S::encode).decoder(CarrierSettingsC2S::decode).consumerMainThread(CarrierSettingsC2S::handle).add();
        CHANNEL.messageBuilder(EquipCarrierSelectedC2S.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(EquipCarrierSelectedC2S::encode).decoder(EquipCarrierSelectedC2S::decode).consumerMainThread(EquipCarrierSelectedC2S::handle).add();
        CHANNEL.messageBuilder(RackLabelC2S.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(RackLabelC2S::encode).decoder(RackLabelC2S::decode).consumerMainThread(RackLabelC2S::handle).add();
        CHANNEL.messageBuilder(InputFramesC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(InputFramesC2S::encode).decoder(InputFramesC2S::decode).consumerMainThread(InputFramesC2S::handle).add();
        CHANNEL.messageBuilder(LeaveSessionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(LeaveSessionC2S::encode).decoder(LeaveSessionC2S::decode).consumerMainThread(LeaveSessionC2S::handle).add();
        CHANNEL.messageBuilder(StationActionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StationActionC2S::encode).decoder(StationActionC2S::decode).consumerMainThread(StationActionC2S::handle).add();
        CHANNEL.messageBuilder(StartBrewC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StartBrewC2S::encode).decoder(StartBrewC2S::decode).consumerMainThread(StartBrewC2S::handle).add();
        CHANNEL.messageBuilder(SetLabelC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetLabelC2S::encode).decoder(SetLabelC2S::decode).consumerMainThread(SetLabelC2S::handle).add();
        CHANNEL.messageBuilder(NotebookPrefsC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(NotebookPrefsC2S::encode).decoder(NotebookPrefsC2S::decode).consumerMainThread(NotebookPrefsC2S::handle).add();
        CHANNEL.messageBuilder(PauseSessionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PauseSessionC2S::encode).decoder(PauseSessionC2S::decode).consumerMainThread(PauseSessionC2S::handle).add();

        CHANNEL.messageBuilder(PlanListS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlanListS2C::encode).decoder(PlanListS2C::decode).consumerMainThread(PlanListS2C::handle).add();
        CHANNEL.messageBuilder(SessionStartS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SessionStartS2C::encode).decoder(SessionStartS2C::decode).consumerMainThread(SessionStartS2C::handle).add();
        CHANNEL.messageBuilder(StateSnapshotS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StateSnapshotS2C::encode).decoder(StateSnapshotS2C::decode).consumerMainThread(StateSnapshotS2C::handle).add();
        CHANNEL.messageBuilder(SessionEndS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SessionEndS2C::encode).decoder(SessionEndS2C::decode).consumerMainThread(SessionEndS2C::handle).add();
        CHANNEL.messageBuilder(SessionResultS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SessionResultS2C::encode).decoder(SessionResultS2C::decode).consumerMainThread(SessionResultS2C::handle).add();
        CHANNEL.messageBuilder(StationNoticeS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StationNoticeS2C::encode).decoder(StationNoticeS2C::decode).consumerMainThread(StationNoticeS2C::handle).add();
        CHANNEL.messageBuilder(DefinitionsSyncS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DefinitionsSyncS2C::encode).decoder(DefinitionsSyncS2C::decode).consumerMainThread(DefinitionsSyncS2C::handle).add();
        CHANNEL.messageBuilder(GuideSyncS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GuideSyncS2C::encode).decoder(GuideSyncS2C::decode).consumerMainThread(GuideSyncS2C::handle).add();
        CHANNEL.messageBuilder(OpenPracticeS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenPracticeS2C::encode).decoder(OpenPracticeS2C::decode).consumerMainThread(OpenPracticeS2C::handle).add();
    }

    public static void sendTo(ServerPlayer player, Object message) {
        if (player instanceof FakePlayer) {
            BiConsumer<ServerPlayer, Object> sink = fakePlayerSink;
            if (sink != null) sink.accept(player, message);
            return;
        }
        if (player.connection == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    public static void notice(ServerPlayer player, Component text, boolean failure) {
        sendTo(player, new StationNoticeS2C(text, failure));
    }

    /** Sends the active dataset in bounded chunks. */
    public static void syncDefinitions(ServerPlayer player, BrewingDefinitions defs) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        defs.write(buf);
        byte[] all = new byte[buf.readableBytes()];
        buf.readBytes(all);
        buf.release();
        int chunks = Math.max(1, (all.length + DefinitionsSyncS2C.CHUNK - 1) / DefinitionsSyncS2C.CHUNK);
        if (chunks > DefinitionsSyncS2C.MAX_CHUNKS) {
            ImmersiveBrewing.LOGGER.error("Immersive Brewing: brewing data is too large to sync ({} bytes); clients keep their previous copy", all.length);
            return;
        }
        for (int i = 0; i < chunks; i++) {
            int from = i * DefinitionsSyncS2C.CHUNK;
            int to = Math.min(all.length, from + DefinitionsSyncS2C.CHUNK);
            sendTo(player, new DefinitionsSyncS2C(defs.generation(), i, chunks, Arrays.copyOfRange(all, from, to)));
        }
        var index=com.otectus.immersivebrewing.recipe.PlanIndex.get(player.server.getRecipeManager());
        var report=index.report();
        int pages=Math.max(1,(report.size()+255)/256);
        if(pages<=256) for(int i=0;i<pages;i++) sendTo(player,new CatalogSupportS2C(index.generation(),i,pages,
                !com.otectus.immersivebrewing.event.CommonEvents.tippedArrowOverrideActive(),
                report.subList(i*256,Math.min(report.size(),(i+1)*256))));
    }

    private ModNetwork() {}
}
