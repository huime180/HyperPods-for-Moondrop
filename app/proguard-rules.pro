# 普通 App（已不再作为 Xposed 模块）：release 现已开启 R8 与资源压缩
# （isMinifyEnabled / isShrinkResources = true，与 HyperPods 的构建配置一致），
# 因此这里的 keep 规则是生效的：协议层与状态层按包全保留。
# 应用只用 Compose / Miuix（纯 Kotlin + 无反射），没有其它需要额外 keep 的入口。
# 原模块专属的两条已删除：hook 入口类的 keep 与框架依赖的 -dontwarn
# （Xposed 模块与对应依赖都已从工程里移除，本应用是普通 App）。
-keep class moe.huime.miuixmoondrop.core.** { *; }
-keep class moe.huime.miuixmoondrop.pods.** { *; }
