# GFBS : Auralis

**GFBS Auralis** is a Minecraft client-side audio control mod designed for advanced gameplay and content creators.  
Built on **OpenAL**, it provides a **completely independent audio system separate from the vanilla game**, enabling more precise and controllable 3D sound playback and management.

This mod is suitable for scenarios that require higher‑level sound control, such as **command‑block systems, server scripts, map‑making, story‑driven performances, and sound experiments**.

---

## Core Features

### 1. Independent Audio Engine

* Does not use the vanilla `SoundEngine` playback pipeline  
* Directly controls sound sources (Source / Buffer) via OpenAL  
* Avoids limitations and conflicts of the vanilla sound system

### 2. Accurate 3D Spatial Audio

* Supports world‑coordinate sound sources (not bound to the player)  
* Supports **min‑distance / max‑distance** attenuation model  
  * `min‑distance`: full‑volume distance  
  * `max‑distance`: complete silence distance  
* Distance attenuation logic is fully controlled by the mod, not relying on OpenAL’s default model

### 3. Stable, Controllable SoundEvent Parsing

* Supports vanilla `SoundEvent`  
* Automatically avoids pitch instability caused by random SoundEvent variations  
* The same sound‑effect ID is consistently resolved to the same concrete audio resource

### 4. Powerful Command System (Command‑Block Compatible)

* All audio operations can be performed via the `/gfbs_auralis` command  
* Supports **player objects or player groups (@p / @a / specified players)**  
* Fully compatible with command blocks and server consoles

Supported operations include:

* Play / Pause / Stop  
* Real‑time adjustment of volume, pitch, speed  
* Switch between static sounds and world sounds  
* Dynamic position updates  
* Set loop, priority, and distance parameters

### 5. Client‑Safe Execution Model

* All OpenAL operations **execute only on the client**  
* The server sends “control instructions” via network packets  
* Prevents threading errors, OpenAL state exceptions, and illegal calls

### 6. Multi‑Source Concurrency & Resource Management

* Source‑pool management  
* Buffer caching and recycling  
* Supports a large number of concurrently existing custom sound instances

---

## Target Audience

* Map authors / Story‑map creators  
* Advanced command‑block users  
* Server developers  
* Mod developers (requiring independent audio control)  
* Experimental projects with in‑depth requirements for Minecraft’s sound system

---

## Design Goals

* Predictable (Deterministic)  
* Independent of vanilla sound‑playback logic  
* Friendly to command systems and servers  
* Aimed at advanced “orchestrated audio” gameplay, not simply replacing vanilla sounds

---

## Notes

* This is a **client‑side mod** (the server is only responsible for commands and network synchronization)  
* Ensure the client supports OpenAL before use (Minecraft satisfies this by default)  
* Not recommended to use alongside mods that deeply modify the audio system (compatibility should be tested individually)

---

## License

This project is open‑source under the MIT License.  
You are free to use, modify, and distribute it, subject to the terms of the license.

---

- This document was written by AI.
