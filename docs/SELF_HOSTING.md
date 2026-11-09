# Self-hosting Call, step by step

This guide takes you from zero to a working family calling app: your own server and your own APK.
Plan about an hour the first time. No Go knowledge is needed; basic Linux terminal skills are enough.

## What you need

| | Notes |
|---|---|
| **A small Linux server (VPS)** | 1 vCPU, 1 GB RAM, Ubuntu/Debian. Any cheap provider works. It needs a public IP. |
| **A domain or subdomain** | e.g. `call.yourname.com`, pointing to the server's IP. HTTPS is required. No domain? `call.<ip-with-dashes>.sslip.io` works too (e.g. `call.203-0-113-7.sslip.io`). |
| **A computer to build the APK** | Linux, macOS or Windows with [Android Studio](https://developer.android.com/studio) (it brings the Android SDK and Java). |
| **Firebase project** *(optional)* | Only for instant wake-ups of sleeping phones. Without it, calls still arrive, just a bit later on some phones. |

The server address is **built into the APK**. That's why everyone runs their own server and builds their own APK.

---

## Part 1: the server

### 1.1 Install the basics

On the server (as root, or with `sudo`):

```bash
apt update && apt install -y docker.io docker-compose-v2 nginx certbot python3-certbot-nginx git openssl curl
```

### 1.2 Get the code and run the setup script

```bash
git clone https://github.com/<you>/grandma-call.git /opt/call-src
cd /opt/call-src/deploy
./setup.sh call.yourname.com
```

`setup.sh` creates `.env` with random secrets and an nginx config for your domain. **It prints your admin panel
link, password and first join code once. Save them.** They also stay in `deploy/.env`.

### 1.3 DNS, HTTPS and firewall

1. At your domain provider, add an **A record**: `call.yourname.com` → your server's IP. Skip this with sslip.io.
2. Enable the site and get a certificate:
   ```bash
   cp nginx-call.yourname.com.conf /etc/nginx/sites-enabled/
   nginx -t && systemctl reload nginx
   certbot --nginx -d call.yourname.com
   ```
3. Open the ports (example for `ufw`):
   ```bash
   ufw allow 443/tcp && ufw allow 80/tcp
   ufw allow 3478/udp && ufw allow 3478/tcp && ufw allow 49160:49200/udp
   ```
   Ports 3478 and 49160–49200 are for the TURN relay, used when two phones can't reach each other directly.

### 1.4 Start it

```bash
docker compose up -d --build
docker compose logs server     # should end with "call server on :8080"
```

Open the admin panel link from step 1.2 in a browser and log in. 🎉

### 1.5 (Optional) Firebase push on the server

In the [Firebase console](https://console.firebase.google.com): ⚙️ **Project settings → Service accounts →
Generate new private key**. Upload the file to the server as `deploy/secrets/fcm.json`, then:

```bash
chown 65534:65534 secrets/fcm.json && chmod 400 secrets/fcm.json
docker compose restart server   # logs: "fcm: push enabled for project …"
```

This file is a secret: never commit it or send it to anyone.

---

## Part 2: the Android app

### 2.1 Get the code and open it

```bash
git clone https://github.com/<you>/grandma-call.git
```

Open the `android/` folder in Android Studio and let it sync. It downloads Gradle and the SDK parts it needs.

### 2.2 Tell the app where your server is

Edit `android/local.properties`. Android Studio creates it; add the last line:

```properties
sdk.dir=/path/to/Android/Sdk
call.server=https://call.yourname.com
```

If you forget this, the build stops with a clear "Server address missing" message.

### 2.3 Create your signing key (once, keep it forever)

```bash
cd android
keytool -genkeypair -keystore zeng.keystore -alias zeng -keyalg RSA -keysize 2048 -validity 10000
```

Then create `android/keystore.properties`:

```properties
storeFile=zeng.keystore
storePassword=the-password-you-chose
keyAlias=zeng
keyPassword=the-password-you-chose
```

> ⚠️ **Back up `zeng.keystore` and the password.** Updates must be signed with the same key. If you lose it,
> every phone has to uninstall the app and install it again.

Without a key the build still works, signed with a debug key, but that's only for trying things out.

### 2.4 (Optional) Firebase for the app

Firebase console → **Add app → Android** → package name **`az.zeng`** → download `google-services.json` →
put it in `android/app/`. Without it the app builds and works, just without push wake-ups.

### 2.5 Build

```bash
cd android
./gradlew assembleRelease          # Windows: gradlew.bat assembleRelease
```

The APK is at `android/app/build/outputs/apk/release/app-release.apk`.

---

## Part 3: phones

1. Admin panel → **Tətbiq** (App) → upload `app-release.apk`.
2. Admin panel → **Qruplar** (Groups): copy the **APK link** and the **join code**.
3. On each phone, open the link, download and install the APK. If Android or Play Protect warns about an unknown
   app, choose **More details → Install anyway**.
4. Open Call, enter a **name** and the **join code**. Allow microphone and camera.
5. Everyone in the group now sees each other. Call! 📞

**Updates:** build a new APK with a higher `versionCode` in `android/app/build.gradle.kts`, then upload it in the
admin panel. Every phone shows an **Update** banner.

---

## Troubleshooting

| Problem | Check |
|---|---|
| App says it can't reach the server | The domain must have a valid Let's Encrypt certificate (certbot). `call.server` must start with `https://`. |
| Calls connect but there's no sound | The TURN ports (3478, 49160–49200/udp) must be open. `TURN_EXTERNAL_IP` in `.env` must be the public IP. |
| Calls arrive late on a sleeping phone | Set up Firebase (1.5 and 2.4). On Samsung, Xiaomi and Honor phones, exclude Call from battery optimization. |
| Update won't install | It must be signed with the same key as the installed app and have a higher `versionCode`. |
| Server logs | `docker compose logs -f server` shows each call: who called, ringing, answered, ended. |
