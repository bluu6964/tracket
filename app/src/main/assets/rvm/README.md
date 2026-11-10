The cutout model is downloaded by the app the first time Cutout is used on a video
(see engine/ModelDownloader.kt, URL + optional SHA-256 are set there) and kept in the app's files folder.

Optional: to ship it inside the APK instead, put the file here with exactly this name:

    rvm_mobilenetv3_fp32.onnx

Note: RobustVideoMatting is GPL-3.0 licensed; check this before publishing the app.
