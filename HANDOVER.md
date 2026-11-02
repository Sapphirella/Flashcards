# 项目接手文档

> 面向接手「闪过 Flashcards」的新同学/新 AI 助手。这份文档讲清楚**代码是怎么组织的、数据怎么流动、为什么这么写、以及改代码时要注意什么**。
>
> 想快速了解「这 app 能干什么」请看 [README.md](README.md)。

---

## 1. 一句话认识它

一个**单文件**纯前端闪卡应用。全部功能（界面、逻辑、样式、数据）都在 `index.html` 一个文件里，**没有构建步骤、没有 npm、没有框架、没有后端**。

这不是偷懒，而是刻意的设计决策：

| 决策 | 原因 |
| --- | --- |
| 单文件 | 整个应用就是一个网页，拷走 `index.html` 到任何地方都能用 —— 手机、U 盘、静态托管、离线打开 |
| 不用框架 | 这个体量（~1353 行）用框架反而是负担；原生 DOM 操作在这个规模下完全够用 |
| localStorage 存数据 | 零后端成本，且离线可用；用 WebDAV 解决多设备同步 |
| 无构建 | 想改就改，保存刷新即生效，没有 `npm install` 门槛 |

**接手时请不要轻易引入构建工具或框架** —— 那会破坏这个项目最重要的优点（打开就能用）。除非需求变了。

---

## 2. 目录结构

```
Flashcards/
├── index.html          # 全部代码（HTML + CSS + JS，1353 行）
├── README.md           # 项目说明：功能、数据格式、快速开始
├── HANDOVER.md         # 本文件
├── .gitignore
├── docs/images/        # README 配图
└── android/            # Android 宿主工程（WebView 外壳），见 §6.2
```

没有 `package.json`、没有 `src/`、没有构建产物。**所有改动都发生在 `index.html` 里。**

> `android/` 是唯一的多文件目录，但它是**宿主的壳**，不是应用代码的所在地 —— APK 里打包的仍然是根目录这一份 `index.html`，构建时同步进去。网页版与 APK 版永远同源。

---

## 3. 架构

### 3.1 全局结构

```
<body>
  └─ #app                       height:100dvh，flex column，overflow:hidden
       ├─ section#vStudy       背诵视图
       ├─ section#vLibrary     卡片库视图
       └─ section#vSettings    设置视图
  ├─ .scrim                    全局遮罩（弹窗/搜索面板/菜单共用）
  ├─ .menu#deckMenu            卡组下拉菜单
  ├─ .searchsheet#searchSheet  下拉搜索面板（含搜索框 + 结果列表）
  ├─ .modal#modal              通用弹窗
  ├─ .toast                    轻提示
  └─ .hint                     首次使用提示
```

三个视图是**同级 section**，通过 `.view{display:none}` / `.view.is-active{display:flex}` 切换。同一时刻只有一个可见，所以不需要路由。

### 3.2 状态

只有两个全局状态对象（`index.html:466` 起）：

```js
let store = null;   // 业务数据：{version, cards[], groups[], settings{}}
let ui = {
  view: 'study',    // 当前视图
  group: 'all',     // 背诵的卡组（'all' | 'star' | groupId）
  index: 0,         // 背诵的卡片下标
  flipped: false,   // 卡片是否已翻面
  batch: false,     // 卡片库是否处于批量模式
  sel: new Set(),   // 批量模式选中的卡片 id
  libGroup: 'all'   // 卡片库的筛选卡组
};
```

- `store` 是**持久化数据**，任何修改后必须 `save()` 写进 localStorage。
- `ui` 是**瞬时界面状态**，不落盘（`ui.group` 除外，它会同步到 `store.settings.group`）。
- `ui.sel` 用 `Set` 存选中项，切换到非批量模式时必须 `ui.sel.clear()`。

### 3.3 数据流

典型的单向流，非常朴素：

```
用户操作 → 修改 store / ui → save() 落盘 → render*() 重绘 → innerHTML 重建
```

三个渲染函数是全部的视图更新入口：

| 函数 | 位置 | 负责 |
| --- | --- | --- |
| `renderStudy()` | `index.html:567` | 背诵视图：卡片正反面、进度条、底部计数 |
| `renderLib()` | `index.html:847` | 卡片库：卡组 chips、卡片列表、批量栏 |
| `renderSearch()` | `index.html:705` | 下拉搜索面板的结果列表 |

