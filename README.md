# IqraX Companion AI — Autonomous Android Phone Controller & Emotional Assistant

An on-device, autonomous AI Companion application for Android that controls phone apps, auto-taps, types, scrolls, and speaks with emotional intelligence using **Google Gemini 2.0 Multimodal AI** and **Android Accessibility Services**.

---

## 🌟 Key Architecture & Capabilities

1. **Auto-Touch & Device Autonomy (`CompanionAccessibilityService.kt`)**:
   - Programmatic tap/click dispatching at screen coordinates `(x, y)` without root or PC.
   - Screen UI hierarchy inspection (buttons, labels, input fields, bounds).
   - Automated text typing into WhatsApp, YouTube, Settings, and any app.
   - System keys execution (`BACK`, `HOME`, `RECENTS`).
   - Direct package launching.

2. **The Glowing Floating Orb (`FloatingOverlayService.kt`)**:
   - Always-on-top draggable floating bubble on top of any app.
   - Dynamic states: `Idle`, `Listening`, `Thinking`, `Acting`, `Speaking`.
   - TextToSpeech integration with warm, empathetic voice feedback.

3. **Autonomous Agent Loop (`GeminiAgentBrain.kt`)**:
   - Multi-step observe-think-act loop powered by Gemini 2.0.
   - Reads current screen state -> plans next tool call -> dispatches gesture -> verifies screen transition.

4. **Emotional Intelligence & Persona (`EmotionalCompanionPersona.kt`)**:
   - Empathetic, supportive, sharp personality that speaks natural Hinglish/English.

5. **On-Device Memory (`CompanionDatabase.kt`)**:
   - Room SQLite database to remember user preferences, frequent contacts, alarms, and daily routines.

---

## 📱 How to Run & Build APK

### Option A: Open in Android Studio
1. Copy or git-clone `/projects/iqrax-companion-ai` to your PC/laptop.
2. Open with **Android Studio (Hedgehog / Iguana / Koala / Ladybug)**.
3. Click **Build -> Build APK** or connect your phone via USB and click **Run**.

### Option B: On-Device Terminal Testing
- Grant Accessibility Service in phone settings: **Settings -> Accessibility -> Installed Services -> IqraX Companion**.
- Grant Overlay permission: **Settings -> Apps -> Special App Access -> Display over other apps**.
