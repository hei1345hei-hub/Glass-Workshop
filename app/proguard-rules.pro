# Add project specific ProGuard rules here.
# Liquid Glass 渲染用不到反射，默认规则即可。

# LSPosed 通过 java_init.list 按类名反射实例化入口，必须保留。
-keep class com.c17.glass.xposed.** { *; }
