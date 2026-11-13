package com.tms.bmwediag;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.tms.bmwediag.sgbd.SgbdRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** อ่านไฟล์ SGBD จากโฟลเดอร์ที่ผู้ใช้เลือก (Storage Access Framework) */
public class SafFileSource implements SgbdRunner.FileSource {
    private final ContentResolver cr;
    private final Uri tree;
    private Map<String, String> index; // ชื่อไฟล์ตัวเล็ก -> documentId
    private final Map<String, byte[]> cache = new HashMap<>();

    public SafFileSource(ContentResolver cr, Uri tree) {
        this.cr = cr;
        this.tree = tree;
    }

    private synchronized void buildIndex() throws IOException {
        if (index != null) return;
        index = new HashMap<>();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) throw new IOException("อ่านรายชื่อไฟล์ในโฟลเดอร์ SGBD ไม่ได้");
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                if (name != null) index.put(name.toLowerCase(Locale.ROOT), id);
            }
        }
    }

    public int fileCount() throws IOException {
        buildIndex();
        return index.size();
    }

    @Override
    public synchronized byte[] read(String name) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        byte[] c = cache.get(key);
        if (c != null) return c;
        buildIndex();
        String id = index.get(key);
        if (id == null) return null;
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        try (InputStream in = cr.openInputStream(doc)) {
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] d = bo.toByteArray();
            if (cache.size() > 20) cache.clear();
            cache.put(key, d);
            return d;
        }
    }
}
