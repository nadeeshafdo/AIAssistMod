# AI Advisor

A context-aware AI assistant mod for [Mindustry](https://github.com/Anuken/Mindustry) powered by the Gemini API. Provides real-time strategic advice based on your current game state.

## Features

- **Context-aware analysis** — reads your live game state (resources, buildings, units, power grid) and feeds it to the AI
- **Token streaming** — responses appear incrementally as the AI generates them (SSE via `streamGenerateContent`)
- **Quick-action buttons** — Analyze, Defend, Build for instant strategic queries
- **Conversational chat** — ask open-ended questions about your game
- **Model selection** — fetch and choose any Gemini model that supports `generateContent`
- **Rate-limited** — 2s interval between requests to avoid API abuse
- **Auto-hiding HUD button** — the AI button hides when the panel is open, reappears when closed

### In-Game Command Execution

The AI can execute arbitrary in-game commands by wrapping them in `[!cmd]...[/cmd]` tags. Supported commands:

| Command | Description |
|---|---|
| `spawn <unit> [amount]` | Spawn units near core |
| `give <item> [amount]` | Add items to core (`*` for all) |
| `setwave <number>` | Set current wave |
| `heal` | Heal friendly units and core |
| `kill [all\|type]` | Kill enemy units / all / by type |
| `destroy [all\|range]` | Destroy enemy buildings |
| `research <name\|all>` | Unlock content |
| `god` | Toggle invulnerability |
| `instant` | Toggle instant build |
| `rain` | Toggle rain |
| `time <speed>` | Set time speed multiplier |
| `weather <type>` | Set weather (snow, rain, spore, none) |
| `team <name>` | Change player team |
| `gameover [team]` | End the game |
| `build <block> <x> <y>` | Place a block at tile coords |
| `unit <type>` | Spawn and take control of a unit |
| `clear` | Clear all friendly units, buildings, core items |
| `all` | Max everything (unlock all, items, god, instant) |
| `info` | Full game state dump |

Any unrecognized command is forwarded to Mindustry's built-in JavaScript console for **full unrestricted access** to the game engine.

## Setup

1. Get a free API key from [ai.google.dev](https://ai.google.dev)
2. Launch Mindustry with the mod installed
3. Click the **AI** button (bottom-left) to open the panel (button hides when panel is open)
4. Click the gear icon and paste your API key

## Build

### Desktop (testing only)

```bash
./gradlew jar
```

JAR will be at `build/libs/AIAssistModDesktop.jar`.

### Android + Desktop (release)

Requires Android SDK with `ANDROID_HOME` set and `d8` in PATH:

```bash
./gradlew deploy
```

JAR will be at `build/libs/AIAssistMod.jar`.

### CI

Push a tag matching `v*` to trigger a GitHub Release build with the artifact attached.

## Configuration

Settings are persisted via Mindustry's built-in settings system:
- **API Key** — saved under `ai-advisor-apikey`
- **Model** — saved under `ai-advisor-model` (default: `gemma-4-31b-it`)

## Project Structure

```
src/advisor/
├── AdvisorMod.java      # Mod entry point
├── AssistantUI.java     # In-game chat panel UI, streaming, command processing
├── GameContext.java      # Game state snapshot collector
├── GeminiClient.java     # Gemini API HTTP client (SSE streaming, model list)
└── CommandHandler.java   # In-game command execution + JS console fallback
```
