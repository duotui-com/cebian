# 多进程状态归属与跨进程通道清单

> **状态：已于 1.25.x 之后回退为单进程（交互面）。**
> 无障碍服务、触钮/悬浮球/边角轮盘、所有浮层窗口（含小组件宿主）、剪贴板监听、录屏/截屏、
> 模块桥、媒体监听已全部合并回主进程；只保留 `:engine`（OCR/翻译）做内存隔离。
> 因此本文中所有"跨进程通道/镜像/租约/静默丢弃"的条目都已作废（或退化为同进程直连），
> 保留作历史记录；新增设置项不再需要走 Intent 通道。

> 状态：现行约定（随代码维护）。与 `multiprocess_refactor_plan.md`（设计文档）不同，
> 本文只记录**当前**哪些状态属于哪个进程、走哪条通道、通道缺失时怎么办。
>
> 背景：设置界面（主进程）与浮层宿主（`:overlay`）分进程后，所有"进程内静态直连"的调用
> 都会命中 `null` 并**静默丢弃**。这类 bug 的表现是"功能莫名坏了"，而不是崩溃，所以必须靠
> 约定 + 机械校验来防，而不是靠记性。

## 1. 进程与存活理由

| 进程 | 为什么必须单独活着 | 备注 |
| --- | --- | --- |
| `main` (`com.slideindex.app`) | 承载设置/编辑器 UI，随用户交互生灭 | 可被系统回收 |
| `:overlay` | 常驻交互：无障碍服务、边缘触钮、悬浮球、边角轮盘、各类浮层窗口 | **被杀即等于手势全停** |
| `:engine` | OCR/翻译等重内存识别，避免 OOM 连带拖死交互 | 大内存分配隔离 |
| `:clipboard` | 剪贴板后台监听前台服务 | **待算账**：见 §5；实测无崩溃记录，建议合并回 `:overlay` |

判断标准：**每个进程的存活理由必须独立于"另一个进程的存在"**。互拉/守护是耦合证据，不是理由。

## 2. 跨进程交互通道

| 场景 | 通道 | 失败语义 |
| --- | --- | --- |
| 设置页 → 浮层预览（滑条拖动期） | `Intent` → `OverlayService`（`ACTION_PREVIEW_*` + extras） | 发送端 `runCatching` + 日志；预览不可用时给用户一次 Toast（10s 节流） |
| 设置页 → 面板/聚焦预览 | `Intent` → `OverlayService`（`ACTION_PREVIEW_START/STOP`） | 同上（沿用既有入口） |
| 模块(system_server) → 应用触摸转发 | `IModuleGestureBridge`（AIDL） | 连接断开时模块侧收流 |
| 应用 → 模块状态通知 | 广播（`notifyModuleHostState`） | 无回执，模块按超时自判 |
| 引擎 OCR/翻译 | `IEngineOcr`（AIDL） | 超时 + 回退 |
| 浮层状态回读（主进程读 overlay 状态） | `OverlayStatePort` 镜像 | 过期即视为未知，不得当作"空状态"使用 |
| `:engine` 下载进度 → 设置页 | `DownloadProgressChannel`（快照文件 + 广播） | 发送端 `runCatching` + 日志；快照超过 5 分钟视为死任务并清掉 |

**硬规则**：新增任何"设置页要影响浮层"的能力，只能走 §2 第一条通道；
`ui/` 包下**禁止**出现 `SlideIndexAccessibilityService.<浮层/预览 API>` 的直接调用。
该规则由 `OverlayProcessBoundaryTest` 扫描源码强制（违反即测试失败）。

## 3. 状态归属清单

| 状态 | 归属进程 | 写入者 | 读取者 | 缺通道时的表现 | 现状 |
| --- | --- | --- | --- | --- | --- |
| 触钮布局/宽度/滑动阈值预览 | `:overlay` | 设置页 → Intent | `OverlayLayoutPreviewStore` → 触钮绘制 | 拖动无预览 | ✅ 已接通道 |
| 索引高度预览 | `:overlay` | 同上 | 同上 | 拖动无预览 | ✅ 已接通道 |
| 手势角度预览 | `:overlay` | 同上 | `GestureAnglesPreviewStore` | 拖动无预览 | ✅ 已接通道 |
| 边角轮盘触发区预览 | `:overlay` | 同上 | `CornerGestureController` | 拖动无预览 | ✅ 已接通道 |
| 悬浮指针行程范围预览 | `:overlay` | 同上（含灵敏度实时值） | `FloatingPointerAreaPreviewOverlay` | 窗口不出现 | ✅ 窗口改由 `:overlay` 创建 |
| 悬浮球外观/位置/边条预览 | `:overlay` | 同上 | `FloatBallOverlay` | 拖动无预览 | ✅ 已接通道 |
| 前台包名（抑制判定用） | `:overlay` | 无障碍服务前台追踪 | `OverlaySuppression` | 误判"该抑制"→ 轮盘/触钮被拆 | ✅ 已自愈（见 §4） |
| 按应用禁用列表 | 设置（DataStore） | 设置页 | 设置页 / `:overlay` 只读 | — | ✅ 已核查：`settings.excludedAppScopes` 本就是设置归属，无进程边界问题（设计文档里的"读到空状态"是过时描述） |
| 截屏/取词会话状态 | `:overlay` | `ScreenCaptureService` | 取词/截屏流程 | — | ✅ 已核查：服务已在 `:overlay`（manifest `android:process=":overlay"`），设计文档写的"留在主进程"已过时 |
| 剪贴板监听状态 | `:clipboard` → 主进程 | 监听前台服务心跳 | 设置页镜像（`ClipboardMonitorStatusPort`） | 镜像过期 | ✅ 已是带心跳的镜像；只需保证过期显示为"未知" |
| 小组件实例与预览位图 | `:overlay` | `SlideIndexAppWidgetHost` | 主进程编辑器占位卡 | 编辑器永远停在"加载中…" | ⚠️ 唯一确认未修的一条，见 §7 |
| OCR 模型 / 引擎包下载进度 | `:engine` | `OcrModelDownloadService` / `NativeEnginePackDownloadService`（前台服务） | 设置页对应的两个 ViewModel | 页面**永远**看不到进度，只有通知有（进程内单例在主进程恒为空） | ✅ 已接通道：`DownloadProgressChannel`；进度的"进行中"快照带时间戳，过期即丢弃 |

