# WenuLink

## Introduction

WenuLink is an Android application designed to function as a middleware interface—a software bridge—between DJI drones and Ground Control Stations (GCS) that utilize the MAVLink protocol.

The name **"Wenu"** originates from Mapudungun, the language of the Mapuche people native to southern Chile. In their worldview, *Wenu* means _sky_, _high_, or _above_ (specifically *Wenu Mapu*, the "land above" or celestial world). This name represents the project's core concept: a tool that enables a connection to the sky, expanding drone capabilities for autonomy and visualization.

---

## Goal & Vision

The primary objective of WenuLink is to “translate” DJI’s proprietary protocol into MAVLink. By bridging this gap, the project aims to unlock the potential of commercial DJI drones — which typically operate in a closed ecosystem — allowing them to be controlled by industry-standard MAVLink GCS platforms.

This integration opens a wide range of possibilities, including:

- Autonomous mission execution
- Complex flight planning
- Integration with computer vision technologies
- Integration with fleet management systems

---

## Features

WenuLink is developed using **Kotlin** and **Jetpack Compose**, and its architecture is being continuously expanded. Below is the current status of the main capabilities of the system:

### Capabilities Status Overview

| Feature                | Status             |
|------------------------|--------------------|
| MAVLink Middleware     | 🟡 In Development |
| WebRTC Video Streaming | 🟢 Fully Developed |
| Telemetry Monitoring   | 🟡 In Development |
| Flight Control         | 🟡 In Development  |
| Mission Control        | 🟡 In Development  |
| Video/Photo Capture    | 🔴 Not Developed   |

---

## Getting Started

For detailed setup instructions (DJI SDK API keys, WebRTC signaling servers, MAVLink endpoints, and Android Studio configuration), please refer to our **Getting Started Guide**:

📄 [_Click here to view the Getting Started Guide_](GettingStarted.md)

---

## Target Audience

WenuLink is designed for:

- Developers and researchers working with drone autonomy who require access to DJI hardware via standard protocols
- Fleet managers looking to integrate DJI drones into existing MAVLink-based management systems
- Drone operators seeking to use specific GCS software (like QGroundControl or Mission Planner) with DJI platforms

---

## Team

- **Angel Ayala Maldonado** — PhD in Computational Science at the Escola Politécnica da Universidade de Pernambuco (POLI-UPE), Brazil; Co-advisor of the [DeltaV Drones](https://deltavquad.github.io/) team.
- **Max Johenneken** — Researcher at the Hochschule Bonn-Rhein-Sieg (H-BRS), Germany; specialization in UAV systems, machine learning and computer vision.
- **Eliton Sena de Souza** — Operational Leader of the [DeltaV Drones](https://deltavquad.github.io/) team; Computer Engineering student at the Escola Politécnica da Universidade de Pernambuco (POLI-UPE), Brazil.
- **Timon Schreiber** - Software Developer (Placeholder)
---

## Contribute

WenuLink is in active development. We welcome contributions from the community, particularly in the following areas:

- Implementation of flight control commands (Takeoff/Land/Navigate)
- Mission controller module logic
- Improvements to WebRTC streaming implementation

If you wish to contribute, please **fork the repository** and submit a **Pull Request**.
