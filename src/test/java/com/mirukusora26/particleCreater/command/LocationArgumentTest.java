package com.mirukusora26.particleCreater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocationArgumentTest {
    private static final Set<String> WORLDS = Set.of("world", "nether", "123", "saved", "~weird");
    private static final LocationArgument.Source PLAYER = new LocationArgument.Source("world", 12.5, 64, -8.25, false);

    @Test void infersWorldForAbsoluteAndRelativeCoordinates() {
        var absolute = LocationArgument.parse(List.of("play", "ring", "100", "70", "-30", "for", "@a"), 2, PLAYER, WORLDS::contains);
        assertEquals("world", absolute.world());
        assertEquals(100, absolute.x());
        assertEquals(70, absolute.y());
        assertEquals(-30, absolute.z());
        assertEquals(5, absolute.nextIndex());

        var relative = LocationArgument.parse(List.of("~", "~2", "~-1"), 0, PLAYER, WORLDS::contains);
        assertEquals(12.5, relative.x());
        assertEquals(66, relative.y());
        assertEquals(-9.25, relative.z());
    }

    @Test void supportsExplicitWorldIncludingNumericWorldNames() {
        var target = LocationArgument.parse(List.of("123", "1", "2", "3"), 0, PLAYER, WORLDS::contains);
        assertEquals("123", target.world());
        assertEquals(1, target.x());
        assertEquals(4, target.nextIndex());
        assertTrue(LocationArgument.hasWorldCoordinates(List.of("123","1","2","3"),0,WORLDS::contains));
        assertFalse(LocationArgument.hasWorldCoordinates(List.of("world","spawn_gate"),0,WORLDS::contains));
        var reserved=LocationArgument.parse(List.of("world:saved","1","2","3"),0,PLAYER,WORLDS::contains);
        assertEquals("saved",reserved.world());
        assertTrue(LocationArgument.hasWorldCoordinates(List.of("world:~weird","1","2","3"),0,WORLDS::contains));
        assertEquals("~weird",LocationArgument.parse(List.of("world:~weird","1","2","3"),0,PLAYER,WORLDS::contains).world());
    }

    @Test void consoleRequiresWorldAndAbsoluteCoordinates() {
        var console = new LocationArgument.Source(null, Double.NaN, Double.NaN, Double.NaN, true);
        assertThrows(IllegalArgumentException.class,
            () -> LocationArgument.parse(List.of("1", "2", "3"), 0, console, WORLDS::contains));
        assertThrows(IllegalArgumentException.class,
            () -> LocationArgument.parse(List.of("world", "~", "2", "3"), 0, console, WORLDS::contains));
        var target = LocationArgument.parse(List.of("nether", "1", "2", "3"), 0, console, WORLDS::contains);
        assertEquals("nether", target.world());
        assertEquals(3, target.z());
    }

    @Test void rejectsUnknownWorldMissingCoordinatesAndNonFiniteNumbers() {
        assertThrows(IllegalArgumentException.class,
            () -> LocationArgument.parse(List.of("missing", "1", "2", "3"), 0, PLAYER, WORLDS::contains));
        assertThrows(IllegalArgumentException.class,
            () -> LocationArgument.parse(List.of("world", "1", "2"), 0, PLAYER, WORLDS::contains));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> LocationArgument.parse(List.of("NaN", "2", "3"), 0, PLAYER, WORLDS::contains)).getMessage().contains("Coordinate x='NaN'"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> LocationArgument.parse(List.of("1", "2", "30000001"), 0, PLAYER, WORLDS::contains)).getMessage().contains("Coordinate z='30000001'"));
    }
}
