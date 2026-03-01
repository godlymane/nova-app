# Nova Knowledge Base

## What is Nova?

Nova is a personal AI companion built for Android. It lives in your pocket and acts as a brutally honest, always-available AI that knows you — your goals, habits, struggles, and wins — and responds accordingly.

Nova is not a generic chatbot. It's built around deep memory, proactive engagement, and zero BS responses.

---

## Core Features

### 1. Deep Memory System

Nova remembers everything you tell it — structured as facts, not just chat logs.

**Memory types:**
- **UserFact** — Structured key-value facts (`name: Devdatta`, `bench_press_pr: 100kg`, `startup_name: Blayzex`)
- **Memory** — Free-text snippets from conversations, scored by importance and recency
- **UserProfile** — Core profile fields (name, age, location, occupation, fitness phase)
- **DailySummary** — Auto-generated end-of-day summaries across categories

**How it works:**
After every conversation exchange, `MemoryManager.extractMemories()` and `extractAndStoreFacts()` run automatically. Keywords and regex patterns identify fitness PRs, emotional states, business updates, relationship mentions, and more.

When building the next response, `buildContext()` injects:
1. User profile
2. Structured facts (deep memory)
3. Relevant past memories (scored by keyword match + recency + access frequency)
4. Recent daily summaries

---

### 2. Workflow Templates

Workflows are pre-built multi-step response sequences triggered by user input.

**How it works:**
1. User sends message → `WorkflowMatcher.match()` checks against all trigger phrases
2. If matched → `WorkflowExecutor.execute()` runs the workflow step by step
3. Each step can be: `message` (response text), `log` (record event), `api_call`, `recall_memories`, or `daily_brief`
4. Workflow bypasses the LLM API for these templated responses

**Example triggers:**
- `"at the gym"` → Gym Check-in workflow
- `"good morning"` → Morning Routine workflow
- `"focus mode"` → Deep Focus Session workflow
- `"feeling stressed"` → Stress Check-in workflow

**Categories:** fitness, productivity, business, emotional, coding

---

### 3. Task Bubble Overlay

When a workflow or long task runs, a floating bubble appears on screen showing real-time progress.

**How it works:**
- `TaskBubbleService` is a foreground service with `SYSTEM_ALERT_WINDOW` permission
- `TaskProgressManager` is a singleton `StateFlow` — anyone can push progress updates to it
- The bubble auto-dismisses 3 seconds after completion or failure
- Requires overlay permission (prompted on first launch)

---

### 4. Proactive Engine

Nova doesn't wait to be messaged. It fires contextual reminders and check-ins based on behavior patterns.

**Trigger examples:**
- 8 AM daily → Morning check-in notification
- 7 PM → Evening reflection prompt
- 6–9 PM with no gym log → "Did you train today?"
- 3+ days since Blayzex mentioned → "How's Blayzex going?"
- Stress keywords in last 24h → "You seemed off recently. Better today?"

**How it works:**
- `ProactiveCheckWorker` (WorkManager `PeriodicWorkRequest`) runs every 2 hours
- Queries memory DB for recent logs and keywords
- Posts notifications via `ProactiveNotificationHelper`
- Tapping a notification opens `MainActivity` with the proactive message pre-injected

---

### 5. ElevenLabs ConvAI Integration

Nova uses ElevenLabs' conversational AI API with a custom system prompt built from memory context.

**API details:**
- Endpoint: `https://api.elevenlabs.io/v1/convai/conversation`
- Model: `grok-3-mini-fast`
- Auth: `xi-api-key` header
- Max tokens: 300
- Temperature: 0.85

API key is stored in `SharedPreferences` under `nova_prefs / elevenlabs_api_key`.

---

## Database Schema (Room)

| Table | Purpose |
|---|---|
| `conversations` | Active conversation sessions |
| `messages` | Individual messages per conversation |
| `memories` | Free-text memory snippets |
| `user_facts` | Structured key-value facts (deep memory) |
| `user_profile` | Core profile fields |
| `daily_summaries` | Auto-generated daily summaries |

DB version: **3** (added `user_facts` table)

---

## File Structure

```
app/src/main/java/com/nova/companion/
├── data/
│   ├── entity/          # Room entities (UserFact, Memory, etc.)
│   ├── dao/             # DAOs (UserFactDao, MemoryDao, etc.)
│   └── NovaDatabase.kt  # DB singleton
├── memory/
│   └── MemoryManager.kt # All memory logic
├── ui/chat/
│   └── ChatViewModel.kt # Orchestrates everything
├── overlay/bubble/
│   ├── TaskProgressState.kt
│   ├── TaskProgressManager.kt
│   └── TaskBubbleService.kt
├── workflows/
│   ├── WorkflowTemplate.kt
│   ├── WorkflowRegistry.kt
│   ├── WorkflowMatcher.kt
│   └── WorkflowExecutor.kt
├── proactive/
│   ├── ProactiveNotificationHelper.kt
│   └── ProactiveCheckWorker.kt
├── NovaApp.kt
└── MainActivity.kt
```

---

## Known Limitations

- Memory extraction uses regex/keyword matching only — no ML/NLP
- Workflow log entries are not yet persisted to DB (placeholder)
- `daily_brief` and `recall_memories` workflow steps are stubs
- No voice input yet
- Overlay bubble requires manual layout file (`overlay_task_bubble.xml`) — not yet committed