**注意**：这些函数是**整块 `innerHTML` 替换**，不是局部更新。改完数据只要调用对应 `render*()` 即可，不需要手动刷新 DOM。

### 3.4 事件模型

没有 `addEventListener` 挂在动态生成的元素上。统一用**事件委托**：

```js
el('libList').addEventListener('click', e => {
  const sel = e.target.closest('[data-sel]');
  if (sel) { ... }
});
```

即：HTML 模板里写 `data-xxx="值"`，JS 里用 `closest('[data-xxx]')` 取值。

**新增交互时的标准做法**：在模板里加 `data-*` 属性 → 在对应的委托监听器里加一个 `closest()` 分支 → 完事。不要试图给 innerHTML 生成的元素单独绑事件。

### 3.5 存储层

```js
const KEY      = 'flashcards.v2';   // 当前版本
const OLD_KEY  = 'flashcards.v1';   // 旧版本（仅迁移用）
const HINT_KEY = 'flashcards.hint.v1'; // 只记「提示是否显示过」
```

- `load()`（`index.html:510`）：依次尝试读 v2 → 读 v1 并迁移 → 都没有就 `seed()` 写示例数据。
- `save(data)`（`index.html:521`）：`localStorage.setItem(KEY, JSON.stringify(data || store))`，**包了 try/catch**，写入失败（隐私模式、配额满）时静默忽略。
- `migrateV1()`（`index.html:501`）：把 v1 的 `decks[]` 结构转成 v2 的 `groups[] + cards[].groups[]`。
- `seed()`（`index.html:482`）：只在**从未存过数据**时跑一次，写 3 张示例卡片 + 3 个示例卡组。
- `emptyContent()`（`index.html:496`）：清空卡片与卡组，保留外观与 WebDAV 设置。「设置 → 清空全部数据」调的是它。

> ⚠️ **清空数据要写回一个「空 store」，而不是 `localStorage.removeItem(KEY)`。**
> `load()` 只在 KEY **不存在**时才会跑 `seed()`；如果只是删掉 KEY 而不写回空数据，
> 下次打开示例卡片会全部回来。清空的正确做法是 `emptyContent(); save();`。

**破坏性变更时不要删旧 key 的兼容读取** —— 用户升级后老数据还在 localStorage 里，读不到就等于清空。

---

## 4. 模块地图（带行号）

> 行号对应当前 `index.html`（1353 行）。改动后行号会漂移，用注释标记 `/* ================= xxx ================= */` 搜索更可靠。

| 分块 | 行 | 要点 |
| --- | --- | --- |
| CSS 变量 / 主题 | 12–38 | `:root` 定义配色，`[data-theme="dark"]` 覆盖，`[data-fs="lg\|xl"]` 覆盖字号 |
| 布局骨架 | 53–55 | `#app` / `.view` / `.scroll`（`.scroll` 已于本次修复移除） |
| 背诵视图样式 | 75–135 | 卡片 3D 翻面、滑动、飞入飞出关键帧 |
| 浮层样式 | 137–204 | 遮罩、菜单、**搜索面板**、弹窗、按钮 |
| 卡片库样式 | 206–248 | **批量栏修复重点**，见 §5 |
| 设置样式 | 250–287 | 分组卡片、开关行 |
| storage | 462 | key 常量、`uid()`、`esc()`、`toast()`、`fmt()` |
| seed / migrate | 478 | **3 张**示例卡片、3 个示例卡组、v1 迁移、`emptyContent()` |
| data helpers | 525 | `cardsOf(gid)` 是**最核心的查询函数** |
| theme | 549 | `applyTheme()` 应用主题 + 同步状态栏 |
| study view | 564 | `renderStudy` / `flip` / `goCard` / `toggleStar` / `starCurrent` |
| group menu | 656 | 卡组下拉菜单（含「星标」入口与「清空星标」） |
| search sheet | 689 | 下拉搜索面板：`matchCards` / `markHit` / `renderSearch` / `jumpToCard` |
| gestures | 741 | `onDown` / `onMove` / `onUp`，阈值常量 `TH` |
| library | 846 | `renderLib()` 渲染列表与批量栏 |
| modal | 894 | `openModal(title, bodyHTML, buttons, onClose)` 通用弹窗 |
| card editor | 929 | `editCard()` 新增/编辑，`groupPicker()` 卡组选择器 |
| group manager | 972 | `manageGroups` / `newGroup` / `renameGroup` / `delGroup` |
| batch ops | 1025 | `batchGroups(mode)` 批量加入/移出卡组 |
| import/export | 1050 | `exportJson` / `exportTxt` / `mergeStore` / `importJson` |
| webdav | 1119 | `davFetch` / `davTest` / `davSync` |
| views | 1175 | `show(view)` 切换视图、`closeTopOverlay()` 关闭最上层浮层 |
| events | 1200 | 所有事件绑定、键盘快捷键 |
| init | 1333 | 启动：load → applyTheme → render → 首次提示 |

