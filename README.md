# 拾图 · FindThatPic

Android 离线图片搜索。用截图里的字、文件名、画面描述或一张参考图找回手机里的图片。支持 Android 10 及以上的 ARM64 设备。

| 功能 | 轻量版 | 普通版 |
| --- | :---: | :---: |
| Paddle OCR、文字与含义搜索、选图找相近 | ✓ | ✓ |
| 技术详情、性能报告、批量标记与导出 | ✓ | ✓ |
| 用文字描述找画面 |  | ✓ |

[下载 0.0.1 APK](https://github.com/zurrll/FindThatPic/releases/tag/v0.0.1)。普通版沿用旧包名，同签名可覆盖升级并保留索引。轻量版可与普通版并存，分别管理索引和标记。

## 使用

1. 授权照片访问，点首页「补全搜索信息」。可选处理范围、暂停和继续，已完成的结果会复用。
2. 搜截图文字、近义表达或文件名。在普通版也可以切换到「描述找图」。打开任意图片可点「找相近」，并从结果继续查找。
3. 用「目录」和「筛选」缩小范围。在设置中可排除目录或清除文字识别记录。

单图的「更多 → 技术详情」显示识别记录与匹配依据。性能报告在「设置 → 测试与维护」。需要反馈问题时，可批量标记和导出选中图片，填一段可选说明。

应用无联网权限，原图只读，索引留在本机。导出由用户主动触发，ZIP 可包含原图、路径、识别文字和诊断信息。应用只读取系统收录且已授权的图片。相近和画面描述提供候选，复杂画面、小字和没有匹配目标时都可能排错。

## 构建

需要 JDK 17、Android SDK 36、Build Tools 35.0.0 和 Python 3.9+。配置 `ANDROID_HOME` 或在 `local.properties` 中设置 `sdk.dir`。

```sh
python3 -m pip install -r scripts/model-requirements.txt
python3 scripts/fetch_models.py --edition standard
./gradlew :app:assembleLiteRelease :app:assembleStandardRelease
```

只构建轻量版可用 `--edition lite` 和 `:app:assembleLiteRelease`。APK 位于 `app/build/outputs/apk/<版本>/release/`。当前私有测试 APK 用开发签名。

[开发与设计](docs/DEVELOPMENT.md) · [模型来源与许可](THIRD_PARTY_NOTICES.md) · [MIT 源码许可](LICENSE)
