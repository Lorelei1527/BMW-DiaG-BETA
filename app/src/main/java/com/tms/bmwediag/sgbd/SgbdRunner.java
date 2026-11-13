package com.tms.bmwediag.sgbd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** ใช้ไฟล์ SGBD จริงของผู้ใช้: หา variant จากไฟล์กลุ่ม D_00XX.grp แล้วรัน job ต่างๆ */
public class SgbdRunner {
    public interface FileSource {
        /** อ่านไฟล์ตามชื่อ (ไม่สนตัวพิมพ์) คืน null ถ้าไม่มี */
        byte[] read(String name) throws IOException;
    }

    public interface Log { void log(String s); }

    private final FileSource files;
    private final EdInterface iface;
    private final Log log;
    private Sgbd variant;
    public String variantName = "";

    public SgbdRunner(FileSource files, EdInterface iface, Log log) {
        this.files = files;
        this.iface = iface;
        this.log = log;
    }

    private void log(String s) { if (log != null) log.log(s); }

    private Sgbd load(String name) throws IOException, SgbdException {
        byte[] d = files.read(name);
        if (d == null) return null;
        Sgbd s = new Sgbd(name, d);
        s.setInterface(iface);
        return s;
    }

    /** หา SGBD ของโมดูลที่ address นี้ (ใช้ D_00XX.grp ถ้ามี ไม่งั้นใช้ fallback) */
    public String identify(int address, String fallback) throws IOException, SgbdException {
        String grp = String.format("D_%04X.grp", address);
        Sgbd g = load(grp);
        String v = null;
        if (g != null) {
            try {
                List<Map<String, Sgbd.Result>> r = g.executeJob("IDENTIFIKATION", "");
                if (r.size() > 1 && r.get(1).get("VARIANTE") != null) v = r.get(1).get("VARIANTE").asText();
                log("กลุ่ม " + grp + " → variant " + v);
            } catch (SgbdException e) {
                log("ระบุ variant จาก " + grp + " ไม่ได้: " + e.getMessage());
            }
        } else log("ไม่พบไฟล์กลุ่ม " + grp);
        if (v == null || v.isEmpty()) v = fallback;
        if (v == null || v.isEmpty()) throw new SgbdException("ไม่รู้ว่าจะใช้ SGBD ไฟล์ไหน");
        Sgbd s = load(v + ".prg");
        if (s == null && fallback != null && !fallback.isEmpty() && !v.equalsIgnoreCase(fallback)) {
            log("ไม่มีไฟล์ " + v + ".prg — ใช้ " + fallback + " แทน");
            v = fallback;
            s = load(v + ".prg");
        }
        if (s == null) throw new SgbdException("ไม่พบไฟล์ " + v + ".prg ในโฟลเดอร์ SGBD");
        variant = s;
        variantName = v.toUpperCase(Locale.ROOT);
        return variantName;
    }

    public void useFile(String prgName) throws IOException, SgbdException {
        Sgbd s = load(prgName);
        if (s == null) throw new SgbdException("ไม่พบไฟล์ " + prgName);
        variant = s;
        variantName = prgName.replaceAll("(?i)\\.prg$", "").toUpperCase(Locale.ROOT);
    }

    public boolean ready() { return variant != null; }

    public List<String> jobs() { return variant == null ? new ArrayList<>() : variant.jobNames(); }

    public boolean hasJob(String j) { return variant != null && variant.hasJob(j); }

    public List<Map<String, Sgbd.Result>> run(String job, String args) throws SgbdException {
        if (variant == null) throw new SgbdException("ยังไม่ได้โหลด SGBD");
        try {
            return variant.executeJob(job, args);
        } catch (SgbdException e) {
            variant.forceInit();
            throw e;
        }
    }

    // ---------- ตัวช่วยสำหรับหน้าจอ ----------
    public static boolean isMeta(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        return n.equals("TELEGRAMM") || n.equals("JOB_STATUS") || n.startsWith("_");
    }

    /** ผล IDENT เป็นบรรทัดอ่านง่าย */
    public List<String> identLines() throws SgbdException {
        List<String> out = new ArrayList<>();
        out.add("SGBD: " + variantName);
        List<Map<String, Sgbd.Result>> r = run("IDENT", "");
        for (int i = 1; i < r.size(); i++)
            for (Sgbd.Result x : r.get(i).values())
                if (!isMeta(x.name)) out.add(label(x.name) + ": " + x.asText());
        return out;
    }

