# StoreVisit — Pixel-oriented Android UI integration

## Authoritative references
- `docs/design/reference/login-target.png`
- `docs/design/reference/assigned-stores-target.png`

These are the user's approved screenshots. Use them as visual *targets*, not bitmap backgrounds. Recreate everything with native Kotlin/XML Views and ViewBinding, preserving working behavior. The approved screenshots show a fully dark UI with deep navy surface, bright blue accent, white headings, subtle bordered fields and cards.

## Existing app and scope
- Package: `com.example.storevisitmanagementsystem`.
- Project: `C:\Users\zeeshan\AndroidStudioProjects\StoreVisitManagementSystem`.
- Day 15 only: login/logout; assigned-store search/list/selection.
- No Day 16 task CRUD, camera, photo capture, Room sync, or navigation to unimplemented screens.
- Read the *local* current code before edits, especially `LoginActivity`, `StoreSelectionActivity`, `StoreAdapter`, `LoginViewModel`, `StoreViewModel`, XML layouts, themes, `AndroidManifest.xml`, and `gradle/libs.versions.toml`.
- The public main branch may lag behind local uncommitted UI work. Don't revert or overwrite local fixes.

## Non-negotiable visual targets
1. Remove the default black `Store Visit Management System` ActionBar entirely by applying a compatible `NoActionBar` MaterialComponents theme in BOTH `values/themes.xml` and `values-night/themes.xml`. Ensure `AndroidManifest` references it. Never show an extra title strip.
2. Status bar, screen, and navigation area must form one coherent dark appearance. Use Android WindowInsetsCompat with explicit edge-to-edge handling; avoid drawing text under system bars or double-applying padding. Status icons light, content dark. Test Android 12 and targetSdk 37 behavior.
3. Login screenshot: simple blue storefront vector mark, `StoreVisit` wordmark + `FIELD OPS` pill, heading `Welcome back`, short subtitle, two outlined dark fields with icons and password toggle, bright-blue full-width `SIGN IN` button, lower debug-credentials panel. Layout must scroll with keyboard and large font scale. Avoid huge white margins or boxy login card.
4. Stores screenshot: dark custom StoreVisit header with logo and `LOGOUT`; greeting avatar/`Welcome, Alex Mercer` from real authenticated user; `Assigned Stores` title with actual count; rounded search field; active-store blue banner; three example cards from backend (actual list), with store code, address, category, last-visited; selected card has blue stroke + dark-blue fill + check. No artificial statistics or static hardcoded store data in UI.
5. Layouts should use resource-based dark palette: bg `#0B1019`, app bar `#101722`, surface `#19222E`, accent `#1685FF`, selected surface `#0D2344`, text `#F8FAFC`, secondary `#A8B5C8`, border `#344252`.
6. Honor Material tap target 48dp minimum; avoid truncating store names/codes; handle 360dp phone width, keyboard, long text, orientation and font scale. Log out must be accessible.
7. Debug test credentials may appear ONLY when `BuildConfig.DEBUG`. Release hides or removes this panel.
8. Maintain existing ViewBinding IDs: Login `etUsername`, `etPassword`, `tvError`, `btnLogin`, `pbLoading`; Stores `tvGreeting`, `btnLogout`, `etSearch`, `cardSelectedStore`, `tvSelectedStoreInfo`, `rvStores`, `pbLoading`, `tvEmpty`, `tvError`; Store item `cardStore`, `tvStoreName`, `tvStoreCode`, `tvStoreAddress`, `tvStoreCategory`, `tvLastVisited`.

## Proposed native Android template resources
See `proposed/app/src/main/` for sample `colors.xml`, `themes.xml`, drawables and layout XML. These are **reference implementation templates**, NOT replacements to copy blindly. Agent must inspect the latest local layouts and merge carefully to preserve bindings and existing logic. The attached reference screenshots are the primary source of visual truth; templates are secondary.

## Small Kotlin integration requirements
- On `LoginActivity` set `binding.debugCredentialsContainer.isVisible = BuildConfig.DEBUG` if that ID exists. Do not reveal test passwords in release builds.
- On store list success set `binding.tvStoreCount.text = "${list.size} stores"`; on loading/error adapt count correctly; only if the new `tvStoreCount` ID is present.
- On `StoreAdapter` use selected colors from `@color/sv_selected`, `@color/sv_accent`, `@color/sv_surface` rather than old green + white hardcodes, and toggle `tvSelectedIndicator` visibility.
- Follow actual ViewBinding API; if an XML ID changes, update Kotlin references safely.
- Use `WindowCompat.setDecorFitsSystemWindows(window,false)` plus `ViewCompat.setOnApplyWindowInsetsListener(binding.root)` to apply top/bottom system bar padding exactly once. Configure light status/nav icon appearance to false on dark backgrounds. Avoid a double status-bar inset.

## Verification gate before commit
- `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` exit code 0.
- Install and inspect both screens on OnePlus EB2101 (or connected device); do not claim hardware tests without running them.
- Confirm unwanted black ActionBar is absent, status icons readable, and screen does not overlap status/nav bars.
- Valid + invalid login; logout; session restoration; assigned store search by name/code/address; selected card switching; empty search state; two accounts.
- Capture final *real app screenshots* and compare with `docs/design/reference/*.png` side-by-side; report any deviations.
- No changes to API contract, repositories, tokens, mock DB schema, Room, WorkManager, Gradle versions or business logic unless absolutely necessary for compilation; document exceptions.
- No automatic commit/push. Report changed files and exact commands/tests run.
