package com.tms.bmwediag.proto;

import com.tms.bmwediag.io.Hex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** OBD2 ISO 9141-2 (5-baud init ที่ 0x33) — E46/E39 รุ่นแรกๆ ใช้แบบนี้ */
public class Iso9141Channel implements EcuChannel {
    private final KLine k;
    public int kb1, kb2;

    public int p3Ms = 55;
    private int filterSource = -1;
    private final List<Integer> sources = new ArrayList<>();

    @Override public void setSourceFilter(int src) { filterSource = src; }
    @Override public List<Integer> lastSources() { return new ArrayList<>(sources); }

    public Iso9141Channel(KLine k) { this.k = k; }

    public void start() throws IOException {
        k.purge();
        k.fiveBaud(0x33);
        byte[] sync = k.readExact(3, 1500);
        if (sync == null) throw new IOException("5-baud init: ไม่ได้รับ 0x55 + key bytes");
        k.trace("RX " + Hex.of(sync));
        if ((sync[0] & 0xFF) != 0x55) throw new IOException("5-baud init: sync ผิด " + Hex.of(sync));
        kb1 = sync[1] & 0xFF;
        kb2 = sync[2] & 0xFF;
        KLine.sleep(30); // W4
        k.send(new byte[]{(byte) (~kb2 & 0xFF)});
        byte[] inv = k.readExact(1, 500);
        if (inv == null || (inv[0] & 0xFF) != 0xCC) throw new IOException("5-baud init: ไม่ได้รับ ~address (CC)");
        KLine.sleep(60);
    }

    public static byte[] frame(byte[] data) {
        byte[] f = new byte[data.length + 4];
        f[0] = 0x68; f[1] = 0x6A; f[2] = (byte) 0xF1;
        System.arraycopy(data, 0, f, 3, data.length);
        f[f.length - 1] = (byte) KLine.sum(f, 0, f.length - 1);
        return f;
    }

    @Override
    public byte[] request(byte[] payload) throws IOException {
        return requestMulti(payload).get(0);
    }

    /** รู้ความยาวคำตอบ: header 3 + data + checksum — อ่านครบแล้วคืนเลย ไม่ต้องรอสายเงียบ 40ms */
    @Override
    public byte[] requestExpect(byte[] payload, int responseLen) throws IOException {
        k.waitP3(p3Ms);
        k.purgeLight();
        k.send(frame(payload));
        int total = responseLen + 4;
        long deadline = System.currentTimeMillis() + 600;
        int neg = -1;
        while (System.currentTimeMillis() < deadline) {
            byte[] r = k.readUpTo(total, (int) Math.max(1, deadline - System.currentTimeMillis()), 25);
            if (r.length == 0) break;
            k.trace("RX " + Hex.of(r));
            int src = r.length > 2 ? r[2] & 0xFF : -1;
            boolean wanted = filterSource < 0 || src == filterSource;
            if (r.length >= 7 && (r[3] & 0xFF) == 0x7F) {
                // คำตอบปฏิเสธยาว 7 ไบต์ — คืนส่วนเกินที่อ่านมาไม่ได้ จึงล้างทิ้งแล้วอ่านต่อ
                neg = r[5] & 0xFF;
                if (wanted && filterSource >= 0) throw new NegativeResponseException(payload[0] & 0xFF, neg);
                k.purgeLight();
                continue;
            }
            if (r.length == total && KLine.sum(r, 0, total - 1) == (r[total - 1] & 0xFF)
                    && wanted && r[3] == (byte) ((payload[0] & 0xFF) + 0x40)
                    && (payload.length < 2 || r[4] == payload[1])) {
                byte[] d = new byte[responseLen];
                System.arraycopy(r, 3, d, 0, responseLen);
                return d;
            }
            // เฟรมจากกล่องอื่น/ไม่ตรง PID: ข้ามแล้วรอเฟรมถัดไป
        }
        if (neg >= 0) throw new NegativeResponseException(payload[0] & 0xFF, neg);
        throw new IOException("ISO9141: ไม่ได้คำตอบที่ตรงกับ PID");
    }

    @Override
    public List<byte[]> requestMulti(byte[] payload) throws IOException {
        k.waitP3(p3Ms);
        k.purgeLight();
        k.send(frame(payload));
        byte[] r = k.readUntilIdle(1000, 40);
        if (r.length == 0) throw new IOException("ISO9141: ไม่มีการตอบกลับ");
        k.trace("RX " + Hex.of(r));
        // แยกเฟรมด้วยการหา header 48 6B แล้วตรวจ checksum
        List<byte[]> out = new ArrayList<>();
        sources.clear();
        int start = 0;
        for (int i = 1; i <= r.length; i++) {
            boolean boundary = i == r.length ||
                    (i + 1 < r.length && (r[i] & 0xFF) == 0x48 && (r[i + 1] & 0xFF) == 0x6B
                            && KLine.sum(r, start, i - start - 1) == (r[i - 1] & 0xFF));
            if (boundary) {
                int len = i - start;
                if (len >= 5) {
                    byte[] d = new byte[len - 4];
                    System.arraycopy(r, start + 3, d, 0, d.length);
                    int src = r[start + 2] & 0xFF;
                    boolean wanted = filterSource < 0 || src == filterSource;
                    if ((d[0] & 0xFF) != 0x7F && wanted) { out.add(d); sources.add(src); }
                }
                start = i;
            }
        }
        if (out.isEmpty()) {
            if (r.length >= 6 && (r[3] & 0xFF) == 0x7F) throw new NegativeResponseException(payload[0] & 0xFF, r[5] & 0xFF);
            throw new IOException("ISO9141: ไม่มีคำตอบที่ใช้ได้ " + Hex.of(r));
        }
        return out;
    }

    @Override
    public String describe() { return String.format("OBD2 ISO9141-2 (KB %02X %02X)", kb1, kb2); }

    @Override
    public void close() {}
}
