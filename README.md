# 竖滑播放器 FlickBox

本机相册视频的抖音式上下滑动播放器。**离线、零依赖、APK 53 KB。**

![主视觉](promo/hero-16x9.png)

- 播放内核：系统 `MediaPlayer` + `SurfaceView`，全程只有一个播放器实例，换页时 `reset()` 复用。
- 视频来源：`MediaStore` 自动扫描本机全部视频（自动排除实况照片的动态 mp4），不联网、不申请网络权限。
- UI：Kotlin 纯代码构建 View，无 XML 布局、无 Compose、无 AndroidX/RecyclerView/ViewPager2，竖向翻页手势自己实现。
- 体积：`dist/FlickBox.apk` = **53,720 字节（约 52 KB）**。内部构成：`classes.dex` 89,712 字节（压入 APK 后约 43 KB）、二进制清单 2,652 字节、启动图标 380 字节、`resources.arsc` 588 字节，其余为签名块。
- 全部控件（进度线、播放/暂停图标、横屏按钮、循环 chip、信息面板、选片面板）都是 `onDraw` 或代码搭出来的，没有一张图片资源、没有一个 XML 布局。体积轨迹：33 KB（最初）→ 37 KB（细进度线 + 图标徽标 + 横屏按钮 + 系统选片对话框）→ 41 KB（自绘选片面板 + 手势带避让 + 后台 Surface 重绑）→ 53 KB（第六轮：双击快进 + 选片搜索/排序 + 视频信息面板）。第四轮（横屏真全屏 + 画面一律等比完整显示）和第五轮（竖屏同样隐藏功能区 + 三档循环播放）加起来没让包变大；第六轮三个功能都是新代码，dex 从 64,964 涨到 89,328 字节，压缩包 +12 KB，仍然没有任何依赖、资源或权限的增加。第七轮（选片面板点排序 tab 就地换序）又写了 384 字节 dex，压缩后正好吃掉 zip 对齐的余量，包仍是 53,720 字节。

## 手势与功能

| 操作 | 行为 |
| --- | --- |
| 上下滑动 | 切换上一个 / 下一个视频，带惯性与回弹，封面跟随手指滑动 |
| 单击画面 | 暂停 / 继续（在加载中点击会记住意图，准备完成后按意图执行）。因为要给双击留判断时间，单击比之前晚约 0.3 秒生效 |
| 双击画面左半 / 右半 | 后退 / 快进 10 秒，屏幕侧边亮出「« 10 秒 0:04」提示条后淡出；播放中（功能区收起时）也能用 |
| 长按画面 | 临时 2.0x 加速，松手恢复常速 |
| 点右下「1.0x 倍速」 | 常速循环：1.0x → 1.25x → 1.5x → 2.0x → 1.0x |
| 点底部进度条 | 直接跳到点击位置（抖音同款细线进度条） |
| 横向拖动进度条 | 精确定位；松手后恢复拖动前的播放/暂停状态 |
| 在进度条上竖向滑动 | 手势自动交给翻页层，依然能换视频 |
| 点左下「N/总数 ▾」 | 底部滑出选片面板：搜索框 + 时间/名称/大小/目录四种排序 + 缩略图列表（`#序号 · 时长 · 目录`），点一条直接播；点排序 tab 就地换序，面板不收起也不重新拉起 |
| 点标题右侧「信息」 | 弹出这条视频的详细信息：分辨率（含旋转）、时长、文件大小、码率、编码、容器、帧率、目录、完整路径、媒体库 ID、URI、加入时间、当前进度、倍速、循环方式、列表排序 |
| 点右下「横屏」按钮 | 切到横屏（按钮变「竖屏」再点回来），画面重新适配 |
| 点标题右侧「顺序播放」chip | 循环方式循环：顺序播放 → 单条循环 → 列表循环 → 顺序播放，选完立刻存盘，重开应用还在 |
| 播放中（横屏/竖屏都一样） | 整条功能区自动收起，画面独占全屏（竖屏 1080×2400、横屏 2400×1080）；上下滑动换视频照常可用 |
| 暂停后 | 功能区淡入浮在画面下方（标题、循环、序号、时间、倍速、横屏按钮、进度线都在），再点一下收起 |
| 切到别的软件再回来 | 重新绑定 Surface：画面照常显示不会黑屏只剩声音；切走前在播就继续，切走前暂停着就保持暂停 |
| 旋转设备 | Activity 不重建，画面重新适配，进度不丢 |

