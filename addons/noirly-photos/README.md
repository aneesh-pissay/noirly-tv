# Noirly Photos

Your Google Photos on the TV, in the Noirly look: choose photos or albums on your phone, and the TV keeps them
for a full-screen slideshow and for the screensaver. For the [Noirly TV](../../README.md) launcher it is also a
module card; on any other Android TV home it is an ordinary app.

## How it works

Google lets apps open only what you pick, through the
[Photos Picker API](https://developers.google.com/photos/picker/guides/get-started-picker). Your phone does both
steps by scanning a QR code on the TV:

1. **Sign in** (the first time, and when Google asks again). The TV shows a QR code for Google's sign-in. On your
   phone, sign in and allow Noirly Photos to open the photos you pick. Google then sends the phone to a small
   hand-off page ([`oauth/index.html`](oauth/index.html), published on GitHub Pages). That page posts the one-time
   code to a random [ntfy.sh](https://ntfy.sh) topic that only this sign-in knows, and the TV reads it from there.
   The phone never has to reach the TV, so any network works, mobile data included. The TV keeps the sign-in and
   renews it by itself.
2. **Pick**. The TV shows a second QR code. Google Photos opens on the phone; choose photos or a whole album (up to
   300) and tap **Done**.
3. The TV downloads the photos at screen size into its own storage. Google's download links last only an hour, so
   the photos are kept on the TV. After that, the slideshow and screensaver work without the internet.

**Choose again** replaces the set; **Remove** deletes it. Videos are skipped. Left and Right step through the
slideshow; Back leaves it.

**Screensaver.** On Android TV, pick **Noirly Photos** in Settings → Device Preferences → Screen saver. Google TV's
settings list only Google's own screensaver, so set it over ADB instead:

```bash
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components dev.noirly.tv.photos/.PhotosDream'
```

The screensaver starts after the TV's idle time (`settings get system screen_off_timeout`, in milliseconds). To go
back to the launcher's clock, set `com.gathin.tvhome/.ClockDream` the same way.

Why ntfy.sh and not a link straight to the TV? A phone on mobile data, another Wi-Fi, a VPN, or behind the
router's AP isolation can't reach the TV, which is what happened here. The code on the topic is useless to anyone
else: Google turns it into access only with the PKCE verifier, which never leaves the TV, plus the client secret,
and it expires in minutes.

Why not Android's own sign-in? Google refuses the Photos Picker scope for Android OAuth clients ("This OAuth client
is not permitted to request the following scopes"). Its TV sign-in flow doesn't allow the scope either. A Web
application client is the kind that may ask for it.

## Google Cloud setup (once)

In your own Google Cloud project (free; only you use the app, so Google doesn't need to verify it):

1. **APIs & Services → Library**: enable **Google Photos Picker API**.
2. **Google Auth Platform → Branding**: app name `Noirly Photos`, your email as contact. Audience **External**,
   status **Testing**. **Audience → Test users**: add the Google account whose photos you want.
3. **Data Access**: add `https://www.googleapis.com/auth/photospicker.mediaitems.readonly` and save.
4. **Clients → Create client → Web application**, named `Noirly Photos`. Under **Authorized redirect URIs**, add
   the hand-off page: `https://aneesh-pissay.github.io/noirly-tv/oauth/` (or your fork's Pages address).
5. Copy [`oauth.properties.example`](oauth.properties.example) to `oauth.properties` in this folder and fill in the
   client's id and secret, plus the same redirect URI. The file is git-ignored; the secret is built into the app
   and never committed.
6. Publish [`oauth/index.html`](oauth/index.html) on GitHub Pages at that address, for example as `oauth/index.html`
   on a `gh-pages` branch, then turn Pages on for that branch in the repository settings.

While the project is in Testing, Google expires the sign-in after about a week. Choose photos then shows the
sign-in code again.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Google sign-in and the Photos Picker API, downloading the photos you picked, and reading the sign-in topic on ntfy.sh. |

The only Google permission is `photospicker.mediaitems.readonly`: the app can see only the photos you pick, and
only for the hour after you pick them. During sign-in the one-time code passes through ntfy.sh, as above. Nothing
else is sent anywhere. There is no analytics and no background service, and the photos stay in the app's private
storage (backups off).

## Build and install

JDK 17 and Android SDK platform 34, as for the launcher:

```bash
cd addons/noirly-photos && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Build with `oauth.properties` in place, or the app says sign-in is not set up. The release build is signed with the
debug key so it installs over ADB. One dependency: ZXing core, for the QR codes.
