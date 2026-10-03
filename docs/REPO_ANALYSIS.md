# Repository Analysis and Refactor Blueprint

## 1. Current Folder and Module Structure

The project is structured as a single Gradle module (`app`) written in Kotlin. The current source files are organized as follows:

```
app/src/
└── main/
    ├── AndroidManifest.xml
    ├── res/
    │   ├── drawable/
    │   │   ├── bg_bottom_sheet.xml
    │   │   ├── bg_dialog.xml
    │   │   ├── bg_sheet_handle.xml
    │   │   ├── ic_copy_m3.xml
    │   │   ├── ic_download_m3.xml
    │   │   ├── ic_launcher_foreground.xml
    │   │   ├── ic_open_m3.xml
    │   │   ├── ic_photo_m3.xml
    │   │   ├── ic_share_m3.xml
    │   │   ├── logo.png
    │   │   └── progress_gradient.xml
    │   ├── layout/
    │   │   ├── activity_komik.xml
    │   │   ├── fragment_context_menu.xml
    │   │   ├── view_dialog_message.xml
    │   │   ├── view_dialog_prompt.xml
    │   │   └── view_dialog_title.xml
    │   ├── mipmap-anydpi-v26/
    │   │   └── ic_launcher_adf.xml
    │   ├── values/
    │   │   ├── colors.xml
    │   │   ├── strings.xml
    │   │   └── themes.xml
    │   └── xml/
    │       └── file_paths.xml
    └── java/
        └── com/shinigami/client/
            ├── aeldyStudio.kt
            ├── extension/
            │   └── WebExtension.kt
            ├── manager/
            │   ├── ConfigManager.kt
            │   ├── DialogManager.kt
            │   └── NetworkManager.kt
            ├── ui/
            │   ├── ContextMenuSheet.kt
            │   ├── DebugActivity.kt
            │   ├── KomikActivity.kt
            │   ├── KomikVM.kt
            │   └── PopupHost.kt
            └── util/
                ├── AppConfig.kt
                └── Logger.kt
```

---

## 2. Class Responsibilities

Below is an exhaustive breakdown of every Kotlin file and class in the codebase:

- **`aeldyStudio` (`aeldyStudio.kt`)**: Custom `Application` class declared in `AndroidManifest.xml`. Initializes the `Logger` utility during app startup (`onCreate()`).
- **`WebExtension` (`WebExtension.kt`)**: Singleton manager handling OkHttp request interception, HTML content response patching (`is_premium:false` -> `is_premium:true`), and in-memory `LruCache` storage for patched WebResourceResponse objects. Also manages a shared `OkHttpClient` instance.
- **`ConfigManager` (`ConfigManager.kt`)**: Handles fetching the remote target URL from GitHub (`AppConfig.CONFIG_URL`) via OkHttp with fallback to local `SharedPreferences` caching.
- **`DialogManager` (`DialogManager.kt`)**: Factory helper object for inflating custom XML dialog views and building `MaterialAlertDialogBuilder` instances (info, error, confirm, prompt dialogs).
- **`NetworkManager` (`NetworkManager.kt`)**: Utility class monitoring network connectivity via Android `ConnectivityManager.NetworkCallback`, exposing network availability state as a Kotlin `Flow<Boolean>`.
- **`ContextMenuSheet` (`ContextMenuSheet.kt`)**: `BottomSheetDialogFragment` presented when an image in the WebView is long-pressed or tapped. Provides options to preview the image bitmap via OkHttp, open in browser, copy link, download image via `DownloadManager`, share link, or open full image in popup WebView.
- **`DebugActivity` (`DebugActivity.kt`)**: Fallback Activity declared in `AndroidManifest.xml` that formats stack traces and presents crash details in a scrollable `TextView`.
- **`KomikActivity` (`KomikActivity.kt`)**: The main launcher Activity. Manages WebView setup, swipe-to-refresh, splash screen visibility, touch/gesture detection for image links via JavaScript evaluation (`JAVASCRIPT_IMAGE_DETECTOR`), request interception for ad blocking & announcement mocking, file chooser uploads (`onShowFileChooser`), storage permission requests, and popup window creation (`onCreateWindow`).
- **`KomikViewModel` / `KomikUiState` (`KomikVM.kt`)**: `AndroidViewModel` maintaining UI state (`url`, `isLoading`, `loadingProgress`, `isSplashVisible`) and coordinating network connectivity monitoring with remote URL retrieval.
- **`PopupHost` (`PopupHost.kt`)**: Interface contract defining `openPopupWebView(url: String)` implemented by `KomikActivity` and invoked by `ContextMenuSheet`.
- **`AppConfig` (`AppConfig.kt`)**: Singleton configuration object holding constant parameters (base URLs, config endpoint URL, logging settings, version strings).
- **`Logger` (`Logger.kt`)**: Logging framework writing logs to Android logcat as well as file-backed storage in a background coroutine channel with automatic rotation and crash logging.

