package com.tms.bmwediag.diag;

import com.tms.bmwediag.io.Hex;
import com.tms.bmwediag.io.SerialPort;
import com.tms.bmwediag.proto.Ds2Channel;
import com.tms.bmwediag.proto.EcuChannel;
import com.tms.bmwediag.proto.Elm327;
import com.tms.bmwediag.proto.Iso9141Channel;
import com.tms.bmwediag.proto.KLine;
import com.tms.bmwediag.proto.KwpChannel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** เชื่อมกล่องตามนิยาม EcuDef ผ่าน USB K+DCAN หรือ ELM327 แล้วให้บริการอ่านข้อมูล/โค้ด */
public class DiagSession {
    public enum ObdInit { AUTO, FAST, SLOW }

    public static class Options {
        public boolean echo = true;
        public boolean dtr = true;
        public boolean rts = false;
        public ObdInit obdInit = ObdInit.AUTO;
        public int[] obdPids = null; // null = ทุกตัวที่รองรับ
        /** log TX/RX ทุกเฟรมหลังเชื่อมต่อ (ช้าลง) */
        public boolean trace = false;
        /** หน่วงระหว่างคำสั่ง KWP/ISO (ms) มาตรฐาน 55 */
        public int p3Ms = 25;
        /** โฟลเดอร์ไฟล์ SGBD ของผู้ใช้ (null = ไม่ใช้) */
        public com.tms.bmwediag.sgbd.SgbdRunner.FileSource sgbdFiles;
        /** job สถานะที่ใช้ทำ live data ในโหมด SGBD */
        public String sgbdLiveJob;
    }

    public static class Reading {
        public final String name, unit, text;
        public final Double value;
        public Reading(String name, String unit, Double value, String text) {
            this.name = name; this.unit = unit; this.value = value; this.text = text;
        }
    }

    private final EcuDef ecu;
    private final Options opt;
    private final KLine.Logger logger;
    private SerialPort serial;
    private Elm327 elm;
    private EcuChannel ch;
    private boolean[] supported;
    private KLine kline;
    private com.tms.bmwediag.sgbd.SgbdRunner sgbd;

    public boolean isSgbd() { return sgbd != null && sgbd.ready(); }

    public com.tms.bmwediag.sgbd.SgbdRunner sgbd() { return sgbd; }

    public void setSgbdLiveJob(String j) { opt.sgbdLiveJob = j; }
    /** โปรโตคอลที่ใช้จริงหลังเชื่อมต่อ (AUTO จะกลายเป็น DS2 หรือ KWP) */
    private EcuDef.Protocol proto;

    public EcuDef.Protocol protocol() { return proto; }
    private final java.util.Map<Integer, Integer> pidFails = new java.util.HashMap<>();

    public void setObdPids(int[] pids) { opt.obdPids = pids; }

    public DiagSession(EcuDef ecu, Options opt, KLine.Logger logger) {
        this.ecu = ecu;
        this.opt = opt;
        this.logger = logger;
    }

    public EcuDef ecu() { return ecu; }

    public String describe() {
        if (isSgbd()) return "SGBD " + sgbd.variantName + " @0x" + String.format("%02X", ecu.address) + (elm != null ? " (ELM327 D-CAN)" : " (K+DCAN)");
        return ch == null ? "-" : ch.describe();
    }

