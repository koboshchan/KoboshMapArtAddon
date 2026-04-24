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
import net.wurstclient.util.ChatUtils;

@SearchTags({"schematic", "air walk", "airwalk", "litematica", "ghost"})
public final class SchematicAirWalkHack extends Hack implements UpdateListener {

	public SchematicAirWalkHack() {
		super("SchematicAirWalk");
		setCategory(Category.MOVEMENT);
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

		// Check the block below the player
		BlockPos blockBelow = playerPos.down();
		if (isGhostBlock(verifier, blockBelow)) {
			// Make the ghost block solid for collision
			makeBlockSolid(blockBelow);
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
	 * to simulate standing on it.
	 */
	private void makeBlockSolid(BlockPos blockPos) {
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
