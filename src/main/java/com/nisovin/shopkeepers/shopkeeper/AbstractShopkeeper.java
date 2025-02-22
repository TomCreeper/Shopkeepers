package com.nisovin.shopkeepers.shopkeeper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.Settings;
import com.nisovin.shopkeepers.api.ShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperAddedEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperRemoveEvent;
import com.nisovin.shopkeepers.api.shopkeeper.ShopCreationData;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.ShopkeeperCreateException;
import com.nisovin.shopkeepers.api.shopkeeper.ShopkeeperRegistry;
import com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe;
import com.nisovin.shopkeepers.api.shopobjects.ShopObjectType;
import com.nisovin.shopkeepers.api.storage.ShopkeeperStorage;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import com.nisovin.shopkeepers.api.ui.UIType;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.shopobjects.AbstractShopObject;
import com.nisovin.shopkeepers.shopobjects.AbstractShopObjectType;
import com.nisovin.shopkeepers.ui.UIHandler;
import com.nisovin.shopkeepers.ui.defaults.SKDefaultUITypes;
import com.nisovin.shopkeepers.ui.defaults.TradingHandler;
import com.nisovin.shopkeepers.util.Log;
import com.nisovin.shopkeepers.util.StringUtils;
import com.nisovin.shopkeepers.util.Utils;

/**
 * Abstract base class for all shopkeeper implementations.
 * <p>
 * Implementation hints:<br>
 * <ul>
 * <li>Make sure to follow the initialization instructions outlined in the constructor description.
 * <li>Make sure to call {@link #markDirty()} on every change of data that might need to be persisted.
 * </ul>
 */
public abstract class AbstractShopkeeper implements Shopkeeper {

	// the maximum supported name length:
	// the actual maximum name length that can be used might be lower depending on config settings
	// and on shop object specific limits
	public static final int MAX_NAME_LENGTH = 128;

	private final int id;
	private UUID uniqueId;
	private AbstractShopObject shopObject;
	private String worldName;
	private int x;
	private int y;
	private int z;
	private ChunkCoords chunkCoords;
	private String name = "";

	// has unsaved data changes:
	private boolean dirty = false;
	// is currently registered:
	private boolean valid = false;

	// ui type identifier -> ui handler
	private final Map<String, UIHandler> uiHandlers = new HashMap<>();
	private boolean uiActive = true; // can be used to deactivate UIs for this shopkeeper

	// CONSTRUCTION AND SETUP

	/**
	 * Creates a shopkeeper.
	 * <p>
	 * Important: Depending on whether the shopkeeper gets freshly created or loaded, either
	 * {@link #initOnCreation(ShopCreationData)} or {@link #initOnLoad(ConfigurationSection)} need to be called to
	 * complete the initialization.
	 * 
	 * @param id
	 *            the shopkeeper id
	 */
	protected AbstractShopkeeper(int id) {
		this.id = id;
	}

	/**
	 * Initializes the shopkeeper by using the data from the given {@link ShopCreationData}.
	 * 
	 * @param shopCreationData
	 *            the shop creation data
	 * @throws ShopkeeperCreateException
	 *             in case the shopkeeper could not be created
	 */
	protected final void initOnCreation(ShopCreationData shopCreationData) throws ShopkeeperCreateException {
		this.loadFromCreationData(shopCreationData);
		this.commonSetup();
	}

	/**
	 * Initializes the shopkeeper by loading its previously saved data from the given config section.
	 * 
	 * @param configSection
	 *            the config section
	 * @throws ShopkeeperCreateException
	 *             in case the shopkeeper could not be loaded
	 */
	protected final void initOnLoad(ConfigurationSection configSection) throws ShopkeeperCreateException {
		this.loadFromSaveData(configSection);
		this.commonSetup();
	}

	private void commonSetup() {
		this.setup();
		this.postSetup();
	}

	/**
	 * Initializes the shopkeeper by using the data from the given {@link ShopCreationData}.
	 * 
	 * @param shopCreationData
	 *            the shop creation data
	 * @throws ShopkeeperCreateException
	 *             if the shopkeeper cannot be properly initialized
	 */
	protected void loadFromCreationData(ShopCreationData shopCreationData) throws ShopkeeperCreateException {
		assert shopCreationData != null;
		this.uniqueId = UUID.randomUUID();
		Location spawnLocation = shopCreationData.getSpawnLocation();
		assert spawnLocation != null;
		this.worldName = spawnLocation.getWorld().getName();
		this.x = spawnLocation.getBlockX();
		this.y = spawnLocation.getBlockY();
		this.z = spawnLocation.getBlockZ();
		this.updateChunkCoords();

		ShopObjectType<?> shopObjectType = shopCreationData.getShopObjectType();
		Validate.isTrue(shopObjectType instanceof AbstractShopObjectType,
				"Expecting an AbstractShopObjectType, got " + shopObjectType.getClass().getName());
		this.shopObject = ((AbstractShopObjectType<?>) shopObjectType).createObject(this, shopCreationData);

		// automatically mark new shopkeepers as dirty:
		this.markDirty();
	}

