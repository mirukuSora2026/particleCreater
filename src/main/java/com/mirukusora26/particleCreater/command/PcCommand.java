package com.mirukusora26.particleCreater.command;

import com.google.gson.GsonBuilder;
import com.mirukusora26.particleCreater.engine.EffectService;
import com.mirukusora26.particleCreater.engine.ParticleDataCodec;
import com.mirukusora26.particleCreater.engine.PlaybackManager;
import com.mirukusora26.particleCreater.math.Expression;
import com.mirukusora26.particleCreater.math.ShapeAssets;
import com.mirukusora26.particleCreater.math.ShapeUtils;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import com.mirukusora26.particleCreater.model.EffectDefinition.Layer;
import com.mirukusora26.particleCreater.model.EffectDefinition.Shape;
import com.mirukusora26.particleCreater.storage.SavedLocation;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** /pc administration and playback, with contextual BasicCommand suggestions. */
public final class PcCommand implements BasicCommand {
    private final EffectService effects;
    private final PlaybackManager playback;
    private final Logger logger;
    private final Path importFolder;
    private final String prefix="§b[pc] §r";
    private static final List<String> ROOT=List.of("help","list","info","create","copy","rename","delete","validate","types","type","shapes","shape","layer","mix","set","motion","param","location","preview","spawn","play","running","pause","resume","stop","stopall","export","import");

    public PcCommand(EffectService effects,PlaybackManager playback,Logger logger,Path importFolder) {this.effects=effects;this.playback=playback;this.logger=logger;this.importFolder=importFolder;}
    @Override public boolean canUse(CommandSender sender) {return sender instanceof ConsoleCommandSender||sender instanceof RemoteConsoleCommandSender||sender instanceof BlockCommandSender||sender instanceof Player player&&player.isOp();}

    @Override public void execute(CommandSourceStack source,String[] args) {
        CommandSender sender=source.getSender();
        if(!canUse(sender)) {sender.sendMessage(prefix+"§cOnly operators, the console, and command blocks may use this command.");return;}
        List<String> parsed=Arrays.asList(args);
        try {parsed=words(String.join(" ",args));dispatch(source,parsed);}
        catch(IllegalArgumentException e) {String message=CommandDiagnostics.failure(CommandDiagnostics.inputCategory(parsed,e),parsed,e);logger.warning(message);replyError(sender,message);}
        catch(ArithmeticException e) {String message=CommandDiagnostics.failure("NUMERIC",parsed,e);logger.warning(message);replyError(sender,message);}
        catch(SQLException e) {String message=CommandDiagnostics.failure("SQLITE",parsed,e);logger.log(Level.SEVERE,message+" (SQLState="+e.getSQLState()+", code="+e.getErrorCode()+")",e);replyError(sender,message);}
        catch(IOException e) {String message=CommandDiagnostics.failure("FILE",parsed,e);logger.log(Level.SEVERE,message,e);replyError(sender,message);}
        catch(Exception e) {String message=CommandDiagnostics.failure("INTERNAL",parsed,e);logger.log(Level.SEVERE,message,e);replyError(sender,message+" Check the server log for details.");}
    }

