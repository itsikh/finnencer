# New Android App from Template

You are setting up a brand-new Android app from this template project.
Work through the steps below in order. Do not skip or assume any value.

---

## Step 1 — Collect all required information

Ask the user for everything in a **single message** before touching any files.
Present it as a numbered list so they can answer each item clearly.

### Mandatory questions

1. **App name** — human-readable display name shown on the launcher (e.g. `My Cool App`)
2. **App ID** — Android package name, reverse-DNS style (e.g. `com.mycompany.myapp`)
3. **Initial version** — semantic version string (default: `0.0.1`)
4. **GitHub issues repo** — `owner/repo` where bug reports will be filed as issues
5. **GitHub releases repo** — `owner/repo` where release APKs are published
   _(can be the same as the issues repo — just confirm)_
6. **Git remote URL** — the new app's git remote URL so `origin` no longer points to the template
   _(e.g. `git@github.com:myorg/myapp.git` or `https://github.com/myorg/myapp.git`)_
7. **Keystore file path** — relative to project root (default: `keystore.jks`)
8. **Key alias** — (default: `key0`)
9. **Keystore password** — for the `.jks` file
10. **Key password** — for the signing key (can be the same as keystore password)

### Optional questions

11. **Install autofix cron?** — Set up the autonomous bug-fixing agent (runs every 30 min)?
    Requires the GitHub issues repo to exist and a PAT to be added later in-app.
    _(yes / no, default: no)_

---

## Step 2 — Run setup_new_app.sh non-interactively

Once you have all answers, pipe them into the setup script in **exactly this order**
(one answer per line, matching the script's `read` prompts):

```
APP_NAME
APP_ID
VERSION
ISSUES_OWNER
ISSUES_REPO
RELEASES_OWNER   ← or blank to default to ISSUES_OWNER
RELEASES_REPO    ← or blank to default to ISSUES_REPO
GIT_REMOTE_URL   ← or blank to keep current
KEYSTORE_PATH    ← or blank for default keystore.jks
KEY_ALIAS        ← or blank for default key0
KEYSTORE_PASS
KEY_PASS         ← or blank to reuse KEYSTORE_PASS
AUTOFIX_CRON     ← y or n
y                ← final "Looks good? Apply changes?" confirmation
```

Run it like this (replace each `<value>` with the actual answer):
```bash
printf '%s\n' \
  "<APP_NAME>" \
  "<APP_ID>" \
  "<VERSION>" \
  "<ISSUES_OWNER>" \
  "<ISSUES_REPO>" \
  "<RELEASES_OWNER_OR_BLANK>" \
  "<RELEASES_REPO_OR_BLANK>" \
  "<GIT_REMOTE_URL_OR_BLANK>" \
  "<KEYSTORE_PATH_OR_BLANK>" \
  "<KEY_ALIAS_OR_BLANK>" \
  "<KEYSTORE_PASS>" \
  "<KEY_PASS_OR_BLANK>" \
  "<y_OR_n_FOR_CRON>" \
  "y" \
| bash setup_new_app.sh
```

If the script exits non-zero, read its error output and fix the underlying issue
(missing directory, keytool not found, etc.) before retrying.

---

## Step 3 — Verify the build compiles

Run a debug build to confirm the renamed package and patched files compile cleanly:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug --build-cache
```

If it fails, read the error, fix the root cause (usually a missed package reference),
and re-run. Do NOT continue to step 4 until the build is green.

---

## Step 4 — Initial git commit and push

```bash
git add -A
git commit -m "chore: initialise $(grep 'APP_NAME' app/src/main/java/**/AppConfig.kt | grep -oP '"[^"]+"' | head -1 | tr -d '"') from Android template

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
git push origin main
```

---

## Step 5 — Tell the user what's left to do manually

Print this checklist:

```
✅ Setup complete. Three things left to do manually:

1. App icon
   Replace the placeholder icon with your own:
   - app/src/main/res/mipmap-mdpi/ic_launcher.png       (48×48)
   - app/src/main/res/mipmap-hdpi/ic_launcher.png        (72×72)
   - app/src/main/res/mipmap-xhdpi/ic_launcher.png       (96×96)
   - app/src/main/res/mipmap-xxhdpi/ic_launcher.png      (144×144)
   - app/src/main/res/mipmap-xxxhdpi/ic_launcher.png     (192×192)
   Or use Android Studio → Image Asset to regenerate all sizes at once.

2. GitHub PAT (Personal Access Token)
   The token must have write access to both the issues and releases repos.
   Add it inside the running app:
     Settings → (7-tap the version number for admin mode) → GitHub Token

3. Sync Gradle in Android Studio
   File → Sync Project with Gradle Files
   (or just open the project — it will prompt you automatically)
```

---

## Reference — what each answer configures

| Question | Where it lands |
|----------|---------------|
| App name | `AppConfig.APP_NAME`, `strings.xml` app_name, `settings.gradle.kts` root name, themes |
| App ID | `build.gradle.kts` namespace + applicationId, package folder path, all `package`/`import` in .kt files |
| Version | `build.gradle.kts` versionName |
| Issues repo | `AppConfig.GITHUB_ISSUES_REPO_OWNER/NAME` → used by `GitHubIssuesClient` |
| Releases repo | `AppConfig.GITHUB_RELEASES_REPO_OWNER/NAME` → used by `AppUpdateManager` and `/release` |
| Git remote | `git remote set-url origin` |
| Keystore | `keystore.properties` (gitignored) + `app/build.gradle.kts` signingConfig |
| Autofix cron | `crontab` entry calling `.claude/skills/autofix/autofix-wrapper.sh` every 30 min |
