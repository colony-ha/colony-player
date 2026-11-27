# COLONY PLAYER

Official website and device portal for COLONY PLAYER.

## Current scope
- Responsive Arabic-first marketing website (Arabic, English, French)
- Product overview, supported TV platforms, setup steps, device connection UI, and support section
- Device sign-in and playlist management are implemented as Cloudflare Pages Functions; they become live after the D1 binding and encrypted secrets are configured.

## Planned platform
- Samsung Smart TV (Tizen)
- LG Smart TV (webOS)

## Subscription rules
- 7-day free trial per device, beginning at first successful device registration
- One annual activation code type: exactly 365 days
- Each activation code binds to one device
- Annual subscription sales are handled by admin through WhatsApp or email
- Users provide their own authorized playlist/source

## Local preview
Open index.html in a browser.

## Cloudflare Pages Functions API

The API is implemented under `functions/api` and uses the same Pages origin:
- `POST /api/device/login` — first successful device login creates the device's 7-day trial; later logins verify its Device Key.
- `GET /api/device/me` — validates the active session and entitlement.
- `GET /api/playlists` — lists the signed-in device's playlists.
- `POST /api/playlists` — stores an encrypted M3U/M3U8 or Xtream playlist URL.
- `DELETE /api/playlists/:id` — deletes a playlist belonging to that device.

### Cloudflare setup
1. Create a Cloudflare D1 database for this project.
2. Run the full contents of `schema.sql` in that database's SQL Console.
3. In the Pages project, open **Settings → Functions → D1 database bindings** and add binding name `DB`, selecting the new database.
4. In **Settings → Variables and Secrets**, add these as encrypted secrets:
   - `DEVICE_KEY_PEPPER`: a long random secret (at least 32 random bytes).
   - `PLAYLIST_ENCRYPTION_KEY`: a different long random secret.
5. Trigger a new Pages deployment after the binding and secrets are saved.

The website stores the session token in the browser's session storage. Playlist URLs are encrypted before they are written to D1. Do not reuse or publish either secret. A device's 7-day trial starts only on its first successful login.
