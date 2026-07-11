package advisor;

import arc.*;
import arc.files.*;
import arc.util.*;
import mindustry.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AdvisorLogger {
    private static Fi logFile;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    public static void init() {
        // Vars.dataDirectory points to ~/.local/share/Mindustry on Linux, 
        // Android data folder on Android, and %appdata% on Windows.
        // It is the only 100% safe, cross-platform directory with guaranteed write access.
        logFile = Vars.dataDirectory.child("ai-advisor-debug.log");
        
        // Wipe log if it gets too large (e.g. > 5MB)
        if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
            logFile.delete();
        }
        
        debug("=== AI Advisor Initialized ===");
    }
    
    public static void debug(String message) {
        if (logFile == null) return;
        String time = dateFormat.format(new Date());
        String line = "[" + time + "] [DEBUG] " + message + "\n";
        
        // Log to Mindustry console as well
        Log.info("[AI Advisor] " + message);
        
        // Append to file asynchronously to avoid freezing the game
        appendAsync(line);
    }
    
    public static void error(String message, Throwable t) {
        if (logFile == null) return;
        String time = dateFormat.format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(time).append("] [ERROR] ").append(message).append("\n");
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString()).append("\n");
        }
        
        Log.err("[AI Advisor] " + message, t);
        appendAsync(sb.toString());
    }
    
    public static void logConversation(String prompt, String context, String response) {
        if (logFile == null) return;
        String time = dateFormat.format(new Date());
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n======================================================\n");
        sb.append("[").append(time).append("] [CONVERSATION]\n");
        sb.append("--- USER PROMPT ---\n").append(prompt).append("\n\n");
        sb.append("--- GAME CONTEXT ---\n").append(context).append("\n\n");
        sb.append("--- AI RESPONSE ---\n").append(response).append("\n");
        sb.append("======================================================\n\n");
        
        appendAsync(sb.toString());
    }
    
    private static void appendAsync(String text) {
        Core.app.post(() -> {
            try {
                logFile.writeString(text, true);
            } catch (Exception e) {
                Log.err("Failed to write to ai-advisor log", e);
            }
        });
    }
}
