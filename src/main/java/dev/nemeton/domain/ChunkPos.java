package dev.nemeton.domain;

import org.bukkit.Chunk;

public record ChunkPos(String world, int x, int z) {
    public static ChunkPos of(Chunk chunk) {
        return new ChunkPos(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public boolean adjacentTo(ChunkPos other) {
        return world.equals(other.world) && Math.abs(x - other.x) + Math.abs(z - other.z) == 1;
    }

    public String regionSuffix() {
        return world.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + x + "_" + z;
    }
}