	/**
	 * This gets called at the end of construction, after the shopkeeper data has been loaded.
	 * <p>
	 * This can be used to perform any remaining setup.
	 * <p>
	 * This might setup defaults for some things, if not yet specified by the sub-classes. So if you are overriding this
	 * method, consider doing your own setup before calling the overridden method. And also take into account that
	 * further sub-classes might perform their setup prior to calling your setup method as well. So don't replace any
	 * components that have already been setup by further sub-classes.
	 */
	protected void setup() {
		// add a default trading handler, if none is provided:
		if (this.getUIHandler(DefaultUITypes.TRADING()) == null) {
			this.registerUIHandler(new TradingHandler(SKDefaultUITypes.TRADING(), this));
		}
	}

	/**
	 * This gets called after {@link #setup()} and might be used to perform any setup that is intended to definitely
	 * happen last.
	 */
	protected void postSetup() {
		// inform shop object:
		this.getShopObject().setup();
	}

	// STORAGE

	/**
	 * Loads the shopkeeper's saved data from the given config section.
	 * 
	 * @param configSection
	 *            the config section
	 * @throws ShopkeeperCreateException
	 *             if the shopkeeper cannot be properly loaded
	 */
	protected void loadFromSaveData(ConfigurationSection configSection) throws ShopkeeperCreateException {
		String uniqueIdString = configSection.getString("uniqueId", "");
		try {
			this.uniqueId = UUID.fromString(uniqueIdString);
		} catch (IllegalArgumentException e) {
			if (!uniqueIdString.isEmpty()) {
				Log.warning("Shopkeeper '" + id + "' has an invalid unique id '" + uniqueIdString + "'. Creating a new one.");
			}
			this.uniqueId = UUID.randomUUID();
			this.markDirty();
		}

		this.name = this.trimName(Utils.colorize(configSection.getString("name", "")));
		this.worldName = configSection.getString("world");
		this.x = configSection.getInt("x");
		this.y = configSection.getInt("y");
		this.z = configSection.getInt("z");
		this.updateChunkCoords();

		// shop object:
		String objectTypeId;
		ConfigurationSection objectSection = configSection.getConfigurationSection("object");
		if (objectSection == null) {
			// load from legacy data:
			// TODO remove again at some point
			objectTypeId = configSection.getString("object");
			objectSection = configSection;
			this.markDirty();
		} else {
			objectTypeId = objectSection.getString("type");
		}

		// convert legacy object identifiers:
		if (objectTypeId != null) {
			// 'block' -> 'sign'
			if (objectTypeId.equalsIgnoreCase("block")) {
				objectTypeId = "sign";
				this.markDirty();
			}
			// normalize:
			String normalizedOjectTypeId = StringUtils.normalize(objectTypeId);
			if (!normalizedOjectTypeId.equals(objectTypeId)) {
				objectTypeId = normalizedOjectTypeId;
				this.markDirty();
			}
		}

		AbstractShopObjectType<?> objectType = SKShopkeepersPlugin.getInstance().getShopObjectTypeRegistry().get(objectTypeId);
		if (objectType == null) {
			// couldn't find object type by id, try to find object type via matching:
			objectType = SKShopkeepersPlugin.getInstance().getShopObjectTypeRegistry().match(objectTypeId);
			if (objectType != null) {
				// mark dirty, so the correct id gets saved:
				this.markDirty();
			} else {
				throw new ShopkeeperCreateException("Invalid object type for shopkeeper '" + id + "': " + objectTypeId);
			}
		}

		this.shopObject = objectType.createObject(this, null);
		this.shopObject.load(objectSection);
	}

	/**
	 * Saves the shopkeeper's data to the specified configuration section.
	 * <p>
	 * Note: The serialization of the inserted data may happen asynchronously, so make sure that this is not a problem
	 * (ex. only insert immutable objects, or always create copies of the data you insert and/or make sure to not modify
	 * the inserted objects).
	 * 
	 * @param configSection
	 *            the config section
	 */
	public void save(ConfigurationSection configSection) {
		configSection.set("uniqueId", uniqueId.toString());
		configSection.set("name", Utils.decolorize(name));
		configSection.set("world", worldName);
		configSection.set("x", x);
		configSection.set("y", y);
		configSection.set("z", z);
		configSection.set("type", this.getType().getIdentifier());

		// shop object:
		ConfigurationSection objectSection = configSection.createSection("object");
		shopObject.save(objectSection);
	}

