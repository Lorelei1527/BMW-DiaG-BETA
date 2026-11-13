package com.tms.bmwediag.io;

/** คำนวณค่า divisor ของ FTDI FT232R/FT232BM (clock 3 MHz, ส่วนแบ่ง 1/8) */
public final class FtdiBaud {
    private FtdiBaud() {}
    private static final int[] FRAC = {0, 3, 2, 4, 1, 5, 6, 7};

    /** คืน {value, index} สำหรับ control transfer SET_BAUD_RATE */
    public static int[] encode(int baud) {
        int d8 = (24000000 + baud / 2) / baud; // หน่วย 1/8
        int encoded;
        if (d8 <= 8) encoded = 0;           // 3 Mbaud
        else if (d8 < 16) encoded = 1;      // 2 Mbaud
        else encoded = (d8 >> 3) | (FRAC[d8 & 7] << 14);
        return new int[]{encoded & 0xFFFF, (encoded >> 16) & 0xFFFF};
    }

    public static int actual(int baud) {
        int d8 = (24000000 + baud / 2) / baud;
        return 24000000 / d8;
    }
}