其他行为：

- 画面一律按原比例完整显示：等比缩放、长边贴边、短边留黑，不裁切、不拉伸、不放大。竖屏视频在横屏下也是原样居中（实测 1080×1920 的视频在 2400×1080 里显示成 608×1080），字幕、水印、抖音号这类贴在画面边缘的信息不会再被切掉。封面缩略图用同一套 `FIT_CENTER` 铺法，和首帧对得上，切换时不跳。
- 暂停时画面中央常驻白色三角形图标，恢复播放时短暂闪现暂停双竖条后淡出，不再用「已暂停」文字。
- 实况照片的动态 mp4 会被过滤掉（识别 MediaStore 的 `is_live_photo` 及厂商 `live_photo` 字段），列表里只剩真正的视频。
- 视频区域永远是整屏（竖屏 1080×2400、横屏 2400×1080），控制条不再吃掉任何一条底部区域：播放中整条收起，暂停时才浮在画面之上。实测竖屏播放中 `PagerView 0,0-1080,2400`、控制条标志位 `G`。
- 循环三档：「顺序播放」= 播完自动下一条，最后一条播完停住；「单条循环」= 当前这条从头再播；「列表循环」= 最后一条播完回到第一条。档位存在 `SharedPreferences`。
- 双击快进/后退 10 秒：判定窗口 300ms、两次落点距离不超过 72dp，左半屏后退、右半屏前进。为了留出这个窗口，单击暂停延后 300ms 执行；一旦拖动手势成立就立刻取消待定的单击，所以滑动换片不受影响；长按加速优先于双击（长按触发时同样取消待定的单击）。定位结果夹在 `0 ~ 时长-300ms`，和进度条同一套规则。
- 选片面板支持搜索与排序：搜索框按片名或目录名过滤（不区分大小写，边打边筛，标题行变成「命中 N/总数 条」），序号始终按完整列表算，过滤不会让「跳到第几条」错位。排序四档：时间（`DATE_ADDED` 倒序，默认）/ 名称（不区分大小写）/ 大小（降序）/ 目录（同目录聚在一起，目录内按时间）。点排序 tab 只换列表和 chip 高亮，面板原地不动（不收起、不重新拉起、不重播入场动画），已输入的搜索词和过滤结果跟着新顺序重算；行内缩略图先查内存缓存（`VideoRepo.peekSmall`），命中就直接贴上去，换序时不闪白。换排序时停在当前这条视频上（不重新解码，播放不断），序号标签和退出位置一起更新；排序方式存 `SharedPreferences`，重开应用还是它。
- 换序不动画面：`PagerView.reorder()` 只改列表、名次和邻居封面，当前页直接按「视频已经透着」处理（`alpha` 当场置 0，不走 120ms 淡出）。原来走 `setPosition + markVideoPage` 会先把该页封面盖回画面再淡出，面板后面的画面就闪一下。
- 信息面板的编码/码率/帧率/旋转来自 `MediaExtractor` 的轨道格式（异步取，晚到一步再刷新一遍），分辨率/时长/大小/目录/路径/ID/URI/加入时间来自 `MediaStore`，当前进度/倍速/循环/排序来自播放器实时状态。取不到的字段显示「未知」，不猜。
- 控制条整体抬到系统手势带之上：按 `navigationBars`/`systemGestures` 的真实 inset 计算（本机竖屏导航带 54px，再加 8dp 余量 = 78px），横屏时手势带在左右两侧，底部只留 10dp。进度线不再压在屏幕最下沿，拖它不会被系统判成上滑切应用。
- 视频条数没有上限。实测那台手机显示 100 条，是因为媒体库 177 条记录里有 77 条是实况照片的动态 mp4，过滤后正好剩 100 条真视频。
- 每条视频的播放进度按 `MediaStore` id 存在 `SharedPreferences`，回到该条时从上次位置继续（片尾 2 秒内视为已播完，从头开始）。
- 退出时还会记住「最后在看哪一条」，重开应用直接回到那一条（按 id 匹配，扫描顺序变化也不怕）。
- 播完之后怎么走由循环档位决定（见上）；无法解码的文件会被跳过并从列表移除。
- 屏幕常亮、全屏沉浸态、无通知栏/状态栏。

## 目录结构

