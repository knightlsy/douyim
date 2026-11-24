# 抖仙人（douyim）完整实现笔记

`1zzzzzlll/douyim` v1.7.7 — LSPosed Modern API 102 模块，抖音纯净播放 + 内容过滤 + 无水印下载。
17 个 Java 文件 / 7904 行。fork 在 `knightlsy/douyim`，本地 `/opt/data/work/douyim`。

**技术路线**：Hook 抖音进程读内存对象（`Aweme`）。我们是独立 App 读分享页 web 接口。
能搬的集中在「与数据来源无关的本地校验」，不能搬的是「依赖 App 私有字段的选择逻辑」。

---

## 文件总览

| 文件 | 行数 | 职责 |
|---|---|---|
| `ModuleApplication` | 60 | Application 入口，监听 XposedService 绑定/断开 |
| `FilterPreferences` | 174 | 配置常量 + 关键词解析 |
| `MainActivity` | 526 | 设置页（纯代码构建 UI，无 XML） |
| `hook/DouyinModule` | 197 | 模块入口，装 10 个子系统 |
| `hook/ImmersiveUi` | 2031 | 沉浸界面：隐藏/恢复、双击开评论、弹幕保留 |
| `hook/FeedContentTracker` | 1298 | 内容识别：广告/直播/图文/文章判定 + 快照 |
| `hook/QualityHooks` | 1245 | 清晰度三层钉死 |
| `hook/VideoDownloader` | 975 | 下载：验轨、格式校验、重试 |
| `hook/PlaybackState` | 631 | 播放状态机：播放/暂停/完成/错误/循环边界 |
| `hook/PlayerHooks` | 208 | 播放器方法 + listener 的 hook 安装 |
| `hook/ImmersiveTouchGuard` | 141 | 触摸拦截 + 长按开关的保存/恢复 |
| `hook/AudioTranscoder` | 113 | MediaCodec 解码音轨 → PCM |
| `hook/Mp3Encoder` | 113 | jump3r(Java LAME) 编码 MP3 |
| `hook/DoubleTapGuard` | 104 | 双击拦截（抖音容器 + 系统 GestureDetector） |
| `hook/DanmakuViewClassifier` | 52 | 弹幕多渲染层识别 |
| `hook/ImmersiveTouchPolicy` | 22 | 纯函数：该不该拦、是不是侧边长按 |
| `hook/ImmersiveTransitionPolicy` | 14 | 纯函数：切换期间是否保持隐藏 |

---

## 一、模块架构

### DouyinModule —— 每个子系统独立容错

```java
onPackageReady(param) {
    if (!"com.ss.android.ugc.aweme".equals(pkg) || !param.isFirstPackage()) return;
    if (!INSTALLED_LOADERS.add(param.getClassLoader())) return;   // WeakHashMap 去重

    installSubsystem("activity lifecycle",    this::hookActivityLifecycle);
    installSubsystem("feed double tap guard", () -> DoubleTapGuard.installFeedInterceptor(...));
    installSubsystem("player tracker",        () -> PlayerHooks.install(...));
    installSubsystem("video quality guard",   () -> QualityHooks.install(...));
    // ...共 10 个
}

private void installSubsystem(String name, HookInstaller installer) {
    try { installer.install(); log(INFO, "hook subsystem installed: " + name); }
    catch (Throwable error) { log(ERROR, "hook subsystem failed: " + name, error); }
}
```

**设计意图**：抖音每次升级总有类名变。一个子系统挂了不影响其余九个，模块能带着半个功能活下去。

所有 `hook()` 都设 `.setExceptionMode(ExceptionMode.PROTECTIVE)` —— 异常不逃逸到宿主。

### ModuleApplication —— 服务绑定

```java
XposedServiceHelper.registerListener(this);
onServiceBind(service)  → 保存 + notifyListeners(service)
onServiceDied(dead)     → 置 null + notifyListeners(null)
```
`CopyOnWriteArraySet` 存监听者；`addServiceStateListener(listener, notifyImmediately)` 支持立即回放当前状态。

---

## 二、内容识别（FeedContentTracker，1298 行）

### snapshot() —— 从 `Aweme` 对象提快照

**广告判定（最有价值的教训）**

