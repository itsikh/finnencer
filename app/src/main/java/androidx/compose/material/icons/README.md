# Vendored Material icons

These files are verbatim, Apache-2.0-licensed copies of the icon sources
from `androidx.compose.material:material-icons-extended:1.7.6`
(sources jar on Google Maven), keeping their original packages.

Why: depending on `material-icons-extended` put ~11k generated icon
classes on the release classpath, and R8 full-mode spent minutes
tree-shaking them on every release build. Vendoring only the icons we
actually use (everything not already bundled in `material-icons-core`,
which stays a dependency) removes that cost while keeping call sites
untouched — the extension properties resolve exactly as before.

Adding a new icon: grab its file from the extended sources jar
(`material-icons-extended-android-<ver>-sources.jar` →
`commonMain/androidx/compose/material/icons/...`) and drop it in the
matching folder here. Do not add the extended dependency back.
