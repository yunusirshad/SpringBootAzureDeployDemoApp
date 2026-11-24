package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.store.JsonStore;
import org.springframework.stereotype.Service;

/** The trader profile (account size, risk % per trade, minimum R:R), saved to profile.json and shared app-wide. */
@Service
public class ProfileService {

    private static final String FILE = "profile.json";
    private final JsonStore store;
    private volatile PlaybookService.Profile profile;

    public ProfileService(JsonStore store) {
        this.store = store;
        this.profile = store.read(FILE, new TypeReference<PlaybookService.Profile>() {}, new PlaybookService.Profile());
    }

    public PlaybookService.Profile get() { return profile; }

    public PlaybookService.Profile save(PlaybookService.Profile p) {
        p.validate();
        profile = p;
        store.write(FILE, p);
        return p;
    }
}
