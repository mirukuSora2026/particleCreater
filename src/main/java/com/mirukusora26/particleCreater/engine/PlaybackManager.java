package com.mirukusora26.particleCreater.engine;

import com.mirukusora26.particleCreater.math.Expression;
import com.mirukusora26.particleCreater.math.ShapeEngine;
import com.mirukusora26.particleCreater.math.ShapeUtils;
import com.mirukusora26.particleCreater.math.Vec3;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import com.mirukusora26.particleCreater.model.EffectDefinition.Layer;
import com.mirukusora26.particleCreater.model.CoordinateBounds;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Tick-driven particle playback. Each run is isolated and has a bounded emission budget. */
public final class PlaybackManager implements AutoCloseable {
    public record Context(Location origin,Entity caller,Entity follow,Collection<Player> receivers,Map<String,Double> overrides) {
        public Context {
            if(origin==null||origin.getWorld()==null) throw new IllegalArgumentException("Playback needs a world and location");
            CoordinateBounds.require(origin.getX(),origin.getY(),origin.getZ());
            origin=origin.clone();
            if(receivers!=null&&receivers.stream().anyMatch(player -> player==null)) throw new IllegalArgumentException("Playback receivers cannot contain null players");
            receivers=receivers==null?null:List.copyOf(receivers);
            if(overrides!=null) for(var entry:overrides.entrySet())
                if(entry.getKey()==null||entry.getValue()==null||!Double.isFinite(entry.getValue())) throw new IllegalArgumentException("Playback variable overrides must have names and finite values");
            overrides=overrides==null?Map.of():Map.copyOf(overrides);
        }
        public static Context at(Location origin) {return new Context(origin,null,null,null,Map.of());}
    }
    public record Handle(String id,String name,boolean paused,int ageTicks) {}
    private static final int MAX_RUNS=64,MAX_POINTS_PER_RUN=2048,MAX_PARTICLES_PER_TICK=10000;
    private final JavaPlugin plugin;
    private final EffectService definitions;
    private final Map<String,Run> runs=new LinkedHashMap<>();
    private final BukkitTask task;
    private int remainingThisTick;
    private int lastBudgetTick=Integer.MIN_VALUE;

    public PlaybackManager(JavaPlugin plugin,EffectService definitions) {
        this.plugin=plugin;this.definitions=definitions;
        this.task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }

    public Handle play(EffectDefinition effect,Context context) {
        primaryThread();
        if(runs.size()>=MAX_RUNS) throw new IllegalArgumentException("Too many active effects (limit "+MAX_RUNS+")");
        Run run=prepare(effect,context);
        runs.put(run.id,run);
        return run.handle();
    }
    public void spawn(EffectDefinition effect,Context context) {
        primaryThread();
        Run run=prepare(effect,context);
        resetBudgetIfNewTick();
        render(run,true);
    }
    public boolean stop(String id) {primaryThread();return runs.remove(id)!=null;}
    public int stopAll(String name) {primaryThread();int before=runs.size();runs.values().removeIf(run -> run.effect.name.equals(name));return before-runs.size();}
    public boolean pause(String id) {primaryThread();Run run=runs.get(id);if(run==null)return false;run.paused=true;return true;}
    public boolean resume(String id) {primaryThread();Run run=runs.get(id);if(run==null)return false;run.paused=false;return true;}
    public List<Handle> running() {primaryThread();return runs.values().stream().map(Run::handle).toList();}