    private void dispatch(CommandSourceStack source,List<String> a) throws SQLException,IOException {
        CommandSender sender=source.getSender();
        if(a.isEmpty()||a.getFirst().equalsIgnoreCase("help")) {help(sender,a.size()>1?a.get(1):null);return;}
        String root=a.getFirst().toLowerCase(Locale.ROOT);
        switch(root) {
            case "list" -> {
                int page=a.size()>1?positive(a.get(1)):1;
                List<String> all=effects.names();int from=CommandNumbers.pageOffset(page,all.size());
                tell(sender,"Effects: "+all.size()+" (page "+page+"): "+String.join(", ",all.subList(Math.min(from,all.size()),Math.min(from+20,all.size()))));
            }
            case "info" -> {need(a,2,"/pc info <effect>");EffectDefinition effect=effects.get(a.get(1));tell(sender,"Name="+effect.name+", shapes="+effect.shapes.size()+", layers="+effect.layers.size()+", duration="+effect.durationTicks+"t, interval="+effect.intervalTicks+"t, mix="+effect.mix);}
            case "create" -> {need(a,2,"/pc create <name>");effects.create(a.get(1));tell(sender,"Effect created and saved: "+a.get(1));}
            case "copy" -> {need(a,3,"/pc copy <source> <new-name>");effects.copy(a.get(1),a.get(2));tell(sender,"Effect copied: "+a.get(2));}
            case "rename" -> {need(a,3,"/pc rename <name> <new-name>");effects.rename(a.get(1),a.get(2));tell(sender,"Effect renamed: "+a.get(2));}
            case "delete" -> {need(a,2,"/pc delete <name>");effects.delete(a.get(1));tell(sender,"Saved effect deleted. Existing runs will continue.");}
            case "validate" -> {
                need(a,2,"/pc validate <name>");
                List<String> problems=effects.validate(a.get(1));
                if(problems.isEmpty()) tell(sender,"Validation passed: "+a.get(1));
                else {
                    tell(sender,"[VALIDATION] Effect '"+a.get(1)+"' has "+problems.size()+" issue(s):");
                    for(int problem=0;problem<problems.size();problem++) replyError(sender,"[VALIDATION "+(problem+1)+"/"+problems.size()+"] "+problems.get(problem));
                }
            }
            case "types" -> {
                int page=a.size()>1?positive(a.get(1)):1;
                List<String> types=Arrays.stream(Particle.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).sorted().toList();
                int from=CommandNumbers.pageOffset(page,types.size());
                tell(sender,"Particle types: "+types.size()+" (page "+page+"): "+String.join(", ",types.subList(from,Math.min(from+20,types.size()))));
            }
            case "type" -> {need(a,2,"/pc type <particle>");Particle p=ParticleDataCodec.particle(a.get(1));tell(sender,p.name().toLowerCase(Locale.ROOT)+" data fields: "+ParticleDataCodec.keys(p));}
            case "shapes" -> tell(sender,"Built-in shapes: "+String.join(", ",ShapeUtils.BASIC)+" / Formula shapes: curve, surface, equation, solid, system");
            case "shape" -> shape(a,sender);
            case "layer" -> layer(a,sender);
            case "mix" -> {need(a,3,"/pc mix <effect> together|weighted");effects.update(a.get(1),e -> e.mix=a.get(2));tell(sender,"Mix mode saved.");}
            case "set" -> setting(a,sender);
            case "motion" -> motion(a,sender);
            case "param" -> parameter(a,sender);
            case "location" -> location(a,source);
            case "preview","spawn","play" -> invoke(root,a,source);
            case "running" -> {
                List<PlaybackManager.Handle> runs=playback.running().stream().filter(h -> a.size()<2||h.name().equals(a.get(1))).toList();
                tell(sender,runs.isEmpty()?"No effects are running.":runs.stream().map(h -> h.id()+":"+h.name()+(h.paused()?"(paused)":"")).reduce((x,y)->x+", "+y).orElse(""));
            }
            case "pause","resume","stop" -> {
                need(a,2,"/pc "+root+" <run-id>");
                boolean done=switch(root){case "pause" -> playback.pause(a.get(1));case "resume" -> playback.resume(a.get(1));default -> playback.stop(a.get(1));};
                if(!done) throw new IllegalArgumentException("Run ID not found: "+a.get(1));
                tell(sender,"Run "+switch(root){case "pause" -> "paused";case "resume" -> "resumed";default -> "stopped";}+": "+a.get(1));
            }
            case "stopall" -> {need(a,2,"/pc stopall <effect>");tell(sender,"Stopped "+playback.stopAll(a.get(1))+" runs.");}
            case "export" -> {need(a,2,"/pc export <effect>");tell(sender,"JSON exported: "+effects.exportJson(a.get(1)));}
            case "import" -> {need(a,2,"/pc import <file.json> [new-name]");effects.importJson(a.get(1),a.size()>2?a.get(2):null);tell(sender,"JSON effect imported.");}
            default -> throw new IllegalArgumentException("Unknown subcommand. Use /pc help.");
        }
    }

