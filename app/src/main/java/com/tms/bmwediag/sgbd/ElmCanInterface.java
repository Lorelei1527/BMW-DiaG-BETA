package com.tms.bmwediag.sgbd;

import com.tms.bmwediag.io.Hex;
import com.tms.bmwediag.proto.Elm327;

import java.io.IOException;
import java.util.List;

/**
 * ช่องสื่อสาร SGBD ผ่าน ELM327 บน D-CAN ของ BMW (E9x)
 * แปลง telegram KWP2000* และ BMW-FAST [fmt][tgt][src][len][data] เป็น CAN 0x6F1 + extended address
 * และคืนคำตอบเป็น [80][F1][tgt][len][data][XOR] ให้ SGBD
 */
public class ElmCanInterface extends EdInterface {
    private final Elm327 elm;
    private int currentTarget = -1;
    private boolean initDone;
    private int concept = 0x010D;

    public ElmCanInterface(Elm327 elm) { this.elm = elm; }

    @Override
    public void setCommParameter(long[] par) throws SgbdException {
        commParameter = par;
        if (par.length == 0) return;
        concept = (int) par[0];
        if (concept != 0x010D && concept != 0x010F && concept != 0x0110 && concept != 0x010C) {
            SgbdBridge.setError(ediabas, SgbdException.Err.ifh(14));
        }
    }

    private void init() throws IOException {
        if (initDone) return;
        elm.command("ATZ", 3000);
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAL", "ATCAF1", "ATSP6", "ATST64", "ATSH6F1", "ATFCSH6F1", "ATFCSM1"})
            elm.command(c, 1000);
        initDone = true;
    }

    private void selectTarget(int tgt) throws IOException {
        if (tgt == currentTarget) return;
        String t = String.format("%02X", tgt);
        elm.command("ATCEA" + t, 1000);
        elm.command("ATCRA6" + t, 1000);
        elm.command("ATFCSD" + t + "300000", 1000);
        currentTarget = tgt;
    }

    @Override
    public byte[] transmit(byte[] req) throws SgbdException {
        byte[] hit = cached(req);
        if (hit != null) return hit;
        byte[] r = transmitReal(req);
        remember(req, r);
        return r;
    }

    private byte[] transmitReal(byte[] req) throws SgbdException {
        try {
            init();
            if (req.length < 4) throw new IOException("telegram too short");
            int tgt = req[1] & 0xFF;
            int len, off;
            boolean fast = concept != 0x010D;
            if (!fast || (req[0] & 0x3F) == 0) { len = req[3] & 0xFF; off = 4; }
            else { len = req[0] & 0x3F; off = 3; } // BMW-FAST: ความยาวใน format byte
            byte[] data = new byte[Math.min(len, req.length - off)];
            System.arraycopy(req, off, data, 0, data.length);
            selectTarget(tgt);
            String r = elm.command(Hex.of(data).replace(" ", ""), 6000);
            List<byte[]> l = Elm327.parse(r, data[0] & 0xFF);
            byte[] p = l.get(0);
            // ตัด extended address (F1) ถ้า ELM ส่งมาด้วย
            if (p.length > 1 && (p[0] & 0xFF) == 0xF1 && ((p[1] & 0xFF) == (data[0] & 0xFF) + 0x40 || (p[1] & 0xFF) == 0x7F)) {
                byte[] q = new byte[p.length - 1];
                System.arraycopy(p, 1, q, 0, q.length);
                p = q;
            }
            return frame(tgt, p, fast);
        } catch (com.tms.bmwediag.proto.NegativeResponseException e) {
            // ส่งคำตอบปฏิเสธให้ SGBD จัดการเอง
            return frame(req.length > 1 ? req[1] & 0xFF : 0, new byte[]{0x7F, (byte) e.service, (byte) e.code}, concept != 0x010D);
        } catch (IOException e) {
            SgbdBridge.setError(ediabas, SgbdException.Err.ifh(9));
            return null;
        }
    }

    /** สร้างคำตอบ: KWP2000* = [80][F1][tgt][len][data][XOR], BMW-FAST = [80|len][F1][tgt][data][SUM] */
    static byte[] frame(int tgt, byte[] p, boolean fast) throws SgbdException {
        if (p.length > 255) throw new SgbdException("คำตอบยาวเกิน 255 ไบต์");
        if (fast) {
            boolean shortLen = p.length < 64;
            int hl = shortLen ? 3 : 4;
            byte[] f = new byte[hl + p.length + 1];
            f[0] = (byte) (shortLen ? 0x80 | p.length : 0x80);
            f[1] = (byte) 0xF1;
            f[2] = (byte) tgt;
            if (!shortLen) f[3] = (byte) p.length;
            System.arraycopy(p, 0, f, hl, p.length);
            int cs = 0;
            for (int i = 0; i < f.length - 1; i++) cs += f[i] & 0xFF;
            f[f.length - 1] = (byte) cs;
            return f;
        }
        byte[] f = new byte[p.length + 5];
        f[0] = (byte) 0x80;
        f[1] = (byte) 0xF1;
        f[2] = (byte) tgt;
        f[3] = (byte) p.length;
        System.arraycopy(p, 0, f, 4, p.length);
        int cs = 0;
        for (int i = 0; i < f.length - 1; i++) cs ^= f[i];
        f[f.length - 1] = (byte) cs;
        return f;
    }

    @Override
    public String interfaceType() { return "ELM327-DCAN"; }
}