### 关键函数速查

| 函数 | 作用 | 注意 |
| --- | --- | --- |
| `cardsOf(gid)` | 按卡组取卡片；`'all'` 全部、`'star'` 星标 | 返回**新数组**（`slice()` / `filter()`），不要直接改 |
| `groupCount(gid)` | 卡组内卡片数 | 基于 `cardsOf` |
| `save()` | 落盘 | **每次改数据后都要调** |
| `touchCard(c)` / `touchGroup(g)` | 更新 `updatedAt` | WebDAV 合并的决胜依据，**改数据必须调** |
| `mergeStore(local, remote)` | 按 `updatedAt` 逐条合并 | 导入「合并」与 WebDAV 同步共用 |
| `openModal(...)` | 通用弹窗 | 按钮 `fn` 返回 `false` 可阻止自动关闭 |
| `esc(s)` | HTML 转义 | **任何用户输入拼进 innerHTML 前必须过一遍** |
| `hl(s)` | 高亮 `「」` 包裹的内容 | 背面答案里 `「...」` 会渲染成粉色高亮 |

---

## 5. 布局约定（**最容易踩坑的地方**）

### 5.1 三段式布局原则

卡片库（以及设置视图）是这个结构：

```
┌─────────────────────┐
│ .libhead  (flex:none)│  固定头部
├─────────────────────┤
│ .libscroll          │  唯一可滚动区域
│  (flex:1;min-height:0│  overflow-y:auto
│   overflow-y:auto)  │
├─────────────────────┤
│ .libfoot / .batchbar│  固定底栏
│  (flex:none)        │
└─────────────────────┘
```

**规则：**

1. **可伸缩的滚动区必须同时写 `flex:1` 和 `min-height:0`。**
   `min-height:0` 是关键 —— flex item 默认 `min-height:auto`，不写的话内容会把容器撑破，滚动条不会出现。

2. **固定尺寸的头/尾必须写 `flex:none`（`flex-shrink:0`）。**
   不写就会被 flex 收缩算法压扁。

3. **不要用 `position:sticky; bottom:0` 来做底栏。**
   sticky 定位依赖「祖先是滚动容器」且「元素本身在滚动流里」，在 flex 列布局里和收缩算法打架 —— 这正是下面那个 bug 的根源。

### 5.2 ⚠️ 血泪教训：批量栏被压扁的 bug

**现象**：点击「批量管理」后，底部操作栏被屏幕下边缘裁掉一半，「全选 / 加入卡组 / 移出卡组 / 删除 / 退出」只露出上半截。

**根因**（三个条件叠加）：

1. `.batchbar` 漏写了 `flex:none`（旁边的 `.libfoot` 有），所以它是**可收缩**的 flex item。
2. 它带着 `overflow-x:auto; overflow-y:hidden`，**因此是一个滚动容器**，而滚动容器的自动最小尺寸是 **0** —— 所以它可以被压到任意小。
3. 列表变长把列 flex 容器撑出溢出时，flex 收缩算法就把 `.batchbar` 压到只剩上下内边距（24px）。34px 高的按钮溢出这个盒子，被 `overflow-y:hidden` 裁掉 —— 看起来就是「底栏溢出屏幕外」。

**次生现象**：列表较短时容器不溢出，`position:sticky;bottom:0` 不会把底栏拉到底部，它停在列表末尾，浮在屏幕中间。

**修复**：改成 §5.1 的三段式布局，两个底栏统一 `flex:none`，彻底移除 sticky 依赖。

**怎么复现**：卡片库 → 点右上角「批量管理」→ 看底栏。建议同时验证「卡片很多（长列表）」和「只有 3 张（短列表）」两种情况 —— 修复前这两种的表现完全不同，只测一种会漏。

> 通用教训：**在 flex 列布局里，`overflow` 不是 `visible` 的元素会自动最小尺寸归零**，只要它同时还是可收缩的 flex item，就存在被压扁的风险。给固定头尾一律加 `flex:none`。

### 5.3 顶部下拉面板（搜索面板）

