# 屏幕区域触控屏蔽 APK 实施计划

> 综合 Codex 后端架构（SESSION 019dd1b8）+ Gemini 前端架构（SESSION efe23192）+ 用户决策的最终落地版本

## 0. 元信息

| 项 | 值 |
|---|---|
| 应用名 | Touch Blocker |
| 包名 | `com.ccg.screenblocker` |
| 目标设备 | 小米 13 Ultra（HyperOS 2.x，Android 14+，1440×3200 portrait） |
| minSdk / targetSdk | 26 / 34 |
| 语言 | Kotlin（无 Compose） |
| UI | Android Views + ViewBinding + Material 3 |
| 持久化 | SharedPreferences |
| 服务 | Started + Bound Service，启用时进入 FGS（`specialUse`） |
| 屏幕方向 | 锁定竖屏（`screenOrientation="portrait"` + `configChanges`） |
| APK 体积目标 | ≤ 5MB |

---

## 1. 项目目录结构

```
/home/ubuntu/Project/CCG/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/gradle-wrapper.properties     ← gradle wrapper 命令生成
├── gradlew, gradlew.bat                         ← gradle wrapper 命令生成
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/ccg/screenblocker/
        │   ├── MainActivity.kt
        │   ├── service/OverlayService.kt
        │   ├── ui/OverlayView.kt
        │   ├── model/BlockArea.kt
        │   ├── data/BlockAreaRepository.kt
        │   ├── data/SharedPrefsBlockAreaRepository.kt
        │   ├── util/PermissionHelper.kt
        │   ├── util/NotificationHelper.kt
        │   └── util/DisplayHelper.kt
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   └── view_permission_guide.xml
            ├── values/
            │   ├── colors.xml
            │   ├── strings.xml
            │   ├── themes.xml
            │   └── styles.xml
            ├── values-night/
            │   ├── colors.xml
            │   └── themes.xml
            ├── drawable/
            │   ├── ic_play.xml
            │   ├── ic_stop.xml
            │   ├── ic_notification.xml
            │   ├── ic_block_overlay.xml
            │   ├── ic_warning.xml
            │   └── bg_rect_editable.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml
            ├── mipmap-anydpi-v26/ic_launcher_round.xml
            ├── values/ic_launcher_background.xml
            ├── drawable/ic_launcher_foreground.xml
            ├── menu/main_menu.xml
            └── xml/locales_config.xml
```

---

## 2. Gradle 配置

### 2.1 `settings.gradle.kts`
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "CCG"
include(":app")
```

### 2.2 `build.gradle.kts`（root）
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
```

### 2.3 `gradle.properties`
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

### 2.4 `gradle/libs.versions.toml`
```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.21"
coreKtx = "1.13.1"
appcompat = "1.7.0"
activityKtx = "1.9.3"
constraintlayout = "2.1.4"
material = "1.12.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
androidx-activity-ktx = { module = "androidx.activity:activity-ktx", version.ref = "activityKtx" }
androidx-constraintlayout = { module = "androidx.constraintlayout:constraintlayout", version.ref = "constraintlayout" }
google-material = { module = "com.google.android.material:material", version.ref = "material" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

### 2.5 `app/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ccg.screenblocker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ccg.screenblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh-rCN", "en")
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // 本地分发先用 debug 签名
        }
    }
    buildFeatures { viewBinding = true; buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    ndk { abiFilters += listOf("arm64-v8a") }    // 小米13U仅需 arm64
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)
}
```

---

## 3. AndroidManifest.xml 完整内容

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.TouchBlocker">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:screenOrientation="portrait"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.OverlayService"
            android:description="@string/service_description"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="touch_block_overlay_runtime" />
        </service>

    </application>
</manifest>
```

---

## 4. 类设计与职责

### 4.1 `MainActivity`
- 唯一 Activity，继承 `AppCompatActivity`
- 加载 `activity_main.xml`，含 `OverlayView(mode=EDIT)`
- 初次启动检查 `SYSTEM_ALERT_WINDOW`，无则展示 `view_permission_guide.xml`
- onStart bind Service 同步状态；onStop unbind
- 启用屏蔽：保存矩形 → 启动 FGS（intent action `ACTION_ENABLE`）→ 切到 ENABLED 视图
- 暂停屏蔽：发送 intent action `ACTION_STOP` 给 Service

