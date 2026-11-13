package com.tms.bmwediag.io;

import java.io.IOException;

/** พอร์ตอนุกรมระดับต่ำ (ใช้กับสาย K+DCAN แบบ FTDI) */
public interface SerialPort {
    int PARITY_NONE = 0, PARITY_ODD = 1, PARITY_EVEN = 2;

    void open() throws IOException;
    void close();
    void setParameters(int baud, int dataBits, int parity, int stopBits) throws IOException;
    void setBreak(boolean on) throws IOException;
    void setDtr(boolean on) throws IOException;
    void setRts(boolean on) throws IOException;
    void write(byte[] data) throws IOException;
    /** อ่านข้อมูลที่มี ภายในเวลา timeoutMs คืนจำนวนไบต์ (0 = หมดเวลา) */
    int read(byte[] buf, int timeoutMs) throws IOException;
    void purge() throws IOException;
}
