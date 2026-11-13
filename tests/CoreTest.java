import com.tms.bmwediag.diag.*;
import com.tms.bmwediag.io.*;
import com.tms.bmwediag.proto.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** ทดสอบแกนโปรโตคอลด้วย ECU จำลองบน K-line (echo + ตอบกลับ) */
public class CoreTest {
    static int fails = 0;

    static void check(boolean c, String what) {
        System.out.println((c ? "PASS " : "FAIL ") + what);
        if (!c) fails++;
    }

    /** พอร์ตจำลอง: ทุกไบต์ที่ส่งจะ echo กลับ แล้ว handler สร้างคำตอบ */
    static class FakePort implements SerialPort {
        final ArrayDeque<Byte> q = new ArrayDeque<>();
        final Function<byte[], byte[]> handler;
        int breaks = 0; boolean slowPending = false; byte[] slowSync;
        int baud, parity;
        FakePort(Function<byte[], byte[]> h) { handler = h; }
        public void open() {}
        public void close() {}
        public void setParameters(int b, int d, int p, int s) { baud = b; parity = p; }
        public synchronized void setBreak(boolean on) { if (on) breaks++; else if (breaks >= 3 && slowSync != null) slowPending = true; }
        public void setDtr(boolean on) {}
        public void setRts(boolean on) {}
        public synchronized void write(byte[] d) {
            for (byte b : d) q.add(b);
            byte[] r = handler.apply(d);
            if (r != null) for (byte b : r) q.add(b);
        }
        public synchronized int read(byte[] buf, int t) {
            if (q.isEmpty()) { try { wait(Math.min(t, 20)); } catch (InterruptedException e) {} if (q.isEmpty()) return 0; }
            int n = 0;
            while (!q.isEmpty() && n < buf.length) buf[n++] = q.poll();
            return n;
        }
        public synchronized void purge() {
            q.clear();
            if (slowPending) { slowPending = false; breaks = 0; for (byte b : slowSync) q.add(b); }
        }
    }

    static byte[] h(String s) { return Hex.parse(s); }

