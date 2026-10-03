package com.mirukusora26.particleCreater.model;

/** Safety cap for particle positions and command coordinates. */
public final class CoordinateBounds {
    public static final double MAX_ABS = 30_000_000;

    private CoordinateBounds() {}

    public static boolean valid(double value) {return Double.isFinite(value)&&Math.abs(value)<=MAX_ABS;}

    public static void require(double x,double y,double z) {
        if(!valid(x)) throw new IllegalArgumentException("Coordinate x="+x+" must be finite and within +/-30000000 blocks");
        if(!valid(y)) throw new IllegalArgumentException("Coordinate y="+y+" must be finite and within +/-30000000 blocks");
        if(!valid(z)) throw new IllegalArgumentException("Coordinate z="+z+" must be finite and within +/-30000000 blocks");
    }
}
