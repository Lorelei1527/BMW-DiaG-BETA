package com.tms.bmwediag.diag;

import com.tms.bmwediag.io.Hex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * แยกโค้ด fault จากคำตอบ DS2 (A0 ...) ของโมดูลตัวถัง
 * รูปแบบแต่ละโมดูลต่างกัน จึงลองหาโครงสร้าง [ตัวนับ?][รหัส + ข้อมูล] x N
 * ที่ทำให้ "รหัส" ทุกตัวตรงกับตารางโค้ดของ SGBD มากที่สุด
 */
public final class Ds2Faults {
    private Ds2Faults() {}

    public static final class Layout {
        public int header, size, count, score;
        public List<Integer> codes = new ArrayList<>();
        public List<byte[]> entries = new ArrayList<>();
    }

    /** หาโครงสร้างที่น่าจะถูกต้องที่สุด (null = เดาไม่ได้) */
    public static Layout guess(byte[] data, Map<Integer, String> table) {
        Layout best = null;
        if (data.length == 0) return null;
        for (int h = 0; h <= 2 && h < data.length; h++) {
            for (int s = 1; s <= 16; s++) {
                int body = data.length - h;
                if (body < s || body % s != 0) continue;
                int n = body / s;
                Layout l = new Layout();
                l.header = h;
                l.size = s;
                boolean ok = true;
                for (int i = 0; i < n; i++) {
                    int off = h + i * s;
                    boolean zero = true;
                    for (int j = 0; j < s; j++) if (data[off + j] != 0) zero = false;
                    if (zero) continue; // ช่องว่าง
                    int code = data[off] & 0xFF;
                    if (!table.containsKey(code)) { ok = false; break; }
                    byte[] e = new byte[s];
                    System.arraycopy(data, off, e, 0, s);
                    l.codes.add(code);
                    l.entries.add(e);
                }
                if (!ok || l.codes.isEmpty()) continue;
                l.count = l.codes.size();
                int score = 10;
                if (h >= 1 && (data[0] & 0xFF) == l.count) score += 30;        // ไบต์แรก = จำนวนโค้ด
                if (h >= 1 && (data[0] & 0xFF) == n) score += 20;              // ไบต์แรก = จำนวนช่อง
                if (s >= 2 && s <= 6) score += 8;                               // ขนาด entry ที่พบบ่อย
                if (n == 1 && s == body && s > 6) score -= 15;                  // ทั้งก้อนเป็น 1 รายการ: ไม่น่าเชื่อ
                score += Math.min(l.count, 5);
                l.score = score;
                if (best == null || l.score > best.score) best = l;
            }
        }
        return best;
    }

    /** แปลงคำตอบหลายชุดเป็นรายการโค้ดไทย (ไม่ซ้ำ) */
    public static List<Dtc.Code> decode(List<byte[]> responses, Map<Integer, String> table) {
        Map<Integer, Dtc.Code> found = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();
        boolean anyData = false, unknown = false;
        for (byte[] r : responses) {
            if (r.length <= 1) continue;
            byte[] d = new byte[r.length - 1];
            System.arraycopy(r, 1, d, 0, d.length);
            boolean allZero = true;
            for (byte b : d) if (b != 0) allZero = false;
            if (allZero) continue;
            anyData = true;
            raw.append(Hex.of(r)).append('\n');
            if (d.length == 1) continue; // ตัวนับอย่างเดียว
            Layout l = table.isEmpty() ? null : guess(d, table);
            if (l == null) { unknown = true; continue; }
            for (int i = 0; i < l.codes.size(); i++) {
                int c = l.codes.get(i);
                if (found.containsKey(c)) continue;
                found.put(c, new Dtc.Code(String.format("%02X", c), table.get(c) + "   [" + Hex.of(l.entries.get(i)) + "]"));
            }
        }
        List<Dtc.Code> out = new ArrayList<>(found.values());
        if (out.isEmpty() && !anyData) out.add(new Dtc.Code("✓", "ไม่มีโค้ด"));
        else if (out.isEmpty() || unknown)
            out.add(new Dtc.Code("ข้อมูลดิบ", raw.toString().trim()
                    + "\n   (แปลโครงสร้างไม่ได้ทั้งหมด — ส่งภาพนี้ให้ผู้พัฒนาเพื่อปรับ)"));
        return out;
    }
}
