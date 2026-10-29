package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.screen.DistillationScreen;
import com.otectus.immersivebrewing.client.screen.KettleBrewScreen;
import com.otectus.immersivebrewing.client.screen.KettlePlanScreen;
import com.otectus.immersivebrewing.client.screen.PreparationScreen;
import com.otectus.immersivebrewing.client.screen.ResultScreen;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.network.packet.DefinitionsSyncS2C;
import com.otectus.immersivebrewing.network.packet.GuideSyncS2C;
import com.otectus.immersivebrewing.network.packet.OpenPracticeS2C;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.network.packet.SessionEndS2C;
import com.otectus.immersivebrewing.network.packet.SessionResultS2C;
import com.otectus.immersivebrewing.network.packet.SessionStartS2C;
import com.otectus.immersivebrewing.network.packet.StateSnapshotS2C;
import com.otectus.immersivebrewing.network.packet.StationNoticeS2C;
import com.otectus.immersivebrewing.session.SessionKind;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.io.ByteArrayOutputStream;

/** Client-side packet handling (reached only through {@code DistExecutor}). */
public final class ClientPacketHandler {
    private static int syncGeneration = -1;
    private static int syncNext;
    private static ByteArrayOutputStream syncBuffer = new ByteArrayOutputStream();

    public static void sessionStart(SessionStartS2C msg) {
        ClientSession session = ClientSession.start(msg.sessionId(), msg.kind(), msg.pos(), msg.params(), msg.state(), msg.countInTicks(),
                msg.resumed(), msg.display());
        open(session);
    }

    /** Opens the game screen for a session (productive or practice). */
    public static void open(ClientSession session) {
        Screen screen = switch (session.kind) {
            case BREWING -> new KettleBrewScreen(session);
            case PREPARATION -> new PreparationScreen(session);
            case DISTILLATION -> new DistillationScreen(session);
        };
        Minecraft.getInstance().setScreen(screen);
    }

    public static void snapshot(StateSnapshotS2C msg) {
        ClientSession session = ClientSession.active();
        if (session != null && session.id.equals(msg.sessionId())) session.reconcile(msg.step(), msg.state());
    }

    public static void sessionEnd(SessionEndS2C msg) {
        ClientSession session = ClientSession.active();
        if (session != null && session.id.equals(msg.sessionId())) {
            boolean screenOpen = Minecraft.getInstance().screen instanceof com.otectus.immersivebrewing.client.screen.GameScreen;
            session.end(msg.reason(), msg.sealed());
            if (!screenOpen) ClientSession.clear();
        }
    }

    public static void result(SessionResultS2C msg) {
        ClientSession session = ClientSession.active();
        if (session != null && session.id.equals(msg.sessionId())) {
            session.finish(msg.result());
            if (!(Minecraft.getInstance().screen instanceof com.otectus.immersivebrewing.client.screen.GameScreen)) {
                Minecraft.getInstance().setScreen(new ResultScreen(session));
            }
        }
    }

    public static void notice(StationNoticeS2C msg) {
        StationNotices.show(msg.message(), msg.failure());
    }

    public static void definitions(DefinitionsSyncS2C msg) {
        if (msg.index() == 0 || msg.generation() != syncGeneration) {
            syncGeneration = msg.generation();
            syncNext = 0;
            syncBuffer = new ByteArrayOutputStream();
        }
        if (msg.index() != syncNext || msg.count() > DefinitionsSyncS2C.MAX_CHUNKS) {
            ImmersiveBrewing.LOGGER.warn("Immersive Brewing: out-of-order definitions chunk {}/{}; waiting for a fresh sync", msg.index(), msg.count());
            syncNext = -1;
            return;
        }
        syncBuffer.writeBytes(msg.data());
        syncNext++;
        if (syncNext == msg.count()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(syncBuffer.toByteArray()));
            try {
                BrewingDefinitions.activateClient(BrewingDefinitions.read(buf));
            } catch (RuntimeException e) {
                ImmersiveBrewing.LOGGER.error("Immersive Brewing: could not read the synced brewing data", e);
            } finally {
                buf.release();
            }
            syncBuffer = new ByteArrayOutputStream();
        }
    }

    static void resetDefinitions() {
        ServerCatalog.reset();
        syncGeneration = -1;
        syncNext = 0;
        syncBuffer = new ByteArrayOutputStream();
    }

    public static void planList(PlanListS2C msg) {
        Screen current = Minecraft.getInstance().screen;
        if(current instanceof KettlePlanScreen plans) { plans.accept(msg); return; }
        Screen parent = current instanceof KettlePlanScreen plans ? plans.parent() : current;
        Minecraft.getInstance().setScreen(new KettlePlanScreen(parent, msg));
    }

    public static void guide(GuideSyncS2C msg) {
        ClientGuide.set(msg.guide());
    }

    public static void practice(OpenPracticeS2C msg) {
        ClientSession session = msg.params() != null && msg.display() != null
                ? ClientSession.practice(msg.kind(), msg.params(), msg.display()) : PracticeSetup.create(msg.kind(), msg.profile());
        if (session == null) {
            StationNotices.show(Component.translatable("notice.immersive_brewing.practice_unavailable"), true);
            return;
        }
        open(session);
    }

    public static void practice(SessionKind kind) {
        practice(new OpenPracticeS2C(kind, null));
    }

    private ClientPacketHandler() {}
}
