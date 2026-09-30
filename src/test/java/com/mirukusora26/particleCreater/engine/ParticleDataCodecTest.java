package com.mirukusora26.particleCreater.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class ParticleDataCodecTest {
    @Test void everyPaperParticleDataTypeHasAHandler() {
        for(Particle particle:Particle.values()) assertDoesNotThrow(()->ParticleDataCodec.keys(particle),particle.name());
    }
}
