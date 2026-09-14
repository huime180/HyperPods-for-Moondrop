# 普通 App（已不再作为 Xposed 模块）：release 目前未开启混淆（isMinifyEnabled = false），
# 这里只留下协议层与状态层的 keep 规则，将来开启混淆时不需要重新排查。
# 原模块专属的两条已删除：hook 入口类的 keep 与框架依赖的 -dontwarn
# （Xposed 模块与对应依赖都已从工程里移除，本应用是普通 App）。
-keep class moe.chenxy.hyperpods.core.** { *; }
-keep class moe.chenxy.hyperpods.pods.** { *; }
