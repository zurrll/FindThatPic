# 拾图 · FindThatPic

手机里存了很多截图和照片，记得内容，却想不起存在哪。拾图在本机把图片变成可以搜索的图库。

拾图完全离线运行，应用无联网权限，原图只读。

Android 10+ · ARM64 · 安装包约 151 MiB

## 你想找哪一张？

- **记得图片里有的文字？图片的文件名？** 搜你还记得的词，比如「取件码」。近义词也能找哦。
- **手头有一张图片，想找类似的图片？** 从一张照片找回同一处风景的其他照片，或找出保存过的不同版本。
- **记得图片的画面但是没有用来参考的图？** 那就描述出来。试试「一只橘猫」或「山上俯瞰城市」。

<p>
  <img src="docs/screenshots/text-search.png" alt="文字搜索取件码，找到两张包裹通知" width="230">
  <img src="docs/screenshots/similar.png" alt="用一张山景照片找出同一片风景的另外两张照片" width="230">
  <img src="docs/screenshots/guide.png" alt="操作指引，用红圈标出补全信息的入口" width="230">
</p>

在结果里可以继续找相近、用系统相册打开或分享当前图片。不想看到的目录可以排除。

## 大图库上的性能

准备图片时按需解码，分阶段处理，支持暂停、继续和复用已有结果。首次补全的时间取决于图片数量和内容，字多、长图或需要补看的图片会更慢。

「图库设置 → 性能报告」可看批量准备和单次查询的耗时，单图的「技术详情 → OCR」可查看检测推理和识别推理的耗时。

首次可以先补全 100 张，再试着搜索。需要时从「设置 → 操作指引」重看。

## 从源码构建

需要 JDK 17、Android SDK 36、Build Tools 35.0.0 和 Python 3.9+。设置 `ANDROID_HOME`，或在 `local.properties` 里填写 `sdk.dir`。

```sh
python3 -m pip install -r scripts/model-requirements.txt
python3 scripts/fetch_models.py --description-assets /path/to/rn50-assets
./gradlew :app:assembleRelease
```

构建需要 [画面模型清单](description-model.lock.json) 对应的 RN50 资产目录。已准备资产时可省略 `--description-assets`。APK 位于 `app/build/outputs/apk/release/`。

[开发与设计](docs/DEVELOPMENT.md) · [第三方许可](THIRD_PARTY_NOTICES.md) · [源码许可](LICENSE)
