# 第三方依赖与模型

应用源码采用 MIT。第三方依赖和模型按各自条款使用。版本、下载来源与 SHA-256 见 [通用模型](models.lock.json)、[相近特征模型](visual-model.lock.json)和[画面描述模型](description-model.lock.json)。

| 组件 | 来源与许可 |
| --- | --- |
| AndroidX、Jetpack Compose、WorkManager、Kotlin、Gradle | Apache-2.0。版本见 Gradle 构建文件。 |
| ONNX Runtime Android | [Microsoft，MIT](https://github.com/microsoft/onnxruntime/blob/v1.22.0/LICENSE) |
| PP-OCRv6 tiny | [PaddleOCR，Apache-2.0](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE)。随包使用官方检测和识别 ONNX。 |
| BGE-small-zh-v1.5 | [BAAI，MIT](https://huggingface.co/BAAI/bge-small-zh-v1.5)。使用 Xenova INT8 ONNX 转换。 |
| MobileNetV4 Conv Small | [timm，Apache-2.0](https://huggingface.co/timm/mobilenetv4_conv_small.e2400_r224_in1k)。使用 onnx-community 的图，提取分类层前的特征。 |
| Chinese-CLIP RN50 | [OFA-Sys 权重，Apache-2.0](https://huggingface.co/OFA-Sys/chinese-clip-rn50)。拾图将图像与文字编码器分别导出为 ONNX 并量化。另附 [OFA-Sys 代码的 MIT 声明](https://github.com/OFA-Sys/Chinese-CLIP/blob/master/MIT-LICENSE.txt)。 |
| OpenCC TSCharacters | [BYVoid/OpenCC，Apache-2.0](https://github.com/BYVoid/OpenCC/blob/ver.1.1.9/data/dictionary/TSCharacters.txt)。使用原始字符表做检索归一化。 |

随包声明见 `app/src/main/resources/`。设备测试素材来源见对应测试资源目录。