    private void shape(List<String> a,CommandSender sender) throws SQLException,IOException {
        need(a,2,"/pc shape <list|info|add|set|point|transform|group|remove> ...");
        String action=a.get(1);
        switch(action) {
            case "list" -> {need(a,3,"/pc shape list <effect>");tell(sender,effects.get(a.get(2)).shapes.stream().map(s -> s.id+":"+s.kind).toList().toString());}
            case "info" -> {need(a,4,"/pc shape info <effect> <shape>");tell(sender,new GsonBuilder().setPrettyPrinting().create().toJson(shape(effects.get(a.get(2)),a.get(3))));}
            case "add" -> {
                need(a,5,"/pc shape add <effect> <shape> <basic|curve|surface|equation|solid|system|points> ...");
                String kind=a.get(4).toLowerCase(Locale.ROOT);boolean four=kind.endsWith("4");if(four)kind=kind.substring(0,kind.length()-1);
                final String selected=kind;Shape s=new Shape(a.get(3),selected);if(four)s.dimensions=4;
                int index=5;
                if(kind.equals("basic")) {need(a,6,"/pc shape add <effect> <shape> basic <type> [key=value...]");s.kind=a.get(5).toLowerCase(Locale.ROOT);if(List.of("circle","ellipse","rectangle","polygon","star","heart").contains(s.kind))s.fill="outline";index=6;}
                else if(kind.equals("curve")||kind.equals("surface")) {
                    need(a,5+s.dimensions,"Expected "+s.dimensions+" coordinate formulas.");
                    for(int i=0;i<s.dimensions;i++) s.expressions.add(a.get(index++));
                } else if(kind.equals("equation")||kind.equals("solid")) {need(a,6,"An equation is required.");s.expressions.add(a.get(index++));}
                else if(kind.equals("system")) {need(a,7,"At least two equations are required for a system.");s.expressions.addAll(a.subList(index,a.size()));index=a.size();}
                else if(!kind.equals("points")) throw new IllegalArgumentException("Unknown shape mode: "+kind);
                for(int i=index;i<a.size();i++) numberOption(s,a.get(i));
                effects.update(a.get(2),e -> {if(e.shapes.stream().anyMatch(old -> old.id.equals(s.id))) throw new IllegalArgumentException("Duplicate shape name: "+s.id);e.shapes.add(s);});
                tell(sender,"Shape saved: "+s.id);
            }
            case "text", "image" -> {
                need(a,5,"/pc shape text|image <effect> <shape> <text|file.png> [pixel-size]");
                double size=a.size()>5?decimal(a.get(5)):0.1;
                Shape created=action.equals("text")?ShapeAssets.text(a.get(3),a.get(4),size):ShapeAssets.png(a.get(3),importFolder,a.get(4),size);
                effects.update(a.get(2),e -> {if(e.shapes.stream().anyMatch(old -> old.id.equals(created.id)))throw new IllegalArgumentException("Duplicate shape name.");e.shapes.add(created);});
                tell(sender,"Point shape saved: "+created.id+" ("+created.points.size()+" points)");
            }
            case "set" -> {
                need(a,6,"/pc shape set <effect> <shape> fill|step|dimension|view|slice|projection|bounds|number <value...>");
                effects.update(a.get(2),e -> {
                    Shape s=shape(e,a.get(3));String field=a.get(4);
                    switch(field) {
                        case "fill" -> s.fill=a.get(5);
                        case "step" -> s.step=decimal(a.get(5));
                        case "dimension" -> s.dimensions=integer(a.get(5));
                        case "view" -> s.view=a.get(5);
                        case "slice" -> s.slice=a.get(5);
                        case "projection" -> s.projection=triple(a,5);
                        case "bounds" -> {need(a,13,"Bounds require xmin xmax ymin ymax zmin zmax wmin wmax.");for(int i=0;i<8;i++)s.bounds[i]=decimal(a.get(5+i));}
                        case "number" -> {need(a,7,"/pc shape set <effect> <shape> number <field> <number>");s.numbers.put(a.get(5),decimal(a.get(6)));}
                        default -> throw new IllegalArgumentException("Unknown shape setting: "+field);
                    }
                });tell(sender,"Shape setting saved.");
            }
            case "point" -> {
                need(a,8,"/pc shape point add <effect> <shape> <x> <y> <z>");
                if(!a.get(2).equals("add")) throw new IllegalArgumentException("Use point add.");
                effects.update(a.get(3),e -> {Shape s=shape(e,a.get(4));if(!s.kind.equals("points"))throw new IllegalArgumentException("This is not a points shape.");s.points.add(List.of(decimal(a.get(5)),decimal(a.get(6)),decimal(a.get(7))));});
                tell(sender,"Point added.");
            }
            case "transform" -> {
                need(a,8,"/pc shape transform <effect> <shape> move|rotate|scale <x> <y> <z>");
                effects.update(a.get(2),e -> {Shape s=shape(e,a.get(3));double[] v=triple(a,5);switch(a.get(4)){case "move" -> s.translate=v;case "rotate" -> s.rotate=v;case "scale" -> s.scale=v;default -> throw new IllegalArgumentException("Choose move, rotate, or scale.");}});
                tell(sender,"Shape transform saved.");
            }
            case "group" -> {
                need(a,5,"/pc shape group create|add|remove <effect> <group> [shape]");String operation=a.get(2);
                if(operation.equals("create")) effects.update(a.get(3),e -> {if(e.shapes.stream().anyMatch(s -> s.id.equals(a.get(4)))) throw new IllegalArgumentException("Duplicate shape name.");e.shapes.add(new Shape(a.get(4),"group"));});
                else {need(a,6,"Specify a shape to add to or remove from the group.");effects.update(a.get(3),e -> {Shape group=shape(e,a.get(4));if(!group.kind.equals("group"))throw new IllegalArgumentException("This shape is not a group.");shape(e,a.get(5));if(operation.equals("add")){if(!group.children.contains(a.get(5)))group.children.add(a.get(5));}else if(operation.equals("remove"))group.children.remove(a.get(5));else throw new IllegalArgumentException("Choose create, add, or remove.");});}
                tell(sender,"Shape group saved.");
            }
            case "remove" -> {need(a,4,"/pc shape remove <effect> <shape>");effects.update(a.get(2),e -> {if(e.layers.stream().anyMatch(l -> l.shape.equals(a.get(3))))throw new IllegalArgumentException("A layer uses this shape.");for(Shape s:e.shapes)if(s.children.contains(a.get(3)))throw new IllegalArgumentException("A group uses this shape.");if(!e.shapes.removeIf(s -> s.id.equals(a.get(3))))throw new IllegalArgumentException("Shape not found.");});tell(sender,"Shape deleted.");}
            default -> throw new IllegalArgumentException("Unknown shape command.");
        }
    }

    private void layer(List<String> a,CommandSender sender) throws SQLException {
        need(a,2,"/pc layer <list|info|add|set|data|remove> ...");
        switch(a.get(1)) {
            case "list" -> {need(a,3,"/pc layer list <effect>");tell(sender,effects.get(a.get(2)).layers.stream().map(l -> l.id+":"+l.particle+"@"+l.shape).toList().toString());}
            case "info" -> {need(a,4,"/pc layer info <effect> <layer>");tell(sender,new GsonBuilder().setPrettyPrinting().create().toJson(layer(effects.get(a.get(2)),a.get(3))));}
            case "add" -> {need(a,6,"/pc layer add <effect> <layer> <particle> <shape>");effects.update(a.get(2),e -> {if(e.layers.stream().anyMatch(l -> l.id.equals(a.get(3))))throw new IllegalArgumentException("Duplicate layer name.");shape(e,a.get(5));e.layers.add(new Layer(a.get(3),a.get(4),a.get(5)));});tell(sender,"Layer saved. Use /pc type "+a.get(4)+" to check required data fields.");}
            case "set" -> {
                need(a,6,"/pc layer set <effect> <layer> <field> <value...>");
                effects.update(a.get(2),e -> {Layer l=layer(e,a.get(3));switch(a.get(4)) {
                    case "particle" -> l.particle=a.get(5);
                    case "shape" -> {shape(e,a.get(5));l.shape=a.get(5);}
                    case "count" -> l.count=integer(a.get(5));
                    case "offset" -> l.offset=triple(a,5);
                    case "extra" -> l.extra=decimal(a.get(5));
                    case "interval" -> l.intervalTicks=ticks(a.get(5));
                    case "start" -> l.startTicks=ticks(a.get(5));
                    case "duration" -> l.durationTicks=ticks(a.get(5));
                    case "weight" -> l.weight=decimal(a.get(5));
                    default -> throw new IllegalArgumentException("Unknown layer field: "+a.get(4));
                }});tell(sender,"Layer setting saved.");
            }
            case "data" -> {
                need(a,5,"/pc layer data <effect> <layer> <field> <value> or /pc layer data <effect> <layer> remove <field>");
                if(a.get(4).equals("remove")) {need(a,6,"Specify a data field to remove.");effects.update(a.get(2),e -> layer(e,a.get(3)).data.remove(a.get(5)));}
                else {need(a,6,"Specify a data value.");effects.update(a.get(2),e -> layer(e,a.get(3)).data.put(a.get(4),a.get(5)));}
                tell(sender,"Particle data saved.");
            }
            case "remove" -> {need(a,4,"/pc layer remove <effect> <layer>");effects.update(a.get(2),e -> {if(!e.layers.removeIf(l -> l.id.equals(a.get(3))))throw new IllegalArgumentException("Layer not found.");});tell(sender,"Layer deleted.");}
            default -> throw new IllegalArgumentException("Unknown layer command.");
        }
    }

