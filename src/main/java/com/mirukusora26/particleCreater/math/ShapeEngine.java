package com.mirukusora26.particleCreater.math;

import com.mirukusora26.particleCreater.model.EffectDefinition;
import com.mirukusora26.particleCreater.model.EffectDefinition.Shape;
import com.mirukusora26.particleCreater.model.CoordinateBounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Compiles and samples the shapes in one effect, including four-dimensional slices. */
public final class ShapeEngine {
    private record Compiled(Shape definition, List<Expression> expressions, Expression slice, ShapeUtils.Transform transform) {}
    private final Map<String, Compiled> shapes = new HashMap<>();
    private final Set<String> variables;

    public ShapeEngine(EffectDefinition effect) {
        variables = new HashSet<>(Set.of("x","y","z","w","u","v","t"));
        variables.addAll(effect.parameters.keySet());
        Set<String> parametricVariables=new HashSet<>(variables);
        parametricVariables.removeAll(Set.of("x","y","z","w"));
        for (Shape shape : effect.shapes) {
            if(shape==null) throw new IllegalArgumentException("Null shape definition");
            if (shapes.containsKey(shape.id)) throw new IllegalArgumentException("Duplicate shape: " + shape.id);
            validateShape(shape);
            List<Expression> expr = new ArrayList<>();
            if (List.of("curve","surface","equation","solid","system").contains(shape.kind)) {
                Set<String> allowed=List.of("curve","surface").contains(shape.kind)?parametricVariables:variables;
                for (int i=0;i<shape.expressions.size();i++) {
                    String source=shape.expressions.get(i);
                    try {expr.add(Expression.compile(normalize(source, shape.kind), allowed));}
                    catch(RuntimeException e){throw new IllegalArgumentException("Shape '"+shape.id+"' "+shape.kind+" formula["+i+"]='"+source+"' failed: "+reason(e),e);}
                }
            }
            Set<String> sliceVariables=new HashSet<>(variables);
            sliceVariables.remove("w");
            if(!List.of("curve","surface").contains(shape.kind)) sliceVariables.removeAll(Set.of("u","v"));
            Expression slice;
            try {slice=Expression.compile(shape.slice,sliceVariables);}
            catch(RuntimeException e){throw new IllegalArgumentException("Shape '"+shape.id+"' slice formula='"+shape.slice+"' failed: "+reason(e),e);}
            ShapeUtils.Transform transform;
            try {transform=ShapeUtils.compileTransform(shape);}
            catch(RuntimeException e){throw new IllegalArgumentException("Shape '"+shape.id+"' transform failed: "+reason(e),e);}
            shapes.put(shape.id, new Compiled(shape, expr, slice,transform));
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
            Vec3 v=c.transform.apply(p);
            if(!v.finite()) throw new ArithmeticException("Non-finite coordinates in shape " + id);
            budget.emit(v,output);
        };
        switch(s.kind) {
            case "group" -> {
                for(String child:s.children) sampleNested(child,vars,budget,p -> {
                    Vec3 v=c.transform.apply(p);
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
            double x=evaluate(c,0,vars), y=evaluate(c,1,vars), z=evaluate(c,2,vars);
            if(s.dimensions==4) {
                double w=evaluate(c,3,vars);
                if(s.view.equals("slice")) {
                    vars.put("x",x);vars.put("y",y);vars.put("z",z);
                    if(Math.abs(w-sliceValue(c,vars))>s.step/2) continue;
                } else { x+=w*s.projection[0]; y+=w*s.projection[1]; z+=w*s.projection[2]; }
            }
            out.accept(new Vec3(x,y,z));
        }
    }

    private void implicit(Compiled c,Map<String,Double> vars,Consumer<Vec3> out) {
        Shape s=c.definition;
        boolean solid=s.kind.equals("solid"),system=s.kind.equals("system");
        Set<Vec3> emitted=solid?null:new HashSet<>();
        int nx=steps(s.bounds[0],s.bounds[1],s.step,128);
        int ny=steps(s.bounds[2],s.bounds[3],s.step,128);
        int nz=steps(s.bounds[4],s.bounds[5],s.step,128);
        int nw=s.dimensions==4 && s.view.equals("project")?Math.max(1,Math.min(4,s.numbers.getOrDefault("wslices",4.0).intValue())):1;
        if((long)(nx+1)*(ny+1)*(nz+1)*nw>100_000) throw new IllegalArgumentException("Equation grid exceeds 100000 points: "+s.id);
        double dx=(s.bounds[1]-s.bounds[0])/nx,dy=(s.bounds[3]-s.bounds[2])/ny,dz=(s.bounds[5]-s.bounds[4])/nz;
        boolean coordinateSlice=s.dimensions==4&&s.view.equals("slice")&&c.slice.variables().stream().anyMatch(Set.of("x","y","z")::contains);
        for(int wi=0;wi<nw;wi++) {
            double w=s.dimensions==4?(s.view.equals("slice")?(coordinateSlice?0:sliceValue(c,vars)):s.bounds[6]+(s.bounds[7]-s.bounds[6])*(wi+0.5)/nw):0;
            vars.put("w",w);
            for(int ix=0;ix<=nx;ix++) for(int iy=0;iy<=ny;iy++) for(int iz=0;iz<=nz;iz++) {
                double x=s.bounds[0]+ix*dx,y=s.bounds[2]+iy*dy,z=s.bounds[4]+iz*dz;
                vars.put("x",x);vars.put("y",y);vars.put("z",z);
                if(coordinateSlice) vars.put("w",sliceValue(c,vars));
                double f=evaluate(c,0,vars);
                if(solid) {
                    if(f<=0) out.accept(project(s,new Vec3(x,y,z),w));
                    continue;
                }
                if(ix==nx || iy==ny || iz==nz) continue;
                for(int axis=0;axis<3;axis++) {
                    double qx=x+(axis==0?dx:0),qy=y+(axis==1?dy:0),qz=z+(axis==2?dz:0);
                    vars.put("x",qx);vars.put("y",qy);vars.put("z",qz);
                    if(coordinateSlice) vars.put("w",sliceValue(c,vars));
                    double next=evaluate(c,0,vars);
                    if((f<0 && next>0)||(f>0 && next<0)||(f==0 && next!=0)||(next==0 && f!=0)) {
                        double a=Math.abs(f)/(Math.abs(f)+Math.abs(next)+1e-12);
                        double px=x+(axis==0?dx*a:0),py=y+(axis==1?dy*a:0),pz=z+(axis==2?dz*a:0);
                        vars.put("x",px);vars.put("y",py);vars.put("z",pz);
                        if(coordinateSlice) vars.put("w",sliceValue(c,vars));
                        boolean match=true;
                        if(system) for(int q=1;q<c.expressions.size();q++) if(Math.abs(evaluate(c,q,vars))>s.step) {match=false;break;}
                        if(match) {
                            Vec3 point=project(s,new Vec3(px,py,pz),w);
                            if(emitted.add(point)) out.accept(point);
                        }
                    }
                }
            }
        }
    }

    private static double evaluate(Compiled compiled,int index,Map<String,Double> vars) {
        try {return compiled.expressions.get(index).evaluate(vars);}
        catch(RuntimeException e){throw new IllegalArgumentException("Shape '"+compiled.definition.id+"' formula["+index+"]='"+compiled.definition.expressions.get(index)+"' failed at "+sampleVariables(vars)+": "+reason(e),e);}
    }

    private static double sliceValue(Compiled compiled,Map<String,Double> vars) {
        try {return compiled.slice.evaluate(vars);}
        catch(RuntimeException e){throw new IllegalArgumentException("Shape '"+compiled.definition.id+"' slice formula='"+compiled.definition.slice+"' failed at "+sampleVariables(vars)+": "+reason(e),e);}
    }

    private static String sampleVariables(Map<String,Double> vars) {
        return "x="+vars.get("x")+", y="+vars.get("y")+", z="+vars.get("z")+", w="+vars.get("w")+", u="+vars.get("u")+", v="+vars.get("v")+", t="+vars.get("t");
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
        if(s.fill==null||!List.of("outline","surface","solid").contains(s.fill)) throw new IllegalArgumentException("Shape '"+s.id+"' fill='"+s.fill+"' must be outline, surface or solid");
        if(!Double.isFinite(s.step)||s.step<0.02||s.step>8) throw new IllegalArgumentException("Shape '"+s.id+"' step="+s.step+" must be 0.02..8");
        if(s.dimensions<3||s.dimensions>4) throw new IllegalArgumentException("Shape '"+s.id+"' dimensions="+s.dimensions+" must be 3 or 4");
        if(s.dimensions==4&&!List.of("curve","surface","equation","solid","system").contains(s.kind)) throw new IllegalArgumentException("4D requires a formula shape: "+s.id);
        if(s.view==null||!List.of("slice","project").contains(s.view)) throw new IllegalArgumentException("Invalid 4D view: "+s.id);
        if(s.dimensions==3&&s.view.equals("project")) throw new IllegalArgumentException("Projection view requires a 4D shape: "+s.id);
        if(s.translate==null||s.translate.length!=3) throw new IllegalArgumentException("Shape '"+s.id+"' translate requires x, y, z values");
        if(s.rotate==null||s.rotate.length!=3) throw new IllegalArgumentException("Shape '"+s.id+"' rotate requires x, y, z values");
        if(s.scale==null||s.scale.length!=3) throw new IllegalArgumentException("Shape '"+s.id+"' scale requires x, y, z values");
        if(s.projection==null||s.projection.length!=3) throw new IllegalArgumentException("Shape '"+s.id+"' projection requires x, y, z values");
        String[] axes={"x","y","z"};
        for(int i=0;i<3;i++) {
            if(!CoordinateBounds.valid(s.translate[i])) throw new IllegalArgumentException("Shape '"+s.id+"' translate."+axes[i]+"="+s.translate[i]+" must be finite and within +/-30000000");
            if(!Double.isFinite(s.rotate[i])||Math.abs(s.rotate[i])>ShapeUtils.MAX_ROTATION_DEGREES) throw new IllegalArgumentException("Shape '"+s.id+"' rotate."+axes[i]+"="+s.rotate[i]+" must be finite and within +/-"+ShapeUtils.MAX_ROTATION_DEGREES);
            if(!Double.isFinite(s.scale[i])||s.scale[i]==0||Math.abs(s.scale[i])>100) throw new IllegalArgumentException("Shape '"+s.id+"' scale."+axes[i]+"="+s.scale[i]+" must be nonzero, finite and within +/-100");
            if(!Double.isFinite(s.projection[i])||Math.abs(s.projection[i])>100) throw new IllegalArgumentException("Shape '"+s.id+"' projection."+axes[i]+"="+s.projection[i]+" must be finite and within +/-100");
        }
        if(s.bounds==null||s.bounds.length!=8) throw new IllegalArgumentException("Invalid bounds: "+s.id);
        if(s.expressions==null||s.points==null||s.children==null||s.numbers==null) throw new IllegalArgumentException("Missing shape fields: "+s.id);
        if(s.points.size()>2048) throw new IllegalArgumentException("Shape exceeds 2048 control or point coordinates: "+s.id);
        for(int i=0;i<s.points.size();i++) {
            List<Double> point=s.points.get(i);
            if(point==null||point.size()!=3) throw new IllegalArgumentException("Shape '"+s.id+"' point["+i+"] requires x, y, z values");
            for(int axis=0;axis<3;axis++) {
                Double value=point.get(axis);
                if(value==null||!Double.isFinite(value)||Math.abs(value)>64) throw new IllegalArgumentException("Shape '"+s.id+"' point["+i+"]."+axes[axis]+"="+value+" must be finite and within +/-64");
            }
        }
        if(s.kind.equals("bezier")&&s.points.size()>64) throw new IllegalArgumentException("Bezier supports at most 64 control points: "+s.id);
        for(var entry:s.numbers.entrySet()) {
            if(entry.getKey()==null||!entry.getKey().matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("Shape '"+s.id+"' parameter name '"+entry.getKey()+"' is invalid");
            if(entry.getValue()==null||!Double.isFinite(entry.getValue())||Math.abs(entry.getValue())>10000) throw new IllegalArgumentException("Shape '"+s.id+"' parameter '"+entry.getKey()+"'="+entry.getValue()+" must be finite and within +/-10000");
        }
        String[] boundAxes={"x","y","z","w"};
        for(int i=0;i<8;i+=2) if(!Double.isFinite(s.bounds[i])||!Double.isFinite(s.bounds[i+1])||s.bounds[i]>=s.bounds[i+1]||s.bounds[i+1]-s.bounds[i]>64) throw new IllegalArgumentException("Shape '"+s.id+"' bounds."+boundAxes[i/2]+"=["+s.bounds[i]+", "+s.bounds[i+1]+"] requires finite min < max with span <=64");
        Double slices=s.numbers.get("wslices");
        if(slices!=null&&(slices<1||slices>4||slices!=Math.rint(slices))) throw new IllegalArgumentException("wslices must be an integer from 1 to 4: "+s.id);
        for(String positive:List.of("radius","rx","ry","rz","width","height","depth","tube","length")) {
            Double value=s.numbers.get(positive);
            if(value!=null&&value<=0) throw new IllegalArgumentException(positive+" must be positive: "+s.id);
        }
        int expected=s.dimensions;
        switch(s.kind) {
            case "curve","surface" -> {
                if(s.expressions.size()!=expected) throw new IllegalArgumentException(s.kind+" needs "+expected+" coordinate expressions: "+s.id);
                steps(s.numbers.getOrDefault("umin",0.0),s.numbers.getOrDefault("umax",Math.PI*2),s.step,512);
                if(s.kind.equals("surface")) steps(s.numbers.getOrDefault("vmin",0.0),s.numbers.getOrDefault("vmax",Math.PI),s.step,256);
            }
            case "equation","solid" -> { if(s.expressions.size()!=1) throw new IllegalArgumentException(s.kind+" needs one expression: "+s.id); }
            case "system" -> { if(s.expressions.size()<2||s.expressions.size()>4) throw new IllegalArgumentException("System needs 2..4 equations: "+s.id); }
            case "points" -> { }
            case "group" -> { }
            default -> { if(!ShapeUtils.BASIC.contains(s.kind)) throw new IllegalArgumentException("Unknown shape kind: "+s.kind); }
        }
    }

    private static String reason(RuntimeException error) {return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}

    private static final class Budget {
        final int max; int count;
        Budget(int max) {this.max=max;}
        void emit(Vec3 p,Consumer<Vec3> out) {
            if(++count>max) throw new IllegalArgumentException("Shape exceeds "+max+" points per frame; increase step or reduce bounds");
            out.accept(p);
        }
    }
}
