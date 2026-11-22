package com.personalscreener.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.personalscreener.config.ScreenerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;

/** Small JSON files in the data folder, written atomically. */
@Component
public class JsonStore {

    private static final Logger log = LoggerFactory.getLogger(JsonStore.class);
    private final Path dir;
    private final ObjectMapper mapper;

    public JsonStore(ScreenerProperties props, ObjectMapper mapper) throws IOException {
        this.dir = Paths.get(props.getDataDir()).toAbsolutePath();
        Files.createDirectories(dir);
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        log.info("Data folder: {}", dir);
    }

    public synchronized <T> T read(String name, TypeReference<T> type, T fallback) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return fallback;
        try {
            return mapper.readValue(p.toFile(), type);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", p, e.getMessage());
            return fallback;
        }
    }

    public synchronized void write(String name, Object value) {
        Path p = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        try {
            mapper.writeValue(tmp.toFile(), value);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", p, e.getMessage());
        }
    }
}
