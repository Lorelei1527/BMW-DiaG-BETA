package com.tms.bmwediag.sgbd;

public class SgbdException extends Exception {
    public final int code;

    public SgbdException(int code) {
        super(Err.describe(code));
        this.code = code;
    }

    public SgbdException(String msg) {
        super(msg);
        this.code = -1;
    }

    /** รหัสข้อผิดพลาดแบบ EDIABAS */
    public static final class Err {
        private Err() {}
        public static final int NONE = 0;
        public static int ifh(int n) { return n < 50 ? 10 + n : 200 + (n - 50); }
        public static int bip(int n) { return 60 + n; }
        public static int sys(int n) { return 90 + n; }

        public static String describe(int c) {
            if (c >= 10 && c <= 59) return String.format("IFH-%04d%s", c - 10, ifhText(c - 10));
            if (c >= 60 && c <= 89) return String.format("BIP-%04d%s", c - 60, bipText(c - 60));
            if (c >= 90 && c <= 119) return String.format("SYS-%04d", c - 90);
            if (c >= 200 && c <= 249) return String.format("IFH-%04d", c - 200 + 50);
            return "EDIABAS error " + c;
        }

        static String ifhText(int n) {
            switch (n) {
                case 2: return ": ไม่มีการตอบจากสาย";
                case 3: return ": ส่งข้อมูลไปสายผิดพลาด";
                case 6: return ": ไม่รับคำสั่ง";
                case 8: return ": เชื่อมต่อกล่องไม่ได้";
                case 9: return ": กล่องไม่ตอบ (NO RESPONSE FROM CONTROLUNIT)";
                case 10: return ": ข้อมูลจากกล่องผิดพลาด";
                case 14: return ": โปรโตคอล (concept) นี้ยังไม่รองรับ";
                case 41: return ": พารามิเตอร์ผิด";
                default: return "";
            }
        }

        static String bipText(int n) {
            switch (n) {
                case 1: return ": OUT OF RANGE";
                case 2: return ": IFH FUNCTION ERROR";
                case 5: return ": STACK OVERFLOW";
                case 7: return ": DIVISION BY ZERO";
                case 9: return ": BEST VERSION ERROR";
                case 10: return ": TABLE ACCESS ERROR";
                default: return "";
            }
        }
    }
}
