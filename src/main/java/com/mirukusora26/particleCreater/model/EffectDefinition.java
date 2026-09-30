package com.mirukusora26.particleCreater.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A saved effect. Callers should submit changes through EffectService, which validates and copies it. */
public final class EffectDefinition {
    public int schemaVersion = 1;
    public String name;
    public int durationTicks = 100; // -1 means unlimited
    public int intervalTicks = 1;
    public String anchor = "fixed"; // fixed, caller, target
    public String mix = "together"; // together, weighted
    public Map<String, Double> parameters = new LinkedHashMap<>();
    public List<Shape> shapes = new ArrayList<>();
    public List<Layer> layers = new ArrayList<>();
    public Motion motion = new Motion();

    public EffectDefinition() {}

    public EffectDefinition(String name) {
        this.name = name;
        Shape origin = new Shape("origin", "point");
        shapes.add(origin);
    }

    public static final class Shape {
        public String id;
        public String kind;
        public String fill = "surface"; // outline, surface, solid
        public Map<String, Double> numbers = new LinkedHashMap<>();
        public List<String> expressions = new ArrayList<>();
        public List<List<Double>> points = new ArrayList<>();
        public List<String> children = new ArrayList<>();
        public double[] translate = {0, 0, 0};
        public double[] rotate = {0, 0, 0}; // degrees
        public double[] scale = {1, 1, 1};
        public double[] bounds = {-2, 2, -2, 2, -2, 2, -2, 2};
        public double step = 0.25;
        public int dimensions = 3;
        public String view = "slice"; // for 4D: slice or project
        public String slice = "0";
        public double[] projection = {0.5, 0, 0};

        public Shape() {}
        public Shape(String id, String kind) {
            this.id = id; this.kind = kind;
            if (List.of("circle", "ellipse", "rectangle", "polygon", "star", "heart").contains(kind)) this.fill = "outline";
        }
    }

    public static final class Layer {
        public String id;
        public String particle;
        public String shape = "origin";
        public int count = 1;
        public double[] offset = {0, 0, 0};
        public double extra = 0;
        public int startTicks = 0;
        public int intervalTicks = 1;
        public int durationTicks = -1;
        public double weight = 1;
        public Map<String, String> data = new LinkedHashMap<>();

        public Layer() {}
        public Layer(String id, String particle, String shape) {
            this.id = id; this.particle = particle; this.shape = shape;
        }
    }

    public static final class Motion {
        public List<String> move = new ArrayList<>(List.of("0", "0", "0"));
        public List<String> rotate = new ArrayList<>(List.of("0", "0", "0"));
        public List<String> scale = new ArrayList<>(List.of("1", "1", "1"));
    }
}
