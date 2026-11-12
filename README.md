# 老旧安卓电视投屏工具

把一台已经"生态死掉"的老安卓电视，重新变成**能收手机整屏、还带声音**的显示器。

自写一对 App：手机端采集屏幕 + 内录声音 → H.264 编码 → TCP 送到电视 → 电视硬解上屏。
不依赖厂商投屏，不依赖任何云服务，不联网、不上传，全程只在局域网里跑。

这套东西是在一台 2017 年康佳 50 寸（`LED50X1800A`，Mstar 6A918，**Android 4.3 / API 18**）上真刀真枪做出来的，
但两端本身是通用的：电视只要 API 14+ 且能硬解 H.264，基本能跑。

---

## 为什么需要它

那一代国产电视硬件其实不差：1080p 面板、能硬解 H.264、1GB 内存。死掉的是系统和生态：

- **厂商自带的投屏**（康佳叫「易互动」，海豚 Hpplay SDK）只推画面、**不传音频**，版本封顶；
  会话断一次状态就脏掉，之后再也连不上，只能杀进程。
- **应用商店里的现代 App 装不上**：minSdk 基本都是 21 以上，API 18 的机器直接拒绝安装。
- **系统级投屏收不了**：这批机器的 Miracast 只有发送端（`wfdType=1`、`availableDisplays=[]`），
  手机"无线投屏"搜不到它。

所以只能自己写一对：**画面 + 声音**都送过去。

---

## 直接下载

