# KARGATHRA & Co. — Android Timegrapher App
## Comprehensive Technical Requirements Specification
**Target Device:** Google Pixel 8 Pro  
**Platform:** Android (minSdk 26, targetSdk 35)  
**Language:** Kotlin / Jetpack Compose (UI), C++ (audio/DSP via JNI)  
**Version:** 1.2 — Input Source Policy Revised (Built-In Default, Opt-In USB-C, Silent Bluetooth Filter)

---

## Table of Contents

1. [Audio Capture Module](#1-audio-capture-module)
2. [DSP & Detection Module](#2-dsp--detection-module)
3. [Timing & BPH Module](#3-timing--bph-module)
4. [Amplitude & Escapement Module](#4-amplitude--escapement-module)
5. [Visualization & UI Module](#5-visualization--ui-module)
6. [Branding & Visual Identity](#6-branding--visual-identity)
7. [User Experience & Interaction](#7-user-experience--interaction)
8. [Build, Signing & Distribution](#8-build-signing--distribution)
9. [Permissions & Runtime Device Handling](#9-permissions--runtime-device-handling)

Appendices: [A — Standard BPH Reference](#appendix-a--standard-bph-reference-table) · [B — Formulas](#appendix-b--key-formulas-summary) · [C — Glossary](#appendix-c--glossary) · [D — Changelog](#appendix-d--changelog)

---

## 1. Audio Capture Module

### REQ-1.1 — Audio API & Architecture

**Requirement:**  
The app MUST use Google's **Oboe C++ library**, which wraps the AAudio API on modern Android, as the exclusive audio capture backend. The app MUST NOT use standard Kotlin or Java audio classes (e.g., `AudioRecord`, `MediaRecorder`) for live measurement capture.

**Rationale:**  
Standard Kotlin/Java audio APIs introduce unacceptable scheduling latency caused by the JVM and Android's audio subsystem buffering. Oboe's AAudio backend enables direct memory-mapped (MMAP) access to the hardware audio buffer, reducing round-trip latency to the low single-digit millisecond range. This low latency is a hard prerequisite for an accurate, live-scrolling paper tape display, because any jitter in the audio pipeline directly manifests as false timing deviations in the measurement output.

---

### REQ-1.2 — Audio Source Selection & Signal Purity

**Requirement:**  
The audio capture session MUST request either `VOICE_RECOGNITION` or `UNPROCESSED` as the Android `AudioSource`. In addition, the capture session MUST explicitly and programmatically disable the following audio effects at the hardware and software level:

- Acoustic Echo Cancellation (AEC)
- Automatic Gain Control (AGC)
- Noise Suppression (NS), if exposed as a separable effect

**Rationale:**  
Modern Android devices — and the Pixel 8 Pro in particular — apply a stack of AI-driven and DSP-driven audio processing algorithms to every capture stream by default. On the Pixel 8 Pro this includes Google's "Audio Magic Eraser" class of signal processing. These algorithms are tuned for voice clarity and call quality; they are specifically designed to attenuate and reshape sharp, non-voice transient sounds. A mechanical watch escapement produces precisely these kinds of sounds: extremely sharp, high-frequency transient impulses lasting only a few milliseconds. Allowing AEC or AGC to process the signal would suppress, smear, or entirely eliminate these peaks before the DSP pipeline ever sees them, making accurate tick detection impossible. `VOICE_RECOGNITION` and `UNPROCESSED` are the only Android `AudioSource` modes that bypass or minimize this processing stack.

---

### REQ-1.3 — Audio Format & Sample Rate

**Requirement:**  
The audio stream MUST be configured with the following format parameters, with no deviation:

| Parameter    | Value                        |
|--------------|------------------------------|
| Channel mode | Mono (single channel)        |
| Sample rate  | 48,000 Hz (48 kHz)           |
| Bit depth    | 16-bit PCM **or** 32-bit Float |

**Rationale:**  
48 kHz is the native sample rate of the Pixel 8 Pro's internal ADC/DAC hardware. Requesting any other sample rate (most commonly 44,100 Hz / CD quality) forces the Android audio subsystem to perform real-time sample rate conversion (resampling) before handing audio data to the app. This resampling step consumes additional CPU cycles on the audio thread and can introduce subtle micro-latency or interpolation artefacts into the waveform — both of which would corrupt the sub-millisecond timing precision required for beat error and rate measurement. Mono capture is sufficient for this application and halves the memory bandwidth compared to stereo.

---

### REQ-1.4 — External Mic Support & User-Initiated USB-C Routing

**Requirement:**  
The app MUST default to the built-in microphone (bottom mic on the Pixel 8 Pro) for all measurement sessions. The built-in microphone is the known, characterised, predictable transducer and is the correct default for the vast majority of use cases.

The app MUST additionally support an optional external USB-C audio input, subject to these constraints:

1. **Opt-in only.** When a USB-C audio input device is detected via `AudioDeviceCallback`, the app MUST NOT automatically switch to it. The built-in mic remains active unless the user explicitly chooses otherwise via the UI toggle specified in REQ-7.8.

2. **UI availability indicator.** While a USB-C input is physically connected, the UI MUST surface a mic-source toggle that shows the current selection (built-in vs USB-C) and allows the user to switch between them with a single tap. When no USB-C device is connected, the toggle MUST be hidden entirely (the built-in mic is then the only option and no control is needed).

3. **Live routing switch.** When the user toggles to USB-C (or back) during an active measurement session, the audio engine MUST tear down the active Oboe stream, open a new stream targeting the newly-selected device ID, and resume capture. Detection state (noise floor history, BPH lock, lift angle, threshold multiplier, tape points) MUST be preserved across the switch.

4. **Automatic fallback on disconnect.** If the user is actively routed through a USB-C device and it is physically unplugged, the app MUST immediately and automatically fall back to the built-in microphone without user interaction — preserving the same detection state.

5. **Boot with USB-C already plugged.** If the user launches the app with a USB-C device already connected, the app MUST still start with the built-in mic selected (opt-in policy applies equally at launch). The toggle is immediately visible so the user can switch in one tap.

**Rationale:**  
The built-in microphone is always present, always a known quantity, and always the right starting point — jumping to an external device the moment it appears would surprise users who may have only plugged in USB-C headphones for an unrelated purpose. Making USB-C explicit and opt-in keeps the behaviour predictable: the app uses what the user told it to use, not what happened to be plugged in. Preserving detection state across the switch means the watchmaker can compare readings between the two transducers in the same session, which is valuable for characterising movements.

---

### REQ-1.5 — Bluetooth Audio Source Policy (Silent Ignore)

**Requirement:**  
The app MUST NOT consider, enumerate for selection, or route audio through any Bluetooth audio device under any circumstance. Bluetooth input devices are identified by their Android `AudioDeviceInfo` type (see REQ-9.2) and MUST be treated as if they do not exist for the purposes of audio capture:

- Never returned by the device-selection routine.
- Never offered as a choice in the UI.
- Never routed to by the hot-plug handler when a Bluetooth device is newly connected.
- Never logged as an error or surfaced to the user — the app is simply silent about Bluetooth audio.

The built-in microphone is always a viable input (it is present on every supported device), so there is no scenario in which the app needs to display a "Bluetooth only available" error. That case does not exist.

**Rationale:**  
All Bluetooth audio profiles (SCO for microphones, A2DP for audio) use lossy compression codecs and introduce substantial, non-deterministic round-trip latency — typically 100ms to 300ms with variance. Beyond the latency, the codec processing fundamentally alters and smears the sharp transient waveform of a watch tick at the signal level, making accurate peak detection and sub-millisecond timing measurement impossible. There is no software-side mitigation.

Because the built-in microphone is always available, "silent ignore" is a stronger and cleaner user experience than "reject with an error dialog" — the user never sees a failure state, never needs to dismiss a modal, and never wonders why their paired Bluetooth headphones are not an option. The app simply uses the right mic.

---

## 2. DSP & Detection Module

### REQ-2.1 — Pre-Processing: Bandpass Filtering

**Requirement:**  
The DSP pipeline MUST apply a digital bandpass filter to every incoming audio sample before any peak detection or energy calculation occurs. The filter's passband MUST cover approximately **1,500 Hz to 10,000 Hz**. The filter implementation MUST be computationally efficient (e.g., a biquad IIR filter cascade is recommended) to run in real time on the C++ audio thread without introducing processing delays that would affect timing accuracy.

**Rationale:**  
Raw microphone audio in any real-world environment contains a broad spectrum of interference. Low-frequency content (below ~1,500 Hz) includes HVAC rumble, handling vibration transmitted through surfaces, street and building noise, and low-frequency acoustic resonances — none of which are produced by the watch escapement. High-frequency content (above ~10,000 Hz) includes electronic hiss, ADC quantisation noise, and RF interference. Mechanical watch escapements produce their characteristic "tick" sounds primarily as sharp metallic transients in the mid-to-high frequency band (approximately 2,000 Hz to 8,000 Hz), well within the defined passband. By aggressively attenuating everything outside this band before the detection stage, the algorithm's dynamic noise floor tracking and peak detection operate on a much cleaner, more focused signal — dramatically reducing false positives from ambient noise.

---

### REQ-2.2 — Dynamic Noise Floor Tracking

**Requirement:**  
The detection algorithm MUST continuously compute a **rolling Root Mean Square (RMS)** of the filtered signal's energy over a sliding time window. This rolling RMS value constitutes the current **dynamic noise floor baseline**. The time window length MUST be long enough to smooth over individual tick events (recommended: 20ms to 100ms, tunable) but short enough to respond to genuine ambient noise level changes within approximately 1–2 seconds.

**Rationale:**  
A fixed, static amplitude threshold for tick detection is fundamentally incompatible with real-world usage. The acoustic environment changes constantly — a user may place their phone closer to or further from the watch, other sounds may occur in the room, or the user may move from a quiet workshop to a noisier environment mid-session. The rolling RMS baseline provides automatic, continuous calibration: the algorithm always knows what "silence between ticks" sounds like right now, in this moment, at this specific noise level. This is the core of the auto-leveling behaviour. If the room gets louder, the noise floor rises proportionally; if it goes quiet, the floor drops. Tick detection adapts automatically without any user intervention.

---

### REQ-2.3 — Transient Peak Detection (The "Tick" Trigger)

**Requirement:**  
A tick event MUST be registered — and a timestamp recorded — only when the instantaneous filtered signal energy spikes above the current rolling noise floor baseline by a specific **dynamic threshold multiplier**. The trigger condition is:

```
instantaneous_energy > noise_floor_RMS × threshold_multiplier
```

The threshold multiplier MUST be configurable (recommended default: ×4 to ×7). The timestamp recorded for each tick event MUST correspond to the moment of the initial energy spike — the very first sample crossing the threshold — and not to the peak of the waveform envelope.

**Rationale:**  
A fixed absolute threshold (e.g., "trigger when amplitude exceeds 0.3") fails when the watch is quiet or the room is loud. The ratio-based dynamic threshold solves this: it does not ask "is the signal loud?" — it asks "is the signal suddenly much louder than the background?" This isolates the impulsive, transient character of a watch tick from the continuous, slowly-varying character of ambient noise, regardless of the absolute signal level. The ratio multiplier can be thought of as a signal-to-noise requirement for the detection event. Capturing the timestamp at the initial threshold crossing (rather than the waveform peak) is critical for timing precision, as peak detection introduces a small but non-negligible and variable delay.

---

### REQ-2.4 — Macro Hold-Off / Dead-Time (Beat Debouncing)

**Requirement:**  
Once a primary tick event is detected and its timestamp recorded (per REQ-2.3), the detection algorithm MUST enter a **macro hold-off period** of **50 to 80 milliseconds** during which the peak detection threshold is raised to an impossibly high level (effectively suppressing all further detections). This hold-off window exists exclusively for the purpose of macro-timing analysis (rate and beat error calculation, REQ-3.x). It does NOT suppress the separate micro-window analysis defined in REQ-4.1.

**Rationale:**  
A single mechanical watch beat is not a single acoustic event. Each beat consists of three distinct sequential mechanical impacts:

1. **The Unlock** — the pallet fork jewel releasing the escape wheel tooth (~0ms)
2. **The Impulse** — the escape wheel tooth sliding along the impulse face of the pallet jewel (~5–15ms after Unlock)
3. **The Drop** — the opposite pallet jewel catching the next escape wheel tooth (~20–30ms after Unlock)

All three impacts produce acoustic energy spikes that would individually cross the detection threshold. Without a hold-off period, the algorithm would count each of these three sub-events as a separate "tick," producing a BPH reading approximately three times the actual beat rate and rendering all rate and beat error calculations completely invalid. The hold-off window (50–80ms) is intentionally set to cover the full 30ms span of a single three-part beat, with margin.

---

## 3. Timing & BPH Module

### REQ-3.1 — BPH Auto-Detection via Interval Averaging

**Requirement:**  
Upon session initialisation (and whenever the BPH state is reset to AUTO mode), the app MUST enter a silent **auto-detection phase** during which it captures a rolling buffer of consecutive tick timestamps. The buffer MUST hold a minimum of **10 to 16 consecutive beat timestamps**. Once the buffer is full, the app MUST calculate the mean inter-tick interval (Δt_avg) in seconds as follows:

```
Δt_avg = (t_last − t_first) / (n − 1)
```

where `t_first` and `t_last` are the timestamps of the first and last ticks in the buffer, and `n` is the total number of ticks captured. This mean interval is then passed to the frequency snapping logic (REQ-3.2).

**Rationale:**  
Attempting to determine the BPH from the interval between just two consecutive ticks is dangerously unreliable in the presence of beat error. Beat error is the condition where the balance wheel's arc is asymmetric — the "tick" (clockwise arc) and the "tock" (counter-clockwise arc) have different durations. This asymmetry causes consecutive inter-tick intervals to alternate between a shorter and a longer value. If the two chosen ticks happen to be tick→tock (the short interval), the calculated BPH will be too high; if they are tock→tick (the long interval), it will be too low. Averaging across 10–16 beats ensures an even number of tick and tock intervals are included in the calculation, causing the asymmetry to cancel out and revealing the true underlying mean beat rate.

---

### REQ-3.2 — Frequency Snapping (Nearest Neighbour)

**Requirement:**  
After calculating the raw mean BPH from the detection buffer (REQ-3.1), the app MUST compare this raw value against the following predefined array of standard horological beat frequencies and select the closest match:

```
[14400, 18000, 19800, 21600, 25200, 28800, 36000]
```

The snapped BPH value becomes the **target BPH** used in all subsequent rate, beat error, and amplitude calculations for the duration of the session (or until a new auto-detection cycle is triggered or the user manually overrides). The underlying conversion formula is:

```
BPH = 3600 / Δt_avg
```

**Rationale:**  
No mechanical watch escapement runs at exactly its nominal frequency. A watch rated for 28,800 BPH may, in practice, be producing 28,793 or 28,811 beats per hour due to mainspring tension variation, positional error, temperature, lubrication state, and manufacturing tolerances. The timegrapher's purpose is to measure the deviation from the ideal target — which means the app must know what the ideal target is. Without snapping to a standard frequency, the app would treat the watch's actual (imperfect) mean rate as the target, resulting in a rate display that always reads near zero regardless of how well or poorly the watch is performing. Snapping to the nearest standard frequency anchors the measurement to the watchmaker's design intent.

---

### REQ-3.3 — Manual BPH Override & State Management

**Requirement:**  
The UI MUST provide a user-accessible control (dropdown selector or rotary dial) listing all standard BPH values from REQ-3.2, plus an "AUTO" option. When the user selects a specific BPH value:

1. The auto-detection buffer (REQ-3.1) MUST be immediately discarded and the auto-detection logic MUST be suspended for the remainder of the session.
2. The selected BPH value MUST become the target BPH immediately and take effect on the very next beat event processed.
3. The app state MUST transition to `LOCKED` mode, visually indicated in the UI.

When the user selects "AUTO," the app MUST clear all current timing data and restart the auto-detection phase from scratch.

**Rationale:**  
Auto-detection, while robust, can fail in specific scenarios: extreme beat error can bias the averaged interval, strong ambient noise can inject false tick events into the buffer during the detection phase, and a watch running very far off nominal frequency might cause the algorithm to snap to a harmonic (e.g., a 28,800 BPH watch running very fast might snap to 36,000 BPH). Manual override gives the experienced user a reliable escape hatch. It also covers specialist cases such as unusual antique movements or custom escapements with non-standard beat rates.

---

## 4. Amplitude & Escapement Module

### REQ-4.1 — Micro-Acoustic Analysis (Lift Time Extraction)

**Requirement:**  
Immediately upon detection of a primary tick event (the **Unlock** peak, per REQ-2.3), the DSP pipeline MUST open a separate, independent **micro-analysis window** of approximately **30 milliseconds** duration. Within this window, the macro hold-off debouncing (REQ-2.4) does NOT apply. The pipeline MUST actively scan this 30ms window to identify two additional sub-peaks:

- **The Impulse peak** — the second detectable energy spike, typically occurring 5–15ms after the Unlock.
- **The Drop peak** — the third detectable energy spike, typically occurring 20–30ms after the Unlock.

The **Lift Time (Δt_lift)** is defined as the elapsed time in seconds between the timestamp of the Unlock peak and the timestamp of the Drop peak. This value MUST be computed for every individual beat and stored alongside the beat's macro timestamp.

**Rationale:**  
The three-part acoustic signature of a watch beat (Unlock → Impulse → Drop) is not merely noise to be filtered out — it encodes physical information about the mechanical state of the escapement. Specifically, the time between the Unlock and the Drop represents the exact duration for which the balance wheel is physically engaged with and driving the pallet fork. This is the **lift time**: the window during which the escape wheel is transferring energy to the balance wheel. The lift time, combined with the known beat frequency and the user-defined lift angle, provides a mathematically sound basis for calculating the balance wheel's angular amplitude of oscillation. This requires simultaneously processing two time scales: the macro scale (inter-beat intervals, hundreds of milliseconds) for rate and beat error, and the micro scale (intra-beat sub-peaks, tens of milliseconds) for amplitude.

---

### REQ-4.2 — Amplitude Calculation

**Requirement:**  
The app MUST calculate the balance wheel's swing **Amplitude (A)** in degrees for every beat, using the following formula:

```
A = θ_lift / sin(π × (BPH / 7200) × Δt_lift)
```

Where:
- **θ_lift** = the user-configured Lift Angle in degrees (see REQ-4.3)
- **BPH** = the current target BPH (snapped or manually set, per REQ-3.2 / REQ-3.3)
- **Δt_lift** = the measured Lift Time in seconds for this beat (per REQ-4.1)

The computed amplitude value MUST be validated before display. Specifically:
- If the argument to `sin()` produces a value ≤ 0 or ≥ 1, the result is mathematically degenerate (undefined or infinite). In this case, the amplitude for that beat MUST be flagged as `INVALID` and omitted from the display rather than showing NaN, infinity, or a nonsensical number.
- A running smoothed average of recent valid amplitude readings SHOULD be maintained and displayed alongside the per-beat value to reduce visual noise.

**Rationale:**  
The balance wheel in a mechanical watch moves in approximately simple harmonic motion (SHM). At the moment of Unlock, the balance wheel is passing through the centre of its arc at peak angular velocity. The lift time — the duration the pallet fork is engaged — subtends a specific arc of the balance wheel's swing, defined by the lift angle (a fixed geometric property of the escapement). By knowing what fraction of the oscillation period the lift time represents (given the BPH), trigonometry allows back-calculation of the total arc. This is the only non-invasive, acoustic-only method for estimating amplitude without physically attaching a sensor to the balance wheel.

---

### REQ-4.3 — Lift Angle Slider (UI)

**Requirement:**  
The UI MUST expose an interactive slider control for the **Lift Angle (θ_lift)** with the following constraints:

| Property      | Value  |
|---------------|--------|
| Minimum value | 35°    |
| Maximum value | 70°    |
| Default value | 53°    |
| Step size     | 1°     |

Changes to the lift angle MUST take effect immediately on the next computed beat — no session restart or recalibration is required. The current lift angle value MUST be displayed numerically adjacent to the slider at all times.

**Rationale:**  
The lift angle is a fixed geometric property of the escapement design — specifically, the angular span of the impulse face of the pallet jewels. It varies by manufacturer and calibre. 53° is a widely accepted default for modern Swiss lever escapements. However, vintage movements, non-Swiss calibres, and bespoke or modified escapements can have lift angles ranging from approximately 35° (very shallow impulse geometry) to 70° (deep, high-energy impulse face). Constraining the slider to 35°–70° prevents the user from entering values that are physically unrealisable for a lever escapement and that would cause the amplitude formula's `sin()` argument to produce degenerate results. Allowing immediate real-time updates without a restart enables the user to tune the lift angle while observing the amplitude output stabilise, which is a standard workflow when characterising an unfamiliar movement.

---

## 5. Visualization & UI Module

### REQ-5.1 — Traditional "Paper Tape" Scrolling Display

**Requirement:**  
The primary measurement display MUST be a continuously scrolling 2D canvas that emulates a traditional hardware timegrapher's paper tape output. The canvas MUST exhibit the following behaviour:

- **Scroll direction:** Right to left (newest data on the right, scrolling leftward over time).
- **X-axis (time):** Each new beat advances the plotting position by a fixed horizontal distance, creating a consistent horizontal time scale.
- **Y-axis (deviation):** The vertical position of each plotted dot represents the **timing deviation (Δ) in milliseconds** between when the beat actually occurred and when it would have occurred if the watch were running at exactly the target BPH. A dot on the centre horizontal axis means zero deviation (perfect timekeeping). Dots above centre indicate the watch is running fast (beats arriving early). Dots below centre indicate the watch is running slow (beats arriving late).
- **Beat error visualisation:** Because the tick and tock beats have different interval durations when beat error is present, the deviations of tick beats and tock beats will systematically differ, causing the tape to render as **two distinct parallel lines** (one for tick, one for tock) rather than a single line.
- **Visual slope:** The gradient of the line (or pair of lines) directly and intuitively represents the rate: a perfectly horizontal line means the rate error is 0 seconds/day; a positive slope means the watch gains time; a negative slope means it loses time.

**Rationale:**  
The paper tape display is the defining visual metaphor of precision watch timing instruments, tracing its lineage to 1960s electro-mechanical timegraphers. It is chosen because it encodes multiple dimensions of information simultaneously in an immediately readable visual form: rate (slope), beat error (line separation), consistency (scatter/noise of the dots), and trend over time (curve changes). It is the most information-dense and time-proven display format for this application.

---

### REQ-5.2 — Live Filtered Waveform Oscilloscope

**Requirement:**  
The UI MUST include a dedicated waveform viewport displaying a real-time, continuously updating plot of the **bandpass-filtered** audio signal buffer (i.e., the signal post-REQ-2.1 filtering, not the raw input). The waveform display MUST include the following overlaid visual markers:

- **Noise floor line:** A horizontal line rendered at the amplitude level corresponding to the current rolling RMS noise floor value (REQ-2.2).
- **Trigger threshold line:** A horizontal line rendered at the amplitude level of `noise_floor_RMS × threshold_multiplier` (REQ-2.3) — i.e., the level a signal must exceed to register as a tick.
- **Beat event markers:** A visual indicator (e.g., a vertical line or highlighted region) marking the timestamp of each detected Unlock event, so the user can see which waveform peaks are being counted as ticks.

The waveform viewport SHOULD display a time window sufficient to show at least one complete three-part beat (Unlock → Impulse → Drop), ideally spanning approximately 40–60ms. The refresh rate of the waveform display MUST be high enough to appear smooth and responsive (minimum 30fps, target 60fps).

**Rationale:**  
The oscilloscope waveform serves as the user's primary diagnostic tool for microphone placement, session quality verification, and false-positive debugging. By literally seeing the filtered waveform and the threshold lines simultaneously, the user can:
- Verify that the tick peaks are clearly rising above the threshold line.
- Verify that ambient noise and room rumble sit well below the threshold line.
- Identify if the threshold is set too aggressively (triggering on noise) or too conservatively (missing beats).
- Confirm that the three sub-peaks of each beat (Unlock, Impulse, Drop) are distinguishable — validating that the micro-analysis window (REQ-4.1) has clean data to work with.
- Diagnose poor microphone placement (signal too weak) or coupling issues.

---

### REQ-5.3 — Rendering Architecture & Thread Isolation

**Requirement:**  
The rendering pipeline for both the paper tape canvas (REQ-5.1) and the waveform oscilloscope (REQ-5.2) MUST use Android's **hardware-accelerated graphics path**. Acceptable implementations include:

- OpenGL ES 2.0 or higher via a `GLSurfaceView` or `SurfaceView` with custom render thread
- Vulkan via a `SurfaceView`
- Android's hardware-accelerated `Canvas` API (via `SurfaceView` with `lockHardwareCanvas()`)
- Jetpack Compose `Canvas` with hardware acceleration enabled (acceptable for lower-complexity rendering)

The rendering thread MUST be entirely decoupled from the C++ Oboe audio processing thread. Under no circumstances should the render thread block on the audio thread or vice versa. Communication between the audio thread and the render thread MUST use a lock-free or minimally-locking data structure (e.g., a ring buffer or atomic pointer swap) to pass detected beat events and waveform sample data across the thread boundary.

**Target rendering performance:** Sustained 60 frames per second on the Pixel 8 Pro under normal measurement conditions, with no dropped audio frames attributable to UI rendering activity.

**Rationale:**  
A precision measurement instrument with a stuttering or choppy UI undermines user confidence and makes it impossible to visually assess the smoothness and consistency of the timing data. The C++ Oboe audio thread operates under strict real-time constraints — any blocking, lock contention, or scheduling interruption on that thread causes missed audio callbacks, which directly corrupt the timing measurements. The render thread must therefore never compete with or block the audio thread. Hardware-accelerated rendering offloads compositing to the GPU, leaving the CPU audio thread entirely undisturbed. The two threads must communicate only via carefully designed non-blocking data hand-off mechanisms.

---

## 6. Branding & Visual Identity

### REQ-6.1 — Brand Identity

**Requirement:**  
The application MUST identify itself as **"Kargathra & Co. — Timegrapher"** in all user-facing surfaces. The app's display name (launcher label, task switcher, settings) MUST be "Kargathra & Co." Every screen SHALL present the brand prominently so the app reads as a professional instrument made by a specific horological brand rather than as a generic utility.

**Rationale:**  
The timegrapher is an instrument in the Kargathra & Co. product line and its visual treatment is an expression of the brand itself. Brand presence on every screen reinforces the heritage-watchmaker positioning and differentiates the app from generic timegrapher utilities.

---

### REQ-6.2 — Colour Palette (Navy + Champagne Gold)

**Requirement:**  
The app's colour system MUST be built around two primary brand colours — **deep navy** and **champagne gold** — plus a small set of supporting tones. The exact palette is:

| Role | Hex | Purpose |
|------|-----|---------|
| NavyDeep | `#0B1A2E` | Primary app background, status bar, navigation bar |
| NavySurface | `#152641` | Cards, elevated surfaces, controls background |
| NavyElevated | `#1B2F4F` | Dialogs, overlay surfaces |
| NavyBorder | `#1E3555` | Hairline dividers, card borders |
| GoldPrimary | `#D4A84B` | Brand mark, logo, primary data readouts |
| GoldBright | `#EFC667` | Active / selected controls, trigger marker |
| GoldMuted | `#8B7344` | Labels, secondary text, inactive selected tabs |
| GoldDim | `#5A4A2C` | Inactive controls, disabled states |
| CreamPrimary | `#F5E8C8` | Body text where gold would be too warm |
| CreamMuted | `#A89B7F` | Secondary labels, captions |
| StatusWarn | `#E89B3E` | Amber — marginal readings, trigger threshold |
| StatusBad | `#CC4E3A` | Oxide red — out-of-spec readings, stop button, errors |

The palette MUST be defined in **two locations** and kept in sync:
1. `res/values/colors.xml` (for XML-referenced resources: launcher icon, platform theme)
2. `ui/theme/Theme.kt → KargathraColors` object (for Compose UI)

All Compose UI code MUST reference colours through the `KargathraColors` object (or through the `MaterialTheme.colorScheme` role mapping) rather than using raw hex literals. This ensures the palette can be adjusted in a single place.

**Rationale:**  
The navy/gold combination evokes classical heritage watchmaking — deep enamel-dial navy paired with gilt-engraving champagne gold. Centralising the palette prevents drift between XML and Compose contexts and makes future palette adjustments safe.

---

### REQ-6.3 — Logo Mark

**Requirement:**  
The Kargathra & Co. logo is an **inverted (downward-pointing) triangle outline containing a centred serif capital K**. It MUST be rendered in GoldPrimary (`#D4A84B`) on every surface. Both the triangle and the K MUST be the same colour — the logo is a single cohesive mark, not two separate elements.

The logo MUST be available in two technical forms to support both contexts where it is used:

1. **Static vector drawable** at `res/drawable/ic_kargathra_logo.xml` — SVG paths for use in platform contexts (launcher icon composition, notification icons, splash screens).
2. **Compose composable** `KargathraLogo(size: Dp, color: Color)` — draws the triangle using Compose Canvas primitives and renders the K glyph using `drawText` with `FontFamily.Serif`. Scales crisply at any size.

**Rationale:**  
Two forms are required because Android mandates XML vector drawables for adaptive launcher icons, while the in-app brand mark is best rendered as a real serif glyph inside a Canvas so the K remains a proper typographic character rather than a hand-constructed path that may not scale or render consistently.

---

### REQ-6.4 — Adaptive Launcher Icon

**Requirement:**  
The app MUST ship a full Android adaptive launcher icon (API 26+) consisting of:

- **Background layer:** solid NavyDeep (`#0B1A2E`) across the entire 108×108 dp canvas.
- **Foreground layer:** the Kargathra logo in GoldPrimary, sized to fit entirely within the central 72×72 dp safe zone so that no launcher mask (circle, squircle, teardrop, etc.) clips the mark.
- **Monochrome layer:** identical to the foreground, used by Android 13+ themed icons.

Both `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` MUST be provided so that older launchers expecting a separate round icon receive the same adaptive definition.

**Rationale:**  
Adaptive icons are mandatory for a polished Android 8+ launcher presentation. The 72dp safe zone constraint is critical — elements outside this zone are at risk of being masked off by aggressive launcher shape masks on OEM skins.

---

### REQ-6.5 — Typography System

**Requirement:**  
The app MUST use **system-provided font families only** (no bundled font files) to minimise APK size and ensure the app respects any user-level font preferences:

| Font family | Compose reference | Usage |
|-------------|-------------------|-------|
| Serif | `FontFamily.Serif` | Brand wordmark, primary metric numerals (heritage feel) |
| Default (sans-serif) | `FontFamily.Default` | UI labels, body text, buttons |
| Monospace | `FontFamily.Monospace` | BPH selector, lift angle display, threshold multiplier — anywhere digit alignment matters |

Typography sizes MUST meet or exceed Android accessibility minimums:
- Smallest caption: 9 sp (permitted only for heavily tracking-spaced uppercase labels such as `TIMEGRAPHER` or section headers)
- UI labels: 11 sp minimum
- Body text: 14 sp
- Primary metric numerals: 34 sp in serif (readable from a workbench distance)
- Brand wordmark: 16 sp (in-app header) or 22 sp (permission/splash screen)

**Rationale:**  
System fonts keep the APK small, respect user accessibility settings (font scaling), and avoid font-licensing concerns. Serif for the brand and primary numerals reinforces the heritage watchmaker aesthetic; monospace for digit readouts prevents character-width jitter as values change. The size floor of 11 sp for UI labels meets WCAG readability guidance on a phone-sized screen.

---

## 7. User Experience & Interaction

### REQ-7.1 — Screen Layout & Visual Hierarchy

**Requirement:**  
The main measurement screen MUST present its elements in the following vertical order, with approximate space allocations:

1. **Brand header** (~56 dp fixed) — logo, wordmark, subtitle, USB-C indicator (if connected), clear-tape action (if running), primary Start/Stop action.
2. **Status strip** (~28 dp fixed) — current engine state (IDLE / DETECTING N/16 / LOCKED AUTO … / LOCKED MANUAL …).
3. **Metric quadrant** (~200 dp fixed) — four large cards laid out 2×2 showing: Rate (s/day), Amplitude (degrees), Beat Error (ms), Lift Time (ms).
4. **Paper tape** (flex, approximately 35% of remaining screen height) — primary visual output per REQ-5.1.
5. **Oscilloscope** (flex, approximately 15% of remaining screen height) — filtered signal viewport per REQ-5.2.
6. **Controls panel** (~210 dp fixed) — BPH selector, lift angle slider, collapsed advanced section (threshold multiplier).

**Rationale:**  
This hierarchy places the watchmaker's primary information (the four metrics + the paper tape) in the visually dominant upper two-thirds of the screen, with diagnostics and controls below. The fixed-height header and status strip provide stable spatial anchors so the user always knows where to find the primary action and current state.

---

### REQ-7.2 — Primary Action (Start / Stop)

**Requirement:**  
The Start/Stop button MUST be:
- A circular button **at least 48 dp in diameter** (meets Android's minimum touch target).
- Located in the top-right of the brand header (consistent anchor position).
- Visually distinct between states:
  - **Idle (ready to start):** GoldPrimary fill with GoldBright ring, NavyDeep play-triangle icon.
  - **Running:** NavySurface fill with StatusBad (oxide red) ring, StatusBad stop-square icon.

**Rationale:**  
The 48 dp minimum comes from Google's Material Design touch target guidance. The colour inversion between states (filled when idle, outlined when running) provides a strong visual cue about what pressing the button will do — a filled button reads as "go", an outlined one as a warning state.

---

### REQ-7.3 — Metric Card Colour Coding

**Requirement:**  
Each metric card's numeric value MUST be coloured according to whether the reading is within accepted horological tolerances:

| Metric | Good (GoldPrimary) | Marginal (StatusWarn amber) | Out-of-spec (StatusBad red) |
|--------|---------------------|------------------------------|------------------------------|
| Rate | \|rate\| ≤ 10 s/day | 10 < \|rate\| ≤ 30 s/day | \|rate\| > 30 s/day |
| Amplitude | 260° – 310° | 220° – 260° or 310° – 330° | < 220° or > 330° |
| Beat Error | ≤ 0.5 ms | 0.5 – 1.0 ms | > 1.0 ms |
| Lift Time | always GoldPrimary (informational only) | n/a | n/a |

When a metric is invalid (e.g., amplitude fails the sin() guard per REQ-4.2), the card MUST display an em-dash (`—`) in GoldDim rather than showing `0` or `NaN`.

**Rationale:**  
Glanceable colour status lets the watchmaker identify an out-of-spec reading instantly without reading numbers, especially useful when adjusting regulation and watching the display for changes. Using the brand's gold as the "good" status colour reinforces the brand throughout normal operation rather than only at the edges.

---

### REQ-7.4 — Status Strip

**Requirement:**  
Directly below the brand header the app MUST display a thin (~28 dp) status strip with a single line of text summarising the current engine state. The strip MUST render one of:

- `READY` (GoldMuted) — engine is idle.
- `DETECTING · N / 16` (StatusWarn amber) — auto-detection in progress, N is current buffer fill count.
- `LOCKED AUTO · XXXXX BPH` (GoldPrimary) — auto-detection completed, locked to a standard BPH.
- `LOCKED MANUAL · XXXXX BPH` (GoldBright) — user manually selected a BPH.

**Rationale:**  
The state of the BPH detection machine is critical context — without it, the user cannot interpret whether a rate reading is meaningful. A dedicated strip avoids the ambiguity of cramming this into the header alongside the brand and the Start/Stop button.

---

### REQ-7.5 — Advanced Controls Progressive Disclosure

**Requirement:**  
Controls MUST be split between always-visible and progressively-disclosed:

- **Always visible:** BPH selector (including AUTO), lift angle slider with numeric readout.
- **Collapsed by default, under "ADVANCED ▼":** threshold multiplier slider, and any future advanced tuning controls.

Tapping the `ADVANCED` label MUST expand or collapse the advanced section with a smooth animation. The advanced toggle's icon MUST flip between down-chevron (collapsed) and up-chevron (expanded).

**Rationale:**  
Most users will never need to adjust the threshold multiplier; exposing it permanently clutters the control panel and risks users changing a working parameter by accident. Advanced users still have full access one tap away.

---

### REQ-7.6 — Permission Flow

**Requirement:**  
The app MUST implement a three-state microphone-permission flow and present an appropriate branded screen for each non-granted state:

| State | Trigger | UI action |
|-------|---------|-----------|
| `NOT_REQUESTED` | First launch, permission never asked | "GRANT MICROPHONE ACCESS" button → launches system permission dialog |
| `DENIED_RATIONALE` | User denied once but `shouldShowRequestPermissionRationale()` returns true | Explanatory message + "TRY AGAIN" button → re-launches system dialog |
| `DENIED_PERMANENT` | User denied with "Don't ask again" or `shouldShowRequestPermissionRationale()` returns false after denial | Explanation that permission is permanent + "OPEN APP SETTINGS" button → opens `ACTION_APPLICATION_DETAILS_SETTINGS` intent |
| `GRANTED` | Permission granted | Main measurement screen |

The Activity MUST re-check permission state in `onResume()` to detect the case where the user granted permission via App Settings and returned to the app.

The permission screen MUST be branded: large (96 dp) Kargathra logo, serif wordmark, muted-cream rationale text, gold primary button — consistent with the rest of the app's visual system.

**Rationale:**  
Android's permission system silently stops showing the system dialog after a permanent denial; without the Settings-redirect path the user has no way to re-grant permission and the app becomes unusable. The `onResume()` re-check ensures a seamless return from Settings.

---

### REQ-7.7 — Loading & Empty States

**Requirement:**  
Every measurement-related surface MUST have a clearly-designed non-running state:

- **Paper tape when idle:** centred dim-gold caption `PRESS START TO BEGIN`. No dots, no scrolling.
- **Paper tape during DETECTING phase:** semi-transparent navy overlay on top of the tape with a gold `CircularProgressIndicator`, the label `DETECTING BPH`, and the serif progress counter `N / 16`.
- **Oscilloscope when idle:** just the centre grid line, no waveform.
- **Metric cards when not yet populated:** em-dash (`—`) per REQ-7.3.

**Rationale:**  
An instrument with ambiguous "is it working?" states erodes trust. Explicit placeholder text and progress indicators make it unambiguous when the app is idle, warming up, or actively measuring.

---

### REQ-7.8 — Mic Source Toggle UI

**Requirement:**  
The brand header MUST include an interactive **mic source toggle** that is displayed only when a USB-C audio input device is currently physically connected. When visible, the toggle MUST:

- Show the **currently active input** with an icon (`Icons.Default.Mic` for built-in, `Icons.Default.Cable` for USB-C) and a short text label (`BUILT-IN` or `USB-C`).
- Use GoldPrimary when the built-in mic is active (the default) and GoldBright when USB-C is active, so the "default" state visually blends with the rest of the header and the "override" state reads as subtly highlighted.
- Tapping the toggle MUST flip between the two states and trigger a live routing switch in the audio engine per REQ-1.4.

When no USB-C audio device is connected, the toggle MUST be hidden entirely — the app is running on the built-in mic (the only possible source) and no affordance is needed.

The app MUST NOT display any user-facing dialog or error related to Bluetooth audio. Bluetooth is invisible to this app (REQ-1.5).

**Rationale:**  
A toggle that appears only when relevant is less visually noisy than one that is always visible. The colour differentiation (primary vs bright gold) is deliberately subtle — the user should know which input they're using, but the information shouldn't compete for attention with the primary metrics. Forbidding any Bluetooth-related dialog eliminates a whole class of user-facing failure modes that would have been needed under the old "reject with error" model.

---

## 8. Build, Signing & Distribution

### REQ-8.1 — Continuous Integration Build (GitHub Actions)

**Requirement:**  
The project MUST include a GitHub Actions workflow (`.github/workflows/build.yml`) that builds a debug APK on every push to `main` or `develop` and on every pull request targeting `main`. The workflow MUST:

1. Run on `ubuntu-latest`.
2. Install JDK 17 (Temurin distribution).
3. Install and pin NDK version `25.2.9519653` (r25c) — required for Oboe 1.9.0 prefab compatibility.
4. Install CMake version `3.22.1`.
5. Bootstrap the Gradle wrapper using `gradle/actions/setup-gradle@v4` and `gradle wrapper --gradle-version 8.7` (the wrapper JAR and `gradlew` scripts are NOT committed to the repository).
6. Build with `./gradlew assembleDebug`.
7. Cache NDK build outputs (`app/.cxx`, `app/build/intermediates/cmake`) keyed by NDK version and C++ source hash.
8. Upload the resulting APK as a build artifact with a 14-day retention period, named `kargathra-debug-{commit-sha}`.

**Rationale:**  
A deterministic CI pipeline ensures every pushed commit produces a verifiable APK with no developer-machine dependencies. The NDK version pin is critical — version drift here is a common silent cause of Oboe prefab resolution failures. Not committing the Gradle wrapper binary keeps the repo free of committed binaries and forces the CI to demonstrate the bootstrap procedure.

---

### REQ-8.2 — Signed Release Build (Tag-Triggered)

**Requirement:**  
On any push of a tag matching the pattern `v*` (e.g., `v1.0.0`, `v1.2.3-beta`), the CI workflow MUST run an additional job that:

1. Only runs if the debug job succeeded (depends-on relationship).
2. Decodes a base64 keystore from the `KEYSTORE_BASE64` repository secret.
3. Builds a signed release APK via `./gradlew assembleRelease` using four mandatory repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. Deletes the decoded keystore from the runner filesystem immediately after signing (`if: always()` cleanup step).
5. Uploads the signed APK as an artifact with a 90-day retention period.
6. Creates a GitHub Release named from the tag and attaches the signed APK to it, with installation instructions in the body.

Signing credentials MUST be passed to Gradle via `-P` command-line flags rather than written to `local.properties`, so they never appear in build logs or committed files.

**Rationale:**  
Tag-triggered releases provide a clean handoff between development (debug APKs on every commit) and distribution (signed, versioned, downloadable release APKs). The keystore-cleanup step ensures secrets cannot leak via subsequent workflow steps or cached runner state.

---

### REQ-8.3 — ABI Filters & Distribution Size

**Requirement:**  
The build MUST target only the following ABIs: `arm64-v8a` (ARM64, the Pixel 8 Pro's native architecture) and `x86_64` (for emulator development). 32-bit ARM (`armeabi-v7a`) and 32-bit x86 MUST NOT be built.

**Rationale:**  
Modern Android devices and the explicit target device (Pixel 8 Pro) use ARM64 exclusively. Including 32-bit variants would roughly double the APK size with no benefit to the target user base.

---

### REQ-8.4 — Build Version Consistency

**Requirement:**  
The JVM target version MUST be consistent across three locations:
- `app/build.gradle.kts`: `compileOptions.sourceCompatibility` and `targetCompatibility` = `JavaVersion.VERSION_17`
- `app/build.gradle.kts`: `kotlinOptions.jvmTarget = "17"`
- `.github/workflows/build.yml`: `JAVA_VERSION: "17"`

The Gradle version MUST be consistent across two locations:
- CI: `gradle/actions/setup-gradle@v4` with `gradle-version: "8.7"`
- CI: `gradle wrapper --gradle-version 8.7`

**Rationale:**  
Version mismatches between these files produce confusing compile failures that appear only in CI and not locally, or vice versa. Listing them explicitly prevents drift.

---

## 9. Permissions & Runtime Device Handling

### REQ-9.1 — Required Permissions

**Requirement:**  
The `AndroidManifest.xml` MUST declare the following permissions, and no more:

- `android.permission.RECORD_AUDIO` — required for REQ-1.2 raw audio capture.
- `android.permission.MANAGE_USB` — required for REQ-1.4 USB-C audio device handling.

The app MUST also declare a `<uses-feature>` entry for `android.hardware.microphone` with `required="true"` so devices without a microphone are filtered out in the Play Store.

No other permissions (network, location, storage, etc.) may be added without explicit requirements justifying them.

**Rationale:**  
Minimum-permission declarations reduce user friction during installation, avoid Play Store review flags, and prevent accidental capability creep. A microphone-required app should declare the hardware requirement so it never appears on microphone-less devices.

---

### REQ-9.2 — Bluetooth Device Silent Filter

**Requirement:**  
The `AudioDeviceMonitor` MUST recognise the following Android `AudioDeviceInfo` device types as Bluetooth and silently filter them out of all enumeration, selection, and hot-plug handling:

- `AudioDeviceInfo.TYPE_BLUETOOTH_SCO`
- `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`
- `AudioDeviceInfo.TYPE_BLE_HEADSET`
- `AudioDeviceInfo.TYPE_BLE_BROADCAST`

"Silently" means: the device is logged at debug level (for diagnostic purposes only), but no warning is raised, no callback is fired, no UI is notified, and no error is surfaced to the user. The device may as well not exist from the app's perspective.

**Rationale:**  
Enumerating all four Bluetooth types (including BLE variants) closes the loophole where a future Bluetooth LE Audio device might not be caught by the legacy `BLUETOOTH_SCO`/`A2DP` checks alone. Treating these devices as invisible rather than as errors matches the REQ-1.5 policy and avoids unnecessary user-facing failure modes.

---

### REQ-9.3 — Default Input Device & User-Initiated Override

**Requirement:**  
The `AudioDeviceMonitor.selectInputDevice(preferUsbC: Boolean)` method MUST apply this policy:

1. When called with `preferUsbC = false` (the default), it MUST return `0` — Oboe's symbolic value for the built-in microphone — regardless of what other devices are connected.
2. When called with `preferUsbC = true`, it MUST enumerate connected input devices, filter out Bluetooth devices per REQ-9.2, and return the device ID of the first USB-C device found. If no USB-C device is present, it MUST fall back to returning `0` (built-in mic) with a warning log.
3. It MUST never return a Bluetooth device ID.
4. It MUST never return `null` — there is always a valid input available (the built-in mic).

The ViewModel MUST pass `preferUsbC = false` on first call of any session unless the user has explicitly toggled to USB-C before starting measurement. This enforces the "built-in is always default" policy across cold starts, launches with USB-C already plugged in, and reconnect-after-disconnect scenarios.

**Rationale:**  
Centralising the "built-in is default" behaviour in a single method call with a boolean override makes the policy auditable and testable. The default parameter value (`false`) ensures that any caller who forgets to think about input selection gets the correct behaviour. The never-return-null guarantee simplifies caller logic — no error path needs to exist for "no valid input available."

---

### REQ-9.4 — Hot-Plug Routing Transitions

**Requirement:**  
The audio engine MUST handle four distinct hot-plug events, each with specific behaviour:

**1. USB-C connect while engine is running, built-in currently active (default case):**
- The `AudioDeviceCallback.onAudioDevicesAdded` fires for a USB-C device.
- The engine MUST NOT automatically switch the audio stream.
- The `AudioDeviceMonitor` MUST fire its `onUsbCAvailable` callback so the UI can reveal the mic source toggle per REQ-7.8.
- The currently-running stream on the built-in mic MUST continue uninterrupted.

**2. USB-C disconnect while engine is running, USB-C currently active:**
- The `onAudioDevicesRemoved` fires for the active USB-C device.
- The engine MUST immediately tear down the USB-C stream and open a new stream on the built-in mic.
- The `useUsbCInput` flag in the ViewModel MUST be cleared so subsequent starts default correctly.
- Detection state (noise floor history, BPH lock, lift angle, threshold multiplier, tape points) MUST be preserved.
- The `onUsbCUnavailable` callback MUST fire so the UI hides the mic source toggle.

**3. USB-C disconnect while engine is running, built-in currently active:**
- The engine MUST do nothing to the stream (it's already on built-in).
- The `onUsbCUnavailable` callback MUST fire so the UI hides the mic source toggle.

**4. User-initiated toggle via the UI (regardless of connect/disconnect):**
- The ViewModel calls `engine.requestRoutingSwitch(deviceId)` with the newly-selected device ID (0 for built-in, or the specific USB-C device ID).
- The engine MUST serialise this with the `mOperationInFlight` atomic flag so it cannot race against error-triggered restarts or hot-plug disconnect handling.
- Detection state MUST be preserved.
- The switch MUST complete within a few hundred milliseconds.

**Rationale:**  
The fundamental distinction from a naive hot-plug handler is that the app NEVER auto-routes to USB-C. Connecting USB-C is a passive event that only makes the option visible; the user's explicit intent is required to actually use it. Conversely, disconnecting USB-C while it is actively routed IS handled automatically — losing your active input is a different scenario than opting into a new one. The four cases listed above exhaustively enumerate the policy so that future refactors cannot accidentally reintroduce auto-switching on plug-in.

---



---

## Appendix A — Standard BPH Reference Table

| BPH    | Beats/second | Typical Usage |
|--------|--------------|---------------|
| 14,400 | 4.0 Hz       | Vintage pocket watches, slow-beat movements |
| 18,000 | 5.0 Hz       | Classic vintage wristwatches (pre-1960s) |
| 19,800 | 5.5 Hz       | Some Omega and Longines calibres |
| 21,600 | 6.0 Hz       | Common 1960s–1980s movements |
| 25,200 | 7.0 Hz       | Certain Seiko and Japanese calibres |
| 28,800 | 8.0 Hz       | Most common modern Swiss movement standard |
| 36,000 | 10.0 Hz      | High-frequency movements (Zenith El Primero, etc.) |

---

## Appendix B — Key Formulas Summary

| Metric        | Formula                                                              |
|---------------|----------------------------------------------------------------------|
| Target BPH    | `BPH = 3600 / Δt_avg`                                               |
| Timing Rate   | `rate (s/day) = ((actual_interval − ideal_interval) / ideal_interval) × 86400` |
| Amplitude     | `A = θ_lift / sin(π × (BPH / 7200) × Δt_lift)`                     |
| Trigger level | `trigger = noise_floor_RMS × threshold_multiplier`                  |

---

## Appendix C — Glossary

| Term               | Definition |
|--------------------|------------|
| **Beat Error**     | The asymmetry between the tick and tock arcs of the balance wheel, expressed in milliseconds. Zero beat error means tick and tock intervals are equal. |
| **Lift Angle**     | The angular span (in degrees) of the impulse face of the pallet jewels in a lever escapement. A fixed geometric property of the calibre. Default: 53°. |
| **Lift Time**      | The measured duration in seconds between the Unlock and Drop acoustic events of a single beat. Used to infer amplitude. |
| **Noise Floor**    | The rolling RMS energy level of the filtered audio signal during inter-tick silence. The adaptive baseline for tick detection. |
| **Rate**           | The deviation of the watch from perfect timekeeping, expressed in seconds gained or lost per day (s/day). |
| **Tick / Tock**    | Informal terms for the two alternating beat directions of the balance wheel oscillation (clockwise arc = tick, counter-clockwise = tock). |
| **Unlock**         | The first of the three acoustic sub-events in a single watch beat. The moment the pallet fork releases an escape wheel tooth. The primary timestamp for macro-timing. |
| **Impulse**        | The second acoustic sub-event. The escape wheel tooth sliding along the impulse face, transferring energy to the pallet fork and balance wheel. |
| **Drop**           | The third acoustic sub-event. The opposite pallet jewel arresting the next escape wheel tooth. Marks the end of the lift time measurement window. |
| **Hold-off**       | The dead-time period (50–80ms) after a primary tick detection during which macro peak detection is suppressed to prevent triple-counting. |
| **MMAP**           | Memory-mapped audio buffer access. A low-latency audio path in AAudio/Oboe that allows direct hardware buffer access, bypassing intermediate OS buffering. |
| **Adaptive Icon**  | Android 8+ launcher-icon format with separate foreground/background/monochrome layers, allowing OEM launchers to apply a shape mask (circle, squircle, etc.) without clipping the foreground. |
| **Safe Zone**      | The central 72×72 dp region of the 108×108 dp adaptive-icon canvas that is guaranteed visible under any launcher shape mask. |
| **Prefab**         | Android Gradle Plugin feature that exposes native headers and libraries from AAR dependencies (such as Oboe) to CMake via `find_package`. |
| **SPSC Queue**     | Single-Producer Single-Consumer lock-free ring buffer. Used to hand BeatEvents from the audio thread to the dispatch worker without mutexes. |

---

## Appendix D — Changelog

### Version 1.2 (current)
- **REQ-1.4 rewritten** — USB-C is now explicitly opt-in via a UI toggle rather than auto-selecting whenever present. Built-in mic is always the default across cold starts, launches with USB-C already connected, and post-disconnect fallback.
- **REQ-1.5 rewritten** — Bluetooth devices are silently filtered out of all enumeration rather than triggering a user-facing error dialog. Since the built-in mic is always present, "only Bluetooth available" is not a real case.
- **REQ-7.8 rewritten** — replaces the former Bluetooth error dialog requirement with a mic source toggle UI (visible only when USB-C is plugged in).
- **REQ-9.2 / REQ-9.3 / REQ-9.4 rewritten** — aligned with the new default-built-in, opt-in-USB-C, silent-ignore-Bluetooth policy. REQ-9.4 now enumerates all four hot-plug scenarios exhaustively.

### Version 1.1
- Added Module 6: Branding & Visual Identity (REQ-6.1 through REQ-6.5)
- Added Module 7: User Experience & Interaction (REQ-7.1 through REQ-7.8)
- Added Module 8: Build, Signing & Distribution (REQ-8.1 through REQ-8.4)
- Added Module 9: Permissions & Runtime Device Handling (REQ-9.1 through REQ-9.4)
- Extended glossary with branding, Android, and build-system terms.

### Version 1.0 (initial)
- Modules 1–5 covering Audio Capture, DSP & Detection, Timing & BPH, Amplitude & Escapement, and Visualization & UI.

---

*End of Requirements Specification v1.2*  
*KARGATHRA & Co. — Android Timegrapher*
