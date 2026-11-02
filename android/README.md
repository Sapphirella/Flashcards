# Android 宿主工程

把网页版闪卡打包成可安装的 APK。整个 App = 一个全屏 WebView + 打包进 APK 的 `index.html`。

**页面源码不在本工程内**：构建时 `syncWebAssets` 任务会把项目根目录的 `index.html` 同步到
`assets/web/index.html`，并去掉 Google Fonts 的 CDN 引用、换成内置离线字体。
所以「网页版」和「APK 版」永远是同一份源文件，不会各自漂移。

```
android/
├── build.gradle              根构建脚本
├── settings.gradle           仓库与模块声明
├── gradle.properties
├── gradlew / gradlew.bat     Gradle wrapper（8.14.3，已入库）
├── gradle/wrapper/           wrapper 配置与 jar
├── keystore/                 签名密钥（不入库，见下）
├── build-apk.sh              离线构建脚本（不依赖 Gradle）
├── tools/
│   └── prepare-web-assets.sh 去掉 CDN 字体引用、注入本地字体表
└── app/
    ├── build.gradle          模块配置 + 网页资源同步任务
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/amarisgo/flashcards/
        │   ├── MainActivity.java   WebView 宿主、返回键、文件选择、系统栏内边距
        │   ├── AssetServer.java    assets → 虚拟 https 源（读 assets/web/）
        │   ├── NativeBridge.java   window.Android 桥：状态栏 / 存文件 / WebDAV
        │   └── SimpleHttp.java     支持任意方法（含 PROPFIND）的 HTTP 客户端
        ├── res/                     主题、颜色、应用名、自适应图标
        └── webfonts/                内置离线字体（Latin）
```

---

## 1. 环境准备

需要三样东西（macOS 上的路径，仅作参考）：

| 依赖 | 本机路径 | 说明 |
| --- | --- | --- |
| JDK 17+ | `/opt/homebrew/opt/openjdk@17` | `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` |
| Android SDK | `~/Library/Android/sdk` | 需要 platform 35、build-tools 35/36 |
| Gradle | 不需要单独装 | 用仓库自带的 `./gradlew`（wrapper 指向 8.14.3） |

> wrapper **首次运行**会从 `services.gradle.org` 下载 Gradle 发行包（约 130MB）。
> 如果网络受限或不想等，直接用 `./build-apk.sh` —— 它只依赖本机的 build-tools 与 JDK，
> 产出的 APK 与 Gradle 路径完全一致。

在 `android/local.properties` 里写 SDK 路径（该文件不入库）：

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

或者设置环境变量 `ANDROID_HOME`。

---

## 2. 构建

有两条路径，**产出的 APK 内容一致**。

### 2.1 Gradle（常规路径）

```bash
cd android

# 调试包：自动用 debug 签名，装上就能用
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 正式包：用 keystore/flashcards.jks 签名
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

### 2.2 离线脚本（没有网络时用）

`build-apk.sh` 只依赖本机的 **Android SDK build-tools + JDK**，不需要下载 Gradle 发行包：

```bash
cd android
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

./build-apk.sh              # 正式包
./build-apk.sh --debug      # 调试包
# → build/manual/out/flashcards-release.apk
# → build/manual/out/flashcards-debug.apk
```

它手工串起了整条流水线：

```
prepare-web-assets.sh  →  aapt2 compile  →  aapt2 link  →  javac  →  d8
                        →  aapt add  →  zipalign  →  apksigner
```

脚本会自动探测 `JAVA_HOME`（依次尝试 `/opt/homebrew/opt/openjdk@17`、`/opt/homebrew/opt/openjdk`、`/usr/libexec/java_home`），
SDK 路径取 `$ANDROID_HOME` / `$ANDROID_SDK_ROOT` / `~/Library/Android/sdk`。
可用环境变量覆盖：`BUILD_TOOLS_VERSION`（默认 35.0.0）、`PLATFORM_VERSION`（默认 35）。

> ⚠️ 两点注意：
> 1. `build-apk.sh` 用 `aapt2` 直接处理清单，因此会在构建时生成一份带 `package` 属性的临时清单
>    （源码里的 `AndroidManifest.xml` 保持 AGP 8 兼容，不写 `package`）。
> 2. **APK 里网页资源的目录结构必须是 `assets/web/`**（`AssetServer.ASSET_ROOT = "web"`）。
>    离线脚本直接输出到 `assets/web/`；Gradle 侧用 `addGeneratedSourceDirectory` 时，
>    输出目录的内容会被平铺到 assets 根下，所以生成任务里特意套了一层 `web/`。
>    改完这块务必用 `unzip -l xxx.apk | grep assets/` 确认层级 —— 层级错了装上就是白屏。

产物路径汇总：

| 变体 | Gradle | 离线脚本 | 包名 |
| --- | --- | --- | --- |
| 正式 | `app/build/outputs/apk/release/app-release.apk` | `build/manual/out/flashcards-release.apk` | `com.amarisgo.flashcards` |
| 调试 | `app/build/outputs/apk/debug/app-debug.apk` | `build/manual/out/flashcards-debug.apk` | `com.amarisgo.flashcards.debug` |

---

## 3. 安装到手机

```bash
# 方式一：adb
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk

