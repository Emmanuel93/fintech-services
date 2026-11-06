package com.fintech.documents;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The design system's palette as the templates carry it: every template is written in these colors, and a service
 * that prints for another brand configures its own; {@link #applyPalette} swaps them before compiling.
 */
public final class Brand {

    private Brand() {}

    /** The roles and the Kredius color of each: what the templates are written in. */
    public static Map<String, String> defaultRoles() {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("primary", "#0D47A1");
        roles.put("accent", "#00B894");
        roles.put("ink", "#0F1A2E");
        roles.put("muted", "#7A8296");
        roles.put("paper", "#EBF0F7");
        roles.put("soft", "#F6F8FC");
        roles.put("line", "#EDEFF3");
        roles.put("primaryContainer", "#D6E4FF");
        roles.put("onPrimaryContainer", "#001A42");
        return roles;
    }

    /** The logotype every document prints, packaged in this module. */
    public static final String DEFAULT_LOGO = "classpath:branding/kredius-logotipo-azul.png";

    /**
     * Replaces each role's default color with the configured one. A placeholder first, so a configured color that
     * equals another role's default is not replaced twice.
     */
    public static String applyPalette(String jrxml, Map<String, String> defaults, Map<String, String> configured) {
        String out = jrxml;
        for (Map.Entry<String, String> role : defaults.entrySet()) {
            String wanted = normalize(configured == null ? null : configured.get(role.getKey()), role.getValue());
            if (!wanted.equalsIgnoreCase(role.getValue())) {
                out = out.replace(role.getValue(), "@@" + role.getKey() + "@@")
                         .replace(role.getValue().toLowerCase(Locale.ROOT), "@@" + role.getKey() + "@@");
            }
        }
        for (Map.Entry<String, String> role : defaults.entrySet()) {
            out = out.replace("@@" + role.getKey() + "@@",
                    normalize(configured == null ? null : configured.get(role.getKey()), role.getValue()));
        }
        return out;
    }

    static String normalize(String color, String fallback) {
        if (color == null || color.isBlank()) return fallback;
        String c = color.trim().toUpperCase(Locale.ROOT);
        if (!c.startsWith("#")) c = "#" + c;
        if (!c.matches("#[0-9A-F]{6}")) {
            throw new IllegalStateException("Color de marca inválido '" + color + "': se espera #RRGGBB");
        }
        return c;
    }
}
