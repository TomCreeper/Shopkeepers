package com.nisovin.shopkeepers.shopobjects.sign;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import com.nisovin.shopkeepers.Settings;
import com.nisovin.shopkeepers.api.shopkeeper.ShopCreationData;
import com.nisovin.shopkeepers.api.shopobjects.sign.SignShopObjectType;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopobjects.block.AbstractBlockShopObjectType;
import com.nisovin.shopkeepers.util.StringUtils;

public class SKSignShopObjectType extends AbstractBlockShopObjectType<SKSignShopObject> implements SignShopObjectType<SKSignShopObject> {

	private final SignShops signShops;

	public SKSignShopObjectType(SignShops signShops) {
		super("sign", "shopkeeper.sign");
		this.signShops = signShops;
	}

	@Override
	public String getDisplayName() {
		return Settings.msgShopObjectTypeSign;
	}

	@Override
	public SKSignShopObject createObject(AbstractShopkeeper shopkeeper, ShopCreationData creationData) {
		return new SKSignShopObject(signShops, shopkeeper, creationData);
	}

	@Override
	public boolean isEnabled() {
		return Settings.enableSignShops;
	}

	@Override
	public boolean matches(String identifier) {
		identifier = StringUtils.normalize(identifier);
		if (super.matches(identifier)) return true;
		return identifier.startsWith("sign");
	}

	@Override
	public boolean needsSpawning() {
		return true; // despawn signs on chunk unload, and spawn them again on chunk load
	}

	@Override
	public boolean isValidSpawnLocation(Location spawnLocation, BlockFace targetedBlockFace) {
		// block has to be empty, and limit to wall sign faces if sign posts are disabled:
		return spawnLocation.getBlock().isEmpty()
				&& (Settings.enableSignPostShops || targetedBlockFace != BlockFace.UP)
				&& super.isValidSpawnLocation(spawnLocation, targetedBlockFace);
	}
}
