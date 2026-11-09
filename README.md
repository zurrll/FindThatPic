# 拾图 · FindThatPic

手机里存了很多截图和照片，记得内容，却想不起存在哪。拾图在本机把图片变成可以搜索的图库。

拾图完全离线运行，应用无联网权限，原图只读。

[0.1.0 发布页](https://github.com/zurrll/FindThatPic/releases/tag/v0.1.0) · Android 10+ · ARM64

## 你想找哪一张？

- **记得图片里有的文字？图片的文件名？** 搜你还记得的词，比如「取件码」。近义词也能找哦。
- **手头有一张图片，想找类似的图片？** 从一张照片找回同一处风景的其他照片，或找出保存过的不同版本。
- **记得图片的画面但是没有用来参考的图？** 那就描述出来。普通版支持搜「一只橘猫」「山上俯瞰城市」这样的画面描述。

<p>
  <img src="docs/screenshots/text-search.png" alt="文字搜索取件码，找到两张包裹通知" width="230">
  <img src="docs/screenshots/similar.png" alt="用一张山景照片找出同一片风景的另外两张照片" width="230">
  <img src="docs/screenshots/guide.png" alt="操作指引，用红圈标出补全信息的入口" width="230">
</p>

在结果里可以继续找相近、用系统相册打开或分享当前图片。不想看到的目录可以排除。

## 两版怎么选

|  | 轻量版 | 普通版 |
| --- | --- | --- |
| 搜图片文字、近义词和文件名 | 支持 | 支持 |
| 用一张图找相近 | 支持 | 支持 |
| 用文字描述找画面 | — | 支持 |
| ARM64 安装包约 | 77 MiB | 151 MiB（0.1.1 测试版） |

两版的界面和基本操作相同。只搜截图文字、文件名或相近图片，轻量版就够用。普通版额外保存画面向量，补全画面信息也会花时间。

0.1.0 轻量版 APK 在发布页。0.1.1 普通版测试包使用 RN50，覆盖升级后只需补全画面描述。

## 大图库上的性能

准备图片时按需解码，分阶段处理，支持暂停、继续和复用已有结果。首次补全的时间取决于图片数量和内容，字多、长图或需要补看的图片会更慢。

一加 Ace 5 上的 0.1.0 使用反馈可供参考。

- **准备信息**每张约 600～800 ms。普通版如果同时补全画面信息，每张再花约 250 ms。第一次准备大量图片时，这部分耗时会累积。
- **单次搜索**通常一百多到两百多毫秒，文字含义搜索（BGE）和画面描述搜索（Chinese-CLIP）都在这个范围。

「图库设置 → 性能报告」可看批量准备和单次查询的耗时，单图的「技术详情 → OCR」可查看检测推理和识别推理的耗时。

首次可以先补全 100 张，再试着搜索。需要时从「设置 → 操作指引」重看。

## 从源码构建

需要 JDK 17、Android SDK 36、Build Tools 35.0.0 和 Python 3.9+。设置 `ANDROID_HOME`，或在 `local.properties` 里填写 `sdk.dir`。

```sh
python3 -m pip install -r scripts/model-requirements.txt
python3 scripts/fetch_models.py --edition standard --description-assets /path/to/rn50-assets
./gradlew :app:assembleLiteRelease :app:assembleStandardRelease
```

普通版测试构建需要 [画面模型清单](description-model.lock.json) 对应的 RN50 资产目录。已准备资产时可省略 `--description-assets`。只构建轻量版时，用 `--edition lite` 和 `:app:assembleLiteRelease`。APK 位于 `app/build/outputs/apk/<版本>/release/`。

[开发与设计](docs/DEVELOPMENT.md) · [第三方许可](THIRD_PARTY_NOTICES.md) · [源码许可](LICENSE)