```
AndroidManifest.xml        清单（无 INTERNET 权限）
r8.pro                     R8 裁剪规则
res/drawable/ic_launcher.png  启动图标（源文件 458 字节，aapt2 优化后 380 字节）
src/com/pd/player/
  MainActivity.kt          组装三层界面、权限、进度/退出位置/循环档位/排序方式存档、旋转适配
  PagerView.kt             竖向翻页容器（拖动/惯性/吸附/3 槽复用 + 单击与双击识别 + 换序不动画面）
  PageView.kt              单页封面（异步取缩略图）
  Overlay.kt               底部控制条（标题、信息 chip、循环 chip、序号、时间、倍速、横屏按钮、进度线；播放中整条收起；含快进/快退提示条）
  Controls.kt              三个自绘控件：细进度线、播放/暂停图标徽标、横屏按钮
  Picker.kt                自绘的底部选片面板（搜索 + 排序 + 缩略图列表）与居中的视频信息面板，都是框架 Dialog
  PlayerCtl.kt             MediaPlayer 封装（单实例、变速、进度、单条循环重播、Surface 重绑）
  VideoRepo.kt             MediaStore 扫描（过滤实况照片）+ 四种排序 + 大小/目录格式化 + MediaExtractor 元数据探测 + 整屏/列表两级缩略图缓存
tools/setup_env.ps1        安装构建工具链
tools/build_apk.ps1        无 Gradle 打包
tools/make_icon.py         重新生成启动图标（需要 Pillow）
tools/make_promo.py        合成宣传图（AI 舞台背景 + 真机截图 + 中文排版，需要 Pillow）
promo/*.png                宣传图产物：hero-16x9 / poster-3x4 / features-16x9
device-tests/*.png         真机实测截图（adb 截屏，非工程产物）
```

`device-tests/` 里的截图是手机相册的真实内容，属于隐私，不入库；下文实测段落提到的 `nXX_*.png`、`rX_*.png` 只是本地采样时的文件名。对外看效果请看 `promo/`。

![功能一览](promo/features-16x9.png)

渲染层次（自下而上）：`SurfaceView`（视频） → `PagerView`（封面） → `Overlay`（控制）。
封面不透明时挡住视频，首帧渲染后当前页封面淡出，于是看到的是实时画面；手指一开始拖动就恢复封面，滑动过程只搬运 3 张位图，与解码无关，低配机也不掉帧。
`content`（视频 + 封面 + 徽标）永远铺满整屏，控制条只在暂停时叠在画面之上，不再向画面要边距。控制条空白处不消费触摸，竖向滑动照样落到翻页层；它整体 `GONE` 时触摸全部穿透到翻页层，所以播放中依然能滑动换片。

## 环境搭建（一次性）

不需要 Android Studio，也不需要 Gradle。默认安装到 `D:\CodeDevelop\Env`，总下载约 450 MB，使用清华 / 阿里云 / Google 官方源：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup_env.ps1
```

装的内容：

| 组件 | 来源 | 位置 |
| --- | --- | --- |
| Temurin JDK 17.0.20.1+1 | mirrors.tuna.tsinghua.edu.cn/Adoptium | `D:\CodeDevelop\Env\jdk-17` |
| Kotlin 2.1.0 编译器与 stdlib（纯 jar） | maven.aliyun.com | `D:\CodeDevelop\Env\kotlin\lib` |
| Android cmdline-tools | dl.google.com | `D:\CodeDevelop\Env\android-sdk\cmdline-tools\latest` |
| platforms;android-34、build-tools;34.0.0 | dl.google.com（自动接受 license） | `D:\CodeDevelop\Env\android-sdk` |

脚本可重复执行，已存在的组件会跳过。路径不同时用 `-EnvRoot` 参数覆盖。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_apk.ps1
```

产物：`dist\FlickBox.apk`（签名对齐好的 debug 包），控制台会打印体积与 `aapt2 dump badging` 摘要。

流水线（全部是命令行工具，没有 Gradle）：

1. `aapt2 compile` + `aapt2 link` 生成资源和二进制清单
2. `java -cp kotlin\lib\* org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-jdk -jvm-target 1.8` 编译 Kotlin
3. `jar` 打包 class
4. **R8** `--release --min-api 21`：把 kotlin-stdlib 里没用到的部分全部裁掉（53 KB 就是这么来的）
5. `jar --update` 注入 `classes.dex`
6. `zipalign -f 4`
7. `apksigner sign`（首次运行自动用 `keytool` 生成 `D:\CodeDevelop\Env\pd-debug.keystore`，口令 `android`）