搜索面板 `.searchsheet` 是从顶部滑下来的 sheet，结构和抽屉时代一样，但要注意两点：

```
position:fixed; top:0; height:auto; max-height:min(78dvh,620px);
transform:translateY(-101%)   ← 藏在屏幕上方
.searchsheet.show{transform:none}
```

1. **面板会盖住顶部的卡组按钮**（`deckChip`）。所以面板里必须有一个明确的「收起」入口 ——
   现在是右上角的 ✕（`searchClose`），它会**关闭面板并清空查询**；
   点遮罩（`.scrim`）也能关。早期版本 ✕ 只清空文本不关面板，结果用户想用卡组菜单还得先点遮罩，很别扭。

2. **拖拽过程用 inline `transform` 跟手，松手后再交给 CSS transition。**
   `onMove` 里设 `d.style.transition='none'` 并直接写 `translateY(dy)`；
   `onUp` 里根据位移是否超过阈值（`TH.searchPx`）决定吸附展开还是滑回去。

### 5.4 安全区适配

底部统一用 `padding-bottom: calc(env(safe-area-inset-bottom) + 14px)`，头部用 `padding-top: calc(env(safe-area-inset-top) + 10px)`。这样在 iPhone 刘海屏 / 底部横条上不会被系统栏压住。**新增固定在底部的元素时别忘了这个。**

---

## 6. 外部集成

### 6.1 WebDAV

- 地址格式：`https://dav.example.com/flashcards`（不带结尾斜杠，代码会 `replace(/\/+$/,'')`）。
- 实际文件路径：`<地址>/<目录>/flashcards.json`。
- 认证：`Basic` + `btoa(unescape(encodeURIComponent(user + ':' + pass)))` —— 那段 `unescape/encodeURIComponent` 是为了兼容中文密码。
- 测试连接：先 `OPTIONS`，若 `!r.ok && status!==405` 则退回 `PROPFIND`（`Depth:0`）。
- 同步：`GET` 远端 → `mergeStore(local, remote)` → `PUT` 回远端。

**合并语义**：卡片和卡组**各自独立**比较 `updatedAt`，谁的 `updatedAt` 大用谁。同一张卡在两台设备上同时被改，仍然会丢一方（last-write-wins），这是有意为之的简化。

### 6.2 Android 宿主工程

`android/` 是一套**独立的宿主工程**，把本应用包成可安装的 APK。整个 App = 一个全屏 WebView + 打包进去的**同一份** `index.html`。

**它不改变应用架构**：`android/app/src/main/` 里没有一份页面副本，构建时由 Gradle 的 `syncWebAssets` 任务（或离线脚本 `android/tools/prepare-web-assets.sh`）把根目录的 `index.html` 同步到 `assets/web/index.html`，顺带把 Google Fonts 的 CDN 引用换成内置的离线字体。所以「网页版」和「APK 版」永远是同一份源文件，不会各自漂移。

#### 6.2.1 四个 Java 类

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 全屏 WebView、加载虚拟源、系统栏 insets 内边距、返回键接管、文件选择器 |
| `AssetServer.java` | 把 `assets/web/` 映射成虚拟 https 源 `https://flashcards.local/`，含目录穿越防护 |
| `NativeBridge.java` | `window.Android` 桥：`setStatusBar` / `saveFile` / `davRequest` |
| `SimpleHttp.java` | 用 Socket 手写 HTTP/1.1 的客户端，**支持任意方法** |

#### 6.2.2 三个关键设计决策

1. **用虚拟 https 源而不是 `file:///android_asset/`。**
   `file://` 是不透明源，各版本 WebView 对 localStorage 的支持不一致；而 localStorage 是本应用**唯一**的存储手段，一旦不持久化用户就丢卡片。`https://flashcards.local/` 是正常的同源安全上下文，localStorage 稳定可用。

2. **WebDAV 走原生 HTTP 桥而不是 `fetch`。**
   WebView 里 `fetch` 受同源策略约束，跨域的 WebDAV 服务器不会返回 CORS 头 → 直接被浏览器拦掉。而 `HttpURLConnection.setRequestMethod()` 不接受 `PROPFIND`，所以 `SimpleHttp` 用 `Socket` 手写 HTTP/1.1，请求方法不受限。

3. **能力全部通过 `window.Android` 接入，页面侧改动极小。**
   `index.html` 里只加了三处：webdav 段的 `davFetch()` 包装（4 个调用点替换）、views 段的 `closeTopOverlay()`、文件末尾的 `window.Flashcards.back()`。**纯网页环境下 `window.Android` 为 undefined，行为与改动前完全一致。**

