package com.tms.bmwediag.proto;

import com.tms.bmwediag.io.Hex;
import com.tms.bmwediag.io.SerialPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** ตัวช่วยสาย K-line: ตัด echo, อ่านครบจำนวนไบต์, init แบบ fast/5-baud */
public class KLine {
    public interface Logger { void log(String s); }

    private final SerialPort port;
    public boolean echo = true;
    public Logger logger;
    /** true = log TX/RX ทุกเฟรม (ช้าลงเมื่ออ่าน live) */
    public boolean trace = true;
    /** เวลาที่สายมีข้อมูลล่าสุด ใช้คำนวณ P3 (หน่วงก่อนส่งคำสั่งถัดไป) */
    private long lastActivity = 0;
    private final byte[] one = new byte[256];
    private final ByteArrayOutputStream carry = new ByteArrayOutputStream();

    public KLine(SerialPort port) { this.port = port; }

    public SerialPort port() { return port; }

    void log(String s) { if (logger != null) logger.log(s); }

    void trace(String s) { if (trace && logger != null) logger.log(s); }

    /** รอให้ครบ P3 นับจากไบต์สุดท้ายบนสาย (ไม่ sleep ตายตัว) */
    public void waitP3(int ms) {
        sleep(ms - (System.currentTimeMillis() - lastActivity));
    }

    /** ล้างบัฟเฟอร์ฝั่งรับแบบเร็ว (ไม่ส่ง control transfer ไป FTDI) */
    public void purgeLight() throws IOException {
        carry.reset();
        for (int i = 0; i < 8; i++) if (port.read(one, 1) <= 0) break;
    }

    public void configure(int baud, int parity) throws IOException {
        port.setParameters(baud, 8, parity, 1);
    }

    public void purge() throws IOException { carry.reset(); port.purge(); }

    /** ส่งข้อมูล แล้วอ่าน echo ทิ้ง (K-line เป็นสายเดี่ยว จะได้ข้อมูลที่ส่งกลับมาเสมอ) */
    public void send(byte[] data) throws IOException {
        trace("TX " + Hex.of(data));
        port.write(data);
        if (echo) {
            byte[] e = readExact(data.length, 300 + data.length * 3);
            if (e == null) log("! ไม่ได้รับ echo (สายอยู่โหมด D-CAN? หรือปิด echo ในตั้งค่า)");
        }
    }

    /** อ่านให้ครบ n ไบต์ภายใน timeout คืน null ถ้าไม่ครบ */
    public byte[] readExact(int n, int timeoutMs) throws IOException {
        byte[] out = new byte[n];
        int got = 0;
        byte[] c = carry.toByteArray();
        carry.reset();
        int take = Math.min(c.length, n);
        System.arraycopy(c, 0, out, 0, take);
        got = take;
        if (c.length > take) carry.write(c, take, c.length - take);
        long end = System.currentTimeMillis() + timeoutMs;
        while (got < n) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            int r = port.read(one, (int) left);
            if (r <= 0) continue;
            int use = Math.min(r, n - got);
            System.arraycopy(one, 0, out, got, use);
            got += use;
            lastActivity = System.currentTimeMillis();
            if (r > use) carry.write(one, use, r - use);
        }
        return out;
    }

    /** อ่านจนสายเงียบ interByteMs (ใช้กับ ISO9141 ที่ไม่มีไบต์ความยาว) */
    public byte[] readUntilIdle(int firstTimeoutMs, int interByteMs) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] c = carry.toByteArray();
        carry.reset();
        bo.write(c, 0, c.length);
        int r = bo.size() > 0 ? 1 : port.read(one, firstTimeoutMs);
        if (r <= 0) return new byte[0];
        if (bo.size() == 0) bo.write(one, 0, r);
        while (true) {
            r = port.read(one, interByteMs);
            if (r <= 0) break;
            bo.write(one, 0, r);
        }
        lastActivity = System.currentTimeMillis();
        return bo.toByteArray();
    }

    /** อ่านจนได้ครบ max ไบต์ (คืนทันที) หรือสายเงียบ interByteMs */
    public byte[] readUpTo(int max, int firstTimeoutMs, int interByteMs) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] c = carry.toByteArray();
        carry.reset();
        bo.write(c, 0, c.length);
        long end = System.currentTimeMillis() + firstTimeoutMs;
        while (bo.size() == 0 && System.currentTimeMillis() < end) {
            int r = port.read(one, (int) Math.max(1, end - System.currentTimeMillis()));
            if (r > 0) bo.write(one, 0, r);
        }
        while (bo.size() > 0 && bo.size() < max) {
            int r = port.read(one, interByteMs);
            if (r <= 0) break;
            bo.write(one, 0, r);
        }
        byte[] all = bo.toByteArray();
        if (all.length > max) {
            carry.write(all, max, all.length - max);
            byte[] t = new byte[max];
            System.arraycopy(all, 0, t, 0, max);
            all = t;
        }
        if (all.length > 0) lastActivity = System.currentTimeMillis();
        return all;
    }

    public static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** ISO 14230 fast init: low 25ms / high 25ms */
    public void fastInitPulse() throws IOException {
        sleep(300); // W5 สายต้องว่างก่อน
        port.setBreak(true);
        sleep(25);
        port.setBreak(false);
        sleep(25);
    }

    /** ส่ง address ที่ 5 baud (200ms/บิต) โดยใช้ break: start, 8 data (LSB ก่อน), stop */
    public void fiveBaud(int address) throws IOException {
        sleep(300);
        port.setBreak(true);           // start bit = low
        sleep(200);
        for (int i = 0; i < 8; i++) {
            boolean bit = ((address >> i) & 1) != 0;
            port.setBreak(!bit);       // 1 = high (idle), 0 = low (break)
            sleep(200);
        }
        port.setBreak(false);          // stop bit (สาย idle = high)
        // ทิ้ง 0x00/echo ที่เกิดจาก break แล้วรอ 0x55 จาก ECU (W1 = 20..300ms)
        carry.reset();
        port.purge();
    }

    public static int sum(byte[] b, int off, int len) {
        int s = 0;
        for (int i = off; i < off + len; i++) s += b[i] & 0xFF;
        return s & 0xFF;
    }

    public static int xor(byte[] b, int off, int len) {
        int s = 0;
        for (int i = off; i < off + len; i++) s ^= b[i] & 0xFF;
        return s & 0xFF;
    }
}
