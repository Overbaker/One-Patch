# Publishing DeadZone on GitHub — step-by-step

## Prerequisites

- GitHub account: **overbaker**
- `gh` CLI installed (recommended) — `brew install gh` / `apt install gh`
- Repository will be at: https://github.com/overbaker/deadzone

## Step 1 — generate signing key (LOCAL, ONCE)

```bash
cd /home/ubuntu/Project/CCG
./scripts/gen-keystore.sh
# Creates release.jks. **BACK IT UP** (e.g. encrypted USB + cloud).
```

Then create the local config:
```bash
cp keystore.properties.template keystore.properties
# edit keystore.properties with your real passwords
```

Verify the signed APK builds:
```bash
./gradlew :app:assembleRelease
ls -lh app/build/outputs/apk/release/app-release.apk
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Step 2 — initialize git & create the GitHub repo

```bash
cd /home/ubuntu/Project/CCG
git init
git add .
git commit -m "Initial public release: DeadZone v1.0.0"

# Login to GitHub
gh auth login   # follow prompts, choose overbaker account

# Create the public repo and push
gh repo create overbaker/deadzone \
  --public \
  --source=. \
  --remote=origin \
  --description="Screen Touch DeadZone — define a no-input zone on your Android screen" \
  --push

# Open it
gh browse
```

If you prefer the web UI: visit https://github.com/new while signed in as overbaker.

## Step 3 — add GitHub Actions secrets (so CI can sign)

```bash
# encode keystore as base64 (single line)
KEYSTORE_B64=$(base64 -w 0 release.jks)

gh secret set RELEASE_KEYSTORE_BASE64 -b "$KEYSTORE_B64"
gh secret set RELEASE_STORE_PASSWORD  -b 'your-store-password'
gh secret set RELEASE_KEY_ALIAS       -b 'deadzone'
gh secret set RELEASE_KEY_PASSWORD    -b 'your-key-password'

# verify
gh secret list
```

## Step 4 — tag & release v1.0.0

```bash
git tag -a v1.0.0 -m "DeadZone v1.0.0 — initial release"
git push origin v1.0.0
```

GitHub Actions will:
1. Set up JDK 17 + Android SDK
2. Decode keystore from secret
3. Build signed `app-release.apk`
4. Rename → `deadzone-1.0.0.apk`
5. Create a GitHub Release with the APK attached and content from `.github/RELEASE_TEMPLATE.md`

Watch progress:
```bash
gh run watch
```

## Step 5 — set repository topics (for discoverability)

```bash
gh repo edit overbaker/deadzone --add-topic android,kotlin,touch-blocker,overlay,accessibility,xiaomi,hyperos,deadzone,foreground-service,material3
```

## Step 6 — enable GitHub Pages for the privacy policy *(optional but recommended)*

```bash
# Pages will serve PRIVACY.md at https://overbaker.github.io/deadzone/PRIVACY
gh api -X POST /repos/overbaker/deadzone/pages \
  -f source.branch=main \
  -f source.path=/
```

Or in web UI: **Settings → Pages → Source: Deploy from branch → main / root → Save**

## Step 7 — pin the repo on your profile

`gh repo edit overbaker/deadzone --visibility public --enable-issues --enable-discussions`

Then on https://github.com/overbaker → **Customize your pins** → check `deadzone`.

## Future releases

```bash
# bump versionCode/versionName in app/build.gradle.kts
git commit -am "release v1.0.1"
git tag -a v1.0.1 -m "..."
git push origin main v1.0.1
# CI auto-builds and publishes
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| CI build fails: `Could not resolve all files` | Bump cache key in `release.yml` or clear Actions cache |
| CI APK is unsigned | Verify all 4 secrets are set: `gh secret list` |
| `apksigner verify` shows different cert | Make sure you used the SAME keystore as previous releases |
| Release page shows no APK | Check `gh run view <run-id> --log` for upload step errors |