```java
boolean ad       = readField("isAd");
Object  rawAd    = readField("awemeRawAd") ?? invokeNoArg("getAwemeRawAd");
int     adSource = readField("adAwemeSource");
Object  gdAd     = readField("gdAd");
Object  asyncAdInfo = readField("asyncAdInfo");
Object  linkAdData  = readField("linkAdData");

// 只有广告独占标记才能决定跳过
boolean advertisement = ad || rawAd != null || gdAd != null;
```

源码注释（L316-318）：

> `adAwemeSource` / `asyncAdInfo` / `linkAdData` 在普通视频上也会被填（40.8 给每个 feed item
> 都填 `asyncAdInfo`），拿它们判断会**跳过整个信息流**。这三个只写进诊断日志。

**原则**：宁可漏判广告，也不能误杀正常内容。

**内容类型**

```java
boolean live  = invokeNoArg("isLive") || awemeType == 101;
boolean photo = isImage || isMultiImage || isSlides
             || awemeType == 2 || awemeType == 0x44
             || imageCount > 0 || imageInfoCount > 0;
boolean photoContent = photo || awemeType == 0xA3;    // 0xA3 = 长文章
```

跳过原因写得具体：`"advertisement model"` / `"live model"` / `"long article model"` /
`"photo article model"` / `"article model"` / `"non-video model"`。

### 字段读取的双路径

```java
Object video = readField(type, aweme, "video");
if (video == null) video = readSerializedField(aweme, "video");

Object article = readField("articleInfo");
if (article == null) article = readSerializedField("article_info");   // 下划线命名
```
先按 Java 字段名反射，失败再按序列化名（`image_infos`、`item_title`）。抗混淆 / 抗版本变化。
标题、描述都用 `firstNonBlank(...)` 串 3-4 个来源兜底。

---

## 三、下载（VideoDownloader，975 行）★

### downloadVideo() 主流程

```
for (每个候选地址) {
    非 2xx                    → 换下一个
    ensureProgressiveSource() → 拒绝 .m3u8 / mpegurl / dash+xml
    contentType 是 text/json  → "返回的不是视频数据"
    下载到临时文件（不直接写相册）
    大小校验：>0 且与 Content-Length 一致 → 否则"视频数据不完整"
    inspectVideo(temp)        → MediaExtractor 验轨（见下）
    通过 → publishFile() 移到 Download
    finally 删临时文件
}
全失败 → freshPlayUrls() 重新读作品拿新地址，再试一轮
```

### inspectVideo() —— 三道验轨

```
没 video/* 轨    → "下载的文件没有画面（拿到的是音轨地址）"
没 audio/* 轨    → "下载的文件没有声音（DASH 单轨地址）"
!canDecode(...)  → "设备无法解码该视频编码：" + mime
```

**为什么需要**：抖音 `bit_rate[]` 里混着 DASH 音轨条目 —— 宽高都是 0，但 `gear_name`
写着 `normal_1080_0`。按 `gear_name` 解析会把音轨当最高档，下回来只有声音。

### canDecode() —— 保守判断

```java
video/avc, video/mp4v  → true    (H.264，系统一定有)
含 bytevc1             → false   (抖音私有编码，相册解不了)
其他                   → MediaCodecList.findDecoderForFormat()
查询抛异常             → true    ← 保守：别把正常视频误判成坏的
```

### 降级要说清楚

```java
DownloadResult.message("已保存 X（抖音私有编码，系统播放器可能只有声音）");
```
成功也提示代价，不假装一切正常。

### 图片处理

**needsJpeg(mime)**：`avif / heic / heif / webp` → 转 JPEG；`png / gif` 保留（透明 / 动画）

**sniffImageMime(file)** —— 看文件头，不信 Content-Type

```
FF D8                         → image/jpeg
89 50 4E 47                   → image/png
52 49 46 46 .... 57 45 42 50  → image/webp   (RIFF....WEBP)
47 49 46 38                   → image/gif
42 4D                         → image/bmp
....ftyp + avif/avis          → image/avif
....ftyp + heic/heix/hevc/hevx/heim/heis/mif1/msf1 → image/heic
```

**isGzip + gunzip**：读头两字节 `1F 8B`，是就解压到临时 `.raw`。CDN 可能返回 gzip。

**decodeBitmap OOM 兜底**：
```java
按 24_000_000 像素算 inSampleSize（上限 8）
decodeFile 抛 OutOfMemoryError → sampleSize 翻倍重试
到 8 倍还 OOM → "图片过大，无法转码"
```

---

## 四、播放状态（PlaybackState，631 行）

