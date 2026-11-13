package com.tms.bmwediag.sgbd;

import com.tms.bmwediag.io.Hex;
import com.tms.bmwediag.io.SerialPort;
import com.tms.bmwediag.proto.KLine;

import java.io.IOException;

/**
 * ช่องสื่อสาร SGBD ผ่านสาย K+DCAN (USB FTDI)
 * - DS1/DS2 (0x0005/0x0006): MS42, MS43, GS20, GS8.60.4 — 9600 8E1 (สายโหมด K-line)
 * - KWP2000* (0x010D): ME9.2 / MS45 บน E46 — 9600 8E1 (สายโหมด K-line)
 * - BMW-FAST (0x010F) / D-CAN (0x0110): E90 เช่น MEV9.2 — 115200 8N1 (สายโหมด D-CAN แปลงเป็น CAN เอง)
 * อ้างอิงพฤติกรรม TransDs2 / TransKwp2000S / TransBmwFast ของ EdiabasLib (GPLv3)
 */
public class KLineInterface extends EdInterface {
    private final KLine k;
    private int concept;
    private int timeoutStd = 1000, regenTime = 0, telEnd = 50, timeout78 = 5000, retry78 = 2;
    private long lastResponse;
    /** โหมดเร็ว: ลดเวลาพักระหว่างคำสั่ง DS2 (SGBD ตั้งไว้ 100 ms) ถ้ากล่องไม่ตอบจะกลับไปใช้ค่าเดิมเอง */
    private final boolean fast;
    private boolean slowFallback;
    private int curBaud = -1, curParity = -1;

    public KLineInterface(KLine k) { this(k, true); }

    public KLineInterface(KLine k, boolean fast) { this(k, fast, false); }

    /** dcan = กล่องอยู่บน D-CAN (E90): ข้ามการลอง K-line ของ SGBD ไปใช้ BMW-FAST ทันที (เชื่อมต่อเร็วขึ้น) */
    private final boolean dcan;

    public KLineInterface(KLine k, boolean fast, boolean dcan) { this.k = k; this.fast = fast; this.dcan = dcan; }

    @Override
    public void setCommParameter(long[] par) throws SgbdException {
        commParameter = par;
        if (par.length == 0) return;
        concept = (int) par[0];
        int baud, parity = SerialPort.PARITY_EVEN;
        switch (concept) {
            case 0x0005:
            case 0x0006:
                if (par.length < 8) { SgbdBridge.setError(ediabas, SgbdException.Err.ifh(41)); return; }
                commAnswerLen = new short[]{-1, 0};
                baud = (int) par[1];
                timeoutStd = (int) par[5];
                regenTime = (int) par[6];
                telEnd = (int) par[7];
                break;
            case 0x010D: // KWP2000* (ME9.2 / MS45 บน E46 — 9600 8E1 ไม่ต้อง init)
                if (par.length < 7) { SgbdBridge.setError(ediabas, SgbdException.Err.ifh(41)); return; }
                baud = (int) par[1];
                timeoutStd = (int) par[2];
                regenTime = (int) par[3];
                telEnd = (int) par[4];
                retry78 = (int) par[6];
                timeout78 = par.length > 7 ? (int) par[7] : 5000;
                break;
            case 0x010F: // BMW-FAST (สาย D-CAN 115200 8N1)
                if (par.length < 7) { SgbdBridge.setError(ediabas, SgbdException.Err.ifh(41)); return; }
                baud = (int) par[1];
                parity = SerialPort.PARITY_NONE;
                timeoutStd = (int) par[2];
                regenTime = (int) par[3];
                telEnd = (int) par[4];
                retry78 = (int) par[5];
                timeout78 = (int) par[6];
                break;
            case 0x0110: // D-CAN
                if (par.length < 11) { SgbdBridge.setError(ediabas, SgbdException.Err.ifh(41)); return; }
                baud = 115200;
                parity = SerialPort.PARITY_NONE;
                timeoutStd = (int) par[7];
                telEnd = 10;
                regenTime = (int) par[8];
                timeout78 = (int) par[9];
                retry78 = (int) par[10];
                break;
            default:
                SgbdBridge.setError(ediabas, SgbdException.Err.ifh(14));
                return;
        }
        if (timeoutStd <= 0) timeoutStd = 1000;
        if (baud <= 0) baud = parity == SerialPort.PARITY_NONE ? 115200 : 9600;
        try {
            if (baud != curBaud || parity != curParity) {
                k.configure(baud, parity);
                curBaud = baud;
                curParity = parity;
            }
            k.purge();
        } catch (IOException e) {
            SgbdBridge.setError(ediabas, SgbdException.Err.ifh(1));
        }
    }