v1.0 已经发到 [Release](https://github.com/ShenShangShenShang/phone-tv-mirror/releases/tag/v1.0)，不想自己编译的话直接装这两个：

| 文件 | 大小 | 下载 |
|---|---|---|
| 手机端 v1.0 | 90,915 字节（versionCode 105） | [phone-tv-mirror-sender-v1.0.apk](https://github.com/ShenShangShenShang/phone-tv-mirror/releases/download/v1.0/phone-tv-mirror-sender-v1.0.apk) |
| 电视端 v1.0 | 62,243 字节（versionCode 102） | [phone-tv-mirror-tv-v1.0.apk](https://github.com/ShenShangShenShang/phone-tv-mirror/releases/download/v1.0/phone-tv-mirror-tv-v1.0.apk) |

Release 附件用的是纯英文文件名（GitHub 会把中文名剥掉，两个包会撞成同一个名字），
仓库里 `01-APK/` 下还是原来的中文名，是同一个文件。

电视端要先装、手机端后装。装电视端之前如果装过旧版本（用 `tvbox.keystore` 签的那一版），
必须先 `pm uninstall tv.debloat.mirror` 再装，否则会报 `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`。

---

## 里面有什么

| 组件 | 作用 | 适配范围 |
|---|---|---|
| `投屏手机端_v1.0.apk` | 采集手机整屏 + 内录声音，编码后走 TCP 送到电视 | Android 10+（音频内录需要 `AudioPlaybackCapture`） |
| `投屏电视端_v1.0.apk` | 电视端常驻接收、硬解、上屏、出声 | API 14+，能硬解 H.264 Baseline |
| `03-源码` | 两端完整源码 + 一条不依赖 Gradle 的构建脚本 | — |

协议是自定义的（TCP 9000 + UDP 9100 发现），细节见 `02-文档/02-投屏协议与实现.md`。

---

## 怎么用

### 1. 电视端

1. 把 `投屏电视端_v1.0.apk` 装到电视（`adb install`、U 盘、或任意侧载方式）。
2. 打开它**一次**就够。监听跑在常驻服务里，之后：
   - **开机自启**（收 `BOOT_COMPLETED`），不用每次手动点图标；
   - **平时不占屏幕**，电视显示桌面、播放器、HDMI 都行；
   - **只有手机真的连上来**才把投屏画面切到前台，连接结束 3 秒后自动退回原来的界面；
   - 断网、手机关机、进程被杀之后不用拔电源：会话有 15 秒读超时，超时自己释放连接回到监听，
     端口出问题也会 1 秒后自动重建。

### 2. 手机端

1. 装 `投屏手机端_v1.0.apk`。
2. 第一次打开会连着弹三个权限页（通知 / 电池白名单 / 悬浮窗），**都允许**。
   这三项叠加才最接近"不被系统杀掉"，没有"后台无限制"这种总开关。
3. 点「开始投屏」→ App 自己在局域网里广播找电视（**不用手填 IP**）→ 系统弹一次录屏授权 → 允许。
4. 投上之后手机可以退回桌面、切别的 App，画面继续跟着走。
   万一没搜到电视，界面上会出现输入框，可以手动填电视 IP 直连。

### 3. 声音

手机端有投屏音量滑块（0–100%），**只改投屏这一路的 PCM 增益，不动电视本身的系统音量**。
所以投完屏换回电视自带节目，不用再把音量调回去。拉到 0 就是彻底静音。
电视端遥控器上下键也能实时增减，每档 5%。

### 4. 转屏

横竖屏切换会**再弹一次录屏授权**，这是 Android 14 的硬限制：同一份 `MediaProjection` 只能建一个
`VirtualDisplay`，换分辨率必须换授权。本工具做到的是**只弹一次、弹完直接续上、不闪回 App 界面**。
代价是每次转屏都要点一下"允许"，没有更省事的办法。

> 画面比例和电视不一致时会有黑边，这是比例差异不是 bug。本工具选择**等比显示、不裁任何内容**。
> 想填满可以改 `MirrorActivity.adjustSurface()` 的显示区算法，代价是最边缘一条看不见。

---

## 换一台机器要注意什么

两端是通用的，但每台机器得先测底线：API 版本、ABI、能否侧载、能否硬解 H.264。
详细清单见 `02-文档/04-通用化改造指南.md`。

分辨率与码率按机器性能调，位置在手机端 `MirrorService` 里（默认 5 Mbps / 1280 长边）。
老机器跑到卡顿时，先降长边再降码率。

---

## 编译

不需要 Gradle，不需要 Android Studio。`03-源码/build.ps1` 是一条手写流水线：

```
javac -> d8 -> aapt2 compile -> aapt2 link -> 塞 classes.dex -> zipalign + apksigner
```

准备：JDK 17、Android SDK（含 build-tools 与 `platforms/android-36`），环境变量 `JAVA_HOME` / `ANDROID_HOME` 指好。

```powershell
cd 03-源码
.\build.ps1 -App 投屏手机端     # 产出 投屏手机端.apk
.\build.ps1 -App 投屏电视端     # 产出 投屏电视端.apk
```

两个坑（脚本已经处理，自己另写流水线时要记得）：

1. **aapt2 / apksigner 在中文路径上会失败**（`failed to open directory`）。脚本会把源码复制到纯 ASCII
   的临时目录编译，编完再拷回来。
2. **第一次运行会自动生成 `release.keystore`**（口令 123456），存在临时目录里。这把钥匙和
   `01-APK` 里预编译包**不是同一把**——如果机器上已经装了本项目 APK，装自编译版本前要先卸载，
   否则报签名冲突。

改代码后**务必提 `AndroidManifest.xml` 里的 `versionCode`**，否则覆盖安装会被当成同一个包。

---

## 已知限制

- 转屏必须重新申请录屏授权（系统限制，不是本工具能解的）。
- Android 10 以下没有 `AudioPlaybackCapture`，手机端抓不到内录声音，只能出画面。
- 电视端只做等比显示、不裁画面，比例不一致必然有黑边。
- 电视端在 Android 4.3 上走 `SurfaceView` + `awaitFreshSurface`，**不能用 TextureView 矩阵变换**
  （康佳固件上参数正确也会变形）。
- 手机开热点给电视连的场景依赖"热点接口能拿到本地地址"，个别厂商 ROM 的热点接口不会注册成
  `Network`，代码里为此额外走了"按网卡地址绑定"的路子；再碰上连不上，就用手填 IP。

---

## 许可

MIT，见 `LICENSE`。
