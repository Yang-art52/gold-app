# 金价小时监控（Android）

一个极简安卓 App：点开即见当前金价（元/克）+ 今日逐小时折线图；可设底价，后台每小时查一次，跌破底价弹通知。

- 包名：`com.goldhourly.app`　最低系统：Android 8.0（API 26）
- 价格源：`api.gold-api.com`（现货 XAU，美元/盎司，免费无 key）
- 汇率源：`open.er-api.com`（USD→CNY，缓存 12 小时）
- 换算：`元/克 = 美元/盎司 × 美元兑人民币 ÷ 31.1034768`（参考值，与上金所 Au99.99 有细微差异）

## 一、出 APK（GitHub Actions，无需装 Android Studio）

1. 在 GitHub 新建一个**空仓库**（Public 免费；Private 也够用）。
2. 上传本目录全部内容：
   - 网页方式：仓库页 → `Add file` → `Upload files`，把 `gold-app` 文件夹里的内容拖进去（保持目录结构），Commit。
   - 若 `.github` 目录没被上传（隐藏目录偶尔被忽略），手动补一个文件：
     `Add file` → `Create new file`，文件名填 `.github/workflows/build.yml`，把本仓库 `build.yml` 的内容粘进去。
3. 打开仓库的 **Actions** 标签，等 `build-apk` 跑完（首次约 3–6 分钟）。
4. 点进这次运行，页面底部 **Artifacts** → 下载 `gold-hourly-apk.zip`，解压得到 `app-debug.apk`。

## 二、安装

1. 把 `app-debug.apk` 传到手机（微信文件传输助手 / 数据线 / 网盘均可）。
2. 点击安装，允许「安装未知来源应用」。
3. 首次打开：允许通知；输入底价并保存；点「允许后台」→ 在系统弹窗里选允许（跳过电池优化）。
   - 小米/华为/OPPO/vivo 还需在「设置 → 应用 → 金价小时监控」里打开**自启动**、把电池策略设为**无限制/允许后台活动**，否则系统会杀进程导致提醒延迟。

## 三、行为说明

- 打开 App：立即联网拉一次价格，写入当前小时的点；前台每 60 秒检查是否进入新的一小时，进入就刷新。
- 后台：`AlarmManager.setAndAllowWhileIdle` 每个整点后 10 秒触发一次，拉价 → 存点 → 比底价。
- 提醒去重：跌破底价通知一次，价格回升到 `底价 × 1.002` 以上后重新武装，避免每小时重复轰炸。
- 断网：保留上次数据，界面显示「网络异常」，闹钟下一个整点继续。
- 重启手机 / 覆盖安装：自动重新排闹钟。

## 四、常见问题

| 现象 | 处理 |
|---|---|
| Actions 报错 | 把失败日志里第一个 `e:` 或 `FAILURE` 段落发我，我改代码 |
| 通知不弹 | 检查系统通知权限、电池优化、自启动；部分 ROM 需「通知 → 允许横幅」 |
| 提醒比整点晚 | Doze 省电机制导致，通常几分钟内；国产 ROM 关闭电池优化可缓解 |
| 桌面图标空白 | 极个别老旧启动器不认自适应图标，不影响功能 |
| 想改底价 | 打开 App，输入新数值 → 保存底价，立即生效 |

## 五、目录结构

```
app/src/main/java/com/goldhourly/app/
  MainActivity.kt   界面（大字价格、折线图、底价设置）
  ChartView.kt      自绘 0–23 点折线图
  GoldApi.kt        HTTP 取价 + 汇率
  PriceStore.kt     SharedPreferences 存储
  Sync.kt/PriceReceiver.kt  前台与闹钟共用的同步与告警逻辑
  HourlyAlarm.kt    整点闹钟 + 开机重排
  Notifier.kt       通知渠道与通知内容
```
