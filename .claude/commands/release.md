Release a new version of this app. Arguments: optional version string e.g. "1.2.0". If not provided, auto-increment the patch version.

## Environment
- JAVA_HOME: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- Gradle: `./gradlew` (wrapper in project)
- Primary remote: `origin` → GitHub (releases + issues)
- Secondary remote: `github` → push only if this remote exists

---

## Steps

### 1. Read app configuration
Read `app/build.gradle.kts` and extract the current `versionCode` (integer) and `versionName` (string, e.g. `"1.0.0"`).

Find `AppConfig.kt` (search under `app/src/`) and extract:
- `APP_NAME` — used for the APK filename and release title
- `GITHUB_RELEASES_REPO_OWNER` — GitHub org/user that owns the releases repo
- `GITHUB_RELEASES_REPO_NAME` — GitHub repo name where releases are published

### 2. Determine new version
- If `$ARGUMENTS` is non-empty, use it as `newVersionName`.
- Otherwise auto-increment the **patch** segment of `versionName` (1.0.0 → 1.0.1).
- `newVersionCode` = current `versionCode` + 1.

### 3. Pre-flight checks — abort if any fail
Run these two checks **in parallel** (single Bash call with `&` and `wait`):
```bash
{ test -f keystore.properties && echo "✅ keystore" || { echo "❌ keystore.properties missing"; exit 1; }; } &
{ [ "$(git branch --show-current)" = "main" ] && echo "✅ branch=main" || { echo "❌ not on main"; exit 1; }; } &
wait
```

### 4. Commit any uncommitted changes
Check for uncommitted tracked changes (`git status --porcelain`).
If there are any modified tracked files (`M` lines), stage all and commit:
```bash
git add -u
git commit -m "chore: pre-release changes for v<newVersionName>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```
Untracked files (`??`) are ignored. If the tree is already clean, skip this step.

### 5. Bump version in build.gradle.kts
Edit `app/build.gradle.kts`:
- `versionCode = <old>` → `versionCode = <newVersionCode>`
- `versionName = "<old>"` → `versionName = "<newVersionName>"`

### 6. Commit version bump
```bash
git add app/build.gradle.kts && git commit -m "chore: release v<newVersionName>"
```

### 7. Push source + start build in parallel
Push to origin; also push to `github` remote if it exists:
```bash
git push origin main &
git remote | grep -q '^github$' && git push github main &
wait
```
If origin push fails, log the warning and continue. Then immediately start the build.

`gradle.properties` already enables daemon, parallel, configuration-cache,
build-cache, R8 full-mode, K2 incremental, KSP incremental + intermodule,
10G heap with G1GC, and `org.gradle.workers.max=11`. So the command is
short — just pin worker count to the box's actual core count and skip
tasks that don't belong in a release ship.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
CPUS=$(sysctl -n hw.logicalcpu 2>/dev/null || nproc 2>/dev/null || echo 8)
./gradlew assembleRelease \
  --max-workers="$CPUS" \
  -x lint -x lintVitalRelease -x test \
  -q
```
If the build fails, stop and show the last 30 lines. Do NOT continue.

### 8. Copy APK, create tag, push tag — all in parallel
After the build succeeds:
```bash
cp app/build/outputs/apk/release/app-release.apk <AppName>-v<newVersionName>.apk
git tag v<newVersionName>
git push origin v<newVersionName> &
git remote | grep -q '^github$' && git push github v<newVersionName> &
wait
```
Where `<AppName>` is the `APP_NAME` value from `AppConfig.kt` (spaces replaced with hyphens).

### 9. Generate structured release notes from commits
The app surfaces these notes in **Settings → What's new**, so every release
MUST ship structured notes — not the placeholder. Collect commits since
the previous tag and bucket them by type so the user can scan changes at
a glance.

```bash
PREV_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")
if [ -n "$PREV_TAG" ]; then
  RANGE="$PREV_TAG..HEAD"
else
  RANGE="HEAD"
fi
git log --format='%s' "$RANGE" > /tmp/release-commits.txt
```

Read `/tmp/release-commits.txt` and classify each subject line:

- **New features** → commits starting with `feat(...):` or `feat:` (and not
  the chore version-bump). One bullet per commit, rewritten as a
  user-facing one-liner (drop the prefix, expand abbreviations, keep it
  readable to a non-developer).
- **Bug fixes** → commits starting with `fix(...):` or `fix:`. Same
  rewriting rules. If the subject says `closes #N` or the body
  references an issue number, append `(#N)` so the user can cross-link.
- **Improvements / under-the-hood** → commits starting with `perf:`,
  `refactor:`, `chore:` (excluding the version-bump commits whose
  subjects start with `chore: release v` or `chore: pre-release`).
  Optional — include only if at least one entry is user-visible.

Sections with zero entries are omitted entirely. If literally every
commit is a version-bump (no real changes), use one bullet: "Maintenance
release; no user-visible changes."

Then publish:

```bash
gh release create v<newVersionName> \
  --repo <GITHUB_RELEASES_REPO_OWNER>/<GITHUB_RELEASES_REPO_NAME> \
  --title "<AppName> v<newVersionName>" \
  --notes "$(cat <<'EOF'
## New features
- …

## Bug fixes
- …

## Improvements
- …
EOF
)" \
  "<AppName>-v<newVersionName>.apk"
rm /tmp/release-commits.txt
rm "<AppName>-v<newVersionName>.apk"
```

Substitute the bullets in the heredoc with the categorized list you
derived above; omit any section whose bullet list would be empty.

### 10. Print summary
```
✅ Released <AppName> v<newVersionName>
   versionCode : <newVersionCode>
   APK size    : <size of app-release.apk>
   GitHub      : https://github.com/<GITHUB_RELEASES_REPO_OWNER>/<GITHUB_RELEASES_REPO_NAME>/releases/tag/v<newVersionName>
```