---

## 3. WebView Response Modification

Response modification and request interception occur across two main files:

### A. `KomikActivity.kt`
- **Functions**: `DefaultWebViewClient.shouldInterceptRequest()`, `DefaultWebChromeClient.onCreateWindow()` (child `WebViewClient.shouldInterceptRequest()`), and helper `KomikActivity.interceptBlockedRequest()`.
- **Logic & Rules**:
  1. **Ad Blocking**: Intercepts URLs matching `ads.shinigami`. Returns a mocked JSON response payload (`{"message":"success", "data": [...]}`) with empty ad lists.
  2. **Announcement Mocking**: Intercepts requests to `api.shngm.io/v1/announcement`. Returns hardcoded mock JSON arrays/objects (`ANNOUNCEMENT_LIST_JSON` or `ANNOUNCEMENT_DETAIL_JSON`) injected with `Ads blocking by aeeL`.
  3. **Google Tag Manager Blocking**: Intercepts domains containing `googletagmanager`. Returns an empty `WebResourceResponse` (mime-type `application/javascript` for `.js` files, or `text/plain`).
  4. **Novu Notifications Blocking**: Intercepts domains ending with `novu.my`. Returns an empty `WebResourceResponse` (`text/plain`).

### B. `WebExtension.kt`
- **Function**: `WebExtension.interceptRequest(request: WebResourceRequest)`.
- **Logic & Rules**:
  1. Checks if the URL host is contained in `blockedHosts` or if `request.isForMainFrame` is true. If so, skips interception (returns `null`).
  2. Executes an OkHttp request using `sharedHttpClient` with synced web cookies (`CookieManager`).
  3. Checks response `Content-Type`. If it contains `html`, reads body string and replaces `"is_premium:false"` with `"is_premium:true"`.
  4. Wraps the patched byte array into a `CachedResource` and stores it in an in-memory `LruCache` (max 16 MB).
  5. Returns a `WebResourceResponse` constructed from `CachedResource.toWebResourceResponse()`.

---

## 4. XML Layouts and Activity/Fragment Usage

| Layout File | Used By | Description |
| :--- | :--- | :--- |
| `activity_komik.xml` | `KomikActivity` | Main activity layout containing `SwipeRefreshLayout`, `WebView` (`web_komik`), progress bar, and splash overlay container. |
| `fragment_context_menu.xml` | `ContextMenuSheet` | Bottom sheet layout displaying image preview `MaterialCardView`, URL text, and menu item buttons. |
| `view_dialog_title.xml` | `DialogManager` | Title view with bold `TextView` (`txt_title`) for custom Material dialog headers. |
| `view_dialog_message.xml` | `DialogManager` | Message body layout with `TextView` (`txt_message`) for info, error, and confirm dialogs. |
| `view_dialog_prompt.xml` | `DialogManager` | Prompt dialog body with message `TextView` (`txt_message`) and text `EditText` (`input`). |

*Note: `DebugActivity` builds its layout programmatically using `ScrollView` and `HorizontalScrollView`, so it does not inflate an XML layout.*

---

## 5. Dead Code

1. **`DebugActivity` (`app/src/main/java/com/shinigami/client/ui/DebugActivity.kt`)**:
   - Declared in `AndroidManifest.xml` (`.ui.DebugActivity`), but never instantiated or launched anywhere in the codebase.
2. **Unused Constants in `KomikActivity.kt`**:
   - `PREF_WELCOME_SHOWN = "welcome_dialog_displayed"` defined in `KomikActivity.companion object`, but never read or written in code.
3. **`AppConfig.kt` Version Field Mismatch**:
   - `AppConfig.VERSION_NAME = "1.7.0"` and `AppConfig.VERSION_CODE = 170` are never referenced by build scripts or UI (the app version is set in `app/build.gradle.kts` as `1.0-github` / `1`).
