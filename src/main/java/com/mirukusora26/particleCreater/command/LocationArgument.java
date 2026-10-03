package com.mirukusora26.particleCreater.command;

import com.mirukusora26.particleCreater.model.CoordinateBounds;
import java.util.List;
import java.util.function.Predicate;

/** Parses the location following a playback command's at option. */
final class LocationArgument {
    record Source(String world, double x, double y, double z, boolean console) {}
    record Parsed(String world, double x, double y, double z, int nextIndex) {}

    private LocationArgument() {}

    static Parsed parse(List<String> args, int start, Source source, Predicate<String> worldExists) {
        if (start >= args.size()) throw new IllegalArgumentException("Usage: at [world] <x> <y> <z>.");
        String first = args.get(start);
        boolean prefixedWorld=first.startsWith("world:");
        String selectedWorld=prefixedWorld?first.substring("world:".length()):first;
        boolean namedWorld = prefixedWorld||worldExists.test(first) || !looksLikeCoordinate(first);
        if (source.console() && !namedWorld) {
            throw new IllegalArgumentException("Console commands require at <world> <x> <y> <z>.");
        }
        String world = namedWorld ? selectedWorld : source.world();
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("Unable to determine a world. Use at <world> <x> <y> <z>.");
        }
        if (!worldExists.test(world)) throw new IllegalArgumentException("World not found: " + world);

        int coordinates = start + (namedWorld ? 1 : 0);
        if (args.size() < coordinates + 3) {
            throw new IllegalArgumentException("at requires x y z coordinates.");
        }
        double baseX = source.console() ? Double.NaN : source.x();
        double baseY = source.console() ? Double.NaN : source.y();
        double baseZ = source.console() ? Double.NaN : source.z();
        return new Parsed(world,
            coordinate(args.get(coordinates), baseX,"x"),
            coordinate(args.get(coordinates + 1), baseY,"y"),
            coordinate(args.get(coordinates + 2), baseZ,"z"),
            coordinates + 3);
    }

    static boolean looksLikeCoordinate(String value) {
        if (value.startsWith("~")) return true;
        try { Double.parseDouble(value); return true; }
        catch (NumberFormatException e) { return false; }
    }

    static boolean hasWorldCoordinates(List<String> args,int start,Predicate<String> worldExists) {
        if(start+3>=args.size()) return false;
        String first=args.get(start),world=first.startsWith("world:")?first.substring("world:".length()):first;
        return worldExists.test(world)&&
            looksLikeCoordinate(args.get(start+1))&&looksLikeCoordinate(args.get(start+2))&&looksLikeCoordinate(args.get(start+3));
    }

    private static double coordinate(String value, double base,String axis) {
        boolean relative = value.startsWith("~");
        if (relative && !Double.isFinite(base)) {
            throw new IllegalArgumentException("Relative "+axis+" coordinate '"+value+"' requires a player or command block source");
        }
        String number = relative ? value.substring(1) : value;
        try {
            double offset = number.isEmpty() && relative ? 0 : Double.parseDouble(number);
            double result = relative ? base + offset : offset;
            if (!CoordinateBounds.valid(result)) throw new IllegalArgumentException("Coordinate "+axis+"='"+value+"' resolves to "+result+"; expected a finite value within +/-30000000 blocks");
            return result;
        } catch (NumberFormatException e) {throw new IllegalArgumentException("Coordinate "+axis+"='"+value+"' is not a number",e);}
    }
}
