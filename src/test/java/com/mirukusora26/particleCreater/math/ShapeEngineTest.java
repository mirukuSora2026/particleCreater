package com.mirukusora26.particleCreater.math;

import static org.junit.jupiter.api.Assertions.*;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import com.mirukusora26.particleCreater.model.EffectDefinition.Shape;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ShapeEngineTest {
    @Test void basicAndImplicitShapesProduceFinitePoints() {
        EffectDefinition effect=new EffectDefinition("geometry");
        Shape sphere=new Shape("sphere_eq","equation");
        sphere.expressions.add("x^2+y^2+z^2=1");sphere.step=0.5;
        effect.shapes.add(sphere);
        ShapeEngine engine=new ShapeEngine(effect);
        List<Vec3> result=new ArrayList<>();
        assertTrue(engine.sample("sphere_eq",0,Map.of(),2048,result::add)>0);
        assertTrue(result.stream().allMatch(Vec3::finite));
        assertEquals(1,engine.sample("origin",0,Map.of(),10,p -> {}));
    }
    @Test void fourDimensionalSliceChangesWithW() {
        EffectDefinition effect=new EffectDefinition("four");
        Shape sphere=new Shape("hypersphere","equation");
        sphere.dimensions=4;sphere.expressions.add("x^2+y^2+z^2+w^2=4");sphere.step=0.5;
        effect.shapes.add(sphere);
        ShapeEngine engine=new ShapeEngine(effect);
        int center=engine.sample("hypersphere",0,Map.of(),2048,p -> {});
        sphere.slice="3";
        ShapeEngine outer=new ShapeEngine(effect);
        int outside=outer.sample("hypersphere",0,Map.of(),2048,p -> {});
        assertTrue(center>0);
        assertEquals(0,outside);
    }
    @Test void implicitPlaneDoesNotEmitDuplicateGridPoints() {
        EffectDefinition effect=new EffectDefinition("plane");
        Shape plane=new Shape("flat","equation");
        plane.expressions.add("z=0");plane.step=0.5;
        effect.shapes.add(plane);
        List<Vec3> points=new ArrayList<>();
        new ShapeEngine(effect).sample("flat",0,Map.of(),2048,points::add);
        assertFalse(points.isEmpty());
        assertEquals(points.size(),points.stream().distinct().count());
        assertTrue(points.stream().allMatch(p -> Math.abs(p.z())<1e-9));
    }
    @Test void groupCyclesAreRejected() {
        EffectDefinition effect=new EffectDefinition("cycle");
        Shape a=new Shape("a","group");a.children.add("b");
        Shape b=new Shape("b","group");b.children.add("a");
        effect.shapes.add(a);effect.shapes.add(b);
        assertThrows(IllegalArgumentException.class,()->new ShapeEngine(effect));
    }
    @Test void solidBasicShapeContainsMorePointsThanItsSurface() {
        EffectDefinition effect=new EffectDefinition("filled");
        Shape box=new Shape("box","box");box.step=0.5;effect.shapes.add(box);
        int surface=new ShapeEngine(effect).sample("box",0,Map.of(),2048,p -> {});
        box.fill="solid";
        int solid=new ShapeEngine(effect).sample("box",0,Map.of(),2048,p -> {});
        assertTrue(solid>surface);
    }
}