4. **`Logger.kt` Uncalled Logging Methods**:
   - `Logger.v()` is defined but never called anywhere in the codebase.

---

## 6. Unused Dependencies and Resources

### Unused Dependencies (`app/build.gradle.kts` & `gradle/libs.versions.toml`)
- **`androidx.appcompat:appcompat` (`libs.androidx.appcompat`)**: The app uses `ComponentActivity`/`FragmentActivity` and Material 3 styles (`Theme.Material3`). `AppCompatActivity` and appcompat widgets are unused.
- **`androidx.constraintlayout:constraintlayout` (`libs.androidx.constraintlayout`)**: Used in `activity_komik.xml` and `view_dialog_prompt.xml`. Once migrated to Compose, this dependency becomes unused.
- **`androidx.swiperefreshlayout:swiperefreshlayout` (`libs.androidx.swiperefreshlayout`)**: Used in `activity_komik.xml`, will be replaced by Compose `PullToRefreshBox`.

### Unused & Redundant Resources
- **`app/src/main/res/xml/file_paths.xml`**: Declared for `FileProvider` in `AndroidManifest.xml`, but `FileProvider` is never referenced in Kotlin code (downloads use system `DownloadManager`).
- **Drawable Artifacts**: `bg_bottom_sheet.xml`, `bg_dialog.xml`, `bg_sheet_handle.xml`, `progress_gradient.xml`, and M3 vector drawables (`ic_copy_m3.xml`, `ic_download_m3.xml`, `ic_open_m3.xml`, `ic_photo_m3.xml`, `ic_share_m3.xml`) will become obsolete once UI is fully migrated to Jetpack Compose + Material 3 Icons.

---

## 7. Duplicated Code

1. **Eruda Console JS Injection**:
   - Identical injection calls (`activity.injectErudaConsole(view)`) are made in two locations: `DefaultWebViewClient.onPageFinished()` and inside `DefaultWebChromeClient.onCreateWindow()` child `WebViewClient.onPageFinished()`.
2. **Blocked Request Interception Dispatch**:
   - `DefaultWebViewClient.shouldInterceptRequest()` and child `WebViewClient.shouldInterceptRequest()` inside `onCreateWindow()` duplicate identical calls to `interceptBlockedRequest(request)`.
3. **Dialog View Inflation**:
   - `DialogManager.info()`, `DialogManager.error()`, and `DialogManager.confirm()` duplicate `ViewDialogMessageBinding.inflate(...)` and text assignment boilerplate.
4. **CORS Header Construction**:
   - `KomikActivity.jsonResponse()` manually constructs `Access-Control-Allow-*` header maps repeatedly for mock WebResourceResponse objects.

---

## 8. Review of Workflow Files (`.github/workflows/`)

### File: `.github/workflows/android_release.yml`
- **What It Does**:
  - Triggers on `push` to `main` branch or manual `workflow_dispatch`.
  - **Job 1 (`build`)**: Checks out code (`actions/checkout@v6`), sets up JDK 21 (`actions/setup-java@v5`), grants execute permission to `./gradlew`, runs `./gradlew assembleRelease`, renames output APK to `app-release.apk`, and uploads it as an artifact (`actions/upload-artifact@v7`).
  - **Job 2 (`release`)**: Downloads `app-release.apk` (`actions/download-artifact@v8`), generates timestamp tag `release-YYYYMMDDHHMMSS`, and creates a GitHub Release via `softprops/action-gh-release@v3`.
- **Problems & Issues**:
  1. **Debug Signing in Release Build**: `app/build.gradle.kts` assigns `signingConfig = signingConfigs.getByName("debug")` for the `release` build type. While functional for GitHub Releases without a keystore, it should be configurable via GitHub Secrets.
  2. **No Pre-Release Verification**: Workflow directly executes `assembleRelease` without running `./gradlew lint` or `./gradlew test` first to catch failures.
  3. **Unpinned / Non-Standard Action Versions**: Actions use unpinned/future major versions (`actions/checkout@v6`, `actions/upload-artifact@v7`, `actions/download-artifact@v8`). Standard GitHub Actions versions are `@v4`.
  4. **Redundant Upload/Download Overhead**: Passing APK artifacts between two jobs in a single sequential workflow adds unnecessary runner execution overhead.