#### 6.2.3 桥接协议

页面 → 原生：

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `Android.setStatusBar(color, lightIcons)` | `#RRGGBB`、布尔 | void（异步切主线程执行） |
| `Android.saveFile(name, content, type)` | 文件名、文本内容、MIME | void（失败时原生弹 Toast 兜底） |
| `Android.davRequest(method, url, headersJson, body)` | 方法、URL、JSON 字符串的请求头、请求体 | JSON 字符串：`{"status":207,"body":"..."}` 或 `{"error":"..."}` |

`davFetch()` 把原生返回包装成近似 `fetch` 的对象（只需 `ok` / `status` / `json()` / `text()`），让 `davTest` / `davSync` 的代码在两种环境下一模一样。

原生 → 页面：返回键事件。`MainActivity` 优先调 `window.Flashcards.back()`（关掉最上层的弹窗 / 搜索面板 / 菜单）；返回 `false` 说明没有浮层可关，才执行默认的「返回上一层 / 退出」。

#### 6.2.4 构建

两条路径，**产出的 APK 内容一致**（实测 `assets/web/index.html` 逐字节相同）：

```bash
cd android
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

./gradlew assembleRelease     # 正规路径（仓库自带 wrapper，Gradle 8.14.3）
./build-apk.sh                # 离线路径：只用本机 build-tools + JDK，无需下载 Gradle
./build-apk.sh --debug        # 离线路径的调试包
```

`build-apk.sh` 是手写的离线构建：`aapt2 compile/link` → `javac` → `d8` → `aapt add` → `zipalign` → `apksigner`。存在的意义是**在网络受限的环境里也能出包**，不作为日常开发路径。

产物：

| 变体 | Gradle 路径 | 离线脚本路径 | 包名 |
| --- | --- | --- | --- |
| 正式 | `app/build/outputs/apk/release/app-release.apk` | `build/manual/out/flashcards-release.apk` | `com.amarisgo.flashcards` |
| 调试 | `app/build/outputs/apk/debug/app-debug.apk` | `build/manual/out/flashcards-debug.apk` | `com.amarisgo.flashcards.debug` |

配置：AGP 8.11.0 / Gradle 8.14.3 / compileSdk 35 / minSdk 26 / targetSdk 35 / versionCode 2 / versionName 2.0。

- **minSdk 26（Android 8.0）**：这样只需要自适应图标（纯 XML），不必生成 PNG 位图。
- **targetSdk 35 + 手动应用 systemBars insets 作为 padding**：API 35 强制 edge-to-edge，早期版本系统已内缩窗口、insets 为 0，两者统一处理不会重复留白。
- **调试包用 `.debug` 后缀**（`applicationIdSuffix`），可与正式包共存对比。

> ⚠️ **APK 里网页资源的目录结构是 `assets/web/`，不是 `assets/` 根目录。**
> `AssetServer.ASSET_ROOT = "web"`，虚拟源把 `/index.html` 映射到 `assets/web/index.html`。
> 用 AGP 的 `addGeneratedSourceDirectory` 时，**输出目录的内容会被平铺到 assets 根下**，
> 所以生成任务必须在输出目录里再套一层 `web/`，否则 APK 装上后是一张白屏（404）。
> 离线脚本 `prepare-web-assets.sh` 则直接输出到 `assets/web/`，两条路径必须保持一致。
> 改这块之后，务必用 `unzip -l xxx.apk | grep assets/` 确认目录层级。

#### 6.2.5 签名密钥

密钥在 `android/keystore/`，**已 gitignore，不入库**。仓库里只有生成命令（见 `android/README.md` §4）。

> ⚠️ **换签名密钥 = 必须卸载旧版 = 连同 localStorage 里的卡片数据一起删掉。** 正式发布请另建一把并离线备份。

#### 6.2.6 尚未验证的部分

| 项 | 状态 |
| --- | --- |
| 真机安装与运行 | **未验证** —— 构建机上没有 Android 设备，也没有模拟器镜像（SDK 里没有 `emulator` 与 system-images），只做了静态验证（`aapt dump badging` / `apksigner verify` / `zipalign -c` / dex 类检查 / 解包确认 `assets/web/` 目录层级与字体齐全） |
| Gradle 构建路径 | **已验证** —— `./gradlew assembleRelease assembleDebug` 与离线脚本产出的 `assets/web/index.html` 逐字节相同 |
| WebDAV 原生桥 | **未在真机验证** —— 网页版的 `fetch` 分支已实测通过，原生分支需要在真机上连一次真实的 WebDAV 服务器才能确认 |
| 系统栏 insets / 安全区 | **未验证** —— 需要全面屏真机确认边缘到边缘的留白是否合适 |