### 常量

```java
TRANSITION_GRACE_MS              = 2_000    // 切换过程中保持隐藏
AUTO_SWITCH_GRACE_MS             = 2_500    // 自动切换后的冷静期
ERROR_CONFIRM_MS                 = 900      // 错误确认窗口
PENDING_SWITCH_PLAYER_MAX_AGE_MS = 1_000
CANDIDATE_PLAYERS = WeakHashMap<>()         // 防泄漏
```

### consumeLoopBoundary() —— 「播完自动下一条」★

抖音是循环播放，播完 position 归零，**没有任何回调**。它靠位置变化推断：

```java
endWindow   = max(250, min(750, duration / 20))
startWindow = max(100, min(500, duration / 20))
loopBoundary = previousPosition >= duration - endWindow
            && currentPosition <= startWindow
```

**关键：`completionArmed` 布防机制**

```java
boolean samePlayback = (player == trackedPlayer && duration == trackedDuration);
if (!samePlayback) {
    trackedPlayer = player; trackedDuration = duration;
    // 只有首次采样确实在片头附近才布防
    completionArmed = position <= max(100, min(500, duration / 20));
    return false;
}
```

源码注释解释了原因：

> 翻页后回收的播放器引擎可能仍报告**上一条**的末尾位置。在那里布防，会让之后每一条
> 都看起来像「已播完」，连环自动下滑。

**防抖**：`now - lastAutoAdvanceAt > 1500ms`；触发后立刻 `completionArmed = false`。

这是「列表 + 循环播放器」场景的经典坑，解法可直接复用。

### 其他

`playing` / `playingFromCallback` / `paused` / `completed` / `error` /
`isPlaying` / `isUserPaused` / `setPhotoContent` / `setContentAvailable` /
`shouldKeepUiHidden` / `consumePlaybackError`

---

## 五、播放器 Hook（PlayerHooks，208 行）

### 用方法名启发式，不绑死签名

```java
ENGINE_CLASSES = {
  "com.ss.ttvideoengine.TTVideoEngine",
  "com.ss.ttvideoengine.TTVideoEngineImpl",
  "com.ss.ttvideoengine.TTVideoEngineImplV2",
  "com.ss.android.ugc.aweme.player.sdk.impl.SimplifyPlayerImpl",
  "com.ss.android.ugc.playerkit.simapicommon.model.SimVideoUrlModel"
};

遍历 getDeclaredMethods():
  无参 play/start/resume     → 标记 playing
  无参 pause*/stop/release*  → 标记 paused
  含 completion/completed    → 标记 completed
  set*listener               → hook setter，把传入的 listener 也 hook 掉
```

**抗版本变化**：不看固定签名，只看方法名语义。

### listener 也要 hook

```java
hook(setXxxListener) → chain.proceed()
                     → LISTENER_OWNERS.put(listener, WeakReference(owner))
                     → hookListenerObject(listener)

hookListenerObject: 找 onCompletion / onCompleted / onPlaybackStateChanged
```

**状态码映射**（TTVideoEngine 39.7.0）：`0=stopped, 1=playing, 2=paused, 3=error, 4=initializing`

`HOOKED_LISTENER_CLASSES` 用 WeakHashMap 去重；`LISTENer_OWNERS` 用 `WeakReference` 防泄漏。

---

## 六、音频转 MP3（AudioTranscoder + Mp3Encoder，各 113 行）

### AudioTranscoder —— MediaCodec 解码

```java
MediaExtractor 找第一个 audio/* 轨 → selectTrack
MediaCodec.createDecoderByType(mime)
format.setInteger(KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
loop {
    dequeueInputBuffer(10ms) → readSampleData → queueInputBuffer
    dequeueOutputBuffer(10ms)
    INFO_OUTPUT_FORMAT_CHANGED → 更新 format
    取 KEY_SAMPLE_RATE / KEY_CHANNEL_COUNT / KEY_PCM_ENCODING
    支持 PCM_16BIT 和 PCM_FLOAT
    encoder.matches(rate, channels) 变了 → "audio format changed midstream"
    codec.releaseOutputBuffer(index, false) 在 finally
    lastProgress 超过 30s 无进展 → "audio decoder timed out"
}
encoder.finish(stream)
finally: encoder.close(), codec.release(), extractor.release()
```

### Mp3Encoder —— jump3r (Java LAME)

