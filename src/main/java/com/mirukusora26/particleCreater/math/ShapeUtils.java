package com.mirukusora26.particleCreater.math;

import com.mirukusora26.particleCreater.model.EffectDefinition.Shape;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Built-in shape samplers. Coordinates are local to the effect origin. */
public final class ShapeUtils {
    private ShapeUtils() {}

    public static final List<String> BASIC = List.of("point", "line", "polyline", "circle", "arc", "ellipse", "rectangle", "polygon", "star", "spiral", "heart", "bezier", "spline", "sphere", "hemisphere", "ellipsoid", "box", "cylinder", "cone", "pyramid", "torus", "capsule", "helix", "orbit", "wave", "parabola");

    public static void sampleBasic(Shape shape, Consumer<Vec3> out) {
        String kind = shape.kind.toLowerCase();
        Map<String, Double> p = shape.numbers;
        double step = shape.step;
        double radius = value(p, "radius", 1);
        double height = value(p, "height", 2);
        if (!shape.fill.equals("outline") && List.of("circle","ellipse","rectangle","box","pyramid","sphere","hemisphere","ellipsoid","cylinder","cone","torus","capsule").contains(kind)) {
            sampleArea(shape,out);
            return;
        }
        if (!shape.fill.equals("outline") && List.of("polygon","star","heart").contains(kind)) {
            sampleFlat(shape,out);
            return;
        }
        int ring = Math.max(8, Math.min(256, (int) Math.ceil(2 * Math.PI * Math.max(0.1, radius) / step)));
        switch (kind) {
            case "point" -> out.accept(new Vec3(0, 0, 0));
            case "line" -> {
                Vec3 a = new Vec3(value(p,"x1",0), value(p,"y1",0), value(p,"z1",0));
                Vec3 b = new Vec3(value(p,"x2",0), value(p,"y2",height), value(p,"z2",0));
                line(a, b, step, out);
            }
            case "polyline" -> {
                for (int i = 1; i < shape.points.size(); i++) line(point(shape.points.get(i - 1)), point(shape.points.get(i)), step, out);
            }
            case "circle", "orbit", "arc", "ellipse", "polygon", "star", "spiral", "heart", "wave", "parabola", "helix" -> {
                int n = Math.max(8, Math.min(512, ring * (kind.equals("helix") ? (int) Math.max(1, value(p,"turns",2)) : 1)));
                double rx = value(p, "rx", radius), rz = value(p, "rz", radius);
                double arc = kind.equals("arc") ? Math.toRadians(value(p, "degrees", 180)) : Math.PI * 2;
                int sides = Math.max(3, (int) value(p, "sides", 6));
                for (int i = 0; i <= n; i++) {
                    double f = (double) i / n, angle = f * arc;
                    Vec3 v = switch (kind) {
                        case "wave" -> new Vec3((f - 0.5) * value(p,"length",4), Math.sin(f * Math.PI * 2 * value(p,"cycles",2)) * height / 2, 0);
                        case "parabola" -> new Vec3((f - 0.5) * value(p,"length",4), height * (1 - Math.pow(2 * f - 1, 2)), 0);
                        case "heart" -> new Vec3(radius * 16 * Math.pow(Math.sin(angle), 3) / 17, radius * (13 * Math.cos(angle) - 5 * Math.cos(2*angle) - 2 * Math.cos(3*angle) - Math.cos(4*angle)) / 17, 0);
                        case "spiral" -> new Vec3(rx * f * Math.cos(angle * value(p,"turns",3)), 0, rz * f * Math.sin(angle * value(p,"turns",3)));
                        case "helix" -> new Vec3(rx * Math.cos(f * Math.PI * 2 * value(p,"turns",2)), f * height, rz * Math.sin(f * Math.PI * 2 * value(p,"turns",2)));
                        case "polygon" -> { double a = Math.floor(f * sides) * Math.PI * 2 / sides, b = (Math.floor(f * sides)+1) * Math.PI * 2 / sides, q = (f * sides) % 1; yield new Vec3(rx * lerp(Math.cos(a),Math.cos(b),q), 0, rz * lerp(Math.sin(a),Math.sin(b),q)); }
                        case "star" -> { int edges = sides * 2; double a = Math.floor(f * edges) * Math.PI * 2 / edges, b = (Math.floor(f * edges)+1) * Math.PI * 2 / edges, q = (f * edges)%1, r1 = ((int)Math.floor(f*edges)%2==0 ? 1 : value(p,"inner",0.5)), r2 = ((int)(Math.floor(f*edges)+1)%2==0 ? 1 : value(p,"inner",0.5)); yield new Vec3(rx*lerp(r1*Math.cos(a),r2*Math.cos(b),q),0,rz*lerp(r1*Math.sin(a),r2*Math.sin(b),q)); }
                        default -> new Vec3(rx * Math.cos(angle), 0, rz * Math.sin(angle));
                    };
                    out.accept(v);
                }
            }
            case "rectangle", "box", "pyramid" -> {
                double x = value(p,"width",2)/2, y = kind.equals("rectangle") ? 0 : value(p,"height",2)/2, z = value(p,"depth",2)/2;
                Vec3[] bottom = {new Vec3(-x,-y,-z),new Vec3(x,-y,-z),new Vec3(x,-y,z),new Vec3(-x,-y,z)};
                for (int i=0;i<4;i++) line(bottom[i],bottom[(i+1)%4],step,out);
                if (!kind.equals("rectangle")) {
                    Vec3[] top = {new Vec3(-x,y,-z),new Vec3(x,y,-z),new Vec3(x,y,z),new Vec3(-x,y,z)};
                    for (int i=0;i<4;i++) {
                        if (kind.equals("box")) line(top[i],top[(i+1)%4],step,out);
                        line(bottom[i], kind.equals("pyramid") ? new Vec3(0,y,0) : top[i], step, out);
                    }
                }
            }
            case "bezier" -> {
                if (shape.points.size() < 2) throw new IllegalArgumentException("Bezier needs at least two points");
                int n = Math.max(8, Math.min(512, (int)Math.ceil(shape.points.size()*2/step)));
                for (int i=0;i<=n;i++) {
                    double t=(double)i/n; Vec3 v=new Vec3(0,0,0);
                    for(int j=0;j<shape.points.size();j++) {
                        double c=binomial(shape.points.size()-1,j)*Math.pow(1-t,shape.points.size()-1-j)*Math.pow(t,j);
                        v=v.add(point(shape.points.get(j)).scale(c));
                    }
                    out.accept(v);
                }
            }
            case "spline" -> {
                if(shape.points.size()<4) throw new IllegalArgumentException("Spline needs at least four points");
                int n=Math.max(8,Math.min(128,(int)Math.ceil(2/step)));
                for(int s=0;s<shape.points.size()-3;s++) for(int i=0;i<=n;i++) {
                    double t=(double)i/n, t2=t*t,t3=t2*t;
                    Vec3 a=point(shape.points.get(s)),b=point(shape.points.get(s+1)),c=point(shape.points.get(s+2)),d=point(shape.points.get(s+3));
                    out.accept(new Vec3(catmull(a.x(),b.x(),c.x(),d.x(),t,t2,t3),catmull(a.y(),b.y(),c.y(),d.y(),t,t2,t3),catmull(a.z(),b.z(),c.z(),d.z(),t,t2,t3)));
                }
            }
            case "sphere", "hemisphere", "ellipsoid", "torus", "cylinder", "cone", "capsule" -> {
                int rows=Math.max(4,Math.min(128,(int)Math.ceil(Math.max(height,2*radius)/step)));
                for(int j=0;j<=rows;j++) {
                    double v=(double)j/rows;
                    for(int i=0;i<ring;i++) {
                        double a=Math.PI*2*i/ring;
                        Vec3 point;
                        switch(kind) {
                            case "sphere", "hemisphere", "ellipsoid" -> {
                                double phi=(kind.equals("hemisphere") ? Math.PI/2 : Math.PI)*v;
                                double sx=value(p,"rx",radius), sy=value(p,"ry",radius), sz=value(p,"rz",radius);
                                point=new Vec3(sx*Math.sin(phi)*Math.cos(a),sy*Math.cos(phi),sz*Math.sin(phi)*Math.sin(a));
                            }
                            case "torus" -> { double minor=value(p,"tube",0.3), b=2*Math.PI*v, r=radius+minor*Math.cos(b); point=new Vec3(r*Math.cos(a),minor*Math.sin(b),r*Math.sin(a)); }
                            case "cylinder" -> point=new Vec3(radius*Math.cos(a),(v-0.5)*height,radius*Math.sin(a));
                            case "cone" -> point=new Vec3(radius*(1-v)*Math.cos(a),v*height,radius*(1-v)*Math.sin(a));
                            default -> { double phi=Math.PI*v, cy=height/2; point=new Vec3(radius*Math.sin(phi)*Math.cos(a),radius*Math.cos(phi)+(v<0.5?cy:-cy),radius*Math.sin(phi)*Math.sin(a)); }
                        }
                        out.accept(point);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown basic shape: " + kind);
        }
    }

    public static Vec3 transform(Vec3 value, Shape shape) {
        double[] s=shape.scale, r=shape.rotate, tr=shape.translate;
        double x=value.x()*s[0], y=value.y()*s[1], z=value.z()*s[2];
        double ax=Math.toRadians(r[0]), ay=Math.toRadians(r[1]), az=Math.toRadians(r[2]);
        double yy=y*Math.cos(ax)-z*Math.sin(ax), zz=y*Math.sin(ax)+z*Math.cos(ax); y=yy; z=zz;
        double xx=x*Math.cos(ay)+z*Math.sin(ay); zz=-x*Math.sin(ay)+z*Math.cos(ay); x=xx; z=zz;
        xx=x*Math.cos(az)-y*Math.sin(az); yy=x*Math.sin(az)+y*Math.cos(az);
        return new Vec3(xx+tr[0],yy+tr[1],z+tr[2]);
    }

    private static void sampleArea(Shape shape,Consumer<Vec3> out) {
        String kind=shape.kind;Map<String,Double> p=shape.numbers;double step=shape.step;
        double r=value(p,"radius",1),h=value(p,"height",2),tube=value(p,"tube",0.3);
        double rx=value(p,"rx",r),ry=value(p,"ry",r),rz=value(p,"rz",r);
        double width=value(p,"width",2),depth=value(p,"depth",2);
        double xmax=switch(kind){case "circle","ellipse" -> rx;case "rectangle","box","pyramid" -> width/2;case "torus" -> r+tube;default -> Math.max(r,rx);};
        double ymax=switch(kind){case "circle","ellipse","rectangle" -> 0;case "sphere","hemisphere","ellipsoid" -> ry;case "capsule" -> h/2+r;case "torus" -> tube;case "cone" -> h;default -> h/2;};
        double zmax=switch(kind){case "circle","ellipse" -> rz;case "rectangle","box","pyramid" -> depth/2;case "torus" -> r+tube;default -> Math.max(r,rz);};
        double ymin=kind.equals("hemisphere")||kind.equals("cone")||kind.equals("rectangle")||kind.equals("circle")||kind.equals("ellipse")?0:-ymax;
        int nx=Math.max(1,(int)Math.ceil(2*xmax/step)),ny=ymax==0?0:Math.max(1,(int)Math.ceil((ymax-ymin)/step)),nz=Math.max(1,(int)Math.ceil(2*zmax/step));
        for(int i=0;i<=nx;i++)for(int j=0;j<=ny;j++)for(int k=0;k<=nz;k++) {
            double x=-xmax+2*xmax*i/nx,y=ny==0?0:ymin+(ymax-ymin)*j/ny,z=-zmax+2*zmax*k/nz;
            double clearance;
            switch(kind) {
                case "circle","ellipse" -> {double q=Math.sqrt(x*x/(rx*rx)+z*z/(rz*rz));clearance=(1-q)*Math.min(rx,rz);}
                case "rectangle" -> clearance=Math.min(xmax-Math.abs(x),zmax-Math.abs(z));
                case "box" -> clearance=Math.min(Math.min(xmax-Math.abs(x),ymax-Math.abs(y)),zmax-Math.abs(z));
                case "pyramid" -> {double factor=Math.max(0,1-(y+ymax)/(2*ymax));clearance=Math.min(Math.min(xmax*factor-Math.abs(x),zmax*factor-Math.abs(z)),Math.min(y+ymax,ymax-y));}
                case "sphere","hemisphere","ellipsoid" -> {double q=Math.sqrt(x*x/(rx*rx)+y*y/(ry*ry)+z*z/(rz*rz));clearance=(1-q)*Math.min(rx,Math.min(ry,rz));}
                case "cylinder" -> clearance=Math.min(r-Math.hypot(x,z),h/2-Math.abs(y));
                case "cone" -> clearance=Math.min(r*(1-y/h)-Math.hypot(x,z),Math.min(y,h-y));
                case "torus" -> clearance=tube-Math.hypot(Math.hypot(x,z)-r,y);
                default -> clearance=r-Math.hypot(Math.hypot(x,z),y-Math.max(-h/2,Math.min(h/2,y)));
            }
            if(clearance>=-step*0.25 && (shape.fill.equals("solid")||ymax==0||clearance<=step*0.8)) out.accept(new Vec3(x,y,z));
        }
    }

    private static void sampleFlat(Shape shape,Consumer<Vec3> out) {
        Map<String,Double> p=shape.numbers;double radius=value(p,"radius",1),step=shape.step;
        int n=Math.max(1,(int)Math.ceil(2*radius/step));
        int sides=Math.max(3,Math.min(64,(int)value(p,"sides",6)));
        int vertices=shape.kind.equals("star")?sides*2:sides;
        double[] px=new double[vertices],pz=new double[vertices];
        for(int i=0;i<vertices;i++) {
            double angle=2*Math.PI*i/vertices, scale=shape.kind.equals("star")&&i%2==1?value(p,"inner",0.5):1;
            px[i]=radius*scale*Math.cos(angle);pz[i]=radius*scale*Math.sin(angle);
        }
        for(int ix=0;ix<=n;ix++)for(int iz=0;iz<=n;iz++) {
            double x=-radius+2*radius*ix/n,z=-radius+2*radius*iz/n;
            boolean inside;
            if(shape.kind.equals("heart")) {
                double nx=x/radius,nz=z/radius;
                inside=Math.pow(nx*nx+nz*nz-1,3)-nx*nx*nz*nz*nz<=0;
            } else {
                inside=false;
                for(int i=0,j=vertices-1;i<vertices;j=i++) if((pz[i]>z)!=(pz[j]>z)&&x<(px[j]-px[i])*(z-pz[i])/(pz[j]-pz[i])+px[i]) inside=!inside;
            }
            if(inside)out.accept(shape.kind.equals("heart")?new Vec3(x,z,0):new Vec3(x,0,z));
        }
    }

    public static void line(Vec3 a, Vec3 b, double step, Consumer<Vec3> out) {
        double length=Math.sqrt(Math.pow(b.x()-a.x(),2)+Math.pow(b.y()-a.y(),2)+Math.pow(b.z()-a.z(),2));
        int n=Math.max(1,Math.min(2048,(int)Math.ceil(length/step)));
        for(int i=0;i<=n;i++) { double t=(double)i/n; out.accept(new Vec3(lerp(a.x(),b.x(),t),lerp(a.y(),b.y(),t),lerp(a.z(),b.z(),t))); }
    }

    private static double value(Map<String,Double> p,String k,double defaultValue) { return p.getOrDefault(k,defaultValue); }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static Vec3 point(List<Double> p) { if(p.size()!=3) throw new IllegalArgumentException("Point needs x,y,z"); return new Vec3(p.get(0),p.get(1),p.get(2)); }
    private static double binomial(int n,int k) { double r=1; for(int i=1;i<=k;i++) r=r*(n-i+1)/i; return r; }
    private static double catmull(double a,double b,double c,double d,double t,double t2,double t3) { return 0.5*(2*b+(-a+c)*t+(2*a-5*b+4*c-d)*t2+(-a+3*b-3*c+d)*t3); }
}
