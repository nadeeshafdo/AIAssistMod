# AI Advisor

A context-aware AI assistant mod for [Mindustry](https://github.com/Anuken/Mindustry) powered by the Gemini API. Provides real-time strategic advice based on your current game state.

## Features

- **Context-aware analysis** — reads your live game state (resources, buildings, units, power grid) and feeds it to the AI
- **Quick-action buttons** — Analyze, Defend, Build for instant strategic queries
- **Conversational chat** — ask open-ended questions about your game
- **Model selection** — choose any Gemini model that supports `generateContent`
- **Rate-limited** — 2s interval between requests to avoid API abuse

## Setup

1. Get a free API key from [ai.google.dev](https://ai.google.dev)
2. Launch Mindustry with the mod installed
3. Click the **AI** button (bottom-left) to open the panel
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
├── AssistantUI.java     # In-game chat panel UI
├── GameContext.java      # Game state snapshot collector
└── GeminiClient.java     # Gemini API HTTP client
```