    private boolean isFast() { return concept == 0x010F || concept == 0x0110; }

    @Override
    public byte[] transmit(byte[] request) throws SgbdException {
        if (concept != 5 && concept != 6 && concept != 0x010D && !isFast()) {
            SgbdBridge.setError(ediabas, SgbdException.Err.ifh(14));
            return null;
        }
        if (dcan && !isFast()) { // E90: ไม่มี K-line ให้ SGBD ข้ามไปลองแบบ BMW-FAST เลย
            SgbdBridge.setError(ediabas, SgbdException.Err.ifh(9));
            return null;
        }
        byte[] hit = cached(request);
        if (hit != null) return hit;
        int tries = (int) Math.max(0, commRepeats) + 1;
        if (fast && !slowFallback && (concept == 5 || concept == 6)) tries = Math.max(tries, 2); // โหมดเร็ว: ลองซ้ำอีกครั้งด้วยเวลาปกติ
        for (int t = 0; t < tries; t++) {
            try {
                byte[] r = concept == 0x010D ? transKwp2000S(request) : isFast() ? transBmwFast(request) : transDs2(request);
                remember(request, r);
                return r;
            } catch (IOException e) {
                if (fast && !slowFallback && t == 0 && (concept == 5 || concept == 6)) slowFallback = true;
                try { k.purge(); } catch (IOException ignored) {}
            }
        }
        SgbdBridge.setError(ediabas, SgbdException.Err.ifh(9));
        return null;
    }

    /** เวลาพักระหว่างคำสั่ง: DS2 โหมดเร็วใช้ 10 ms (เดิม 100 ms) */
    private int regen() {
        if (fast && !slowFallback && (concept == 5 || concept == 6)) return Math.min(regenTime, 10);
        return regenTime;
    }

    private void sendRaw(byte[] tel) throws IOException {
        long wait = regen() - (System.currentTimeMillis() - lastResponse);
        if (wait > 0) KLine.sleep(wait);
        k.purgeLight();
        k.send(tel);
    }

    private void sendWithChecksum(byte[] req, int len) throws IOException {
        byte[] tel = new byte[len + 1];
        System.arraycopy(req, 0, tel, 0, len);
        tel[len] = (byte) KLine.xor(tel, 0, len);
        sendRaw(tel);
    }

    private byte[] transDs2(byte[] req) throws IOException {
        if (req.length > 0) sendWithChecksum(req, req.length);
        int hdr = commAnswerLen[0];
        if (hdr < 0) hdr = -hdr + 1;
        if (hdr == 0) throw new IOException("answer length 0");
        byte[] h = k.readExact(hdr, Math.max(timeoutStd, 100));
        if (h == null) throw new IOException("no response");
        int len;
        if (commAnswerLen[0] > 0) len = commAnswerLen[0];
        else len = (h[-commAnswerLen[0]] & 0xFF) + commAnswerLen[1];
        if (len < hdr) throw new IOException("bad length");
        byte[] rest = len > hdr ? k.readExact(len - hdr, Math.max(telEnd, 20) + (len - hdr) * 2 + 100) : new byte[0];
        if (rest == null) throw new IOException("incomplete");
        byte[] f = new byte[len];
        System.arraycopy(h, 0, f, 0, hdr);
        System.arraycopy(rest, 0, f, hdr, rest.length);
        if (KLine.xor(f, 0, len - 1) != (f[len - 1] & 0xFF)) throw new IOException("checksum " + Hex.of(f));
        lastResponse = System.currentTimeMillis();
        return f;
    }