可选参数：

```powershell
tools\build_apk.ps1 -SkipShrink          # 跳过 R8 裁剪，构建更快、APK 约 2 MB
tools\build_apk.ps1 -EnvRoot E:\Env      # 换工具链位置
tools\build_apk.ps1 -Platform android-35 -BuildTools 35.0.0
```

## 安装到手机

方式一（adb，工具链里已带）：

```powershell
D:\CodeDevelop\Env\android-sdk\platform-tools\adb.exe install -r dist\FlickBox.apk
```

方式二：把 `dist\FlickBox.apk` 拷到手机直接安装（debug 签名，需要在系统里允许“安装未知来源应用”）。

首次启动会申请读取视频的权限：

- Android 13+：`READ_MEDIA_VIDEO`（运行时申请）
- Android 5.0 ~ 12L：`READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`）
- 拒绝后界面会显示提示，点提示文字可重新申请

## 真机验证

已在 **vivo V2359A / Android 16（API 36）/ 1080×2400 / 刘海屏** 上安装并实测，媒体库共 177 条视频记录：

- 冷启动无崩溃（`logcat` 无 FATAL/AndroidRuntime），权限申请一次通过后自动扫描；实况照片被过滤掉 77 条，列表只剩 **100 条真正视频**，选片面板里也只有这 100 条。
- 竖屏真全屏（第五轮）：视频区域 `0,0-1080,2400` 整屏，播放中控制条 `GONE`（`dumpsys` 标志位 `G`，`n31` 前一次采样），点一下暂停才淡入 `0,2049-1080,2400` 浮在画面之上；`n31_portrait_paused_loop.png`。旧的「视频区域 1080×2054 + 控制条 1080×346」分块已取消，控件不再向画面要地盘。
- 横屏真全屏：点「横屏」后视频区域 `0,0-2400,1080`，正在播放时整条功能区 `GONE`（`dumpsys` 里 `LinearLayout` 标志位从 `V` 变 `G`），屏幕上没有任何控件；`n24_landscape_playing.png`。点一下暂停，功能区淡入浮在画面下方，播放三角同时出现；`n23_landscape_paused.png`、`n26_land_check_index.png`。播放中上下滑动仍能换片（实测从 100/100 连滑到 39 前后）。
- 循环三档实测（第五轮）：点标题右侧 chip 依次变「顺序播放 → 单条循环 → 列表循环」。单条循环：第 100 条播到 0:23 结束后没有翻页，直接回到 0:02 重播（`n33_single_loop_replay.png`）；列表循环：第 100 条播完自动跳回第 1 条「泡沫-mv.mp4」（`n34_list_loop_wrap.png`）；`force-stop` 后重开，档位仍是「列表循环」且回到上次那条（`n35_loop_persisted.png`）。
- 画面等比完整显示：竖屏 9:16 视频在竖屏整屏里显示成 `1080×1920`（上下各留 240px 黑），在横屏里显示成 `608×1080`（左右留黑、不拉伸）；横屏 16:9 视频在竖屏里是 `1080×608` 居中（`n34_list_loop_wrap.png`、`n35_loop_persisted.png`），连字幕条带歌词框整块都在画面里。最直接的对照是同一条把字幕和账号水印贴在画面底部的竖屏视频：这一轮整块都露出来了，旧版铺满裁切时正好被切掉。
- 封面与首帧用同一种 `FIT_CENTER` 铺法，换页、出首帧时画面不跳、不再出现「先放大一点再定位」的观感。
- 上下滑动翻页、松手惯性吸附、连续自动播下一条均正常。
- 单击暂停 → 中央常驻白色三角图标；恢复播放 → 双竖条闪现后淡出（`n6_badge_paused.png`、`n7_badge_flash.png`），不再用文字。
- 长按 → 「2.0x 加速」，**松手后恢复原倍速且不会误触发暂停**；加速途中自动换页，标签仍如实显示当前速度（`n8_boost.png`）。
- 进度线点按直接跳位（`n11_tap_seek.png`：0:01/0:11；本轮复测点中间 → 1:34/3:15）、横向拖动精确定位（复测拖到最右 → 3:04/3:15）；在进度线上竖向起手滑动会转交给翻页层，照样换视频（复测 22→23，`n10_handoff2.png`）。
- 进度线本身位于 `y 2226-2304`，距屏幕底沿 96px；从屏幕最下沿（y=2380）上滑交给系统手势，翻页层不会抢走。
- 点「N/总数 ▾」滑出选片面板，缩略图 + `#序号 · 时长` + 当前条高亮「播放中」，打开时自动滚到正在播的那条（`n15_picker_sheet.png`）；点一条实测 22 → 21。
- 点右下「横屏」按钮进入横屏，按钮变「竖屏」，画面重新按原比例适配（`n3_landscape.png`）。测试提示：`adb shell settings put system user_rotation 1` 转不动这个应用（方向由应用自己控制），必须点应用内的「横屏」按钮。
- BACK 退出再进来回到上次那条（`n12_relaunch.png`：8/100），不是从第一条开始。
- 播放中按 HOME 再进来：画面照常（`n16_after_home.png`、`n17_after_settings.png` 切到系统设置再回来，进度 5:04 → 5:34 连续），不再出现「有声音黑屏」。暂停着切走再回来仍是暂停，并重新显示播放三角（`n21_return_while_paused.png`）；本轮改完控件显隐逻辑后复测：HOME 出去再回来 0:10/0:23 继续播、画面正常（`n29_return_from_home.png`）。
- 选片面板在本轮改动后复测无回归：点「100/100 ▾」照常滑出，`#95`–`#100` 缩略图 + 时长 + 当前条「播放中」都在（`n30_picker_still_ok.png`）。

