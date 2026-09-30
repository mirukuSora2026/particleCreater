package com.mirukusora26.particleCreater.math;

import com.mirukusora26.particleCreater.model.EffectDefinition;
import com.mirukusora26.particleCreater.model.EffectDefinition.Shape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Compiles and samples the shapes in one effect, including four-dimensional slices. */
public final class ShapeEngine {
    private record Compiled(Shape definition, List<Expression> expressions, Expression slice) {}
    private final Map<String, Compiled> shapes = new HashMap<>();
    private final Set<String> variables;

    public ShapeEngine(EffectDefinition effect) {
        variables = new HashSet<>(Set.of("x","y","z","w","u","v","t"));
        variables.addAll(effect.parameters.keySet());
        for (Shape shape : effect.shapes) {
            if (shapes.containsKey(shape.id)) throw new IllegalArgumentException("Duplicate shape: " + shape.id);
            validateShape(shape);
            List<Expression> expr = new ArrayList<>();
            if (List.of("curve","surface","equation","solid","system").contains(shape.kind)) {
                for (String source : shape.expressions) expr.add(Expression.compile(normalize(source, shape.kind), variables));
            }
            shapes.put(shape.id, new Compiled(shape, expr, Expression.compile(shape.slice, variables)));
        }
        for (Shape shape : effect.shapes) if (shape.kind.equals("group")) {
            for (String child : shape.children) if (!shapes.containsKey(child)) throw new IllegalArgumentException("Group " + shape.id + " references missing shape " + child);
            checkCycle(shape.id, new HashSet<>());
        }
    }

    public Set<String> names() { return Set.copyOf(shapes.keySet()); }

    public int sample(String id, double timeSeconds, Map<String,Double> parameters, int maxPoints, Consumer<Vec3> output) {
        if (maxPoints < 1) throw new IllegalArgumentException("maxPoints must be positive");
        Map<String,Double> vars=new HashMap<>(parameters);
        for(String standard : List.of("x","y","z","w","u","v")) vars.put(standard,0.0);
        vars.put("t",timeSeconds);
        Budget budget=new Budget(maxPoints);
        sampleNested(id,vars,budget,output);
        return budget.count;
    }

    private void sampleNested(String id, Map<String,Double> vars, Budget budget, Consumer<Vec3> output) {
        Compiled c=shapes.get(id);
        if(c==null) throw new IllegalArgumentException("Unknown shape: " + id);
        Shape s=c.definition;
        Consumer<Vec3> transformed=p -> {
            Vec3 v=ShapeUtils.transform(p,s);
            if(!v.finite()) throw new ArithmeticException("Non-finite coordinates in shape " + id);
            budget.emit(v,output);
        };
        switch(s.kind) {
            case "group" -> {
                for(String child:s.children) sampleNested(child,vars,budget,p -> {
                    Vec3 v=ShapeUtils.transform(p,s);
                    if(!v.finite()) throw new ArithmeticException("Non-finite coordinates in group "+id);
                    output.accept(v);
                });
            }
            case "points" -> {
                for(List<Double> p:s.points) transformed.accept(new Vec3(p.get(0),p.get(1),p.get(2)));
            }
            case "curve", "surface" -> parametric(c,vars,transformed);
            case "equation", "solid", "system" -> implicit(c,vars,transformed);
            default -> ShapeUtils.sampleBasic(s,transformed);
        }
    }

    private void parametric(Compiled c,Map<String,Double> vars,Consumer<Vec3> out) {
        Shape s=c.definition;
        int nu=steps(s.numbers.getOrDefault("umin",0.0),s.numbers.getOrDefault("umax",Math.PI*2),s.step,512);
        int nv=s.kind.equals("surface")?steps(s.numbers.getOrDefault("vmin",0.0),s.numbers.getOrDefault("vmax",Math.PI),s.step,256):0;
        if((long)(nu+1)*(nv+1)>100_000) throw new IllegalArgumentException("Shape sampling grid exceeds 100000 points: "+s.id);
        double umin=s.numbers.getOrDefault("umin",0.0),umax=s.numbers.getOrDefault("umax",Math.PI*2);
        double vmin=s.numbers.getOrDefault("vmin",0.0),vmax=s.numbers.getOrDefault("vmax",Math.PI);
        for(int i=0;i<=nu;i++) for(int j=0;j<=nv;j++) {
            vars.put("u",umin+(umax-umin)*i/nu);
            vars.put("v",nv==0?0:vmin+(vmax-vmin)*j/nv);
            double x=c.expressions.get(0).evaluate(vars), y=c.expressions.get(1).evaluate(vars), z=c.expressions.get(2).evaluate(vars);
            if(s.dimensions==4) {
                double w=c.expressions.get(3).evaluate(vars);
                if(s.view.equals("slice")) {
                    if(Math.abs(w-c.slice.evaluate(vars))>s.step/2) continue;
                } else { x+=w*s.projection[0]; y+=w*s.projection[1]; z+=w*s.projection[2]; }
            }
            out.accept(new Vec3(x,y,z));
        }
    }

