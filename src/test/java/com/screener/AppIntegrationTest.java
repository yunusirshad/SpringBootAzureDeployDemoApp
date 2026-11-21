package com.screener;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Runs the whole app on the demo data. */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppIntegrationTest {

    static Path dir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        dir = Files.createTempDirectory("screener-test");
        r.add("screener.store-dir", dir::toString);
        r.add("screener.data.provider", () -> "demo");
        r.add("screener.data.pause-millis", () -> "0");
        r.add("screener.zoya.api-key", () -> "");
    }

    @Autowired MockMvc mvc;

    @Test @Order(1)
    void screenAppliesEveryGateAndZoya() throws Exception {
        mvc.perform(post("/api/screen/run?wait=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows[*].symbol", hasItem("DEMA")))
                .andExpect(jsonPath("$.result.rows[*].symbol", hasItem("DEMB")))
                .andExpect(jsonPath("$.result.rows[*].symbol", hasItem("DEMH")))   // low quality, still listed
                .andExpect(jsonPath("$.result.rows[*].symbol", not(hasItem("DEMC")))) // non-compliant
                .andExpect(jsonPath("$.result.rows[*].symbol", not(hasItem("DEMD")))) // price over $20
                .andExpect(jsonPath("$.result.rows[*].symbol", not(hasItem("DEME")))) // questionable
                .andExpect(jsonPath("$.result.rows[*].symbol", not(hasItem("DEMF")))) // debt
                .andExpect(jsonPath("$.result.rows[*].symbol", not(hasItem("DEMG")))) // upside
                .andExpect(jsonPath("$.result.rows[*].symbol", not(hasItem("DEMI")))) // cap
                .andExpect(jsonPath("$.result.rows[*].zoya.status", everyItem(is("COMPLIANT"))))
                .andExpect(jsonPath("$.result.rows[0].gex.callWall").isNumber())
                .andExpect(jsonPath("$.result.rows[0].earningsDate").isString());
    }

    @Test @Order(2)
    void coreKeepsCompliantNamesForTheDay() throws Exception {
        mvc.perform(get("/api/core")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].symbol", hasItem("DEMA")))
                .andExpect(jsonPath("$.rows[0].gates").isArray());
        mvc.perform(post("/api/core/DEMC")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/core/DEMB")).andExpect(jsonPath("$.removed", is(true)));
        mvc.perform(post("/api/core/DEMB")).andExpect(jsonPath("$.added", is(true)));
    }

    @Test @Order(3)
    void analyzeGivesPlanAndGex() throws Exception {
        mvc.perform(get("/api/analyze/DEMA?account=10000&riskPercent=1&side=long")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.side", is("LONG")))
                .andExpect(jsonPath("$.plan.shares").isNumber())
                .andExpect(jsonPath("$.gex.king").isNumber())
                .andExpect(jsonPath("$.row.symbol", is("DEMA")));
    }

    @Test @Order(4)
    void snapshotAndExports() throws Exception {
        mvc.perform(get("/public/export/snapshot")).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].symbol", hasItem("DEMA")))
                .andExpect(jsonPath("$.entries[0].side").isString())
                .andExpect(jsonPath("$.entries[0].bias").isString())
                .andExpect(jsonPath("$.entries[0].state").isString())
                .andExpect(jsonPath("$.entries[0].zoya").isString())
                .andExpect(jsonPath("$.entries[0].qualityScore").isNumber());
        mvc.perform(get("/public/export/screen?format=csv&include=core")).andExpect(status().isOk())
                .andExpect(content().string(containsString("earnings_date")))
                .andExpect(content().string(containsString("DEMA,")));
        mvc.perform(get("/public/export/snapshot?format=csv")).andExpect(status().isOk())
                .andExpect(content().string(containsString("side,bias,state,zoya")));
        mvc.perform(get("/public/export/bars/DEMA?days=2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.interval", is("15m")))
                .andExpect(jsonPath("$.bars[*].session", hasItem("PRE")))
                .andExpect(jsonPath("$.bars[*].session", hasItem("POST")))
                .andExpect(jsonPath("$.bars[*].session", hasItem("REGULAR")));
        mvc.perform(get("/api/analyze/BAD$")).andExpect(status().isBadRequest());
    }
}
