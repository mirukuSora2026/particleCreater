package com.mirukusora26.particleCreater.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.mirukusora26.particleCreater.model.EffectDefinition.Layer;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PlaybackManagerTest {
    @Test void layerTimingDoesNotOverflowAtLargeStartTimes() {
        Layer layer=new Layer("late","flame","origin");
        layer.startTicks=Integer.MAX_VALUE-10;
        layer.durationTicks=100;
        assertFalse(PlaybackManager.active(layer,(long)layer.startTicks-1,false));
        assertTrue(PlaybackManager.active(layer,(long)layer.startTicks,false));
        assertTrue(PlaybackManager.active(layer,(long)layer.startTicks+90,false));
        assertFalse(PlaybackManager.active(layer,(long)layer.startTicks+100,false));
    }

    @Test void playbackContextRejectsInvalidApiInputs() {
        World world=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class<?>[]{World.class},(proxy,method,args) -> null);
        assertThrows(IllegalArgumentException.class,() -> PlaybackManager.Context.at(new Location(world,Double.NaN,0,0)));
        assertThrows(IllegalArgumentException.class,() -> PlaybackManager.Context.at(new Location(world,30000001,0,0)));
        Location valid=new Location(world,0,64,0);
        assertThrows(IllegalArgumentException.class,() -> new PlaybackManager.Context(valid,null,null,Arrays.asList((Player)null),Map.of()));
        Map<String,Double> invalid=new HashMap<>();invalid.put("radius",null);
        assertThrows(IllegalArgumentException.class,() -> new PlaybackManager.Context(valid,null,null,null,invalid));
    }
}
