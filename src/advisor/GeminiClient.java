package advisor;

import arc.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;

/**
 * HTTP client for the Google Gemini API.
 * Runs requests on background threads, delivers results on the main thread.
 */
public class GeminiClient {
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemma-4-31b-it";

    private String apiKey = "";
    private String model = DEFAULT_MODEL;
    private long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL_MS = 2000;

    public void setApiKey(String key) {
        this.apiKey = key != null ? key.trim() : "";
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    public void setModel(String model) {
        this.model = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
    }

    public String getModel() {
        return model;
    }

    private boolean rateLimitCheck(Cons<String> onError) {
        long now = Time.millis();
        if (now - lastRequestTime < MIN_REQUEST_INTERVAL_MS) {
            Core.app.post(() -> onError.get("Please wait a moment between requests."));
            return true;
        }
        lastRequestTime = now;
        return false;
    }

    /**
     * Streaming request to Gemini — tokens arrive incrementally via SSE.
     *
     * @param systemPrompt  System instruction text
     * @param messages       Array of [role, text] pairs
     * @param onChunk        Called on main thread with full accumulated text so far
     * @param onComplete     Called on main thread with the full response text
     * @param onError        Called on main thread with the error message
     */
    public void sendStream(String systemPrompt, String[][] messages,
                           Cons<String> onChunk, Cons<String> onComplete, Cons<String> onError) {
        if (!isConfigured()) {
            Core.app.post(() -> onError.get("API key not configured. Open AI Advisor settings."));
            return;
        }
        if (rateLimitCheck(onError)) return;

        String url = API_URL + model + ":streamGenerateContent?key=" + apiKey;
        String body = buildRequestBody(systemPrompt, messages);

        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                if (status != 200) {
                    String errBody = new String(
                        (conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream()).readAllBytes(),
                        StandardCharsets.UTF_8);
                    String errMsg = parseStreamError(errBody);
                    Core.app.post(() -> onError.get(errMsg));
                    return;
                }

                StringBuilder fullText = new StringBuilder();
                StringBuilder lineBuf = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        if (line.equals("[DONE]")) break;

                        String jsonStr = null;
                        if (line.startsWith("data: ")) {
                            jsonStr = line.substring(6).trim();
                        } else if (line.startsWith("{")) {
                            jsonStr = line;
                        }
                        if (jsonStr == null) continue;

                        String chunk = parseStreamChunk(jsonStr);
                        if (chunk != null && !chunk.isEmpty()) {
                            fullText.append(chunk);
                            String current = fullText.toString();
                            Core.app.post(() -> onChunk.get(current));
                        }
                    }
                }
                conn.disconnect();

                String result = fullText.toString();
                Core.app.post(() -> onComplete.get(result));
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null) msg = "Stream error";
                String finalMsg = msg;
                Core.app.post(() -> onError.get(finalMsg));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private String parseStreamChunk(String jsonStr) {
        try {
            Jval json = Jval.read(jsonStr);
            if (json.has("error")) {
                throw new RuntimeException("API error: " + json.get("error").get("message").asString());
            }
            Jval candidates = json.get("candidates");
            if (candidates == null || candidates.asArray().isEmpty()) return null;

            Jval parts = candidates.asArray().get(0).get("content").get("parts");
            if (parts == null || parts.asArray().isEmpty()) return null;

            return parts.asArray().get(0).get("text").asString();
        } catch (Exception e) {
            return null;
        }
    }

    private String parseStreamError(String body) {
        try {
            Jval json = Jval.read(body);
            if (json.has("error")) {
                return "API error: " + json.get("error").get("message").asString();
            }
        } catch (Exception ignored) {}
        return "HTTP error (see logs)";
    }

    private String buildRequestBody(String systemPrompt, String[][] messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // System instruction
        sb.append("\"systemInstruction\":{\"parts\":[{\"text\":\"");
        sb.append(escapeJson(systemPrompt));
        sb.append("\"}]},");

        // Contents (conversation history)
        sb.append("\"contents\":[");
        for (int i = 0; i < messages.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(escapeJson(messages[i][0])).append("\",");
            sb.append("\"parts\":[{\"text\":\"").append(escapeJson(messages[i][1])).append("\"}]}");
        }
        sb.append("],");

        // Generation config
        sb.append("\"generationConfig\":{");
        sb.append("\"temperature\":0.7,");
        sb.append("\"maxOutputTokens\":1024");
        sb.append("}");

        sb.append("}");
        return sb.toString();
    }

    private String parseResponse(String responseBody) {
        Jval json = Jval.read(responseBody);

        // Check for API errors
        if (json.has("error")) {
            Jval error = json.get("error");
            throw new RuntimeException("API error: " + error.get("message").asString());
        }

        Jval candidates = json.get("candidates");
        if (candidates == null || candidates.asArray().isEmpty()) {
            throw new RuntimeException("No response generated.");
        }

        return candidates.asArray().get(0)
            .get("content").get("parts").asArray().get(0)
            .get("text").asString();
    }

    /** Escape a string for safe inclusion in a JSON string value. */
    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public void fetchModels(Cons<Seq<String[]>> onSuccess, Cons<String> onError) {
        if (!isConfigured()) {
            Core.app.post(() -> onError.get("API key not configured."));
            return;
        }

        String url = API_URL.substring(0, API_URL.length() - 1) + "?key=" + apiKey;

        Http.get(url)
            .timeout(10000)
            .error(e -> {
                String msg = e.getMessage();
                if (msg == null) msg = "Failed to fetch models";
                String finalMsg = msg;
                Core.app.post(() -> onError.get(finalMsg));
            })
            .submit(response -> {
                try {
                    String result = response.getResultAsString();
                    Jval json = Jval.read(result);
                    
                    if (json.has("error")) {
                        throw new RuntimeException(json.get("error").get("message").asString());
                    }

                    Jval modelsJson = json.get("models");
                    Seq<String[]> modelList = new Seq<>();
                    if (modelsJson != null && modelsJson.isArray()) {
                        for (Jval m : modelsJson.asArray()) {
                            String name = m.get("name").asString();
                            if (name.startsWith("models/")) {
                                name = name.substring("models/".length());
                            }
                            
                            boolean supportsGenerate = false;
                            Jval methods = m.get("supportedGenerationMethods");
                            if (methods != null && methods.isArray()) {
                                for (Jval method : methods.asArray()) {
                                    if (method.asString().equals("generateContent")) {
                                        supportsGenerate = true;
                                        break;
                                    }
                                }
                            }
                            
                            if (supportsGenerate) {
                                String displayName = m.has("displayName") ? m.get("displayName").asString() : name;
                                modelList.add(new String[]{name, displayName});
                            }
                        }
                    }
                    
                    modelList.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
                    
                    Core.app.post(() -> onSuccess.get(modelList));
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg == null) msg = "Failed to parse models";
                    String finalMsg = msg;
                    Core.app.post(() -> onError.get(finalMsg));
                }
            });
    }
}
