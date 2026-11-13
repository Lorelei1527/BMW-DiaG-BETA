package com.tms.bmwediag.sgbd;

import com.tms.bmwediag.io.Hex;
import com.tms.bmwediag.io.SerialPort;
import com.tms.bmwediag.proto.KLine;

import java.io.IOException;

/**
 * ช่องสื่อสาร SGBD ผ่านสาย K+DCAN (K-line)
 * รองรับ concept DS1/DS2 (0x0005/0x0006) และ KWP2000* (0x010D)
 * อ้างอิงพฤติกรรม TransDs2 / TransKwp2000S ของ EdiabasLib (GPLv3)
 */
public class KLineInterface extends EdInterface {
    private final KLine k;
    private int concept;
    private int timeoutStd = 1000, regenTime = 0, telEnd = 50, timeout78 = 5000, retry78 = 2;
    private long lastResponse;

    public KLineInterface(KLine k) { this.k = k; }

    @Override
    public void setCommParameter(long[] par) throws SgbdException {
        commParameter = par;
        if (par.length == 0) return;
        concept = (int) par[0];
        int baud;
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
            default:
                SgbdBridge.setError(ediabas, SgbdException.Err.ifh(14));
                return;
        }
        try {
            k.configure(baud > 0 ? baud : 9600, SerialPort.PARITY_EVEN);
            k.purge();
        } catch (IOException e) {
            SgbdBridge.setError(ediabas, SgbdException.Err.ifh(1));
        }
    }

    @Override
    public byte[] transmit(byte[] request) throws SgbdException {
        if (concept != 5 && concept != 6 && concept != 0x010D) {
            SgbdBridge.setError(ediabas, SgbdException.Err.ifh(14));
            return null;
        }
        int tries = (int) Math.max(0, commRepeats) + 1;
        for (int t = 0; t < tries; t++) {
            try {
                return concept == 0x010D ? transKwp2000S(request) : transDs2(request);
            } catch (IOException e) {
                try { k.purge(); } catch (IOException ignored) {}
            }
        }
        SgbdBridge.setError(ediabas, SgbdException.Err.ifh(9));
        return null;
    }

    private void sendWithChecksum(byte[] req, int len) throws IOException {
        long wait = regenTime - (System.currentTimeMillis() - lastResponse);
        if (wait > 0) KLine.sleep(wait);
        byte[] tel = new byte[len + 1];
        System.arraycopy(req, 0, tel, 0, len);
        tel[len] = (byte) KLine.xor(tel, 0, len);
        k.purgeLight();
        k.send(tel);
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

    @Override
    public String interfaceType() { return "OBD"; }
}
