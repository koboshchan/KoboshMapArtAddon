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
        "How many blocks above the missing block to stop at. 0 = stop at block level, 1–5 = stop that many blocks above.",
        0, 0, 5, 1, ValueDisplay.INTEGER);

    private final CheckboxSetting skipStuck = new CheckboxSetting(
        "Skip Stuck Blocks",
        "If a block is still missing 1.5 seconds after arriving, move to the next closest block at least 5 blocks away.",
        false);

    private MissingBlockPathFinder pathFinder;
    private PathProcessor processor;
    private BlockPos currentGoal;
    private int missingBlocksLeft;
    // -1 = not in arrived state; >= 0 = ticks spent waiting at a goal that is
    // still missing after the path processor completed.
    private int arrivedTicks = -1;
    private boolean enabledFlightForThisHack;
    private boolean notifiedNoMissing;
    private boolean foundMissingThisRun;
    private double lastTargetDistSq = Double.MAX_VALUE;
    private int noProgressTicks;
    private double lastPosX = Double.NaN;
    private double lastPosZ = Double.NaN;
    private double prevMoveX;
    private double prevMoveZ;
    private int xzOscillationTicks;

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
        addSetting(skipStuck);
    }

    @Override
    public String getRenderName() {
        String name = getName();
        if (missingBlocksLeft > 0) {
            String countStr = missingBlocksLeft >= 1000
                ? "1000+" : String.valueOf(missingBlocksLeft);
            name += " (" + countStr + " left)";
        }
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

        // Handle "arrived" state: we reached the goal area but the block is
        // still missing. After 1.5 seconds (30 ticks) try the closest
        // alternative at least 5 blocks away. If none exists, keep waiting.
        if (skipStuck.isChecked() && currentGoal != null && arrivedTicks >= 0) {
            applyPlacementHeightOffset(currentGoal);
            arrivedTicks++;
            if (arrivedTicks >= 30) {
                BlockPos alt =
                    findAlternativeMissingBlock(verifier, currentGoal, 5);
                if (alt != null) {
                    currentGoal = alt;
                    arrivedTicks = -1; // exit arrived state, navigate below
                } else {
                    arrivedTicks = 0; // no alternative — reset and retry in 1.5 s
                    return;
                }
            } else {
                return; // still waiting
            }
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
            lastTargetDistSq = Double.MAX_VALUE;
            noProgressTicks = 0;
            lastPosX = Double.NaN;
            lastPosZ = Double.NaN;
            prevMoveX = 0;
            prevMoveZ = 0;
            xzOscillationTicks = 0;
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
            processor = null;
            lastTargetDistSq = Double.MAX_VALUE;
            noProgressTicks = 0;
            lastPosX = Double.NaN;
            lastPosZ = Double.NaN;
            prevMoveX = 0;
            prevMoveZ = 0;
            xzOscillationTicks = 0;
            return;
        }

        // Follow the path.
        if (processor != null) {
            Vec3d targetCenter = Vec3d.ofCenter(target);
            double beforeDistSq = targetCenter.squaredDistanceTo(
                MC.player.getX(), MC.player.getY(), MC.player.getZ());
            processor.process();

            if (processor.isDone()) {
                // Arrived at the target area. Transition to the "arrived" waiting
                // state — keep currentGoal so stuck detection can run in onUpdate.
                applyPlacementHeightOffset(goal);
                pathFinder = null;
                processor = null;
                arrivedTicks = 0;
                lastTargetDistSq = Double.MAX_VALUE;
                noProgressTicks = 0;
                lastPosX = Double.NaN;
                lastPosZ = Double.NaN;
                prevMoveX = 0;
                prevMoveZ = 0;
                xzOscillationTicks = 0;
                PathProcessor.releaseControls();
            } else {
                // Freecam camera mode makes FlightHack ignore movement updates,
                // so drive the real player directly in that case.
                if (WURST.getHax().freecamHack.isMovingCamera()) {
                    applyFreecamCameraBypassMovement();
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

                double afterDistSq = targetCenter.squaredDistanceTo(
                    MC.player.getX(), MC.player.getY(), MC.player.getZ());
                // Re-path if we make no measurable progress for too long. This
                // avoids rare spin/oscillation states where controls keep firing
                // but the player does not get closer to the target.
                if (afterDistSq + 0.01 < beforeDistSq || afterDistSq + 0.01 < lastTargetDistSq) {
                    noProgressTicks = 0;
                } else {
                    noProgressTicks++;
                }
                lastTargetDistSq = afterDistSq;

                updateXzOscillationWatchdog();

                if (noProgressTicks >= 40 || xzOscillationTicks >= 20) {
                    pathFinder = null;
                    processor = null;
                    lastTargetDistSq = Double.MAX_VALUE;
                    noProgressTicks = 0;
                    lastPosX = Double.NaN;
                    lastPosZ = Double.NaN;
                    prevMoveX = 0;
                    prevMoveZ = 0;
                    xzOscillationTicks = 0;
                    PathProcessor.releaseControls();
                }
            }
        }
    }

    private void updateXzOscillationWatchdog() {
        double x = MC.player.getX();
        double z = MC.player.getZ();

        if (Double.isNaN(lastPosX) || Double.isNaN(lastPosZ)) {
            lastPosX = x;
            lastPosZ = z;
            prevMoveX = 0;
            prevMoveZ = 0;
            xzOscillationTicks = 0;
            return;
        }

        double dx = x - lastPosX;
        double dz = z - lastPosZ;
        lastPosX = x;
        lastPosZ = z;

        boolean xMoved = Math.abs(dx) > 0.005;
        boolean zMoved = Math.abs(dz) > 0.005;
        boolean xFlipped = xMoved && Math.abs(prevMoveX) > 0.005
            && Math.signum(dx) != Math.signum(prevMoveX);
        boolean zFlipped = zMoved && Math.abs(prevMoveZ) > 0.005
            && Math.signum(dz) != Math.signum(prevMoveZ);

        boolean oneAxisBackAndForth = (!xMoved && zFlipped) || (!zMoved && xFlipped);
        if (oneAxisBackAndForth) {
            xzOscillationTicks++;
        } else {
            xzOscillationTicks = 0;
        }

        prevMoveX = xMoved ? dx : 0;
        prevMoveZ = zMoved ? dz : 0;
    }

    /**
     * When Freecam is set to move the camera, FlightHack intentionally stops
     * applying movement. We therefore apply direct velocity to the real player
     * from the current movement keys that FlyPathProcessor set this tick.
     */
    private void applyFreecamCameraBypassMovement() {
        double hSpeed = speedOverride.isChecked()
            ? overrideHorizontalSpeed.getValue()
            : WURST.getHax().flightHack.getHorizontalSpeed();
        double vSpeed = speedOverride.isChecked()
            ? overrideVerticalSpeed.getValue()
            : WURST.getHax().flightHack.getActualVerticalSpeed();

        double forward = 0;
        double strafe = 0;
        if (MC.options.forwardKey.isPressed())
            forward += 1;
        if (MC.options.backKey.isPressed())
            forward -= 1;
        if (MC.options.leftKey.isPressed())
            strafe += 1;
        if (MC.options.rightKey.isPressed())
            strafe -= 1;

        if (forward != 0 && strafe != 0) {
            double invSqrt2 = 1 / Math.sqrt(2);
            forward *= invSqrt2;
            strafe *= invSqrt2;
        }

        double yawRad = Math.toRadians(MC.player.getYaw());
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);
        double vx = (-sinYaw * forward + cosYaw * strafe) * hSpeed;
        double vz = (cosYaw * forward + sinYaw * strafe) * hSpeed;

        double vy = 0;
        if (MC.options.sneakKey.isPressed()) {
            vy -= vSpeed;
        }
        if (MC.options.jumpKey.isPressed()) {
            vy += vSpeed;
        }

        MC.player.setVelocity(vx, vy, vz);
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

    private void applyPlacementHeightOffset(BlockPos goal) {
        if (MC.player == null || MC.world == null) {
            return;
        }

        double yOffset = getPlacementYOffset(goal);
        if (yOffset == 0) {
            return;
        }

        double targetY = goal.getY() + yOffset;
        if (Math.abs(MC.player.getY() - targetY) < 0.01) {
            return;
        }

        MC.player.setPosition(MC.player.getX(), targetY, MC.player.getZ());
    }

    private double getPlacementYOffset(BlockPos goal) {
        boolean hasBlockAbove = !MC.world.getBlockState(goal.up()).isAir();
        boolean hasBlockBelow = !MC.world.getBlockState(goal.down()).isAir();

        if (hasBlockAbove && !hasBlockBelow) {
            // Goal is under a block -> stay half a block lower.
            return -0.5;
        }

        if (hasBlockBelow && !hasBlockAbove) {
            // Goal is above a block -> stay half a block higher.
            return 0.5;
        }

        // If both or neither are true, don't force an offset.
        return 0;
    }

    /**
     * Finds the closest missing block that is at least {@code minDist} blocks
     * away from {@code exclude}. Used to escape a stuck position.
     */
    private BlockPos findAlternativeMissingBlock(SchematicVerifier verifier,
        BlockPos exclude, double minDist) {
        Vec3d eyes = RotationUtils.getEyesPos();
        BlockPos closest = null;
        double closestDistSq = Double.MAX_VALUE;
        double minDistSq = minDist * minDist;

        for (BlockPos pos : verifier.getSelectedMismatchBlockPositionsForRender()) {
            BlockMismatch mismatch = verifier.getMismatchForPosition(pos);
            if (mismatch == null || mismatch.mismatchType != MismatchType.MISSING)
                continue;
            if (pos.getSquaredDistance(Vec3d.ofCenter(exclude)) < minDistSq)
                continue;
            double distSq = pos.getSquaredDistance(eyes);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = pos;
            }
        }
        return closest;
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
        arrivedTicks = -1;
        lastTargetDistSq = Double.MAX_VALUE;
        noProgressTicks = 0;
        lastPosX = Double.NaN;
        lastPosZ = Double.NaN;
        prevMoveX = 0;
        prevMoveZ = 0;
        xzOscillationTicks = 0;
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