### File: `.github/dependabot.yml`
- **What It Does**: Configures daily automated dependency check updates for `gradle` and `github-actions`. Functioning properly.

---

## 9. Proposed Target Structure

Adhering strictly to constraints (Solo developer, single Gradle module `:app`, package-by-feature top-level, small shared core, Jetpack Compose + Material 3, ViewModel + StateFlow):

```
app/src/main/java/com/shinigami/client/
├── ShinigamiApp.kt                         (renamed from aeldyStudio.kt)
├── core/
│   ├── webview/
│   │   ├── WebExtension.kt                 (OkHttp interception & response caching)
│   │   ├── RequestInterceptor.kt           (Ad blocking & announcement mocks)
│   │   └── ErudaConsole.kt                (Eruda dev tools JS injection)
│   ├── network/
│   │   └── NetworkMonitor.kt               (renamed/refactored from NetworkManager.kt)
│   ├── util/
│   │   ├── AppConfig.kt                    (Config constants)
│   │   └── Logger.kt                       (Logging utility)
│   └── ui/
│       ├── theme/
│       │   ├── Theme.kt                    (Compose Material3 Theme)
│       │   └── Color.kt                    (Compose Color definitions)
│       └── components/
│           ├── AppDialog.kt                (Compose AlertDialog replacements)
│           └── ContextMenuBottomSheet.kt     (Compose ModalBottomSheet replacement)
└── feature/
    └── komik/
        ├── data/
        │   └── ConfigRepository.kt        (extracted from ConfigManager.kt)
        └── ui/
            ├── KomikActivity.kt            (ComponentActivity initializing Compose)
            ├── KomikScreen.kt              (Compose UI wrapping WebView)
            ├── KomikViewModel.kt           (ViewModel with StateFlow)
            └── KomikUiState.kt             (UiState data class)
```

### File Location Mapping

| Existing Location / File | Proposed New Location / File |
| :--- | :--- |
| `com.shinigami.client.aeldyStudio` | `com.shinigami.client.ShinigamiApp` |
| `com.shinigami.client.extension.WebExtension` | `com.shinigami.client.core.webview.WebExtension` |
| `com.shinigami.client.manager.ConfigManager` | `com.shinigami.client.feature.komik.data.ConfigRepository` |
| `com.shinigami.client.manager.NetworkManager` | `com.shinigami.client.core.network.NetworkMonitor` |
| `com.shinigami.client.manager.DialogManager` | `com.shinigami.client.core.ui.components.AppDialog` |
| `com.shinigami.client.ui.ContextMenuSheet` | `com.shinigami.client.core.ui.components.ContextMenuBottomSheet` |
| `com.shinigami.client.ui.DebugActivity` | Removed (Dead code) |
| `com.shinigami.client.ui.KomikActivity` | `com.shinigami.client.feature.komik.ui.KomikActivity` & `KomikScreen.kt` |
| `com.shinigami.client.ui.KomikVM.kt` | `com.shinigami.client.feature.komik.ui.KomikViewModel.kt` & `KomikUiState.kt` |
| `com.shinigami.client.ui.PopupHost` | Removed (replaced by Compose callbacks) |
| `com.shinigami.client.util.AppConfig` | `com.shinigami.client.core.util.AppConfig` |
| `com.shinigami.client.util.Logger` | `com.shinigami.client.core.util.Logger` |
| Interception logic in `KomikActivity.companion object` | `com.shinigami.client.core.webview.RequestInterceptor` |

---

## 10. Step-by-Step Refactor Plan (Safest to Riskiest)

Each step is designed to be completed in a single PR with complete verification instructions.

### Step 1: Code Cleanup & Dead Code Removal (Safest)
- **Scope**:
  - Delete unused `DebugActivity.kt` and remove `<activity android:name=".ui.DebugActivity">` from `AndroidManifest.xml`.
  - Remove unused constants (`PREF_WELCOME_SHOWN`).
  - Delete `app/src/main/res/xml/file_paths.xml` and remove `FileProvider` declaration from `AndroidManifest.xml`.
- **Verification**:
  - Build & Lint: `./gradlew assembleDebug lint`
  - Manual Test: Confirm app launches cleanly without manifest issues.

