package com.mirukusora26.particleCreater.engine;

import com.mirukusora26.particleCreater.math.Expression;
import com.mirukusora26.particleCreater.math.ShapeEngine;
import com.mirukusora26.particleCreater.math.ShapeUtils;
import com.mirukusora26.particleCreater.math.Vec3;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import com.mirukusora26.particleCreater.model.EffectDefinition.Layer;
import com.mirukusora26.particleCreater.model.EffectDefinition.Shape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
            origin=origin.clone();
            receivers=receivers==null?null:List.copyOf(receivers);
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
    public List<Handle> running() {return runs.values().stream().map(Run::handle).toList();}

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
        return new Run(UUID.randomUUID().toString().substring(0,8),effect,context,params,new ShapeEngine(effect),compile(effect.motion.move,variables),compile(effect.motion.rotate,variables),compile(effect.motion.scale,variables));
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
                plugin.getLogger().warning("Stopped effect '"+run.effect.name+"' run "+run.id+": "+e.getMessage());
            }
        }
    }
    private void resetBudgetIfNewTick() {
        int current=Bukkit.getCurrentTick();
        if(current!=lastBudgetTick) {remainingThisTick=MAX_PARTICLES_PER_TICK;lastBudgetTick=current;}
    }

    private void render(Run run,boolean oneShot) {
        Location origin=anchor(run);
        double t=run.age/20.0;
        Map<String,Double> vars=new HashMap<>(run.params);
        for(String axis:List.of("x","y","z","w","u","v")) vars.put(axis,0.0);
        vars.put("t",t);
        Shape motion=new Shape("motion","point");
        motion.translate=values(run.move,vars);motion.rotate=values(run.rotate,vars);motion.scale=values(run.scale,vars);
        int[] used={0};
        if(run.effect.mix.equals("weighted")) {
            List<Layer> active=run.effect.layers.stream().filter(layer -> active(layer,run.age,oneShot)).toList();
            if(active.isEmpty()) return;
            double total=active.stream().mapToDouble(layer -> layer.weight).sum();
            run.shapes.sample(active.getFirst().shape,t,run.params,MAX_POINTS_PER_RUN,p -> {
                double choice=ThreadLocalRandom.current().nextDouble(total);
                Layer selected=active.getLast();
                for(Layer layer:active) {choice-=layer.weight;if(choice<0){selected=layer;break;}}
                emit(run,selected,p,motion,origin,used);
            });
        } else {
            for(Layer layer:run.effect.layers) if(active(layer,run.age,oneShot)) {
                run.shapes.sample(layer.shape,t,run.params,MAX_POINTS_PER_RUN-used[0],p -> emit(run,layer,p,motion,origin,used));
            }
        }
    }

    private static boolean active(Layer layer,int age,boolean oneShot) {
        return oneShot || age>=layer.startTicks && (layer.durationTicks==-1||age<layer.startTicks+layer.durationTicks) && (age-layer.startTicks)%layer.intervalTicks==0;
    }
    private static double[] values(Expression[] expressions,Map<String,Double> vars) {
        return new double[]{expressions[0].evaluate(vars),expressions[1].evaluate(vars),expressions[2].evaluate(vars)};
    }
    private Location anchor(Run run) {
        Entity tracked=switch(run.effect.anchor) {case "caller" -> run.context.caller();case "target" -> run.context.follow();default -> null;};
        if(tracked!=null) {
            if(!tracked.isValid()) throw new IllegalStateException("Tracked entity is gone");
            return tracked.getLocation();
        }
        Location origin=run.context.origin();
        World world=origin.getWorld();
        if(world==null||Bukkit.getWorld(world.getUID())==null) throw new IllegalStateException("Effect world is unloaded");
        return origin;
    }
    private void emit(Run run,Layer layer,Vec3 point,Shape motion,Location origin,int[] used) {
        int cost=Math.max(1,layer.count);
        if(++used[0]>MAX_POINTS_PER_RUN||remainingThisTick<cost) throw new IllegalArgumentException("Particle budget exceeded; reduce shape density or count");
        remainingThisTick-=cost;
        Vec3 p=ShapeUtils.transform(point,motion);
        if(!p.finite()) throw new ArithmeticException("Non-finite motion result");
        Location place=origin.clone().add(p.x(),p.y(),p.z());
        Particle type=ParticleDataCodec.particle(layer.particle);
        Object data=ParticleDataCodec.decode(layer,place);
        var builder=type.builder().location(place).count(layer.count).offset(layer.offset[0],layer.offset[1],layer.offset[2]).extra(layer.extra).data(data).force(false);
        if(run.context.receivers()==null) builder.receivers(32,true);
        else builder.receivers(run.context.receivers().stream().filter(player -> player.isOnline()&&player.getWorld().equals(place.getWorld())).toList());
        builder.spawn();
    }
    private static void primaryThread() {if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Particle API must be called on the server thread");}
    @Override public void close() {task.cancel();runs.clear();}

    private static final class Run {
        final String id;final EffectDefinition effect;final Context context;final Map<String,Double> params;final ShapeEngine shapes;
        final Expression[] move,rotate,scale;int age;boolean paused;
        Run(String id,EffectDefinition effect,Context context,Map<String,Double> params,ShapeEngine shapes,Expression[] move,Expression[] rotate,Expression[] scale) {
            this.id=id;this.effect=effect;this.context=context;this.params=params;this.shapes=shapes;this.move=move;this.rotate=rotate;this.scale=scale;
        }
        Handle handle(){return new Handle(id,effect.name,paused,age);}
    }
}