    private void implicit(Compiled c,Map<String,Double> vars,Consumer<Vec3> out) {
        Shape s=c.definition;
        Set<Vec3> emitted=new HashSet<>();
        int nx=steps(s.bounds[0],s.bounds[1],s.step,128);
        int ny=steps(s.bounds[2],s.bounds[3],s.step,128);
        int nz=steps(s.bounds[4],s.bounds[5],s.step,128);
        int nw=s.dimensions==4 && s.view.equals("project")?Math.max(1,Math.min(4,s.numbers.getOrDefault("wslices",4.0).intValue())):1;
        if((long)(nx+1)*(ny+1)*(nz+1)*nw>100_000) throw new IllegalArgumentException("Equation grid exceeds 100000 points: "+s.id);
        double dx=(s.bounds[1]-s.bounds[0])/nx,dy=(s.bounds[3]-s.bounds[2])/ny,dz=(s.bounds[5]-s.bounds[4])/nz;
        for(int wi=0;wi<nw;wi++) {
            double w=s.dimensions==4?(s.view.equals("slice")?c.slice.evaluate(vars):s.bounds[6]+(s.bounds[7]-s.bounds[6])*(wi+0.5)/nw):0;
            vars.put("w",w);
            for(int ix=0;ix<=nx;ix++) for(int iy=0;iy<=ny;iy++) for(int iz=0;iz<=nz;iz++) {
                double x=s.bounds[0]+ix*dx,y=s.bounds[2]+iy*dy,z=s.bounds[4]+iz*dz;
                vars.put("x",x);vars.put("y",y);vars.put("z",z);
                double f=c.expressions.get(0).evaluate(vars);
                if(s.kind.equals("solid")) {
                    if(f<=0) out.accept(project(s,new Vec3(x,y,z),w));
                    continue;
                }
                if(ix==nx || iy==ny || iz==nz) continue;
                double[][] dirs={{dx,0,0},{0,dy,0},{0,0,dz}};
                for(double[] dir:dirs) {
                    vars.put("x",x+dir[0]);vars.put("y",y+dir[1]);vars.put("z",z+dir[2]);
                    double next=c.expressions.get(0).evaluate(vars);
                    if((f<0 && next>0)||(f>0 && next<0)||(f==0 && next!=0)||(next==0 && f!=0)) {
                        double a=Math.abs(f)/(Math.abs(f)+Math.abs(next)+1e-12);
                        double px=x+dir[0]*a,py=y+dir[1]*a,pz=z+dir[2]*a;
                        vars.put("x",px);vars.put("y",py);vars.put("z",pz);
                        boolean match=true;
                        if(s.kind.equals("system")) for(int q=1;q<c.expressions.size();q++) if(Math.abs(c.expressions.get(q).evaluate(vars))>s.step) {match=false;break;}
                        if(match) {
                            Vec3 point=project(s,new Vec3(px,py,pz),w);
                            if(emitted.add(point)) out.accept(point);
                        }
                    }
                }
            }
        }
    }

    private static Vec3 project(Shape s,Vec3 p,double w) {
        if(s.dimensions!=4 || s.view.equals("slice")) return p;
        return new Vec3(p.x()+w*s.projection[0],p.y()+w*s.projection[1],p.z()+w*s.projection[2]);
    }

    private void checkCycle(String id,Set<String> stack) {
        if(!stack.add(id)) throw new IllegalArgumentException("Shape group cycle at "+id);
        Shape s=shapes.get(id).definition;
        if(s.kind.equals("group")) for(String child:s.children) checkCycle(child,stack);
        stack.remove(id);
    }

    private static String normalize(String source,String kind) {
        if(source==null) throw new IllegalArgumentException("Missing formula");
        if(List.of("equation","system").contains(kind) && source.contains("=") && !source.contains("==")) {
            String[] halves=source.split("=",-1);
            if(halves.length==2) return "("+halves[0]+")-("+halves[1]+")";
        }
        if(kind.equals("solid") && source.contains("<=")) {
            String[] halves=source.split("<=",-1);
            if(halves.length==2) return "("+halves[0]+")-("+halves[1]+")";
        }
        return source;
    }

