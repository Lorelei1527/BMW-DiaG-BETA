package com.tms.bmwediag.diag;

import com.tms.bmwediag.io.Hex;

import java.util.ArrayList;
import java.util.List;

/**
 * ถอดคำตอบ IDENT ของ DS2 (คำสั่ง 00) เป็นข้อมูลอ่านง่าย
 * รูปแบบที่พบใน E46: A0 [BMW-Nr 4 ไบต์ BCD] [HW] [Coding idx] [Diag idx] [Bus idx] [สัปดาห์ BCD] [ปี BCD] [ผู้ผลิต] [SW]
 * บางกล่อง (เช่น EGS) ตอบเป็นตัวอักษร ASCII ทั้งหมด
 */
public final class Ds2Ident {
    private Ds2Ident() {}

    static int bcd(int b) { return ((b >> 4) & 0xF) * 10 + (b & 0xF); }

    static boolean isBcd(byte[] r, int off, int len) {
        for (int i = off; i < off + len && i < r.length; i++) {
            int b = r[i] & 0xFF;
            if ((b & 0x0F) > 9 || ((b >> 4) & 0x0F) > 9) return false;
        }
        return true;
    }

    /** เลขชิ้นส่วน BMW 7 หลักจาก 4 ไบต์ BCD (ตัดหลักแรกที่เป็นตัวบอกประเภท) */
    public static String partNumber(byte[] r) {
        if (r.length < 5) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) sb.append(String.format("%02X", r[i] & 0xFF));
        String s = sb.substring(1);
        return s.matches("\\d{7}") ? s.substring(0, 1) + " " + s.substring(1, 4) + " " + s.substring(4) : null;
    }

    public static List<String> decode(byte[] r) {
        List<String> out = new ArrayList<>();
        // แบบ ASCII (เช่น EGS): ตัวอักษรเกือบทั้งหมดเป็นตัวเลข
        int printable = 0;
        for (int i = 1; i < r.length; i++) { int c = r[i] & 0xFF; if (c >= 0x20 && c < 0x7F) printable++; }
        if (r.length > 8 && printable >= (r.length - 1) * 0.8) {
            String a = Hex.ascii(r, 1).trim();
            if (a.length() >= 7 && a.substring(0, 7).matches("\\d{7}"))
                out.add("เลขชิ้นส่วน BMW: " + a.substring(0, 1) + " " + a.substring(1, 4) + " " + a.substring(4, 7));
            out.add("ข้อมูล ident: " + a);
            return out;
        }
        String pn = partNumber(r);
        if (pn != null) out.add("เลขชิ้นส่วน BMW: " + pn);
        if (r.length >= 13) {
            out.add(String.format("Hardware: %02X   Coding index: %02X   Diag index: %02X   Bus index: %02X",
                    r[5] & 0xFF, r[6] & 0xFF, r[7] & 0xFF, r[8] & 0xFF));
            int wk = r[9] & 0xFF, yr = r[10] & 0xFF;
            if (isBcd(r, 9, 2) && bcd(wk) >= 1 && bcd(wk) <= 53)
                out.add("ผลิตสัปดาห์ที่ " + bcd(wk) + " ปี 20" + String.format("%02d", bcd(yr)));
            out.add(String.format("ผู้ผลิต: %02X   Software: %02X", r[11] & 0xFF, r[12] & 0xFF));
        }
        out.add("HEX: " + Hex.of(r));
        return out;
    }

    /** VIN 17 หลักที่อยู่ในข้อความ (ถ้ามี) */
    public static String findVin(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-HJ-NPR-Z0-9]{17}").matcher(s);
        return m.find() ? m.group() : null;
    }
}
