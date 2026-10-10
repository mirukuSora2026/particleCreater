package com.mirukusora26.particleCreater.engine;

import com.mirukusora26.particleCreater.math.Expression;
import com.mirukusora26.particleCreater.math.ShapeEngine;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import com.mirukusora26.particleCreater.model.EffectDefinition.Layer;
import com.mirukusora26.particleCreater.storage.EffectRepository;
import com.mirukusora26.particleCreater.storage.SavedLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/** Public Java API for saved definitions. All methods are server-thread only. */
public final class EffectService {
    public static final String NAME_PATTERN = "[a-z0-9_-]{1,48}";
    private final NavigableMap<String,EffectDefinition> definitions=new TreeMap<>();
    private final EffectRepository repository;
    private final Logger logger;
    private PlaybackManager playback;

    public EffectService(EffectRepository repository,Logger logger) throws SQLException {
        this.repository=repository;this.logger=logger;
        for(EffectDefinition effect:repository.loadAll()) {
            List<String> problems=validate(effect,false);
            if(problems.isEmpty()) definitions.put(effect.name,effect);
            else logger.warning("Skipping saved effect '"+effect.name+"': "+String.join("; ",problems));
        }
    }
    public void attachPlayback(PlaybackManager playback) {this.playback=playback;}
    public List<String> names() {primaryThread();return List.copyOf(definitions.keySet());}
    public List<String> suggestNames(String prefix,int limit) {
        primaryThread();
        List<String> matches=new ArrayList<>();
        for(String name:definitions.tailMap(prefix,true).keySet()) {
            if(!name.startsWith(prefix)||matches.size()>=limit) break;
            matches.add(name);
        }
        return matches;
    }
    public EffectDefinition get(String name) {
        primaryThread();
        EffectDefinition effect=definitions.get(name);
        if(effect==null) throw new IllegalArgumentException("Unknown effect: "+name);
        return repository.copy(effect);
    }
    public boolean exists(String name) {primaryThread();return definitions.containsKey(name);}