	@Override
	public void save() {
		this.markDirty();
		ShopkeepersPlugin.getInstance().getShopkeeperStorage().save();
	}

	@Override
	public void saveDelayed() {
		this.markDirty();
		ShopkeepersPlugin.getInstance().getShopkeeperStorage().saveDelayed();
	}

	/**
	 * Marks this shopkeeper as 'dirty'. Its data gets saved with the next save of the {@link ShopkeeperStorage}.
	 * <p>
	 * The shopkeeper and the shop object implementations are responsible for marking the shopkeeper as dirty on every
	 * change affecting data that needs to be persisted.
	 */
	public void markDirty() {
		dirty = true;
		// inform the storage that there are dirty shopkeepers:
		if (this.isValid()) {
			// if the shopkeeper gets marked dirty during creation or loading (while it is not yet valid),
			// the storage gets marked dirty by the shopkeeper registry after the creation/loading was successful
			SKShopkeepersPlugin.getInstance().getShopkeeperStorage().markDirty();
		}
	}

	@Override
	public boolean isDirty() {
		return dirty;
	}

	// called by shopkeeper storage once the shopkeeper data gets saved
	public void onSave() {
		dirty = false;
	}

	// LIFE CYCLE

	@Override
	public boolean isValid() {
		return valid;
	}

	public final void informAdded(ShopkeeperAddedEvent.Cause cause) {
		assert !valid;
		valid = true;
		this.onAdded(cause);
	}

	/**
	 * This gets called once the shopkeeper has been added to the {@link ShopkeeperRegistry}.
	 * <p>
	 * The shopkeeper has not yet been activated at this point.
	 * 
	 * @param cause
	 *            the cause for the addition
	 */
	protected void onAdded(ShopkeeperAddedEvent.Cause cause) {
	}

	public final void informRemoval(ShopkeeperRemoveEvent.Cause cause) {
		assert valid;
		this.onRemoval(cause);
		if (cause == ShopkeeperRemoveEvent.Cause.DELETE) {
			this.onDeletion();
		}
		valid = false;
	}

	/**
	 * This gets called once the shopkeeper is about to be removed from the {@link ShopkeeperRegistry}.
	 * <p>
	 * The shopkeeper has already been deactivated at this point.
	 * 
	 * @param cause
	 *            the cause for the removal
	 */
	protected void onRemoval(ShopkeeperRemoveEvent.Cause cause) {
		shopObject.remove();
	}

	@Override
	public void delete() {
		this.markDirty();
		SKShopkeepersPlugin.getInstance().getShopkeeperRegistry().deleteShopkeeper(this);
	}

	/**
	 * This gets called if the shopkeeper is about to be removed due to permanent deletion.
	 * <p>
	 * This gets called after {@link #onRemoval(com.nisovin.shopkeepers.api.events.ShopkeeperRemoveEvent.Cause)}.
	 */
	protected void onDeletion() {
		shopObject.delete();
	}

	// ATTRIBUTES

	@Override
	public int getId() {
		return id;
	}

	@Override
	public UUID getUniqueId() {
		return uniqueId;
	}

	@Override
	public abstract AbstractShopType<?> getType();

	@Override
	public String getWorldName() {
		return worldName;
	}

	@Override
	public int getX() {
		return x;
	}

	@Override
	public int getY() {
		return y;
	}

	@Override
	public int getZ() {
		return z;
	}

	@Override
	public String getPositionString() {
		return Utils.getLocationString(worldName, x, y, z);
	}

	@Override
	public Location getLocation() {
		World world = Bukkit.getWorld(worldName);
		if (world == null) return null;
		return new Location(world, x, y, z);
	}

	/**
	 * Sets the stored location of this shopkeeper.
	 * <p>
	 * This will not actually move the shop object on its own, until the next time it gets spawned at or teleported to
	 * its intended location.
	 * 
	 * @param location
	 *            the new stored location of this shopkeeper
	 */
	public void setLocation(Location location) {
		this.markDirty();
		ChunkCoords oldChunk = this.getChunkCoords();
		x = location.getBlockX();
		y = location.getBlockY();
		z = location.getBlockZ();
		worldName = location.getWorld().getName();
		this.updateChunkCoords();

		// update shopkeeper in chunk map:
		SKShopkeepersPlugin.getInstance().getShopkeeperRegistry().onShopkeeperMove(this, oldChunk);
	}