    /** เชื่อมต่อผ่านสาย K+DCAN (FTDI) */
    public void connectSerial(SerialPort port) throws IOException {
        serial = port;
        port.open();
        port.setDtr(opt.dtr);
        port.setRts(opt.rts);
        KLine k = new KLine(port);
        kline = k;
        k.echo = opt.echo;
        k.logger = logger;
        // ---- โหมด SGBD ก่อน (ถ้าผู้ใช้เลือกโฟลเดอร์ SGBD และกล่องนี้มีชื่อ SGBD) ----
        if (opt.sgbdFiles != null && !ecu.sgbd.isEmpty()) {
            try {
                k.configure(9600, SerialPort.PARITY_EVEN);
                com.tms.bmwediag.sgbd.SgbdRunner r = new com.tms.bmwediag.sgbd.SgbdRunner(opt.sgbdFiles,
                        new com.tms.bmwediag.sgbd.KLineInterface(k), this::log);
                String v = r.identify(ecu.address, sgbdBaseName());
                r.identLines(); // ทดสอบว่ากล่องตอบจริง
                sgbd = r;
                proto = EcuDef.Protocol.DS2;
                log("เชื่อมต่อด้วย SGBD: " + v);
                k.trace = opt.trace;
                return;
            } catch (Exception ex) {
                log("SGBD ใช้ไม่ได้ (" + ex.getMessage() + ") — ใช้โหมดสำรอง");
                sgbd = null;
                k.purge();
            }
        }
        proto = ecu.protocol;
        if (proto == EcuDef.Protocol.AUTO) {
            // ลอง DS2 ก่อน ถ้าไม่ตอบค่อยลอง KWP2000 (โมดูลรุ่นหลังของ E46 เช่น DSC MK60)
            k.configure(ecu.baud, ecu.parity);
            Ds2Channel dc = new Ds2Channel(k, ecu.address);
            dc.timeoutMs = 800;
            if (ds2Present(dc)) {
                proto = EcuDef.Protocol.DS2;
            } else {
                log("DS2 ไม่ตอบ — ลอง KWP2000");
                proto = EcuDef.Protocol.KWP;
            }
        }
        switch (proto) {
            case DS2:
                k.configure(ecu.baud, ecu.parity);
                ch = new Ds2Channel(k, ecu.address);
                break;
            case KWP: {
                k.configure(10400, SerialPort.PARITY_NONE);
                KwpChannel kc = new KwpChannel(k, ecu.address, false);
                kc.p3Ms = opt.p3Ms;
                kc.timeoutMs = 400;
                kc.start();
                ch = kc;
                break;
            }
            case OBD: {
                k.configure(10400, SerialPort.PARITY_NONE);
                IOException last = null;
                if (opt.obdInit != ObdInit.SLOW) {
                    try {
                        KwpChannel kc = new KwpChannel(k, 0x33, true);
                        kc.p3Ms = opt.p3Ms;
                kc.timeoutMs = 400;
                        kc.start();
                        ch = kc;
                    } catch (IOException e) { last = e; log("fast init ไม่สำเร็จ: " + e.getMessage()); }
                }
                if (ch == null && opt.obdInit != ObdInit.FAST) {
                    try {
                        Iso9141Channel ic = new Iso9141Channel(k);
                        ic.p3Ms = opt.p3Ms;
                        ic.start();
                        ch = ic;
                    } catch (IOException e) { last = e; log("5-baud init ไม่สำเร็จ: " + e.getMessage()); }
                }
                if (ch == null) throw last != null ? last : new IOException("init ไม่สำเร็จ");
                break;
            }
        }
        log("เชื่อมต่อแล้ว: " + ch.describe());
        if (proto == EcuDef.Protocol.DS2 && opt.sgbdFiles != null && sgbd == null && ecu.sgbd.isEmpty()) {
            try {
                com.tms.bmwediag.sgbd.SgbdRunner r = new com.tms.bmwediag.sgbd.SgbdRunner(opt.sgbdFiles,
                        new com.tms.bmwediag.sgbd.KLineInterface(k), this::log);
                String fallback = ecu.sgbd.replaceAll("[^A-Za-z0-9_].*$", "");
                String v = r.identify(ecu.address, fallback);
                sgbd = r;
                log("ใช้ SGBD: " + v);
            } catch (Exception e) {
                log("ใช้ SGBD ไม่ได้ (" + e.getMessage() + ") — ใช้โหมดในตัวแทน");
                k.configure(ecu.baud, ecu.parity);
            }
        }
        k.trace = opt.trace;
    }

