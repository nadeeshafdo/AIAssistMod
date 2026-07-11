package advisor;

import arc.math.*;
import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

public class CommandHandler {

    public static String execute(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "Empty command.";
        
        AdvisorLogger.debug("Executing command: " + raw);

        String[] parts = trimmed.split("\\s+", 4);
        String cmd = parts[0].toLowerCase();

        String result = switch (cmd) {
            case "spawn" -> cmdSpawn(parts);
            case "give" -> cmdGive(parts);
            case "setwave" -> cmdSetWave(parts);
            case "heal" -> cmdHeal();
            case "kill" -> cmdKill(parts);
            case "destroy" -> cmdDestroy(parts);
            case "research", "unlock" -> cmdResearch(parts);
            case "god" -> cmdGod();
            case "instant" -> cmdInstant();
            case "rain" -> cmdRain();
            case "time" -> cmdTime(parts);
            case "weather" -> cmdWeather(parts);
            case "team" -> cmdTeam(parts);
            case "gameover" -> cmdGameOver(parts);
            case "build" -> cmdBuild(parts);
            case "unit" -> cmdUnit(parts);
            case "clear" -> cmdClear();
            case "all" -> cmdAll();
            case "info" -> cmdInfo();
            default -> {
                String scriptResult = tryScript(trimmed);
                yield scriptResult != null ? scriptResult
                    : "Unknown command: " + cmd + ". Wrap any command in [!cmd]...[/cmd].";
            }
        };
        
        AdvisorLogger.debug("Command [" + cmd + "] result: " + result);
        return result;
    }

    // --- SPAWN ---
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

    // --- GIVE ---
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

    // --- SETWAVE ---
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

    // --- HEAL ---
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

    // --- KILL ---
    private static String cmdKill(String[] parts) {
        if (!canExecute()) return "Not in game.";
        Team team = Vars.player.team();
        int killed = 0;

        if (parts.length >= 2 && parts[1].equalsIgnoreCase("all")) {
            for (Unit unit : Groups.unit) {
                unit.kill();
                killed++;
            }
            return "Killed all " + killed + " units.";
        }

        String target = parts.length >= 2 ? parts[1].toLowerCase() : null;
        if (target == null) {
            for (Unit unit : Groups.unit) {
                if (unit.team != team) {
                    unit.kill();
                    killed++;
                }
            }
            return "Killed " + killed + " enemy units.";
        }

        for (Unit unit : Groups.unit) {
            String name = unit.type.name.toLowerCase();
            String localized = unit.type.localizedName.toLowerCase().replace(" ", "");
            if (name.equals(target) || localized.equals(target) || localized.contains(target)) {
                unit.kill();
                killed++;
            }
        }
        return "Killed " + killed + "x " + parts[1] + ".";
    }

    // --- DESTROY ---
    private static String cmdDestroy(String[] parts) {
        if (!canExecute()) return "Not in game.";
        Team team = Vars.player.team();

        if (parts.length >= 2 && parts[1].equalsIgnoreCase("all")) {
            int destroyed = 0;
            for (Building b : Groups.build) {
                if (b.team != team && !(b.block instanceof CoreBlock)) {
                    b.kill();
                    destroyed++;
                }
            }
            return "Destroyed " + destroyed + " enemy buildings.";
        }

        float range = 120f;
        if (parts.length >= 2) {
            try { range = Float.parseFloat(parts[1]) * 8f; }
            catch (NumberFormatException ignored) {}
        }

        Building core = team.core();
        if (core == null) return "No core as center point.";

        int destroyed = 0;
        for (Building b : Groups.build) {
            if (b.team != team && b.dst(core) <= range && !(b.block instanceof CoreBlock)) {
                b.kill();
                destroyed++;
            }
        }
        return "Destroyed " + destroyed + " enemy buildings within range.";
    }

    // --- RESEARCH (via JS console — protected field access) ---
    private static String cmdResearch(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) return "Usage: research <name|all>";

        if (parts[1].equalsIgnoreCase("all") || parts[1].equals("*")) {
            return tryScript(
                "var c=Vars.content;" +
                "c.blocks().each(b=>b.unlocked=true);" +
                "c.units().each(u=>u.unlocked=true);" +
                "c.items().each(i=>i.unlocked=true);" +
                "c.liquids().each(l=>l.unlocked=true);" +
                "'Unlocked everything.'");
        }