接手后第一件事建议：装到真机上把 §10.1 的清单重走一遍。

### 6.3 暴露给宿主的接口

```js
window.__fc = { store, ui, render, renderLib, show };   // 调试用
window.Flashcards = { view: () => ui.view, home: () => show('study'), back: () => closeTopOverlay() };
```

宿主 App 可用 `Flashcards.home()` 跳回首页、`Flashcards.view()` 查询当前视图、`Flashcards.back()` 关闭最上层浮层（弹窗 → 搜索面板 → 卡组菜单），没有浮层可关时返回 `false`。

`closeTopOverlay()` 在 `index.html:1193`。

---

## 7. 怎么加新功能（照着做就行）

### 7.1 加一个按钮并做某件事

1. 在对应视图的 HTML 里加按钮，给它一个唯一的 `id`。
2. 需要动态内容的话，加 `data-*` 属性而不是 `id`。
3. 在 `events` 分块里用 `el('yourId').onclick = ...` 绑定；或用事件委托处理动态列表。
4. 涉及数据的：**改 `store` → `touchCard`/`touchGroup` → `save()` → `render*()`**。

### 7.2 加一个新视图

1. 加 `<section class="view" id="vYourView">…</section>`，放在 `#app` 内。
2. 在 `show(view)`（`index.html:1176`）里加一行 `el('vYourView').classList.toggle('is-active', view==='yourview')`。
3. 加 `ui.yourview = ...` 状态。
4. 记得给 `#app` 里的兄弟 section 留出空间（它们 `display:none`，不占位）。

### 7.3 加一条数据字段

1. `seed()`（`index.html:482`）里给示例卡片补上新字段。
2. `editCard()`（`index.html:941`）里加输入项，保存时写进对象。
3. `mergeStore()`（`index.html:1077`）里补上新字段的合并逻辑 —— **漏了这步，WebDAV 同步会把新字段抹掉**。
4. 如果字段会破坏兼容，考虑在 `load()` 里做默认值兜底。

### 7.4 加一种卡片操作

批量操作在 `batchBar` 的 `data-b` 分发（`index.html:1297`）和 `batchGroups()`（`index.html:1026`）两处。新的批量动作需要在这两处都加分支。

---

## 8. 已知问题与技术债

| # | 问题 | 影响 | 现状 |
| --- | --- | --- | --- |
| 1 | 无自动化测试 | 改动靠手动回归 | 未解决，建议至少为核心纯函数（`cardsOf` / `mergeStore`）补单测 |
| 2 | WebDAV 密码明文存 localStorage | 设备失守即泄露 | 有意简化，同步前请告知用户 |
| 3 | 批量栏在 < 340px 极窄屏下文字截断 | 显示为「加入卡…」 | 已用 `text-overflow:ellipsis` 兜底，功能正常 |
| 4 | 同一张卡并发修改会丢一方 | WebDAV 同步边缘情况 | last-write-wins 的固有代价 |
| 5 | 卡片库列表滚动位置在视图切换后保留 | 可能不符合预期 | 与原实现一致，暂未改 |
| 6 | `index.html` 单文件已达 1353 行 | 再加功能会继续膨胀 | 目前可接受；若超过 ~2000 行，建议至少拆出 CSS 与 JS（仍保持零构建，手写 `<link>`/`<script src>`） |
| 7 | 示例数据在首次打开时自动写入 | 打开即有 3 张示例卡片 | 设计如此（3 张够看清形态又不淹没内容），「设置 → 清空全部数据」可一键清掉 |
| 8 | 搜索面板不搜卡组名 | 只搜卡片正反面，搜不到「古诗文」这类卡组名 | 有意为之：卡组本来就能在菜单里直接选。要加的话改 `matchCards()` 一处即可 |
| 9 | 搜索跳转统一落到「全部卡片」上下文 | 从某个卡组里搜到别的卡组的卡，跳过去后卡组变成了「全部卡片」 | 有意为之：搜索是跨卡组的，沿用当前卡组上下文会导致「看到的卡」和「搜到的卡」对不上 |
| 10 | 清空数据不可撤销 | 手滑清空就没了 | 弹窗里已写明「无法撤销」；建议用户定期「导出 JSON」 |
| 11 | APK 未做真机安装验证 | 首次装机可能遇到未预料的问题 | 构建机上无设备也无模拟器镜像，只做了静态验证（签名/对齐/badging/资源完整性）；接手后请装真机走一遍 §10.1 |
| 12 | WebDAV 原生桥未在真机验证 | 原生分支能否真正绕过 CORS 未经证实 | 网页版的 `fetch` 分支已实测通过；原生分支需在真机上连一次真实 WebDAV 服务器才能确认 |
| 13 | 系统栏 insets / 安全区未在真机验证 | 全面屏机型可能留白不均 | API 35 的 edge-to-edge 处理是按文档实现的，缺真机确认 |

