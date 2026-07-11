package advisor;

import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.*;

/**
 * Collects a structured snapshot of the current game state
 * for use as AI context.
 */
public class GameContext {

    /** Build a full text snapshot of the current game state. */
    public static String collect() {
        if (!Vars.state.isPlaying()) {
            return "[Not in game] The player is currently in the main menu.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== GAME STATE ===\n");
        appendGameInfo(sb);
        sb.append("\n=== SIMULATION TIME ===\n");
        appendGameTime(sb);
        sb.append("\n=== RESOURCES IN CORE ===\n");
        appendResources(sb);
        sb.append("\n=== BUILDINGS & POWER ===\n");
        appendBuildingsAndPower(sb);
        sb.append("\n=== TECH TREE ===\n");
        appendTechTree(sb);
        sb.append("\n=== SECTOR ===\n");
        appendSector(sb);
        return sb.toString();
    }

    private static void appendGameInfo(StringBuilder sb) {
        sb.append("Mode: ").append(Vars.state.rules.mode().name()).append("\n");
        sb.append("Map: ").append(Vars.state.map != null ? Vars.state.map.name() : "unknown").append("\n");
        sb.append("Wave: ").append(Vars.state.wave).append("\n");

        if (Vars.state.rules.waveTimer) {
            float secs = Vars.state.wavetime / 60f;
            sb.append("Next wave in: ").append(String.format("%.0f", secs)).append("s\n");
        }

        if (Vars.player != null && Vars.player.team() != null) {
            sb.append("Team: ").append(Vars.player.team().name).append("\n");
        }

        // Core health
        Team team = playerTeam();
        if (team != null && team.core() != null) {
            Building core = team.core();
            sb.append("Core health: ").append(String.format("%.0f/%.0f", core.health, core.maxHealth)).append("\n");
        }
    }

    private static void appendGameTime(StringBuilder sb) {
        double tick = Vars.state.tick;
        long totalSecs = (long)(tick / 60.0);
        long mins = totalSecs / 60;
        long secs = totalSecs % 60;
        sb.append("Elapsed: ").append(mins).append("m ").append(secs).append("s (")
          .append((long)tick).append(" ticks)\n");
    }

    private static void appendTechTree(StringBuilder sb) {
        int unlockedBlocks = 0, totalBlocks = 0;
        int unlockedUnits = 0, totalUnits = 0;
        int unlockedItems = 0, totalItems = 0;
        int unlockedLiquids = 0, totalLiquids = 0;

        for (Block block : Vars.content.blocks()) {
            if (block.isHidden()) continue;
            totalBlocks++;
            if (block.unlocked()) unlockedBlocks++;
        }
        for (UnitType type : Vars.content.units()) {
            if (type.isHidden()) continue;
            totalUnits++;
            if (type.unlocked()) unlockedUnits++;
        }
        for (Item item : Vars.content.items()) {
            if (item.isHidden()) continue;
            totalItems++;
            if (item.unlocked()) unlockedItems++;
        }
        for (Liquid liquid : Vars.content.liquids()) {
            if (liquid.isHidden()) continue;
            totalLiquids++;
            if (liquid.unlocked()) unlockedLiquids++;
        }

        sb.append("Blocks: ").append(unlockedBlocks).append("/").append(totalBlocks).append("\n");
        sb.append("Units: ").append(unlockedUnits).append("/").append(totalUnits).append("\n");
        sb.append("Items: ").append(unlockedItems).append("/").append(totalItems).append("\n");
        sb.append("Liquids: ").append(unlockedLiquids).append("/").append(totalLiquids).append("\n");
    }

    private static void appendSector(StringBuilder sb) {
        Sector sector = Vars.state.rules.sector;
        if (sector != null) {
            sb.append("Current sector: ").append(sector.name()).append("\n");
            sb.append("Planet: ").append(sector.planet.name).append("\n");
            sb.append("Threat: ").append(sector.threat).append("\n");
            if (sector.hasEnemyBase()) {
                sb.append("Enemy base present\n");
            }
        } else {
            sb.append("Not in campaign mode.\n");
        }
    }

    private static void appendResources(StringBuilder sb) {
        Team team = playerTeam();
        if (team == null || team.core() == null) {
            sb.append("No core available.\n");
            return;
        }

        var core = team.core();
        boolean any = false;
        for (Item item : Vars.content.items()) {
            int amount = core.items.get(item);
            if (amount > 0) {
                sb.append(item.localizedName).append(": ").append(amount).append("\n");
                any = true;
            }
        }
        if (!any) sb.append("Empty.\n");
    }

    private static void appendBuildingsAndPower(StringBuilder sb) {
        Team team = playerTeam();
        if (team == null) {
            sb.append("No buildings or power data.\n");
            return;
        }

        OrderedMap<String, ObjectIntMap<String>> categories = new OrderedMap<>();
        float totalProduction = 0;
        float totalConsumption = 0;
        int generators = 0;
        int consumers = 0;

        Groups.build.each(b -> {
            if (b.team == team) {
                // Building counts
                String cat = b.block.category != null ? b.block.category.name() : "other";
                categories.get(cat, ObjectIntMap::new).increment(b.block.localizedName);

                // Power calculations
                if (b.power != null) {
                    if (b.block.consPower != null) {
                        float usage = b.block.consPower.usage;
                        if (usage > 0) {
                            totalConsumption += usage * 60f;
                            consumers++;
                        }
                    }

                    if (b instanceof PowerGenerator.GeneratorBuild gen) {
                        totalProduction += gen.productionEfficiency * ((PowerGenerator) b.block).powerProduction * 60f;
                        generators++;
                    }
                }
            }
        });

        if (categories.isEmpty()) {
            sb.append("No buildings.\n");
        } else {
            for (var catEntry : categories.entries()) {
                sb.append("[").append(capitalize(catEntry.key)).append("] ");
                Seq<String> entryList = new Seq<>();
                for (var e : catEntry.value.entries()) {
                    entryList.add(e.key + " x" + e.value);
                }
                sb.append(entryList.toString(", ")).append("\n");
            }
        }

        sb.append("\n[Power Grid]\n");
        sb.append("Generators: ").append(generators).append(" (est. ").append(String.format("%.1f", totalProduction)).append("/s)\n");
        sb.append("Consumers: ").append(consumers).append(" (est. ").append(String.format("%.1f", totalConsumption)).append("/s)\n");
        float balance = totalProduction - totalConsumption;
        sb.append("Balance: ").append(balance >= 0 ? "+" : "").append(String.format("%.1f", balance)).append("/s\n");
    }

    private static Team playerTeam() {
        return Vars.player != null ? Vars.player.team() : null;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
