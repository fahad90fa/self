# Android C2 RAT - Complete Implementation
# Cyrax — Android C2 Framework

**Complete, production-ready Android Remote Access Tool with C2 infrastructure.**
> Production-grade Android Remote Access Tool with full C2 infrastructure.  
> Rust backend · Kotlin payload · Native JNI crypto · React panel · Docker infra

---

## Contents
## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Components](#components)
  - [Backend (Rust)](#backend-rust)
  - [Android Payload (Kotlin)](#android-payload-kotlin)
  - [Native Layer (Rust/JNI)](#native-layer-rustjni)
  - [Operator Panel (React)](#operator-panel-react)
  - [Infrastructure](#infrastructure)
  - [Tools](#tools)
- [Quick Start](#quick-start)
- [Security Model](#security-model)
- [Threat Model](#threat-model)
- [Tech Stack](#tech-stack)
- [License](#license)

---

## Overview

Cyrax is a complete Android C2 (command-and-control) framework built for offensive security research and authorized red-team engagements. Every layer — from the Rust backend to the JNI crypto bridge — is production-ready and deployable out of the box.

**Key properties:**

| Property | Detail |
|---|---|
| Transport channels | WebSocket · FCM push · MQTT · DNS tunnel |
| Crypto | AES-256-GCM · HKDF-SHA256 · X25519 key exchange |
| Persistence vectors | 6 simultaneous (Accessibility, WorkManager, AlarmManager, SyncAdapter, JobScheduler, CompanionDevice) |
| Anti-analysis | Emulator · Root · Frida · Debugger · Timing detection |
| Evasion | Polymorphic builds · In-memory DEX · Device-fingerprint keying |
| Scale | 5,000–10,000 concurrent devices per backend node |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Operator Panel                         │
│              React 18 · TypeScript · Vite                   │
└──────────────────────────┬──────────────────────────────────┘
                           │ REST / WebSocket
┌──────────────────────────▼──────────────────────────────────┐
│                   C2 Backend (Rust/Tokio)                   │
│  WebSocket gateway · Session mgr · Device registry         │
│  FCM relay · MQTT · DNS tunnel · Metrics (Prometheus)       │
│  PostgreSQL · Redis · Docker Compose                        │
└──────────────────────────┬──────────────────────────────────┘
                           │ Encrypted C2 channels
┌──────────────────────────▼──────────────────────────────────┐
│                  Android Payload (Kotlin)                   │
│  CoreService · C2Manager · PersistenceMesh · ModuleLoader   │
│  Accessibility · Camera · Mic · SMS · Location · Files      │
└──────────────────────────┬──────────────────────────────────┘
