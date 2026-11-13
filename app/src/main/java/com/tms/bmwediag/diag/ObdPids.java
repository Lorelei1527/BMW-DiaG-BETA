package com.tms.bmwediag.diag;

import java.util.ArrayList;
import java.util.List;

/** PID มาตรฐาน OBD2 โหมด 01 ที่ DME ของ BMW E-series รองรับโดยทั่วไป */
public final class ObdPids {
    private ObdPids() {}

    public interface F { double f(int A, int B, int C, int D); }

    public static final class Pid {
        public final int pid, bytes, decimals;
        public final String name, unit;
        public final F formula;
        Pid(int pid, int bytes, String name, String unit, int dec, F f) {
            this.pid = pid; this.bytes = bytes; this.name = name; this.unit = unit; this.decimals = dec; this.formula = f;
        }
        /** data = คำตอบเต็ม 41 PID A B ... */
        public Double decode(byte[] data) {
            if (data == null || data.length < 2 + bytes) return null;
            if ((data[0] & 0xFF) != 0x41 || (data[1] & 0xFF) != pid) return null;
            int[] v = new int[4];
            for (int i = 0; i < bytes && i < 4; i++) v[i] = data[2 + i] & 0xFF;
            return formula.f(v[0], v[1], v[2], v[3]);
        }
    }

    public static final List<Pid> ALL = new ArrayList<>();
    static {
        ALL.add(new Pid(0x0C, 2, "รอบเครื่อง", "rpm", 0, (A, B, C, D) -> (A * 256 + B) / 4.0));
        ALL.add(new Pid(0x0D, 1, "ความเร็ว", "km/h", 0, (A, B, C, D) -> A));
        ALL.add(new Pid(0x05, 1, "อุณหภูมิน้ำ", "°C", 0, (A, B, C, D) -> A - 40));
        ALL.add(new Pid(0x0F, 1, "อุณหภูมิอากาศเข้า", "°C", 0, (A, B, C, D) -> A - 40));
        ALL.add(new Pid(0x04, 1, "ภาระเครื่อง (load)", "%", 1, (A, B, C, D) -> A * 100 / 255.0));
        ALL.add(new Pid(0x11, 1, "ลิ้นปีกผีเสื้อ", "%", 1, (A, B, C, D) -> A * 100 / 255.0));
        ALL.add(new Pid(0x0E, 1, "องศาจุดระเบิด", "°", 1, (A, B, C, D) -> A / 2.0 - 64));
        ALL.add(new Pid(0x10, 2, "อากาศเข้า MAF", "g/s", 2, (A, B, C, D) -> (A * 256 + B) / 100.0));
        ALL.add(new Pid(0x0B, 1, "แรงดันท่อไอดี MAP", "kPa", 0, (A, B, C, D) -> A));
        ALL.add(new Pid(0x06, 1, "STFT B1", "%", 1, (A, B, C, D) -> (A - 128) * 100 / 128.0));
        ALL.add(new Pid(0x07, 1, "LTFT B1", "%", 1, (A, B, C, D) -> (A - 128) * 100 / 128.0));
        ALL.add(new Pid(0x08, 1, "STFT B2", "%", 1, (A, B, C, D) -> (A - 128) * 100 / 128.0));
        ALL.add(new Pid(0x09, 1, "LTFT B2", "%", 1, (A, B, C, D) -> (A - 128) * 100 / 128.0));
        ALL.add(new Pid(0x14, 2, "O2 B1S1", "V", 3, (A, B, C, D) -> A / 200.0));
        ALL.add(new Pid(0x15, 2, "O2 B1S2", "V", 3, (A, B, C, D) -> A / 200.0));
        ALL.add(new Pid(0x18, 2, "O2 B2S1", "V", 3, (A, B, C, D) -> A / 200.0));
        ALL.add(new Pid(0x19, 2, "O2 B2S2", "V", 3, (A, B, C, D) -> A / 200.0));
        ALL.add(new Pid(0x03, 2, "สถานะส่วนผสม", "", 0, (A, B, C, D) -> A));
        ALL.add(new Pid(0x1F, 2, "เวลาตั้งแต่สตาร์ท", "s", 0, (A, B, C, D) -> A * 256 + B));
        ALL.add(new Pid(0x2F, 1, "ระดับน้ำมัน", "%", 0, (A, B, C, D) -> A * 100 / 255.0));
        ALL.add(new Pid(0x33, 1, "ความกดอากาศ", "kPa", 0, (A, B, C, D) -> A));
        ALL.add(new Pid(0x42, 2, "แรงดันไฟ ECU", "V", 2, (A, B, C, D) -> (A * 256 + B) / 1000.0));
        ALL.add(new Pid(0x46, 1, "อุณหภูมิภายนอก", "°C", 0, (A, B, C, D) -> A - 40));
        ALL.add(new Pid(0x5C, 1, "อุณหภูมิน้ำมันเครื่อง", "°C", 0, (A, B, C, D) -> A - 40));
    }

    /** PID 03 สถานะระบบเชื้อเพลิง */
    public static String fuelStatus(int a) {
        switch (a) {
            case 1: return "Open loop (เครื่องเย็น)";
            case 2: return "Closed loop (ใช้ O2)";
            case 4: return "Open loop (เร่ง/ตัดน้ำมัน)";
            case 8: return "Open loop (O2 เสีย)";
            case 16: return "Closed loop (O2 มีปัญหา)";
            default: return "—";
        }
    }

    /** แปลง bitmask จาก 01 00 / 01 20 / 01 40 เป็นเซ็ต PID ที่รองรับ */
    public static boolean[] supported(byte[][] masks) {
        boolean[] s = new boolean[0x61];
        for (int m = 0; m < masks.length; m++) {
            byte[] r = masks[m];
            if (r == null || r.length < 6) continue;
            int base = r[1] & 0xFF;
            for (int i = 0; i < 32; i++) {
                int bit = (r[2 + i / 8] >> (7 - i % 8)) & 1;
                int pid = base + i + 1;
                if (bit == 1 && pid < s.length) s[pid] = true;
            }
        }
        return s;
    }
}
