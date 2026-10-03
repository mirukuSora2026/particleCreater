package com.mirukusora26.particleCreater.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/** Durable SQLite definitions. JSON is the interchange format, not a second source of truth. */
public final class EffectRepository implements AutoCloseable {
    private static final int MAX_JSON_BYTES = 1_048_576;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Connection connection;
    private final Path exports;
    private final Logger logger;

    public EffectRepository(Path dataFolder, Logger logger) throws SQLException, IOException {
        this.logger=logger;
        Files.createDirectories(dataFolder);
        exports=dataFolder.resolve("exports");
        Files.createDirectories(exports);
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new SQLException("SQLite driver is missing from plugin jar",e); }
        connection=DriverManager.getConnection("jdbc:sqlite:"+dataFolder.resolve("effects.db").toAbsolutePath());
        try(Statement st=connection.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS effects (name TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, definition TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS saved_locations (name TEXT PRIMARY KEY, world_uuid TEXT NOT NULL, world_name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, updated_at INTEGER NOT NULL)");
        } catch(SQLException e) {
            try {connection.close();} catch(SQLException close) {e.addSuppressed(close);}
            throw e;
        }
    }

    public synchronized List<EffectDefinition> loadAll() throws SQLException {
        List<EffectDefinition> effects=new ArrayList<>();
        try(Statement st=connection.createStatement(); ResultSet rs=st.executeQuery("SELECT name,definition FROM effects ORDER BY name")) {
            while(rs.next()) {
                try {
                    EffectDefinition effect=parse(rs.getString(2));
                    if(!rs.getString(1).equals(effect.name)) throw new IllegalArgumentException("Stored name '"+rs.getString(1)+"' differs from definition name '"+effect.name+"'");
                    effects.add(effect);
                } catch(RuntimeException e) { logger.warning("[STORAGE] Skipping invalid saved effect '"+rs.getString(1)+"' during database load: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())); }
            }
        }
        return effects;
    }

    public synchronized Optional<EffectDefinition> find(String name) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT definition FROM effects WHERE name=?")) {
            ps.setString(1,name);
            try(ResultSet rs=ps.executeQuery()) { return rs.next()?Optional.of(parse(rs.getString(1))):Optional.empty(); }
        }
    }