    /** มีโมดูลตอบที่ address นี้ไหม (นับคำตอบ A1/A2/FF ว่ามีโมดูลด้วย) */
    static boolean ds2Present(Ds2Channel c) {
        for (int i = 0; i < 2; i++) {
            try { c.request(new byte[]{0x00}); return true; }
            catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("(A2)") || m.contains("(FF)") || m.contains("busy")) return true;
            }
        }
        return false;
    }

    public void setTrace(boolean t) {
        opt.trace = t;
        if (kline != null) kline.trace = t;
        if (elm != null) elm.trace = t;
    }

    /** เชื่อมต่อผ่าน ELM327 */
    private String sgbdBaseName() { return ecu.sgbd.replaceAll("[^A-Za-z0-9_].*$", ""); }

    public void connectElm(Elm327 e) throws IOException {
        elm = e;
        e.logger = logger;
        // ---- SGBD ผ่าน ELM327 บน D-CAN (เช่น MEV9.2 ของ E90) ----
        if (opt.sgbdFiles != null && !ecu.sgbd.isEmpty() && ecu.can) {
            try {
                com.tms.bmwediag.sgbd.SgbdRunner r = new com.tms.bmwediag.sgbd.SgbdRunner(opt.sgbdFiles,
                        new com.tms.bmwediag.sgbd.ElmCanInterface(e), this::log);
                r.useFile(sgbdBaseName() + ".prg");
                r.identLines();
                sgbd = r;
                proto = EcuDef.Protocol.KWP;
                log("เชื่อมต่อด้วย SGBD ผ่าน ELM327 D-CAN: " + r.variantName);
                e.trace = opt.trace;
                return;
            } catch (Exception ex) {
                log("SGBD ผ่าน ELM ใช้ไม่ได้ (" + ex.getMessage() + ") — ใช้ OBD2 แทน");
                sgbd = null;
            }
        }
        proto = ecu.protocol == EcuDef.Protocol.AUTO ? EcuDef.Protocol.KWP : ecu.protocol;
        switch (proto) {
            case DS2:
                throw new IOException("ELM327 ใช้โปรโตคอล DS2 (9600 8E1) ไม่ได้ — ใช้สาย K+DCAN USB หรือเลือก \"OBD2 มาตรฐาน\"");
            case KWP:
                e.initKwp(ecu.address);
                break;
            case OBD:
                e.initObd();
                break;
        }
        ch = e;
        log("เชื่อมต่อแล้ว: " + e.describe());
        e.trace = opt.trace;
    }

    private void log(String s) { if (logger != null) logger.log(s); }

    public byte[] raw(byte[] payload) throws IOException { return ch.request(payload); }

    /** รัน job SGBD ตามชื่อ ("JOB;arg1;arg2") คืนผลเป็นข้อความ */
    public List<String> runJob(String spec) throws IOException {
        if (!isSgbd()) throw new IOException("ยังไม่ได้ใช้โหมด SGBD");
        int i = spec.indexOf(';');
        String job = i < 0 ? spec.trim() : spec.substring(0, i).trim();
        String args = i < 0 ? "" : spec.substring(i + 1);
        List<String> out = new ArrayList<>();
        try {
            List<java.util.Map<String, com.tms.bmwediag.sgbd.Sgbd.Result>> r = sgbd.run(job, args);
            for (int s = 1; s < r.size(); s++) {
                if (r.size() > 2) out.add("— ชุดที่ " + s + " —");
                for (com.tms.bmwediag.sgbd.Sgbd.Result x : r.get(s).values()) out.add(x.name + " = " + x.asText());
            }
            if (r.size() <= 1) out.add("(ไม่มีผลลัพธ์)");
        } catch (com.tms.bmwediag.sgbd.SgbdException e) {
            throw new IOException(e.getMessage());
        }
        return out;
    }

    public String rawElmCommand(String at) throws IOException {
        if (elm == null) throw new IOException("คำสั่ง AT ใช้ได้เฉพาะ ELM327");
        return elm.command(at, 3000);
    }

    // ---------- ข้อมูลกล่อง ----------
    public List<String> readIdent() throws IOException {
        if (isSgbd()) {
            try {
                List<String> l = new ArrayList<>();
                l.add("โมดูล: " + ecu.name);
                l.addAll(sgbd.identLines());
                return l;
            } catch (com.tms.bmwediag.sgbd.SgbdException e) { throw new IOException(e.getMessage()); }
        }
        List<String> out = new ArrayList<>();
        if (proto == EcuDef.Protocol.OBD) {
            out.add("ช่องทาง: " + ch.describe());
            try {
                String raw = asciiAll(ch.requestMulti(new byte[]{0x09, 0x02}));
                String vin = Ds2Ident.findVin(raw.toUpperCase());
                out.add("เลขตัวถัง VIN: " + (vin != null ? vin : raw.isEmpty() ? "กล่องไม่ส่งค่า" : raw));
            } catch (IOException e) {
                out.add("เลขตัวถัง VIN: กล่องรุ่นนี้ไม่รองรับการอ่าน VIN ทาง OBD (" + e.getMessage() + ")");
            }
            try { out.add("CALID: " + asciiAll(ch.requestMulti(new byte[]{0x09, 0x04}))); } catch (IOException e) { out.add("CALID: " + e.getMessage()); }
            try { out.add("CVN: " + Hex.of(ch.request(new byte[]{0x09, 0x06}))); } catch (IOException e) { out.add("CVN: " + e.getMessage()); }
            return out;
        }
        out.add("โมดูล: " + ecu.name);
        if (!ecu.sgbd.isEmpty()) out.add("SGBD: " + ecu.sgbd);
        out.add("ช่องทาง: " + ch.describe());
        out.add("");
        List<byte[]> reqs = ecu.ident;
        if (proto == EcuDef.Protocol.KWP && ecu.protocol == EcuDef.Protocol.AUTO) {
            reqs = new ArrayList<>();
            reqs.add(new byte[]{0x1A, (byte) 0x80});
        }
        for (byte[] req : reqs) {
            try {
                byte[] r = ch.request(req);
                if (proto == EcuDef.Protocol.DS2 && req.length == 1 && req[0] == 0) {
                    out.addAll(Ds2Ident.decode(r));
                } else {
                    String a = Hex.ascii(r, 2);
                    String vin = Ds2Ident.findVin(a);
                    if (vin != null) out.add("VIN: " + vin);
                    out.add("[" + Hex.of(req) + "] ข้อมูล: " + a.replaceAll("\\.{2,}", " ").trim());
                    out.add("HEX: " + Hex.of(r));
                }
            } catch (IOException e) {
                out.add("[" + Hex.of(req) + "] " + e.getMessage());
            }
        }
        out.add("");
        out.add("* เลข VIN เต็ม 17 หลักอ่านได้จาก \"เครื่องยนต์ DME — OBD2\" → ข้อมูลกล่อง");
        return out;
    }

    private static String asciiAll(List<byte[]> frames) {
        StringBuilder sb = new StringBuilder();
        for (byte[] f : frames) sb.append(asciiAfter(f, 2));
        return sb.toString();
    }

    private static String asciiAfter(byte[] r, int skip) {
        // K-line: 49 02 NN data ; CAN: 49 02 01 data
        StringBuilder sb = new StringBuilder();
        for (int i = Math.min(skip, r.length); i < r.length; i++) {
            int c = r[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.toString();
    }

    // ---------- fault ----------
    public List<Dtc.Code> readFaults() throws IOException {
        if (isSgbd()) {
            try {
                List<Dtc.Code> l = new ArrayList<>();
                for (com.tms.bmwediag.sgbd.SgbdRunner.Fault f : sgbd.faults())
                    l.add(new Dtc.Code(f.code, f.text + (f.detail.isEmpty() ? "" : "\n      " + f.detail)));
                if (l.isEmpty()) l.add(new Dtc.Code("✓", "ไม่มีโค้ด fault"));
                return l;
            } catch (com.tms.bmwediag.sgbd.SgbdException e) { throw new IOException(e.getMessage()); }
        }
        switch (proto) {
            case OBD: {
                List<byte[]> fr;
                try {
                    fr = ch.requestMulti(new byte[]{0x03});
                } catch (IOException e) {
                    if ("NO DATA".equals(e.getMessage())) fr = new ArrayList<>();
                    else throw e;
                }
                boolean can = elm != null && elm.isCan();
                List<Dtc.Code> c = Dtc.parseObd(fr, can, "");
                // โหมด 07: โค้ดที่ตรวจพบแต่ยังไม่ยืนยัน (ยังไม่ขึ้นไฟเตือน)
                try {
                    for (Dtc.Code p : Dtc.parseObd(ch.requestMulti(new byte[]{0x07}), can, "  [รอยืนยัน — ยังไม่ขึ้นไฟเตือน]")) {
                        boolean dup = false;
                        for (Dtc.Code x : c) if (x.code.equals(p.code)) dup = true;
                        if (!dup) c.add(p);
                    }
                } catch (IOException ignored) {}
                if (c.isEmpty()) c.add(new Dtc.Code("✓", "ไม่มีโค้ด fault ในกล่องเครื่อง"));
                return c;
            }
            case KWP: {
                byte[] r = ch.request(ecu.faultRead != null && ecu.protocol == EcuDef.Protocol.KWP ? ecu.faultRead : new byte[]{0x18, 0x02, (byte) 0xFF, (byte) 0xFF});
                List<Dtc.Code> c = Dtc.parseKwp(r);
                if (c.isEmpty()) c.add(new Dtc.Code("—", "ไม่มีโค้ด"));
                return c;
            }
            default: {
                if (ecu.faultReads.isEmpty()) throw new IOException("ยังไม่ได้กำหนด faultRead ในนิยามกล่องนี้");
                if (ecu.ds2FaultEntrySize > 0 && ecu.faultRead != null)
                    return Dtc.parseDs2(ch.request(ecu.faultRead), ecu.ds2FaultHeader, ecu.ds2FaultEntrySize, ecu.faultText);
                List<byte[]> resp = new ArrayList<>();
                StringBuilder trace = new StringBuilder();
                int count = -1;
                IOException last = null;
                for (String t : ecu.faultReads) {
                    List<String> tels = new ArrayList<>();
                    if (t.toUpperCase().contains("XX")) {
                        int n = count < 0 ? 1 : Math.min(count, 30);
                        for (int i = 1; i <= n; i++) tels.add(t.toUpperCase().replace("XX", String.format("%02X", i)));
                    } else tels.add(t);
                    for (String tel : tels) {
                        try {
                            byte[] r = ch.request(Hex.parse(tel));
                            resp.add(r);
                            trace.append("   ส่ง ").append(tel).append(" → ").append(Hex.of(r)).append('\n');
                            if (r.length == 2 && count < 0) count = r[1] & 0xFF;
                        } catch (IOException e) {
                            last = e;
                            trace.append("   ส่ง ").append(tel).append(" → ").append(e.getMessage()).append('\n');
                        }
                    }
                }
                log("FS:\n" + trace);
                if (resp.isEmpty()) throw last != null ? last : new IOException("ไม่มีคำตอบ");
                List<Dtc.Code> res = Ds2Faults.decode(resp, ecu.faultText);
                for (int i = 0; i < res.size(); i++) {
                    if (res.get(i).code.equals("ข้อมูลดิบ")) {
                        res.set(i, new Dtc.Code("ข้อมูลดิบ", (count > 0 ? "กล่องแจ้งว่ามี " + count + " โค้ด แต่ยังแยกรหัสไม่ได้\n" : "")
                                + trace.toString().trim() + "\n   (แคปหน้านี้ส่งให้ผู้พัฒนาเพื่อปรับรูปแบบ)"));
                    }
                }
                return res;
            }
        }
    }

    public String clearFaults() throws IOException {
        if (isSgbd()) {
            try { return sgbd.clearFaults(); }
            catch (com.tms.bmwediag.sgbd.SgbdException e) { throw new IOException(e.getMessage()); }
        }
        byte[] req;
        boolean auto = ecu.protocol == EcuDef.Protocol.AUTO;
        switch (proto) {
            case OBD: req = new byte[]{0x04}; break;
            case KWP: req = ecu.faultClear != null && !auto ? ecu.faultClear : new byte[]{0x14, (byte) 0xFF, (byte) 0xFF}; break;
            default:
                if (ecu.faultClear == null) throw new IOException("ยังไม่ได้กำหนด faultClear");
                req = ecu.faultClear;
        }
        byte[] r = ch.request(req);
        int s = r.length > 0 ? r[0] & 0xFF : 0;
        boolean ok = s == (req[0] & 0xFF) + 0x40 || s == 0xA0;
        if (ok) {
            supported = null; // รีเซ็ตเผื่อกล่องเริ่มใหม่
            return "✓ ลบโค้ด fault สำเร็จ\n\nไฟเช็คเอนจิ้นจะดับ และค่าเรียนรู้ (fuel trim) อาจถูกรีเซ็ต — ขับสักระยะให้กล่องเรียนรู้ใหม่"
                    + "\nถ้าโค้ดกลับมาอีก แปลว่าปัญหายังอยู่";
        }
        return "กล่องตอบกลับไม่ตรงที่คาดไว้ (" + Hex.of(r) + ") — ลองอ่านโค้ดอีกครั้งเพื่อตรวจสอบ";
    }

    // ---------- live data ----------
    public List<ObdPids.Pid> obdPidList() throws IOException {
        if (supported == null) {
            byte[][] masks = new byte[3][];
            int[] bases = {0x00, 0x20, 0x40};
            // ถามแบบรอทุกกล่อง แล้วเลือกกล่องที่รองรับ PID มากที่สุด (= DME) ล็อกไว้
            try {
                List<byte[]> all = ch.requestMulti(new byte[]{0x01, 0x00});
                List<Integer> src = ch.lastSources();
                int best = 0, bestBits = -1;
                for (int i = 0; i < all.size(); i++) {
                    int bits = 0;
                    byte[] m = all.get(i);
                    for (int j = 2; j < m.length && j < 6; j++) bits += Integer.bitCount(m[j] & 0xFF);
                    if (bits > bestBits) { bestBits = bits; best = i; }
                }
                if (best < src.size() && src.get(best) >= 0) {
                    ch.setSourceFilter(src.get(best));
                    log(String.format("ตอบ OBD %d กล่อง — ล็อกที่ ECU 0x%02X", all.size(), src.get(best)));
                }
            } catch (IOException e) { log("0100: " + e.getMessage()); }
            for (int i = 0; i < 3; i++) {
                try { masks[i] = ch.request(new byte[]{0x01, (byte) bases[i]}); }
                catch (IOException e) { break; }
                if (masks[i].length < 6 || (masks[i][5] & 1) == 0) break;
            }
            supported = ObdPids.supported(masks);
        }
        List<ObdPids.Pid> l = new ArrayList<>();
        for (ObdPids.Pid p : ObdPids.ALL) {
            if (p.pid >= supported.length || !supported[p.pid]) continue;
            if (opt.obdPids != null) {
                boolean sel = false;
                for (int x : opt.obdPids) if (x == p.pid) sel = true;
                if (!sel) continue;
            }
            l.add(p);
        }
        return l;
    }

    public List<Reading> pollLive() throws IOException {
        List<Reading> out = new ArrayList<>();
        if (isSgbd()) {
            String jobsSpec = opt.sgbdLiveJob;
            if (jobsSpec == null || jobsSpec.isEmpty()) {
                List<String> js = sgbd.statusJobs();
                if (js.isEmpty()) return out;
                String[] pref = {"STATUS_MESSWERTE", "STATUS_MESSWERTEBLOCK", "STATUS_LESEN", "STATUS_ANALOG"};
                jobsSpec = js.get(0);
                for (String p : pref) if (sgbd.hasJob(p)) { jobsSpec = p; break; }
                opt.sgbdLiveJob = jobsSpec;
            }
            for (String job : jobsSpec.split(",")) {
            job = job.trim();
            if (job.isEmpty() || !sgbd.hasJob(job)) continue;
            try {
                List<java.util.Map<String, com.tms.bmwediag.sgbd.Sgbd.Result>> r = sgbd.run(job, "");
                for (int s = 1; s < r.size(); s++) {
                    java.util.Map<String, com.tms.bmwediag.sgbd.Sgbd.Result> set = r.get(s);
                    for (com.tms.bmwediag.sgbd.Sgbd.Result x : set.values()) {
                        if (com.tms.bmwediag.sgbd.SgbdRunner.isMeta(x.name) || x.name.toUpperCase().endsWith("_EINH")) continue;
                        String unit = "";
                        String base = x.name.replaceAll("(?i)_WERT$", "");
                        com.tms.bmwediag.sgbd.Sgbd.Result u = set.get((base + "_EINH").toUpperCase());
                        if (u != null) unit = u.asText();
                        Double v = x.value instanceof Long ? Double.valueOf((Long) x.value) : x.value instanceof Double ? (Double) x.value : null;
                        out.add(new Reading(base, unit, v, x.asText()));
                    }
                }
            } catch (com.tms.bmwediag.sgbd.SgbdException e) {
                out.add(new Reading(job, "", null, "ERR " + e.getMessage()));
            }
            }
            return out;
        }
        if (proto == EcuDef.Protocol.OBD) {
            for (ObdPids.Pid p : obdPidList()) {
                Double v = null;
                int fails = pidFails.containsKey(p.pid) ? pidFails.get(p.pid) : 0;
                // PID ที่ไม่ตอบติดกัน 3 ครั้ง: ข้ามไป ถามซ้ำทุกๆ 20 รอบ (ไม่ให้เสีย timeout ทุกรอบ)
                if (fails >= 3 && (fails++ % 20) != 0) {
                    pidFails.put(p.pid, fails);
                    out.add(new Reading(p.name, p.unit, null, "ไม่ตอบ"));
                    continue;
                }
                try {
                    v = p.decode(ch.requestExpect(new byte[]{0x01, (byte) p.pid}, 2 + p.bytes));
                    pidFails.put(p.pid, 0);
                } catch (IOException ignored) {
                    pidFails.put(p.pid, fails + 1);
                }
                String txt = v == null ? "--" : p.pid == 0x03 ? ObdPids.fuelStatus(v.intValue())
                        : String.format("%." + p.decimals + "f", v);
                out.add(new Reading(p.name, p.unit, v, txt));
            }
            return out;
        }
        if (proto == EcuDef.Protocol.KWP && ecu.protocol == EcuDef.Protocol.AUTO) return out;
        for (LiveRequest lr : ecu.live) {
            byte[] r;
            try { r = ch.request(lr.request); }
            catch (IOException e) {
                out.add(new Reading("[" + Hex.of(lr.request) + "]", "", null, "ERR"));
                continue;
            }
            byte[] d = new byte[Math.max(0, r.length - lr.skip)];
            System.arraycopy(r, Math.min(lr.skip, r.length), d, 0, d.length);
            for (LiveParam p : lr.params) {
                Double v = p.decode(d);
                out.add(new Reading(p.name, p.unit, v, p.format(v)));
            }
            if (lr.showRaw) {
                for (int i = 0; i < d.length; i++) {
                    int b = d[i] & 0xFF;
                    out.add(new Reading(String.format("[%s] B%02d", Hex.of(lr.request), i), "",
                            (double) b, String.format("%02X  (%d)", b, b)));
                }
            }
        }
        return out;
    }

    /** สแกนโมดูล DS2 ทั้งหมด (ถามซ้ำ 3 ครั้ง/โมดูล) — ต้องเชื่อมต่อด้วยสาย USB */
    public List<String> scanDs2(List<EcuDef> defs, KLine.Logger progress) throws IOException {
        if (kline == null) throw new IOException("การสแกนต้องใช้สาย K+DCAN (USB) — ELM327 ทำ DS2 ไม่ได้");
        List<String> ok = new ArrayList<>(), missing = new ArrayList<>();
        int found = 0;
        for (EcuDef d : defs) {
            if (d.protocol != EcuDef.Protocol.DS2 && d.protocol != EcuDef.Protocol.AUTO) continue;
            if (progress != null) progress.log("กำลังถาม " + d.name + " ...");
            kline.configure(9600, SerialPort.PARITY_EVEN);
            kline.purge();
            KLine.sleep(60);
            Ds2Channel c = new Ds2Channel(kline, d.address);
            c.timeoutMs = 1000;
            String line = null;
            // ถามซ้ำสูงสุด 3 ครั้ง — บางโมดูล (เช่น IKE) ไม่ตอบคำถามแรกหลังเปลี่ยน address
            for (int attempt = 0; attempt < 3 && line == null; attempt++) {
                try {
                    byte[] r = c.request(new byte[]{0x00});
                    String pn = Ds2Ident.partNumber(r);
                    List<String> dec = Ds2Ident.decode(r);
                    line = "✓ " + d.name + "\n     " + (pn != null ? "เลขชิ้นส่วน " + pn : dec.get(0));
                } catch (IOException e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("(A2)") || m.contains("(FF)") || m.contains("busy")) line = "✓ " + d.name;
                    else KLine.sleep(150);
                }
            }
            if (line != null) { found++; ok.add(line); }
            else missing.add("✗ " + d.name);
        }
        List<String> out = new ArrayList<>();
        out.add("พบ " + found + " โมดูล\n");
        out.addAll(ok);
        if (!missing.isEmpty()) {
            out.add("\nไม่ตอบ (รถคันนี้อาจไม่มี หรือ address ต่างรุ่น):");
            out.addAll(missing);
        }
        if (proto != EcuDef.Protocol.DS2) {
            out.add("\n* การสแกนเปลี่ยนความเร็วสาย — กดตัดการเชื่อมต่อแล้วเชื่อมต่อใหม่ก่อนใช้งานต่อ");
        } else {
            kline.configure(ecu.baud, ecu.parity);
        }
        return out;
    }

    public void close() {
        try { if (ch != null) ch.close(); } catch (Exception ignored) {}
        if (serial != null) serial.close();
        ch = null;
    }
}