        String name = parts[1];
        return tryScript(
            "var n='" + jsStr(name) + "'.toLowerCase();" +
            "var c=Vars.content;" +
            "function tryResolve(arr){" +
            "  for(var i=0;i<arr.size;i++){" +
            "    var x=arr.get(i);" +
            "    if(x.name.toLowerCase().contains(n)||x.localizedName.toLowerCase().replace(' ','').contains(n)){" +
            "      x.unlocked=true;" +
            "      return x.localizedName;" +
            "  }}" +
            "  return null;" +
            "}" +
            "var r=tryResolve(c.blocks())||tryResolve(c.units())||tryResolve(c.items())||tryResolve(c.liquids());" +
            "r?'Researched '+r:'Unknown: " + jsStr(name) + "'");
    }

    // --- GOD (via JS console) ---
    private static String cmdGod() {
        if (!canExecute()) return "Not in game.";
        return tryScript(
            "Vars.state.rules.invulnerable=!Vars.state.rules.invulnerable;" +
            "if(Vars.player)Vars.player.invulnerable=Vars.state.rules.invulnerable;" +
            "Vars.state.rules.invulnerable?'God mode ON.':'God mode OFF.'");
    }

    // --- INSTANT ---
    private static String cmdInstant() {
        if (!canExecute()) return "Not in game.";
        Vars.state.rules.instantBuild = !Vars.state.rules.instantBuild;
        return "Instant build " + (Vars.state.rules.instantBuild ? "ON." : "OFF.");
    }

    // --- RAIN (via JS console) ---
    private static String cmdRain() {
        if (!canExecute()) return "Not in game.";
        return tryScript(
            "Vars.state.rules.rain=!Vars.state.rules.rain;" +
            "Vars.state.rules.rain?'Rain ON.':'Rain OFF.'");
    }

    // --- TIME (via JS console) ---
    private static String cmdTime(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) {
            return tryScript("'Time speed: '+Vars.state.rules.speedMultiplier+'x'");
        }
        try {
            float speed = Float.parseFloat(parts[1]);
            return tryScript(
                "Vars.state.rules.speedMultiplier=Math.max(0," + speed + ");" +
                "'Time speed set to "+speed+"x.'");
        } catch (NumberFormatException e) {
            return "Invalid speed.";
        }
    }

    // --- WEATHER (via JS console) ---
    private static String cmdWeather(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) return "Usage: weather <type>. Types: snow, rain, spore, none";
        String type = parts[1].toLowerCase();
        return switch (type) {
            case "snow" -> tryScript(
                "Vars.state.rules.rain=false;Vars.state.rules.snow=true;Vars.state.rules.spores=false;'Weather set to snow.'");
            case "rain" -> tryScript(
                "Vars.state.rules.rain=true;Vars.state.rules.snow=false;Vars.state.rules.spores=false;'Weather set to rain.'");
            case "spore" -> tryScript(
                "Vars.state.rules.rain=false;Vars.state.rules.snow=false;Vars.state.rules.spores=true;'Weather set to spore.'");
            case "none", "clear" -> tryScript(
                "Vars.state.rules.rain=false;Vars.state.rules.snow=false;Vars.state.rules.spores=false;'Weather cleared.'");
            default -> "Unknown weather: " + type + ". Options: snow, rain, spore, none";
        };
    }

    // --- TEAM ---
    private static String cmdTeam(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) return "Usage: team <name>. Teams: blue, red, green, purple, etc.";

        String name = parts[1].toLowerCase();
        for (Team t : Team.all) {
            if (t.name.equalsIgnoreCase(name)) {
                Vars.player.team(t);
                return "Switched to team " + t.name + ".";
            }
        }
        return "Unknown team: " + name;
    }

    // --- GAMEOVER ---
    private static String cmdGameOver(String[] parts) {
        if (!canExecute()) return "Not in game.";
        Team winner = Vars.player.team();
        if (parts.length >= 2) {
            for (Team t : Team.all) {
                if (t.name.equalsIgnoreCase(parts[1])) {
                    winner = t;
                    break;
                }
            }
        }
        Vars.state.gameOver = true;
        return "Game over! " + winner.name + " wins.";
    }

    // --- BUILD ---
    private static String cmdBuild(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 4) return "Usage: build <blockName> <x> <y>";

        Block block = findBlock(parts[1]);
        if (block == null) return "Unknown block: " + parts[1];

        try {
            float x = Float.parseFloat(parts[2]) * 8f;
            float y = Float.parseFloat(parts[3]) * 8f;
            Tile tile = Vars.world.tileWorld(x, y);
            if (tile == null) return "Invalid tile position.";
            tile.setBlock(block, Vars.player.team(), 0);
            return "Placed " + block.localizedName + " at (" + parts[2] + ", " + parts[3] + ").";
        } catch (NumberFormatException e) {
            return "Invalid coordinates.";
        }
    }

    // --- UNIT ---
    private static String cmdUnit(String[] parts) {
        if (!canExecute()) return "Not in game.";
        if (parts.length < 2) return "Usage: unit <unitName>";

        UnitType type = findUnit(parts[1]);
        if (type == null) return "Unknown unit: " + parts[1];

        Team team = Vars.player.team();
        Building core = team.core();
        if (core == null) return "No core.";

        float x = core.x + Mathf.random(-32f, 32f);
        float y = core.y + Mathf.random(-32f, 32f);
        Unit unit = type.spawn(team, x, y);
        if (unit == null) return "Failed to spawn unit.";

        Vars.player.unit(unit);
        return "Now controlling " + type.localizedName + ".";
    }

    // --- CLEAR ---
    private static String cmdClear() {
        if (!canExecute()) return "Not in game.";
        Team team = Vars.player.team();

        int units = 0, builds = 0;
        for (Unit unit : Groups.unit) {
            if (unit.team == team) {
                unit.kill();
                units++;
            }
        }
        for (Building b : Groups.build) {
            if (b.team == team && !(b.block instanceof CoreBlock)) {
                b.kill();
                builds++;
            }
        }

        Building core = team.core();
        if (core != null) {
            for (Item item : Vars.content.items()) {
                core.items.remove(item, core.items.get(item));
            }
        }

        return "Cleared " + units + " units, " + builds + " buildings, and core items.";
    }

    // --- ALL ---
    private static String cmdAll() {
        if (!canExecute()) return "Not in game.";

        StringBuilder report = new StringBuilder();

        report.append(tryScript(
            "var c=Vars.content;" +
            "c.blocks().each(b=>b.unlocked=true);" +
            "c.units().each(u=>u.unlocked=true);" +
            "c.items().each(i=>i.unlocked=true);" +
            "c.liquids().each(l=>l.unlocked=true);" +
            "'Unlocked everything.'")).append("\n");

        Team team = Vars.player.team();
        Building core = team.core();
        if (core != null) {
            for (Item item : Vars.content.items()) {
                core.items.add(item, 9999);
            }
            report.append("Given 9999 of all items.\n");
        }

        report.append(tryScript(
            "Vars.state.rules.invulnerable=true;" +
            "if(Vars.player)Vars.player.invulnerable=true;" +
            "'God mode enabled.'")).append("\n");

        Vars.state.rules.instantBuild = true;
        report.append("Instant build enabled.");

        return report.toString();
    }

    // --- INFO ---
    private static String cmdInfo() {
        return GameContext.collect();
    }

    // --- SCRIPT FALLBACK ---
    private static String tryScript(String code) {
        try {
            if (Vars.mods != null && Vars.mods.getScripts() != null) {
                Object result = Vars.mods.getScripts().runConsole(code);
                return (result != null) ? result.toString() : "Executed via script console.";
            }
        } catch (Exception e) {
            return "Script error: " + e.getMessage();
        }
        return null;
    }

    // --- LOOKUP HELPERS ---
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

    private static Block findBlock(String name) {
        String n = name.toLowerCase();
        for (Block block : Vars.content.blocks()) {
            if (block.name.equalsIgnoreCase(n) ||
                block.localizedName.replace(" ", "").equalsIgnoreCase(n) ||
                block.localizedName.toLowerCase().contains(n)) {
                return block;
            }
        }
        return null;
    }

    private static String jsStr(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean canExecute() {
        return Vars.state != null && Vars.state.isCampaign() && Vars.player != null;
    }

    public static String helpText() {
        return """
            Execute in-game commands via [!cmd]...[/cmd] tags.
            All commands:
              spawn <unit> [amount]   — Spawn units near core
              give <item> [amount]    — Add items to core ('*' for all)
              setwave <number>        — Set current wave
              heal                    — Heal friendly units and core
              kill [all|type]         — Kill units (enemy / all / by type)
              destroy [all|range]     — Destroy enemy buildings
              research <name|all>     — Unlock content
              unlock <name|all>       — Alias for research
              god                     — Toggle invulnerability
              instant                 — Toggle instant build
              rain                    — Toggle rain
              time <speed>            — Set time speed multiplier
              weather <type>          — Set weather (snow, rain, spore, none)
              team <name>             — Change player team
              gameover [team]         — End the game
              build <block> <x> <y>   — Place a block at tile coords
              unit <type>             — Spawn and control a unit
              clear                   — Clear all friendly units/buildings/items
              all                     — Unlock all, max items, god mode
              info                    — Show current game state
            Unrecognized commands are forwarded to the built-in JavaScript console for full unrestricted access.""";
    }
}
