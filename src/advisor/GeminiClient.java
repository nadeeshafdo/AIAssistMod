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
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

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
     * Falls back to non-streaming :generateContent if the stream returns empty.
     */
    public void sendStream(String systemPrompt, String[][] messages,
                           Cons<String> onChunk, Cons<String> onComplete, Cons<String> onError) {
        if (!isConfigured()) {
            Core.app.post(() -> onError.get("API key not configured. Open AI Advisor settings."));
            return;
        }
        if (rateLimitCheck(onError)) return;

        String body = buildRequestBody(systemPrompt, messages);

        Thread t = new Thread(() -> {
            // Try streaming first
            String streamResult = doStreamRequest(body, onChunk, onError);
            if (streamResult != null) {
                // Streaming succeeded
                if (streamResult.isEmpty()) {
                    Log.warn("[AI Advisor] Stream returned empty, trying non-streaming fallback...");
                } else {
                    Core.app.post(() -> onComplete.get(streamResult));
                    return;
                }
            }

            // Non-streaming fallback
            doNonStreamingRequest(body, onChunk, onComplete, onError);
        });
        t.setDaemon(true);
        t.start();
    }

    /** @return full text from SSE stream, or null on hard error (callbacks already fired) */
    private String doStreamRequest(String body, Cons<String> onChunk, Cons<String> onError) {
        String url = API_URL + model + ":streamGenerateContent?key=" + apiKey + "&alt=sse";
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
                String errMsg = parseApiError(errBody);
                Log.err("[AI Advisor] Stream API error " + status + ": " + errMsg);
                // Don't fire onError here — caller will try non-streaming fallback
                return "";
            }

            StringBuilder fullText = new StringBuilder();
            int chunkCount = 0;
            String rawLine;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                while ((rawLine = br.readLine()) != null) {
                    if (rawLine.isEmpty()) continue;

                    String jsonStr = null;
                    if (rawLine.startsWith("data: ")) {
                        jsonStr = rawLine.substring(6).trim();
                        if (jsonStr.equals("[DONE]")) break;
                    } else if (rawLine.startsWith("{")) {
                        jsonStr = rawLine;
                    } else if (rawLine.startsWith("[")) {
                        // Some endpoints return a JSON array instead of SSE
                        jsonStr = rawLine;
                    } else {
                        Log.warn("[AI Advisor] Unrecognized SSE line: " + rawLine.substring(0, Math.min(80, rawLine.length())));
                        continue;
                    }

                    String chunk = parseStreamChunk(jsonStr);
                    if (chunk != null && !chunk.isEmpty()) {
                        fullText.append(chunk);
                        chunkCount++;
                        String current = fullText.toString();
                        Core.app.post(() -> onChunk.get(current));
                    }
                }
            }

            String result = fullText.toString();
            Log.info("[AI Advisor] Stream finished: " + chunkCount + " chunks, " + result.length() + " chars");
            if (result.isEmpty()) {
                Log.warn("[AI Advisor] All parsed chunks were empty/null");
            }
            return result;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = "Stream error";
            Log.err("[AI Advisor] Stream exception: " + msg);
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void doNonStreamingRequest(String body, Cons<String> onChunk,
                                       Cons<String> onComplete, Cons<String> onError) {
        String url = API_URL + model + ":generateContent?key=" + apiKey;
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
                String errMsg = parseApiError(errBody);
                Log.err("[AI Advisor] Non-streaming API error " + status + ": " + errMsg);
                Core.app.post(() -> onError.get(errMsg));
                return;
            }

            String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String result = parseResponse(responseBody);
            Log.info("[AI Advisor] Non-streaming response: " + result.length() + " chars");

            // Deliver as a single chunk
            Core.app.post(() -> onChunk.get(result));
            Core.app.post(() -> onComplete.get(result));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = "Non-streaming error";
            Log.err("[AI Advisor] Non-streaming exception: " + msg);
            String finalMsg = msg;
            Core.app.post(() -> onError.get(finalMsg));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String parseStreamChunk(String jsonStr) {
        try {
            // Handle JSON array format: some endpoints return [{...}, ...]
            if (jsonStr.startsWith("[")) {
                Jval arr = Jval.read(jsonStr);
                if (arr.asArray().isEmpty()) return null;
                jsonStr = arr.asArray().get(0).toString();
            }

            Jval json = Jval.read(jsonStr);
            if (json.has("error")) {
                Log.warn("[AI Advisor] Stream chunk contains error: " + json.get("error").get("message").asString());
                return null;
            }
            Jval candidates = json.get("candidates");
            if (candidates == null || candidates.asArray().isEmpty()) return null;

            Jval content = candidates.asArray().get(0).get("content");
            if (content == null) return null;

            Jval parts = content.get("parts");
            if (parts == null || parts.asArray().isEmpty()) return null;

            Jval text = parts.asArray().get(0).get("text");
            if (text == null) return null;

            return text.asString();
        } catch (Exception e) {
            Log.warn("[AI Advisor] Failed to parse stream chunk: " + e.getMessage()
                + " | json=" + jsonStr.substring(0, Math.min(120, jsonStr.length())));
            return null;
        }
    }

    private String parseApiError(String body) {
        try {
            Jval json = Jval.read(body);
            if (json.has("error")) {
                return "API error: " + json.get("error").get("message").asString();
            }
        } catch (Exception ignored) {}
        // Try plain text
        if (body.length() > 0) {
            return body.substring(0, Math.min(200, body.length()));
        }
        return "Unknown error (see logs)";
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
