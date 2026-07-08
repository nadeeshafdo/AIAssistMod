package advisor;

import arc.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;

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

    /**
     * Send a prompt to Gemini with conversation history.
     *
     * @param systemPrompt  System instruction text
     * @param messages       Array of [role, text] pairs — roles are "user" or "model"
     * @param onSuccess      Called on main thread with the response text
     * @param onError        Called on main thread with the error message
     */
    public void send(String systemPrompt, String[][] messages, Cons<String> onSuccess, Cons<String> onError) {
        if (!isConfigured()) {
            Core.app.post(() -> onError.get("API key not configured. Open AI Advisor settings."));
            return;
        }

        long now = Time.millis();
        if (now - lastRequestTime < MIN_REQUEST_INTERVAL_MS) {
            Core.app.post(() -> onError.get("Please wait a moment between requests."));
            return;
        }
        lastRequestTime = now;

        String url = API_URL + model + ":generateContent?key=" + apiKey;
        String body = buildRequestBody(systemPrompt, messages);

        Http.post(url)
            .timeout(30000)
            .header("Content-Type", "application/json")
            .content(body)
            .error(e -> {
                String msg = e.getMessage();
                if (msg == null) msg = "Unknown network error";
                String finalMsg = msg;
                Core.app.post(() -> onError.get("Network error: " + finalMsg));
            })
            .submit(response -> {
                try {
                    String result = response.getResultAsString();
                    String text = parseResponse(result);
                    Core.app.post(() -> onSuccess.get(text));
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg == null) msg = "Failed to parse response";
                    String finalMsg = msg;
                    Core.app.post(() -> onError.get(finalMsg));
                }
            });
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
