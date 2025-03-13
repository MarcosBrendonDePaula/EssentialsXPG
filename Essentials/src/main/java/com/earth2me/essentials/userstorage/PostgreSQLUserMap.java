package com.earth2me.essentials.userstorage;

import com.earth2me.essentials.OfflinePlayerStub;
import com.earth2me.essentials.User;
import com.earth2me.essentials.config.EssentialsConfiguration;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.ess3.api.IEssentials;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class PostgreSQLUserMap extends ModernUserMap {
    private final transient IEssentials ess;
    private final transient LoadingCache<UUID, User> userCache;
    private final transient ConcurrentMap<String, UUID> nameCache = new ConcurrentHashMap<>();
    
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private Connection connection;
    
    // Debug settings
    private final boolean debugPrintStackWithWarn;
    private final long debugMaxWarnsPerType;
    private final boolean debugLogCache;
    private final ConcurrentMap<String, AtomicLong> debugNonPlayerWarnCounts;

    public PostgreSQLUserMap(final IEssentials ess) {
        super(ess);
        this.ess = ess;
        
        // Get database configuration from Essentials settings
        final EssentialsConfiguration config = new EssentialsConfiguration(new File(ess.getDataFolder(), "config.yml"));
        config.load();
        this.jdbcUrl = config.getString("database.jdbc-url", "jdbc:postgresql://localhost:5432/essentials");
        this.username = config.getString("database.username", "postgres");
        this.password = config.getString("database.password", "");
        
        this.userCache = CacheBuilder.newBuilder()
                .maximumSize(ess.getSettings().getMaxUserCacheCount())
                .expireAfterAccess(ess.getSettings().getMaxUserCacheValueExpiry(), TimeUnit.SECONDS)
                .softValues()
                .build(new CacheLoader<UUID, User>() {
                    @Override
                    public User load(final UUID uuid) throws Exception {
                        return loadUncachedUser(uuid);
                    }
                });

        // -Dnet.essentialsx.usermap.print-stack=true
        final String printStackProperty = System.getProperty("net.essentialsx.usermap.print-stack", "false");
        // -Dnet.essentialsx.usermap.max-warns=20
        final String maxWarnProperty = System.getProperty("net.essentialsx.usermap.max-warns", "10");
        // -Dnet.essentialsx.usermap.log-cache=true
        final String logCacheProperty = System.getProperty("net.essentialsx.usermap.log-cache", "false");

        this.debugMaxWarnsPerType = Long.parseLong(maxWarnProperty);
        this.debugPrintStackWithWarn = Boolean.parseBoolean(printStackProperty);
        this.debugLogCache = Boolean.parseBoolean(logCacheProperty);
        this.debugNonPlayerWarnCounts = new ConcurrentHashMap<>();
        
        // Initialize database
        initializeDatabase();
    }
    
    private void initializeDatabase() {
        try {
            // Load PostgreSQL JDBC driver
            Class.forName("org.postgresql.Driver");
            
            // Create connection
            connection = DriverManager.getConnection(jdbcUrl, username, password);
            
            // Create tables if they don't exist
            createTables();
            
            // Load name cache
            loadNameCache();
            
            ess.getLogger().info("Successfully connected to PostgreSQL database");
        } catch (final ClassNotFoundException e) {
            ess.getLogger().log(Level.SEVERE, "PostgreSQL JDBC driver not found. Make sure you have the PostgreSQL JDBC driver in your classpath", e);
        } catch (final SQLException e) {
            ess.getLogger().log(Level.SEVERE, "Failed to connect to PostgreSQL database", e);
        }
    }
    
    private void createTables() throws SQLException {
        try (final Statement stmt = connection.createStatement()) {
            // Create users table
            stmt.execute("CREATE TABLE IF NOT EXISTS essentials_users (" +
                    "uuid UUID PRIMARY KEY, " +
                    "last_account_name VARCHAR(16), " +
                    "data JSONB NOT NULL" +
                    ")");
            
            // Create name cache table
            stmt.execute("CREATE TABLE IF NOT EXISTS essentials_name_cache (" +
                    "name VARCHAR(16) PRIMARY KEY, " +
                    "uuid UUID NOT NULL" +
                    ")");
        }
    }
    
    private void loadNameCache() throws SQLException {
        try (final Statement stmt = connection.createStatement();
             final ResultSet rs = stmt.executeQuery("SELECT name, uuid FROM essentials_name_cache")) {
            
            while (rs.next()) {
                final String name = rs.getString("name");
                final UUID uuid = UUID.fromString(rs.getString("uuid"));
                nameCache.put(name, uuid);
            }
        }
    }

    @Override
    public Set<UUID> getAllUserUUIDs() {
        final Set<UUID> uuids = new HashSet<>();
        
        try (final Statement stmt = connection.createStatement();
             final ResultSet rs = stmt.executeQuery("SELECT uuid FROM essentials_users")) {
            
            while (rs.next()) {
                uuids.add(UUID.fromString(rs.getString("uuid")));
            }
        } catch (final SQLException e) {
            ess.getLogger().log(Level.SEVERE, "Failed to get all user UUIDs", e);
        }
        
        return uuids;
    }

    @Override
    public long getCachedCount() {
        return userCache.size();
    }

    @Override
    public int getUserCount() {
        try (final Statement stmt = connection.createStatement();
             final ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM essentials_users")) {
            
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (final SQLException e) {
            ess.getLogger().log(Level.SEVERE, "Failed to get user count", e);
        }
        
        return 0;
    }

    @Override
    public User getUser(final UUID uuid) {
        if (uuid == null) {
            return null;
        }

        try {
            return userCache.get(uuid);
        } catch (final ExecutionException e) {
            if (ess.getSettings().isDebug()) {
                ess.getLogger().log(Level.WARNING, "Exception while getting user for " + uuid, e);
            }
            return null;
        }
    }

    @Override
    public User getUser(final Player base) {
        final User user = loadUncachedUser(base);
        userCache.put(user.getUUID(), user);
        debugLogCache(user);
        return user;
    }

    @Override
    public User getUser(final String name) {
        if (name == null) {
            return null;
        }

        final UUID uuid = nameCache.get(name.toLowerCase());
        if (uuid == null) {
            return null;
        }
        
        final User user = getUser(uuid);
        if (user != null && user.getBase() instanceof OfflinePlayerStub) {
            if (user.getLastAccountName() != null) {
                ((OfflinePlayerStub) user.getBase()).setName(user.getLastAccountName());
            } else {
                ((OfflinePlayerStub) user.getBase()).setName(name);
            }
        }
        return user;
    }

    public void addCachedNpcName(final UUID uuid, final String name) {
        if (uuid == null || name == null) {
            return;
        }

        updateNameCache(uuid, name);
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public User load(final UUID uuid) throws Exception {
        final User user = loadUncachedUser(uuid);
        if (user != null) {
            debugLogCache(user);
            return user;
        }

        throw new Exception("User not found!");
    }

    @Override
    public User loadUncachedUser(final Player base) {
        if (base == null) {
            return null;
        }

        User user = getUser(base.getUniqueId());
        if (user == null) {
            debugLogUncachedNonPlayer(base);
            user = new User(base, ess);
        } else if (!base.equals(user.getBase())) {
            if (ess.getSettings().isDebug()) {
                ess.getLogger().log(Level.INFO, "Essentials updated the underlying Player object for " + user.getUUID());
            }
            user.update(base);
        }
        updateNameCache(user.getUUID(), user.getName());

        return user;
    }

    @Override
    public User loadUncachedUser(final UUID uuid) {
        final User user = userCache.getIfPresent(uuid);
        if (user != null) {
            return user;
        }

        final Player player = ess.getServer().getPlayer(uuid);
        if (player != null) {
            // This is a real player, cache their UUID.
            final User newUser = new User(player, ess);
            updateNameCache(uuid, player.getName());
            return newUser;
        }

        try {
            // Check if user exists in database
            try (final PreparedStatement stmt = connection.prepareStatement("SELECT last_account_name, data FROM essentials_users WHERE uuid = ?")) {
                stmt.setObject(1, uuid);
                try (final ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        final Player offlinePlayer = new OfflinePlayerStub(uuid, ess.getServer());
                        final User offlineUser = new User(offlinePlayer, ess);
                        
                        // Load user data from database
                        final String lastAccountName = rs.getString("last_account_name");
                        if (lastAccountName != null) {
                            ((OfflinePlayerStub) offlinePlayer).setName(lastAccountName);
                            
                            // Check if there's already a UUID mapping for the name in the name cache
                            if (!nameCache.containsKey(lastAccountName.toLowerCase())) {
                                updateNameCache(uuid, lastAccountName);
                            }
                        }
                        
                        // Load user data from JSON
                        final String jsonData = rs.getString("data");
                        if (jsonData != null && !jsonData.isEmpty()) {
                            try {
                                final Gson gson = new Gson();
                                final JsonObject userData = gson.fromJson(jsonData, JsonObject.class);
                                
                                // Set user properties from JSON data
                                if (userData.has("money")) {
                                    try {
                                        offlineUser.setMoney(new java.math.BigDecimal(userData.get("money").getAsString()));
                                    } catch (final Exception e) {
                                        ess.getLogger().log(Level.WARNING, "Failed to set money for user " + uuid, e);
                                    }
                                }
                                
                                if (userData.has("nickname")) {
                                    offlineUser.setNickname(userData.get("nickname").getAsString());
                                }
                                
                                if (userData.has("godmode")) {
                                    offlineUser.setGodModeEnabled(userData.get("godmode").getAsBoolean());
                                }
                                
                                if (userData.has("muted")) {
                                    offlineUser.setMuted(userData.get("muted").getAsBoolean());
                                }
                                
                                if (userData.has("jailed")) {
                                    offlineUser.setJailed(userData.get("jailed").getAsBoolean());
                                }
                                
                                if (userData.has("jail")) {
                                    offlineUser.setJail(userData.get("jail").getAsString());
                                }
                                
                                // Set timestamps
                                if (userData.has("timestamps")) {
                                    final JsonObject timestamps = userData.getAsJsonObject("timestamps");
                                    
                                    if (timestamps.has("login")) {
                                        offlineUser.setLastLogin(timestamps.get("login").getAsLong());
                                    }
                                    
                                    if (timestamps.has("logout")) {
                                        offlineUser.setLastLogout(timestamps.get("logout").getAsLong());
                                    }
                                    
                                    if (timestamps.has("mute")) {
                                        offlineUser.setMuteTimeout(timestamps.get("mute").getAsLong());
                                    }
                                    
                                    if (timestamps.has("jail")) {
                                        offlineUser.setJailTimeout(timestamps.get("jail").getAsLong());
                                    }
                                }
                            } catch (final Exception e) {
                                ess.getLogger().log(Level.SEVERE, "Failed to parse user data from JSON for user " + uuid, e);
                            }
                        }
                        
                        return offlineUser;
                    }
                }
            }
        } catch (final SQLException e) {
            ess.getLogger().log(Level.SEVERE, "Failed to load user from database: " + uuid, e);
        }

        return null;
    }

    @Override
    public Map<String, UUID> getNameCache() {
        return nameCache;
    }

    public String getSanitizedName(final String name) {
        return name.toLowerCase();
    }

    public void blockingSave() {
        // Save all cached users to database
        for (final User user : userCache.asMap().values()) {
            saveUser(user);
        }
    }

    public void invalidate(final UUID uuid) {
        userCache.invalidate(uuid);
        
        // Remove from name cache
        final String nameToRemove = nameCache.entrySet().stream()
                .filter(entry -> entry.getValue().equals(uuid))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        
        if (nameToRemove != null) {
            nameCache.remove(nameToRemove);
            
            // Remove from database
            try (final PreparedStatement stmt = connection.prepareStatement("DELETE FROM essentials_name_cache WHERE name = ?")) {
                stmt.setString(1, nameToRemove);
                stmt.executeUpdate();
            } catch (final SQLException e) {
                ess.getLogger().log(Level.SEVERE, "Failed to remove name from cache: " + nameToRemove, e);
            }
        }
    }

    public void shutdown() {
        // Save all cached users
        blockingSave();
        
        // Close database connection
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (final SQLException e) {
            ess.getLogger().log(Level.SEVERE, "Failed to close database connection", e);
        }
    }
    
    private void saveUser(final User user) {
        try {
            // Create a JSON object with user data
            final JsonObject userData = new JsonObject();
            
            // Add basic user data
            userData.addProperty("money", user.getMoney().toString());
            userData.addProperty("nickname", user.getNickname());
            userData.addProperty("godmode", user.isGodModeEnabled());
            userData.addProperty("muted", user.isMuted());
            userData.addProperty("jailed", user.isJailed());
            userData.addProperty("jail", user.getJail());
            
            // Add timestamps
            final JsonObject timestamps = new JsonObject();
            timestamps.addProperty("login", user.getLastLogin());
            timestamps.addProperty("logout", user.getLastLogout());
            timestamps.addProperty("mute", user.getMuteTimeout());
            timestamps.addProperty("jail", user.getJailTimeout());
            userData.add("timestamps", timestamps);
            
            // Convert to JSON string
            final Gson gson = new GsonBuilder().setPrettyPrinting().create();
            final String jsonData = gson.toJson(userData);
            
            // Check if user exists
            final boolean exists;
            try (final PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM essentials_users WHERE uuid = ?")) {
                stmt.setObject(1, user.getUUID());
                try (final ResultSet rs = stmt.executeQuery()) {
                    exists = rs.next();
                }
            }
            
            // Insert or update user
            if (exists) {
                try (final PreparedStatement stmt = connection.prepareStatement(
                        "UPDATE essentials_users SET last_account_name = ?, data = ?::jsonb WHERE uuid = ?")) {
                    stmt.setString(1, user.getLastAccountName());
                    stmt.setString(2, jsonData);
                    stmt.setObject(3, user.getUUID());
                    stmt.executeUpdate();
                }
            } else {
                try (final PreparedStatement stmt = connection.prepareStatement(
                        "INSERT INTO essentials_users (uuid, last_account_name, data) VALUES (?, ?, ?::jsonb)")) {
                    stmt.setObject(1, user.getUUID());
                    stmt.setString(2, user.getLastAccountName());
                    stmt.setString(3, jsonData);
                    stmt.executeUpdate();
                }
            }
        } catch (final SQLException e) {
            ess.getLogger().log(Level.SEVERE, "Failed to save user to database: " + user.getUUID(), e);
        }
    }
    
    private void updateNameCache(final UUID uuid, final String name) {
        if (name == null) {
            return;
        }
        
        final String lowercaseName = name.toLowerCase();
        nameCache.put(lowercaseName, uuid);
        
        try {
            // Check if name exists in cache
            final boolean exists;
            try (final PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM essentials_name_cache WHERE name = ?")) {
                stmt.setString(1, lowercaseName);
                try (final ResultSet rs = stmt.executeQuery()) {
                    exists = rs.next();
                }
            }
            
            // Insert or update name cache
            if (exists) {
                try (final PreparedStatement stmt = connection.prepareStatement(
                        "UPDATE essentials_name_cache SET uuid = ? WHERE name = ?")) {
                    stmt.setObject(1, uuid);
                    stmt.setString(2, lowercaseName);
                    stmt.executeUpdate();
                }
            } else {
                try (final PreparedStatement stmt = connection.prepareStatement(
                        "INSERT INTO essentials_name_cache (name, uuid) VALUES (?, ?)")) {
                    stmt.setString(1, lowercaseName);
                    stmt.setObject(2, uuid);
                    stmt.executeUpdate();
                }
            }
        } catch (final SQLException e) {
            ess.getLogger().log(Level.SEVERE, "Failed to update name cache: " + name + " -> " + uuid, e);
        }
    }

    private void debugLogCache(final User user) {
        if (!debugLogCache) {
            return;
        }
        final Throwable throwable = new Throwable();
        ess.getLogger().log(Level.INFO, String.format("Caching user %s (%s)", user.getName(), user.getUUID()), throwable);
    }

    private void debugLogUncachedNonPlayer(final Player base) {
        final String typeName = base.getClass().getName();
        final long count = debugNonPlayerWarnCounts.computeIfAbsent(typeName, name -> new AtomicLong(0)).getAndIncrement();
        if (debugMaxWarnsPerType < 0 || count <= debugMaxWarnsPerType) {
            final Throwable throwable = debugPrintStackWithWarn ? new Throwable() : null;
            ess.getLogger().log(Level.INFO, "Created a User for " + base.getName() + " (" + base.getUniqueId() + ") for non Bukkit type: " + typeName, throwable);
            if (count == debugMaxWarnsPerType) {
                ess.getLogger().log(Level.WARNING, "Essentials will not log any more warnings for " + typeName + ". Please report this to the EssentialsX team.");
            }
        }
    }
}
