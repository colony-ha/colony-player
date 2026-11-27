# COLONY PLAYER for Android TV / Google TV

This native Android TV client is built separately from the Cloudflare Pages website.

## Build
The GitHub Actions workflow builds:
- Debug APK for device testing
- Release APK and AAB (unsigned)

## Device setup
On first launch the app creates a unique device ID and device key, registers the device through the Pages API, then starts its seven-day trial. The device key is stored locally in app-private preferences and should never be shared.

Use the device details shown in the app to connect the device through the COLONY PLAYER web portal. Add only playlists and streams that you are authorized to access.

## Current implementation
- Device registration, login and entitlement refresh
- Playlist metadata and secure source retrieval
- M3U parsing, basic Live / Movies / Series classification
- Search, favorites and Media3 playback
- Remote-control focusable controls

Before Google Play publication, complete physical-device compatibility testing, accessibility and policy review, privacy disclosures, branded assets, and configure a private upload signing key. This build is not yet a Play Store release.
