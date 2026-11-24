-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keepclassmembers class com.zz.douyin.hook.** { *; }

# 跨进程共享组件：Provider 的入口方法名、Receiver 的 onReceive 必须原样保留。
# AGP 一般会为 manifest 中声明的组件自动生成 keep 规则，但那条保证随 AGP 版本变化，
# 而这几个类是给**别的应用**调用的 —— 被改名或剥掉就是「已安装但读不到数据」，
# 排查起来毫无线索。显式写死，不依赖工具的隐式行为。
-keep class com.zz.douyin.SharedProvider { *; }
-keep class com.zz.douyin.HistoryReceiver { *; }
-keep class com.zz.douyin.SharedHistoryStore { *; }
