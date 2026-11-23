package com.personalscreener.service;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionSortTest {

    @Test
    void phasesSortInTheRequestedOrder() {
        List<String> shuffled = List.of("Bear Distribution", "Bull Accumulation", "Bear Absorption", "Bull Manipulation",
                "Bull Distribution", "Bull Absorption", "Bear Manipulation");
        List<String> sorted = shuffled.stream().sorted(Comparator.comparingInt(ScreenService::sessionGroup)).toList();
        assertEquals(List.of("Bull Distribution", "Bull Absorption", "Bear Absorption", "Bull Manipulation", "Bull Accumulation"),
                sorted.subList(0, 5));
        assertEquals(9, ScreenService.sessionGroup(null));
        assertEquals(9, ScreenService.sessionGroup("Bear Accumulation"));
    }
}
