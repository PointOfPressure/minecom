package dev.pointofpressure.minecom.mobs.ai;

import java.util.EnumSet;

/**
 * Vanilla Goal semantics (reimplemented from the decompiled reference):
 * flags declare which controls a goal locks; the selector arbitrates by
 * priority with interruption.
 */
public abstract class VGoal {
    public enum Flag { MOVE, LOOK, JUMP, TARGET }

    private EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);

    protected void setFlags(EnumSet<Flag> flags) {
        this.flags = flags;
    }

    public EnumSet<Flag> getFlags() {
        return flags;
    }

    public abstract boolean canUse();

    public boolean canContinueToUse() {
        return canUse();
    }

    public boolean isInterruptable() {
        return true;
    }

    public void start() {}

    public void stop() {}

    public void tick() {}

    public boolean requiresUpdateEveryTick() {
        return false;
    }
}
