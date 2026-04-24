package com.kobosh.koboshmapartaddon.client.hack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"litematica", "schema", "schematic", "missing", "pathfind", "fly"})
public final class LitematicaMissingFlyHack extends Hack implements UpdateListener {

    private MissingBlockPathFinder pathFinder;
    private PathProcessor processor;
    private BlockPos currentGoal;
    private BlockPos cachedTargetMissing;
    private boolean enabledFlightForThisHack;
    private boolean notifiedNoMissing;
    private boolean foundMissingThisRun;

    public LitematicaMissingFlyHack() {
        super("LitematicaMissingFly");
        setCategory(Category.MOVEMENT);
    }

    @Override
    protected void onEnable() {
        notifiedNoMissing = false;
        foundMissingThisRun = false;

        if (!WURST.getHax().flightHack.isEnabled()) {
            WURST.getHax().flightHack.setEnabled(true);
            enabledFlightForThisHack = true;
        }

        EVENTS.add(UpdateListener.class, this);
    }

    @Override
    protected void onDisable() {
        EVENTS.remove(UpdateListener.class, this);
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

        SchematicPlacement placement = DataManager.getSchematicPlacementManager()
            .getSelectedSchematicPlacement();
        if (placement == null) {
            failAndDisable("No selected Litematica schematic placement.");
            return;
        }

        SchematicVerifier verifier = placement.getSchematicVerifier();

        // Determine if we need to search for a new target block.
        // Only search if: no target cached, cached target no longer missing, or pathfinder failed.
        boolean needsNewTarget = cachedTargetMissing == null
            || (pathFinder != null && pathFinder.isFailed())
            || !isBlockStillMissing(verifier, cachedTargetMissing);

        if (needsNewTarget) {
            List<BlockPos> selectedMissingBlocks = getSelectedMissingBlocks(verifier);
            if (selectedMissingBlocks.isEmpty()) {
                if (!notifiedNoMissing) {
                    if (foundMissingThisRun) {
                        ChatUtils.message("No selected missing blocks left. Done.");
                    } else {
                        ChatUtils.message(
                            "No selected missing blocks found. Verify the schematic first or it may already be complete.");
                    }
                    notifiedNoMissing = true;
                }

                clearPathing();
                cachedTargetMissing = null;
                return;
            }

            notifiedNoMissing = false;
            foundMissingThisRun = true;
            cachedTargetMissing = getClosest(selectedMissingBlocks);
        }

        if (cachedTargetMissing == null) {
            clearPathing();
            return;
        }

        BlockPos goal = cachedTargetMissing.up();
        moveToGoal(goal);
    }

    /**
     * Check if a block is still marked as MISSING in the verifier without iterating all blocks.
     */
    private boolean isBlockStillMissing(SchematicVerifier verifier, BlockPos block) {
        BlockMismatch mismatch = verifier.getMismatchForPosition(block);
        return mismatch != null && mismatch.mismatchType == MismatchType.MISSING;
    }

    private List<BlockPos> getSelectedMissingBlocks(SchematicVerifier verifier) {
        ArrayList<BlockPos> selectedMissingBlocks = new ArrayList<>();

        for (BlockPos pos : verifier.getSelectedMismatchBlockPositionsForRender()) {
            BlockMismatch mismatch = verifier.getMismatchForPosition(pos);
            if (mismatch != null && mismatch.mismatchType == MismatchType.MISSING) {
                selectedMissingBlocks.add(pos);
            }
        }

        return selectedMissingBlocks;
    }

    private BlockPos getClosest(List<BlockPos> candidates) {
        Vec3d eyes = RotationUtils.getEyesPos();
        return candidates.stream()
            .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(eyes)))
            .orElse(null);
    }

    private void moveToGoal(BlockPos goal) {
        if (pathFinder == null || pathFinder.isDone() || pathFinder.isFailed()
            || !goal.equals(currentGoal)) {
            currentGoal = goal;
            pathFinder = new MissingBlockPathFinder(goal);
            processor = null;
        }

        if (!pathFinder.isDone() && !pathFinder.isFailed()) {
            PathProcessor.lockControls();

            pathFinder.think();
            if (!pathFinder.isDone() && !pathFinder.isFailed()) {
                return;
            }

            pathFinder.formatPath();
            processor = pathFinder.getProcessor();
        }

        if (processor != null && !pathFinder.isPathStillValid(processor.getIndex())) {
            pathFinder = new MissingBlockPathFinder(pathFinder);
            processor = null;
            return;
        }

        if (processor != null) {
            processor.process();

            if (processor.isDone()) {
                clearPathing();
            }
        }
    }

    private void clearPathing() {
        pathFinder = null;
        processor = null;
        currentGoal = null;
        cachedTargetMissing = null;
        PathProcessor.releaseControls();
    }

    private void failAndDisable(String reason) {
        ChatUtils.error(reason);
        setEnabled(false);
    }

    private static final class MissingBlockPathFinder extends PathFinder {

        public MissingBlockPathFinder(BlockPos goal) {
            super(goal);
            setThinkTime(10);
        }

        public MissingBlockPathFinder(MissingBlockPathFinder pathFinder) {
            super(pathFinder);
        }

        @Override
        protected boolean checkDone() {
            BlockPos goal = getGoal();

            return done = goal.equals(current)
                || goal.up().equals(current)
                || goal.down().equals(current)
                || goal.north().equals(current)
                || goal.south().equals(current)
                || goal.east().equals(current)
                || goal.west().equals(current);
        }
    }
}
