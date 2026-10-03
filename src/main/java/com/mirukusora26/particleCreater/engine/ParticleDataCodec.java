package com.mirukusora26.particleCreater.engine;

import com.mirukusora26.particleCreater.model.EffectDefinition.Layer;
import com.mirukusora26.particleCreater.model.CoordinateBounds;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Vibration;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/** Converts named layer properties to every data class used by the Paper Particle enum. */
public final class ParticleDataCodec {
    private ParticleDataCodec() {}

    public static Particle particle(String name) {
        try { return Particle.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException e) { throw new IllegalArgumentException("Unknown particle: "+name); }
    }

    public static List<String> keys(Particle particle) {
        Class<?> type=particle.getDataType();
        if(type==Void.class) return List.of();
        if(type==BlockData.class) return List.of("block");
        if(type==ItemStack.class) return List.of("item");
        if(type==Color.class) return List.of("color");
        if(type==Particle.DustOptions.class) return List.of("color","size");
        if(type==Particle.DustTransition.class) return List.of("color","to","size");
        if(type==Particle.Spell.class) return List.of("color","power");
        if(type==Particle.Trail.class) return List.of("target","color","ticks");
        if(type==Vibration.class) return List.of("target","entity","ticks");
        if(type==Particle.Geyser.class) return List.of("waterBlocks");
        if(type==Particle.GeyserBase.class) return List.of("waterBlocks","burstImpulse");
        if(type==Float.class||type==Integer.class) return List.of("value");
        throw new IllegalStateException("Particle data type needs a codec: "+type.getName()+" ("+particle+")");
    }

    /** These data objects depend on the emitted point or its world. */
    public static boolean locationDependent(Particle particle) {
        Class<?> type=particle.getDataType();
        return type==Particle.Trail.class||type==Vibration.class;
    }

    /** Check fields already supplied while allowing a layer to be edited before required fields are set. */
    public static void validateProvided(Layer layer,Particle particle,Location origin) {
        if(origin==null) throw new IllegalArgumentException("Particle data origin is required");
        if(layer.data==null) throw new IllegalArgumentException("Missing particle data map");
        Map<String,String> data=layer.data;
        List<String> supported=keys(particle);
        if(data.containsKey("target")&&data.containsKey("entity"))
            throw new IllegalArgumentException("Use either target or entity for vibration data");
        for(String key:data.keySet()) {
            if(!supported.contains(key)) throw new IllegalArgumentException("Unsupported data key '"+key+"' for "+particle);
            switch(key) {
                case "block" -> {
                    try {Bukkit.createBlockData(required(data,key));}
                    catch(RuntimeException e){throw new IllegalArgumentException("block='"+data.get(key)+"' is not valid block data: "+reason(e),e);}
                }
                case "item" -> item(data);
                case "color","to" -> {
                    try {color(required(data,key));}
                    catch(RuntimeException e){throw new IllegalArgumentException(key+"='"+data.get(key)+"' is invalid: "+reason(e),e);}
                }
                case "size" -> {if(floatValue(data,key,1)<=0) throw new IllegalArgumentException("size must be positive");}
                case "power","burstImpulse" -> floatValue(data,key,0);
                case "ticks","waterBlocks" -> positiveInt(data,key,1);
                case "target" -> target(required(data,key),origin);
                case "entity" -> {
                    try {UUID.fromString(required(data,key));}
                    catch(IllegalArgumentException e){throw new IllegalArgumentException("entity='"+data.get(key)+"' must be a UUID",e);}
                }
                case "value" -> {if(particle.getDataType()==Float.class) floatValue(data,key,0);else intValue(data,key,0);}
                default -> throw new IllegalArgumentException("Unsupported data key '"+key+"' for "+particle);
            }
        }
    }