**手动装配 11 个模块**（jump3r 的 API 特点）：
`GainAnalysis, BitStream, Presets, QuantizePVT, Quantize, VBRTag, Version, ID3Tag,
Reservoir, Takehiro, MPGLib, Interface, Common`

参数：
```java
out_samplerate = min(sampleRate, 48000)
mode   = 单声道 ? MONO : JOINT_STEREO
brate  = 单声道 ? 128 : 192
quality = 5
bWriteVbrTag = false      // CBR 不需要回写 Xing header
```

PCM 转换：
```java
16-bit: pcm.getShort() << 16                // 16 位左移到 32 位
float : clamp(-1..1) * Integer.MAX_VALUE
```

每帧 1152 样本（MP3 标准），`lame_encode_buffer_int` / `lame_encode_flush`。

---

## 七、沉浸界面（ImmersiveUi，2031 行）

### attemptOpenComments() —— 双击开评论的工程细节★

**① 800ms 去重**
> 三个 hooked callback（interceptDoubleClick + onDoubleTap + onDoubleTapEvent）会报告
> 同一次手势，点三次会把面板**关掉**。

**② 冷启动重试**
```java
candidates.isEmpty() && attempt < 2 → postDelayed(350ms) 重试
```
> 冷启动后第一个视频还没有右侧操作栏，隔一会儿再找，第一次双击才有效。

**③ clickableTarget() 向上找 4 层**
```java
for (level = 0; level < 4 && current != null; level++) {
    if (current.isClickable() && current.hasOnClickListeners()) return current;
    current = parent;
}
```
> 钻进最小的可点子控件会点到评论数（TextSwitcher），那玩意儿只播数字动画。

**④ tapView 失败退回 performClick()**，最多试 3 个候选。

### DanmakuViewClassifier（52 行）—— 多渲染层

40.8 的弹幕由多层渲染：
```
DDanmakuComposeView      (Compose)
DanmakuFunctorView       (Skity/functor)
DanmakuView              (legacy canvas)
BaseComposeView
   ↑ 都挂在 DanmakuAsyncRenderParent 容器下
```
只保留 Compose 一层 → 其它层弹幕全消失。**保留容器**能连未来新增的渲染层一起兜住。

启发式兜底：包名 `com.bytedance.common.ultra.danmaku.` + 类名以 `view`/`parent` 结尾
+ 排除含 `permission`/`setting`/`input`/`panel` 的。

### ImmersiveTouchGuard（141 行）—— 长按开关的保存/恢复

```java
反射拿 LongPressLayout 的私有字段 longPressEnable / enableLongClick
ACTION_DOWN: 保存原值 → 改值（侧边 25% 保留长按=快进，中间禁用=长按菜单）
UP/CANCEL  : restoreLongPress(view) 恢复原值
```

**过滤隐藏控件触摸**：Hook `ViewGroup.dispatchTransformedTouchEvent`，
在 `child.dispatchTouchEvent()` **之前**拦。

**关键约束**：
> 一旦 DOWN 被接受，MOVE/UP/CANCEL 必须继续放行。丢掉 UP 会让原生快进状态卡住。

### 两个纯函数 Policy（22 + 14 行）

```java
// ImmersiveTouchPolicy
shouldBlock(hidden, gesturePath, cancel) = hidden && !gesturePath && !cancel
    // 取消事件必须放行，让已接受手势的目标释放状态
isSideLongPress(x, width) = x <= width*0.25 || x >= width*0.75

// ImmersiveTransitionPolicy
shouldHoldHiddenUi(hasHidden, now, transitionUntil) = hasHidden && now < transitionUntil
```

**把判定逻辑抽成纯函数**，便于单测（项目里 `ImmersiveTouchPolicyTest` 就是这么测的）。

---

## 八、双击拦截（DoubleTapGuard，104 行）

两条独立路径：

**① 抖音自己的识别**
```java
FeedComponentGroup.interceptDoubleClick(MotionEvent, MotionEvent) → boolean
返回 true 消费手势，阻止后续点赞和爱心动画，但不破坏它的手势状态
```

**② 系统 GestureDetector**
```java
Hook 所有构造函数 → chain.proceed() 后遍历参数找 OnDoubleTapListener → hook 它
Hook setOnDoubleTapListener → 同样处理传入的 listener
hookListener: onDoubleTap / onDoubleTapEvent
```
`HOOKED` Set 防重复 hook（同一 Method 对象）。

