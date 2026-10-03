package com.mirukusora26.particleCreater.storage;

import com.mirukusora26.particleCreater.model.CoordinateBounds;
import java.util.UUID;

/** A named, server-wide fixed position. World UUID prevents accidental reuse of a world name. */
public record SavedLocation(String name, UUID worldId, String worldName, double x, double y, double z) {
    public SavedLocation {
        if (name == null || !name.matches("[a-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Location name must use lowercase a-z, 0-9, _ or - (1..48).");
        if (worldId == null || worldName == null || worldName.isBlank())
            throw new IllegalArgumentException("A world is required for a saved location.");
        CoordinateBounds.require(x,y,z);
    }
}
