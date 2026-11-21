package com.screener.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.config.ScreenerProperties;

/** Small JSON files under screener.store-dir. Written to a temp file and moved into place. */
@Component
public class JsonStore {
    private static final Logger log = LoggerFactory.getLogger(JsonStore.class);

    private final Path dir;
    private final ObjectMapper mapper;

    public JsonStore(ScreenerProperties props, ObjectMapper mapper) {
        this.dir = Path.of(props.getStoreDir());
        this.mapper = mapper;
    }

    public <T> Optional<T> read(String name, TypeReference<T> type) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.ofNullable(mapper.readValue(p.toFile(), type));
        } catch (IOException e) {
            log.warn("Could not read {}: {}", p, e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void write(String name, Object value) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", name, e.getMessage());
        }
    }
}