    private Run prepare(EffectDefinition effect,Context context) {
        List<String> errors=definitions.validate(effect,true);
        if(!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ",errors));
        if(effect.anchor.equals("caller")&&context.caller()==null) throw new IllegalArgumentException("Caller anchor requires a player or entity caller");
        if(effect.anchor.equals("target")&&context.follow()==null) throw new IllegalArgumentException("Target anchor requires follow <entity>");
        Map<String,Double> params=new HashMap<>(effect.parameters);
        for(var entry:context.overrides().entrySet()) {
            if(!params.containsKey(entry.getKey())||!Double.isFinite(entry.getValue())) throw new IllegalArgumentException("Invalid variable override: "+entry.getKey());
            params.put(entry.getKey(),entry.getValue());
        }
        Set<String> variables=new java.util.HashSet<>(params.keySet());variables.addAll(Set.of("x","y","z","w","u","v","t"));
        List<PreparedLayer> layers=new ArrayList<>(effect.layers.size());
        for(Layer layer:effect.layers) {
            Particle particle=ParticleDataCodec.particle(layer.particle);
            boolean locationDependent=ParticleDataCodec.locationDependent(particle);
            Object fixedData;
            try {fixedData=locationDependent?null:ParticleDataCodec.decode(layer,context.origin());}
            catch(RuntimeException e){throw new IllegalArgumentException("Layer '"+layer.id+"' particle '"+layer.particle+"' data preparation failed: "+reason(e),e);}
            layers.add(new PreparedLayer(layer,particle,fixedData,locationDependent));
        }
        ShapeEngine shapes=new ShapeEngine(effect);
        if(!context.overrides().isEmpty()) {
            Set<String> sampled=new java.util.HashSet<>();
            for(Layer layer:effect.layers) if(sampled.add(layer.shape)) try {shapes.sample(layer.shape,0,params,MAX_POINTS_PER_RUN,p -> {});}
            catch(RuntimeException e){throw new IllegalArgumentException("Shape '"+layer.shape+"' used by layer '"+layer.id+"' failed with variable overrides: "+reason(e),e);}
        }
        Expression[] move=compile(effect.motion.move,variables),rotate=compile(effect.motion.rotate,variables),scale=compile(effect.motion.scale,variables);
        Map<String,Double> initial=new HashMap<>(params);
        for(String axis:List.of("x","y","z","w","u","v")) initial.put(axis,0.0);
        initial.put("t",0.0);
        double[] initialMove=values(move,effect.motion.move,initial,"move"),initialRotate=values(rotate,effect.motion.rotate,initial,"rotate"),initialScale=values(scale,effect.motion.scale,initial,"scale");
        try {ShapeUtils.compileTransform(initialScale,initialRotate,initialMove);}
        catch(RuntimeException e){throw new IllegalArgumentException("Initial motion transform failed: "+reason(e),e);}
        String id;
        do {id=UUID.randomUUID().toString().substring(0,8);} while(runs.containsKey(id));
        return new Run(id,effect,context,params,layers,shapes,move,rotate,scale);
    }

    private static Expression[] compile(List<String> values,Set<String> variables) {
        Expression[] compiled=new Expression[3];
        for(int i=0;i<3;i++) compiled[i]=Expression.compile(values.get(i),variables);
        return compiled;
    }

    private void tick() {
        resetBudgetIfNewTick();
        for(Run run:new ArrayList<>(runs.values())) {
            if(run.paused) continue;
            try {
                if(run.effect.durationTicks!=-1&&run.age>=run.effect.durationTicks) {runs.remove(run.id);continue;}
                if(run.age%run.effect.intervalTicks==0) render(run,false);
                run.age++;
            } catch(Exception e) {
                runs.remove(run.id);
                Location last=run.context.origin();
                plugin.getLogger().log(Level.WARNING,"[PLAYBACK] Effect '"+run.effect.name+"' run "+run.id+" stopped at age="+run.age+" ticks, anchor="+run.effect.anchor+", origin="+locationText(last)+": "+reason(e),e);
            }
        }
    }
    private void resetBudgetIfNewTick() {
        int current=Bukkit.getCurrentTick();
        if(current!=lastBudgetTick) {remainingThisTick=MAX_PARTICLES_PER_TICK;lastBudgetTick=current;}
    }

    private void render(Run run,boolean oneShot) {
        Location origin;
        try {origin=anchor(run);}
        catch(RuntimeException e){throw new IllegalStateException("Anchor '"+run.effect.anchor+"' resolution failed: "+reason(e),e);}
        CoordinateBounds.require(origin.getX(),origin.getY(),origin.getZ());
        List<Player> receivers=run.context.receivers()==null?null:run.context.receivers().stream().filter(player -> player.isOnline()&&player.getWorld().equals(origin.getWorld())).toList();
        double t=run.age/20.0;
        Map<String,Double> vars=new HashMap<>(run.params);
        for(String axis:List.of("x","y","z","w","u","v")) vars.put(axis,0.0);
        vars.put("t",t);
        double[] move=values(run.move,run.effect.motion.move,vars,"move"),rotate=values(run.rotate,run.effect.motion.rotate,vars,"rotate"),scale=values(run.scale,run.effect.motion.scale,vars,"scale");
        ShapeUtils.Transform motion;
        try {motion=ShapeUtils.compileTransform(scale,rotate,move);}
        catch(RuntimeException e){throw new IllegalArgumentException("Motion transform at age="+run.age+" ticks failed: "+reason(e),e);}
        int[] used={0};
        if(run.effect.mix.equals("weighted")) {
            List<PreparedLayer> active=run.layers.stream().filter(layer -> active(layer.layer(),run.age,oneShot)).toList();
            if(active.isEmpty()) return;
            double total=active.stream().mapToDouble(layer -> layer.layer().weight).sum();
            String shape=active.getFirst().layer().shape;
            try {run.shapes.sample(shape,t,run.params,MAX_POINTS_PER_RUN,p -> {
                double choice=ThreadLocalRandom.current().nextDouble(total);
                PreparedLayer selected=active.getLast();
                for(PreparedLayer layer:active) {choice-=layer.layer().weight;if(choice<0){selected=layer;break;}}
                emit(selected,p,motion,origin,receivers,used);
            });}
            catch(RuntimeException e){throw new IllegalArgumentException("Weighted shape '"+shape+"' at age="+run.age+" ticks failed: "+reason(e),e);}
        } else {
            for(PreparedLayer layer:run.layers) if(active(layer.layer(),run.age,oneShot)) {
                try {run.shapes.sample(layer.layer().shape,t,run.params,MAX_POINTS_PER_RUN-used[0],p -> emit(layer,p,motion,origin,receivers,used));}
                catch(RuntimeException e){throw new IllegalArgumentException("Layer '"+layer.layer().id+"' shape '"+layer.layer().shape+"' at age="+run.age+" ticks failed: "+reason(e),e);}
            }
        }
    }

    static boolean active(Layer layer,long age,boolean oneShot) {
        return oneShot || age>=layer.startTicks && (layer.durationTicks==-1||age<(long)layer.startTicks+layer.durationTicks) && (age-layer.startTicks)%layer.intervalTicks==0;
    }
    private static double[] values(Expression[] expressions,List<String> sources,Map<String,Double> vars,String field) {
        double[] result=new double[3];String[] axes={"x","y","z"};
        for(int i=0;i<3;i++) try {result[i]=expressions[i].evaluate(vars);}
        catch(RuntimeException e){throw new IllegalArgumentException("Motion "+field+"."+axes[i]+" formula '"+sources.get(i)+"' failed: "+reason(e),e);}
        return result;
    }
    private Location anchor(Run run) {
        Entity tracked=switch(run.effect.anchor) {case "caller" -> run.context.caller();case "target" -> run.context.follow();default -> null;};
        if(tracked!=null) {
            if(!tracked.isValid()) throw new IllegalStateException("Tracked entity "+tracked.getUniqueId()+" is gone");
            return tracked.getLocation();
        }
        Location origin=run.context.origin();
        World world=origin.getWorld();
        if(world==null||Bukkit.getWorld(world.getUID())==null) throw new IllegalStateException("Effect world '"+(world==null?"null":world.getName())+"' is unloaded");
        return origin;
    }
    private void emit(PreparedLayer prepared,Vec3 point,ShapeUtils.Transform motion,Location origin,List<Player> receivers,int[] used) {
        Layer layer=prepared.layer();
        int cost=Math.max(1,layer.count);
        if(++used[0]>MAX_POINTS_PER_RUN) throw new IllegalArgumentException("Layer '"+layer.id+"' exceeded "+MAX_POINTS_PER_RUN+" sampled points per run; reduce shape density");
        if(remainingThisTick<cost) throw new IllegalArgumentException("Layer '"+layer.id+"' needs "+cost+" particles but only "+remainingThisTick+" remain of the "+MAX_PARTICLES_PER_TICK+" per-tick budget");
        remainingThisTick-=cost;
        Vec3 p=motion.apply(point);
        if(!p.finite()) throw new ArithmeticException("Layer '"+layer.id+"' motion produced non-finite coordinates from shape point "+point);
        Location place=origin.clone().add(p.x(),p.y(),p.z());
        CoordinateBounds.require(place.getX(),place.getY(),place.getZ());
        try {
            Object data=prepared.locationDependent()?ParticleDataCodec.decode(layer,place):prepared.fixedData();
            var builder=prepared.particle().builder().location(place).count(layer.count).offset(layer.offset[0],layer.offset[1],layer.offset[2]).extra(layer.extra).data(data).force(false);
            if(receivers==null) builder.receivers(32,true);
            else builder.receivers(receivers);
            builder.spawn();
        } catch(RuntimeException e){throw new IllegalArgumentException("Layer '"+layer.id+"' particle '"+layer.particle+"' emission at "+locationText(place)+" failed: "+reason(e),e);}
    }
    private static String reason(Throwable error) {return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private static String locationText(Location location) {return (location.getWorld()==null?"null":location.getWorld().getName())+" ("+location.getX()+", "+location.getY()+", "+location.getZ()+")";}
    private static void primaryThread() {if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Particle API must be called on the server thread");}
    @Override public void close() {task.cancel();runs.clear();}

    private record PreparedLayer(Layer layer,Particle particle,Object fixedData,boolean locationDependent) {}

    private static final class Run {
        final String id;final EffectDefinition effect;final Context context;final Map<String,Double> params;final ShapeEngine shapes;
        final List<PreparedLayer> layers;final Expression[] move,rotate,scale;long age;boolean paused;
        Run(String id,EffectDefinition effect,Context context,Map<String,Double> params,List<PreparedLayer> layers,ShapeEngine shapes,Expression[] move,Expression[] rotate,Expression[] scale) {
            this.id=id;this.effect=effect;this.context=context;this.params=params;this.layers=layers;this.shapes=shapes;this.move=move;this.rotate=rotate;this.scale=scale;
        }
        Handle handle(){return new Handle(id,effect.name,paused,age>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)age);}
    }
}