    /** KWP2000*: [fmt][tgt][src][len][data..][XOR] */
    private byte[] transKwp2000S(byte[] req) throws IOException {
        if (req.length > 0) {
            if (req.length < 4) throw new IOException("telegram too short");
            int telLen = (req[3] & 0xFF) + 4;
            if (telLen > req.length) throw new IOException("telegram length");
            sendWithChecksum(req, telLen);
        }
        int pending = 0;
        while (true) {
            int timeout = pending > 0 ? timeout78 : timeoutStd;
            byte[] h = k.readExact(4, Math.max(timeout, 100));
            if (h == null) throw new IOException("no response");
            int telLen = (h[3] & 0xFF) + 4;
            byte[] rest = k.readExact(telLen - 4 + 1, Math.max(telEnd, 20) + telLen * 2 + 100);
            if (rest == null) throw new IOException("incomplete");
            byte[] f = new byte[telLen + 1];
            System.arraycopy(h, 0, f, 0, 4);
            System.arraycopy(rest, 0, f, 4, rest.length);
            if (KLine.xor(f, 0, telLen) != (f[telLen] & 0xFF)) throw new IOException("checksum " + Hex.of(f));
            if ((f[3] & 0xFF) == 3 && (f[4] & 0xFF) == 0x7F && (f[6] & 0xFF) == 0x78) {
                if (++pending > Math.max(retry78, 1) * 20) throw new IOException("response pending timeout");
                continue;
            }
            lastResponse = System.currentTimeMillis();
            return f;
        }
    }

    /** ความยาว telegram BMW-FAST (ไม่รวม checksum) */
    static int telLengthFast(byte[] b) {
        int len = b[0] & 0x3F;
        if (len == 0) {
            len = b[3] & 0xFF;
            if (len == 0) return ((b[4] & 0xFF) << 8) + (b[5] & 0xFF) + 6;
            return len + 4;
        }
        return len + 3;
    }

    /** BMW-FAST: [80|len][tgt][src]([len])[data..][SUM] — สายโหมด D-CAN แปลงเป็น CAN ให้ */
    private byte[] transBmwFast(byte[] req) throws IOException {
        if (req.length > 0) {
            if (req.length < 3) throw new IOException("telegram too short");
            int len = telLengthFast(req);
            if (len > req.length) throw new IOException("telegram length");
            byte[] tel = new byte[len + 1];
            System.arraycopy(req, 0, tel, 0, len);
            tel[len] = (byte) KLine.sum(tel, 0, len);
            sendRaw(tel);
        }
        int pending = 0;
        while (true) {
            int timeout = pending > 0 ? timeout78 : timeoutStd;
            byte[] h = k.readExact(4, Math.max(timeout, 100));
            if (h == null) throw new IOException("no response");
            if ((h[0] & 0xC0) != 0x80) throw new IOException("bad header " + Hex.of(h));
            byte[] head = h;
            if ((h[0] & 0x3F) == 0 && h[3] == 0) {
                byte[] l2 = k.readExact(2, Math.max(telEnd, 20) + 50);
                if (l2 == null) throw new IOException("no length");
                head = new byte[]{h[0], h[1], h[2], h[3], l2[0], l2[1]};
            }
            int telLen = telLengthFast(head);
            byte[] rest = k.readExact(telLen - head.length + 1, Math.max(telEnd, 20) + telLen + 100);
            if (rest == null) throw new IOException("incomplete");
            byte[] f = new byte[telLen + 1];
            System.arraycopy(head, 0, f, 0, head.length);
            System.arraycopy(rest, 0, f, head.length, rest.length);
            if ((KLine.sum(f, 0, telLen) & 0xFF) != (f[telLen] & 0xFF)) throw new IOException("checksum " + Hex.of(f));
            int dl = f[0] & 0x3F, ds = 3;
            if (dl == 0) { dl = f[3] & 0xFF; ds = 4; }
            if (dl == 3 && (f[ds] & 0xFF) == 0x7F && (f[ds + 2] & 0xFF) == 0x78) {
                if (++pending > Math.max(retry78, 1) * 20) throw new IOException("response pending timeout");
                continue;
            }
            lastResponse = System.currentTimeMillis();
            return f;
        }
    }

    @Override
    public String interfaceType() { return "OBD"; }
}
