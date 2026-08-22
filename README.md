# 课程表D

中山大学本科教务课表的 Android 客户端：在应用内登录教务，把课表和考试下载到手机，离线查看、手动改课、分享给同学，并支持桌面小组件和上课提醒。

开源地址：<https://github.com/pipidu/sysukcb>

## 功能

- WebView 登录中山大学教务（CAS），会话只存在本机；重新登录会清掉 WebView 历史和 Cookie
- 导入课表与考试；「全部导入」覆盖教务当前学期前后各 8 个学期（含已公布的未来课表）
- 登录 / 导入成功后回到课表页
- 周视图课表：点顶栏周数选周、左右滑动切周，也可点箭头或学年菜单
- 点课程看详情；三点菜单进入编辑模式后才能改课、点空白加课
- 考试列表、考试周筛选、考试倒计时
- 导出 / 导入 JSON，方便同学互导
- 今日课程、本周课表桌面小组件
- 上课与考试通知，主题色可改（默认中大红）
- 「我的」底部有关于页

数据全部存在本地 SQLite（Room），不经过第三方服务器。

## 环境

- Android Studio 或命令行 Gradle
- JDK 17
- Android SDK（compileSdk 35，minSdk 26）
- 在项目根目录创建 `local.properties`，写入本机 SDK 路径，例如：

```
sdk.dir=C:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
```

`local.properties` 已在 `.gitignore` 中，不要提交。

## 构建

Debug：

```bat
gradlew.bat :app:assembleDebug
```

Debug 包名是 `cn.sysu.kcb.debug`，产物在 `app/build/outputs/apk/debug/`。

有设备时：

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Release（包名 `cn.sysu.kcb`）：

```bat
gradlew.bat :app:assembleRelease
```

签名密钥只放在本机：根目录 `keystore.properties` + `keystore/kcb-release.jks`（均已 gitignore）。没有这两份文件时，release 产物会是未签名包。

## 使用

1. 打开应用，到「我的」登录教务（完成学校 CAS 验证即可）。
2. 登录成功后会自动导入课表并回到课表页；也可手动点「导入选中学期」或「全部导入」。
3. 课表页可点周数、左右滑动或用箭头切换教学周；学年在顶栏菜单里选。
4. 需要改课时，打开右上角菜单进入编辑模式。
5. 关于与开源地址在「我的」最底部。

## 技术栈

Kotlin、Jetpack Compose、Material 3、Room、Retrofit、DataStore、Glance 小组件、AlarmManager。

## 隐私

- 教务 Cookie 只保存在本机加密存储中
- 仓库不包含抓包文件、账号、Cookie 或签名密钥
- 请勿把登录凭证、HAR、keystore 或含个人信息的截图提交到 git
