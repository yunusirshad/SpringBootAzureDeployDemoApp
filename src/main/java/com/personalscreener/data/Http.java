package com.personalscreener.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** A small JSON-over-HTTP helper shared by the data clients. */
public class Http {

    private final HttpClient client;
    private final ObjectMapper mapper;

    public Http(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(java.net.ProxySelector.getDefault()) // honours https.proxyHost when set
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    public HttpResponse<String> get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET();
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String url, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public JsonNode getJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpResponse<String> r = get(url, headers);
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        return mapper.readTree(r.body());
    }

    public JsonNode postJson(String url, Object body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpResponse<String> r = post(url, mapper.writeValueAsString(body), headers);
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        return mapper.readTree(r.body());
    }

    public ObjectMapper mapper() { return mapper; }

    static String host(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return url; }
    }

    /** Null-safe double read: missing, null or non-numeric becomes NaN. */
    public static double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? Double.NaN : n.asDouble();
    }

    public static double num(JsonNode n, String field) { return n == null ? Double.NaN : num(n.get(field)); }

    public static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
