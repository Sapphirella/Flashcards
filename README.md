# 闪过 Flashcards

一个纯前端的闪卡速记应用。单文件、零依赖、零构建 —— 双击 `index.html` 就能用。

数据全部存在浏览器本地（localStorage），可选通过 WebDAV 在多设备间同步。

<img src="docs/images/study.jpg" width="260" alt="背诵视图" />
<img src="docs/images/library.jpg" width="260" alt="卡片库" />
<img src="docs/images/library-batch.jpg" width="260" alt="批量模式" />

## 特性

**背诵**
- 点击卡片翻面看答案
- 左右滑动切卡，滑动跟手、带惯性
- 上滑把卡片加入星标（卡片飞出动效）
- 下拉打开星标抽屉，可「只刷星标」或一键清空
- 键盘：`空格`/`Enter` 翻面 · `←`/`→` 切卡 · `S` 星标 · `D` 抽屉
- 底部进度条显示当前进度与星标总数

**卡片库**
- 按卡组筛选卡片，卡组名横向滑动
- 新增 / 编辑 / 删除卡片，一卡可归属多个卡组
- 批量模式：多选后批量加入卡组、移出卡组、删除
- 导入 / 导出 JSON、导出 TXT（按卡组分节）

**设置**
- 主题：浅色 / 深色 / 跟随系统
- 字号：标准 / 大 / 特大
- WebDAV 同步：测试连接、一键同步（双向合并，按 `updatedAt` 决胜）
- 数据导入导出、清空全部数据
- 卡片与卡组数量统计

## 快速开始

### 直接打开

```bash
open index.html          # macOS
xdg-open index.html      # Linux
```

用浏览器打开 `index.html` 即可。首次打开会载入 13 张示例卡片（古诗文 / 单词 / 数理公式 / 文学常识），可在「设置 → 清空全部数据」中重置。

### 本地预览（推荐用于开发）

部分浏览器对 `file://` 的限制较多（WebDAV 请求、文件导入），建议起一个本地静态服务器：

```bash
python3 -m http.server 8777
# 打开 http://127.0.0.1:8777/index.html
```

### 装到手机 / 桌面

这是一个纯静态页面，把它放到任意静态托管（GitHub Pages、Vercel、Netlify，或自己的 Nginx）即可手机访问。也可以用「添加到主屏幕」当作 PWA 使用 —— 已配置 `viewport-fit=cover` 与 `env(safe-area-inset-*)` 安全区适配。

## 数据存放

| 位置 | 说明 |
| --- | --- |
| `localStorage["flashcards.v2"]` | 当前版本的全部数据（卡片、卡组、设置） |
| `localStorage["flashcards.v1"]` | 旧版数据，仅用于自动迁移，迁移后写回 v2 |
| `localStorage["flashcards.hint.v1"]` | 仅记录「首次显示操作提示」，不存业务数据 |

数据结构：

```jsonc
{
  "version": 2,
  "cards": [
    {
      "id": "a8c186c...",
      "front": "劝君更尽一杯酒，西出阳关无故人。",
      "back": "请你再干了这一杯吧……",
      "starred": true,
      "groups": ["g1", "g2"],      // 卡片所属卡组 id 数组，可为空
      "createdAt": 1730000000000,
      "updatedAt": 1730000000000
    }
  ],
  "groups": [
    { "id": "g1", "name": "古诗文", "updatedAt": 1730000000000 }
  ],
  "settings": {
    "theme": "light",             // light | dark | auto
    "fs": "std",                  // std | lg | xl
    "group": "all",               // 上次背诵的卡组
    "lastSync": 1730000000000,    // 上次 WebDAV 同步时间
    "webdav": { "url": "", "user": "", "pass": "", "dir": "flashcards" }
  }
}
```

⚠️ 数据只存在浏览器里。**清理浏览器数据、换浏览器、换设备都会丢失**，请用「导出 JSON」定期备份。

## WebDAV 同步

在「设置 → WebDAV」填入地址、账号、密码与目录，然后：

- **测试连接**：发 `OPTIONS`，失败则退回 `PROPFIND`
- **立即同步**：先 `GET` 远端文件与本地合并，再 `PUT` 回远端

合并规则是**按 `updatedAt` 时间戳逐条决胜**，卡片和卡组各自独立比较，因此多设备同时编辑不会互相覆盖整份数据（但同一条记录的同时修改仍会丢一方）。

同步文件路径为 `<地址>/<目录>/flashcards.json`。密码以明文存在 localStorage，请自行评估设备安全性。

## 项目结构

```
Flashcards/
├── index.html      # 全部代码：HTML 结构 + CSS + JS（约 1276 行）
├── README.md       # 本文件
├── HANDOVER.md     # 项目接手文档：架构、数据流、扩展指引、遗留问题
├── .gitignore      # 忽略 .DS_Store 等
└── docs/images/    # README 配图
```

整个应用就是 `index.html` 一个文件，内部按注释分块：

| 分块 | 行号 | 内容 |
| --- | --- | --- |
| storage | 459 | localStorage 读写、键名常量 |
| seed / migrate | 475 | 示例数据、v1 → v2 数据迁移 |
| data helpers | 522 | `cardsOf` / `groupById` 等查询helper |
| theme | 546 | 主题与字号应用 |
| study view | 561 | 背诵视图渲染、翻面、切卡、星标 |
| group menu | 653 | 卡组下拉菜单 |
| star drawer | 684 | 星标抽屉 |
| gestures | 705 | 指针手势：滑动切卡 / 上滑星标 / 下拉抽屉 |
| library | 810 | 卡片库列表与批量栏渲染 |
| modal | 858 | 通用弹窗框架 |
| card editor | 893 | 新增 / 编辑卡片 |
| group manager | 936 | 卡组增删改 |
| batch group ops | 989 | 批量加入 / 移出卡组 |
| import / export | 1014 | JSON / TXT 导入导出、数据合并 |
| webdav | 1083 | WebDAV 连接测试与同步 |
| views | 1120 | 视图切换 |
| events | 1137 | 事件绑定与键盘快捷键 |
| init | 1256 | 启动引导 |

## 开发约定

- **无构建、无依赖**：不引入 npm 包、不引入框架。要加能力就写原生 JS。
- **状态集中**：全局 `store`（数据）与 `ui`（界面状态）。任何写操作后都要 `save()` 落盘，再调 `renderLib()` / `renderStudy()` 重绘。
- **渲染方式**：整块 `innerHTML` 重绘 + 事件委托（`data-*` 属性 + `closest()` 匹配）。不要在渲染出来的 DOM 上挂监听器。
- **提交信息**：中文 Conventional Commits，如 `fix(library): 修复批量操作底栏溢出`、`feat(study): 增加卡片间隔提示`。
- **改完必须验证**：至少跑一遍「背诵 → 卡片库 → 批量模式 → 设置」四个视图。

## 对外接口

页面在 `window` 上暴露了两个对象，方便调试或被宿主 App（Android WebView）调用：

```js
window.__fc       // { store, ui, render, renderLib, show }
window.Flashcards // { view(): string, home(): void }
```

`window.Android` 若存在会被自动使用：`Android.setStatusBar()` 同步状态栏配色，`Android.saveFile()` 把导出文件直接写进系统「下载」目录。

## 已知问题

- 批量操作栏在极窄屏（< 340px）下按钮文字会被省略号截断，功能不受影响。
- WebDAV 密码明文存储于 localStorage。
- 无自动化测试，回归验证依赖手动走查。

更多细节见 [HANDOVER.md](HANDOVER.md)。
