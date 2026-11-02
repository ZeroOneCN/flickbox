# R8 配置：只保留框架会反射/按名字实例化的东西，其余全部裁剪。
-ignorewarnings
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# Activity 由系统按类名实例化
-keep class com.pd.player.MainActivity { <init>(); }

# View 由代码构造，不需要 keep；此处只保留可能的 XML 入口（本项目不使用 XML）
-dontobfuscate

# 进一步压缩：把未使用的 stdlib 交给 R8 tree-shaking，不需要显式 keep
-repackageclasses ''
