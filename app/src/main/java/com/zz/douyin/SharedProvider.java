package com.zz.douyin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * 把模块下载过的作品记录暴露给 DouyinDownloader。
 *
 * ## 双层校验
 * ```
 * ① 调用方签名与本应用一致  → 放行（最高信任）
 * ② 签名不同但包名在白名单   → 放行（降级）
 * ③ 都不满足                → 返回 null，并在日志里记下包名
 * ```
 *
 * 为什么不是单靠 ①：本模块目前用 debug 签名，与 App 的 release 签名不一致，
 * ① 走不通。等模块改用同一份 keystore 后 ① 自动生效，② 退化成兜底。
 *
 * 为什么包名校验可接受：`getCallingPackage()` 由系统填写，应用无法伪装成别的包名。
 * 泄露的只是下载记录（标题/作者/文件名），不含凭据。
 *
 * ## 只读
 * query 之外的操作一律不支持 —— App 不该往模块的历史里写东西，避免双写冲突。
 */
public final class SharedProvider extends ContentProvider {

    private static final String TAG = "DouyinShared";
    public static final String AUTHORITY = "com.zz.douyin.shared";
    public static final Uri HISTORY_URI = Uri.parse("content://" + AUTHORITY + "/history");

    /** 允许读取的调用方。加新 App 时在这里登记。 */
    private static final List<String> ALLOWED_CALLERS = Arrays.asList(
            "com.knightlsy.douyin.release",   // DouyinDownloader 正式包
            "com.knightlsy.douyin"            // 无后缀构建（debug/本地）
    );

    private static final String[] COLUMNS = {
            "id", "contentId", "title", "author", "coverUrl",
            "contentType", "originalUrl", "downloadTime", "downloadedFiles", "source"
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri,
                        String[] projection,
                        String selection,
                        String[] selectionArgs,
                        String sortOrder) {
        String caller = getCallingPackage();
        String trust = trustLevel(caller);
        if (trust == null) {
            Log.w(TAG, "拒绝未授权的读取: " + caller);
            return null;
        }
        Log.i(TAG, "允许读取共享历史，调用方=" + caller + " 信任等级=" + trust);

        Context context = getContext();
        if (context == null) {
            return null;
        }

        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        List<String> lines = SharedHistoryStore.read(context);
        for (String line : lines) {
            try {
                JSONObject object = new JSONObject(line);
                cursor.addRow(new Object[]{
                        object.optString("id"),
                        object.optString("contentId"),
                        object.optString("title"),
                        object.optString("author"),
                        object.optString("coverUrl"),
                        object.optString("contentType", "VIDEO"),
                        object.optString("originalUrl"),
                        object.optLong("downloadTime", 0L),
                        joinFiles(object.optJSONArray("downloadedFiles")),
                        object.optString("source", "xposed"),
                });
            } catch (Exception error) {
                // 单行坏了跳过，不要让整个历史读不出来
                Log.w(TAG, "跳过一行无法解析的历史记录");
            }
        }
        return cursor;
    }

    /** 数组用换行拼成一个字符串 —— MatrixCursor 只接受基本类型。 */
    private static String joinFiles(JSONArray array) {
        if (array == null || array.length() == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < array.length(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(array.optString(index, ""));
        }
        return builder.toString();
    }

    /** @return 信任等级描述；null 表示拒绝 */
    private String trustLevel(String caller) {
        if (caller == null) {
            return null;
        }
        if (signatureMatches(caller)) {
            return "signature";
        }
        if (ALLOWED_CALLERS.contains(caller)) {
            return "package";
        }
        return null;
    }

    /** 调用方签名是否与本应用完全一致。任何异常都当作不匹配。 */
    private boolean signatureMatches(String caller) {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        try {
            PackageManager manager = context.getPackageManager();
            Signature[] self = signaturesOf(manager, context.getPackageName());
            Signature[] theirs = signaturesOf(manager, caller);
            if (self == null || theirs == null || self.length == 0 || theirs.length == 0) {
                return false;
            }
            return Arrays.equals(self, theirs);
        } catch (Exception error) {
            return false;
        }
    }

    private static Signature[] signaturesOf(PackageManager manager, String packageName)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = manager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) {
                return null;
            }
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        PackageInfo info = manager.getPackageInfo(
                packageName, PackageManager.GET_SIGNATURES);
        return info.signatures;
    }

    /* ── 只读 Provider：其余操作一律拒绝 ── */

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.zz.douyin.history";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
