package advisor;

import arc.math.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

public class CommandHandler {

    public static String execute(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "Empty command.";

        String[] parts = trimmed.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        return switch (cmd) {
            case "spawn" -> cmdSpawn(parts);
            case "give" -> cmdGive(parts);
            case "setwave" -> cmdSetWave(parts);
            case "heal" -> cmdHeal();
            default -> "Unknown command: " + cmd + ". Available: spawn, give, setwave, heal";
        };
    }

    private static String cmdSpawn(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) return "Usage: spawn <unitName> [amount]";
        String unitName = parts[1].toLowerCase();
        int amount = 1;
        if (parts.length >= 3) {
            try { amount = Math.min(Math.max(Integer.parseInt(parts[2]), 1), 100); }
            catch (NumberFormatException e) { return "Invalid amount."; }
        }

        UnitType type = findUnit(unitName);
        if (type == null) return "Unknown unit: " + unitName;

        Team team = Vars.player.team();
        Building core = team.core();
        if (core == null) return "No core to spawn at.";

        int spawned = 0;
        for (int i = 0; i < amount; i++) {
            float angle = Mathf.random(360f);
            float dist = Mathf.random(12f, 24f);
            float x = core.x + Mathf.cosDeg(angle) * dist * 8f;
            float y = core.y + Mathf.sinDeg(angle) * dist * 8f;
            Unit unit = type.spawn(team, x, y);
            if (unit != null) spawned++;
        }
        return "Spawned " + spawned + "x " + type.localizedName + " near core.";
    }

    private static String cmdGive(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) return "Usage: give <itemName> [amount]";

        boolean all = parts[1].equals("*") || parts[1].equalsIgnoreCase("all");
        int amount = 1000;
        if (parts.length >= 3) {
            try { amount = Math.min(Math.max(Integer.parseInt(parts[2]), 1), 99999); }
            catch (NumberFormatException e) { return "Invalid amount."; }
        }

        Team team = Vars.player.team();
        Building core = team.core();
        if (core == null) return "No core.";

        if (all) {
            int given = 0;
            for (Item item : Vars.content.items()) {
                core.items.add(item, amount);
                given++;
            }
            return "Gave " + amount + " of all " + given + " item types to core.";
        }

        Item item = findItem(parts[1]);
        if (item == null) return "Unknown item: " + parts[1];
        core.items.add(item, amount);
        return "Gave " + amount + "x " + item.localizedName + " to core.";
    }

    private static String cmdSetWave(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) return "Usage: setwave <number>";
        try {
            int wave = Math.max(1, Integer.parseInt(parts[1]));
            Vars.state.wave = wave;
            Vars.state.wavetime = 60f * 30f;
            return "Wave set to " + wave + ".";
        } catch (NumberFormatException e) {
            return "Invalid wave number.";
        }
    }

    private static String cmdHeal() {
        if (!canExecute()) return "Not in game.";
        Team team = Vars.player.team();

        Building core = team.core();
        if (core != null) {
            core.heal(core.maxHealth);
        }

        int healed = 0;
        for (Unit unit : Groups.unit) {
            if (unit.team == team) {
                unit.heal(unit.maxHealth);
                healed++;
            }
        }

        return "Healed " + healed + " units" + (core != null ? " and the core." : ".");
    }

    private static UnitType findUnit(String name) {
        for (UnitType type : Vars.content.units()) {
            if (type.name.equalsIgnoreCase(name) ||
                type.localizedName.replace(" ", "").equalsIgnoreCase(name) ||
                type.localizedName.toLowerCase().contains(name)) {
                return type;
            }
        }
        return null;
    }

    private static Item findItem(String name) {
        for (Item item : Vars.content.items()) {
            if (item.name.equalsIgnoreCase(name) ||
                item.localizedName.replace(" ", "").equalsIgnoreCase(name) ||
                item.localizedName.toLowerCase().contains(name)) {
                return item;
            }
        }
        return null;
    }

    private static boolean canExecute() {
        return Vars.state != null && Vars.state.isPlaying() && Vars.player != null;
    }

    public static String helpText() {
        return """
            You can execute in-game commands by wrapping them in [!cmd]...[/cmd] tags.
            Available commands:
              spawn <unit> [amount]   — Spawn units near the core
              give <item> [amount]    — Add items to the core (use '*' for all items)
              setwave <number>        — Set the current wave
              heal                    — Heal all player units and core
            Example: [!cmd]spawn dagger 5[/cmd]""";
    }
}
