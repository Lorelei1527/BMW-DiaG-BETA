package com.tms.bmwediag.proto;

import com.tms.bmwediag.io.Hex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * KWP2000 / ISO 14230 บน K-line 10400 8N1 พร้อม fast init
 * ใช้กับ MS45, ME9.2 (physical addr 0x12) และ OBD2 (functional addr 0x33)
 */
public class KwpChannel implements EcuChannel {
    private final KLine k;
    private final int target;
    private final int source = 0xF1;
    private final boolean functional;
    public int timeoutMs = 1000;
    /** P3: หน่วงขั้นต่ำระหว่างคำตอบกับคำขอถัดไป (มาตรฐาน 55ms, ส่วนใหญ่ลดได้ถึง ~20) */
    public int p3Ms = 55;
    public int keyBytes = 0;
    private int filterSource = -1;
    private final List<Integer> sources = new ArrayList<>();

    @Override public void setSourceFilter(int src) { filterSource = src; }
    @Override public List<Integer> lastSources() { return new ArrayList<>(sources); }

    /** service ที่คำตอบต้องสะท้อน byte ที่ 2 (PID / local id) */
    static boolean echoesId(int sid) {
        return sid == 0x01 || sid == 0x02 || sid == 0x09 || sid == 0x21 || sid == 0x1A;
    }

    public KwpChannel(KLine k, int target, boolean functional) {
        this.k = k;
        this.target = target;
        this.functional = functional;
    }

    public static byte[] frame(int target, int source, boolean functional, byte[] data) {
        int base = functional ? 0xC0 : 0x80;
        byte[] f;
        int o;
        if (data.length <= 63) {
            f = new byte[data.length + 4];
            f[0] = (byte) (base | data.length);
            o = 3;
        } else {
            f = new byte[data.length + 5];
            f[0] = (byte) base;
            f[3] = (byte) data.length;
            o = 4;
        }
        f[1] = (byte) target;
        f[2] = (byte) source;
        System.arraycopy(data, 0, f, o, data.length);
        f[f.length - 1] = (byte) KLine.sum(f, 0, f.length - 1);
        return f;
    }

    /** แยก payload จากเฟรม KWP ทั้งเฟรม (ใช้ทดสอบ/parse) */
    public static byte[] payloadOf(byte[] f) throws IOException {
        int fmt = f[0] & 0xFF;
        int pos = 1;
        if ((fmt & 0x80) != 0 || (fmt & 0x40) != 0) pos += 2;
        int len = fmt & 0x3F;
        if (len == 0) len = f[pos++] & 0xFF;
        if (pos + len + 1 != f.length) throw new IOException("KWP ความยาวเฟรมไม่ตรง: " + Hex.of(f));
        if (KLine.sum(f, 0, f.length - 1) != (f[f.length - 1] & 0xFF))
            throw new IOException("KWP checksum ผิด: " + Hex.of(f));
        byte[] d = new byte[len];
        System.arraycopy(f, pos, d, 0, len);
        return d;
    }

    private byte[] readFrame(int timeout) throws IOException {
        byte[] b = k.readExact(1, timeout);
        if (b == null) return null;
        int fmt = b[0] & 0xFF;
        boolean addr = (fmt & 0xC0) != 0;
        int len = fmt & 0x3F;
        int hdrExtra = (addr ? 2 : 0) + (len == 0 ? 1 : 0);
        byte[] h = hdrExtra > 0 ? k.readExact(hdrExtra, 200) : new byte[0];
        if (h == null) throw new IOException("KWP header ไม่ครบ");
        if (len == 0) len = h[h.length - 1] & 0xFF;
        byte[] rest = k.readExact(len + 1, 300 + len * 3);
        if (rest == null) throw new IOException("KWP ข้อมูลไม่ครบ");
        byte[] f = new byte[1 + h.length + rest.length];
        f[0] = b[0];
        System.arraycopy(h, 0, f, 1, h.length);
        System.arraycopy(rest, 0, f, 1 + h.length, rest.length);
        k.trace("RX " + Hex.of(f));
        return f;
    }

    /** fast init + StartCommunication (0x81) */
    public void start() throws IOException {
        k.purge();
        k.fastInitPulse();
        k.send(frame(target, source, functional, new byte[]{(byte) 0x81}));
        byte[] f = readFrame(1000);
        if (f == null) throw new IOException("Fast init: ไม่มีการตอบกลับ");
        byte[] d = payloadOf(f);
        if ((d[0] & 0xFF) != 0xC1) throw new IOException("StartCommunication ถูกปฏิเสธ: " + Hex.of(d));
        if (d.length >= 3) keyBytes = ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
        KLine.sleep(50);
    }

    @Override
    public byte[] request(byte[] payload) throws IOException {
        return transact(payload, true).get(0);
    }

    @Override
    public byte[] requestExpect(byte[] payload, int responseLen) throws IOException {
        return transact(payload, true).get(0);
    }

    @Override
    public List<byte[]> requestMulti(byte[] payload) throws IOException {
        return transact(payload, false);
    }

    /** single = คืนทันทีเมื่อได้คำตอบบวกแรก (ไม่รอเฟรมจาก ECU อื่น 60ms) */
    private List<byte[]> transact(byte[] payload, boolean single) throws IOException {
        k.waitP3(p3Ms);
        k.purgeLight();
        k.send(frame(target, source, functional, payload));
        List<byte[]> out = new ArrayList<>();
        sources.clear();
        int sid = payload[0] & 0xFF;
        long deadline = System.currentTimeMillis() + 5000;
        int to = timeoutMs;
        int negCode = -1;
        while (System.currentTimeMillis() < deadline) {
            byte[] f = readFrame(to);
            if (f == null) break;
            byte[] d = payloadOf(f);
            int src = (f[0] & 0xC0) != 0 ? f[2] & 0xFF : -1;
            boolean fromWanted = filterSource < 0 || src < 0 || src == filterSource;
            if ((d[0] & 0xFF) == 0x7F && d.length >= 3 && (d[1] & 0xFF) == sid) {
                int code = d[2] & 0xFF;
                if (code == 0x78 && fromWanted) { to = 5000; continue; } // response pending
                // ฟังก์ชันแนล (OBD): กล่องอื่นตอบปฏิเสธได้ — อย่าเพิ่งยอมแพ้ รอ DME
                if (fromWanted && (!functional || filterSource >= 0)) throw new NegativeResponseException(sid, code);
                negCode = code;
                to = 80;
                continue;
            }
            boolean idOk = payload.length < 2 || !echoesId(sid) || (d.length > 1 && d[1] == payload[1]);
            if ((d[0] & 0xFF) == sid + 0x40 && idOk && fromWanted) {
                out.add(d);
                sources.add(src);
                if (single) break;
            }
            to = 80; // เฟรมถัดไป (หลาย ECU / หลายเฟรม)
        }
        if (out.isEmpty()) {
            if (negCode >= 0) throw new NegativeResponseException(sid, negCode);
            throw new IOException(String.format("KWP: ไม่มีการตอบกลับ service 0x%02X", sid));
        }
        return out;
    }

    @Override
    public String describe() {
        return String.format("KWP2000 %s 0x%02X 10400 (KB %04X)", functional ? "func" : "phys", target, keyBytes);
    }

    @Override
    public void close() {
        try {
            k.send(frame(target, source, functional, new byte[]{(byte) 0x82})); // StopCommunication
        } catch (Exception ignored) {}
    }
}
