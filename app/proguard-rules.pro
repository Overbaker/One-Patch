# 应用入口（Manifest 引用），保持类名以避免 R8 重命名后 Manifest 无法解析
-keep class com.ccg.screenblocker.MainActivity { *; }
-keep class com.ccg.screenblocker.service.OverlayService { *; }

# 自定义 View 保留构造方法（XML inflate 时会反射调用）
-keep class com.ccg.screenblocker.ui.OverlayView {
    <init>(android.content.Context);
    <init>(android.content.Context, android.util.AttributeSet);
    <init>(android.content.Context, android.util.AttributeSet, int);
}