第六轮（双击快进 + 选片搜索/排序 + 信息面板）实测：

- 双击快进：竖屏播放中双击右半屏 → 屏幕右侧亮出「10 秒 » 0:15」提示条，功能区仍然收起、播放没被打断（`r6_b_seek_fwd.png`）；再双击左半屏 → 左侧「« 10 秒 0:04」，右侧提示同时消失（`r6_c_seek_back.png`）。横屏暂停后双击左半屏 → 左侧「« 10 秒 0:03」，进度线和时间同步跳到 0:03/0:16（`r6_m_land_seek_back.png`）。
- 单击/长按/滑动无回归：单击一下 → 暂停、播放三角出现、功能区淡入（`r6_d_single_tap_pause.png`，标题行已是「信息 + 单条循环」两颗 chip）；上下滑动照常换页并自动接上下一条（`r6_k_after_swipe.png`）。
- 选片搜索：输入 `amera` 后标题行变「命中 70/100 条」，列表只剩 `Camera` 目录那 70 条，序号仍显示 `#91`、`#93`… 这些全列表名次（`r6_j_search.png`）。
- 选片排序：点「大小」→ 当前这条从 `#21` 变成 `#87` 且仍高亮「播放中」、底部序号标签同时变 `87/100`（`r6_i_sort_size.png`）；点「目录」→ 同目录聚在一起，当前条落在 `#28`（`r6_n_sort_folder.png`）；`force-stop` 重开后排序仍是「大小」，且回到同一条视频的 87/100 位置。这一版点 tab 会把面板收起来再重新拉起（排序要回 Activity 重排、然后重开面板），观感上像闪了一下 —— 第七轮改成就地换序。
- 信息面板：点标题右侧「信息」→ 卡片显示 720×1280 / 0:15 / 3.3 MB / 1.82 Mbps / `avc` / `mp4` / 60 fps / Download / 完整路径 / ID 1354 / URI（`r6_f_info_extractor.png`）；面板内可下滑看到 加入时间 2026-09-08 11:03、当前进度 0:09/0:15、播放速度、循环方式、列表排序（`r6_g_info_scrolled.png`）。
- 本轮踩到的坑：`MediaMetadataRetriever.extractMetadata` 在本机 SDK 的 `android.jar` 里只剩 `int` 键的 @hide 重载，反射按公开文档的 `String` 键取在这台 Android 16 真机上拿不到方法（`method=null`），改成 `MediaExtractor` + `MediaFormat` 的字符串键后一次通过；`compareBy(comparator){...}` 在链式 `thenByDescending` 前推不出元素类型，显式写成 `compareBy<VideoItem, String>`；`ScrollView` 没有公开的 `LayoutParams` 内部类，用父类 `FrameLayout.LayoutParams`。

第七轮（选片面板点排序 tab 改为无感切换）实测：