### 4.2 `OverlayService : Service()`
内部 `LocalBinder`。常量：
```kotlin
companion object {
    const val ACTION_ENABLE = "com.ccg.screenblocker.action.ENABLE"
    const val ACTION_STOP = "com.ccg.screenblocker.action.STOP"
    const val EXTRA_AREA = "extra_area"
    const val ENABLE_DELAY_MS = 500L
}
```

职责：
1. `onStartCommand` 处理 `ACTION_ENABLE` / `ACTION_STOP`
2. 启用流程：`startForeground()` → `Handler.postDelayed(addOverlay, 500)`
3. 停止流程：移除 overlay → `stopForeground(STOP_FOREGROUND_REMOVE)` → `stopSelf()`
4. `onConfigurationChanged` 重新应用 `BlockArea.rescaleIfNeeded()`
5. `onDestroy` 必须移除 overlay（防残留）

### 4.3 `OverlayView : View`
单一类，构造时传入 `Mode.EDIT` 或 `Mode.RUNTIME`。

**EDIT 模式**：
- `match_parent`，参与 Activity 布局
- 绘制：
  - 半透明蓝色填充（30% alpha primary color）
  - 虚线边框（dashWidth=8dp, dashGap=4dp, stroke=2dp）
  - 四角小三角指示器（实心）
- 触控：
  - DOWN：判定 `gestureMode`（角点 24dp 感应区 → RESIZE_xx；矩形内部 → MOVE；否则 NONE）
  - MOVE：根据 mode 更新矩形并 invalidate
  - UP/CANCEL：触发 `onAreaChanged(area, isFinal=true)` 回调

**RUNTIME 模式**：
- View 本体不绘制（背景透明）
- `onTouchEvent` 一律返回 `true`，吞掉所有事件
- WindowManager.LayoutParams 大小 = 矩形大小

**核心算法**（4 角缩放感应区）：
```kotlin
private val handleSizePx = dp(24f)
private fun hitTest(x: Float, y: Float): GestureMode {
    val r = currentRect
    val tl = abs(x - r.left) <= handleSizePx && abs(y - r.top) <= handleSizePx
    val tr = abs(x - r.right) <= handleSizePx && abs(y - r.top) <= handleSizePx
    val bl = abs(x - r.left) <= handleSizePx && abs(y - r.bottom) <= handleSizePx
    val br = abs(x - r.right) <= handleSizePx && abs(y - r.bottom) <= handleSizePx
    return when {
        tl -> GestureMode.RESIZE_TL
        tr -> GestureMode.RESIZE_TR
        bl -> GestureMode.RESIZE_BL
        br -> GestureMode.RESIZE_BR
        r.contains(x, y) -> GestureMode.MOVE
        else -> GestureMode.NONE
    }
}
```

**边界 clamp**：
```kotlin
val minSize = dp(72)
val maxW = parentWidth - dp(50)
val maxH = parentHeight - dp(50)
```

### 4.4 `BlockArea` 数据类
```kotlin
data class BlockArea(
    val leftPx: Int, val topPx: Int,
    val widthPx: Int, val heightPx: Int,
    val savedDisplayWidthPx: Int, val savedDisplayHeightPx: Int
) {
    fun isValid(minSize: Int) = widthPx >= minSize && heightPx >= minSize
    fun clamp(displayW: Int, displayH: Int, marginPx: Int): BlockArea { ... }
    fun rescaleIfNeeded(newW: Int, newH: Int): BlockArea { ... }
}
```

### 4.5 `BlockAreaRepository` 接口 + `SharedPrefsBlockAreaRepository` 实现
- 文件名：`block_area_prefs`
- key：`block_area_left_px`、`block_area_top_px`、`block_area_width_px`、`block_area_height_px`、`block_area_saved_display_width_px`、`block_area_saved_display_height_px`、`block_area_schema_version`

### 4.6 `PermissionHelper`
```kotlin
object PermissionHelper {
    fun canDrawOverlays(ctx: Context): Boolean
    fun createOverlayPermissionIntent(pkg: String): Intent
    fun shouldRequestPostNotifications(): Boolean   // sdk >= 33
    fun canPostNotifications(ctx: Context): Boolean
    fun isXiaomiFamilyDevice(): Boolean
    fun getXiaomiManualChecklist(): List<String>    // 自启动/后台弹出/电池无限制
}
```

