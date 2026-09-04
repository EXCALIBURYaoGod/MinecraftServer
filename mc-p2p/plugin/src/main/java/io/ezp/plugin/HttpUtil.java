package io.ezp.plugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 HTTP + 顶层 JSON 对象解析（零第三方依赖）。
 * 仅访问回环守护，超时较短，供插件调用 ez-mc daemon。
 */
public final class HttpUtil {

    private HttpUtil() {}

    /**
     * 发送 JSON 请求并返回顶层 Map（值均为 String；嵌套对象/数组返回其原始 JSON 文本）。
     *
     * @param method GET/POST
     * @param url    完整 URL
     * @param json   POST 请求体；GET 传 null
     */
    public static Map<String, String> send(String method, String url, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        if (json != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
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

    /** 解析顶层 JSON 对象："{ key : value , ... }"，值为字符串；嵌套结构原样保留。 */
    private static Map<String, String> parseTopLevel(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        String s = body.trim();
        if (s.isEmpty() || s.charAt(0) != '{') return out;
        int i = 1;
        int n = s.length();
        while (i < n) {
            // 跳过空白和逗号
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
            if (i >= n || s.charAt(i) == '}') break;
            // key (应带引号)
            if (s.charAt(i) != '"') break;
            int kEnd = s.indexOf('"', i + 1);
            String key = s.substring(i + 1, kEnd);
            i = kEnd + 1;
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ':')) i++;
            if (i >= n) break;
            // value
            char c = s.charAt(i);
            if (c == '"') {
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
                // number / true / false / null / 嵌套对象数组（取原始文本）
                int start = i;
                int depthObj = 0;
                int depthArr = 0;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '{') depthObj++;
                    else if (ch == '}') { if (depthObj == 0) break; depthObj--; }
                    else if (ch == '[') depthArr++;
                    else if (ch == ']') { if (depthArr == 0) break; depthArr--; }
                    else if ((ch == ',' || ch == '}') && depthObj == 0 && depthArr == 0) break;
                    i++;
                }
                out.put(key, s.substring(start, i).trim());
            }
        }
        return out;
    }

    public static String getString(Map<String, String> map, String key, String def) {
        String v = map.get(key);
        return v == null || v.isEmpty() ? def : unquote(v);
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}