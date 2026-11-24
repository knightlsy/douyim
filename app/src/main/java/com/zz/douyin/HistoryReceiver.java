package com.zz.douyin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 抖音进程 → 本应用进程的历史投递通道。
 *
 * ## 为什么用广播
 * 模块的 hook 代码跑在抖音进程里，本应用的文件属于 com.zz.douyin，跨包写不了（Android 沙盒）。
 * 显式广播（setPackage 指向本包）+ 静态注册的 Receiver 是唯一不依赖签名的通路。
 *
 * ## 安全
 * Receiver 设成 exported，但只接受 action = {@link #ACTION} 且包名匹配的请求。
 * 广播里只有下载记录（标题/作者/文件名），不含任何凭据；伪造它最多污染历史列表，
 * 影响面可控。真正的数据出口 {@link SharedProvider} 另有双层校验。
 */
public final class HistoryReceiver extends BroadcastReceiver {

    static final String ACTION = "com.zz.douyin.SHARED_HISTORY";
    static final String EXTRA_JSON = "json";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String json = intent.getStringExtra(EXTRA_JSON);
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        // 广播是在主线程回调的，写盘放到自己的一次性线程里，不阻塞系统广播队列
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                SharedHistoryStore.append(appContext, json);
                Log.i("DouyinShared", "收到模块下载记录");
            } catch (Throwable error) {
                Log.w("DouyinShared", "投递历史失败", error);
            }
        }, "shared-history-write").start();
    }
}