    public static void main(String[] a) throws Exception {
        // ---- FTDI baud ----
        int[] b96 = FtdiBaud.encode(9600);
        check(b96[0] == 0x4138 && b96[1] == 0, "FTDI 9600 divisor = 0x4138 (" + Integer.toHexString(b96[0]) + ")");
        int[] b104 = FtdiBaud.encode(10400);
        check(Math.abs(FtdiBaud.actual(10400) - 10400) < 10400 * 0.01, "FTDI 10400 error <1% (" + FtdiBaud.actual(10400) + ") enc=" + Integer.toHexString(b104[0]));

        // ---- DS2 ----
        check(Hex.of(Ds2Channel.frame(0x12, h("00"))).equals("12 04 00 16"), "DS2 ident MS43 = 12 04 00 16");
        check(Hex.of(Ds2Channel.frame(0x32, h("00"))).equals("32 04 00 36"), "DS2 ident EGS = 32 04 00 36");
        FakePort ds2 = new FakePort(d -> {
            if (Hex.of(d).equals("12 04 00 16")) return Ds2Channel.frame(0x12, h("A0 37 35 31 39 33 30 38"));
            if (Hex.of(d).equals("12 05 0B 03 1F")) return Ds2Channel.frame(0x12, h("A0 10 20 30 40"));
            return Ds2Channel.frame(0x12, h("FF"));
        });
        KLine k = new KLine(ds2);
        Ds2Channel dc = new Ds2Channel(k, 0x12);
        byte[] r = dc.request(h("00"));
        check(Hex.ascii(r, 1).equals("7519308"), "DS2 ident parse ASCII = " + Hex.ascii(r, 1));
        try { dc.request(h("99")); check(false, "DS2 FF should throw"); } catch (IOException e) { check(true, "DS2 FF -> exception: " + e.getMessage()); }

        // ---- KWP2000 ----
        check(Hex.of(KwpChannel.frame(0x12, 0xF1, false, h("81"))).equals("81 12 F1 81 05"), "KWP StartComm frame = 81 12 F1 81 05");
        check(Hex.of(KwpChannel.frame(0x33, 0xF1, true, h("01 0C"))).equals("C2 33 F1 01 0C F3"), "OBD functional 010C = C2 33 F1 01 0C F3");
        FakePort kwp = new FakePort(d -> {
            try {
                byte[] p = KwpChannel.payloadOf(d);
                String ps = Hex.of(p);
                if (ps.equals("81")) return KwpChannel.frame(0xF1, 0x12, false, h("C1 EF 8F"));
                if (ps.equals("1A 80")) {
                    byte[] pend = KwpChannel.frame(0xF1, 0x12, false, h("7F 1A 78"));
                    byte[] ok = KwpChannel.frame(0xF1, 0x12, false, h("5A 80 37 35 32 33 34 35 36"));
                    byte[] both = new byte[pend.length + ok.length];
                    System.arraycopy(pend, 0, both, 0, pend.length);
                    System.arraycopy(ok, 0, both, pend.length, ok.length);
                    return both;
                }
                if (ps.equals("18 02 FF FF")) return KwpChannel.frame(0xF1, 0x12, false, h("58 02 2A 7C 24 29 CC E4"));
                if (ps.equals("14 FF FF")) return KwpChannel.frame(0xF1, 0x12, false, h("54 FF FF"));
                if (ps.equals("31 99")) return KwpChannel.frame(0xF1, 0x12, false, h("7F 31 11"));
            } catch (IOException e) { throw new RuntimeException(e); }
            return null;
        });
        EcuDef ms45 = null, ms43 = null, obd = null;
        List<EcuDef> defs = DefinitionLoader.parse(new String(Files.readAllBytes(Paths.get("app/src/main/assets/ecus.json")), "UTF-8"));
        check(defs.size() == 7, "ecus.json โหลดได้ " + defs.size() + " กล่อง");
        obd = DefinitionLoader.parse("{\"ecus\":[{\"id\":\"obd\",\"name\":\"obd\",\"protocol\":\"OBD\"}]}").get(0);
        { EcuDef m43=null; for (EcuDef e : defs) if (e.id.equals("ms43")) m43=e; check(m43 != null && m43.sgbd.equals("MS430DS0") && m43.protocol == EcuDef.Protocol.OBD, "MS43: SGBD MS430DS0 + สำรอง OBD"); }
        // นิยามทดสอบ (ไม่อยู่ใน ecus.json แล้ว)
        ms45 = DefinitionLoader.parse("{\"ecus\":[{\"id\":\"ms45\",\"name\":\"t\",\"protocol\":\"KWP\",\"address\":\"12\",\"ident\":[\"1A 80\"],\"faultRead\":\"18 02 FF FF\",\"faultClear\":\"14 FF FF\"}]}").get(0);
        ms43 = DefinitionLoader.parse("{\"ecus\":[{\"id\":\"ms43\",\"name\":\"t\",\"protocol\":\"DS2\",\"address\":\"12\",\"ident\":[\"00\"],\"live\":[{\"request\":\"0B 03\",\"showRaw\":true}]}]}").get(0);
        check(ms43.protocol == EcuDef.Protocol.DS2 && ms43.address == 0x12 && ms43.parity == 2, "DS2 default 8E1");
        DiagSession.Options opt = new DiagSession.Options();
        DiagSession s = new DiagSession(ms45, opt, m -> {});
        s.connectSerial(kwp);
        check(kwp.baud == 10400 && kwp.parity == 0, "KWP ตั้ง 10400 8N1");
        List<String> id = s.readIdent();
        check(String.join("|", id).contains("7523456"), "KWP ident ผ่าน responsePending(78): " + id.get(3));
        List<Dtc.Code> codes = s.readFaults();
        check(codes.size() == 2 && codes.get(0).code.equals("2A7C"), "KWP faults: " + codes);
        check(s.clearFaults().startsWith("✓"), "KWP clear (ข้อความไทย)");
        try { s.raw(h("31 99")); check(false, "neg"); } catch (NegativeResponseException e) { check(e.code == 0x11, "KWP negative response: " + e.getMessage()); }

        // ---- DS2 live via DiagSession (raw bytes) ----
        DiagSession s2 = new DiagSession(ms43, opt, m -> {});
        s2.connectSerial(ds2);
        check(ds2.baud == 9600 && ds2.parity == 2, "DS2 ตั้ง 9600 8E1");
        List<String> scan = s2.scanDs2(defs, null);
        check(scan.get(0).startsWith("พบ"), "สแกน DS2 ทำงาน (จำลอง: ไม่มีโมดูลตอบ) " + scan.size() + " บรรทัด");
        List<DiagSession.Reading> live = s2.pollLive();
        check(live.size() == 4 && live.get(0).text.startsWith("10"), "DS2 live raw bytes = " + live.size() + " ค่า, B00=" + live.get(0).text);

        // ---- OBD fast init + PIDs ----
        FakePort obdPort = new FakePort(d -> {
            try {
                byte[] p = KwpChannel.payloadOf(d);
                String ps = Hex.of(p);
                if (ps.equals("81")) return KwpChannel.frame(0xF1, 0x10, false, h("C1 E9 8F"));
                if (ps.equals("01 00")) return KwpChannel.frame(0xF1, 0x10, false, h("41 00 BE 3E B8 11"));
                if (ps.equals("01 0C")) return KwpChannel.frame(0xF1, 0x10, false, h("41 0C 1A F8"));
                if (ps.equals("01 05")) return KwpChannel.frame(0xF1, 0x10, false, h("41 05 7B"));
                if (ps.equals("03")) return KwpChannel.frame(0xF1, 0x10, false, h("43 01 71 03 00 00 00"));
                if (p[0] == 0x01) return KwpChannel.frame(0xF1, 0x10, false, h("41 " + String.format("%02X", p[1]) + " 80 00"));
            } catch (IOException e) { throw new RuntimeException(e); }
            return null;
        });
        DiagSession s3 = new DiagSession(obd, opt, m -> {});
        s3.connectSerial(obdPort);
        List<DiagSession.Reading> ol = s3.pollLive();
        long t1 = System.currentTimeMillis();
        s3.pollLive();
        check(true, "OBD poll (KWP fast, " + ol.size() + " PID) = " + (System.currentTimeMillis() - t1) + " ms/รอบ");
        DiagSession.Reading rpm = null, ect = null;
        for (DiagSession.Reading x : ol) { if (x.unit.equals("rpm")) rpm = x; if (x.name.equals("อุณหภูมิน้ำ")) ect = x; }
        check(rpm != null && Math.abs(rpm.value - 1726) < 0.1, "OBD RPM 1A F8 = " + (rpm == null ? null : rpm.value));
        check(ect != null && ect.value == 83, "OBD coolant 7B = 83°C");
        List<Dtc.Code> oc = s3.readFaults();
        check(oc.size() == 2 && oc.get(0).code.equals("P0171") && oc.get(1).code.equals("P0300"), "OBD DTC: " + oc);


        // ---- OBD มี 2 กล่องตอบ (DME 0x10 + EGS 0x18) — ต้องล็อก DME ----
        FakePort two = new FakePort(d -> {
            try {
                byte[] p = KwpChannel.payloadOf(d);
                String ps = Hex.of(p);
                if (ps.equals("81")) return KwpChannel.frame(0xF1, 0x10, false, h("C1 E9 8F"));
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] egs, dme;
                if (ps.equals("01 00")) { egs = KwpChannel.frame(0xF1, 0x18, false, h("41 00 08 18 00 00")); dme = KwpChannel.frame(0xF1, 0x10, false, h("41 00 BE 3E B8 10")); }
                else if (ps.equals("01 0C")) { egs = KwpChannel.frame(0xF1, 0x18, false, h("7F 01 12")); dme = KwpChannel.frame(0xF1, 0x10, false, h("41 0C 1A F8")); }
                else if (ps.equals("01 05")) { egs = KwpChannel.frame(0xF1, 0x18, false, h("41 05 50")); dme = KwpChannel.frame(0xF1, 0x10, false, h("41 05 7B")); }
                else if (ps.equals("04")) return KwpChannel.frame(0xF1, 0x10, false, h("44"));
                else if (p[0] == 0x01) { egs = KwpChannel.frame(0xF1, 0x18, false, h("7F 01 12")); dme = KwpChannel.frame(0xF1, 0x10, false, h("41 " + String.format("%02X", p[1]) + " 80 00")); }
                else return null;
                bo.write(egs, 0, egs.length); bo.write(dme, 0, dme.length);
                return bo.toByteArray();
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        DiagSession s4 = new DiagSession(obd, new DiagSession.Options(), m -> {});
        s4.connectSerial(two);
        DiagSession.Reading rpm4 = null, ect4 = null;
        for (DiagSession.Reading x : s4.pollLive()) { if (x.unit.equals("rpm")) rpm4 = x; if (x.name.equals("อุณหภูมิน้ำ")) ect4 = x; }
        check(rpm4 != null && rpm4.value != null && rpm4.value == 1726, "2 กล่อง: RPM จาก DME แม้ EGS ปฏิเสธก่อน = " + (rpm4 == null ? null : rpm4.text));
        check(ect4 != null && ect4.value != null && ect4.value == 83, "2 กล่อง: อุณหภูมิน้ำจาก DME (83) ไม่ใช่ EGS (40) = " + (ect4 == null ? null : ect4.text));
        String clr = s4.clearFaults();
        check(clr.startsWith("✓"), "ลบโค้ด: ข้อความภาษาไทย ไม่ใช่ 44");
        check(DtcThai.describe("P0303").contains("สูบ 3") && DtcThai.describe("P0204").contains("สูบ 4") && !DtcThai.describe("P0455").isEmpty() && !DtcThai.describe("P1234").isEmpty(), "คำอธิบายไทย: P0303/P0204/P0455/P1xxx");
        // ---- OBD 5-baud ----
        FakePort slow = new FakePort(d -> {
            if (d.length == 1 && (d[0] & 0xFF) == 0xF7) return h("CC");
            if (d.length > 3 && (d[0] & 0xFF) == 0x68 && d[3] == 0x01 && d[4] == 0x0C) {
                byte[] f = h("48 6B 10 41 0C 0F A0 00");
                f[7] = (byte) KLine.sum(f, 0, 7);
                return f;
            }
            return null;
        });
        slow.slowSync = h("55 08 08");
        KLine ks = new KLine(slow);
        Iso9141Channel ic = new Iso9141Channel(ks);
        ic.start();
        byte[] rr = ic.request(h("01 0C"));
        check(Hex.of(rr).equals("41 0C 0F A0"), "ISO9141 5-baud + 010C = " + Hex.of(rr));
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) rr = ic.requestExpect(h("01 0C"), 4);
        long dt = (System.currentTimeMillis() - t0) / 10;
        check(Hex.of(rr).equals("41 0C 0F A0") && dt < 80, "ISO9141 requestExpect เร็ว: " + dt + " ms/PID");

        // ---- ELM parse ----
        List<byte[]> e1 = Elm327.parse("SEARCHING...\r41 0C 1A F8", 0x01);
        check(Hex.of(e1.get(0)).equals("41 0C 1A F8"), "ELM parse 010C");
        List<byte[]> e2 = Elm327.parse("014\r0: 49 02 01 57 42 41\r1: 41 42 43 44 45 46 47\r2: 48 49 4A 4B 4C 4D 4E", 0x09);
        check(Hex.ascii(e2.get(0), 3).startsWith("WBAABCDEFG"), "ELM CAN multi-frame VIN = " + Hex.ascii(e2.get(0), 3));
        List<Dtc.Code> ec = Dtc.parseObd(Elm327.parse("43 02 01 71 03 00", 0x03), true);
        check(ec.size() == 2 && ec.get(0).code.equals("P0171"), "ELM CAN DTC count-byte = " + ec);
        try { Elm327.parse("7F 22 31", 0x22); check(false, "x"); } catch (NegativeResponseException e) { check(true, "ELM negative"); }

        // ---- DS2 ident decode (ข้อมูลจริงจาก LCM/EWS ของผู้ใช้) ----
        List<String> lcm = Ds2Ident.decode(h("A0 06 92 50 82 22 34 30 00 20 03 09 35"));
        check(lcm.get(0).equals("เลขชิ้นส่วน BMW: 6 925 082") && lcm.get(2).contains("สัปดาห์ที่ 20 ปี 2003"), "LCM ident: " + lcm.get(0) + " / " + lcm.get(2));
        List<String> ews = Ds2Ident.decode(h("A0 86 90 56 67 03 81 81 07 19 03 06 08"));
        check(ews.get(0).equals("เลขชิ้นส่วน BMW: 6 905 667"), "EWS ident: " + ews.get(0));
        List<String> egs = Ds2Ident.decode(("_7521759290010603801").getBytes());
        check(egs.get(0).contains("7 521 759"), "EGS ident ASCII: " + egs.get(0));
        check("WBAAV51060FJ12345".equals(Ds2Ident.findVin("..WBAAV51060FJ12345..")), "หา VIN 17 หลัก");
        EcuDef ft = DefinitionLoader.parse("{\"ecus\":[{\"id\":\"x\",\"name\":\"x\",\"protocol\":\"AUTO\",\"address\":\"D0\",\"faultEntrySize\":2,\"faultText\":{\"0x12\":\"หลอดไฟหน้าซ้ายขาด\"}}]}").get(0);
        List<Dtc.Code> fc = Dtc.parseDs2(h("A0 12 05 00 00"), 1, 2, ft.faultText);
        check(ft.protocol == EcuDef.Protocol.AUTO && ft.parity == 2 && fc.get(0).text.startsWith("หลอดไฟหน้าซ้ายขาด"), "faultText ไทย: " + fc);

        // ---- Ds2Faults heuristic ----
        java.util.Map<Integer,String> tb = new java.util.HashMap<>();
        tb.put(0x21, "ไฟเบรกซ้ายขาด"); tb.put(0x35, "ไฟหน้าขวาขาด"); tb.put(0x0A, "RAM");
        List<Dtc.Code> g1 = Ds2Faults.decode(java.util.Arrays.asList(h("A0 02 21 01 00 35 22 00")), tb);
        check(g1.size() == 2 && g1.get(0).code.equals("21") && g1.get(1).code.equals("35"), "Ds2Faults [count][code art x] x2: " + g1);
        List<Dtc.Code> g2 = Ds2Faults.decode(java.util.Arrays.asList(h("A0 00")), tb);
        check(g2.get(0).code.equals("✓"), "Ds2Faults ไม่มีโค้ด");
        EcuDef gsDef = null; for (EcuDef e : defs) if (e.id.equals("gs20")) gsDef = e;
        check(gsDef != null && gsDef.faultText.size() > 30 && gsDef.address == 0x32, "GS20 มีตาราง Thai " + (gsDef == null ? 0 : gsDef.faultText.size()) + " โค้ด");

        // ---- LiveParam ----
        LiveParam lp = new LiveParam();
        lp.offset = 1; lp.length = 2; lp.signed = true; lp.factor = 0.1;
        check(Math.abs(lp.decode(h("00 FF 9C")) + 10.0) < 1e-9, "LiveParam signed 16-bit FF9C*0.1 = -10.0");

        // ---- CSV ----
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        CsvLogger cl = new CsvLogger(bo);
        cl.write(ol); cl.write(ol); cl.close();
        String csv = bo.toString("UTF-8");
        check(csv.split("\n").length == 3 && csv.contains("รอบเครื่อง [rpm]"), "CSV header + 2 rows");

        System.out.println(fails == 0 ? "\nALL PASSED" : "\nFAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
