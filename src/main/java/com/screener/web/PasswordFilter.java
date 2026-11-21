package com.screener.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * When APP_PASSWORD is set, the page and /api/** ask for it (browser sign-in box, any user name).
 * /public/export/** (guarded by EXPORT_TOKEN instead) and /actuator/health stay open.
 */
@Component
@Order(1)
public class PasswordFilter extends OncePerRequestFilter {

    private final byte[] password;

    public PasswordFilter(@Value("${screener.app-password:}") String password) {
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return password.length == 0 || p.startsWith("/public/export/") || p.equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(h.substring(6)), StandardCharsets.UTF_8);
                int i = decoded.indexOf(':');
                byte[] given = (i < 0 ? "" : decoded.substring(i + 1)).getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(given, password)) {
                    chain.doFilter(req, res);
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // bad base64: fall through to 401
            }
        }
        res.setHeader("WWW-Authenticate", "Basic realm=\"Screener\", charset=\"UTF-8\"");
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