## 4. 失联与自愈

- **窗口不允许活得比服务久**：无障碍服务每 2s 续租，`OverlayHostLease` 在租约超时
  或同进程服务实例消失时，强制拆掉所有浮层窗口（避免"服务已死、全屏直触窗还在吞触摸"，
  那是"整台手机点不动"的根因）。
- **拆卸必须可重建**：`EdgeOverlayHost` 的窗口宿主（触钮/悬浮球/边角轮盘）被拆后，
  下一次设置回流或预览入口会经 `ensureStarted()` 自动重建。只有显式 `stop()` 才不再重建。
  ⚠️ 之前是"单向拆卸"——拆掉后没有任何路径重挂，只能靠用户手动开关或重启。
- **交互硬边界**：熄屏时无条件复位侧边会话与全屏直触；会话/取词超过 10s 收不到输入，
  按"丢了 UP/CANCEL"处理，强制收尾。

## 5. `:clipboard` 进程待算账（仅分析，未改动）

保留理由写在设计文档里，带"常驻守护互拉"色彩 → 属于**保活**理由，不是功能理由。

实测（Meizu 21，2026-09-27）：

- `:clipboard` PSS **58 MB**（RSS 145 MB，其中 **45 MB 被换出**），另有一个独立前台服务通知；
- `adb shell dumpsys activity exit-info com.slideindex.app:clipboard` **没有任何崩溃/OOM 记录**
  → "崩了不连累手势"这条唯一正当理由目前没有数据支撑；
- 互拉保活（`OverlayGuard` + `OverlayWatchdogJobService`）服务的是 `:overlay`，与剪贴板进程本身无关。

结论：**建议合并回 `:overlay`**（`:clipboard` 只是 `ClipboardMonitorForegroundService` 一个前台服务）。
但这不是改一行 manifest 的事——下列位置显式依赖"监听服务独占进程"的判断，必须一起改并按顺序验证：

1. `core/common/src/main/java/com/slideindex/app/util/AppProcess.kt`（`CLIPBOARD_MONITOR_PROCESS_SUFFIX` / 是否监听进程的判断）；
2. `clipboard/monitor/ClipboardMonitorProcess.kt`（进程归属说明与判断）；
3. `clipboard/monitor/ClipboardMonitorController.kt`（"别和前台服务进程撞名"、从非监听进程拉起监听服务的分支）；
4. `clipboard/ClipboardHistoryRepository.kt`（按设置启停监听前台服务）；
5. `ui/settings/clipboard/ClipboardMonitoringUiState.kt` 与 `ClipboardMonitorStatusPort.kt`（跨进程镜像注释与新鲜度语义）；
6. `AndroidManifest.xml`（`ClipboardMonitorForegroundService` 的 `android:process`）。

改动本身是**回退**"剪贴板拆独立进程"那次决定，所以必须带设备验证（复制文本 → 历史是否落库 →
通知是否只用一个），不适合在长会话末尾顺手做。

## 6. 小组件预览位图（唯一确认的遗留）

小组件实例与渲染都在 `:overlay`（`SlideIndexAppWidgetHost` / `WidgetPopupHost`），主进程编辑器拿不到
`AppWidgetHostView`，因此 `WidgetCardContainer` 的占位卡永远停在「加载中…」（`widget_loading`）。

**不建议为它建位图通道**（尺寸、失效时机、回收都要跨进程管）。建议改为交互：
编辑器里点卡片 = 在 `:overlay` 的弹出面板中打开真实小组件预览。
落地前需要先看 `WidgetCardContainer` / `WidgetCanvasLayout` 的点击与编辑态流转，避免和现有的
拖拽/缩放/配置按钮手势打架。

## 7. 新增设置项/预览的标准路径

1. 状态只允许有一个归属进程（默认 `:overlay`）；
2. 设置页只写"用户意图"，通过 `OverlayServiceController.sendPreviewExtras(...)` 发指令；
3. 不允许写 `instance?.`、`?: return`、`runCatching{}.getOrNull()` 这类静默吞掉用户意图的代码；
   所有跨进程失败必须留日志，并给出可见降级（提示/重试）；
4. 跑 `OverlayProcessBoundaryTest`，让边界由 CI 而不是靠记忆守住。
