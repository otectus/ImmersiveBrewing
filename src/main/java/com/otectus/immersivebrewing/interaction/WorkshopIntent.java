package com.otectus.immersivebrewing.interaction;

import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import net.minecraft.world.InteractionHand;

import java.util.UUID;

/**
 * A validated, decoded player intent for one station part (handoff 15). Built from {@code WorkshopIntentC2S} after the
 * envelope checks, or directly by server code and tests. Carries no item, score, recipe or elapsed time.
 *
 * @param job the batch the client believes it is acting on ({@link #ANY_JOB} for idle-station actions); a stale job id
 *            refuses job actions harmlessly
 * @param x,z normalised work-plane coordinates {@code -1..1} (samples only)
 */
public record WorkshopIntent(int part, WorkshopAction action, int argument, double x, double z, boolean contact, Effort effort,
                             InteractionHand hand, boolean batch, UUID job, int sequence) {
    public static final UUID ANY_JOB = new UUID(0, 0);

    public static WorkshopIntent of(int part, WorkshopAction action, int argument) {
        return new WorkshopIntent(part, action, argument, 0, 0, false, Effort.NORMAL, InteractionHand.MAIN_HAND, false, ANY_JOB, 0);
    }

    public static WorkshopIntent sample(int part, double x, double z, boolean contact, Effort effort) {
        return new WorkshopIntent(part, WorkshopAction.SAMPLE, 0, x, z, contact, effort, InteractionHand.MAIN_HAND, false, ANY_JOB, 0);
    }

    public WorkshopIntent batched() {
        return new WorkshopIntent(part, action, argument, x, z, contact, effort, hand, true, job, sequence);
    }

    public WorkshopIntent withEffort(Effort value) {
        return new WorkshopIntent(part, action, argument, x, z, contact, value, hand, batch, job, sequence);
    }

    public WorkshopIntent inHand(InteractionHand value) {
        return new WorkshopIntent(part, action, argument, x, z, contact, effort, value, batch, job, sequence);
    }
}
