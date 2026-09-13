# 模块自身混淆与否不影响 hook（hook 目标全部以字符串名引用）。
-dontwarn io.github.libxposed.**
-keep class moe.chenxy.hyperpods.hook.XposedEntry { *; }
-keep class moe.chenxy.hyperpods.core.** { *; }
-keep class moe.chenxy.hyperpods.pods.** { *; }