    private void setting(List<String> a,CommandSender sender) throws SQLException {
        need(a,4,"/pc set <effect> duration|interval|anchor <value>");
        effects.update(a.get(1),e -> {switch(a.get(2)) {case "duration" -> e.durationTicks=ticks(a.get(3));case "interval" -> e.intervalTicks=ticks(a.get(3));case "anchor" -> e.anchor=a.get(3);default -> throw new IllegalArgumentException("Choose duration, interval, or anchor.");}});
        tell(sender,"Effect setting saved.");
    }
    private void motion(List<String> a,CommandSender sender) throws SQLException {
        need(a,4,"/pc motion set|clear <effect> move|rotate|scale [x-formula y-formula z-formula]");
        String action=a.get(1),axis=a.get(3);List<String> values;
        if(action.equals("clear")) values=axis.equals("scale")?List.of("1","1","1"):List.of("0","0","0");
        else {if(!action.equals("set"))throw new IllegalArgumentException("Use set or clear.");need(a,7,"Three axis formulas are required.");values=List.of(a.get(4),a.get(5),a.get(6));}
        effects.update(a.get(2),e -> {switch(axis){case "move" -> e.motion.move=new ArrayList<>(values);case "rotate" -> e.motion.rotate=new ArrayList<>(values);case "scale" -> e.motion.scale=new ArrayList<>(values);default -> throw new IllegalArgumentException("Choose move, rotate, or scale.");}});
        tell(sender,"Motion saved.");
    }
    private void parameter(List<String> a,CommandSender sender) throws SQLException {
        need(a,3,"/pc param list|set|remove <effect> ...");
        switch(a.get(1)) {
            case "list" -> tell(sender,effects.get(a.get(2)).parameters.toString());
            case "set" -> {need(a,5,"/pc param set <effect> <variable> <number>");effects.update(a.get(2),e -> e.parameters.put(a.get(3),decimal(a.get(4))));tell(sender,"Parameter saved.");}
            case "remove" -> {need(a,4,"/pc param remove <effect> <variable>");effects.update(a.get(2),e -> e.parameters.remove(a.get(3)));tell(sender,"Parameter removed.");}
            default -> throw new IllegalArgumentException("Choose list, set, or remove.");
        }
    }

    private void location(List<String> a,CommandSourceStack source) throws SQLException {
        need(a,2,"/pc location save|list|info|delete ...");
        CommandSender sender=source.getSender();
        switch(a.get(1)) {
            case "save" -> {
                need(a,4,"/pc location save <name> [world] <x> <y> <z>");
                Location origin=source.getLocation();
                boolean console=sender instanceof ConsoleCommandSender||sender instanceof RemoteConsoleCommandSender;
                LocationArgument.Source position=new LocationArgument.Source(origin==null||origin.getWorld()==null?null:origin.getWorld().getName(),origin==null?Double.NaN:origin.getX(),origin==null?Double.NaN:origin.getY(),origin==null?Double.NaN:origin.getZ(),console);
                LocationArgument.Parsed parsed=LocationArgument.parse(a,3,position,name -> Bukkit.getWorld(name)!=null);
                if(parsed.nextIndex()!=a.size()) throw new IllegalArgumentException("Usage: /pc location save <name> [world] <x> <y> <z>");
                World world=Bukkit.getWorld(parsed.world());
                if(world==null) throw new IllegalArgumentException("World not found: "+parsed.world());
                SavedLocation saved=new SavedLocation(a.get(2),world.getUID(),world.getName(),parsed.x(),parsed.y(),parsed.z());
                effects.saveLocation(saved);
                tell(sender,"Saved location "+saved.name()+" at "+where(new Location(world,saved.x(),saved.y(),saved.z()))+".");
            }
            case "list" -> {
                if(a.size()>3) throw new IllegalArgumentException("Usage: /pc location list [page]");
                int page=a.size()>2?positive(a.get(2)):1;
                int total=effects.locationCount();int from=CommandNumbers.pageOffset(page,total);
                tell(sender,"Saved locations: "+total+" (page "+page+"): "+String.join(", ",effects.locationPage(from,20)));
            }
            case "info" -> {
                need(a,3,"/pc location info <name>");
                SavedLocation saved=effects.getLocation(a.get(2));
                tell(sender,"Saved location "+saved.name()+": "+saved.worldName()+" ("+saved.worldId()+") "+coordinates(saved.x(),saved.y(),saved.z()));
            }
            case "delete" -> {
                need(a,3,"/pc location delete <name>");effects.deleteLocation(a.get(2));
                tell(sender,"Saved location deleted: "+a.get(2));
            }
            default -> throw new IllegalArgumentException("Unknown location command. Use /pc location save|list|info|delete.");
        }
    }

