package advisor;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;

/**
 * AI Advisor — a Mindustry mod that provides context-aware game advice
 * powered by the Gemini API.
 */
public class AdvisorMod extends Mod {

    private final GeminiClient client = new GeminiClient();
    private AssistantUI ui;

    public AdvisorMod() {
        Log.info("[AI Advisor] Mod loaded.");

        Events.on(ClientLoadEvent.class, e -> {
            // Load API key from settings
            String savedKey = Core.settings.getString("ai-advisor-apikey", "");
            if (!savedKey.isEmpty()) {
                client.setApiKey(savedKey);
            }

            // Load model from settings
            String savedModel = Core.settings.getString("ai-advisor-model", "gemini-2.5-flash");
            client.setModel(savedModel);

            // Build the UI
            ui = new AssistantUI(this);
            ui.build();

            // Prompt for API key if not configured
            if (!client.isConfigured()) {
                Time.runTask(60f, () -> ui.showApiKeyDialog());
            }
        });
    }

    @Override
    public void loadContent() {
        Log.info("[AI Advisor] Content loaded.");
    }

    public GeminiClient getClient() {
        return client;
    }

    public AssistantUI getUI() {
        return ui;
    }
}