    private static String label(String n) {
        switch (n.toUpperCase(Locale.ROOT)) {
            case "ID_BMW_NR": return "เลขชิ้นส่วน BMW";
            case "ID_HW_NR": return "Hardware";
            case "ID_COD_INDEX": return "Coding index";
            case "ID_DIAG_INDEX": return "Diag index";
            case "ID_BUS_INDEX": return "Bus index";
            case "ID_DATUM_KW": return "ผลิตสัปดาห์ที่";
            case "ID_DATUM_JAHR": return "ปีผลิต (20xx)";
            case "ID_LIEF_NR": return "รหัสผู้ผลิต";
            case "ID_LIEF_TEXT": return "ผู้ผลิต";
            case "ID_SW_NR": return "Software";
            default: return n;
        }
    }

    public static final class Fault {
        public final String code, text, detail;
        Fault(String c, String t, String d) { code = c; text = t; detail = d; }
    }

    /** FS_LESEN → รายการโค้ด (ข้อความจาก SGBD = ภาษาไทยของผู้ใช้) */
    public List<Fault> faults() throws SgbdException {
        List<Fault> out = new ArrayList<>();
        List<Map<String, Sgbd.Result>> r = run("FS_LESEN", "");
        for (int i = 1; i < r.size(); i++) {
            Map<String, Sgbd.Result> s = r.get(i);
            Sgbd.Result nr = s.get("F_ORT_NR");
            if (nr == null) continue;
            Sgbd.Result txt = s.get("F_ORT_TEXT");
            Sgbd.Result pc = s.get("F_PCODE_STRING");
            if (pc == null) pc = s.get("F_PCODE_TEXT");
            StringBuilder d = new StringBuilder();
            for (int a = 1; a <= 8; a++) {
                Sgbd.Result at = s.get("F_ART" + a + "_TEXT");
                if (at != null && !at.asText().trim().isEmpty() && !at.asText().equals("--")) {
                    if (d.length() > 0) d.append(", ");
                    d.append(at.asText());
                }
            }
            Sgbd.Result hfk = s.get("F_HFK");
            if (hfk != null) d.append(d.length() > 0 ? "  •  " : "").append("เกิดขึ้น ").append(hfk.asText()).append(" ครั้ง");
            for (int u = 1; u <= 8; u++) {
                Sgbd.Result ut = s.get("F_UW" + u + "_TEXT");
                Sgbd.Result uw = s.get("F_UW" + u + "_WERT");
                Sgbd.Result ue = s.get("F_UW" + u + "_EINH");
                if (ut != null && uw != null)
                    d.append("\n      ").append(ut.asText()).append(": ").append(uw.asText()).append(ue != null ? " " + ue.asText() : "");
            }
            String code = nr.asLong() > 0xFF ? String.format("%04X", nr.asLong()) : String.format("%02X", nr.asLong());
            String t = txt != null ? txt.asText() : "";
            if (pc != null && !pc.asText().trim().isEmpty() && !pc.asText().startsWith("--")) t = pc.asText() + "  " + t;
            out.add(new Fault(code, t, d.toString()));
        }
        return out;
    }

    public String clearFaults() throws SgbdException {
        List<Map<String, Sgbd.Result>> r = run("FS_LOESCHEN", "");
        String st = jobStatus(r);
        return st.isEmpty() || st.equals("OKAY") ? "✓ ลบโค้ด fault สำเร็จ" : "ผลการลบ: " + st;
    }

    public static String jobStatus(List<Map<String, Sgbd.Result>> r) {
        for (int i = r.size() - 1; i >= 1; i--) {
            Sgbd.Result s = r.get(i).get("JOB_STATUS");
            if (s != null) return s.asText();
        }
        return "";
    }

    /** job สถานะที่ใช้เป็น live data */
    public List<String> statusJobs() {
        List<String> l = new ArrayList<>();
        for (String j : jobs()) {
            String u = j.toUpperCase(Locale.ROOT);
            if (u.contains("SYSTEMCHECK") || u.contains("VERSTELLZEIT") || u.contains("DICHTHEIT") || u.contains("FLASH")) continue;
            if (u.startsWith("STATUS") || u.endsWith("_LESEN") && (u.contains("STATUS") || u.contains("ANALOG") || u.contains("MESSWERT")))
                l.add(j);
        }
        return l;
    }
}
