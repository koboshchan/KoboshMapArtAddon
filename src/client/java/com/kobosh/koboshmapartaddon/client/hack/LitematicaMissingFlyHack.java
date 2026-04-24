package com.kobosh.koboshmapartaddon.client.hack;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.commands.PathCmd;
import net.wurstclient.events.AirStrafingSpeedListener;
import net.wurstclient.events.AirStrafingSpeedListener.AirStrafingSpeedEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"litematica", "schema", "schematic", "missing", "pathfind", "fly"})
public final class LitematicaMissingFlyHack extends Hack
    implements UpdateListener, RenderListener, AirStrafingSpeedListener {

    private final SliderSetting thinkSpeed = new SliderSetting("Think Speed",
        "How many path nodes to process per tick. Higher values find paths faster but may cause lag.",
        1000, 100, 5000, 100, ValueDisplay.INTEGER);

    private final CheckboxSetting autoFlight = new CheckboxSetting("Auto Flight",
        "Automatically enable Flight when this hack is toggled on, and disable it when toggled off.",
        true);

    private final CheckboxSetting showCoords = new CheckboxSetting(
        "Show Coordinates",
        "Show the current target block coordinates in the HackList.", true);

    private final CheckboxSetting speedOverride = new CheckboxSetting(
        "Flight Speed Override",
        "Override FlightHack's speeds while navigating. Restores original speeds when disabled.",
        false);

    private final SliderSetting overrideHorizontalSpeed = new SliderSetting(
        "Horizontal Speed", "Horizontal fly speed used while override is active.",
        1.0, 0.05, 10, 0.05, ValueDisplay.DECIMAL);

    private final SliderSetting overrideVerticalSpeed = new SliderSetting(
        "Vertical Speed", "Vertical fly speed used while override is active.",
        1.0, 0.05, 5, 0.05, ValueDisplay.DECIMAL);

    private final SliderSetting approachHeight = new SliderSetting(
        "Approach Height",
        "How many blocks above the missing block to stop at. 0 = stop at block level, 1–3 = stop that many blocks above.",
        1, 0, 3, 1, ValueDisplay.INTEGER);

    private MissingBlockPathFinder pathFinder;
    private PathProcessor processor;
    private BlockPos currentGoal;
    private int missingBlocksLeft;
    private boolean enabledFlightForThisHack;
    private boolean notifiedNoMissing;
    private boolean foundMissingThisRun;

    public LitematicaMissingFlyHack() {
        super("LitematicaMissingFly");
        setCategory(Category.MOVEMENT);
        addSetting(thinkSpeed);
        addSetting(autoFlight);
        addSetting(showCoords);
        addSetting(speedOverride);
        addSetting(overrideHorizontalSpeed);
        addSetting(overrideVerticalSpeed);
        addSetting(approachHeight);
    }

    @Override
    public String getRenderName() {
        String name = getName();
        if (missingBlocksLeft > 0)
            name += " (" + missingBlocksLeft + " left)";
        if (showCoords.isChecked() && currentGoal != null)
            name += " [" + currentGoal.getX() + ", " + currentGoal.getY()
                + ", " + currentGoal.getZ() + "]";
        return name;
    }

    @Override
    protected void onEnable() {
        notifiedNoMissing = false;
        foundMissingThisRun = false;
        enabledFlightForThisHack = false;

        if (autoFlight.isChecked() && !WURST.getHax().flightHack.isEnabled()) {
            WURST.getHax().flightHack.setEnabled(true);
            enabledFlightForThisHack = true;
        }

        EVENTS.add(UpdateListener.class, this);
        EVENTS.add(RenderListener.class, this);
        EVENTS.add(AirStrafingSpeedListener.class, this);
    }

    @Override
    protected void onDisable() {
        EVENTS.remove(UpdateListener.class, this);
        EVENTS.remove(RenderListener.class, this);
        EVENTS.remove(AirStrafingSpeedListener.class, this);
        clearPathing();

        if (enabledFlightForThisHack) {
            WURST.getHax().flightHack.setEnabled(false);
            enabledFlightForThisHack = false;
        }
    }

    @Override
    public void onUpdate() {
        if (MC.player == null || MC.world == null) {
            return;
        }

        SchematicPlacement placement =
            DataManager.getSchematicPlacementManager()
                .getSelectedSchematicPlacement();
        if (placement == null) {
            ChatUtils.error("No selected Litematica schematic placement.");
            setEnabled(false);
            return;
        }

        SchematicVerifier verifier = placement.getSchematicVerifier();

        // If current target is no longer missing, drop it and search for a new one.
        if (currentGoal != null && !isBlockStillMissing(verifier, currentGoal)) {
            clearPathing();
        }

        // Find a new target block if we don't have one.
        if (currentGoal == null) {
            currentGoal = findClosestMissingBlock(verifier);

            if (currentGoal == null) {
                if (!notifiedNoMissing) {
                    if (foundMissingThisRun) {
                        ChatUtils.message(
                            "No selected missing blocks left. Done!");
                    } else {
                        ChatUtils.message(
                            "No selected missing blocks found."
                                + " Run the verifier first or schematic may be complete.");
                    }
                    notifiedNoMissing = true;
                }
                return;
            }

            notifiedNoMissing = false;
            foundMissingThisRun = true;
        }

        navigateTo(currentGoal);
    }

    @Override
    public void onRender(MatrixStack matrixStack, float partialTicks) {
        if (pathFinder == null) {
            return;
        }
        PathCmd pathCmd = WURST.getCmds().pathCmd;
        pathFinder.renderPath(matrixStack, pathCmd.isDebugMode(),
            pathCmd.isDepthTest());
    }

    /**
     * Intercepts the horizontal strafe speed used by FlightHack so that our
     * override sliders take effect without touching FlightHack's own settings.
     * Fires after FlightHack's listener (registered later), so it wins.
     */
    @Override
    public void onGetAirStrafingSpeed(AirStrafingSpeedEvent event) {
        if (speedOverride.isChecked() && processor != null && !processor.isDone()) {
            event.setSpeed(overrideHorizontalSpeed.getValueF());
        }
    }

    private void navigateTo(BlockPos goal) {
        // Offset the pathfinding target by the configured number of blocks above
        // the missing block. At 0, we stop adjacent to the block itself; at 1+,
        // the player stops that many blocks above it.
        BlockPos target = goal.up(approachHeight.getValueI());

        if (pathFinder == null) {
            pathFinder = new MissingBlockPathFinder(target, thinkSpeed.getValueI());
        }

        // Compute the path — may span multiple ticks.
        if (!pathFinder.isDone() && !pathFinder.isFailed()) {
            PathProcessor.lockControls();
            pathFinder.think();

            if (!pathFinder.isDone() && !pathFinder.isFailed()) {
                return; // Still thinking — come back next tick.
            }

            pathFinder.formatPath();
            processor = pathFinder.getProcessor();
            // Fall through and start processing immediately.
        }

        // If the world changed and the path is no longer valid, recompute.
        if (processor != null
            && !pathFinder.isPathStillValid(processor.getIndex())) {
            pathFinder = new MissingBlockPathFinder(target, thinkSpeed.getValueI());
            return;
        }

        // Follow the path.
        if (processor != null) {
            processor.process();

            if (processor.isDone()) {
                // Reached the target area. Clear state and pick a new goal next tick.
                pathFinder = null;
                processor = null;
                currentGoal = null;
                PathProcessor.releaseControls();
            } else {
                // Apply vertical velocity ourselves.
                // FlyPathProcessor sets keySneak for going down, but FlightHack
                // checks isActuallyDown() (physical key only) and ignores
                // programmatic setDown(true) — so downward movement is silently
                // dropped. We fix that here by applying the velocity directly.
                //
                // When speed override is active, we also replace FlightHack's
                // upward velocity with our custom speed.
                applyVerticalOverride();
            }
        }
    }

    /**
     * Called each tick after processor.process() to:
     * 1. Fix downward movement (FlightHack ignores programmatic keySneak).
     * 2. Replace upward velocity when speed override is enabled.
     *
     * My UpdateListener is registered after FlightHack's (we enable FlightHack
     * first in onEnable), so it fires second — FlightHack has already zeroed
     * deltaMovement and applied its own vertical component. Any velocity we
     * set here is the final value for this tick's physics step.
     */
    private void applyVerticalOverride() {
        double vSpeed = speedOverride.isChecked()
            ? overrideVerticalSpeed.getValue()
            : WURST.getHax().flightHack.getActualVerticalSpeed();

        Vec3d v = MC.player.getVelocity();

        if (MC.options.sneakKey.isPressed()) {
            // Going down: apply the velocity FlightHack refused to.
            MC.player.setVelocity(v.x, -vSpeed, v.z);

        } else if (speedOverride.isChecked() && MC.options.jumpKey.isPressed()) {
            // Going up with override: replace what FlightHack applied with our speed.
            MC.player.setVelocity(v.x, vSpeed, v.z);
        }
    }

    private boolean isBlockStillMissing(SchematicVerifier verifier,
        BlockPos block) {
        BlockMismatch mismatch = verifier.getMismatchForPosition(block);
        return mismatch != null && mismatch.mismatchType == MismatchType.MISSING;
    }

    private BlockPos findClosestMissingBlock(SchematicVerifier verifier) {
        Vec3d eyes = RotationUtils.getEyesPos();
        BlockPos closest = null;
        double closestDistSq = Double.MAX_VALUE;
        int count = 0;

        for (BlockPos pos : verifier.getSelectedMismatchBlockPositionsForRender()) {
            BlockMismatch mismatch = verifier.getMismatchForPosition(pos);
            if (mismatch == null || mismatch.mismatchType != MismatchType.MISSING) {
                continue;
            }
            count++;
            double distSq = pos.getSquaredDistance(eyes);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = pos;
            }
        }

        missingBlocksLeft = count;
        return closest;
    }

    private void clearPathing() {
        pathFinder = null;
        processor = null;
        currentGoal = null;
        missingBlocksLeft = 0;
        PathProcessor.releaseControls();
    }

    private static final class MissingBlockPathFinder extends PathFinder {

        public MissingBlockPathFinder(BlockPos goal, int speed) {
            super(goal);
            setThinkSpeed(speed);
        }

        @Override
        protected boolean checkDone() {
            BlockPos goal = getGoal();
            // Consider done when the player is in any adjacent cell around the
            // goal, matching the same neighborhood used by FillerHack/ExcavatorHack.
            return done = goal.down(2).equals(current)
                || goal.up().equals(current)
                || goal.north().equals(current)
                || goal.south().equals(current)
                || goal.east().equals(current)
                || goal.west().equals(current)
                || goal.down().north().equals(current)
                || goal.down().south().equals(current)
                || goal.down().east().equals(current)
                || goal.down().west().equals(current);
        }
    }
}
