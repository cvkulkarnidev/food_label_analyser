# PaddleOCR loads model/runtime types through JNI and ONNX Runtime.
-keep class com.paddle.ocr.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