    private void invoke(String kind,List<String> a,CommandSourceStack source) throws SQLException {
        need(a,2,"/pc "+kind+" <effect> [at [world] <x> <y> <z>|saved <name>|player <player>] [follow <entity>] [for <player>] [with key=value...]");
        EffectDefinition effect=effects.get(a.get(1));CommandSender sender=source.getSender();
        Location at=source.getLocation();Entity follow=null;Collection<Player> receivers=null;Map<String,Double> overrides=new HashMap<>();
        boolean console=sender instanceof ConsoleCommandSender||sender instanceof RemoteConsoleCommandSender;
        LocationArgument.Source position=new LocationArgument.Source(
            at==null||at.getWorld()==null?null:at.getWorld().getName(),
            at==null?Double.NaN:at.getX(),at==null?Double.NaN:at.getY(),at==null?Double.NaN:at.getZ(),console);
        boolean explicitLocation=false,selectedFollow=false,selectedReceivers=false;int i=2;
        while(i<a.size()) {
            String option=a.get(i++);
            switch(option) {
                case "at" -> {
                    if(explicitLocation) throw new IllegalArgumentException("Specify at only once.");
                    if(i>=a.size()) throw new IllegalArgumentException("at requires coordinates, saved <name>, or player <player>.");
                    boolean worldCoordinates=LocationArgument.hasWorldCoordinates(a,i,name -> Bukkit.getWorld(name)!=null);
                    if(!worldCoordinates&&a.get(i).equals("saved")) {
                        if(i+1>=a.size()) throw new IllegalArgumentException("at saved requires a location name.");
                        at=effects.resolveLocation(a.get(i+1));i+=2;
                    } else if(!worldCoordinates&&a.get(i).equals("player")) {
                        if(i+1>=a.size()) throw new IllegalArgumentException("at player requires a player name.");
                        List<Player> players=entities(sender,a.get(i+1)).stream().filter(Player.class::isInstance).map(Player.class::cast).filter(Player::isOnline).toList();
                        if(players.size()!=1) throw new IllegalArgumentException("at player must select exactly one online player.");
                        at=players.getFirst().getLocation().clone();i+=2;
                    } else {
                        LocationArgument.Parsed target=LocationArgument.parse(a,i,position,name -> Bukkit.getWorld(name)!=null);
                        World world=Bukkit.getWorld(target.world());
                        if(world==null) throw new IllegalArgumentException("World not found: "+target.world());
                        at=new Location(world,target.x(),target.y(),target.z());i=target.nextIndex();
                    }
                    explicitLocation=true;
                }
                case "follow" -> {if(selectedFollow)throw new IllegalArgumentException("Specify follow only once.");selectedFollow=true;if(i>=a.size())throw new IllegalArgumentException("follow requires a target.");List<Entity> matches=entities(sender,a.get(i++));if(matches.size()!=1)throw new IllegalArgumentException("follow must select exactly one entity.");follow=matches.getFirst();}
                case "for" -> {if(selectedReceivers)throw new IllegalArgumentException("Specify for only once.");selectedReceivers=true;if(i>=a.size())throw new IllegalArgumentException("for requires a target.");receivers=entities(sender,a.get(i++)).stream().filter(Player.class::isInstance).map(Player.class::cast).toList();if(receivers.isEmpty())throw new IllegalArgumentException("No target players found.");}
                case "with" -> {if(i>=a.size())throw new IllegalArgumentException("with requires at least one name=value parameter.");while(i<a.size()){String[] pair=a.get(i++).split("=",2);if(pair.length!=2||pair[0].isBlank())throw new IllegalArgumentException("Parameters must use name=value format.");if(overrides.putIfAbsent(pair[0],decimal(pair[1]))!=null)throw new IllegalArgumentException("Duplicate variable override: "+pair[0]);}}
                default -> throw new IllegalArgumentException("Unknown playback option: "+option);
            }
        }
        if(console&&!explicitLocation) throw new IllegalArgumentException("Console commands require at <world> <x> <y> <z>, at saved <name>, or at player <player>.");
        if(explicitLocation&&!effect.anchor.equals("fixed")) throw new IllegalArgumentException("Explicit locations require a fixed anchor. Use /pc set "+effect.name+" anchor fixed.");
        if(at==null||at.getWorld()==null) throw new IllegalArgumentException("Unable to determine a playback location.");
        Location shown=switch(effect.anchor) {case "caller" -> source.getExecutor()==null?at:source.getExecutor().getLocation();case "target" -> follow==null?at:follow.getLocation();default -> at;};
        String placement=where(shown)+(effect.anchor.equals("fixed")?"":" (follows "+effect.anchor+")");
        PlaybackManager.Context context=new PlaybackManager.Context(at,source.getExecutor(),follow,receivers,overrides);
        if(kind.equals("spawn")) {effects.spawn(effect.name,context);tell(sender,"Effect spawned once: "+effect.name+" at "+placement);}
        else if(kind.equals("preview")) {
            if(!(sender instanceof Player player)) throw new IllegalArgumentException("Only players can preview effects.");
            effect.durationTicks=40;
            context=new PlaybackManager.Context(at,source.getExecutor(),follow,List.of(player),overrides);
            PlaybackManager.Handle handle=playback.play(effect,context);tell(sender,"Preview run ID: "+handle.id()+" at "+placement);
        } else {PlaybackManager.Handle handle=effects.play(effect.name,context);tell(sender,"Playback run ID: "+handle.id()+" at "+placement);}
    }

