/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.kobosh.koboshmapartaddon.client.hack;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.ChunkStatus;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"schematic", "air walk", "airwalk", "litematica", "ghost"})
public final class SchematicAirWalkHack extends Hack implements UpdateListener {

	private final CheckboxSetting xzWalls = new CheckboxSetting("XZ Walls",
		"Also treat missing schematic blocks on the north/south/east/west sides"
			+ " as solid walls, not just the floor below.",
		false);

	public SchematicAirWalkHack() {
		super("SchematicAirWalk");
		setCategory(Category.MOVEMENT);
		addSetting(xzWalls);
	}

	@Override
	protected void onEnable() {
		EVENTS.add(UpdateListener.class, this);
	}

	@Override
	protected void onDisable() {
		EVENTS.remove(UpdateListener.class, this);
	}

	@Override
	public void onUpdate() {
		if (MC.player == null || MC.world == null) {
			return;
		}

		SchematicPlacement placement = DataManager.getSchematicPlacementManager()
			.getSelectedSchematicPlacement();
		if (placement == null) {
			return;
		}

		SchematicVerifier verifier = placement.getSchematicVerifier();
		BlockPos playerPos = MC.player.getBlockPos();

		// Y plane: block below the player
		BlockPos blockBelow = playerPos.down();
		if (isGhostBlock(verifier, blockBelow)) {
			makeBlockSolidY(blockBelow);
		}

		// XZ planes: blocks on all four horizontal sides (optional)
		if (xzWalls.isChecked()) {
			applyXzWalls(verifier, playerPos);
		}
	}

	/**
	 * Prevent the player from passing through ghost blocks on the X and Z axes.
	 * Checks all four horizontal neighbours of the player's block position and
	 * clamps the player's position + velocity when they would overlap.
	 */
	private void applyXzWalls(SchematicVerifier verifier, BlockPos playerPos) {
		double px = MC.player.getX();
		double pz = MC.player.getZ();
		double vx = MC.player.getVelocity().x;
		double vy = MC.player.getVelocity().y;
		double vz = MC.player.getVelocity().z;

		// Block integer origin of the block the player currently stands in.
		int bx = playerPos.getX(); // block spans [bx, bx+1]
		int bz = playerPos.getZ(); // block spans [bz, bz+1]

		// Half-width of player bounding box (0.3 on each side).
		final double HW = 0.3;

		boolean changedVx = false;
		boolean changedVz = false;

		// EAST face: ghost block at (bx+1, *, bz). Its west face is at x = bx+1.
		if (isGhostBlock(verifier, playerPos.east()) && px + HW > bx + 1) {
			px = bx + 1 - HW;
			if (vx > 0) { vx = 0; changedVx = true; }
		}
		// WEST face: ghost block at (bx-1, *, bz). Its east face is at x = bx.
		if (isGhostBlock(verifier, playerPos.west()) && px - HW < bx) {
			px = bx + HW;
			if (vx < 0) { vx = 0; changedVx = true; }
		}
		// SOUTH face: ghost block at (bx, *, bz+1). Its north face is at z = bz+1.
		if (isGhostBlock(verifier, playerPos.south()) && pz + HW > bz + 1) {
			pz = bz + 1 - HW;
			if (vz > 0) { vz = 0; changedVz = true; }
		}
		// NORTH face: ghost block at (bx, *, bz-1). Its south face is at z = bz.
		if (isGhostBlock(verifier, playerPos.north()) && pz - HW < bz) {
			pz = bz + HW;
			if (vz < 0) { vz = 0; changedVz = true; }
		}

		if (changedVx || changedVz) {
			MC.player.setPosition(px, MC.player.getY(), pz);
			MC.player.setVelocity(vx, vy, vz);
		}
	}

	/**
	 * Check if a block is a "ghost block" (missing in schematic).
	 */
	private boolean isGhostBlock(SchematicVerifier verifier, BlockPos pos) {
		// Check if this position has a MISSING mismatch
		var mismatch = verifier.getMismatchForPosition(pos);
		return mismatch != null && mismatch.mismatchType == MismatchType.MISSING;
	}

	/**
	 * Temporarily make a block solid by setting player velocity and position
	 * to simulate standing on it (Y axis / floor).
	 */
	private void makeBlockSolidY(BlockPos blockPos) {
		// Ensure player doesn't fall through by stopping downward velocity
		if (MC.player.getVelocity().y < 0) {
			MC.player.setVelocity(MC.player.getVelocity().x, 0, MC.player.getVelocity().z);
		}

		// Position the player on top of the block
		double targetY = blockPos.getY() + 1;
		if (MC.player.getY() < targetY) {
			MC.player.setPosition(MC.player.getX(), targetY, MC.player.getZ());
		}

		MC.player.setOnGround(true);
	}
}
