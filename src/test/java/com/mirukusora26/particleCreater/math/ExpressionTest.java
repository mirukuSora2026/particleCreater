package com.mirukusora26.particleCreater.math;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExpressionTest {
    @Test void evaluatesGeometryAndFourDimensionalVariables() {
        Expression expression=Expression.compile("x^2+y^2+z^2+w^2-r^2",Set.of("x","y","z","w","r"));
        assertEquals(0,expression.evaluate(Map.of("x",1.0,"y",2.0,"z",2.0,"w",0.0,"r",3.0)),1e-10);
        assertEquals(Set.of("x","y","z","w","r"),expression.variables());
    }
    @Test void conditionsAvoidInvalidUnusedBranches() {
        Expression expression=Expression.compile("if(t<1, 2, 1/0)",Set.of("t"));
        assertEquals(2,expression.evaluate(Map.of("t",0.0)));
        assertThrows(ArithmeticException.class,()->expression.evaluate(Map.of("t",2.0)));
    }
    @Test void invalidExpressionsExplainWhereTheyFail() {
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->Expression.compile("sin(q)",Set.of("x")));
        assertTrue(error.getMessage().contains("character"));
        assertThrows(IllegalArgumentException.class,()->Expression.compile("unknown(1)",Set.of()));
        assertThrows(IllegalArgumentException.class,()->Expression.compile("1e999",Set.of()));
        assertThrows(IllegalArgumentException.class,()->Expression.compile("1",null));
        assertThrows(IllegalArgumentException.class,()->Expression.compile("-".repeat(300)+"1",Set.of()));
        assertEquals(-4,Expression.compile("-2^2",Set.of()).evaluate(Map.of()));
    }
}
