# SuperSOS

Android emergency assistance app (Kotlin / Jetpack Compose).

## Features

- SOS:
  - Get GPS location.
  - Text-to-Speech (TTS) voice message with the location.
  - Emergency call (automatic if `CALL_PHONE` is granted, otherwise open the dialer).

- Smart ring (App **ECTRI**):
  - BLE scan, connect, notifications.
  - Read/display vitals:
    - Heart rate (HR)
    - SpO2 (oxygenation)
    - Blood pressure (SYS/DIA)
    - Temperature

- Vital monitoring (Option A):
  - Periodic vitals checks.
  - Rule-based detection + safeguard (2 consecutive out-of-threshold readings).
  - Alert with a 10s countdown + Cancel button.
  - If not cancelled: triggers SOS workflow (GPS + voice + call).

- “I can’t speak” button:
  - Reads a clear TTS message for first responders:
    - consent for emergency care
    - GPS position + Maps link
    - latest measured vitals

## Android permissions

The app requests (depending on actions):

- Location:
  - `ACCESS_FINE_LOCATION`
  - `ACCESS_COARSE_LOCATION`

- Bluetooth (Android 12+):
  - `BLUETOOTH_SCAN`
  - `BLUETOOTH_CONNECT`

- Calling (for direct calls):
  - `CALL_PHONE`

## ECTRI BLE (technical details)

Main UUIDs (extracted from the ECTRI app):

- Service : `F000EFE0-0451-4000-0000-00000000B000`
- TX (write) : `F000EFE1-0451-4000-0000-00000000B000`
- RX (notify) : `F000EFE3-0451-4000-0000-00000000B000`
- CCCD : `00002902-0000-1000-8000-00805f9b34fb`

Message types (byte 0 of 20-byte RX frames):

- `0x0A`: HR
- `0x11`: Blood pressure
- `0x12`: SpO2
- `0x40`: Temperature

## Default monitoring thresholds

- SpO2: `< 88%`
- HR: `> 160 bpm` or `< 40 bpm`
- Temp: `> 40°C` or `< 35°C`
- Blood pressure: `SYS > 180` or `DIA > 120`

## Quick start

1. Enable Bluetooth + location.
2. Open the app.
3. In “ECTRI ring”:
   - “Scan / find ring”
   - “Connect …”
4. Enable “Vital monitoring”.
5. If an alert triggers: cancel if it is a false positive, otherwise let the app trigger SOS.

## Disclaimer

This app is not a medical device. Measurements and alerts are indicative and may be inaccurate depending on hardware, how the ring is worn, and usage conditions.


APP :

https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1

https://xdaforums.com/t/supersos-android-survival-pack.4779243/

Smart RING!

https://maythayus1.store/produit/new-smart-ring-digital-display-5atm-waterproof-heart-ratesleephealth-monitoring-multisports-fashion-ring-for-men-women-2/
