package io.ezp.mod.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 极简 HTTP + 顶层 JSON 对象解析（零第三方依赖），访问本机 ez-mc 守护。 */
public final class HttpUtil {

    private HttpUtil() {}

    public static Map<String, String> send(String method, String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(in);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + body);
        }
        return parseTopLevel(body);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** 解析顶层 JSON 对象："{ key : value , ... }"，值一律转字符串；嵌套结构原样保留。 */
    private static Map<String, String> parseTopLevel(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        String s = body.trim();
        if (s.isEmpty() || s.charAt(0) != '{') return out;
        int i = 1, n = s.length();
        while (i < n) {
            while (i < n && isSpace(s.charAt(i) == ',' ? ',' : s.charAt(i))) {
                char ch = s.charAt(i);
                if (ch != ',' && ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') break;
                i++;
            }
            if (i >= n || s.charAt(i) == '}') break;
            if (s.charAt(i) != '"') break;
            int kEnd = s.indexOf('"', i + 1);
            String key = s.substring(i + 1, kEnd);
            i = kEnd + 1;
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ':')) i++;
            if (i >= n) break;
            char c0 = s.charAt(i);
            if (c0 == '"') {
                StringBuilder v = new StringBuilder();
                i++;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '\\' && i + 1 < n) { v.append(s.charAt(i + 1)); i += 2; continue; }
                    if (ch == '"') { i++; break; }
                    v.append(ch); i++;
                }
                out.put(key, v.toString());
            } else {
                int start = i, oo = 0, aa = 0;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '{') oo++;
                    else if (ch == '}') { if (oo == 0) break; oo--; }
                    else if (ch == '[') aa++;
                    else if (ch == ']') { if (aa == 0) break; aa--; }
                    else if ((ch == ',' || ch == '}') && oo == 0 && aa == 0) break;
                    i++;
                }
                out.put(key, s.substring(start, i).trim());
            }
        }
        return out;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == '{' || c == ':';
    }

    public static String getString(Map<String, String> map, String key, String def) {
        String v = map.get(key);
        if (v == null || v.isEmpty()) return def;
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}