    public void create(String name) throws SQLException {
        if(exists(name)) throw new IllegalArgumentException("Effect already exists: "+name);
        save(new EffectDefinition(name));
    }
    public void save(EffectDefinition definition) throws SQLException {
        primaryThread();
        EffectDefinition copy=repository.copy(definition);
        List<String> problems=validate(copy,false);
        if(!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ",problems));
        repository.save(copy);
        definitions.put(copy.name,copy);
    }
    public void update(String name,Consumer<EffectDefinition> change) throws SQLException {
        EffectDefinition copy=get(name);
        change.accept(copy);
        if(!name.equals(copy.name)) throw new IllegalArgumentException("Use rename to change an effect name");
        save(copy);
    }
    public void copy(String source,String destination) throws SQLException {
        if(exists(destination)) throw new IllegalArgumentException("Effect already exists: "+destination);
        EffectDefinition copy=get(source);copy.name=destination;save(copy);
    }
    public void rename(String source,String destination) throws SQLException {
        primaryThread();
        if(exists(destination)) throw new IllegalArgumentException("Effect already exists: "+destination);
        EffectDefinition changed=get(source);changed.name=destination;
        List<String> problems=validate(changed,false);
        if(!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ",problems));
        repository.rename(source,changed);
        definitions.remove(source);definitions.put(destination,changed);
    }
    public void delete(String name) throws SQLException {
        primaryThread();
        if(!repository.delete(name)) throw new IllegalArgumentException("Unknown effect: "+name);
        definitions.remove(name);
    }
    public Path exportJson(String name) throws IOException {primaryThread();return repository.exportJson(get(name));}
    public void importJson(String file,String replacementName) throws IOException,SQLException {
        primaryThread();
        EffectDefinition definition=repository.importJson(file);
        if(replacementName!=null) definition.name=replacementName;
        if(exists(definition.name)) throw new IllegalArgumentException("Effect already exists: "+definition.name);
        save(definition);
    }

    public void saveLocation(SavedLocation location) throws SQLException {primaryThread();repository.saveLocation(location);}
    public SavedLocation getLocation(String name) throws SQLException {
        primaryThread();
        return repository.findLocation(name).orElseThrow(() -> new IllegalArgumentException("Saved location not found: "+name));
    }
    public Location resolveLocation(String name) throws SQLException {
        SavedLocation saved=getLocation(name);
        World world=Bukkit.getWorld(saved.worldId());
        if(world==null) throw new IllegalArgumentException("Saved location world is not loaded: "+saved.worldName()+" ("+saved.worldId()+")");
        return new Location(world,saved.x(),saved.y(),saved.z());
    }
    public List<String> locationNames() throws SQLException {primaryThread();return repository.locationNames();}
    public int locationCount() throws SQLException {primaryThread();return repository.locationCount();}
    public List<String> locationPage(int offset,int limit) throws SQLException {primaryThread();return repository.locationPage(offset,limit);}
    public List<String> suggestLocationNames(String prefix,int limit) throws SQLException {primaryThread();return repository.suggestLocationNames(prefix,limit);}
    public void deleteLocation(String name) throws SQLException {
        primaryThread();
        if(!repository.deleteLocation(name)) throw new IllegalArgumentException("Saved location not found: "+name);
    }

    public List<String> validate(String name) {return validate(get(name),true);}
    public List<String> validate(EffectDefinition effect,boolean requirePlayable) {
        primaryThread();
        List<String> errors=new ArrayList<>();
        if(effect==null) return List.of("Missing effect");
        if(effect.schemaVersion!=1) errors.add("Effect schemaVersion "+effect.schemaVersion+" is unsupported; expected 1");
        if(effect.name==null||!effect.name.matches(NAME_PATTERN)) errors.add("Effect name must use lowercase a-z, 0-9, _ or - (1..48)");
        if(effect.durationTicks!=-1&&(effect.durationTicks<1||effect.durationTicks>72000)) errors.add("Effect durationTicks="+effect.durationTicks+" must be -1 (infinite) or 1..72000");
        if(effect.intervalTicks<1||effect.intervalTicks>1200) errors.add("Effect intervalTicks="+effect.intervalTicks+" must be 1..1200");
        if(effect.anchor==null||!List.of("fixed","caller","target").contains(effect.anchor)) errors.add("Effect anchor='"+effect.anchor+"' must be fixed, caller or target");
        if(effect.mix==null||!List.of("together","weighted").contains(effect.mix)) errors.add("Effect mix='"+effect.mix+"' must be together or weighted");
        if(effect.parameters==null) errors.add("Effect parameters map is missing");
        if(effect.shapes==null) errors.add("Effect shapes list is missing");
        if(effect.layers==null) errors.add("Effect layers list is missing");
        if(effect.motion==null) errors.add("Effect motion is missing");
        if(effect.parameters==null||effect.shapes==null||effect.layers==null||effect.motion==null) return errors;
        if(effect.parameters.size()>32) errors.add("Effect has "+effect.parameters.size()+" parameters; limit is 32");
        if(effect.shapes.size()>64) errors.add("Effect has "+effect.shapes.size()+" shapes; limit is 64");
        if(effect.layers.size()>32) errors.add("Effect has "+effect.layers.size()+" layers; limit is 32");
        Set<String> variables=new HashSet<>(Set.of("x","y","z","w","u","v","t"));
        for(var entry:effect.parameters.entrySet()) {
            String name=entry.getKey();Double value=entry.getValue();
            if(name==null||!name.matches("[a-z][a-z0-9_]{0,31}")) errors.add("Parameter name '"+name+"' must match [a-z][a-z0-9_]{0,31}");
            else if(variables.contains(name)) errors.add("Parameter '"+name+"' conflicts with a reserved coordinate or time variable");
            if(value==null||!Double.isFinite(value)) errors.add("Parameter '"+name+"' value="+value+" must be finite");
            if(entry.getKey()!=null) variables.add(entry.getKey());
        }
        ShapeEngine shapeEngine=null;
        try {shapeEngine=new ShapeEngine(effect);}catch(RuntimeException e){errors.add("Shape validation failed: "+reason(e));}
        List<List<String>> motionGroups=Arrays.asList(effect.motion.move,effect.motion.rotate,effect.motion.scale);
        String[] motionNames={"move","rotate","scale"},axisNames={"x","y","z"};
        for(int group=0;group<motionGroups.size();group++) {
            List<String> values=motionGroups.get(group);
            if(values==null||values.size()!=3) {errors.add("Motion "+motionNames[group]+" requires exactly x, y, z formulas; found "+(values==null?"null":values.size()));continue;}
            for(int axis=0;axis<3;axis++) try {Expression.compile(values.get(axis),variables);}
            catch(RuntimeException e){errors.add("Motion "+motionNames[group]+"."+axisNames[axis]+" formula '"+values.get(axis)+"' failed: "+reason(e));}
        }
        Set<String> layerNames=new HashSet<>();
        String weightedShape=null;int weightedStart=0,weightedInterval=0,weightedDuration=0;double weightedTotal=0;
        for(Layer layer:effect.layers) {
            if(layer==null) {errors.add("Layer entry is null");continue;}
            if(layer.id==null||!layer.id.matches(NAME_PATTERN)) {errors.add("Layer id '"+layer.id+"' must use lowercase a-z, 0-9, _ or - (1..48)");continue;}
            if(!layerNames.add(layer.id)) {errors.add("Duplicate layer id '"+layer.id+"'");continue;}
            String field="Layer '"+layer.id+"' ";
            if(effect.shapes.stream().noneMatch(s -> s!=null&&Objects.equals(s.id,layer.shape))) errors.add(field+"references missing shape '"+layer.shape+"'");
            if(layer.count<0||layer.count>100) errors.add(field+"count="+layer.count+" must be 0..100");
            if(layer.offset==null||layer.offset.length!=3) errors.add(field+"offset must contain exactly x, y, z");
            else for(int axis=0;axis<3;axis++) if(!Double.isFinite(layer.offset[axis])||Math.abs(layer.offset[axis])>100) errors.add(field+"offset."+axisNames[axis]+"="+layer.offset[axis]+" must be finite and within +/-100");
            if(!Double.isFinite(layer.extra)||Math.abs(layer.extra)>100) errors.add(field+"extra="+layer.extra+" must be finite and within +/-100");
            if(layer.startTicks<0) errors.add(field+"startTicks="+layer.startTicks+" must be nonnegative");
            if(layer.intervalTicks<1||layer.intervalTicks>1200) errors.add(field+"intervalTicks="+layer.intervalTicks+" must be 1..1200");
            if(layer.durationTicks!=-1&&(layer.durationTicks<1||layer.durationTicks>72000)) errors.add(field+"durationTicks="+layer.durationTicks+" must be -1 (infinite) or 1..72000");
            if(!Double.isFinite(layer.weight)||layer.weight<=0) errors.add(field+"weight="+layer.weight+" must be finite and positive");
            try {
                Particle type=ParticleDataCodec.particle(layer.particle);
                Location sample=Bukkit.getWorlds().isEmpty()?new Location(null,0,0,0):new Location(Bukkit.getWorlds().getFirst(),0,0,0);
                ParticleDataCodec.validateProvided(layer,type,sample);
                boolean complete=switch(type.getDataType().getSimpleName()) {
                    case "Vibration" -> layer.data.containsKey("target")||layer.data.containsKey("entity");
                    case "DustTransition" -> layer.data.containsKey("color")&&layer.data.containsKey("to");
                    case "Trail" -> layer.data.containsKey("target")&&layer.data.containsKey("color");
                    case "DustOptions","Spell","Color" -> layer.data.containsKey("color");
                    case "BlockData" -> layer.data.containsKey("block");
                    case "ItemStack" -> layer.data.containsKey("item");
                    default -> true;
                };
                if(!requirePlayable&&type.getDataType()==org.bukkit.Vibration.class&&layer.data.containsKey("entity")) {
                    // The entity may not be loaded while editing or loading a definition.
                } else if(requirePlayable||complete) {
                    ParticleDataCodec.decode(layer,sample);
                }
            } catch(RuntimeException e){errors.add(field+"particle '"+layer.particle+"' data failed: "+reason(e));}
            if("weighted".equals(effect.mix)) {
                weightedTotal+=layer.weight;
                if(weightedShape==null) {weightedShape=layer.shape;weightedStart=layer.startTicks;weightedInterval=layer.intervalTicks;weightedDuration=layer.durationTicks;}
                else if(!Objects.equals(weightedShape,layer.shape)||weightedStart!=layer.startTicks||weightedInterval!=layer.intervalTicks||weightedDuration!=layer.durationTicks) errors.add(field+"weighted mix must use shape '"+weightedShape+"' and timing start="+weightedStart+", interval="+weightedInterval+", duration="+weightedDuration);
            }
        }
        if("weighted".equals(effect.mix)&&!Double.isFinite(weightedTotal)) errors.add("Effect weighted layer total="+weightedTotal+" must be finite");
        if(requirePlayable&&effect.layers.isEmpty()) errors.add("Effect has no particle layers");
        if(requirePlayable&&shapeEngine!=null&&errors.isEmpty()) {
            Set<String> sampled=new HashSet<>();
            for(Layer layer:effect.layers) if(sampled.add(layer.shape)) {
                try {shapeEngine.sample(layer.shape,0,effect.parameters,2048,p -> {});}
                catch(RuntimeException e){errors.add("Shape '"+layer.shape+"' used by layer '"+layer.id+"' sample failed: "+reason(e));}
            }
        }
        return errors;
    }

    public PlaybackManager.Handle play(String name,PlaybackManager.Context context) {
        primaryThread();
        if(playback==null) throw new IllegalStateException("Playback manager is not attached");
        return playback.play(get(name),context);
    }
    public void spawn(String name,PlaybackManager.Context context) {
        primaryThread();
        if(playback==null) throw new IllegalStateException("Playback manager is not attached");
        playback.spawn(get(name),context);
    }
    public boolean stop(String runId) {primaryThread();return playback!=null&&playback.stop(runId);}
    public boolean pause(String runId) {primaryThread();return playback!=null&&playback.pause(runId);}
    public boolean resume(String runId) {primaryThread();return playback!=null&&playback.resume(runId);}
    public int stopAll(String name) {primaryThread();return playback==null?0:playback.stopAll(name);}
    public List<PlaybackManager.Handle> running() {primaryThread();return playback==null?List.of():playback.running();}
    private static void primaryThread() {if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("EffectService must be called on the server thread");}
    private static String reason(RuntimeException error) {return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
