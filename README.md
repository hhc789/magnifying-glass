# Magnify

Android 屏幕放大镜应用。通过悬浮按钮在任何应用中实时放大屏幕内容，无需 root 权限。

## 功能

- **悬浮按钮** — 可拖动的悬浮按钮，覆盖在所有应用之上，一键开关放大
- **截图放大模式** — 自动截取当前屏幕并以高倍率显示，支持拖拽平移浏览
- **多屏支持** — 自动检测所有显示器（包括模拟器的虚拟屏幕），每个屏幕独立放置悬浮按钮
- **多重截图策略** — 依次尝试 root screencap、SurfaceControl 反射、AccessibilityService.takeScreenshot、MediaProjection，确保在各种设备上都能工作
- **无 root 兼容** — 优先使用无障碍服务 API，root 仅作为可选优化

## 系统要求

- Android 9.0 (API 28) 及以上

## 所需权限

| 权限 | 用途 |
|------|------|
| 无障碍服务 | 检测窗口切换、获取截图 |
| 悬浮窗 | 显示悬浮按钮 |
| 屏幕截图 (MediaProjection) | 截图回退方案 |

## 构建

使用 Android Studio 或命令行构建：

```bash
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/`

## 使用说明

1. 打开应用，按引导授予**悬浮窗权限**和**屏幕截图权限**
2. 前往 **设置 → 无障碍 → Magnify** 开启无障碍服务
3. 屏幕上出现放大镜悬浮按钮，点击即可放大当前屏幕
4. 放大后拖拽平移查看，点击任意位置退出

## 许可证

MIT
