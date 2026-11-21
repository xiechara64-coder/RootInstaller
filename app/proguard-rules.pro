# 自定义混淆规则。
#
# 本应用默认关闭了混淆（minifyEnabled false），所以这里其实用不到。
# 如果你出于体积考虑要打开混淆，请保留下面这些规则，
# 否则 AndroidManifest 里按名字引用的四大组件会被混淆掉，应用直接起不来。

# 四大组件不能混淆
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# 清单里以字符串形式引用的类名保留
-keep class com.dsh.rootinstaller.MainActivity { *; }
-keep class com.dsh.rootinstaller.DetailsActivity { *; }
-keep class com.dsh.rootinstaller.FilePickerActivity { *; }
-keep class com.dsh.rootinstaller.InstallService { *; }
-keep class com.dsh.rootinstaller.InstallReceiver { *; }

# 反射用到的字段名（minSdkVersion / targetSdkVersion / installLocation）
# 注意：这些是 android.content.pm.ApplicationInfo 的字段，属于系统类，不会被混淆，
# 但如果将来改成反射自己的类，就要在这里加 -keep。