---

## 9. Git 工作流

仓库已初始化，分支为 `main`（如需 PR 流程可再开分支）。

```bash
git status                 # 看改动
git diff                   # 看具体差异
git add -A && git commit   # 提交
git log --oneline          # 看历史
```

**提交信息规范**：中文 Conventional Commits。

```
<type>(<scope>): <一句话说明>

type:  fix | feat | refactor | docs | chore | style | perf
scope: library | study | settings | webdav | storage | gestures | android
```

例子：

```
fix(library): 修复批量操作底栏溢出屏幕外的问题
feat(study): 增加卡片翻面动画时长设置
feat(android): 新增 Android 宿主工程与离线构建脚本
docs: 补充 WebDAV 合并策略说明
chore: 移除已废弃的 .scroll 工具类
```

**建议流程**：一个 bug 一个提交，提交信息里写清「现象 → 根因 → 修复」，方便后来者追溯。改大功能前先开一个分支。

**关于 `android/` 目录**：

- `android/keystore/`（签名密钥）与 `android/local.properties`（SDK 路径）**都不入库**，由 `android/.gitignore` 排除。
- `android/build/` 是构建产物目录，也被忽略；需要出包时本地跑一次构建脚本即可。
- `android/app/src/main/webfonts/` 下的 woff2 **要入库** —— 它们是离线字体，改了会被 `syncWebAssets` 打进 APK。

---

## 10. 验证清单（改完必走）

```bash
python3 -m http.server 8777   # 在项目根目录
# 打开 http://127.0.0.1:8777/index.html
```

逐项确认：

- [ ] **背诵视图**：翻面、左右滑动切卡、上滑星标、点星标按钮、键盘快捷键（空格 / ← → / S / D）
- [ ] **下拉搜索**：下拉能拉出搜索面板；输入关键词后正反面都能搜到、命中处有高亮；点结果能跳到那张卡；✕ 能关闭面板；点遮罩也能关
- [ ] **星标**：卡片上的星标按钮能加/取消；卡组菜单里有「星标」入口（点进去只刷星标）；有星标时菜单底部出现「清空星标」
- [ ] **卡片库**：卡组筛选、卡片增删改、批量模式（长列表 + 短列表各看一次底栏位置）
- [ ] **设置**：主题切换、字号切换、WebDAV 测试连接与同步、导出 JSON / TXT、导入 JSON（覆盖与合并两种）
- [ ] **清空数据**：设置 → 清空全部数据 → 卡片和卡组都归零，**刷新后仍然是 0**（不会被示例卡片重新灌回来）
- [ ] **首次打开**：清掉 localStorage 后打开，只有 **3 张**示例卡片
- [ ] **视口**：375×667（iPhone SE）、390×844（iPhone 14）、768×1024（平板）、桌面宽屏
- [ ] **控制台**：无报错
- [ ] **持久化**：刷新页面后卡片、星标、主题都还在
- [ ] **宿主接口**：`window.Flashcards.back()` 能依次关闭弹窗 / 搜索面板 / 菜单，没有浮层时返回 `false`

> 视口切换建议用浏览器的响应式模式，或直接改窗口宽度；`100dvh` 在移动端浏览器地址栏收起/展开时会变，务必在真机或模拟器上再看一眼。

### 10.1 APK 专属清单（装到真机后走一遍）

```bash
cd android
./build-apk.sh --debug && adb install -r build/manual/out/flashcards-debug.apk
```

