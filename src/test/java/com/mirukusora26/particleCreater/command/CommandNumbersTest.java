package com.mirukusora26.particleCreater.command;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CommandNumbersTest {
    @Test void timeLimitsHandleUnitsAndOverflow() {
        assertEquals(100,CommandNumbers.ticks("5s"));
        assertEquals(100,CommandNumbers.ticks("100t"));
        assertEquals(-1,CommandNumbers.ticks("infinite"));
        assertThrows(IllegalArgumentException.class,() -> CommandNumbers.ticks("1e308s"));
        assertThrows(IllegalArgumentException.class,() -> CommandNumbers.ticks("72001t"));
        assertThrows(IllegalArgumentException.class,() -> CommandNumbers.ticks("-0.1s"));
    }

    @Test void pagesRejectOverflowAndEmptyLaterPages() {
        assertEquals(0,CommandNumbers.pageOffset(1,0));
        assertEquals(20,CommandNumbers.pageOffset(2,21));
        assertThrows(IllegalArgumentException.class,() -> CommandNumbers.pageOffset(2,0));
        assertThrows(IllegalArgumentException.class,() -> CommandNumbers.pageOffset(Integer.MAX_VALUE,21));
    }
}