### 4.7 `NotificationHelper`
```kotlin
object NotificationHelper {
    const val CHANNEL_ID = "overlay_block_channel"
    const val NOTIFICATION_ID = 1001
    fun ensureChannel(ctx: Context)
    fun buildRunning(ctx: Context, area: BlockArea): Notification
    fun stopActionPendingIntent(ctx: Context): PendingIntent
    fun openAppPendingIntent(ctx: Context): PendingIntent
}
```

### 4.8 `DisplayHelper`
```kotlin
object DisplayHelper {
    fun getDisplaySizePx(ctx: Context): Pair<Int, Int>      // 优先 WindowMetrics（API 30+），fallback DisplayMetrics
    fun dp(ctx: Context, value: Float): Int
}
```

---

## 5. 关键代码骨架

### 5.1 运行态 LayoutParams（OverlayService）
```kotlin
private fun buildOverlayParams(area: BlockArea): WindowManager.LayoutParams {
    return WindowManager.LayoutParams(
        area.widthPx, area.heightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = area.leftPx
        y = area.topPx
        title = "TouchBlockOverlay"
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
```
**注意：禁止添加 `FLAG_NOT_TOUCHABLE`，否则触控会穿透**。

### 5.2 启用延迟（OverlayService）
```kotlin
private val handler = Handler(Looper.getMainLooper())
private var pending: Runnable? = null

private fun armOverlay(area: BlockArea) {
    startForegroundNow(area)
    pending?.let(handler::removeCallbacks)
    pending = Runnable { addOrUpdateOverlay(area) }
    handler.postDelayed(pending!!, 500L)
}
```

### 5.3 OnTouchEvent（运行态）
```kotlin
override fun onTouchEvent(event: MotionEvent): Boolean = true
```

---

## 6. 状态机

**APP 状态**：`UNAUTHORIZED` → `EDITING` ⇄ `ENABLED`

| 触发 | From → To |
|---|---|
| 启动且无 overlay 权限 | * → UNAUTHORIZED |
| 用户授予权限 | UNAUTHORIZED → EDITING |
| 用户点"启用屏蔽" | EDITING → ENABLED |
| 用户点"暂停"/通知"快捷停止" | ENABLED → EDITING |
| 用户在系统中撤销权限 | * → UNAUTHORIZED |

**Service 状态**：`STOPPED` ⇄ `RUNNING`（含子阶段 arming(500ms) / overlayAttached）

---

## 7. 资源清单

### 7.1 `colors.xml`（values）
```xml
<resources>
    <color name="md_seed">#0061A4</color>
    <color name="primary">#0061A4</color>
    <color name="on_primary">#FFFFFF</color>
    <color name="rect_fill_edit">#4D0061A4</color>      <!-- 30% alpha primary -->
    <color name="rect_border_edit">#0061A4</color>
    <color name="status_active">#2E7D32</color>          <!-- 绿色：屏蔽中 -->
    <color name="status_inactive">#C62828</color>        <!-- 红色：已暂停 -->
    <color name="preview_bg_subtle">#0F000000</color>    <!-- 极浅黑遮罩 -->
</resources>
```

### 7.2 `themes.xml`（values）
```xml
<resources>
    <style name="Theme.TouchBlocker" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="android:windowBackground">?attr/colorSurface</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
```

