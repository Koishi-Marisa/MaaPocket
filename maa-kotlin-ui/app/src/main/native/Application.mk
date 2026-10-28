# Application.mk —— 仅 arm64-v8a（项目 jniLibs 只含 arm64-v8a）
# APP_PLATFORM 需 >=26（AImage_getHardwareBuffer 等 API 26+），对齐 targetSdk33
APP_ABI := arm64-v8a
APP_PLATFORM := android-33
APP_STL := c++_shared
APP_CPPFLAGS := -std=c++17