    private static String where(Location location) {return location.getWorld().getName()+" "+coordinates(location.getX(),location.getY(),location.getZ());}
    private static String coordinates(double x,double y,double z) {return String.format(Locale.ROOT,"(%.2f, %.2f, %.2f)",x,y,z);}

    private static List<Entity> entities(CommandSender sender,String selector) {
        if(selector.startsWith("@")) return Bukkit.selectEntities(sender,selector);
        try {Entity entity=Bukkit.getEntity(UUID.fromString(selector));return entity==null?List.of():List.of(entity);}catch(IllegalArgumentException ignored) {}
        Player player=Bukkit.getPlayerExact(selector);return player==null?List.of():List.of(player);
    }

    private static Shape shape(EffectDefinition e,String name) {return e.shapes.stream().filter(s -> s.id.equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Shape not found: "+name));}
    private static Layer layer(EffectDefinition e,String name) {return e.layers.stream().filter(l -> l.id.equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Layer not found: "+name));}
    private static void numberOption(Shape s,String pair) {String[] p=pair.split("=",2);if(p.length!=2)throw new IllegalArgumentException("Shape options must use key=value format: "+pair);s.numbers.put(p[0],decimal(p[1]));}
    private static double[] triple(List<String> a,int start) {need(a,start+3,"Three numbers (x y z) are required.");return new double[]{decimal(a.get(start)),decimal(a.get(start+1)),decimal(a.get(start+2))};}
    private static int integer(String value) {try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Expected an integer: "+value);}}
    private static int positive(String value) {int n=integer(value);if(n<1)throw new IllegalArgumentException("Expected a positive integer.");return n;}
    private static double decimal(String value) {try{double n=Double.parseDouble(value);if(!Double.isFinite(n))throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Expected a finite number: "+value);}}
    private static int ticks(String value) {return CommandNumbers.ticks(value);}
    private static void need(List<String> args,int count,String usage) {if(args.size()<count)throw new IllegalArgumentException("Usage: "+usage);}
    private void tell(CommandSender sender,String text) {sender.sendMessage(prefix+text);}
    private void replyError(CommandSender sender,String message) {
        String safe=message.replaceAll("[\\p{Cntrl}§]","?");
        for(int start=0;start<safe.length();start+=600)
            sender.sendMessage(prefix+"§c"+(start==0?"":"[continued] ")+safe.substring(start,Math.min(start+600,safe.length())));
    }
    private void help(CommandSender sender,String topic) {
        if(topic!=null) {
            switch(topic.toLowerCase(Locale.ROOT)) {
                case "shape" -> {
                    tell(sender,"/pc shape add <effect> <shape> basic <type> [radius=1 height=2 ...]");
                    tell(sender,"/pc shape add <effect> <shape> curve|surface <x-formula> <y-formula> <z-formula> [umin=0 umax=6.28 vmin=0 vmax=3.14]");
                    tell(sender,"/pc shape add <effect> <shape> equation|solid <F(x,y,z,t)> ; 4D: equation4|solid4, curve4|surface4");
                    tell(sender,"/pc shape set <effect> <shape> fill|step|bounds|slice|view|projection|number ...");
                    tell(sender,"/pc shape text <effect> <shape> \"text\" [pixel-size] / image <effect> <shape> <file.png> [pixel-size]");
                    return;
                }
                case "layer" -> {tell(sender,"/pc layer add <effect> <layer> <particle> <shape>");tell(sender,"/pc layer set <effect> <layer> particle|shape|count|offset|extra|interval|start|duration|weight <value>");tell(sender,"/pc layer data <effect> <layer> <field> <value> / data <effect> <layer> remove <field>");return;}
                case "formula" -> {tell(sender,"Parametric coordinates: u v t and parameters. Equations also use x y z w. Slices use x y z t and parameters; parametric slices also use u v. A slice cannot use w. t is seconds.");tell(sender,"Operators: + - * / % ^, comparisons, && ||, if(condition,true,false). Functions: "+String.join(", ",Expression.functionNames().stream().sorted().toList()));return;}
                case "play" -> {tell(sender,"/pc play <effect> [at [world] x y z|saved <name>|player <player>] [follow <entity>] [for <player>] [with variable=value ...]");tell(sender,"at player uses the player's position when the command runs. Use anchor target and follow <entity> for movement.");tell(sender,"Players and command blocks may use ~ coordinates; console coordinates require a world and absolute values. Use world:<name> for ambiguous world names.");return;}
                case "location" -> {tell(sender,"/pc location save <name> [world] <x> <y> <z>");tell(sender,"/pc location list [page] / info <name> / delete <name>");return;}
                default -> {tell(sender,"Unknown help topic: "+topic);return;}
            }
        }
        tell(sender,"/pc create|copy|rename|delete|list|info|validate <effect>");
        tell(sender,"/pc shape, /pc layer, /pc mix, /pc set, /pc motion, /pc param");
        tell(sender,"/pc location save|list|info|delete ...; /pc help location");
        tell(sender,"/pc preview|spawn|play <effect> [at [world] x y z|saved <name>|player <player>] [follow <entity>] [for <player>] [with key=value...]");
        tell(sender,"/pc running, /pc pause|resume|stop <run-id>, /pc stopall <effect>, /pc import|export");
        tell(sender,"/pc types, /pc type <particle>, /pc shapes. Details: /pc help shape|layer|formula|play");
    }

    private static List<String> words(String text) {
        List<String> out=new ArrayList<>();StringBuilder current=new StringBuilder();boolean quoted=false,escape=false;
        for(char ch:text.toCharArray()) {
            if(escape){current.append(ch);escape=false;continue;}
            if(ch=='\\'&&quoted){escape=true;continue;}
            if(ch=='"'){quoted=!quoted;continue;}
            if(Character.isWhitespace(ch)&&!quoted){if(!current.isEmpty()){out.add(current.toString());current.setLength(0);}continue;}
            current.append(ch);
        }
        if(quoted)throw new IllegalArgumentException("Unclosed double quote.");
        if(!current.isEmpty())out.add(current.toString());
        return out;
    }

    @Override public Collection<String> suggest(CommandSourceStack source,String[] args) {
        if(!canUse(source.getSender()))return List.of();
        try {
            List<String> a=new ArrayList<>(Arrays.asList(args));if(a.isEmpty())a.add("");
            int index=a.size()-1;String current=a.get(index).toLowerCase(Locale.ROOT);
            if(index==0)return matches(ROOT,current);
            String root=a.getFirst().toLowerCase(Locale.ROOT);
            List<String> options=new ArrayList<>();
            if(List.of("info","copy","rename","delete","validate","preview","spawn","play","stopall","export").contains(root)&&index==1) options.addAll(effects.suggestNames(current,40));
            if(root.equals("type")&&index==1||root.equals("layer")&&index==4&&a.size()>2&&a.get(1).equals("add")) options.addAll(Arrays.stream(Particle.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList());
            if(root.equals("help")&&index==1)options.addAll(List.of("shape","layer","formula","play","location"));
            if(root.equals("location")) {
                if(index==1) options.addAll(List.of("save","list","info","delete"));
                if(index==2&&List.of("info","delete").contains(a.get(1))) options.addAll(effects.suggestLocationNames(current,40));
                if(a.size()>2&&a.get(1).equals("save")&&index>=3) coordinateSuggestions(source,a,index,3,options);
            }
            if(root.equals("shape")) shapeSuggestions(a,index,options);
            if(root.equals("layer")) layerSuggestions(a,index,options);
            if(root.equals("set")) {if(index==1)options.addAll(effects.names());if(index==2)options.addAll(List.of("duration","interval","anchor"));if(index==3&&a.size()>2&&a.get(2).equals("anchor"))options.addAll(List.of("fixed","caller","target"));if(index==3&&a.size()>2&&a.get(2).equals("duration"))options.addAll(List.of("100t","5s","infinite"));}
            if(root.equals("mix")){if(index==1)options.addAll(effects.names());if(index==2)options.addAll(List.of("together","weighted"));}
            if(root.equals("motion")){if(index==1)options.addAll(List.of("set","clear"));if(index==2)options.addAll(effects.names());if(index==3)options.addAll(List.of("move","rotate","scale"));}
            if(root.equals("motion")&&index>=4&&a.get(1).equals("set"))options.addAll(List.of("0","1","sin(t)","cos(t)","t"));
            if(root.equals("param")){if(index==1)options.addAll(List.of("list","set","remove"));if(index==2)options.addAll(effects.names());if(index==3&&a.get(1).equals("remove"))options.addAll(effects.get(a.get(2)).parameters.keySet());}
            if(List.of("pause","resume","stop").contains(root)&&index==1)options.addAll(playback.running().stream().map(PlaybackManager.Handle::id).toList());
            if(root.equals("running")&&index==1)options.addAll(effects.names());
            if(List.of("play","spawn","preview").contains(root)&&index>1) {
                if(!locationSuggestions(source,a,index,options)) options.addAll(List.of("at","follow","for","with"));
                if(a.get(index-1).equals("follow")||a.get(index-1).equals("for")) {options.addAll(List.of("@p","@a","@e"));options.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());}
            }
            if(root.equals("import")&&index==1)options.add("<name>.json");
            return matches(options,current);
        } catch(Exception e) {logger.log(Level.FINE,"Tab completion for "+CommandDiagnostics.path(Arrays.asList(args))+" failed",e);return List.of();}
    }

    private boolean locationSuggestions(CommandSourceStack source,List<String> args,int index,List<String> options) throws SQLException {
        int at=args.lastIndexOf("at");
        if(at<2||at>=index) return false;
        int offset=index-at-1;
        if(offset==0) {options.addAll(List.of("saved","player"));coordinateSuggestions(source,args,index,at+1,options);return true;}
        if(args.get(at+1).equals("saved")) {if(offset==1)options.addAll(effects.suggestLocationNames(args.get(index).toLowerCase(Locale.ROOT),40));return offset==1;}
        if(args.get(at+1).equals("player")) {
            if(offset==1) {options.add("@p");options.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());}
            return offset==1;
        }
        return coordinateSuggestions(source,args,index,at+1,options);
    }

    private static boolean coordinateSuggestions(CommandSourceStack source,List<String> args,int index,int start,List<String> options) {
        int offset=index-start;
        boolean console=source.getSender() instanceof ConsoleCommandSender||source.getSender() instanceof RemoteConsoleCommandSender;
        Location location=source.getLocation();
        if(offset==0) {
            options.addAll(Bukkit.getWorlds().stream().map(World::getName).toList());
            options.addAll(Bukkit.getWorlds().stream().map(world -> "world:"+world.getName()).toList());
            if(!console) {
                options.add("~");
                if(location!=null) options.add(Integer.toString(location.getBlockX()));
            }
            return true;
        }
        String first=args.get(start);
        boolean namedWorld=first.startsWith("world:")||Bukkit.getWorld(first)!=null||!LocationArgument.looksLikeCoordinate(first);
        int axis=namedWorld?offset-1:offset;
        if(axis<0||axis>2) return false;
        if(!console) options.add("~");
        options.add(location==null?"0":Integer.toString(switch(axis){case 0 -> location.getBlockX();case 1 -> location.getBlockY();default -> location.getBlockZ();}));
        return true;
    }

    private void shapeSuggestions(List<String> a,int index,List<String> options) {
        if(index==1){options.addAll(List.of("list","info","add","text","image","set","point","transform","group","remove"));return;}
        if(index==2&&a.size()>1&&!a.get(1).equals("point")){options.addAll(effects.names());return;}
        if(index==3&&List.of("info","set","transform","remove").contains(a.get(1))&&effects.exists(a.get(2)))options.addAll(effects.get(a.get(2)).shapes.stream().map(s -> s.id).toList());
        if(index==4&&a.get(1).equals("add"))options.addAll(List.of("basic","curve","curve4","surface","surface4","equation","equation4","solid","solid4","system","system4","points"));
        if(index==5&&a.get(1).equals("add")&&a.get(4).equals("basic"))options.addAll(ShapeUtils.BASIC);
        if(index>=5&&a.get(1).equals("add")&&!a.get(4).equals("basic"))options.addAll(List.of("cos(u)","sin(u)","u","v","t","x^2+y^2+z^2-1","x^2+y^2+z^2+w^2-1"));
        if(index==4&&a.get(1).equals("set"))options.addAll(List.of("fill","step","dimension","view","slice","projection","bounds","number"));
        if(index==5&&a.get(1).equals("set")&&a.get(4).equals("fill"))options.addAll(List.of("outline","surface","solid"));
        if(index==5&&a.get(1).equals("set")&&a.get(4).equals("view"))options.addAll(List.of("slice","project"));
        if(index==4&&a.get(1).equals("transform"))options.addAll(List.of("move","rotate","scale"));
        if(index==2&&a.get(1).equals("group"))options.addAll(List.of("create","add","remove"));
        if(index==3&&a.get(1).equals("group"))options.addAll(effects.names());
        if(index==4&&a.get(1).equals("group")&&effects.exists(a.get(3)))options.addAll(effects.get(a.get(3)).shapes.stream().map(s -> s.id).toList());
        if(index==3&&a.get(1).equals("point"))options.addAll(effects.names());
        if(index==4&&a.get(1).equals("point")&&effects.exists(a.get(3)))options.addAll(effects.get(a.get(3)).shapes.stream().filter(s -> s.kind.equals("points")).map(s -> s.id).toList());
        if(index>4&&a.get(1).equals("add"))options.addAll(List.of("radius=1","height=2","width=2","depth=2","turns=2","umin=0","umax=6.28318","vmin=0","vmax=3.14159"));
        if(index==4&&a.get(1).equals("image"))try(var stream=java.nio.file.Files.list(importFolder)){options.addAll(stream.filter(path -> path.getFileName().toString().endsWith(".png")).map(path -> path.getFileName().toString()).limit(40).toList());}catch(IOException ignored){}
    }
    private void layerSuggestions(List<String> a,int index,List<String> options) {
        if(index==1){options.addAll(List.of("list","info","add","set","data","remove"));return;}
        if(index==2){options.addAll(effects.names());return;}
        if(index==3&&List.of("info","set","data","remove").contains(a.get(1))&&effects.exists(a.get(2)))options.addAll(effects.get(a.get(2)).layers.stream().map(l -> l.id).toList());
        if(index==5&&a.get(1).equals("add")&&effects.exists(a.get(2)))options.addAll(effects.get(a.get(2)).shapes.stream().map(s -> s.id).toList());
        if(index==4&&a.get(1).equals("set"))options.addAll(List.of("particle","shape","count","offset","extra","interval","start","duration","weight"));
        if(index==5&&a.get(1).equals("set")){if(a.get(4).equals("particle"))options.addAll(Arrays.stream(Particle.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList());if(a.get(4).equals("shape")&&effects.exists(a.get(2)))options.addAll(effects.get(a.get(2)).shapes.stream().map(s -> s.id).toList());if(a.get(4).equals("duration"))options.addAll(List.of("20t","1s","infinite"));}
        if(index==4&&a.get(1).equals("data")&&effects.exists(a.get(2))){options.add("remove");options.addAll(ParticleDataCodec.keys(ParticleDataCodec.particle(layer(effects.get(a.get(2)),a.get(3)).particle)));}
        if(index==5&&a.get(1).equals("data")&&a.get(4).equals("remove")&&effects.exists(a.get(2)))options.addAll(layer(effects.get(a.get(2)),a.get(3)).data.keySet());
        if(index==5&&a.get(1).equals("data")){switch(a.get(4)){case "color","to" -> options.add("#FF6600");case "target" -> options.add("~0,~1,~0");case "item" -> options.add("diamond");case "block" -> options.add("minecraft:stone");default -> {}}}
    }
    private static List<String> matches(Collection<String> values,String current) {return values.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(current)).distinct().sorted(Comparator.naturalOrder()).limit(40).toList();}
}
