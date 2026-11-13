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

    /** เริ่ม/จบรอบอ่าน live data: คำสั่งที่ซ้ำกันในรอบเดียวส่งไปกล่องครั้งเดียว */
    public void beginCycle() { if (iface != null) iface.beginCycle(); }

    public void endCycle() { if (iface != null) iface.endCycle(); }

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

    /** ผล IDENT เป็นบรรทัดอ่านง่าย (มี VIN ด้านบน) */
    public List<String> identLines() throws SgbdException {
        List<String> out = new ArrayList<>();
        out.add("SGBD: " + variantName);
        String vin = vin();
        out.add("VIN (เลขตัวถัง): " + (vin == null || vin.isEmpty() ? "อ่านไม่ได้" : vin));
        List<Map<String, Sgbd.Result>> r = run("IDENT", "");
        for (int i = 1; i < r.size(); i++)
            for (Sgbd.Result x : r.get(i).values())
                if (!isMeta(x.name)) out.add(label(x.name) + ": " + x.asText());
        return out;
    }

    /** อ่าน VIN ที่เก็บในกล่อง (AIF_LESEN → C_FG_LESEN → IDENT_AIF) */
    public String vin() {
        String[][] tries = {{"AIF_LESEN", "AIF_FG_NR_LANG"}, {"AIF_LESEN", "AIF_FG_NR"}, {"C_FG_LESEN", "FG_NR"},
                {"IDENT_AIF", "AIF_FAHRGESTELL_NR"}, {"FG_LESEN", "FG_NR"}};
        String lastJob = null;
        List<Map<String, Sgbd.Result>> r = null;
        for (String[] t : tries) {
            if (!hasJob(t[0])) continue;
            try {
                if (!t[0].equals(lastJob)) { r = run(t[0], ""); lastJob = t[0]; }
                for (int i = 1; i < r.size(); i++) {
                    Sgbd.Result x = r.get(i).get(t[1]);
                    if (x == null) continue;
                    String v = x.asText().trim();
                    if (v.length() >= 7 && !v.matches("[-\\s0F]*") && !v.matches("[\\x00-\\x1F\\xFF]*")) return v;
                }
            } catch (SgbdException e) {
                log("อ่าน VIN (" + t[0] + ") ไม่ได้: " + e.getMessage());
                lastJob = null;
            }
        }
        return null;
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
            case "ID_SG_ADR": return "Address กล่อง";
            case "ID_LIEF_NR_TEXT": return "ผู้ผลิต";
            case "ID_VAR_INDEX": return "Variant index";
            case "ID_ZIF": return "ZIF";
            case "ID_ZIF_BACKUP": return "ZIF backup";
            case "ID_HW_REF": return "Hardware ref";
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

    /** ค่า live data หนึ่งรายการ (job + ชื่อผล) */
    public static final class LiveItem {
        public final String job, result, label, comment;
        LiveItem(String j, String r, String l, String c) { job = j; result = r; label = l; comment = c; }
        public String key() { return job + "|" + result; }
    }

    private List<LiveItem> catalog;

    /**
     * ค่า live data ที่ใช้งานจริงเท่านั้น — 1 ช่อง = 1 ค่า (เลือกชื่อแรกที่กล่องมี) ยกเว้นน็อคที่แสดงทุกสูบ
     * ชื่อหลังตัด STAT_/STATUS_ และ _WERT/_MWB
     */
    static final String[][] USEFUL = {
            {"รอบเครื่อง", "NMOT", "MOTORDREHZAHL"},
            {"โหลดเครื่อง", "LAST", "RL"},
            {"MAF", "MSHFM", "LMM_MASSE", "LMM", "ML"},
            {"ลิ้นปีก", "WDKBA", "DKP_WINKEL", "DROSSELKLAPPE"},
            {"องศาไฟ", "ZWOUT", "ZUENDWINKEL", "ZWIST"},
            {"*น็อค", "RKRN0", "RKRN1", "RKRN2", "RKRN3", "RKRN4", "RKRN5", "RKRN6", "RKRN7",
                    "K_MW_1", "K_MW_2", "K_MW_3", "K_MW_4", "K_MW_5", "K_MW_6"},
            {"การฉีด", "TE", "EINSPRITZZEIT"},
            {"AFR B1", "LAMSONI"},
            {"AFR B2", "LAMSONI2"},
            {"O2 หน้า B1", "L_SONDE", "LS_VKAT_SIGNAL_1", "USVK", "UULSUV"},
            {"O2 หน้า B2", "L_SONDE_2", "LS_VKAT_SIGNAL_2", "USVK2", "UULSUV2"},
            {"O2 หลัง B1", "L_SONDE_H", "LS_NKAT_SIGNAL_1", "USHK"},
            {"O2 หลัง B2", "L_SONDE_2_H", "LS_NKAT_SIGNAL_2", "USHK2"},
            {"แคมไอดี", "WNWKWE", "VANOS_NW_LAGE_EINLASS", "VANOS_NW_LAGE_EINLASS_IST"},
            {"แคมไอเสีย", "WNWKWA", "VANOS_NW_LAGE_AUSLASS", "VANOS_NW_LAGE_AUSLASS_IST"},
            {"Valvetronic", "EVHUBI"},
            {"แบต", "UB", "UBATT", "UBAT"},
            {"น้ำมันเครื่อง", "OEL_TEMPERATUR", "TOEL"},
            {"น้ำหล่อเย็น", "TMOT", "MOTORTEMPERATUR"},
            {"หม้อน้ำ", "TKA", "KUEHLW_AUSL_TEMPERATUR"},
            // เกียร์
            {"น้ำมันเกียร์", "GETRIEBETEMPERATUR", "GETRIEBEOELTEMPERATUR", "OELTEMPERATUR"},
            {"รอบเทอร์ไบน์", "TURBINENDREHZAHL", "STEGDREHZAHL"},
            {"รอบเพลาขาออก", "ABTRIEBSDREHZAHL"},
            {"เกียร์", "GANG"},
    };

    /** เครื่อง 4 สูบ (N42/N46/M43) มีท่อไอเสียชุดเดียว → ไม่มี O2 B2 / กล่องเกียร์ → แสดงแค่รอบเครื่อง + ค่าเกียร์ */
    private boolean slotOk(int slot) {
        String name = USEFUL[slot][0];
        if (variantName.toUpperCase(Locale.ROOT).startsWith("GS"))
            return name.equals("รอบเครื่อง") || slot >= gearSlotStart();
        if (slot >= gearSlotStart()) return false;
        if (cylinders() == 4 && name.endsWith("B2")) return false;
        return true;
    }

    private static int gearSlotStart() {
        for (int g = 0; g < USEFUL.length; g++) if (USEFUL[g][0].equals("น้ำมันเกียร์")) return g;
        return USEFUL.length;
    }

    /** {ช่อง, ลำดับในช่อง} หรือ null ถ้าไม่ใช่ค่าที่ใช้งานจริง */
    private static int[] slotOf(String base) {
        for (int g = 0; g < USEFUL.length; g++)
            for (int k = 1; k < USEFUL[g].length; k++) if (USEFUL[g][k].equals(base)) return new int[]{g, k};
        return null;
    }

    /** รายการค่า live data ที่เลือกได้ (เฉพาะค่าที่ใช้งานจริง ชื่อภาษาไทย) — ค่าที่มีใน job บล็อกใหญ่จะใช้ job นั้นก่อน */
    public List<LiveItem> liveCatalog() {
        if (catalog != null) return catalog;
        catalog = new ArrayList<>();
        if (variant == null) return catalog;
        Map<String, List<String[]>> desc = variant.jobResults();
        List<String> js = new ArrayList<>();
        for (String j : statusJobs()) if (!variant.jobHasArgs(j) && desc.containsKey(j.toUpperCase(Locale.ROOT))) js.add(j);
        // job ที่คืนค่าหลายตัวก่อน (อ่านทีเดียวได้หลายค่า)
        js.sort((a, b) -> desc.get(b.toUpperCase(Locale.ROOT)).size() - desc.get(a.toUpperCase(Locale.ROOT)).size());
        // ช่องปกติ: เก็บตัวที่ชื่ออยู่ลำดับแรกสุดในช่อง / ช่อง * (น็อค): เก็บทุกตัว
        java.util.Map<String, LiveItem> pick = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> pickRank = new java.util.HashMap<>();
        java.util.Map<LiveItem, Integer> order = new java.util.HashMap<>();
        for (String j : js) {
            for (String[] r : desc.get(j.toUpperCase(Locale.ROOT))) {
                String n = r[0].toUpperCase(Locale.ROOT);
                if (isMeta(n) || n.endsWith("_EINH") || n.endsWith("_TEXT") || n.startsWith("TEL") || n.equals("RESP_CODE")) continue;
                String t = r[1].toLowerCase(Locale.ROOT);
                if (t.contains("binary") || t.contains("data")) continue;
                String b = ThaiLabels.base(n);
                int[] sl = slotOf(b);
                if (sl == null || !cylinderOk(b) || !slotOk(sl[0])) continue;
                boolean multi = USEFUL[sl[0]][0].startsWith("*");
                String key = multi ? "k" + b : "s" + sl[0];
                Integer old = pickRank.get(key);
                if (old != null && old <= sl[1]) continue;
                LiveItem it = new LiveItem(j, r[0], liveLabel(r[0], r[2]), r[2]);
                pick.put(key, it);
                pickRank.put(key, sl[1]);
                order.put(it, sl[0] * 100 + (multi ? sl[1] : 0));
            }
        }
        catalog.addAll(pick.values());
        catalog.sort((x, y) -> order.get(x) - order.get(y));
        return catalog;
    }

    /** จำนวนสูบตามรุ่นกล่อง: ME9.2 / MEV9.2 (N42/N46) และ BMS43/46 (M43) = 4 สูบ, MS42/43/45 (M52/M54) = 6 สูบ */
    public int cylinders() {
        String v = variantName.toUpperCase(Locale.ROOT);
        if (v.startsWith("ME9") || v.startsWith("MEV9") || v.startsWith("BMS4")) return 4;
        return 6;
    }

    /** ตัดค่าน็อคของสูบที่ไม่มีจริงออก (เช่น RKRN4-7 บนเครื่อง 4 สูบ) */
    private boolean cylinderOk(String base) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^RKRN(\\d)$").matcher(base);
        if (m.find()) return Integer.parseInt(m.group(1)) < cylinders();
        m = java.util.regex.Pattern.compile("^K_MW_(\\d)$").matcher(base);
        if (m.find()) return Integer.parseInt(m.group(1)) <= cylinders();
        return true;
    }

    private static String baseOf(String n) { return n.replaceAll("(?i)_(WERT|MWB)$", ""); }

    /** ชื่อภาษาไทยของค่า live data */
    public static String liveLabel(String name, String comment) {
        String l = ThaiLabels.label(name);
        if (!l.equals(name)) return l;
        // ลองใช้รหัสใน comment เช่น "Wert von MSHFM_W"
        if (comment != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^(?:Wert|Ergebnis|Einheit)\\s+(?:von|der|des)?\\s*([A-Z0-9_]+?)(?:_W)?\\s*$").matcher(comment.trim());
            if (m.find()) {
                String c = ThaiLabels.label(m.group(1));
                if (!c.equals(m.group(1))) return c;
            }
        }
        return ThaiLabels.base(name);
    }

    /** ค่าแนะนำเริ่มต้น (~8 ค่าสำคัญ) */
    /** ค่าเริ่มต้น = ค่าที่ใช้งานจริงทั้งหมดของกล่องนี้ (มีไม่เกิน ~20 ค่า) */
    public List<String> defaultLiveKeys() {
        List<String> out = new ArrayList<>();
        for (LiveItem it : liveCatalog()) out.add(it.key());
        return out;
    }

    public LiveItem liveItem(String key) {
        for (LiveItem it : liveCatalog()) if (it.key().equalsIgnoreCase(key)) return it;
        return null;
    }
}