# 方式二：把 apk 传到手机上直接点开安装（需允许「安装未知来源应用」）
```

调试包带 `.debug` 后缀（`applicationIdSuffix`），可以与正式包共存，方便对比测试。

---

## 4. 签名密钥

`keystore/` 目录**不入库**（见 `android/.gitignore`）。仓库里没有密钥，需要自己生成：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd android && mkdir -p keystore

"$JAVA_HOME/bin/keytool" -genkeypair -v \
  -keystore keystore/flashcards.jks -storetype PKCS12 \
  -alias flashcards -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass flashcards -keypass flashcards \
  -dname "CN=Flashcards, OU=Personal, O=Flashcards, L=, ST=, C=CN"
```

再写 `keystore/keystore.properties`：

```properties
storeFile=keystore/flashcards.jks
storePassword=flashcards
keyAlias=flashcards
keyPassword=flashcards
```

> ⚠️ **这把密钥务必自己留一份。** Android 规定：换了签名密钥就不能覆盖安装，必须先卸载旧版，
> 而卸载会连同 localStorage 里的卡片数据一起删掉。
> 正式发布请另建一把并离线备份。

---

## 5. 这个宿主工程解决了哪些网页版的问题

| 网页版的限制 | APK 里的处理 |
| --- | --- |
| `file://` 下 localStorage 支持不一致 | 用虚拟 https 源 `https://flashcards.local/` 加载页面，同源环境正常，localStorage 稳定持久化 |
| WebDAV 跨域被 CORS 拦下 | `window.Android.davRequest` 走原生 HTTP，不受同源策略约束 |
| `HttpURLConnection` 不支持 `PROPFIND` | `SimpleHttp` 用 Socket 手写 HTTP/1.1，方法不受限 |
| `<a download>` 导出在 WebView 里无效 | `window.Android.saveFile` 写入系统「下载」目录（API 29+ 走 MediaStore，免权限） |
| 状态栏配色与应用主题不同步 | `window.Android.setStatusBar` 实时同步 |
| 返回键直接退出应用 | 返回键先交给 `window.Flashcards.back()` 关闭弹窗 / 抽屉 / 菜单 |
| 依赖 Google Fonts，离线字体发不出来 | 移除 CDN 引用，Latin 字体内置为 woff2，中日韩用系统 Noto CJK |

这些能力都通过页面里已有的 `window.Android` 接口接入，因此 `index.html` 里的改动非常小，
且**在纯网页环境下行为完全不变**（`window.Android` 不存在时走原来的 `fetch`）。

---

## 6. 调试

```bash
# 看日志
adb logcat | grep -i flashcard

# 远程调试 WebView
# Chrome 桌面版打开 chrome://inspect/#devices，选中本设备 → inspect
```

`WebView.setWebContentsDebuggingEnabled(true)` 只在 debuggable 的构建里开启。

---

## 7. 常见问题

**Q：构建报 `SDK location not found`**
A：`android/local.properties` 没写 `sdk.dir`，或路径不对。

**Q：`Unsupported class file major version` / `Unsupported Java.17`**
A：Gradle 用的是系统自带的旧 JDK。`export JAVA_HOME=/opt/homebrew/opt/openjdk@17` 再构建。

**Q：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**
A：装的是不同签名的版本，先 `adb uninstall com.amarisgo.flashcards`（会丢数据，先在应用里导出 JSON）。

**Q：改了根目录的 index.html，APK 里没生效**
A：`syncWebAssets` 用的是 `rootProject.file('../index.html')`，也就是 `android/` 的上一级。
确认根目录的 `index.html` 才是要打包的那份，然后重新构建。

**Q：APK 里字体没换掉**
A：检查 `app/src/main/webfonts/` 下是否真的有 woff2 文件；`syncWebAssets` 会把该目录下的
woff2 / woff / css 原样拷到 `assets/web/fonts/`。

**Q：改完 `index.html` 后 APK 还是旧的**
A：先确认改的是**仓库根目录**那份 `index.html`（不是 `android/` 里的副本），然后重新构建。
`prepare-web-assets.sh` 会检查 CDN 引用并替换成 `fonts/fonts.css` 本地引用。

---

## 8. 验证状态（如实说明）

出包时构建机上**没有 Android 设备，也没有模拟器镜像**（SDK 里没有 `emulator` 目录与 system-images），
因此以下部分只做了静态验证：

**已验证** ✅

- `aapt dump badging`：应用名「闪过闪卡」、包名、minSdk 26 / targetSdk 35、权限声明、launchable-activity、自适应图标齐全
- `apksigner verify`：v2 + v3 签名通过
- `zipalign -c -v 4`：对齐通过
- APK 内容：`assets/web/index.html`（约 63KB）、`assets/web/fonts/*`、`classes.dex`（约 22KB）、5 个自定义类齐全
- 打包后的 `index.html` 已确认不含任何 CDN 引用，只剩本地 `fonts/fonts.css`
- **Gradle 与离线脚本两条构建路径都跑通了**，且两者产出的 `assets/web/index.html` 逐字节相同
- 网页侧的宿主适配：`window.Flashcards.back()` 与 `davFetch()` 的网页降级分支已在浏览器里实测通过
  （弹窗 / 抽屉 / 菜单三种浮层都能被 `back()` 关闭；无浮层时返回 `false`；无 `window.Android` 时 `davFetch` 走 `fetch`）

**未验证** ⚠️

- **真机安装与运行**：没装过任何一台设备。请装到真机后按 `HANDOVER.md` §10.1 的清单走一遍
- **WebDAV 原生桥**：需要真机 + 真实 WebDAV 服务器才能确认 `davRequest` 能否成功绕过 CORS
- **系统栏 insets / 安全区**：需要全面屏真机确认边缘到边缘的留白是否合适

接手后建议第一件事就是装个真机把这几条补上。
