package com.mirukusora26.particleCreater.math;

public record Vec3(double x, double y, double z) {
    public Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
    public Vec3 scale(double factor) { return new Vec3(x * factor, y * factor, z * factor); }
    public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
}
