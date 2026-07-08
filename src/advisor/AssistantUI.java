package advisor;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

/**
 * In-game assistant UI — a toggleable side panel with chat interface.
 */
public class AssistantUI {
    private static final int MAX_HISTORY = 20;
    private static final Color USER_COLOR = Color.valueOf("8cbed6");
    private static final Color AI_COLOR = Color.valueOf("a8e6a1");
    private static final Color ERROR_COLOR = Color.valueOf("e68a8a");
    private static final Color SYSTEM_COLOR = Color.gray;

    private final AdvisorMod mod;
    private Table chatPanel;
    private Table messagesContainer;
    private ScrollPane scrollPane;
    private TextField inputField;
    private TextButton sendButton;
    private boolean visible = false;
    private boolean waiting = false;

    /** Messages: [0]=role ("user"/"model"/"error"/"system"), [1]=text */
    final Seq<String[]> messages = new Seq<>();

    public AssistantUI(AdvisorMod mod) {
        this.mod = mod;
    }

    /** Build the HUD toggle button and the chat panel. Call after client load. */
    public void build() {
        // Floating chat panel (initially hidden)
        chatPanel = new Table(Styles.black6);
        chatPanel.visible = false;
        chatPanel.setFillParent(false);
        chatPanel.touchable = Touchable.enabled;

        // HUD button
        Vars.ui.hudGroup.fill(t -> {
            t.bottom().left();
            t.marginBottom(4f).marginLeft(4f);
            t.button("[#8cbed6]AI[]", Styles.flatTogglet, () -> {
                togglePanel();
            }).size(60f, 40f).checked(b -> visible);
        });

        // Build the panel layout
        buildPanel();

        // Add the panel to the HUD group
        Vars.ui.hudGroup.addChild(chatPanel);

        addSystemMessage("AI Advisor ready. Type a question or tap [accent]Analyze[].");
    }

    private void buildPanel() {
        chatPanel.clearChildren();
        float panelWidth = Math.min(420f, Core.graphics.getWidth() * 0.35f);
        float panelHeight = Math.min(520f, Core.graphics.getHeight() * 0.65f);

        chatPanel.setSize(panelWidth, panelHeight);
        chatPanel.setPosition(4f, 4f);
        chatPanel.margin(8f);

        // Title bar
        chatPanel.table(Styles.black5, title -> {
            title.add("[accent]AI Advisor[]").growX().left().padLeft(4f);
            title.button(Icon.add, Styles.clearNonei, this::newChat).size(32f).right().padRight(4f);
            title.button(Icon.settings, Styles.clearNonei, this::showApiKeyDialog).size(32f).right();
            title.button("X", Styles.flatt, this::togglePanel).size(32f).right();
        }).growX().height(36f).row();

        // Quick action buttons
        chatPanel.table(actions -> {
            actions.defaults().growX().height(30f).pad(2f);
            actions.button("Analyze", Styles.flatTogglet, () -> sendQuery("Analyze my current game state. What are my strengths, weaknesses, and what should I prioritize next?")).growX();
            actions.button("Defend", Styles.flatTogglet, () -> sendQuery("How should I improve my defenses for the upcoming waves?")).growX();
            actions.button("Build", Styles.flatTogglet, () -> sendQuery("What should I build next and why?")).growX();
        }).growX().row();

        // Chat messages area
        messagesContainer = new Table();
        messagesContainer.top().left();
        scrollPane = new ScrollPane(messagesContainer, Styles.smallPane);
        scrollPane.setScrollingDisabled(true, false);
        scrollPane.setOverscroll(false, false);
        chatPanel.add(scrollPane).grow().pad(4f).row();

        // Input area
        chatPanel.table(input -> {
            inputField = new TextField("");
            inputField.setMessageText("Ask anything...");
            inputField.typed(c -> {
                if (c == '\n' || c == '\r') {
                    sendCurrentInput();
                }
            });
            input.add(inputField).growX().height(36f).padRight(4f);

            sendButton = new TextButton("Send", Styles.flatt);
            sendButton.clicked(this::sendCurrentInput);
            input.add(sendButton).width(60f).height(36f);
        }).growX().row();

        // Rebuild displayed messages
        rebuildMessages();
    }

    private void togglePanel() {
        visible = !visible;
        chatPanel.visible = visible;
        if (visible) {
            buildPanel();
            scrollToBottom();
        }
    }

