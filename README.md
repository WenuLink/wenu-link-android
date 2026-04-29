# WenuLink

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

📄 [_Click here to view the Getting Started Guide_](https://github.com/WenuLink/wenu-link-android/wiki/GettingStarted)

---

## Example Use Cases

To see what you can do with WenuLink — from autonomous flight plans and live video streaming to aerial photogrammetry — check out the **Example Use Cases** page:

📄 [_Click here to view Example Use Cases_](https://github.com/WenuLink/wenu-link-android/wiki/Example-Use-Cases)

For developers and contributors looking to verify compatibility with specific drone models, see the **Test Procedures**:

📄 [_Click here to view Test Procedures_](https://github.com/WenuLink/wenu-link-android/wiki/Test-Procedures)

---

## Target Audience

WenuLink is designed for:

- Developers and researchers working with drone autonomy who require access to DJI hardware via standard protocols
- Fleet managers looking to integrate DJI drones into existing MAVLink-based management systems
- Drone operators seeking to use specific GCS software (like QGroundControl or Mission Planner) with DJI platforms

---

## Team

- **Angel Ayala Maldonado** — PhD in Computational Science at the Escola Politécnica da Universidade de Pernambuco (POLI-UPE), Brazil; Co-advisor of the [DeltaV Drones](https://deltavquad.github.io/) team.
- **Maximilian Johenneken** — Researcher at the Hochschule Bonn-Rhein-Sieg (H-BRS), Germany; specialization in UAV systems, machine learning and computer vision.
- **Eliton Sena de Souza** — Operational Leader of the [DeltaV Drones](https://deltavquad.github.io/) team; Computer Engineering student at the Escola Politécnica da Universidade de Pernambuco (POLI-UPE), Brazil.
- **Timon Schreiber** - Computer Science Student (H-BRS)
---

## Contribute

WenuLink is in active development. All types of contributions are encouraged and valued. See the [CONTRIBUTING.md](/CONTRIBUTING.md) file for different ways to help and details about how this project handles them. Please make sure to read the relevant section before making your contribution. It will make it a lot easier for us maintainers and smooth out the experience for all involved. The community looks forward to your contributions. 🎉

And if you like the project, but just don't have time to contribute, that's fine. There are other easy ways to support the project and show your appreciation, which we would also be very happy about:
- Star the project
- Tweet about it
- Refer this project in your project's readme
- Mention the project at local meetups and tell your friends/colleagues
