# KoboshMapArtAddon

A Wurst7 addon for Minecraft 1.21.10.

**Requirements:**
- [koboshchan/Wurst7](https://github.com/koboshchan/Wurst7)
- [koboshchan/litematica-printer](https://github.com/koboshchan/litematica-printer) — requires an airplace fork of Litematica

## Hacks

### LitematicaMissingFly

Automatically pathfinds and flies to missing blocks in the active Litematica schematic verifier.

**Settings:**
- **Think Speed** — A* nodes processed per tick (100–5000)
- **Approach Height** — blocks above the missing block to stop at (0–3)
- **Auto Flight** — enables/disables FlightHack automatically on toggle
- **Show Coordinates** — shows target block coords in the HackList
- **Flight Speed Override** — when checked, replaces FlightHack's speeds with the two sliders below
  - **Horizontal Speed**
  - **Vertical Speed**

**Requirements:** Select a schematic placement in Litematica and run the verifier before enabling.

## Build

1. Build Wurst first from `wurst7-base` or `../Wurst7` (branch `master`)
2. `./gradlew build`

## Registration

Uses Java ServiceLoader — `WurstAddonHackAddon` implements `net.wurstclient.addon.Addon` via `META-INF/services`.