    private void sendCurrentInput() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || waiting) return;
        inputField.setText("");
        sendQuery(text);
    }

    public void sendQuery(String query) {
        if (waiting) return;
        if (!mod.getClient().isConfigured()) {
            showApiKeyDialog();
            return;
        }

        addUserMessage(query);
        setWaiting(true);

        // Build conversation history for API
        Seq<String[]> apiMessages = new Seq<>();
        for (String[] msg : messages) {
            String role = msg[0];
            if (role.equals("user") || role.equals("model")) {
                apiMessages.add(new String[]{role, msg[1]});
            }
        }

        // Trim to last 10 exchanges (20 messages)
        while (apiMessages.size > MAX_HISTORY) {
            apiMessages.remove(0);
        }

        String systemPrompt = buildSystemPrompt();
        String[][] history = apiMessages.toArray(String[].class);

        mod.getClient().send(systemPrompt, history,
            response -> {
                setWaiting(false);
                String cleanText = processCommands(response);
                addAIMessage(cleanText);
            },
            error -> {
                setWaiting(false);
                addErrorMessage(error);
            }
        );
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert Mindustry game advisor embedded directly into the game. ");
        sb.append("You have deep knowledge of all Mindustry mechanics: resource chains, production ratios, ");
        sb.append("turret ranges and DPS, unit composition, power management, wave defense strategies, ");
        sb.append("and tech tree progression.\n\n");
        sb.append("Rules:\n");
        sb.append("1. Be concise — players need quick answers during active gameplay.\n");
        sb.append("2. Reference specific blocks, items, and units by their proper Mindustry names.\n");
        sb.append("3. When suggesting builds, give specific quantities and placement advice.\n");
        sb.append("4. Prioritize immediate threats over long-term optimization.\n");
        sb.append("5. If you spot critical issues (no power, weak defenses, resource bottlenecks), flag them first.\n");
        sb.append("6. Use Mindustry color markup sparingly for emphasis: [accent]important[], [scarlet]danger[]\n");
        sb.append("7. You can execute in-game commands by wrapping them in [!cmd]...[/cmd] tags. ");
        sb.append("This lets you spawn units, give resources, set waves, and heal. ");
        sb.append("Use this to help the player — never act without explaining why.\n\n");
        sb.append("Available commands:\n");
        sb.append("  spawn <unit> [amount]  — spawn units near core\n");
        sb.append("  give <item> [amount]   — add items to core ('all' or '*' for every type)\n");
        sb.append("  setwave <number>       — set current wave\n");
        sb.append("  heal                   — heal all player units and core\n\n");
        sb.append("--- CURRENT GAME STATE ---\n");
        sb.append(GameContext.collect());
        return sb.toString();
    }

    // --- Message management ---

    private void addUserMessage(String text) {
        messages.add(new String[]{"user", text});
        appendMessageUI("user", text);
    }

    private void addAIMessage(String text) {
        messages.add(new String[]{"model", text});
        appendMessageUI("model", text);
    }

    private void addErrorMessage(String text) {
        messages.add(new String[]{"error", text});
        appendMessageUI("error", text);
    }

    public void addSystemMessage(String text) {
        messages.add(new String[]{"system", text});
        appendMessageUI("system", text);
    }

    private void appendMessageUI(String role, String text) {
        if (messagesContainer == null) return;

        Color color;
        String prefix;
        switch (role) {
            case "user":   color = USER_COLOR;   prefix = "[#8cbed6]You:[]\n"; break;
            case "model":  color = AI_COLOR;      prefix = "[#a8e6a1]AI:[]\n"; break;
            case "error":  color = ERROR_COLOR;   prefix = "[scarlet]Error:[]\n"; break;
            default:       color = SYSTEM_COLOR;  prefix = ""; break;
        }

        String formattedText = role.equals("model") ? formatMarkdown(text) : text;

        messagesContainer.table(Styles.black3, row -> {
            row.margin(6f);
            Label label = new Label(prefix + formattedText);
            label.setWrap(true);
            label.setColor(Color.white);
            row.add(label).growX().left();
        }).growX().pad(2f).row();

        scrollToBottom();
    }

    private String formatMarkdown(String text) {
        if (text == null) return "";
        
        // Convert bold: **text** to [accent]text[]
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "[accent]$1[]");
        
        // Convert headers: e.g. "### Title" to [accent]Title[]
        text = text.replaceAll("(?m)^#+\\s*(.+)$", "[accent]$1[]");
        
        // Convert code blocks: `code` to [orange]code[]
        text = text.replaceAll("`(.*?)`", "[orange]$1[]");
        
        // Convert bullet points: e.g. "* item" or "- item" to "  • item"
        text = text.replaceAll("(?m)^\\s*[*+-]\\s+", "  • ");
        
        return text;
    }

    private String processCommands(String text) {
        if (text == null || !text.contains("[!cmd]")) return text;

        StringBuilder cleaned = new StringBuilder(text);
        int executed = 0;
        int start;
        while ((start = cleaned.indexOf("[!cmd]")) != -1) {
            if (executed >= 5) {
                cleaned.delete(start, cleaned.indexOf("[/cmd]", start) + 6);
                continue;
            }
            int end = cleaned.indexOf("[/cmd]", start);
            if (end == -1) break;

            String cmd = cleaned.substring(start + 6, end);
            cleaned.delete(start, end + 6);

            try {
                String result = CommandHandler.execute(cmd);
                addSystemMessage("[accent]!" + cmd.replace("\n", "\\n") + "[] -> " + result);
            } catch (Exception e) {
                addSystemMessage("[scarlet]!" + cmd.replace("\n", "\\n") + "[] -> Error: " + e.getMessage());
            }
            executed++;
        }
        return cleaned.toString();
    }

    private void rebuildMessages() {
        if (messagesContainer == null) return;
        messagesContainer.clearChildren();
        for (String[] msg : messages) {
            appendMessageUI(msg[0], msg[1]);
        }
    }

    private void scrollToBottom() {
        if (scrollPane != null) {
            Time.runTask(3f, () -> {
                scrollPane.layout();
                scrollPane.setScrollPercentY(1f);
            });
        }
    }

    private void newChat() {
        messages.clear();
        addSystemMessage("AI Advisor ready. Type a question or tap [accent]Analyze[].");
        rebuildMessages();
    }

    private void setWaiting(boolean w) {
        waiting = w;
        if (sendButton != null) {
            sendButton.setDisabled(w);
            sendButton.setText(w ? "..." : "Send");
        }
    }

    /** Show a dialog to enter the API key and configure options. */
    public void showApiKeyDialog() {
        BaseDialog dialog = new BaseDialog("AI Advisor Setup");
        dialog.cont.margin(16f);

        dialog.cont.add("Enter your Gemini API Key:").left().row();

        TextField keyField = new TextField(mod.getClient().getApiKey());
        keyField.setMessageText("Paste API key here");
        dialog.cont.add(keyField).width(400f).height(40f).row();

        dialog.cont.add("[lightgray]Get a key at [accent]ai.google.dev[]").left().padTop(4f).padBottom(16f).row();

        // Model selection display
        Label modelLabel = new Label("Current Model: [accent]" + mod.getClient().getModel() + "[]");
        dialog.cont.add(modelLabel).left().padBottom(8f).row();

        dialog.cont.button("Select Model", () -> {
            String currentKey = keyField.getText().trim();
            if (!currentKey.isEmpty()) {
                mod.getClient().setApiKey(currentKey);
            }
            showModelSelectionDialog(dialog, modelLabel);
        }).width(180f).height(40f).padBottom(16f).row();

        dialog.buttons.button("Cancel", dialog::hide).width(120f);
        dialog.buttons.button("Save", () -> {
            String key = keyField.getText().trim();
            if (!key.isEmpty()) {
                mod.getClient().setApiKey(key);
                Core.settings.put("ai-advisor-apikey", key);
                Core.settings.manualSave();
                addSystemMessage("API key saved. You're good to go!");
            }
            dialog.hide();
        }).width(120f);

        dialog.show();
    }

    private void showModelSelectionDialog(BaseDialog parentDialog, Label modelLabel) {
        BaseDialog listDialog = new BaseDialog("Select Model");
        listDialog.cont.margin(16f);
        listDialog.cont.add("Fetching models... Please wait.").pad(20f).row();
        listDialog.buttons.button("Close", listDialog::hide).width(120f);
        listDialog.show();

        mod.getClient().fetchModels(models -> {
            listDialog.cont.clearChildren();
            
            if (models.isEmpty()) {
                listDialog.cont.add("No models found supporting text generation.").pad(20f).row();
                return;
            }

            Table scrollTable = new Table();
            scrollTable.top().left();
            
            for (String[] m : models) {
                String name = m[0];
                String displayName = m[1];
                
                scrollTable.button(displayName + "\n[gray]" + name + "[]", Styles.flatt, () -> {
                    mod.getClient().setModel(name);
                    Core.settings.put("ai-advisor-model", name);
                    Core.settings.manualSave();
                    modelLabel.setText("Current Model: [accent]" + name + "[]");
                    addSystemMessage("Switched model to: [accent]" + name + "[]");
                    listDialog.hide();
                }).growX().height(55f).left().pad(4f).row();
            }

            ScrollPane pane = new ScrollPane(scrollTable, Styles.smallPane);
            pane.setScrollingDisabled(true, false);
            pane.setOverscroll(false, false);
            
            listDialog.cont.add(pane).width(450f).height(400f).row();
            listDialog.layout();
        }, err -> {
            listDialog.cont.clearChildren();
            listDialog.cont.add("[scarlet]Error fetching models:[]\n" + err).pad(20f).row();
            listDialog.layout();
        });
    }
}
