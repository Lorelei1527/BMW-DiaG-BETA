package com.tms.bmwediag.proto;

import com.tms.bmwediag.io.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ELM327 (Bluetooth SPP) — รองรับ OBD2 ทุกโปรโตคอล และ KWP2000 ไปยังกล่อง BMW โดยตั้ง header
 * หมายเหตุ: ELM327 ทำ DS2 (9600 8E1) ไม่ได้
 */
public class Elm327 implements EcuChannel {
    private final InputStream in;
    private final OutputStream out;
    public KLine.Logger logger;
    public boolean trace = true;
    public String version = "";
    public String protocol = "";
    private boolean can = false;

    public Elm327(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    private void log(String s) { if (logger != null && trace) logger.log(s); }

    /** ส่งคำสั่งแล้วอ่านจนเจอ prompt '>' */
    public synchronized String command(String cmd, int timeoutMs) throws IOException {
        while (in.available() > 0) in.read();
        log("TX " + cmd);
        out.write((cmd + "\r").getBytes("US-ASCII"));
        out.flush();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int c = in.read();
                if (c < 0) throw new IOException("Bluetooth ตัดการเชื่อมต่อ");
                if (c == '>') break;
                bo.write(c);
            } else {
                KLine.sleep(5);
            }
        }
        String r = bo.toString("US-ASCII").replace("\0", "").trim();
        log("RX " + r.replace("\r", " | "));
        return r;
    }

    public void initObd() throws IOException {
        command("ATZ", 3000);
        KLine.sleep(300);
        version = command("ATI", 1000);
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATST4B"}) command(c, 1000);
        command("ATSP0", 1000);
        String r = command("0100", 10000);
        if (r.contains("UNABLE") || r.contains("ERROR") || r.contains("NO DATA"))
            throw new IOException("ELM327 เชื่อม OBD ไม่ได้: " + r + " (บิดกุญแจ ON แล้วหรือยัง?)");
        protocol = command("ATDPN", 1000).replace("A", "");
        can = protocol.length() > 0 && "6789ABC".indexOf(protocol.charAt(protocol.length() - 1)) >= 0;
    }

    /** KWP2000 ไปยังกล่องโดยตรง เช่น DME addr 0x12 (MS45 / ME9.2 บน E46/E83/E85) */
    public void initKwp(int target) throws IOException {
        command("ATZ", 3000);
        KLine.sleep(300);
        version = command("ATI", 1000);
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATST4B", "ATSP5"}) command(c, 1000);
        command(String.format("ATSH80%02XF1", target), 1000);
        command("ATWM80" + String.format("%02X", target) + "F13E", 1000); // keep-alive = TesterPresent
        String r = command("ATFI", 5000);
        if (r.contains("ERROR")) throw new IOException("ELM327 fast init ล้มเหลว: " + r);
        protocol = "5";
        can = false;
    }

    public boolean isCan() { return can; }

    @Override
    public byte[] request(byte[] payload) throws IOException {
        List<byte[]> l = requestMulti(payload);
        if (l.size() == 1) return l.get(0);
        // รวมหลายบรรทัดเป็นก้อนเดียว (ตัด service byte ซ้ำของบรรทัดถัดไปสำหรับ K-line)
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int i = 0; i < l.size(); i++) {
            byte[] b = l.get(i);
            int off = (i > 0 && !can && b.length > 0 && b[0] == l.get(0)[0]) ? 1 : 0;
            bo.write(b, off, b.length - off);
        }
        return bo.toByteArray();
    }

    /** เติมจำนวนคำตอบ "1" ท้ายคำสั่ง — ELM คืนผลทันทีไม่ต้องรอ timeout (เร็วขึ้นหลายเท่า) */
    @Override
    public byte[] requestExpect(byte[] payload, int responseLen) throws IOException {
        String r = command(Hex.of(payload).replace(" ", "") + "1", 3000);
        return parse(r, payload[0] & 0xFF).get(0);
    }

    @Override
    public List<byte[]> requestMulti(byte[] payload) throws IOException {
        String r = command(Hex.of(payload).replace(" ", ""), 6000);
        return parse(r, payload[0] & 0xFF);
    }

    /** แปลงข้อความตอบกลับของ ELM เป็นรายการ payload */
    public static List<byte[]> parse(String r, int sid) throws IOException {
        List<byte[]> out = new ArrayList<>();
        if (r.contains("NO DATA")) throw new IOException("NO DATA");
        if (r.contains("ERROR") || r.contains("UNABLE") || r.contains("?"))
            throw new IOException("ELM: " + r.replace("\r", " "));
        ByteArrayOutputStream multi = null;
        for (String line : r.split("[\r\n]+")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("SEARCHING") || line.startsWith("BUS INIT")) continue;
            if (line.endsWith("OK") && line.length() < 4) continue;
            if (line.matches("^[0-9A-Fa-f]{3}$")) { multi = new ByteArrayOutputStream(); continue; } // ความยาว ISO-TP
            int colon = line.indexOf(':');
            if (colon >= 0 && colon <= 2) {
                byte[] b = Hex.parse(line.substring(colon + 1));
                if (multi == null) multi = new ByteArrayOutputStream();
                multi.write(b, 0, b.length);
                continue;
            }
            byte[] b;
            try { b = Hex.parse(line); } catch (IllegalArgumentException e) { continue; }
            if (b.length == 0) continue;
            if ((b[0] & 0xFF) == 0x7F && b.length >= 3) {
                if ((b[2] & 0xFF) == 0x78) continue;
                if (out.isEmpty()) throw new NegativeResponseException(sid, b[2] & 0xFF);
                continue;
            }
            out.add(b);
        }
        if (multi != null && multi.size() > 0) out.add(0, multi.toByteArray());
        if (out.isEmpty()) throw new IOException("ELM: ไม่มีข้อมูล (" + r.replace("\r", " ") + ")");
        return out;
    }

    @Override
    public String describe() { return "ELM327 " + version + " protocol " + protocol; }

    @Override
    public void close() {
        try { command("ATPC", 500); } catch (Exception ignored) {}
    }
}
