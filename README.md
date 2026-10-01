# meshline
Offline peer-to-peer emergency mesh network for iOS and Android. Zero cellular or internet required — store-carry-forward multi-hop SOS, breadcrumb ACK routing, and Ed25519 anti-replay security.
# MESHLiNE — Offline Emergency Mesh Network

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20PWA-blue.svg)]()
[![Offline First](https://img.shields.io/badge/Network-100%25%20Offline-success.svg)]()

> **When power grids fail and cell towers collapse, MESHLiNE turns ordinary smartphones into a self-healing, multi-hop emergency mesh network.**

---

## 📌 Problem & Mission
During catastrophic natural disasters (earthquakes, cyclones, floods), conventional communication infrastructure collapses or overloads within minutes. Trapped survivors are unable to call for help, and first responders search blindly.

**MESHLiNE** requires **zero internet and zero cellular connectivity**. Messages hop peer-to-peer across phones over Bluetooth and local radios until reaching a rescuer, and signed confirmations travel back to the survivor.

---

## ⚡ Core Features

- 🆘 **3-Tap Panic SOS**: Designed for extreme physical stress; sends triage category, severity, group count, GPS coordinates, and battery level in 3 taps.
- 🔄 **Store-Carry-Forward Multi-Hop Relay**: Messages persist locally and automatically forward when new physical peer contacts appear.
- 🎯 **Targeted Breadcrumb ACK Routing**: Eliminates network congestion by returning rescuer confirmations along the reverse incoming node path rather than flooding.
- 🛡️ **Cryptographic Authenticity & Anti-Replay**: Ed25519 signing with a 64-bit sliding replay window and per-link rate limits to thwart rogue transmitters and Sybil floods.
- ⚠️ **"Emergency Beats Authenticity" Rule**: If cryptographic keystore fails during a disaster, unverified SOS packets are still dispatched under strict rate limits.
- 🔋 **Battery-Aware Operation**: Normal, Saver, and Critical duty-cycling modes, concluding with a low-power last-gasp beacon below 5% battery.
- 📲 **Cross-Platform Delivery**: Native Android background engine (Kotlin) and installable offline standalone app (iOS & Android).
