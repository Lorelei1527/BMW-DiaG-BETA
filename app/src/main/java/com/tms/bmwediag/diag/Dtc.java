package com.tms.bmwediag.diag;

import com.tms.bmwediag.io.Hex;

import java.util.ArrayList;
import java.util.List;

/** ถอดรหัสโค้ด fault: OBD2 (Pxxxx), KWP2000 BMW (0x58), DS2 (แสดงดิบ) */
public final class Dtc {
    private Dtc() {}

    public final static class Code {
        public final String code, text;
        public Code(String code, String text) { this.code = code; this.text = text; }
        @Override public String toString() { return code + (text.isEmpty() ? "" : "  " + text); }
    }

    public static String obdCode(int a, int b) {
        char[] sys = {'P', 'C', 'B', 'U'};
        return String.format("%c%01X%01X%02X", sys[(a >> 6) & 3], (a >> 4) & 3, a & 0xF, b);
    }

    /** แยกโค้ดจากเฟรม 43 ... (K-line: ไม่มี count, CAN: มี count หลัง 43) */
    public static List<Code> parseObd(List<byte[]> frames, boolean can) {
        return parseObd(frames, can, "");
    }

    /** suffix = สถานะที่ต่อท้าย เช่น "(รอยืนยัน)" สำหรับโหมด 07 */
    public static List<Code> parseObd(List<byte[]> frames, boolean can, String suffix) {
        List<Code> out = new ArrayList<>();
        for (byte[] f : frames) {
            if (f.length < 1) continue;
            int i = 1;
            if (can && f.length % 2 == 0) i = 2; // 43 NN [AB]...
            for (; i + 1 < f.length; i += 2) {
                int a = f[i] & 0xFF, b = f[i + 1] & 0xFF;
                if (a == 0 && b == 0) continue;
                String c = obdCode(a, b);
                String t = DtcThai.describe(c);
                out.add(new Code(c, t + suffix));
            }
        }
        return out;
    }

    /** KWP2000 ReadDTCByStatus: 58 NN [HI LO STATUS]*NN */
    public static List<Code> parseKwp(byte[] r) {
        List<Code> out = new ArrayList<>();
        if (r.length < 2) return out;
        int n = r[1] & 0xFF;
        for (int i = 0; i < n && 2 + i * 3 + 2 < r.length; i++) {
            int hi = r[2 + i * 3] & 0xFF, lo = r[3 + i * 3] & 0xFF, st = r[4 + i * 3] & 0xFF;
            String status = ((st & 0x60) == 0x20 ? "ปัจจุบัน" : (st & 0x60) == 0x60 ? "ปัจจุบัน+บันทึก" : "บันทึกไว้")
                    + ((st & 0x80) != 0 ? ", ไฟเตือนติด" : "");
            out.add(new Code(String.format("%02X%02X", hi, lo),
                    String.format("(%04d dec) status 0x%02X %s", (hi << 8) | lo, st, status)));
        }
        return out;
    }

    /** DS2: โครงสร้างต่างกันทุกโมดูล — ถ้ากำหนดขนาด entry + ตารางข้อความใน JSON จะแปลให้ ไม่งั้นแสดงดิบ */
    public static List<Code> parseDs2(byte[] r, int header, int entrySize, java.util.Map<Integer, String> texts) {
        List<Code> out = new ArrayList<>();
        if (entrySize <= 0 || r.length <= header) {
            if (r.length <= 1) { out.add(new Code("✓", "ไม่มีโค้ด")); return out; }
            out.add(new Code("ข้อมูลดิบ", Hex.of(r) + "\n   (ยังไม่มีตารางแปลโค้ดของโมดูลนี้)"));
            return out;
        }
        for (int i = header; i + entrySize <= r.length; i += entrySize) {
            boolean empty = true;
            for (int j = 0; j < entrySize; j++) if (r[i + j] != 0) empty = false;
            if (empty) continue;
            int code = r[i] & 0xFF;
            String t = texts != null ? texts.get(code) : null;
            out.add(new Code(String.format("%02X", code), (t != null ? t : "ไม่มีคำแปล") + "   [" + Hex.of(r, i, entrySize) + "]"));
        }
        if (out.isEmpty()) out.add(new Code("✓", "ไม่มีโค้ด"));
        return out;
    }

    public static List<Code> parseDs2(byte[] r, int header, int entrySize) { return parseDs2(r, header, entrySize, null); }
}
