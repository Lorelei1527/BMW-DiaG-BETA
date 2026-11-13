package com.tms.bmwediag.diag;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** บันทึก datalog เป็น CSV (เปิดใน Excel / TunerPro / MegaLogViewer ได้) */
public class CsvLogger {
    private final Writer w;
    private final long t0 = System.currentTimeMillis();
    private boolean header;
    public int rows;

    public CsvLogger(OutputStream os) throws IOException {
        w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        w.write('﻿'); // BOM ให้ Excel อ่านภาษาไทยถูก
    }

    public synchronized void write(List<DiagSession.Reading> r) throws IOException {
        if (!header) {
            StringBuilder sb = new StringBuilder("time_s");
            for (DiagSession.Reading x : r)
                sb.append(',').append(esc(x.name + (x.unit.isEmpty() ? "" : " [" + x.unit + "]")));
            w.write(sb.append('\n').toString());
            header = true;
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%.3f", (System.currentTimeMillis() - t0) / 1000.0));
        for (DiagSession.Reading x : r) {
            sb.append(',');
            if (x.value != null) sb.append(String.format(Locale.US, "%.4f", x.value));
        }
        w.write(sb.append('\n').toString());
        rows++;
        if (rows % 20 == 0) w.flush();
    }

    private static String esc(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    public synchronized void close() {
        try { w.flush(); w.close(); } catch (IOException ignored) {}
    }
}
