package com.otectus.immersivebrewing.interaction;

import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import net.minecraft.world.InteractionHand;

import java.util.UUID;

/**
 * A validated, decoded player intent for one station part (handoff 15). Built from {@code WorkshopIntentC2S} after the
 * envelope checks, or directly by server code and tests. Carries no item, score, recipe or elapsed time.
 *
 * @param job      the batch the client believes it is acting on ({@link #ANY_JOB} for idle-station actions); a stale job id
 *                 refuses job actions harmlessly
 * @param x,z      normalised work-plane coordinates {@code -1..1} (samples), or a carried object's position over the station
 * @param epoch    the working-view session the client was in ({@link #ANY_EPOCH} from server code); an intent from an earlier
 *                 view of the same player is stale and ignored
 * @param expected the revision the client last saw for what this action commits (the kettle's recipe page), or
 *                 {@link #NO_REVISION}
 * @param deep     the held tool's posture: worked down at the bottom of the material rather than near its surface
 * @param setup    the action also accepts the displayed recipe page (a kettle bottle tipped to start the batch)
 */
public record WorkshopIntent(int part, WorkshopAction action, int argument, double x, double z, boolean contact, Effort effort,
                             InteractionHand hand, boolean batch, UUID job, int sequence, int epoch, long expected, boolean deep,
                             boolean setup) {
    public static final UUID ANY_JOB = new UUID(0, 0);
    public static final int ANY_EPOCH = 0;
    public static final long NO_REVISION = -1;

    public static WorkshopIntent of(int part, WorkshopAction action, int argument) {
        return new WorkshopIntent(part, action, argument, 0, 0, false, Effort.NORMAL, InteractionHand.MAIN_HAND, false, ANY_JOB, 0,
                ANY_EPOCH, NO_REVISION, false, false);
    }

    public static WorkshopIntent sample(int part, double x, double z, boolean contact, Effort effort) {
        return new WorkshopIntent(part, WorkshopAction.SAMPLE, 0, x, z, contact, effort, InteractionHand.MAIN_HAND, false, ANY_JOB, 0,
                ANY_EPOCH, NO_REVISION, false, false);
    }

    public WorkshopIntent batched() {
        return new WorkshopIntent(part, action, argument, x, z, contact, effort, hand, true, job, sequence, epoch, expected, deep, setup);
    }

    public WorkshopIntent withEffort(Effort value) {
        return new WorkshopIntent(part, action, argument, x, z, contact, value, hand, batch, job, sequence, epoch, expected, deep, setup);
    }

    public WorkshopIntent inHand(InteractionHand value) {
        return new WorkshopIntent(part, action, argument, x, z, contact, effort, value, batch, job, sequence, epoch, expected, deep, setup);
    }

    public WorkshopIntent deepened(boolean value) {
        return new WorkshopIntent(part, action, argument, x, z, contact, effort, hand, batch, job, sequence, epoch, expected, value, setup);
    }

    /** The same action accepting the recipe page the client saw at {@code revision}. */
    public WorkshopIntent accepting(long revision) {
        return new WorkshopIntent(part, action, argument, x, z, contact, effort, hand, batch, job, sequence, epoch, revision, deep, true);
    }
}