    public static Object decode(Layer layer, Location origin) {
        Particle particle=particle(layer.particle);
        Class<?> type=particle.getDataType();
        Map<String,String> data=layer.data;
        for(String key:data.keySet()) if(!keys(particle).contains(key)) throw new IllegalArgumentException("Unsupported data key '"+key+"' for "+particle);
        try {
            if(type==Void.class) return null;
            if(type==BlockData.class) return Bukkit.createBlockData(required(data,"block"));
            if(type==ItemStack.class) return ItemStack.of(item(data));
            if(type==Color.class) return color(required(data,"color"));
            if(type==Particle.DustOptions.class) return new Particle.DustOptions(color(required(data,"color")),floatValue(data,"size",1));
            if(type==Particle.DustTransition.class) return new Particle.DustTransition(color(required(data,"color")),color(required(data,"to")),floatValue(data,"size",1));
            if(type==Particle.Spell.class) return new Particle.Spell(color(required(data,"color")),floatValue(data,"power",1));
            if(type==Particle.Trail.class) return new Particle.Trail(target(required(data,"target"),origin),color(required(data,"color")),positiveInt(data,"ticks",40));
            if(type==Vibration.class) {
                Vibration.Destination destination;
                if(data.containsKey("entity")) {
                    Entity entity=Bukkit.getEntity(UUID.fromString(required(data,"entity")));
                    if(entity==null) throw new IllegalArgumentException("Vibration target entity '"+data.get("entity")+"' is not loaded");
                    destination=new Vibration.Destination.EntityDestination(entity);
                } else destination=new Vibration.Destination.BlockDestination(target(required(data,"target"),origin));
                return new Vibration(destination,positiveInt(data,"ticks",40));
            }
            if(type==Particle.Geyser.class) return new Particle.Geyser(positiveInt(data,"waterBlocks",1));
            if(type==Particle.GeyserBase.class) return new Particle.GeyserBase(positiveInt(data,"waterBlocks",1),floatValue(data,"burstImpulse",1));
            if(type==Float.class) return floatValue(data,"value",0);
            if(type==Integer.class) return intValue(data,"value",0);
            throw new IllegalStateException("Unhandled particle data type: "+type.getName());
        } catch(NumberFormatException e) { throw new IllegalArgumentException("Invalid number for "+particle+": "+e.getMessage(),e); }
    }

    private static String required(Map<String,String> data,String key) {
        String value=data.get(key);
        if(value==null||value.isBlank()) throw new IllegalArgumentException("Missing particle data '"+key+"'");
        return value;
    }
    private static Material item(Map<String,String> data) {
        Material material=Material.matchMaterial(required(data,"item"));
        if(material==null||material.isAir()||!material.isItem()) throw new IllegalArgumentException("item='"+data.get("item")+"' is not an item material");
        return material;
    }
    private static int intValue(Map<String,String> data,String key,int defaultValue) {
        if(!data.containsKey(key)) return defaultValue;
        try {return Integer.parseInt(required(data,key));}
        catch(NumberFormatException e) {throw new IllegalArgumentException(key+"='"+data.get(key)+"' must be an integer",e);}
    }
    private static int positiveInt(Map<String,String> data,String key,int defaultValue) {int n=intValue(data,key,defaultValue);if(n<1||n>12000) throw new IllegalArgumentException(key+"="+n+" must be 1..12000");return n;}
    private static float floatValue(Map<String,String> data,String key,float defaultValue) {
        if(!data.containsKey(key)) return defaultValue;
        try {
            float n=Float.parseFloat(required(data,key));
            if(Float.isFinite(n)) return n;
        } catch(NumberFormatException e) {throw new IllegalArgumentException(key+"='"+data.get(key)+"' must be a finite number",e);}
        throw new IllegalArgumentException(key+"='"+data.get(key)+"' must be a finite number");
    }
    private static Color color(String source) {
        String value=source.startsWith("#")?source.substring(1):source;
        if(!value.matches("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) throw new IllegalArgumentException("Color must be #RRGGBB or #RRGGBBAA");
        int rgb=Integer.parseUnsignedInt(value.substring(0,6),16);
        return value.length()==8?Color.fromARGB(Integer.parseInt(value.substring(6),16),rgb>>16&255,rgb>>8&255,rgb&255):Color.fromRGB(rgb);
    }
    private static Location target(String source,Location origin) {
        String[] parts=source.split(",",-1);
        if(parts.length!=3) throw new IllegalArgumentException("Target must be x,y,z or ~dx,~dy,~dz");
        double[] base={origin.getX(),origin.getY(),origin.getZ()};
        double[] xyz=new double[3];
        for(int i=0;i<3;i++) {
            boolean relative=parts[i].startsWith("~");
            String text=relative?parts[i].substring(1):parts[i];
            String axis="xyz".substring(i,i+1);
            if(text.isEmpty()&&!relative) throw new IllegalArgumentException("Target "+axis+" coordinate is empty");
            try {xyz[i]=(relative?base[i]:0)+(text.isEmpty()?0:Double.parseDouble(text));}
            catch(NumberFormatException e) {throw new IllegalArgumentException("Target "+axis+" coordinate='"+parts[i]+"' is not a number",e);}
            if(!CoordinateBounds.valid(xyz[i])) throw new IllegalArgumentException("Target "+axis+" coordinate='"+parts[i]+"' resolves to "+xyz[i]+"; expected a finite value within +/-30000000 blocks");
        }
        return new Location(origin.getWorld(),xyz[0],xyz[1],xyz[2]);
    }
    private static String reason(RuntimeException error) {return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
