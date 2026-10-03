package com.mirukusora26.particleCreater.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.mirukusora26.particleCreater.model.EffectDefinition.Layer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class ParticleDataCodecTest {
    @Test void everyPaperParticleDataTypeHasAHandler() {
        for(Particle particle:Particle.values()) assertDoesNotThrow(()->ParticleDataCodec.keys(particle),particle.name());
    }

    @Test void optionalFieldsAreCheckedBeforeRequiredFieldsExist() {
        Layer dust=new Layer("dust","dust","origin");
        Location origin=new Location(null,0,0,0);
        dust.data.put("size","-1");
        assertThrows(IllegalArgumentException.class,() -> ParticleDataCodec.validateProvided(dust,Particle.DUST,origin));
        dust.data.put("size","1.5");
        assertDoesNotThrow(() -> ParticleDataCodec.validateProvided(dust,Particle.DUST,origin));
        dust.data.put("color","not-a-color");
        assertThrows(IllegalArgumentException.class,() -> ParticleDataCodec.validateProvided(dust,Particle.DUST,origin));
    }

    @Test void invalidRelativeTargetsAreRejectedDuringEditing() {
        Layer trail=new Layer("trail","trail","origin");
        trail.data.put("target","~30000001,~0,~0");
        assertThrows(IllegalArgumentException.class,() -> ParticleDataCodec.validateProvided(trail,Particle.TRAIL,new Location(null,0,0,0)));
        trail.data.put("target",",,");
        assertThrows(IllegalArgumentException.class,() -> ParticleDataCodec.validateProvided(trail,Particle.TRAIL,new Location(null,0,0,0)));
    }
}
