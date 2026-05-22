# ♟️ Checkmate

> **AI-powered accountability app for JEE / NEET / CUET aspirants.**  
> Not another study timer. Actual crack-the-exam intelligence.

---

## 📲 Download

| Version | Link |
|---|---|
| Latest APK | **[⬇ Download Checkmate.apk](https://github.com/your-username/checkmate/releases/latest)** |
| Release Notes | [Changelog](https://github.com/your-username/checkmate/releases) |

> **Android 8.0+** required. Enable "Install from unknown sources" before installing.

---

## What is Checkmate?

Most study apps give you a timer and call it productivity. Checkmate is different — it knows **who you are**, **what exam you're preparing for**, **which topics you're weak in**, **when your coaching tests are**, and generates a **specific, reasoned daily plan** around all of it.

It also makes sure you actually execute that plan — with a floating attention bar, distraction blocking, WhatsApp guardian reports, and an AI mentor that gives real feedback, not generic motivation.

---

## ✨ Features

### 🧠 Adaptive AI Planner
- Generates topic-specific tasks, not vague ones ("Torque — coaching test in 2 days, PYQ 6.2%, you marked weak")
- Powered by Groq (LLaMA 3.1) with rule-based fallback — works even offline
- Driven by your full profile: exam target, score gap, weak topics, stress level, available hours
- Brings in your coaching test schedule, daily topic check-in, and past behavior

### 👤 Student Consultation Profile
- One-time setup: exam, exam date, class, coaching name, target score, mock scores, weak subjects/topics, sleep hours, stress level, blocked time slots
- Drives every decision the planner makes — nothing is generic

### 📅 Daily Check-In
- Every morning: select today's topics from the official NTA syllabus (JEE / NEET / CUET)
- Rate yesterday's sessions, log your stress and sleep
- Resets at 3 AM, feeds directly into today's plan generation

### 📋 Coaching Schedule Upload
- Paste or type your coaching test + lecture schedule
- Groq extracts a structured schedule — upcoming tests, subjects, chapters
- Planner always knows what's coming in the next 7 days

### ⏱️ Attention Cycle System
- 30-min focus → 5-min break → 10-min long break
- Floating overlay bar with phase timer and confirm button
- **Pause / Resume** support — tracks real focus time, not wall clock time
- Stats show actual focus time, pause count, pause rate (fatigue signals)

### 🚫 WorkMode (Distraction Blocker)
- Blocks selected apps during study sessions
- Accessibility service-based — no root needed

### 💬 WhatsApp Guardian Reports
- Sends study session summaries to a parent/guardian via WhatsApp
- Runs in an isolated process — survives even if main app is killed

### 🤖 Mentor AI
- Chat interface with a mentor that knows your full situation
- Injected with: your profile, behavior ledger, PYQ weightage, exam strategy
- Gives specific, curriculum-aware guidance — not motivational quotes
- Knows JEE/NEET chapter difficulty, common misconceptions, spaced repetition strategy, burnout recovery

### 📊 Stats & Behavior Ledger
- Tracks skip rate, streak, attention cycles, pause patterns
- Behavior data feeds back into planner and mentor for personalized adjustments

### 🔊 Voice Feedback (TTS)
- Android TTS built-in, ElevenLabs as optional upgrade
- Planner speaks plan summary after generation
- Configurable via settings toggle

### 🔑 BYOK (Bring Your Own Key)
- Multi-provider LLM support: Groq, OpenRouter, Claude, Gemini
- You control your API keys — no subscription, no data sent to our servers

---

## 🏗️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9.22 |
| UI | Jetpack Compose |
| Architecture | Multi-module (core / planner / psyche / automation / callhandler) |
| Database | Room |
| LLM | Groq (LLaMA 3.1) · OpenRouter · Gemini · Claude |
| TTS | Android TTS · ElevenLabs (optional) |
| Build | AGP 8.2.2 · Gradle 8.4 · JDK 17 |

---

## 📁 Module Structure

```
checkmate/
├── app/                    # Main app, UI, services
│   ├── ui/home/            # Today screen, task cards
│   ├── ui/planner/         # Plan generation, daily check-in, coaching schedule
│   ├── ui/mentor/          # AI mentor chat
│   ├── ui/consultation/    # Student profile setup
│   ├── ui/stats/           # Focus stats
│   ├── service/            # AttentionCycle, FloatingBar, WhatsApp reporter
│   └── automation/         # WorkMode distraction blocker
├── modules/
│   ├── core/               # LLM gateway, ConsultationProfile, ExamSyllabus, MentorKnowledge
│   ├── planner/            # AdaptivePlanner, StudyTask, BehaviorLedger
│   ├── psyche/             # PsycheEngine behavioral tracking
│   ├── automation/         # App blocking engine
│   └── callhandler/        # Call management during sessions
```

---

## 🚀 Build from Source

**Prerequisites:** Android Studio Hedgehog+, JDK 17, Android SDK 34

```bash
git clone https://github.com/your-username/checkmate.git
cd checkmate
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

**Add your API key** in Settings → LLM Provider after first launch.

---

## ⚙️ Permissions Required

| Permission | Why |
|---|---|
| Accessibility Service | WorkMode distraction blocking |
| Notification Listener | WhatsApp guardian reports (isolated process) |
| Draw Over Other Apps | Floating attention bar |
| Foreground Service | Attention cycle timer |

---

## 🗺️ Roadmap

- [x] Adaptive AI planner with full student context
- [x] Pause/Resume with real focus time tracking
- [x] Coaching schedule upload + Groq extraction
- [x] Daily NTA syllabus check-in
- [x] AI Mentor screen
- [x] WhatsApp guardian reports
- [ ] Timeline view (study blocks on day strip)
- [ ] Offline on-device LLM fallback
- [ ] iOS port

---

## 📄 License

MIT License — see [LICENSE](LICENSE)

---

*Built for JEE / NEET / CUET aspirants who are serious about execution.*