**注意**：Hook 的是**运行时传入的 listener 实例的类**，不是固定类名。

---

## 九、清晰度（QualityHooks，1245 行）

三层钉死，缺一层就漏：
1. **模型层**：`VideoModel` 安装期直接挂钩（41.0 预加载不走 `setVideoModel`）
2. **引擎层**：`getVideoInfoList` / `getSupportResolutions` 统一钉到目标档
3. **应用层**：`SimVideoUrlModel` 首次方法调用时替换 `bitRate` 列表

### 坑

- **DASH 音轨条目必须保留**：`mMediaType=1`、宽高 0、带误导性 `mResolution="360p"`。
  过滤掉 → DASH 视频完全无声
- **档位识别优先 `getVideoHeight()`**，再看 `mResolutionStr`/`mDefinition`，
  不做 `mQualityType` 粗映射兜底
- **Hook `hashCode()` 会在哈希表运算中递归重入 → 启动 ANR**，要加重入守卫
- 每层独立开关 `layerEnabled(key)` + `dumpEnabled()` 诊断输出

---

## 十、设置页（MainActivity，526 行）

**纯代码构建 UI，无 XML**：`buildContent()` 165 行，配套 `card()` / `addDivider()` /
`sectionTitle()` / `text()` / `rounded()` 辅助方法。

**配置读写**：通过 `XposedService` 的 RemotePreferences 与宿主进程同步，
`bindPreferences(service)` 在服务绑定时重建界面。

**关键词解析**（FilterPreferences）：
```java
raw.split("[\\r\\n,，;；]+") → LinkedHashSet 去重 → 不可变 List
匹配：title + "\n" + description 同时搜，忽略大小写（Locale.ROOT）
```
关键词支持换行 / 中英文逗号 / 分号分隔。

---

## 十一、我们能用的 / 用不上的

| 它做的 | 能搬 | 说明 |
|---|---|---|
| MediaExtractor 三道验轨 | ✅ | 纯本地校验 |
| canDecode + ByteVC1 排除 | ✅ | 按 mime 判断即可 |
| 头字节判图片格式 | ✅ | |
| gzip 响应解压 | ✅ | |
| OOM 翻倍重试 | ✅ | |
| 下到临时文件 → 校验 → 才落盘 | ✅ | 防半截文件 |
| 失败后重新取地址 | ✅ | 改造成本中等 |
| 循环边界检测 | ⚠️ | 思路可借鉴，我们没有列表播放 |
| 子系统独立 try/catch | ✅ | 架构层面 |
| 方法名启发式 hook | ❌ | 我们不做 hook |
| 按 `bit_rate[]` 选档 | ❌ | 分享页 `bit_rate` 恒 null |
| `watermark_free_download_url_list` | ❌ | web 接口不下发 |
| 清晰度锁 4K | ❌ | web 端封顶 1080p |
| 音轨条目保留 / 播放器钉死 | ❌ | 播放器 Hook |
| MP3 转码 | ⚠️ | 要引 jump3r（LGPL 2.1） |

---

## 十二、对我们项目的三条改进

### ① 视频下载后验轨
下到临时文件 → `MediaExtractor` 检查有视频轨 + 有音轨 + 能解码 → 通过才移相册。
挡「下了个只有声音的 mp4」。

### ② 图片落盘前看头字节
我们只按 URL 后缀选 jpeg。如果 CDN 返回 webp 数据存成 `.jpg`，相册就是乱码。
加 `sniffImageMime` 校验，非 JPEG 转一下。

### ③ 失败后自动重新解析一次
抖音 CDN 地址有时效，现在过期只能用户手动点重试。

---

## 附：它踩过而我们还没踩的坑（提前防）

- 广告标记 `asyncAdInfo` 在普通视频上也有 → 用它判广告会跳过整个信息流
- 翻页后回收的播放器仍报上一条位置 → 误判播完，连环自动下滑
- 三个回调报告同一次双击 → 点三次把面板关掉
- 钻进最小的可点子控件 → 点到只有动画的数字
- DASH 音轨条目宽高为 0 但 `gear_name` 像视频 → 按字符串解析会选错
- Hook `hashCode()` → 哈希表递归重入 → 启动 ANR
- 丢掉 `ACTION_CANCEL` → 原生快进状态卡住
- 冷启动第一个视频没有右侧操作栏 → 第一次操作静默失败
