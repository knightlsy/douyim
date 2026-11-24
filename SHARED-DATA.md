# 两个 App 的共享数据约定

`DouyinDownloader`（独立 App，读分享页）与 `douyim`（LSPosed 模块，读内存对象）之间交换数据的方式。

## 为什么用文件而不是 ContentProvider

两个 App 签名不同：App 用 release keystore，模块用 debug 签名。
`android:protectionLevel="signature"` 的权限用不了；用普通权限等于谁都能读。
共享文件是唯一不需要统一签名的方案。

## 位置

```
/sdcard/Android/media/com.knightlsy.douyin/shared/
    history.jsonl     下载历史（两个 App 都写，只追加）
    settings.json     共享设置（模块写，App 读）
```

**为什么是 `com.knightlsy.douyin`**：App 的 `Android/media/<pkg>/` 目录。
`Android/data/` 在 Android 11+ 对其它 App 完全不可见，`Android/media/` 仍可读。

写入方各自保证不写坏别人的行 —— **只追加，不改写，不删除**。

## history.jsonl 格式

一行一条 JSON，UTF-8，`\n` 结尾。**只追加**，并发安全靠 O_APPEND。

```json
{"id":"...","contentId":"...","title":"...","author":"...","coverUrl":"...","contentType":"VIDEO","originalUrl":"...","downloadTime":1764000000000,"downloadedFiles":["..."],"source":"app|xposed"}
```

字段与 App 的 `DownloadHistoryItem` 一一对应：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 唯一。App 用 UUID，模块用 `xposed-<aid>-<毫秒>` |
| `contentId` | String | 作品 aweme_id |
| `title` | String | 标题或描述首段 |
| `author` | String | 作者昵称 |
| `coverUrl` | String | 封面地址，可为空串 |
| `contentType` | String | `VIDEO` / `IMAGE_COLLECTION` / `LIVE_PHOTO` |
| `originalUrl` | String | 原始分享链接，模块拿不到时可为空 |
| `downloadTime` | Long | 毫秒时间戳 |
| `downloadedFiles` | String[] | 文件名列表（不是绝对路径） |
| `source` | String | `app` 或 `xposed`，用于界面上标来源 |

**去重**：按 `id`。App 读的时候用 `id` 做 Set 过滤。

**容忍坏行**：解析失败就跳过那一行，不要让整个历史读不出来。

## settings.json 格式

单个 JSON 对象，模块写、App 读：

```json
{"version":1,"quality":"auto","skipAds":true,"skipLives":true,"skipImages":false}
```

| 字段 | 取值 |
|---|---|
| `quality` | `auto` / `360p` / `540p` / `720p` / `1080p` / `2k` / `4k` / `best` |
| `skipAds` | Bool |
| `skipLives` | Bool |
| `skipImages` | Bool |

版本号用于将来加字段时兼容：读到不认识的 `version` 就用默认值。

## 读写规则

**模块（douyim）**
1. 下载成功后追加一行到 `history.jsonl`，`source: "xposed"`
2. 设置变更时重写 `settings.json`（这个文件小、单写者，可以整体重写）

**App（DouyinDownloader）**
1. 启动历史页时读 `history.jsonl`，与 Room 里的记录按 `id` 合并，`downloadTime` 倒序
2. 历史条目标出 `source`，模块来的显示成「模块」角标
3. 只读，不写这个文件（避免双写冲突）

## 失败处理

- 目录不存在 → 创建，失败就静默跳过（不影响主流程）
- 文件读不到 → 当作没有共享历史
- 单行解析失败 → 跳过该行
- 写入失败 → 模块只记日志，不弹错误（下载本身已经成功了）

**原则**：共享数据是锦上添花，任何一步失败都不能影响下载或 App 启动。
