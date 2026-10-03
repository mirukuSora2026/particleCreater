package com.mirukusora26.particleCreater.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import java.nio.file.Path;
import java.util.UUID;
import java.sql.SQLException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectRepositoryTest {
    @TempDir Path folder;
    @Test void definitionsSurviveReopenAndJsonRoundTrip() throws Exception {
        Logger logger=Logger.getAnonymousLogger();
        try(EffectRepository repository=new EffectRepository(folder,logger)) {
            EffectDefinition effect=new EffectDefinition("ring");
            effect.parameters.put("radius",2.0);
            effect.mix="weighted";
            effect.layers.add(new EffectDefinition.Layer("red","flame","origin"));
            effect.layers.add(new EffectDefinition.Layer("blue","soul_fire_flame","origin"));
            repository.save(effect);
            assertTrue(repository.exportJson(effect).toFile().isFile());
        }
        try(EffectRepository repository=new EffectRepository(folder,logger)) {
            assertEquals(2.0,repository.find("ring").orElseThrow().parameters.get("radius"));
            assertEquals("weighted",repository.find("ring").orElseThrow().mix);
            assertEquals(2,repository.find("ring").orElseThrow().layers.size());
            assertEquals("ring",repository.importJson("ring.json").name);
            assertEquals(2,repository.importJson("ring.json").layers.size());
            assertEquals(1,repository.loadAll().size());
        }
    }

    @Test void savedLocationsSurviveReopenAndCanBeUpdatedOrDeleted() throws Exception {
        Logger logger=Logger.getAnonymousLogger();
        UUID world=UUID.randomUUID();
        try(EffectRepository repository=new EffectRepository(folder,logger)) {
            repository.saveLocation(new SavedLocation("spawn_gate",world,"world",100.25,70,-30.5));
            repository.saveLocation(new SavedLocation("upper_gate",world,"world",0,80,0));
            assertEquals(java.util.List.of("spawn_gate","upper_gate"),repository.locationNames());
            assertEquals(2,repository.locationCount());
            assertEquals(java.util.List.of("upper_gate"),repository.locationPage(1,20));
            assertEquals(java.util.List.of("spawn_gate"),repository.suggestLocationNames("spa",40));
            assertEquals(java.util.List.of("upper_gate"),repository.suggestLocationNames("upper_",40));
        }
        try(EffectRepository repository=new EffectRepository(folder,logger)) {
            SavedLocation saved=repository.findLocation("spawn_gate").orElseThrow();
            assertEquals(world,saved.worldId());
            assertEquals(100.25,saved.x());
            assertEquals(-30.5,saved.z());
            repository.saveLocation(new SavedLocation("spawn_gate",world,"world",1,2,3));
            assertEquals(2,repository.findLocation("spawn_gate").orElseThrow().y());
            assertTrue(repository.deleteLocation("upper_gate"));
            assertFalse(repository.deleteLocation("upper_gate"));
            assertEquals(java.util.List.of("spawn_gate"),repository.locationNames());
        }
    }

    @Test void savedLocationsRejectInvalidData() {
        UUID world=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,() -> new SavedLocation("Upper Gate",world,"world",0,0,0));
        assertThrows(IllegalArgumentException.class,() -> new SavedLocation("gate",world,"world",Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,() -> new SavedLocation("gate",world,"world",30000001,0,0));
        assertThrows(IllegalArgumentException.class,() -> new SavedLocation("gate",null,"world",0,0,0));
    }

    @Test void renameIsAtomicWhenDestinationExists() throws Exception {
        try(EffectRepository repository=new EffectRepository(folder,Logger.getAnonymousLogger())) {
            repository.save(new EffectDefinition("old"));
            repository.save(new EffectDefinition("taken"));
            assertThrows(SQLException.class,() -> repository.rename("old",new EffectDefinition("taken")));
            assertTrue(repository.find("old").isPresent());
            assertTrue(repository.find("taken").isPresent());
            repository.rename("old",new EffectDefinition("renamed"));
            assertTrue(repository.find("old").isEmpty());
            assertEquals("renamed",repository.find("renamed").orElseThrow().name);
        }
    }
    @Test void jsonExportRejectsUnsafeNames() throws Exception {
        try(EffectRepository repository=new EffectRepository(folder,Logger.getAnonymousLogger())) {
            assertThrows(IllegalArgumentException.class,() -> repository.exportJson(new EffectDefinition("../outside")));
        }
    }
}
