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
| 不用框架 | 这个体量（~1276 行）用框架反而是负担；原生 DOM 操作在这个规模下完全够用 |
| localStorage 存数据 | 零后端成本，且离线可用；用 WebDAV 解决多设备同步 |
| 无构建 | 想改就改，保存刷新即生效，没有 `npm install` 门槛 |

**接手时请不要轻易引入构建工具或框架** —— 那会破坏这个项目最重要的优点（打开就能用）。除非需求变了。

---

## 2. 目录结构

```
Flashcards/
├── index.html          # 全部代码（HTML + CSS + JS，1276 行）
├── README.md           # 项目说明：功能、数据格式、快速开始
├── HANDOVER.md         # 本文件
├── .gitignore
└── docs/images/        # README 配图
```

没有 `package.json`、没有 `src/`、没有构建产物。**所有改动都发生在 `index.html` 里。**

---

## 3. 架构

### 3.1 全局结构

```
<body>
  └─ #app                       height:100dvh，flex column，overflow:hidden
       ├─ section#vStudy       背诵视图
       ├─ section#vLibrary     卡片库视图
       └─ section#vSettings    设置视图
  ├─ .scrim                    全局遮罩（弹窗/抽屉/菜单共用）
  ├─ .menu#deckMenu            卡组下拉菜单
  ├─ aside#drawer              星标抽屉
  ├─ .modal#modal              通用弹窗
  ├─ .toast                    轻提示
  └─ .hint                     首次使用提示
```

三个视图是**同级 section**，通过 `.view{display:none}` / `.view.is-active{display:flex}` 切换。同一时刻只有一个可见，所以不需要路由。

### 3.2 状态

只有两个全局状态对象（`index.html:463` 起）：

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
| `renderStudy()` | `index.html:564` | 背诵视图：卡片正反面、进度条、底部计数 |
| `renderLib()` | `index.html:811` | 卡片库：卡组 chips、卡片列表、批量栏 |
| `renderDrawer()` | `index.html:685` | 星标抽屉列表 |

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

- `load()`（`index.html:507`）：依次尝试读 v2 → 读 v1 并迁移 → 都没有就 `seed()` 写示例数据。
- `save(data)`（`index.html:518`）：`localStorage.setItem(KEY, JSON.stringify(data || store))`，**包了 try/catch**，写入失败（隐私模式、配额满）时静默忽略。
- `migrateV1()`（`index.html:498`）：把 v1 的 `decks[]` 结构转成 v2 的 `groups[] + cards[].groups[]`。

**破坏性变更时不要删旧 key 的兼容读取** —— 用户升级后老数据还在 localStorage 里，读不到就等于清空。

---

## 4. 模块地图（带行号）

> 行号对应当前 `index.html`（1276 行）。改动后行号会漂移，用注释标记 `/* ================= xxx ================= */` 搜索更可靠。

| 分块 | 行 | 要点 |
| --- | --- | --- |
| CSS 变量 / 主题 | 12–38 | `:root` 定义配色，`[data-theme="dark"]` 覆盖，`[data-fs="lg\|xl"]` 覆盖字号 |
| 布局骨架 | 53–55 | `#app` / `.view` / `.scroll`（`.scroll` 已于本次修复移除） |
| 背诵视图样式 | 75–135 | 卡片 3D 翻面、滑动、飞入飞出关键帧 |
| 浮层样式 | 137–201 | 遮罩、菜单、抽屉、弹窗、按钮 |
| 卡片库样式 | 203–246 | **本次修复重点**，见 §5 |
| 设置样式 | 247–268 | 分组卡片、开关行 |
| storage | 459 | key 常量、`uid()`、`esc()`、`toast()`、`fmt()` |
| seed / migrate | 475 | 13 张示例卡片、4 个示例卡组、v1 迁移 |
| data helpers | 522 | `cardsOf(gid)` 是**最核心的查询函数** |
| theme | 546 | `applyTheme()` 应用主题 + 同步状态栏 |
| study view | 561 | `renderStudy` / `flip` / `goCard` / `toggleStar` / `starCurrent` |
| group menu | 653 | 卡组下拉菜单（含「新建卡组」「管理卡组」） |
| star drawer | 684 | 星标抽屉 |
| gestures | 705 | `onDown` / `onMove` / `onUp`，阈值常量 `TH` |
| library | 810 | `renderLib()` 渲染列表与批量栏 |
| modal | 858 | `openModal(title, bodyHTML, buttons, onClose)` 通用弹窗 |
| card editor | 893 | `editCard()` 新增/编辑，`groupPicker()` 卡组选择器 |
| group manager | 936 | `manageGroups` / `newGroup` / `renameGroup` / `delGroup` |
| batch ops | 989 | `batchGroups(mode)` 批量加入/移出卡组 |
| import/export | 1014 | `exportJson` / `exportTxt` / `mergeStore` / `importJson` |
| webdav | 1083 | `davTest` / `davSync` |
| views | 1120 | `show(view)` 切换视图 |
| events | 1137 | 所有事件绑定、键盘快捷键 |
| init | 1256 | 启动：load → applyTheme → render → 首次提示 |

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

**怎么复现**：卡片库 → 点右上角「批量管理」→ 看底栏。建议同时验证「13 张卡片（长列表）」和「3 张卡片（短列表）」两种情况 —— 修复前这两种的表现完全不同，只测一种会漏。

> 通用教训：**在 flex 列布局里，`overflow` 不是 `visible` 的元素会自动最小尺寸归零**，只要它同时还是可收缩的 flex item，就存在被压扁的风险。给固定头尾一律加 `flex:none`。

### 5.3 安全区适配

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

### 6.2 Android WebView 宿主