	@Override
	public ChunkCoords getChunkCoords() {
		return chunkCoords;
	}

	private void updateChunkCoords() {
		this.chunkCoords = ChunkCoords.fromBlockPos(worldName, x, z);
	}

	// TRADING

	@Override
	public abstract List<TradingRecipe> getTradingRecipes(Player player);

	// ACTIVATION

	@Override
	public AbstractShopObject getShopObject() {
		return shopObject;
	}

	@Override
	public boolean needsSpawning() {
		return shopObject.getType().needsSpawning();
	}

	@Override
	public boolean isActive() {
		return shopObject.isActive();
	}

	@Override
	public String getObjectId() {
		return shopObject.getId();
	}

	@Override
	public Location getObjectLocation() {
		return shopObject.getLocation();
	}

	@Override
	public boolean spawn() {
		return shopObject.spawn();
	}

	@Override
	public void despawn() {
		shopObject.despawn();
	}

	public void onChunkLoad(boolean worldSaving) {
		shopObject.onChunkLoad(worldSaving);
	}

	public void onChunkUnload(boolean worldSaving) {
		shopObject.onChunkUnload(worldSaving);
	}

	/**
	 * See {@link AbstractShopObject#check()}.
	 * 
	 * @return <code>true</code> to if the shop object might no longer be active or its id has changed
	 */
	public boolean check() {
		return shopObject.check();
	}

	// USER INTERFACES

	@Override
	public boolean isUIActive() {
		return uiActive;
	}

	@Override
	public void deactivateUI() {
		uiActive = false;
	}

	@Override
	public void activateUI() {
		uiActive = true;
	}

	@Override
	public void closeAllOpenWindows() {
		ShopkeepersPlugin.getInstance().getUIRegistry().closeAllDelayed(this);
	}

	/**
	 * Registers an {@link UIHandler} which handles a specific type of user interface for this shopkeeper.
	 * <p>
	 * This replaces any {@link UIHandler} which has been previously registered for the same {@link UIType}.
	 * 
	 * @param uiHandler
	 *            the ui handler
	 */
	public void registerUIHandler(UIHandler uiHandler) {
		Validate.notNull(uiHandler, "UI handler is null!");
		uiHandlers.put(uiHandler.getUIType().getIdentifier(), uiHandler);
	}

	/**
	 * Gets the {@link UIHandler} this shopkeeper is using for the specified {@link UIType}.
	 * 
	 * @param uiType
	 *            the ui type
	 * @return the ui handler, or <code>null</code> if none is available
	 */
	public UIHandler getUIHandler(UIType uiType) {
		Validate.notNull(uiType, "UI type is null!");
		return uiHandlers.get(uiType.getIdentifier());
	}

	@Override
	public boolean openWindow(UIType uiType, Player player) {
		return SKShopkeepersPlugin.getInstance().getUIRegistry().requestUI(uiType, this, player);
	}

	// shortcuts for the default UI types:

	@Override
	public boolean openEditorWindow(Player player) {
		return this.openWindow(DefaultUITypes.EDITOR(), player);
	}

	@Override
	public boolean openTradingWindow(Player player) {
		return this.openWindow(DefaultUITypes.TRADING(), player);
	}

	// NAMING:

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void setName(String newName) {
		// prepare and apply new name:
		String preparedName = newName;
		if (preparedName == null) preparedName = "";
		preparedName = Utils.colorize(preparedName);
		preparedName = this.trimName(preparedName);
		this.name = preparedName;

		// update shop object:
		shopObject.setName(preparedName);
		this.markDirty(); // mark dirty
	}

	public boolean isValidName(String name) {
		return (name != null && name.length() <= MAX_NAME_LENGTH && name.matches("^" + Settings.nameRegex + "$"));
	}

	private String trimName(String name) {
		if (name.length() <= MAX_NAME_LENGTH) {
			return name;
		}
		String trimmedName = name.substring(0, MAX_NAME_LENGTH);
		Log.warning("Name of shopkeeper " + id + " is longer than " + MAX_NAME_LENGTH
				+ ". Trimming name '" + name + "' to '" + trimmedName + "'.");
		return trimmedName;
	}

	// INTERACTION HANDLING

	/**
	 * Called when a player interacts with this shopkeeper.
	 * 
	 * @param player
	 *            the interacting player
	 */
	public void onPlayerInteraction(Player player) {
		assert player != null;
		if (player.isSneaking()) {
			// open editor window:
			this.openEditorWindow(player);
		} else {
			// open trading window:
			this.openTradingWindow(player);
		}
	}
}
