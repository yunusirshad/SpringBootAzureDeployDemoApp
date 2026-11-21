package com.screener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PasswordFilterTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("screener-pw").toString();
        r.add("screener.store-dir", () -> dir);
        r.add("screener.app-password", () -> "s3cret");
        r.add("screener.zoya.api-key", () -> "");
    }

    @Autowired MockMvc mvc;

    static String basic(String pw) {
        return "Basic " + Base64.getEncoder().encodeToString(("me:" + pw).getBytes());
    }

    @Test
    void pageAndApiNeedThePassword() throws Exception {
        mvc.perform(get("/api/rules")).andExpect(status().isUnauthorized());
        mvc.perform(get("/index.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rules").header("Authorization", basic("wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rules").header("Authorization", basic("s3cret"))).andExpect(status().isOk());
    }

    @Test
    void exportsAndHealthStayOpen() throws Exception {
        mvc.perform(get("/public/export/screen")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
