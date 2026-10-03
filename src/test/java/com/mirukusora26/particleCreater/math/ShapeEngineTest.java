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
    @Test void coordinateDependentSlicesUseEachSamplePosition() {
        EffectDefinition effect=new EffectDefinition("slice_coordinates");
        Shape curve=new Shape("curve4","curve");
        curve.dimensions=4;curve.step=0.5;
        curve.numbers.put("umin",-1.0);curve.numbers.put("umax",1.0);
        curve.expressions.addAll(List.of("u","0","0","u"));curve.slice="x";
        effect.shapes.add(curve);
        int matching=new ShapeEngine(effect).sample("curve4",0,Map.of(),100,p -> {});
        curve.slice="0";
        int flat=new ShapeEngine(effect).sample("curve4",0,Map.of(),100,p -> {});
        assertEquals(5,matching);
        assertEquals(1,flat);

        Shape plane=new Shape("plane4","equation");
        plane.dimensions=4;plane.step=0.5;plane.expressions.add("w=0");plane.slice="x";
        effect.shapes.add(plane);
        int crossing=new ShapeEngine(effect).sample("plane4",0,Map.of(),2048,p -> {});
        plane.slice="1";
        int outside=new ShapeEngine(effect).sample("plane4",0,Map.of(),2048,p -> {});
        assertTrue(crossing>0);
        assertEquals(0,outside);
    }
    @Test void parametricOutputsAndSliceCannotReadTheirOwnUndefinedValues() {
        EffectDefinition effect=new EffectDefinition("invalid_variables");
        Shape curve=new Shape("curve4","curve");curve.dimensions=4;
        curve.expressions.addAll(List.of("x+u","0","0","u"));
        effect.shapes.add(curve);
        assertTrue(assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect)).getMessage().contains("Shape 'curve4' curve formula[0]='x+u'"));
        curve.expressions.set(0,"u");curve.slice="w";
        assertTrue(assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect)).getMessage().contains("Shape 'curve4' slice formula='w'"));
        curve.slice="0";
        Shape equation=new Shape("equation4","equation");equation.dimensions=4;equation.expressions.add("w=0");equation.slice="u";
        effect.shapes.add(equation);
        assertTrue(assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect)).getMessage().contains("Shape 'equation4' slice formula='u'"));
        effect.shapes.remove(equation);
        Shape basic=new Shape("basic4","sphere");basic.dimensions=4;
        effect.shapes.add(basic);
        assertTrue(assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect)).getMessage().contains("4D requires a formula shape: basic4"));
    }
    @Test void denseFourDimensionalProjectionStopsAtPointBudget() {
        EffectDefinition effect=new EffectDefinition("dense_four");
        Shape surface=new Shape("surface4","surface");
        surface.dimensions=4;surface.view="project";surface.step=0.5;
        surface.expressions.addAll(List.of("u","v","0","1"));
        effect.shapes.add(surface);
        List<Vec3> emitted=new ArrayList<>();
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
            () -> new ShapeEngine(effect).sample("surface4",0,Map.of(),32,emitted::add));
        assertTrue(error.getMessage().contains("exceeds 32 points"));
        assertEquals(32,emitted.size());
    }
    @Test void runtimeFormulaFailureIdentifiesShapeAndSampleVariables() {
        EffectDefinition effect=new EffectDefinition("runtime_formula");
        Shape curve=new Shape("broken_curve","curve");
        curve.expressions.addAll(List.of("1/(u-u)","0","0"));
        effect.shapes.add(curve);
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
            () -> new ShapeEngine(effect).sample("broken_curve",0,Map.of(),100,p -> {}));
        assertTrue(error.getMessage().contains("Shape 'broken_curve' formula[0]='1/(u-u)' failed at"));
        assertTrue(error.getMessage().contains("u="));
    }
    @Test void compiledTransformPreservesRotationAndSnapshotsSettings() {
        Shape shape=new Shape("rotated","point");
        shape.scale=new double[]{2,1,1};
        shape.rotate=new double[]{0,90,0};
        shape.translate=new double[]{4,5,6};
        ShapeUtils.Transform transform=ShapeUtils.compileTransform(shape);
        shape.rotate[1]=0;
        Vec3 result=transform.apply(new Vec3(1,0,0));
        assertEquals(4,result.x(),1e-10);
        assertEquals(5,result.y(),1e-10);
        assertEquals(4,result.z(),1e-10);
        assertThrows(IllegalArgumentException.class,() -> ShapeUtils.compileTransform(new double[]{1,1,1},new double[]{0,1e308,0},new double[]{0,0,0}));
    }
    @Test void invalidShapeSettingsFailAtCompilation() {
        EffectDefinition effect=new EffectDefinition("invalid_shape");
        Shape surface=new Shape("surface","surface");
        surface.expressions.addAll(List.of("u","0","v"));
        surface.bounds[0]=Double.NaN;
        effect.shapes.add(surface);
        assertTrue(assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect)).getMessage().contains("bounds.x=[NaN, 2.0]"));
        surface.bounds[0]=-2;
        surface.numbers.put("wslices",2.5);
        assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect));
        surface.numbers.put("wslices",2.0);
        surface.numbers.put("vmax",-1.0);
        assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect));
    }
    @Test void excessiveBezierControlPointsAreRejectedBeforeSampling() {
        EffectDefinition effect=new EffectDefinition("oversized_bezier");
        Shape bezier=new Shape("curve","bezier");
        for(int i=0;i<65;i++) bezier.points.add(List.of((double)i/2,0.0,0.0));
        effect.shapes.add(bezier);
        assertThrows(IllegalArgumentException.class,() -> new ShapeEngine(effect));
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
    @Test void groupKeepsChildAndParentTransforms() {
        EffectDefinition effect=new EffectDefinition("grouped");
        Shape child=new Shape("child","point");child.translate=new double[]{1,0,0};
        Shape group=new Shape("group","group");group.children.add("child");group.rotate=new double[]{0,90,0};
        effect.shapes.add(child);effect.shapes.add(group);
        List<Vec3> points=new ArrayList<>();
        assertEquals(1,new ShapeEngine(effect).sample("group",0,Map.of(),10,points::add));
        assertEquals(0,points.getFirst().x(),1e-10);
        assertEquals(0,points.getFirst().y(),1e-10);
        assertEquals(-1,points.getFirst().z(),1e-10);
    }
    @Test void solidBasicShapeContainsMorePointsThanItsSurface() {
        EffectDefinition effect=new EffectDefinition("filled");
        Shape box=new Shape("box","box");box.step=0.5;effect.shapes.add(box);
        int surface=new ShapeEngine(effect).sample("box",0,Map.of(),2048,p -> {});
        box.fill="solid";
        int solid=new ShapeEngine(effect).sample("box",0,Map.of(),2048,p -> {});
        assertTrue(solid>surface);
    }
    @Test void oversizedBasicShapeGridsFailBeforeSampling() {
        EffectDefinition effect=new EffectDefinition("huge");
        Shape circle=new Shape("huge_circle","circle");
        circle.fill="surface";circle.step=0.02;circle.numbers.put("radius",10000.0);
        effect.shapes.add(circle);
        IllegalArgumentException area=assertThrows(IllegalArgumentException.class,
            () -> new ShapeEngine(effect).sample("huge_circle",0,Map.of(),2048,p -> {}));
        assertTrue(area.getMessage().contains("grid exceeds"));

        Shape star=new Shape("huge_star","star");
        star.fill="solid";star.step=0.02;star.numbers.put("radius",10000.0);
        effect.shapes.add(star);
        IllegalArgumentException flat=assertThrows(IllegalArgumentException.class,
            () -> new ShapeEngine(effect).sample("huge_star",0,Map.of(),2048,p -> {}));
        assertTrue(flat.getMessage().contains("grid exceeds"));
    }
}
