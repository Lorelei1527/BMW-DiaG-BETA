package com.tms.bmwediag.diag;

/** นิยามค่า live data 1 ตัวในคำตอบ: value = raw * factor + add */
public class LiveParam {
    public String name = "";
    public String unit = "";
    public int offset;          // นับจากไบต์แรกของข้อมูล (หลัง status A0 สำหรับ DS2 / หลัง service+id สำหรับ KWP)
    public int length = 1;      // 1, 2, 4
    public boolean signed;
    public boolean littleEndian;
    public double factor = 1;
    public double add = 0;
    public int decimals = 1;
    public int bitMask = 0;     // ถ้า != 0 ใช้ (raw & mask) != 0 -> 1/0

    public Double decode(byte[] data) {
        if (offset < 0 || offset + length > data.length) return null;
        long raw = 0;
        for (int i = 0; i < length; i++) {
            int b = data[offset + (littleEndian ? length - 1 - i : i)] & 0xFF;
            raw = (raw << 8) | b;
        }
        if (bitMask != 0) return (raw & bitMask) != 0 ? 1.0 : 0.0;
        if (signed) {
            long sign = 1L << (length * 8 - 1);
            if ((raw & sign) != 0) raw -= sign << 1;
        }
        return raw * factor + add;
    }

    public String format(Double v) {
        if (v == null) return "--";
        return String.format("%." + decimals + "f", v);
    }
}