代码会探测 `window.Android`：

| 方法 | 用途 | 降级行为 |
| --- | --- | --- |
| `Android.setStatusBar(color, lightIcons)` | 同步状态栏配色 | 无宿主时跳过 |
| `Android.saveFile(name, content, type)` | 导出文件直接写进「下载」目录 | 回退到 `Blob` + `<a download>` |

### 6.3 暴露给宿主的接口

```js
window.__fc = { store, ui, render, renderLib, show };   // 调试用
window.Flashcards = { view: () => ui.view, home: () => show('study') };
```

宿主 App 可用 `Flashcards.home()` 跳回首页、`Flashcards.view()` 查询当前视图。

---

## 7. 怎么加新功能（照着做就行）

### 7.1 加一个按钮并做某件事

1. 在对应视图的 HTML 里加按钮，给它一个唯一的 `id`。
2. 需要动态内容的话，加 `data-*` 属性而不是 `id`。
3. 在 `events` 分块里用 `el('yourId').onclick = ...` 绑定；或用事件委托处理动态列表。
4. 涉及数据的：**改 `store` → `touchCard`/`touchGroup` → `save()` → `render*()`**。

### 7.2 加一个新视图

1. 加 `<section class="view" id="vYourView">…</section>`，放在 `#app` 内。
2. 在 `show(view)`（`index.html:1121`）里加一行 `el('vYourView').classList.toggle('is-active', view==='yourview')`。
3. 加 `ui.yourview = ...` 状态。
4. 记得给 `#app` 里的兄弟 section 留出空间（它们 `display:none`，不占位）。

### 7.3 加一条数据字段

1. `seed()`（`index.html:476`）里给示例卡片补上新字段。
2. `editCard()`（`index.html:905`）里加输入项，保存时写进对象。
3. `mergeStore()`（`index.html:1041`）里补上新字段的合并逻辑 —— **漏了这步，WebDAV 同步会把新字段抹掉**。
4. 如果字段会破坏兼容，考虑在 `load()` 里做默认值兜底。

### 7.4 加一种卡片操作

批量操作在 `batchBar` 的 `data-b` 分发（`index.html:1219`）和 `batchGroups()`（`index.html:990`）两处。新的批量动作需要在这两处都加分支。

---

## 8. 已知问题与技术债

| # | 问题 | 影响 | 现状 |
| --- | --- | --- | --- |
| 1 | 无自动化测试 | 改动靠手动回归 | 未解决，建议至少为核心纯函数（`cardsOf` / `mergeStore`）补单测 |
| 2 | WebDAV 密码明文存 localStorage | 设备失守即泄露 | 有意简化，同步前请告知用户 |
| 3 | 批量栏在 < 340px 极窄屏下文字截断 | 显示为「加入卡…」 | 已用 `text-overflow:ellipsis` 兜底，功能正常 |
| 4 | 同一张卡并发修改会丢一方 | WebDAV 同步边缘情况 | last-write-wins 的固有代价 |
| 5 | 卡片库列表滚动位置在视图切换后保留 | 可能不符合预期 | 与原实现一致，暂未改 |
| 6 | `index.html` 单文件已达 1276 行 | 再加功能会继续膨胀 | 目前可接受；若超过 ~2000 行，建议至少拆出 CSS 与 JS（仍保持零构建，手写 `<link>`/`<script src>`） |
| 7 | 示例数据在首次打开时自动写入 | 打开即有 13 张卡片 | 设计如此，可在「设置 → 清空」重置 |

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
scope: library | study | settings | webdav | storage | gestures
```

例子：

```
fix(library): 修复批量操作底栏溢出屏幕外的问题
feat(study): 增加卡片翻面动画时长设置
docs: 补充 WebDAV 合并策略说明
chore: 移除已废弃的 .scroll 工具类
```

**建议流程**：一个 bug 一个提交，提交信息里写清「现象 → 根因 → 修复」，方便后来者追溯。改大功能前先开一个分支。

---

## 10. 验证清单（改完必走）

```bash
python3 -m http.server 8777   # 在项目根目录
# 打开 http://127.0.0.1:8777/index.html
```

逐项确认：

- [ ] **背诵视图**：翻面、左右滑动切卡、上滑星标、下拉抽屉、键盘快捷键（空格 / ← → / S / D）
- [ ] **卡片库**：卡组筛选、卡片增删改、批量模式（长列表 + 短列表各看一次底栏位置）
- [ ] **设置**：主题切换、字号切换、WebDAV 测试连接与同步、导出 JSON / TXT、导入 JSON（覆盖与合并两种）
- [ ] **视口**：375×667（iPhone SE）、390×844（iPhone 14）、768×1024（平板）、桌面宽屏
- [ ] **控制台**：无报错
- [ ] **持久化**：刷新页面后卡片、星标、主题都还在

> 视口切换建议用浏览器的响应式模式，或直接改窗口宽度；`100dvh` 在移动端浏览器地址栏收起/展开时会变，务必在真机或模拟器上再看一眼。

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

---

## 12. 接手后的第一个建议

先花 10 分钟把 `index.html` 从头到尾读一遍 —— 只有 1276 行，而且是高度规整的「CSS 在前、JS 按功能分块在后」的结构，读完就完全掌握这个项目了。

读完重点看这几处：

1. `cardsOf()` —— 所有列表查询的入口
2. `renderLib()` / `renderStudy()` —— 两个主视图的渲染
3. `mergeStore()` —— 导入合并与 WebDAV 同步共用的核心
4. `onDown` / `onMove` / `onUp` —— 手势系统，改动时最容易破坏手感

然后从 README 的「验证清单」走一遍，确认环境正常，就可以动手了。