- 播放中暂停 → 点「61/100 ▾」拉起面板（排序「时间」，当前条 `#61`）→ 点「大小」：面板原地不动，列表直接按大小重排，当前条变成 `#70` 且仍带「播放中」高亮，chip 高亮从「时间」移到「大小」，标题行「共 100 条」不变（`r7_7_sheet_after_tab.png`，点完约 200ms 采样，没有收起也没有重新拉起）。
- 关掉面板后底部序号标签是 `70/100`，和面板里的名次对得上（`r7_6_sheet_before.png` 是换序前，`r7_8_search_then_name.png` 是搜索框输入态）；播放没中断、进度没重置。
- 换序时行内缩略图不闪白：`VideoRepo.peekSmall(id)` 命中缓存就直接画上去，不再先 `setImageDrawable(null)` 等一次异步回调。
- 换序时后面的画面闪动（补修）：原因是 `applySort` 走 `data = / setPosition / markVideoPage`，当前页的封面会先被盖回画面（alpha 0→1）再淡出 120ms，面板上方那截画面就闪一下。改成 `pager.reorder(items, at)`：先取消吸附动画，再一次性把当前页 `alpha` 直接置 0（不用 0 时长动画，它也要下一帧才生效、会露一帧封面），邻居槽位照常重贴。包体仍是 53,720 字节。
- 本轮踩到的坑：`onSort` 回调里想引用正在构造的 `PickerSheet` 局部变量 `s`（Kotlin 报 `unresolved reference 's'`，因为 lambda 属于它自己的初始化表达式），改成走 `sheet?.updateSort(...)`；测试提示：`uiautomator dump` 在功能区 `GONE`（正在播放）时只会导出一个空节点，看到「dump 没内容」先确认界面状态，别误判成面板没打开。

真机测试中发现并修掉的问题：长按松手被同时当成单击导致暂停；旋转时 `applySurfaceSize()` 读到的是旋转前的旧屏幕尺寸；换页时倍速标签被写回常速；`msAt()` 运算符优先级导致进度换算失效；选片面板第一次运行崩在 `AdapterView.addView`（ListView 的行不能自己 addView）以及加权行在竖向 LinearLayout 里写成 `width=0` 导致列表宽度为 0 看不见。测试截图都在 `device-tests/`。

注意：用 `adb install -r` 覆盖安装会杀掉进程，`SharedPreferences` 里来不及落盘的「最后在看哪一条」会丢，看起来像「每次都从第一条开始」。正常 BACK 退出再启动则是正确的。

## 已知限制

- 不裁切的代价是黑边：竖屏视频在横屏里只占中间一条（608×1080），左右留黑；想铺满就得接受字幕/水印被切掉，这里按「信息看全」优先。
- 暂停时功能区是浮在画面之上的（普通播放器做法，横竖屏一致），会压住最底部约 350px；播放中整条收起，一点不占地方。代价是**想拖进度条得先点一下暂停**，播放中没有常驻的进度线可拖。
- 真机验证只覆盖了一台 Android 16 机型，其它厂商/系统版本（尤其是 Android 5.0~10 的老设备）的解码兼容性未测。
- 倍速播放需要 Android 6.0（API 23）及以上；更低版本仍可正常播放，点「倍速」会提示不支持。
- 未实现（按需求明确排除）：音量/亮度手势、后台与锁屏继续播放、投屏、字幕。选片面板只能搜索、排序和跳到某一条，不做增删、多选或自建播放列表。
- 双击快进换来的是单击晚 300ms 生效（要等双击窗口过去），暂停/继续的手感会比之前略钝一点。
- 搜索是内存里的子串匹配（片名或目录名，不区分大小写），不做拼音、模糊和正则；排序只改显示顺序，不会写回媒体库。
- 信息面板里 码率/帧率/旋转 取决于文件本身有没有写这些字段，取不到就显示「未知」；容器格式媒体库不记录，是按文件后缀推断的。
- 只播放 MediaStore 已收录的视频；外接硬盘或未被媒体库索引的目录不会出现。
- 精确 seek 依赖设备硬解能力，部分机器拖动进度条会有 1 秒以内的定位误差。
- 实况照片靠 MediaStore 的 `is_live_photo` / 厂商 `live_photo` 字段识别；字段名随系统定制，认不出来的机型上会继续当普通视频展示（查询失败时自动退回不带这些字段的基础投影，不会崩溃）。
- 元数据只走 `MediaExtractor`：这台 Android 16 真机上 `MediaMetadataRetriever.extractMetadata(String)` 已取不到（反射返回 null），低版本系统上它仍在，但为了只留一条会工作的路径统一用 `MediaExtractor`。
- 支持的格式即系统解码器支持的格式（通常 mp4/mkv/webm 里的 H.264/HEVC + AAC）。不内置任何解码库，这是体积能压在 53 KB 的前提。

