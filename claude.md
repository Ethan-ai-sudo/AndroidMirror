# AndroidCtrl 视频传输与远程控制优化

## 项目目标

基于深度代码审计结果，对 AndroidCtrl（DroidMirror）的**视频传输链路**和**远程控制链路**进行系统性优化，提升画面流畅度、降低触控延迟、减少 CPU/电量消耗，并修复已知 bug。

## 优化工作目录设计

本目录为优化工作产物，项目文档统一存放在 `AndroidCtrl/` 根目录：

| 目录/文件 | 用途 |
|-----------|------|
| `AndroidCtrl/claude.md` | 本文件，记录项目目的、目标、目录设计 |
| `AndroidCtrl/progress.md` | 当前优化进度与下一步工作 |
| `optimize/designs/` | 各优化点的详细设计方案文档 |
| `optimize/scripts/` | 辅助脚本（如批量化改造前的对比脚本） |
| `optimize/patches/` | 各优化点对应的代码补丁/替换文件 |
| `outputs/` | 最终产物汇总 |

## 核心优化目标（按优先级）

### P0 — 影响核心体验
| # | 问题 | 方案方向 |
|---|------|----------|
| 1 | Scrcpy.loop() busy-poll（`available()` + `sleep(5)`） | 改阻塞式 `read()` 或 `Selector` 复用 |
| 2 | 控制流与媒体流串行，触控发送阻塞媒体接收 | 拆独立控制发送线程，高优先级调度 |
| 3 | PTS 恒为 0 传入解码器，帧调度失控 | 真实 PTS 传入 `queueInputBuffer` + jitter buffer |
| 4 | VideoDecoder worker 非阻塞轮询（`dequeue(0)` + `sleep(2)`） | 改 `dequeue(-1)` 阻塞等待 |
| 5 | 丢帧策略丢最老帧而非最新帧，可能丢 KEY_FRAME | 队列满时丢最新帧，KEY_FRAME 永不丢弃 |

### P1 — 稳定性和兼容性
| # | 问题 | 方案方向 |
|---|------|----------|
| 6 | 仅支持 H.264，无 HEVC 回退 | 编码格式协商，优先 HEVC |
| 7 | ByteUtils 热路径 `BigInteger` + `ByteBuffer.allocate` 分配 | 零分配重写（位移 / `ByteBuffer.wrap`） |
| 8 | ADB 命令逐条 fork，`waitFor()` 无超时 | 长连接批量执行 + 超时保护 |
| 9 | 音频无 AudioFocus，采样率硬编码 | 请求 AudioFocus + 服务端采样率协商 |
| 10 | ScrcpyHost socket 轮询阻塞 Binder 线程 → ANR | 移至独立线程 + 事件通知 |

### P2 — 代码质量
| # | 问题 | 方案方向 |
|---|------|----------|
| 11 | AudioPacket 类名/职责混乱 | 重构命名与职责分离 |
| 12 | Util.getServerHostAndPort() IPv6 解析 bug | 修复冒号分割逻辑 |
| 13 | 全屏/浮窗双客户端代码重复 | 抽象统一客户端接口 |
| 14 | 全链路明文传输 | 评估 TLS/DTLS 加密方案 |

## 技术背景

- 上游：[scrcpy](https://github.com/Genymobile/scrcpy)、[ScrcpyForAndroid](https://github.com/zwc456baby/ScrcpyForAndroid)
- 协议：14字节头 `[4B size][1B type][1B flag][8B PTS μs][payload]`，视频/音频同 TCP 连接复用
- 传输：ADB TCP forward（localhost:7007→remote:7007 媒体，7008 控制）
- 解码：MediaCodec 硬解（H.264），Surface 零拷贝渲染

## 约束

- 不改动的核心：不改变与 scrcpy-server.jar 的协议格式（需保持向后兼容）
- 优化需保持双显示模式（全屏/浮窗）均可用
- 保持 Android 7.0+ 兼容性