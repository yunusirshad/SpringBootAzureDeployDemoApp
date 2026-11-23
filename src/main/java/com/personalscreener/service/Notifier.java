package com.personalscreener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.data.Http;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Sends a message to your phone through Telegram (SCREENER_TELEGRAM_TOKEN + SCREENER_TELEGRAM_CHAT_ID) and/or ntfy
 * (SCREENER_NTFY_TOPIC). A channel with no settings is skipped. The browser notification in the app needs neither.
 */
@Service
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final Http http;
    private final ScreenerProperties props;

    public Notifier(ObjectMapper mapper, ScreenerProperties props) {
        this.http = new Http(mapper);
        this.props = props;
    }

    public List<String> channels() {
        List<String> c = new ArrayList<>();
        if (props.getNotify().telegram()) c.add("Telegram");
        if (props.getNotify().ntfy()) c.add("ntfy");
        return c;
    }

    /** Sends to every configured channel; returns "channel: ok" or "channel: error" per channel. */
    public List<String> send(String title, String body) {
        List<String> out = new ArrayList<>();
        ScreenerProperties.Notify n = props.getNotify();
        if (n.telegram()) {
            try {
                String url = "https://api.telegram.org/bot" + n.getTelegramToken() + "/sendMessage";
                String form = "chat_id=" + enc(n.getTelegramChatId()) + "&text=" + enc(title + "\n" + body);
                HttpResponse<String> r = http.post(url, form, Map.of("Content-Type", "application/x-www-form-urlencoded"));
                out.add(r.statusCode() / 100 == 2 ? "Telegram: sent" : "Telegram: HTTP " + r.statusCode());
            } catch (Exception e) {
                out.add("Telegram: " + e.getMessage());
            }
        }
        if (n.ntfy()) {
            try {
                String url = n.getNtfyServer().replaceAll("/+$", "") + "/" + enc(n.getNtfyTopic());
                HttpResponse<String> r = http.post(url, body, Map.of("Title", ascii(title), "Tags", "chart_with_upwards_trend"));
                out.add(r.statusCode() / 100 == 2 ? "ntfy: sent" : "ntfy: HTTP " + r.statusCode());
            } catch (Exception e) {
                out.add("ntfy: " + e.getMessage());
            }
        }
        if (!out.isEmpty()) log.info("Notify '{}': {}", title, out);
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** HTTP header values must be plain ASCII. */
    private static String ascii(String s) { return s.replaceAll("[^\\x20-\\x7E]", ""); }
}
