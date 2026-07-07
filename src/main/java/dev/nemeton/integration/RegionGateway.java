package dev.nemeton.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import dev.nemeton.config.Settings;
import dev.nemeton.domain.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RegionGateway {
    private final Settings settings;
    public RegionGateway(Settings settings) { this.settings = settings; }

    public void ensureNemeton() {
        World world = Bukkit.getWorld(settings.hub().world());
        if (world == null) return;
        int radius = settings.hub().radius();
        List<BlockVector2> points = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            double angle = Math.PI * 2 * index / 32.0;
            points.add(BlockVector2.at(
                    (int) Math.round(settings.hub().centerX() + Math.cos(angle) * radius),
                    (int) Math.round(settings.hub().centerZ() + Math.sin(angle) * radius)));
        }
        ProtectedPolygonalRegion region = new ProtectedPolygonalRegion(
                "nemeton_hub", points, world.getMinHeight(), world.getMaxHeight());
        region.setPriority(100);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.PVP, StateFlag.State.DENY);
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.MOB_DAMAGE, StateFlag.State.DENY);
        region.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
        RegionManager manager = manager(world);
        manager.removeRegion("nemeton_hub");
        manager.addRegion(region);
    }

    public void createSanctuary(ChunkPos chunk, UUID owner, Collection<UUID> trusted) {
        ProtectedCuboidRegion region = chunkRegion("sanctuary_" + owner.toString().substring(0, 8) + "_" + chunk.regionSuffix(), chunk, 50);
        DefaultDomain owners = new DefaultDomain(); owners.addPlayer(owner); region.setOwners(owners);
        DefaultDomain members = new DefaultDomain(); trusted.forEach(members::addPlayer); region.setMembers(members);
        region.setFlag(Flags.PVP, StateFlag.State.DENY);
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        manager(chunk).addRegion(region);
    }

    public void createClanClaim(ChunkPos chunk, Collection<UUID> members) {
        ProtectedCuboidRegion region = chunkRegion(clanRegionId(chunk), chunk, 10);
        DefaultDomain domain = new DefaultDomain(); members.forEach(domain::addPlayer); region.setMembers(domain);
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        manager(chunk).addRegion(region);
    }

    public void syncClanMembers(Collection<ChunkPos> claims, Collection<UUID> members) {
        for (ChunkPos chunk : claims) {
            ProtectedRegion region = manager(chunk).getRegion(clanRegionId(chunk));
            if (region != null) { DefaultDomain domain = new DefaultDomain(); members.forEach(domain::addPlayer); region.setMembers(domain); }
        }
    }

    public void createRaidOverlay(UUID raidId, Collection<ChunkPos> claims, Collection<UUID> participants) {
        for (ChunkPos chunk : claims) {
            ProtectedCuboidRegion region = chunkRegion(raidRegionId(raidId, chunk), chunk, 20);
            DefaultDomain members = new DefaultDomain(); participants.forEach(members::addPlayer); region.setMembers(members);
            region.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
            region.setFlag(Flags.PVP, StateFlag.State.ALLOW);
            region.setFlag(Flags.TNT, StateFlag.State.ALLOW);
            region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.ALLOW);
            manager(chunk).addRegion(region);
        }
    }

    public void removeRaidOverlay(UUID raidId, Collection<ChunkPos> claims) {
        claims.forEach(chunk -> manager(chunk).removeRegion(raidRegionId(raidId, chunk)));
    }
    public void removeClanClaim(ChunkPos chunk) { manager(chunk).removeRegion(clanRegionId(chunk)); }
    public void removeSanctuary(ChunkPos chunk, UUID owner) { manager(chunk).removeRegion("sanctuary_" + owner.toString().substring(0, 8) + "_" + chunk.regionSuffix()); }

    private ProtectedCuboidRegion chunkRegion(String id, ChunkPos chunk, int priority) {
        World world = requireWorld(chunk.world()); int minX = chunk.x() << 4; int minZ = chunk.z() << 4;
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, BlockVector3.at(minX, world.getMinHeight(), minZ), BlockVector3.at(minX + 15, world.getMaxHeight(), minZ + 15));
        region.setPriority(priority); return region;
    }
    private RegionManager manager(ChunkPos chunk) { return manager(requireWorld(chunk.world())); }
    private RegionManager manager(World world) { return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)); }
    private World requireWorld(String name) { World world = Bukkit.getWorld(name); if (world == null) throw new IllegalStateException("Mundo não carregado: " + name); return world; }
    private String clanRegionId(ChunkPos chunk) { return "clan_" + chunk.regionSuffix(); }
    private String raidRegionId(UUID raid, ChunkPos chunk) { return "raid_" + raid.toString().substring(0, 8) + "_" + chunk.regionSuffix(); }
}