### 7.3 `strings.xml`
```xml
<resources>
    <string name="app_name">触控屏蔽</string>
    <string name="service_description">屏幕区域触控屏蔽运行时</string>
    
    <!-- 主界面 -->
    <string name="action_enable">启用屏蔽</string>
    <string name="action_pause">暂停屏蔽</string>
    <string name="action_reset">恢复默认</string>
    <string name="status_editing">拖动矩形调整位置和大小</string>
    <string name="status_enabled">屏蔽已启用 · 区域内触控将被拦截</string>
    <string name="coordinates_format">X:%1$d  Y:%2$d  W:%3$d  H:%4$d</string>
    
    <!-- 权限引导 -->
    <string name="perm_overlay_title">需要悬浮窗权限</string>
    <string name="perm_overlay_desc">为了在屏幕上方拦截触控操作，需要授予「显示在其他应用上层」权限。</string>
    <string name="perm_overlay_grant">前往授权</string>
    <string name="perm_xiaomi_title">小米/HyperOS 用户请额外开启</string>
    <string name="perm_xiaomi_step1">「后台弹出界面」权限</string>
    <string name="perm_xiaomi_step2">「自启动」开关</string>
    <string name="perm_xiaomi_step3">「省电策略」设为无限制</string>
    <string name="perm_xiaomi_lock">在最近任务中长按本应用，选择锁定</string>
    <string name="perm_open_app_settings">打开应用详情</string>
    <string name="perm_done">我已设置完成</string>
    
    <!-- 通知 -->
    <string name="notif_channel_name">屏蔽运行状态</string>
    <string name="notif_channel_desc">指示屏蔽功能是否正在运行</string>
    <string name="notif_title">触控屏蔽已启用</string>
    <string name="notif_text">区域 %1$dx%2$d · 点击返回应用</string>
    <string name="notif_action_stop">立即停止</string>
    
    <!-- 提示 -->
    <string name="toast_starting">即将启用屏蔽…</string>
    <string name="toast_stopped">屏蔽已暂停</string>
    <string name="toast_size_too_small">区域太小（最少 72dp）</string>
    <string name="toast_size_clamped">区域已限制为最大允许尺寸</string>
</resources>
```

---

## 8. 编译/打包/安装

```bash
cd /home/ubuntu/Project/CCG
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 小米 13 Ultra 手动保活路径（首次安装后）
1. 设置 → 应用 → 应用管理 → 触控屏蔽 → 权限管理：
   - 显示在其他应用上层 ✓
   - 通知 ✓
   - 后台弹出界面 ✓
   - 自启动 ✓
2. 省电策略 → 无限制
3. 最近任务里长按卡片 → 锁定

---

## 9. 风险与缺陷（不可绕过的边界）

| 等级 | 描述 |
|---|---|
| **High** | 状态栏/IME/系统手势区不能屏蔽（TYPE_APPLICATION_OVERLAY 位于关键系统窗口之下） |
| **High** | 银行/支付页面调用 `HIDE_OVERLAY_WINDOWS` 时屏蔽暂时失效（无法绕过，符合预期） |
| **High** | Android 13+ 用户可在 Task Manager 直接终止 FGS |
| **High** | `specialUse` FGS type 上 Play 商店有审核风险（本地分发不影响） |
| **Medium** | HyperOS 极端省电模式下进程可能被回收 |
| **Medium** | 通知权限被拒后，停止只能通过应用内或 Task Manager |
| **Low** | 单矩形/竖屏锁定/SharedPreferences 实现风险低 |

---

## 10. 实施任务拆分（Phase 4 用）

按依赖顺序实施：

1. Gradle 骨架（settings/build/libs/wrapper）
2. AndroidManifest + 资源（strings/colors/themes/drawable）
3. `BlockArea` 数据模型
4. `BlockAreaRepository` + `SharedPrefsBlockAreaRepository`
5. `DisplayHelper`、`PermissionHelper`
6. `NotificationHelper`
7. `OverlayView`（编辑模式 + 运行模式）
8. `OverlayService`
9. `MainActivity` + layout XML
10. 权限引导子页
11. App icon adaptive
12. 联调编译

---

## 11. 验收标准

| 标准 | 验证方式 |
|---|---|
| 在小米 13U 安装并启动 | `adb install` + 应用启动 |
| 首次启动引导悬浮窗权限 | 无权限时自动跳转设置 |
| 编辑模式可拖动/缩放矩形 | 手动操作 |
| 启用后区域内触控失效（区域外正常） | QQ/微信/浏览器实测 |
| 配置重启不丢失 | 杀进程后重新启动 |
| 锁定竖屏 | 旋转设备应用不响应 |
| APK ≤ 5MB | `ls -l app-release.apk` |
| 启动时间 ≤ 2s | 冷启动观察 |
| 矩形最大尺寸自动限制 | 拖到边界后停止 |
| 启用延迟 500ms | 点击启用按钮观察 |
