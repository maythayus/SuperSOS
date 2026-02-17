# SuperSOS

Application Android (Kotlin / Jetpack Compose) d’assistance d’urgence.

## Fonctionnalités

- SOS :
  - Récupération de la position GPS.
  - Lecture vocale (TTS) de la position.
  - Appel d’urgence (automatique si permission `CALL_PHONE`, sinon ouverture du composeur).

- Bague connectée (App **ECTRI**) :
  - Scan BLE, connexion, notifications.
  - Lecture/affichage des constantes :
    - Fréquence cardiaque (FC)
    - SpO2 (oxygénation)
    - Tension (SYS/DIA)
    - Température

- Monitoring “engagement vital” (Option A) :
  - Surveillance périodique des constantes.
  - Détection par règles + garde-fou (2 mesures consécutives hors seuil).
  - Alerte avec compte à rebours (10s) + bouton Annuler.
  - Si non annulé : déclenche le workflow SOS (GPS + voix + appel).

- Bouton “Je ne peux pas parler” :
  - Lit à voix haute un message clair pour les secours :
    - consentement aux soins
    - position GPS + lien Maps
    - dernières constantes mesurées

## Permissions Android

L’app demande (selon les actions) :

- Localisation :
  - `ACCESS_FINE_LOCATION`
  - `ACCESS_COARSE_LOCATION`

- Bluetooth (Android 12+) :
  - `BLUETOOTH_SCAN`
  - `BLUETOOTH_CONNECT`

- Appel (si appel direct) :
  - `CALL_PHONE`

## BLE ECTRI (détails techniques)

UUID principaux (extraits de l’app ECTRI) :

- Service : `F000EFE0-0451-4000-0000-00000000B000`
- TX (write) : `F000EFE1-0451-4000-0000-00000000B000`
- RX (notify) : `F000EFE3-0451-4000-0000-00000000B000`
- CCCD : `00002902-0000-1000-8000-00805f9b34fb`

Types de messages (byte 0 des trames RX 20 bytes) :

- `0x0A` : FC
- `0x11` : Tension
- `0x12` : SpO2
- `0x40` : Température

## Seuils par défaut du monitoring

- SpO2 : `< 88%`
- FC : `> 160 bpm` ou `< 40 bpm`
- Temp : `> 40°C` ou `< 35°C`
- Tension : `SYS > 180` ou `DIA > 120`

## Utilisation rapide

1. Active Bluetooth + localisation.
2. Ouvre l’app.
3. Dans “Bague ECTRI” :
   - “Scanner / trouver la bague”
   - “Connecter …”
4. Active “Monitoring vital”.
5. En cas d’alerte : Annuler si faux positif, sinon laisse l’app déclencher SOS.

## Avertissement

Cette application n’est pas un dispositif médical. Les mesures et alertes sont indicatives et peuvent être imprécises selon le matériel, le port de la bague et les conditions d’usage.