## English summary

A Douyin-style vertical-swipe video player for Android that plays only videos already indexed in the device gallery. Single `MediaPlayer` + `SurfaceView`, hand-written vertical pager (no AndroidX, no RecyclerView, no XML, no Compose), no network permission.

Build without Gradle:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup_env.ps1   # one-off toolchain install (~450 MB)
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_apk.ps1   # -> dist\FlickBox.apk
```

Output size: **53,720 bytes** (was 41,432 before the double-tap seek, picker search/sort and video info panel were added — all three are new code, still no dependency, resource or permission). R8 full-mode shrinks `kotlin-stdlib` down to the handful of classes actually used. minSdk 21, targetSdk 34, signed with an auto-generated debug keystore (`storepass android`).

Gestures: swipe vertically to change video, tap to pause/resume, long press for 2x, tap the speed chip to cycle 1.0/1.25/1.5/2.0x. The thin Douyin-style progress line takes a tap to jump, a horizontal drag to scrub, and hands a vertical drag back to the pager so swiping still works there — and the whole control strip is lifted above the system's bottom gesture band (measured from the real `navigationBars`/`systemGestures` inset), so scrubbing no longer triggers the app-switch gesture. Icon badges (play triangle / pause bars) replace the old "paused" text, the outlined 横屏 button toggles orientation, and the "N/total" chip slides up a custom picker sheet with thumbnails, `#index · duration · folder` and the playing row highlighted. That sheet now also searches (one substring box matching either the file name or the folder, with a `matched/total` counter) and re-sorts by time, name, size or folder — four chips, the active one highlighted, and the chosen order is persisted too, so restarting the app keeps it; tapping a chip reorders the list right there — the sheet does not collapse and slide back up, the typed keyword and the filtered rows carry over to the new order, and rows reuse cached thumbnails so nothing flashes white; the pager only relabels its pages (`PagerView.reorder`) instead of re-showing the current page's cover, so the picture behind the sheet does not flicker. Re-sorting keeps the video you are watching, the current position, the counter label and the playback itself, because only the list order changes. Double-tapping the left or right half of the screen seeks back or forward 10 seconds and shows a pill with the jump and the landed position; the price is that a single tap is held for 300 ms before it pauses, and a second tap within that window is read as a seek instead (long press, drags and a pending tap never conflict — starting a swipe or a long press cancels the queued pause). A 信息 chip next to the title opens a per-video panel with resolution (rotation-aware) and the rotation angle, duration, file size, bitrate, codec, container, frame rate, folder, file path, MediaStore id and content URI, the date it entered the media library, plus the live position, speed, repeat mode and list order. Technical note: the metadata comes from `MediaExtractor` + `MediaFormat` string keys, not `MediaMetadataRetriever.extractMetadata(String)` — on the Android 16 test device that reflective overload is genuinely not callable, so the retriever path returned "unknown" for codec, frame rate and bitrate. Fields the platform does not report stay as 未知 rather than being guessed. Every picture is shown at its native aspect ratio — scaled to fit, never cropped, stretched or zoomed — so subtitles and watermarks pinned to the edge of a portrait video stay visible (a 1080x1920 video becomes 608x1080 in landscape, centred with black on the sides); covers use the same `FIT_CENTER` rule so nothing jumps when the first frame lands. In both orientations the video owns the whole screen and the control strip collapses while playing, fading back in only when you pause (like a normal player) — so scrubbing means tapping once to pause first. A chip on the title row cycles the repeat mode: sequential (auto-advance, stop at the end), repeat one, repeat all — persisted in `SharedPreferences`. There is no cap on the list — the 100 shown on the test phone is exactly what is left after dropping 77 live-photo MP4s out of 177 MediaStore rows. Returning from another app re-binds the recreated Surface, so you get picture and not just audio; a video that was playing keeps playing, one you paused stays paused with the badge back. Per-video resume position and the last video you were on are stored in `SharedPreferences`. Playback speed requires API 23+. Verified on a vivo V2359A (Android 16, 1080x2400 notch); only that one device was tested.