    public synchronized void save(EffectDefinition effect) throws SQLException {
        requireEffectName(effect);
        String json=encode(effect);
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO effects(name,schema_version,definition,updated_at) VALUES(?,?,?,?) ON CONFLICT(name) DO UPDATE SET schema_version=excluded.schema_version, definition=excluded.definition, updated_at=excluded.updated_at")) {
            ps.setString(1,effect.name);ps.setInt(2,effect.schemaVersion);ps.setString(3,json);ps.setLong(4,System.currentTimeMillis());ps.executeUpdate();
        }
    }

    public synchronized void rename(String oldName,EffectDefinition renamed) throws SQLException {
        requireEffectName(renamed);
        String json=encode(renamed);
        try(PreparedStatement ps=connection.prepareStatement("UPDATE effects SET name=?,schema_version=?,definition=?,updated_at=? WHERE name=?")) {
            ps.setString(1,renamed.name);ps.setInt(2,renamed.schemaVersion);ps.setString(3,json);ps.setLong(4,System.currentTimeMillis());ps.setString(5,oldName);
            if(ps.executeUpdate()!=1) throw new SQLException("Missing original effect: "+oldName);
        }
    }

    public synchronized boolean delete(String name) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("DELETE FROM effects WHERE name=?")) {ps.setString(1,name);return ps.executeUpdate()==1;}
    }

    public synchronized void saveLocation(SavedLocation location) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO saved_locations(name,world_uuid,world_name,x,y,z,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,updated_at=excluded.updated_at")) {
            ps.setString(1,location.name());ps.setString(2,location.worldId().toString());ps.setString(3,location.worldName());
            ps.setDouble(4,location.x());ps.setDouble(5,location.y());ps.setDouble(6,location.z());ps.setLong(7,System.currentTimeMillis());ps.executeUpdate();
        }
    }

    public synchronized Optional<SavedLocation> findLocation(String name) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT name,world_uuid,world_name,x,y,z FROM saved_locations WHERE name=?")) {
            ps.setString(1,name);
            try(ResultSet rs=ps.executeQuery()) {return rs.next()?Optional.of(readLocation(rs)):Optional.empty();}
        }
    }

    public synchronized List<String> locationNames() throws SQLException {
        List<String> names=new ArrayList<>();
        try(Statement st=connection.createStatement();ResultSet rs=st.executeQuery("SELECT name FROM saved_locations ORDER BY name")) {
            while(rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    public synchronized int locationCount() throws SQLException {
        try(Statement st=connection.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM saved_locations")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public synchronized List<String> locationPage(int offset,int limit) throws SQLException {
        if(offset<0||limit<1||limit>100) throw new IllegalArgumentException("Location page size must be 1..100.");
        List<String> names=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT name FROM saved_locations ORDER BY name LIMIT ? OFFSET ?")) {
            ps.setInt(1,limit);ps.setInt(2,offset);
            try(ResultSet rs=ps.executeQuery()) {while(rs.next()) names.add(rs.getString(1));}
        }
        return names;
    }

    public synchronized List<String> suggestLocationNames(String prefix,int limit) throws SQLException {
        if(prefix==null) throw new IllegalArgumentException("Location name prefix is required.");
        if(limit<1) return List.of();
        List<String> names=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT name FROM saved_locations WHERE name>=? AND name<? ORDER BY name LIMIT ?")) {
            ps.setString(1,prefix);ps.setString(2,prefix+'\uffff');ps.setInt(3,Math.min(limit,100));
            try(ResultSet rs=ps.executeQuery()) {while(rs.next()) names.add(rs.getString(1));}
        }
        return names;
    }

    public synchronized boolean deleteLocation(String name) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("DELETE FROM saved_locations WHERE name=?")) {
            ps.setString(1,name);return ps.executeUpdate()==1;
        }
    }

    private static SavedLocation readLocation(ResultSet rs) throws SQLException {
        try {
            return new SavedLocation(rs.getString(1),UUID.fromString(rs.getString(2)),rs.getString(3),rs.getDouble(4),rs.getDouble(5),rs.getDouble(6));
        } catch(IllegalArgumentException e) {throw new SQLException("Invalid saved location: "+rs.getString(1),e);}
    }

    public Path exportJson(EffectDefinition effect) throws IOException {
        requireEffectName(effect);
        Path target=exports.resolve(effect.name+".json");
        Path temporary=Files.createTempFile(exports,"effect-",".tmp");
        try {
            Files.writeString(temporary,encode(effect),StandardCharsets.UTF_8);
            try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e) {Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
        return target;
    }

    public EffectDefinition importJson(String fileName) throws IOException {
        if(!fileName.matches("[a-z0-9_-]{1,48}\\.json")) throw new IllegalArgumentException("Import file must be a name.json in the exports folder");
        Path file=exports.resolve(fileName);
        if(Files.size(file)>MAX_JSON_BYTES) throw new IllegalArgumentException("Import file exceeds 1 MiB");
        return parse(Files.readString(file,StandardCharsets.UTF_8));
    }

    public EffectDefinition copy(EffectDefinition effect) { return parse(encode(effect)); }

    private String encode(EffectDefinition effect) {
        if(effect==null) throw new IllegalArgumentException("Missing effect definition");
        String json=gson.toJson(effect);
        if(json.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES) throw new IllegalArgumentException("Effect definition exceeds 1 MiB");
        return json;
    }
    private EffectDefinition parse(String json) {
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES) throw new IllegalArgumentException("Effect definition exceeds 1 MiB or is missing");
        try {
            EffectDefinition result=gson.fromJson(json,EffectDefinition.class);
            if(result==null||result.schemaVersion!=1) throw new IllegalArgumentException("Unsupported or missing schemaVersion");
            return result;
        } catch(JsonParseException e) {throw new IllegalArgumentException("Invalid effect JSON: "+e.getMessage(),e);}
    }

    private static void requireEffectName(EffectDefinition effect) {
        if(effect==null||effect.name==null||!effect.name.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid effect name");
    }

    @Override public synchronized void close() throws SQLException {connection.close();}
}