### Step 2: Add Jetpack Compose Dependencies & Config Setup
- **Scope**:
  - Add Compose Compiler and Material 3 dependencies in `gradle/libs.versions.toml` and `app/build.gradle.kts`.
  - Enable `buildFeatures { compose = true }` in `app/build.gradle.kts`.
  - Create `core/ui/theme/Theme.kt` and `Color.kt`.
- **Verification**:
  - Build & Lint: `./gradlew assembleDebug lint`
  - Manual Test: Verify app compiles cleanly with Compose enabled.

### Step 3: Refactor Core Utilities & Network Monitoring
- **Scope**:
  - Move `AppConfig` and `Logger` to `core/util/`.
  - Refactor `NetworkManager` to `core/network/NetworkMonitor.kt`.
  - Extract `ConfigManager` to `feature/komik/data/ConfigRepository.kt`.
  - Rename `aeldyStudio` to `ShinigamiApp`.
  - Update ProGuard rules if package paths changed.
- **Verification**:
  - Build & Lint: `./gradlew assembleDebug lint test`
  - Manual Test: Launch app and confirm remote URL config is fetched and network connection status is observed.

### Step 4: Extract WebView Response Interception Core (`core/webview`)
- **Scope**:
  - Move `WebExtension.kt` to `core/webview/WebExtension.kt`.
  - Extract ad blocking, announcement mocking, and CORS header generation from `KomikActivity` into `core/webview/RequestInterceptor.kt`.
  - Extract Eruda console JS script and injection logic into `core/webview/ErudaConsole.kt`.
- **CRITICAL RISK NOTICE**:
  - Must preserve exact regexes, domain checks (`ads.shinigami`, `api.shngm.io/v1/announcement`, `googletagmanager`, `novu.my`), exact JSON payloads (`ANNOUNCEMENT_LIST_JSON`, `ANNOUNCEMENT_DETAIL_JSON`), and string replacement (`is_premium:false` -> `is_premium:true`).
- **Verification**:
  - Build & Lint: `./gradlew assembleDebug lint`
  - Manual Test: Navigate website in app, verify premium features unlock, verify ads are blocked, and verify announcements load correctly.

### Step 5: Migrate Dialogs and Context Menu to Compose Components
- **Scope**:
  - Replace `DialogManager.kt` with Compose `AlertDialog` components in `core/ui/components/AppDialog.kt`.
  - Replace `ContextMenuSheet.kt` (`BottomSheetDialogFragment`) with Compose `ModalBottomSheet` in `core/ui/components/ContextMenuBottomSheet.kt`.
- **Verification**:
  - Build & Lint: `./gradlew assembleDebug lint`
  - Manual Test: Long-press image in WebView, test context menu options (open, copy, download, preview), and test JS alert/confirm/prompt dialogs.

### Step 6: Migrate Main Activity UI (`activity_komik.xml`) to Compose (`KomikScreen`) (Riskiest)
- **Scope**:
  - Replace XML layout `activity_komik.xml` and ViewBinding in `KomikActivity` with `setContent { ShinigamiTheme { KomikScreen() } }`.
  - Implement `KomikScreen.kt` using Compose `AndroidView` to wrap `WebView` and Compose `PullToRefreshBox` for swipe-to-refresh.
  - Move `KomikActivity` and ViewModel to `feature/komik/ui/`.
  - Delete legacy XML layout files (`activity_komik.xml`, `fragment_context_menu.xml`, `view_dialog_*.xml`).
  - Remove unused dependencies (`constraintlayout`, `swiperefreshlayout`, `appcompat`).
- **Verification**:
  - Build & Lint: `./gradlew assembleDebug assembleRelease lint`
  - Manual Test: Verify full app lifecycle: splash screen, web page loading, swipe to refresh, image context menu, file uploads, and popup window opening.

### Step 7: Clean Up GitHub Release Workflow (`.github/workflows/android_release.yml`)
- **Scope**:
  - Update action versions to standard releases (`actions/checkout@v4`, `actions/upload-artifact@v4`, `actions/download-artifact@v4`).
  - Add `./gradlew lint` step prior to `assembleRelease`.
  - Integrate `gradle/actions/setup-gradle@v3` for build caching.
  - Document manual workflow verification instructions (`workflow_dispatch`).
- **Verification**:
  - Manual Test: Review workflow YAML syntax and verify outputs match expectations.
