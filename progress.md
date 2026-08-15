# 优化进度

## 2026-08-11

---

## 一、全量源码审计（已完成）

### 已审计文件（19 个核心源文件）
- Scrcpy.java, ScrcpyHost.java, VideoDecoder.java, VideoPacket.java, MediaPacket.java, ByteUtils.java, Constant.java
- SendCommands.java, DisplayWindow.java, FloatService.java, MainActivity.java, App.java
- AudioDecoder.java, AudioPacket.java, ExecUtil.java, ThreadUtils.java, ProcessHelper.java, Util.java, README.md

### 产出
- 优化分析报告，按 P0/P1/P2 分级共 14 个优化点（详见 `claude.md`）

---

## 二、Windows RDP 协议对比分析

### 2.1 RDP 协议规范与开源生态

RDP 协议规范由微软在 [openspecs](https://learn.microsoft.com/en-us/openspecs/windows_protocols/) 上公开，包括 [MS-RDPBCGR]（Basic Connectivity and Graphics Remoting）等几十篇文档。微软 2011 年起开放规范允许第三方实现。

| 开源项目 | 语言 | 许可 | 说明 | 建议 |
|----------|------|------|------|------|
| [FreeRDP](https://github.com/FreeRDP/FreeRDP) | C | **Apache-2.0** | RDP 事实标准实现，400+ 贡献者，23k+ commits，含客户端库和服务端库 | 🔴 优先 clone，Apache-2.0 许可最宽松，商用友好。读 `libfreerdp/core/*` 和 `libfreerdp/codec/*` 理解算法 |
| [xrdp](https://github.com/neutrinolabs/xrdp) | C | GPL-2.0 | 开源 RDP 服务端，让 Linux 桌面通过 RDP 接收连接 | 研究服务端如何配合客户端做脏区缓存 |
| [IronRDP](https://github.com/kanaka/IronRDP) | Rust | MIT | Rust 重写 RDP，架构现代 | 长远架构升级参考 |

**⚠️ 注意：** FreeRDP 是 C 语言 + PC 桌面环境（Win32/X11/Wayland），AndroidCtrl 是 Android + Java + MediaCodec，**语言和运行环境完全不同**，不能直接复用代码，只能借鉴算法思想和协议设计思路。

### 2.2 RDP 关键优化维度

| 维度 | RDP 能力 | 说明 |
|------|----------|------|
| **脏区更新** | ✅ | 只编码屏幕变化的矩形区域，而非全屏 |
| **图元压缩** | ✅ | 识别文字/矩形/线条用矢量指令重绘，非发送位图 |
| **持久位图缓存** | ✅ | 客户端缓存已接收图块，重复区域只发索引 |
| **自适应码率/色彩** | ✅ | 根据网络状态动态调整 bitrate、帧率、色彩深度 |
| **双通道传输** | TCP + UDP | 输入走低延迟不可靠信道，媒体走可靠信道 |
| **TLS 加密** | TLS 1.2/1.3 | 默认加密 |
| **输入预测** | ✅ | 根据本地移动速度预判位置，提前发送 |
| **剪贴板同步** | ✅ | 跨设备剪贴板共享 |
| **断线恢复** | ✅ | 断线重连恢复会话状态，不重启应用 |

### 2.3 RDP 优化对 AndroidCtrl 的落地评估

| RDP 优化 | 能否只改客户端 | 复杂度 | 收益 | 落地优先级 |
|----------|---------------|--------|------|-----------|
| 自适应码率 | ✅ 可 | 中 | 高 | **P0** |
| 控制流独立低延迟信道 | ✅ 可 | 低 | 高 | **P0** |
| 输入本地预测 | ✅ 可 | 中 | 高 | P1 |
| 输入批量合并 | ✅ 可 | 低 | 中 | P1 |
| 脏区更新 | ❌ 需服务端 | 高 | 极高 | P1（远期） |
| 持久位图缓存 | ❌ 需服务端 | 高 | 高 | P1（远期） |
| 剪贴板同步 | ❌ 需服务端 | 中 | 中 | P2 |
| TLS 加密 | ❌ 需服务端 | 中 | 安全必需 | P2 |

---

## 三、官方 scrcpy 4.1 服务端分析

### 3.1 Clone 信息

- 仓库：`https://github.com/Genymobile/scrcpy.git`
- 本地路径：`e:\WorkSpace\VSCode\AndroidCtrl\scrcpy-upstream\`
- 版本：4.1（最新）
- 许可：Apache-2.0

### 3.2 已审计文件（10 个核心文件）

| 文件 | 职责 |
|------|------|
| `Server.java` | 主入口，管理所有 AsyncProcessor 的生命周期 |
| `AsyncProcessor.java` | 统一接口：`start(stop)+join`，所有工作单元实现 |
| `Options.java` | 服务器参数解析（key=value），60+ 配置项 |
| `SurfaceEncoder.java` | 视频编码：MediaCodec 创建 + 配置 + 阻塞式编码 |
| `ScreenCapture.java` | 屏幕采集：VirtualDisplay/SurfaceControl |
| `Streamer.java` | 数据流传输：帧头序列化 + 写入 Fd |
| `DisplayMonitor.java` | 显示属性监听：Android 14+ 用 DisplayWindowListener 绕 bug |
| `DisplayResizeDebouncer.java` | 显示尺寸去抖：300ms 防抖 |
| `CaptureControl.java` | 编码重置控制：`signalEndOfInputStream` 优雅中断 |
| `Controller.java` | 控制命令处理：800+ 行，注入触控/按键/剪贴板/UHID |

### 3.3 与 AndroidCtrl 的关键差距

| 特性 | AndroidCtrl | scrcpy 4.1 | 能否直接移植 |
|------|-------------|------------|-------------|
| **线程架构** | 单线程混跑 + busy-poll | AsyncProcessor 多线程，视频/音频/控制各自独立线程 | ✅ 可直接移植架构 |
| **解码器等待** | `dequeue(0)` + `sleep(2)` 忙轮询 | `dequeue(-1)` **阻塞等待** | ✅ |
| **控制流线程** | 与媒体流共用 | 独立 `control-recv` 线程 + `INJECT_MODE_ASYNC` | ✅ |
| **编码器选择** | 仅 H.264 | H.264 + **H.265** 可选，可指定编码器名称 | ✅ |
| **编码器调优** | 无 | `KEY_LATENCY=1` / `KEY_PRIORITY=0` / `KEY_REPEAT_PREVIOUS_FRAME_AFTER=100ms` / `KEY_I_FRAME_INTERVAL=10s` | ✅ |
| **编码器约束检测** | 无 | `VideoConstraints` + `MediaCodecInfo`，失败自动降级分辨率 | ✅ |
| **显示变化监听** | 无 | `DisplayMonitor` + Android 版本适配 | ✅ |
| **resize 去抖** | 无 | `DisplayResizeDebouncer` 300ms 防抖 | ✅ |
| **优雅重置编码** | 硬杀线程 | `CaptureControl.signalEndOfInputStream()` | ✅ |
| **位置映射** | 简单缩放 | `PositionMapper` 支持 crop/rotate/angle/resize 逆变换 | ✅ |
| **多点触控** | 简单 pointerId | `PointersState` + `ACTION_POINTER_DOWN/UP` 自动处理 | ✅ |
| **鼠标/手指区分** | 无 | `TOOL_TYPE_MOUSE/FINGER` + `BUTTON_PRESS/RELEASE` 正确事件 | ✅ |
| **剪贴板同步** | ❌ | ✅ 双向自动同步 | ✅ |
| **U-HID 真鼠标** | 无 | Android 15+ 鼠标指针映射虚拟显示器 | 需 Android 15+ |
| **协议头大小** | 14 字节 | 12 字节（位打包 flags） | ✅ |
| **音频编码** | AAC 硬编码 48kHz/128kbps | **OPUS/FLAC/RAW** 三选一，可配置 | ✅ |
| **音频失败降级** | 无 | `writeDisableStream()` 发信号，视频继续播放 | ✅ |
| **版本校验** | 无 | client 版本必须与 server `BuildConfig.VERSION_NAME` 匹配 | ✅ |
| **keep-active 心跳** | 无 | 每 4s 发送心跳 | ✅ |

### 3.4 scrcpy 4.1 编码核心代码片段（SurfaceEncoder）

```java
// 阻塞式编码（关键）
private void encode(MediaCodec codec, Streamer streamer) throws IOException {
    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    boolean eos;
    do {
        int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1); // -1 = 阻塞
        try {
            if (outputBufferId >= 0 && bufferInfo.size > 0) {
                boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!isConfig) {
                    firstFrameSent = true;
                    consecutiveErrors = 0;
                }
                ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                streamer.writePacket(codecBuffer, bufferInfo);
            }
        } finally {
            if (outputBufferId >= 0) {
                codec.releaseOutputBuffer(outputBufferId, false);
            }
        }
    } while (!eos);
}

// MediaFormat 调优参数
format.setInteger(MediaFormat.KEY_PRIORITY, 0);          // 实时优先级
format.setInteger(MediaFormat.KEY_LATENCY, 1);            // 输入1帧即输出1帧
format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000); // 100ms重复帧
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);  // I帧间隔
format.setFloat("max-fps-to-encoder", maxFps);            // 最大FPS限制
```

### 3.5 对比结论

**scrcpy 官方版已超越 AndroidCtrl 的方面：** 线程架构、控制流解耦、编码器调优、多编码器选择、显示变化监听、resize 去抖、位置映射、多点触控、剪贴板、协议紧凑度、音频质量。

**scrcpy 也没做的（RDP 级优化）：** 自适应码率、脏区更新、TLS 加密、输入预测。

**落地策略：**
- **第一阶段（低垂果实）：** 从 scrcpy 4.1 移植 AsyncProcessor 架构、编码器调优参数、位置映射、多点触控——这些是现成的，改动明确
- **第二阶段（对标 RDP）：** 自适应码率、脏区更新、加密——scrcpy 也没做，需参考 FreeRDP 或自行设计

---

## 四、下一步

- [x] 全量源码审计
- [x] Windows RDP 协议对比分析
- [x] 推荐开源仓库调研（FreeRDP / xrdp / IronRDP / scrcpy）
- [x] 官方 scrcpy 4.1 服务端深度审计（10 个核心文件）
- [x] 更新本进度文件

### 待确认事项
- [ ] 用户希望先展开哪些优化点的设计方案？建议顺序：
  1. **P0-2（控制流拆分）**：参考 scrcpy 4.1 AsyncProcessor 架构，改动最小、收益最高
  2. **P0-4（dequeue 阻塞）**：直接照搬 scrcpy 编码参数
  3. **P0-1（busy-poll 改造）**：改阻塞式 read
- [ ] 是否需要保持与原始 scrcpy-server.jar 完全兼容（不修改协议），还是也优化服务端？
  - 若只改客户端：可先做 P0-2/P0-4/P0-1（协议不变）
  - 若可改服务端：可引入 scrcpy 4.1 协议（12 字节头）、多编码器、剪贴板等

---

## 2026-08-15

## 五、选择性回移 scrcpy-upstream → AndroidMirror（已完成）

### 5.1 策略决策（用户确认）
- **方案选择：Option 1 — 保持协议向后兼容，仅回移服务端内部改进 + 客户端 bug 修复。**
- 用户原话："按照 AndroidMirror 的设计逻辑，我们只需要把相关远程视频映射和操作里面有更新的有提升的部分 同步更新到 AndroidMirror 里面。"
- 计划文件：`C:\Users\Ethan\.claude\plans\calm-leaping-waterfall.md`（已批准）。
- **保持不变（7 层协议均不迁移）：** 14 字节头 `[4B size][1B type][1B flag][8B PTS]`、视频/音频复用单 TCP、固定端口 7007/7008、20 字节（5×int）控制消息 + 特殊键码 999/1000/1001/1002、marker-file 握手、8 字节分辨率前导（仅写一次）、5 个位置参数、后台模式、双显示模式、Android 7.0+ 兼容。

### 5.2 已实现项（7 项，全部完成并验证）

| 编号 | 内容 | 文件 | 状态 |
|------|------|------|------|
| **S1** | 编码器 MediaFormat 调优：新增 `KEY_PRIORITY=0`(API23+) / `KEY_COLOR_RANGE=LIMITED`(API24+) / `KEY_LATENCY=1`(API26+ 守卫)；`I_FRAME_INTERVAL=2` 保留（不改为 10，降低丢 P 帧后的花屏恢复时间）；`REPEAT_PREVIOUS_FRAME_AFTER=100ms` 已匹配上游 | `server/.../ScreenEncoder.java` createFormat | ✅ |
| **S2** | 编码失败降分辨率回退：`MAX_SIZE_FALLBACK={2560,1920,1600,1280,1024,800}` + `chooseMaxSizeFallback(Size)` + `prepareRetry(Device,Size)` + `MAX_CONSECUTIVE_ERRORS=3`；`Device.recomputeScreenInfo(int maxSize)` 同步刷新；`encode()` 内 `firstFrameSent`/`consecutiveErrors` 跟踪；仅首帧前降级（匹配上游注释）。8 字节前导写物理尺寸、只写一次，降级只缩小 videoSize（经 SPS/PPS CONFIG 传给客户端），协议安全 | `server/.../ScreenEncoder.java` streamScreen + `Device.java` | ✅ |
| **C4** | ByteUtils 零分配重写（app + server 两份）：`bytesToLong`/`longToBytes`/`intToBytes` 改位移；**符号性保留**：`new BigInteger(byte[])` 是有符号二进制补码 → 首字节不掩码（`(long)bytes[0]<<56` 符号扩展），其余字节掩码；server `bytesToInt` 首字节不掩码（匹配 BigInteger 有符号），app `bytesToInt` 首字节掩码（`& 0xFF` 无符号 — 既存有意差异，按计划保留） | `app/.../model/ByteUtils.java` + `server/.../model/ByteUtils.java` | ✅ |
| **C5** | 控制发送拆独立线程 + 阻塞式媒体读：`event` 改 `LinkedBlockingQueue<byte[]>`；新增 `ControlSender` 线程（唯一写 `controlOutputStream`，`take()`→write+flush，失败设 `controlDisconnected` 标志）；`loop()` 仅媒体；有状态保局部 size 读（4 字节头循环读，`SocketTimeoutException` 时 `sizeRead` 不损坏 → continue 保活）+ payload `readFully` 中途超时=死连接→重连；`mediaSocket.setSoTimeout(100)`（≈100ms 重复帧节拍，静态屏无误超时） | `app/.../Scrcpy.java` | ✅ |
| **C3** | 三个 `decodeSample` 调用点传真实 PTS（`videoPacket.presentationTimeStamp` / `audioPacket.presentationTimeStamp`，从 8B 头解析）而非 `0`，使 `queueInputBuffer`/Surface 渲染时间戳正确；循环级 PTS 丢帧逻辑不变 | `app/.../Scrcpy.java` loop | ✅ |
| **C1** | 解码器非忙轮询：`dequeueOutputBuffer(info, OUTPUT_BUFFER_TIMEOUT_US=10_000L)`（有限 10ms 超时）替代原 `dequeueOutputBuffer(info, 0)`+`Thread.sleep(2)` 忙轮询；删除 `Thread.sleep(2)`；保留未配置分支 `Thread.sleep(5)`。**详见下方 5.3 偏差说明。** | `app/.../decoder/VideoDecoder.java` + `AudioDecoder.java` | ✅ |
| **C2** | 丢最新 + 永不丢关键帧：VideoDecoder — 关键帧队列满时 `poll()` 腾位（绝不丢关键帧），P 帧队列满时丢弃新到（drop-newest，保依赖链连续避免花屏）；AudioDecoder — 无关键帧概念，队列满丢弃新到限延迟。`flags` 字段即协议字节（0/1/2/4），`flags == VideoPacket.Flag.KEY_FRAME.getFlag()` 即 `==1`；CONFIG(2)/END(4) 不到达 `decodeSample` | `app/.../decoder/VideoDecoder.java` + `AudioDecoder.java` | ✅ |

### 5.3 C1 偏差说明（已用户确认 2026-08-15）
- **计划原文：** `dequeueOutputBuffer(info, -1)`（无限阻塞输出排空）。
- **实际实现：** 有限超时 `OUTPUT_BUFFER_TIMEOUT_US = 10_000L`（10ms，上游 scrcpy 的 drain 值）。
- **偏差原因（已验证的正确性问题）：** `-1` 在静态屏会**卡死/冻屏** —— 飞行中的解码管线在两帧之间（重复帧 ~100ms 节拍）瞬时排空时，工作线程在 `dequeueOutputBuffer(-1)` 永久阻塞且无输入排队；下一帧经 `offer()` 入 sample 队列，但线程被阻塞永不轮询队列 → 首个静态帧后冻屏。这是相对当前忙轮询的**回归**。有限 10ms 超时在帧间让出 CPU，又足够频繁地唤醒以重新轮询队列喂下一帧 —— 不卡死、不忙转。
- **代码注释已完整记录此偏差。用户 2026-08-15 确认采用有限 10ms 超时（当前实现）。**

### 5.4 修改文件清单
- 服务端：`ScreenEncoder.java`(S1,S2)、`Device.java`(S2 recompute)、`model/ByteUtils.java`(C4)。
- 客户端：`Scrcpy.java`(C3,C5)、`decoder/VideoDecoder.java`(C1,C2)、`decoder/AudioDecoder.java`(C1,C2)、`model/ByteUtils.java`(C4)。

### 5.5 未回移项（已评估，按计划 DEFERRED）
AsyncProcessor 架构迁移（无运行时收益）、CaptureControl 优雅 EOS（≤100ms 旋转检测延迟，小）、DisplayMonitor+Debouncer（Android 14+ / 折叠屏 nich）、EventController 鼠标支持（注释中 TODO，行为变更需独立任务）、HEVC、AudioFocus、ADB 批量、ScrcpyHost ANR、P2 项 —— 均需协议/特性工作或低价值高扰动，本轮不迁移。

---

## 六、下一步
- [x] 选择性回移 7 项全部完成并经 grep/读取验证
- [ ] **构建验证（需构建环境 + 设备，用户驱动）：** 构建服务端 jar + app APK 确认无编译错误；推送新 jar 经 `SendCommands` 流程；验证视频(CONFIG/KEY_FRAME/FRAME/END 解析、旋转重配、后台→前台刷新、双显示)、控制(单/多点触控 + 特殊键 999/1000/1001/1002 + 延迟降低)、解码器 CPU(静态屏无 sleep(2) 忙转)、降级(S2)、ByteUtils(旧新相等含 bit63 set)。
- [x] **用户确认 C1 偏差**（5.3）：采用有限 10ms 超时（2026-08-15 确认）。