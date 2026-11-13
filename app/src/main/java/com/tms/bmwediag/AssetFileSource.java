package com.tms.bmwediag;

import android.content.res.AssetManager;

import com.tms.bmwediag.sgbd.SgbdRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** ไฟล์ SGBD ที่ฝังมาในแอป (assets/sgbd/ ชื่อไฟล์ตัวพิมพ์เล็ก) */
public class AssetFileSource implements SgbdRunner.FileSource {
    private final AssetManager am;

    public AssetFileSource(AssetManager am) { this.am = am; }

    @Override
    public byte[] read(String name) throws IOException {
        try (InputStream in = am.open("sgbd/" + name.toLowerCase(Locale.ROOT))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
    }
}
