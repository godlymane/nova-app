# Nova Routines

Routines are the behavioral backbone of Nova's proactive system.
These define *when* and *why* Nova reaches out — not just when you ask.

---

## Daily Routine Schedule

| Time | Trigger | Action |
|------|---------|--------|
| 8:00 AM | Every day (if morning check-in not done) | "Good morning. What are we locking in today?" |
| 6–9 PM | If no gym log for today | "Did you train today?" / streak warning |
| 7:00 PM | Every day (if night reflection not done) | "What was today's win?" |
| Any time | 3+ days since Blayzex mentioned | "How's Blayzex going?" |
| Mid-day (10 AM–6 PM) | Stress keywords in last 24h memories | "You seemed off recently. Better today?" |

---

## Workflow Triggers Reference

### Fitness
| Phrase | Workflow |
|--------|----------|
| "at the gym", "gym time", "going gym" | Gym Check-in |
| "workout done", "gym done", "done training" | Workout Complete |
| "weigh in", "morning weight", "weighed myself" | Cutting Update |
| "diet check", "macro check", "calories today" | Diet Check-in |

### Productivity
| Phrase | Workflow |
|--------|----------|
| "good morning", "gm", "just woke up" | Morning Routine |
| "focus mode", "locking in", "locked in" | Deep Focus Session |
| "plan my day", "daily plan" | Daily Planning |
| "weekly review", "week recap" | Weekly Review |
| "good night", "going to sleep" | Night Reflection |

### Business
| Phrase | Workflow |
|--------|----------|
| "blayzex update", "business update", "revenue update" | Blayzex Business Update |

### Emotional
| Phrase | Workflow |
|--------|----------|
| "feeling stressed", "stressed out", "overwhelmed" | Stress Check-in |
| "not motivated", "no motivation", "feeling lazy" | Motivation Boost |

### Coding
| Phrase | Workflow |
|--------|----------|
| "coding session", "dev mode", "shipping today" | Coding Session Start |
| "got a bug", "something broke", "help debug" | Debug Help |

---

## Memory Categories

| Category | What's tracked |
|----------|----------------|
| `fitness` | PRs, body weight, gym sessions, cutting/bulking phase |
| `business` | Blayzex updates, revenue mentions, client/product updates |
| `emotional` | Stress, mood, motivation, anxiety |
| `coding` | Projects, bugs, tech stack, shipping |
| `goals` | Goals, deadlines, targets |
| `personal` | Name, age, relationships, anime, sleep |

---

## Fact Keys (Structured Memory)

| Key | Category | Example Value |
|-----|----------|---------------|
| `name` | personal | Devdatta |
| `age` | personal | 20 |
| `location` | personal | Hyderabad |
| `bench_press_pr` | fitness | 100 kg |
| `squat_pr` | fitness | 120 kg |
| `deadlift_pr` | fitness | 140 kg |
| `body_weight` | fitness | 78 kg |
| `fitness_phase` | fitness | cutting |
| `startup_name` | business | Blayzex |
| `last_revenue_mention` | business | $500 |
| `girlfriend_name` | relationship | — |
| `brother_name` | relationship | Arjun |
| `current_project` | coding | nova-app |
| `college` | personal | — |
| `favorite_food` | preference | biryani |
| `music_taste` | preference | — |

---

## Proactive Check Frequency

The `ProactiveCheckWorker` runs every **2 hours** via WorkManager.
It checks all conditions above and fires at most one notification per category per day.

To adjust frequency: change `INTERVAL_HOURS` in `ProactiveCheckWorker.kt`.