- [ ] **能装上、能启动**，图标与名称正常（应用名「闪过闪卡」）
- [ ] **数据持久化**：加两张卡 → 杀掉进程 → 重开，卡片还在（这是虚拟 https 源方案的核心目的）
- [ ] **返回键**：弹窗打开时返回键关弹窗；搜索面板打开时关面板；都没有时退出应用
- [ ] **导出**：导出 JSON 后到系统「下载」目录能找到文件（走 MediaStore，免权限）
- [ ] **WebDAV**：连一个真实的 WebDAV 服务器，测试连接 + 同步都正常（这条验的是原生 HTTP 桥，不是网页版的 fetch）
- [ ] **状态栏**：切深浅主题，状态栏配色跟着变
- [ ] **离线字体**：飞行模式下打开，中文与标题字体都正常（Latin 用内置 woff2，CJK 用系统 Noto CJK）
- [ ] **安全区**：全面屏机型上，头部不被状态栏压住、底部不被手势条压住

---

## 11. 常见问题速查

**Q：改完样式没生效？**
A：确认改的是 `index.html` 里 `<style>` 块内的规则；CSS 有没有写错分号；浏览器强制刷新（Cmd/Ctrl + Shift + R）。

**Q：数据不见了？**
A：检查 `localStorage["flashcards.v2"]` 是否存在。用无痕窗口打开、换浏览器、换域名都会看到不同的存储空间。如果数据在 v1 键里，说明 `migrateV1` 抛异常被 `try/catch` 吞了 —— 看 console。

**Q：改坏了想回退？**
A：`git log --oneline` 找到目标提交，`git checkout <commit> -- index.html` 取回单个文件，或 `git revert <commit>` 撤销一个提交。

**Q：为什么不用 Vue / React？**
A：见 §1。这是个刻意的一页应用，引入框架的收益远小于破坏「打开就能用」这个优点的代价。

**Q：怎么加第二块屏幕 / 桌面版？**
A：当前是移动优先的单列布局。`.card{width:min(86vw,420px)}` 已经限制了最大宽度，桌面宽屏下不会拉伸变形。要做真正的双栏布局是较大改动，建议开分支做。

**Q：改了根目录的 `index.html`，APK 里没生效？**
A：APK 里没有页面副本，`syncWebAssets` / `android/tools/prepare-web-assets.sh` 会在构建时从**根目录**那份 `index.html` 派生。确认改的是根目录的文件，然后重新构建。

**Q：装 APK 时提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`？**
A：装的是不同签名的版本。先 `adb uninstall com.amarisgo.flashcards` —— 但卸载会连 localStorage 里的卡片数据一起删掉，**先在应用里导出 JSON 备份**。

**Q：网页版和 APK 版的数据能互通吗？**
A：不能。两者是各自独立的 localStorage（网页版按域名存，APK 版按 `https://flashcards.local/` 存）。要迁移只能走「导出 JSON → 导入 JSON」。

**Q：点了「清空全部数据」，示例卡片怎么又回来了？**
A：多半是只 `localStorage.removeItem(KEY)` 而没写回空数据。`load()` 只在 KEY **不存在**时才跑 `seed()`，
所以正确做法是 `emptyContent(); save();` —— 写回一个 cards/groups 都为空的 store。见 §3.5。

**Q：搜索面板里搜不到某个卡组的名字？**
A：`matchCards()` 只匹配卡片的正面与背面，不搜卡组名。要加的话在那一个函数里补一段即可。

---

## 12. 接手后的第一个建议

先花 10 分钟把 `index.html` 从头到尾读一遍 —— 只有 1353 行，而且是高度规整的「CSS 在前、JS 按功能分块在后」的结构，读完就完全掌握这个项目了。

读完重点看这几处：

1. `cardsOf()` —— 所有列表查询的入口
2. `renderLib()` / `renderStudy()` —— 两个主视图的渲染
3. `mergeStore()` —— 导入合并与 WebDAV 同步共用的核心
4. `onDown` / `onMove` / `onUp` —— 手势系统，改动时最容易破坏手感
5. `matchCards()` / `markHit()` / `jumpToCard()` —— 搜索面板三件套，是唯一会拼 HTML 的地方，注意 `esc()`
6. `davFetch()` / `closeTopOverlay()` —— 两处宿主适配点，改动时要保证纯网页环境行为不变

然后从 README 的「验证清单」走一遍，确认环境正常，就可以动手了。

如果你要动 APK 相关的东西，再花 10 分钟读一遍 `android/README.md` 和本文件 §6.2 —— 那里记了宿主架构、桥接协议和**尚未验证的部分**（真机、Gradle 路径、原生 WebDAV 桥都还没在真机上跑过）。
