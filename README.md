# SuperSOS

Android emergency assistance app (Kotlin / Jetpack Compose) that works with a smart ring from the ECTRI app.

**Final version: 1.13.** [Download the latest APK](https://github.com/maythayus/SuperSOS/releases/latest)

This app is not a medical device. Do not rely on it as your only safety measure.

## Features

- **SOS**: GPS location, voice message (TTS) and emergency call.
- **Smart ring**: BLE scan and connection, heart rate, SpO2 and blood pressure.
- **Vital monitoring**: runs in the background and raises an alert with a 10 second countdown and a Cancel button.
- **"I can't speak" button**: reads out consent to care, GPS position, Maps link, latest vitals, height and weight.
- **Height and weight profile**: stored on the device only, excluded from Android backups, read to responders so they can estimate drug doses. It does not change the alert thresholds.
- **Voice recording**: records the phone microphone to `Music/SuperSOS`, from a button or a double tap on the ring.
- **SOS from the ring**: ring code `02` starts the SOS countdown.

## Tested status

Tested on a phone with an R13 ring:

| Feature | Status |
| --- | --- |
| Connection, heart rate, SpO2, blood pressure | Working. Blood pressure not compared with a cuff |
| Double tap to start and stop recording | Working, voice audible |
| SOS from the ring, Cancel button | Worked once |
| Recording with the screen locked | Not confirmed |
| Triple tap as the SOS gesture | Link with code `02` not proven, do not rely on it |
| Temperature | No value received from the R13 |

## Known limits

- **Emergency call**: Android may only open the dialer for an emergency number, so someone may still have to press Call. Without `CALL_PHONE`, the app opens the dialer.
- **Background monitoring is not guaranteed**: Bluetooth being off, an empty battery or the phone maker's battery management can interrupt it. Exempt the app from battery optimization.
- **Detection delay**: measurements rotate every 45 seconds and an alert needs 2 consecutive abnormal readings, so a confirmed alert takes about 6 minutes.
- **A ring reporting 0 is treated as no reading**, so the app does not detect a cardiac arrest this way.
- **Temperature never raises an alert**, it is a stored skin value.
- **After a phone restart**, open the app once: Android does not allow the microphone or location in the background until then.
- **A double tap also pauses music**, as the ring handles it as play/pause.
- Recording someone without their consent may be illegal depending on your country.

## Quick start

1. If you installed V1, uninstall it first: later versions use a different signing key.
2. Force stop the ECTRI app, otherwise it may keep the ring busy.
3. Enable Bluetooth and location, then open SuperSOS.
4. Tap "Scanner / trouver la bague", then connect to your ring.
5. Tap "Activer le double tap (enregistrement)" to enable ring gestures.
6. Turn on "Monitoring vital" and allow notifications and the battery exemption.

If an alert triggers by mistake, tap Annuler before the countdown ends.

## Default monitoring thresholds

- SpO2: `< 88%`
- Heart rate: `> 160 bpm` or `< 40 bpm`
- Blood pressure: `SYS > 180` or `DIA > 120`

## Android permissions

- Location: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- Bluetooth (Android 12+): `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- Calling: `CALL_PHONE`
- Recording: `RECORD_AUDIO`
- Background: `FOREGROUND_SERVICE` and its connected device, location and microphone types, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- Alerts: `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`

## ECTRI BLE (technical details)

Rings may use the standard Bluetooth base instead (`0000EFE0-0000-1000-8000-00805F9B34FB`), so the app matches the 16-bit ids `EFE0`, `EFE1` and `EFE3`.

- Service: `F000EFE0-0451-4000-0000-00000000B000`
- TX (write): `F000EFE1-0451-4000-0000-00000000B000`
- RX (notify): `F000EFE3-0451-4000-0000-00000000B000`
- CCCD: `00002902-0000-1000-8000-00805f9b34fb`

RX frames are 20 bytes. Measurements are identified by byte 0:

- `0x0A`: heart rate
- `0x11`: blood pressure
- `0x12`: SpO2
- `0x40`: temperature, byte 1 being the frame type (`0` last, `1` history, `2` count, `3` complete, `4` current)

Commands and ring events:

| Bytes | Meaning |
| --- | --- |
| `FC 09 <test>` | Start a test: `02` HR, `05` BP, `06` SpO2, `07` temperature |
| `FC 40 02`, then `FC 40 01` | Ask how many temperatures are stored, then read them |
| `FC 5E 62 03 09 00 08 ... 30` | Set the interaction mode to music control |
| `FC 3C 03` | Ring gesture: double tap on an R13 |
| `FC 3C 02` | Ring gesture: second code seen on an R13 |

## Versions

| Version | Changes |
| --- | --- |
| [1.13](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.13) | Final version: journal of SOS events and recording stops |
| [1.12](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.12) | SOS from the ring, recording on code `03` only |
| [1.11](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.11) | Double tap starts and stops the recording |
| [1.10](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.10) | Readable ring journal with timestamps |
| [1.9](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.9) | Interaction mode set from the app |
| [1.8](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.8) | Voice recording with the phone microphone |
| [1.7](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.7) | Ring journal to find the double tap signal |
| [1.6](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.6) | Temperature sequence aligned with ECTRI |
| [1.5](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.5) | Background monitoring |
| [1.4](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.4) | A ring reporting 0 no longer raises an alert |
| [1.3](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.3) | Reliable ring connection, tested with an R13 |
| [1.2](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.2) | Permission and crash fixes |
| [1.1](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1.1) | Height and weight profile |
| [V1](https://github.com/maythayus/SuperSOS/releases/tag/SuperSOS.V1) | First release |

## Links

- [All releases](https://github.com/maythayus/SuperSOS/releases)
- [XDA thread](https://xdaforums.com/t/supersos-android-survival-pack.4779243/)
- [Smart ring](https://maythayus1.store/produit/new-smart-ring-digital-display-5atm-waterproof-heart-ratesleephealth-monitoring-multisports-fashion-ring-for-men-women-2/)
