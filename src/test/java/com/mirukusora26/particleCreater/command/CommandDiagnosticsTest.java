package com.mirukusora26.particleCreater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandDiagnosticsTest {
    @Test void reportsCommandPathCategoryAndCauseInEnglish() {
        List<String> args=List.of("layer","set","spark","glow","offset","1","NaN","2");
        IllegalArgumentException cause=new IllegalArgumentException("Layer 'glow' offset.y=NaN must be finite and within +/-100");
        assertEquals("LAYER",CommandDiagnostics.inputCategory(args,cause));
        assertEquals("[LAYER] /pc layer set spark glow offset failed: Layer 'glow' offset.y=NaN must be finite and within +/-100",CommandDiagnostics.failure("LAYER",args,cause));
        assertEquals("FORMULA",CommandDiagnostics.inputCategory(List.of("motion","set"),new IllegalArgumentException("Motion rotate.y formula 'sin(' failed at index 4")));
        assertEquals("LOCATION",CommandDiagnostics.inputCategory(List.of("play","spark"),new IllegalArgumentException("Coordinate z='NaN' is invalid")));
    }

    @Test void sanitizesUntrustedErrorTextWithoutDiscardingTheCause() {
        String result=CommandDiagnostics.failure("INPUT",List.of("shape","bad§\nname"),new IllegalArgumentException("bad§\n"+"x".repeat(1000)));
        assertTrue(result.contains("/pc shape bad??name failed: bad??"));
        assertTrue(result.endsWith("x".repeat(1000)));
        assertFalse(result.contains("\n"));
        assertFalse(result.contains("§"));
    }
}
