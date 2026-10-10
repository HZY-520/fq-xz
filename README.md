# 小番茄 (fq-xz)

番茄小说下载器 Android 客户端，基于开源项目
[Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader)
及其独立 Web 前端构建。

- 应用名：**小番茄**
- 包名：`com.fanqie.lico`
- 架构：`arm64-v8a` / `armeabi-v7a`

## 功能

仅保留核心四个页面：

| 页面 | 说明 |
| --- | --- |
| 搜索书籍 | 搜索番茄小说并创建下载任务 |
| 下载任务 | 任务进度、取消与格式选择 |
| 下载历史 | 已完成书籍记录 |
| 设置 | 输出格式等配置项 |
| 关于 | 开发者信息与开源仓库 |

## 实现原理

1. 官方 Android 预编译后端（`libtomato.so`）随 APK 打包，安装时解压到
   `nativeLibraryDir`，首次启动复制到 `filesDir` 并赋予执行权限后作为子进程运行
   （监听 `127.0.0.1:18423`）。
2. 内置本地代理（NanoHTTPD）托管打包进 `assets/webui` 的前端静态资源，
   并把 `/api/`、`/download/`、`/download-zip/` 前缀请求反向代理到本机后端，
   解决 WebView 的跨域与静态资源路径问题。
3. 首次启动需授予「所有文件访问权限」并选择下载目录，目录会写入后端
   `config.yml` 的 `save_path`。

## 构建

```bash
# 需要 JDK 17、Android SDK（platform 34 / build-tools 34）
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

## 开发者

- 开发者：离愁Lico
- 邮箱：2197958514@qq.com
- 仓库：https://github.com/HZY-520/fq-xz

## 免责声明

本应用仅供个人学习、研究与阅读使用，请勿将下载内容用于转载、分发或任何商业用途。