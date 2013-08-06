package com.tommytony.war.mapper;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.Map;
import java.util.logging.Level;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Jukebox;
import org.bukkit.block.NoteBlock;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.tommytony.war.War;
import com.tommytony.war.job.ZoneVolumeSaveJob;
import com.tommytony.war.volume.Volume;
import com.tommytony.war.volume.ZoneVolume;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.List;
import org.bukkit.SkullType;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads and saves zone blocks to SQLite3 database.
 *
 * @author cmastudios
 * @since 1.8
 */
public class ZoneVolumeMapper {

	public static final int DATABASE_VERSION = 1;

	/**
	 * Loads the given volume
	 *
	 * @param ZoneVolume volume Volume to load
	 * @param String zoneName Zone to load the volume from
	 * @param World world The world the zone is located
	 * @param boolean onlyLoadCorners Should only the corners be loaded
	 * @return integer Changed blocks
	 * @throws SQLException Error communicating with SQLite3 database
	 */
	public static int load(ZoneVolume volume, String zoneName, World world, boolean onlyLoadCorners) throws SQLException {
		int changed = 0;
		File databaseFile = new File(War.war.getDataFolder(), String.format("/dat/warzone-%s/volume-%s.sl3", zoneName, volume.getName()));
		if (!databaseFile.exists()) {
			// Convert warzone to nimitz file format.
			changed = PreNimitzZoneVolumeMapper.load(volume, zoneName, world, onlyLoadCorners);
			ZoneVolumeMapper.saveAsJob(volume, zoneName, 2);
			War.war.log("Warzone " + zoneName + " file converted to nimitz format!", Level.INFO);
			return changed;
		}
		Connection databaseConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getPath());
		Statement stmt = databaseConnection.createStatement();
		ResultSet versionQuery = stmt.executeQuery("PRAGMA user_version");
		int version = versionQuery.getInt("user_version");
		versionQuery.close();
		if (version > DATABASE_VERSION) {
			try {
				throw new IllegalStateException("Unsupported zone format " + version);
			} finally {
				stmt.close();
				databaseConnection.close();
			}
		} else if (version < DATABASE_VERSION) {
			switch (version) {
				// Run some update SQL for each old version
			}
		}
		ResultSet cornerQuery = stmt.executeQuery("SELECT * FROM corners");
		cornerQuery.next();
		final Block corner1 = world.getBlockAt(cornerQuery.getInt("x"), cornerQuery.getInt("y"), cornerQuery.getInt("z"));
		cornerQuery.next();
		final Block corner2 = world.getBlockAt(cornerQuery.getInt("x"), cornerQuery.getInt("y"), cornerQuery.getInt("z"));
		cornerQuery.close();
		volume.setCornerOne(corner1);
		volume.setCornerTwo(corner2);
		if (onlyLoadCorners) {
			stmt.close();
			databaseConnection.close();
			return 0;
		}
		ResultSet query = stmt.executeQuery("SELECT * FROM blocks");
		while (query.next()) {
			int x = query.getInt("x"), y = query.getInt("y"), z = query.getInt("z");
			BlockState modify = corner1.getRelative(x, y, z).getState();
			modify.setTypeId(query.getInt("type"));
			modify.setRawData((byte) query.getInt("data"));
			modify.update(true, false); // No-physics update, preventing the need for deferring blocks
			modify = corner1.getRelative(x, y, z).getState(); // Grab a new instance
			try {
				if (modify instanceof Sign) {
					final String[] lines = query.getString("sign").split("\n");
					for (int i = 0; i < lines.length; i++) {
						((Sign) modify).setLine(i, lines[i]);
					}
				}
				if (modify instanceof InventoryHolder && query.getString("container") != null) {
					YamlConfiguration config = new YamlConfiguration();
					config.loadFromString(query.getString("container"));
					((InventoryHolder) modify).getInventory().clear();
					for (Object obj : config.getList("items")) {
						if (obj instanceof ItemStack) {
							((InventoryHolder) modify).getInventory().addItem((ItemStack) obj);
						}
					}
				}
				if (modify instanceof NoteBlock) {
					((NoteBlock) modify).setRawNote((byte) query.getInt("note"));
				}
				if (modify instanceof Jukebox) {
					((Jukebox) modify).setPlaying(Material.getMaterial(query.getInt("note")));
				}
				if (modify instanceof Skull && query.getString("skull") != null) {
					String[] opts = query.getString("skull").split("\n");
					((Skull) modify).setOwner(opts[0]);
					((Skull) modify).setSkullType(SkullType.valueOf(opts[1]));
					((Skull) modify).setRotation(BlockFace.valueOf(opts[2]));
				}
				if (modify instanceof CommandBlock && query.getString("command") != null) {
					final String[] commandArray = query.getString("command").split("\n");
					((CommandBlock) modify).setName(commandArray[0]);
					((CommandBlock) modify).setCommand(commandArray[1]);
				}
				if (modify instanceof CreatureSpawner) {
					((CreatureSpawner) modify).setSpawnedType(EntityType.fromId(query.getInt("mobid")));
				}
			} catch (Exception ex) {
				War.war.log("Exception loading some tile data: " + ex.getMessage(), Level.WARNING);
				ex.printStackTrace();
			}
			modify.update(true, false);
			changed++;
		}
		query.close();
		stmt.close();
		databaseConnection.close();
		return changed;
	}

	private static void saveAsJob(ZoneVolume volume, String zoneName, int tickDelay) {
		ZoneVolumeSaveJob job = new ZoneVolumeSaveJob(volume, zoneName);
		War.war.getServer().getScheduler().scheduleSyncDelayedTask(War.war, job, tickDelay);
	}

	/**
	 * Save all war zone blocks to a SQLite3 database file.
	 *
	 * @param volume Volume to save (takes corner data and loads from world).
	 * @param zoneName Name of warzone to save.
	 * @return amount of changed blocks
	 * @throws SQLException
	 */
	public static int save(Volume volume, String zoneName) throws SQLException {
		int changed = 0;
		File warzoneDir = new File(War.war.getDataFolder().getPath() + "/dat/warzone-" + zoneName);
		warzoneDir.mkdirs();
		File databaseFile = new File(War.war.getDataFolder(), String.format("/dat/warzone-%s/volume-%s.sl3", zoneName, volume.getName()));
		Connection databaseConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getPath());
		Statement stmt = databaseConnection.createStatement();
		stmt.executeUpdate("PRAGMA user_version = " + DATABASE_VERSION);
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS blocks (x BIGINT, y BIGINT, z BIGINT, type INT, data INT, sign TEXT, container BLOB, note INT, skull TEXT, command TEXT, mobid INT)");
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS corners (pos INTEGER PRIMARY KEY  NOT NULL  UNIQUE, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL)");
		stmt.executeUpdate("DELETE FROM blocks");
		stmt.executeUpdate("DELETE FROM corners");
		stmt.close();
		PreparedStatement cornerStmt = databaseConnection.prepareStatement("INSERT INTO corners SELECT 1 AS pos, ? AS x, ? AS y, ? AS z UNION SELECT 2, ?, ?, ?");
		cornerStmt.setInt(1, volume.getCornerOne().getX());
		cornerStmt.setInt(2, volume.getCornerOne().getY());
		cornerStmt.setInt(3, volume.getCornerOne().getZ());
		cornerStmt.setInt(4, volume.getCornerTwo().getX());
		cornerStmt.setInt(5, volume.getCornerTwo().getY());
		cornerStmt.setInt(6, volume.getCornerTwo().getZ());
		cornerStmt.executeUpdate();
		cornerStmt.close();
		PreparedStatement dataStmt = databaseConnection.prepareStatement("INSERT INTO blocks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
		databaseConnection.setAutoCommit(false);
		final int batchSize = 1000;
		for (int i = 0, x = volume.getMinX(); i < volume.getSizeX(); i++, x++) {
			for (int j = 0, y = volume.getMinY(); j < volume.getSizeY(); j++, y++) {
				for (int k = 0, z = volume.getMinZ(); k < volume.getSizeZ(); k++, z++) {
					final Block block = volume.getWorld().getBlockAt(x, y, z);
					final Location relLoc = rebase(volume.getCornerOne().getLocation(volume.getWorld()), block.getLocation());
					dataStmt.setInt(1, relLoc.getBlockX());
					dataStmt.setInt(2, relLoc.getBlockY());
					dataStmt.setInt(3, relLoc.getBlockZ());
					dataStmt.setInt(4, block.getTypeId());
					dataStmt.setInt(5, block.getData());
					if (block.getState() instanceof Sign) {
						final String signText = StringUtils.join(((Sign) block.getState()).getLines(), "\n");
						dataStmt.setString(6, signText);
					} else {
						dataStmt.setNull(6, Types.VARCHAR);
					}
					if (block.getState() instanceof InventoryHolder) {
						List<ItemStack> items = Arrays.asList(((InventoryHolder) block.getState()).getInventory().getContents());
						YamlConfiguration config = new YamlConfiguration();
						// Serialize to config, then store config in database
						config.set("items", items);
						dataStmt.setString(7, config.saveToString());
					} else {
						dataStmt.setNull(7, Types.BLOB);
					}
					if (block.getState() instanceof NoteBlock) {
						dataStmt.setInt(8, ((NoteBlock) block.getState()).getRawNote());
					} else if (block.getState() instanceof Jukebox) {
						dataStmt.setInt(8, ((Jukebox) block.getState()).getPlaying().getId());
					} else {
						dataStmt.setNull(8, Types.INTEGER);
					}
					if (block.getState() instanceof Skull) {
						dataStmt.setString(9, String.format("%s\n%s\n%s",
								((Skull) block.getState()).getOwner(),
								((Skull) block.getState()).getSkullType().toString(),
								((Skull) block.getState()).getRotation().toString()));
					} else {
						dataStmt.setNull(9, Types.VARCHAR);
					}
					if (block.getState() instanceof CommandBlock) {
						dataStmt.setString(10, ((CommandBlock) block.getState()).getName()
								+ "\n" + ((CommandBlock) block.getState()).getCommand());
					} else {
						dataStmt.setNull(10, Types.VARCHAR);
					}
					if (block.getState() instanceof CreatureSpawner) {
						dataStmt.setInt(11, ((CreatureSpawner) block.getState()).getSpawnedType().getTypeId());
					} else {
						dataStmt.setNull(11, Types.INTEGER);
					}
					dataStmt.addBatch();
					if (++changed % batchSize == 0) {
						dataStmt.executeBatch();
					}
				}
			}
		}
		dataStmt.executeBatch(); // insert remaining records
		databaseConnection.commit();
		dataStmt.close();
		databaseConnection.close();
		return changed;
	}

	private static Location rebase(final Location base, final Location exact) {
		Validate.isTrue(base.getWorld().equals(exact.getWorld()),
				"Locations must be in the same world");
		return new Location(base.getWorld(),
				exact.getBlockX() - base.getBlockX(),
				exact.getBlockY() - base.getBlockY(),
				exact.getBlockZ() - base.getBlockZ());
	}
}
