package com.example.pdfsplit;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_PICK_PDF = 1001;
    private static final int REQ_PICK_FOLDER = 1002;
    private static final String PREFS = "dest";
    private static final String KEY_TREE = "tree";
    private static final String EXTERNAL_DOCS = "com.android.externalstorage.documents";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    // معلومات آخر ملف PDF اختاره المستخدم
    private String lastName = "";
    private String lastDir = null; // مسار المجلد نسبةً لجذر التخزين الداخلي، مثل Download/Books
    private String lastError = "";

    private static class Dest {
        boolean media; // true: MediaStore بجانب الملف الأصلي، false: مجلد اختاره المستخدم (SAF)
        String rel;
        Uri parent;
    }

    private static class Pending {
        Uri uri;
        OutputStream out;
        boolean media;
    }

    private final Map<String, Dest> sessions = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);
        applyChrome();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setBlockNetworkLoads(true); // التطبيق يعمل بالكامل بدون إنترنت
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(false); // الصفحة تتبع النظام بنفسها
        }

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pushThemeToPage();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                try {
                    startActivityForResult(intent, REQ_PICK_PDF);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    /* ---------- المظهر: أبيض أو أسود حسب النظام ---------- */

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applyChrome() {
        boolean night = isNight();
        int bg = night ? 0xFF000000 : 0xFFFFFFFF;
        web.setBackgroundColor(bg);
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (night) {
            flags &= ~light;
        } else {
            flags |= light;
        }
        decor.setSystemUiVisibility(flags);
    }

    private void pushThemeToPage() {
        String t = isNight() ? "dark" : "light";
        web.evaluateJavascript("document.documentElement.setAttribute('data-theme','" + t + "')", null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyChrome();
        pushThemeToPage();
    }

    /* ---------- نتائج اختيار الملف والمجلد ---------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_PDF) {
            if (filePathCallback == null) {
                return;
            }
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            if (result != null && result.length > 0) {
                String[] info = resolveSource(result[0]);
                lastName = info[0] == null ? "" : info[0];
                lastDir = info[1];
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            return;
        }

        if (requestCode == REQ_PICK_FOLDER) {
            boolean ok = false;
            String label = "";
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    prefs().edit().putString(KEY_TREE, tree.toString()).apply();
                    label = treeLabel(tree);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
            }
            notifyFolder(ok, label);
        }
    }

    private void notifyFolder(boolean ok, String label) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("label", label);
            web.evaluateJavascript("window.__onFolderPicked && window.__onFolderPicked(" + o.toString() + ")", null);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    /* ---------- تحديد مكان الملف الأصلي ---------- */

    private String queryString(Uri uri, String column) {
        try (Cursor c = getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(column);
                if (i >= 0 && !c.isNull(i)) {
                    return c.getString(i);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String trimSlashes(String s) {
        if (s == null) {
            return null;
        }
        String r = s;
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /** "Download/Books/x.pdf" إلى "Download/Books". */
    private static String parentOf(String relativeFile) {
        int i = relativeFile.lastIndexOf('/');
        return i < 0 ? "" : relativeFile.substring(0, i);
    }

    private static String fromAbsolute(String path) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path != null && path.startsWith(root + "/")) {
            return parentOf(path.substring(root.length() + 1));
        }
        return null;
    }

    private String viaMediaStore(String docId) {
        String idPart = docId;
        int colon = docId.indexOf(':');
        if (colon >= 0) {
            String prefix = docId.substring(0, colon);
            if (!"msf".equals(prefix) && !"document".equals(prefix)) {
                return null;
            }
            idPart = docId.substring(colon + 1);
        }
        try {
            long id = Long.parseLong(idPart);
            Uri u = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id);
            String rel = queryString(u, MediaStore.MediaColumns.RELATIVE_PATH);
            return rel == null ? null : trimSlashes(rel);
        } catch (Exception e) {
            return null;
        }
    }

    /** يرجع {اسم الملف، مجلده أو null إن تعذّر تحديده}. */
    private String[] resolveSource(Uri uri) {
        String name = queryString(uri, OpenableColumns.DISPLAY_NAME);
        String dir = null;
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String auth = uri.getAuthority();
                String docId = DocumentsContract.getDocumentId(uri);
                if (EXTERNAL_DOCS.equals(auth)) {
                    int i = docId.indexOf(':');
                    if (i > 0 && "primary".equals(docId.substring(0, i))) {
                        dir = parentOf(docId.substring(i + 1));
                    }
                } else if (docId.startsWith("raw:")) {
                    dir = fromAbsolute(docId.substring(4));
                }
                if (dir == null) {
                    dir = viaMediaStore(docId);
                }
            }
            if (dir == null) {
                String rel = queryString(uri, MediaStore.MediaColumns.RELATIVE_PATH);
                if (rel != null) {
                    dir = trimSlashes(rel);
                }
            }
            if (dir == null) {
                String data = queryString(uri, "_data");
                if (data != null) {
                    dir = fromAbsolute(data);
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{name, dir};
    }

    /** أندرويد يسمح بإنشاء ملفات PDF عبر MediaStore داخل Download وDocuments فقط. */
    private static boolean mediaAllowed(String dir) {
        return dir != null && (dir.equals("Download") || dir.equals("Documents")
                || dir.startsWith("Download/") || dir.startsWith("Documents/"));
    }

    /* ---------- المجلد الذي يختاره المستخدم ---------- */

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private Uri savedTree() {
        String s = prefs().getString(KEY_TREE, null);
        return s == null ? null : Uri.parse(s);
    }

    private boolean hasTreePermission(Uri tree) {
        List<UriPermission> perms = getContentResolver().getPersistedUriPermissions();
        for (UriPermission p : perms) {
            if (p.getUri().equals(tree) && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private String treeLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        if (id.startsWith("primary:")) {
            String rest = id.substring("primary:".length());
            return rest.isEmpty() ? "Internal storage" : rest;
        }
        return id.replace(':', '/');
    }

    private Uri findOrCreateDir(Uri tree, Uri parentDoc, String name) throws Exception {
        ContentResolver r = getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getDocumentId(parentDoc));
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor c = r.query(children, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        }
        return DocumentsContract.createDocument(r, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, name);
    }

    /* ---------- الجسر بين الصفحة وأندرويد ---------- */

    public class Bridge {

        /** لغة النظام (رمز من حرفين مثل ar أو en) لتختار الواجهة العربية أو الإنجليزية. */
        @JavascriptInterface
        public String systemLanguage() {
            try {
                return getResources().getConfiguration().getLocales().get(0).getLanguage();
            } catch (Exception e) {
                return "en";
            }
        }

        /** معلومات الملف المختار: الاسم ومجلده وهل يمكن الحفظ بجانبه مباشرة. */
        @JavascriptInterface
        public String sourceInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("name", lastName);
                o.put("dir", lastDir == null ? "" : lastDir);
                o.put("canNext", mediaAllowed(lastDir));
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** المجلد المحفوظ من المرة السابقة إن بقيت صلاحيته. */
        @JavascriptInterface
        public String currentFolder() {
            try {
                Uri t = savedTree();
                if (t == null || !hasTreePermission(t)) {
                    return "";
                }
                JSONObject o = new JSONObject();
                o.put("label", treeLabel(t));
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** يفتح منتقي المجلدات، والنتيجة تصل إلى window.__onFolderPicked. */
        @JavascriptInterface
        public void pickFolder(final String hintDir) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    try {
                        Uri initial = null;
                        if (hintDir != null && !hintDir.isEmpty()) {
                            initial = DocumentsContract.buildDocumentUri(EXTERNAL_DOCS, "primary:" + hintDir);
                        } else {
                            initial = savedTree();
                        }
                        if (initial != null) {
                            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
                        }
                        startActivityForResult(i, REQ_PICK_FOLDER);
                    } catch (Exception e) {
                        notifyFolder(false, "");
                    }
                }
            });
        }

        /** mode: "source" بجانب الملف الأصلي، أو "tree" في المجلد المختار. يرجع معرّف الجلسة أو "". */
        @JavascriptInterface
        public String openSession(String mode, String sub) {
            lastError = "";
            try {
                String clean = sub == null ? "" : sub.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
                Dest d = new Dest();
                if ("source".equals(mode)) {
                    if (!mediaAllowed(lastDir)) {
                        lastError = getString(R.string.err_no_next);
                        return "";
                    }
                    d.media = true;
                    d.rel = clean.isEmpty() ? lastDir : lastDir + "/" + clean;
                } else {
                    Uri tree = savedTree();
                    if (tree == null || !hasTreePermission(tree)) {
                        lastError = getString(R.string.err_no_tree);
                        return "";
                    }
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                            DocumentsContract.getTreeDocumentId(tree));
                    if (!clean.isEmpty()) {
                        parent = findOrCreateDir(tree, parent, clean);
                    }
                    if (parent == null) {
                        lastError = getString(R.string.err_mkdir);
                        return "";
                    }
                    d.media = false;
                    d.parent = parent;
                }
                String id = UUID.randomUUID().toString();
                synchronized (sessions) {
                    sessions.put(id, d);
                }
                return id;
            } catch (Exception e) {
                lastError = getString(R.string.err_prepare, String.valueOf(e.getMessage()));
                return "";
            }
        }

        @JavascriptInterface
        public void closeSession(String sid) {
            synchronized (sessions) {
                sessions.remove(sid);
            }
        }

        @JavascriptInterface
        public String lastError() {
            return lastError;
        }

        @JavascriptInterface
        public String begin(String sid, String name) {
            Dest d;
            synchronized (sessions) {
                d = sessions.get(sid);
            }
            if (d == null) {
                return "";
            }
            try {
                ContentResolver r = getContentResolver();
                Uri uri;
                if (d.media) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, d.rel);
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = r.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                } else {
                    uri = DocumentsContract.createDocument(r, d.parent, "application/pdf", name);
                }
                if (uri == null) {
                    lastError = getString(R.string.err_create_file, name);
                    return "";
                }
                OutputStream out = r.openOutputStream(uri);
                if (out == null) {
                    lastError = getString(R.string.err_open_file, name);
                    return "";
                }
                Pending p = new Pending();
                p.uri = uri;
                p.out = out;
                p.media = d.media;
                String id = UUID.randomUUID().toString();
                synchronized (pending) {
                    pending.put(id, p);
                }
                return id;
            } catch (Exception e) {
                lastError = getString(R.string.err_create_file_detail, name, String.valueOf(e.getMessage()));
                return "";
            }
        }

        @JavascriptInterface
        public boolean append(String id, String base64) {
            Pending p;
            synchronized (pending) {
                p = pending.get(id);
            }
            if (p == null) {
                return false;
            }
            try {
                p.out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean end(String id) {
            Pending p;
            synchronized (pending) {
                p = pending.remove(id);
            }
            if (p == null) {
                return false;
            }
            try {
                p.out.flush();
                p.out.close();
                if (p.media) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(p.uri, v, null, null);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void abort(String id) {
            Pending p;
            synchronized (pending) {
                p = pending.remove(id);
            }
            if (p == null) {
                return;
            }
            try {
                p.out.close();
            } catch (Exception ignored) {
            }
            try {
                if (p.media) {
                    getContentResolver().delete(p.uri, null, null);
                } else {
                    DocumentsContract.deleteDocument(getContentResolver(), p.uri);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
