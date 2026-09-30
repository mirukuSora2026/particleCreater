package com.mirukusora26.particleCreater.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.mirukusora26.particleCreater.model.EffectDefinition;
import java.nio.file.Path;
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
            repository.save(effect);
            assertTrue(repository.exportJson(effect).toFile().isFile());
        }
        try(EffectRepository repository=new EffectRepository(folder,logger)) {
            assertEquals(2.0,repository.find("ring").orElseThrow().parameters.get("radius"));
            assertEquals("ring",repository.importJson("ring.json").name);
            assertEquals(1,repository.loadAll().size());
        }
    }
}