    private static int steps(double min,double max,double step,int maxSteps) {
        if(!Double.isFinite(min)||!Double.isFinite(max)||min>=max||max-min>64) throw new IllegalArgumentException("Invalid shape range");
        int count=(int)Math.ceil((max-min)/step);
        if(count>maxSteps) throw new IllegalArgumentException("Shape range needs "+count+" steps, limit is "+maxSteps+"; increase step or reduce bounds");
        return Math.max(1,count);
    }

    private static void validateShape(Shape s) {
        if(s==null || s.id==null || !s.id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid shape name");
        if(s.kind==null) throw new IllegalArgumentException("Missing shape kind: "+s.id);
        if(s.fill==null||!List.of("outline","surface","solid").contains(s.fill)) throw new IllegalArgumentException("Fill must be outline, surface or solid: "+s.id);
        if(!Double.isFinite(s.step)||s.step<0.02||s.step>8) throw new IllegalArgumentException("Shape step must be 0.02..8: "+s.id);
        if(s.dimensions<3||s.dimensions>4) throw new IllegalArgumentException("Shape dimension must be 3 or 4: "+s.id);
        if(s.view==null||!List.of("slice","project").contains(s.view)) throw new IllegalArgumentException("Invalid 4D view: "+s.id);
        if(s.translate==null||s.translate.length!=3||s.rotate==null||s.rotate.length!=3||s.scale==null||s.scale.length!=3||s.projection==null||s.projection.length!=3) throw new IllegalArgumentException("Invalid transform: "+s.id);
        for(double n:s.translate) if(!Double.isFinite(n)) throw new IllegalArgumentException("Invalid translation: "+s.id);
        for(double n:s.rotate) if(!Double.isFinite(n)) throw new IllegalArgumentException("Invalid rotation: "+s.id);
        for(double n:s.scale) if(!Double.isFinite(n)||n==0||Math.abs(n)>100) throw new IllegalArgumentException("Invalid scale: "+s.id);
        for(double n:s.projection) if(!Double.isFinite(n)||Math.abs(n)>100) throw new IllegalArgumentException("Invalid projection: "+s.id);
        if(s.bounds==null||s.bounds.length!=8) throw new IllegalArgumentException("Invalid bounds: "+s.id);
        if(s.expressions==null||s.points==null||s.children==null||s.numbers==null) throw new IllegalArgumentException("Missing shape fields: "+s.id);
        for(double n:s.numbers.values()) if(!Double.isFinite(n)||Math.abs(n)>10000) throw new IllegalArgumentException("Invalid shape parameter: "+s.id);
        for(String positive:List.of("radius","rx","ry","rz","width","height","depth","tube","length")) {
            Double value=s.numbers.get(positive);
            if(value!=null&&value<=0) throw new IllegalArgumentException(positive+" must be positive: "+s.id);
        }
        int expected=s.dimensions;
        switch(s.kind) {
            case "curve","surface" -> { if(s.expressions.size()!=expected) throw new IllegalArgumentException(s.kind+" needs "+expected+" coordinate expressions: "+s.id); }
            case "equation","solid" -> { if(s.expressions.size()!=1) throw new IllegalArgumentException(s.kind+" needs one expression: "+s.id); }
            case "system" -> { if(s.expressions.size()<2||s.expressions.size()>4) throw new IllegalArgumentException("System needs 2..4 equations: "+s.id); }
            case "points" -> { if(s.points.size()>2048) throw new IllegalArgumentException("Point shape exceeds 2048 points: "+s.id);for(List<Double> p:s.points) if(p==null||p.size()!=3||p.stream().anyMatch(n -> n==null||!Double.isFinite(n)||Math.abs(n)>64)) throw new IllegalArgumentException("Invalid point: "+s.id); }
            case "group" -> { }
            default -> { if(!ShapeUtils.BASIC.contains(s.kind)) throw new IllegalArgumentException("Unknown shape kind: "+s.kind); }
        }
    }

    private static final class Budget {
        final int max; int count;
        Budget(int max) {this.max=max;}
        void emit(Vec3 p,Consumer<Vec3> out) {
            if(++count>max) throw new IllegalArgumentException("Shape exceeds "+max+" points per frame; increase step or reduce bounds");
            out.accept(p);
        }
    }
}
