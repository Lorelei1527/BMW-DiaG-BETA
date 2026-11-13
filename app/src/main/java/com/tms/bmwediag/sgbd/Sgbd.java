package com.tms.bmwediag.sgbd;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ตัวรันไฟล์ SGBD (.prg / .grp) ของ BMW EDIABAS บน Java
 *
 * พอร์ตจาก EdiabasLib (EdiabasNet.cs / EdOperations.cs) โดย Ulrich Holeschak
 * https://github.com/uholeschak/ediabaslib — สัญญาอนุญาต GPLv3
 * ไฟล์นี้จึงอยู่ภายใต้ GPLv3 เช่นกัน
 */
public class Sgbd {
    // ===================== ผลลัพธ์ =====================
    public static final int TYPE_B = 0, TYPE_W = 1, TYPE_D = 2, TYPE_C = 4, TYPE_I = 5, TYPE_L = 6,
            TYPE_R = 8, TYPE_S = 9, TYPE_Y = 10;

    public static final class Result {
        public final int type;
        public final String name;
        /** Long / Double / String (latin-1 bytes) / byte[] */
        public final Object value;

        Result(int type, String name, Object value) {
            this.type = type;
            this.name = name;
            this.value = value;
        }

        public long asLong() { return value instanceof Long ? (Long) value : value instanceof Double ? (long) (double) (Double) value : 0; }

        /** ข้อความ — ถอดรหัส UTF-8 (SGBD ภาษาไทยของผู้ใช้) หรือ TIS-620/Windows-874 */
        public String asText() {
            if (value instanceof String) return decodeText((String) value);
            if (value instanceof byte[]) {
                StringBuilder sb = new StringBuilder();
                for (byte b : (byte[]) value) sb.append(String.format("%02X ", b & 0xFF));
                return sb.toString().trim();
            }
            if (value instanceof Double) return new BigDecimal((Double) value).round(new MathContext(6)).stripTrailingZeros().toPlainString();
            return String.valueOf(value);
        }

        @Override public String toString() { return name + "=" + asText(); }
    }

    public static String decodeText(String latin1) {
        byte[] b = latin1.getBytes(StandardCharsets.ISO_8859_1);
        boolean high = false;
        for (byte x : b) if ((x & 0x80) != 0) { high = true; break; }
        if (!high) return latin1;
        try {
            java.nio.charset.CharsetDecoder d = StandardCharsets.UTF_8.newDecoder();
            return d.decode(ByteBuffer.wrap(b)).toString();
        } catch (Exception e) {
            try { return new String(b, "x-windows-874"); } catch (Exception e2) {
                try { return new String(b, "TIS-620"); } catch (Exception e3) { return latin1; }
            }
        }
    }

    // ===================== ไฟล์ =====================
    private static final class TableInfo {
        String name;
        int columnOffset, columns, rows;
        Map<String, Integer> columnNames;
        int[][] entries;
        Map<String, Integer>[] seekStr;
        Map<Long, Integer>[] seekVal;
    }

    private static final class JobInfo {
        final String name;
        final int offset, arraySize;
        JobInfo(String n, int o, int a) { name = n; offset = o; arraySize = a; }
    }

    private final byte[] file;
    public final String fileName;
    private final Map<String, JobInfo> jobs = new LinkedHashMap<>();
    private TableInfo[] tables = new TableInfo[0];
    private final Map<String, Integer> tableNames = new HashMap<>();
    private long bipVersion;

    // ===================== สถานะเครื่อง =====================
    private static final long EDIABAS_VERSION = 0x773;
    private final byte[] byteRegs = new byte[32];
    private final double[] floatRegs = new double[16];
    private final StringData[] strRegs = new StringData[16];
    private final Register[] registers;
    private final Flags flags = new Flags();
    private final ArrayList<Byte> stack = new ArrayList<>(); // ท้าย list = top
    private long pc;
    private boolean jobEnd;
    private long arrayMaxBufSize = 1024;
    private long errorTrapMask;
    private int errorTrapBitNr = -1;
    public int errorCodeLast;
    private final Map<String, Result> resultDict = new LinkedHashMap<>();
    private final Map<String, Result> sysDict = new LinkedHashMap<>();
    private List<Map<String, Result>> resultSetsTemp;
    private int tableIndex = -1, tableRowIndex = -1;
    private String tokenSeparator = "";
    private long tokenIndex;
    private long floatPrecision = 4;
    private boolean requestInit = true;
    private boolean jobStd;
    private String resultJobStatus = "";
    private byte[] argBin;
    private List<String> argStrings = new ArrayList<>();
    private final Map<String, String> config = new HashMap<>();
    private static final Map<String, byte[]> SHARED = new HashMap<>();
    private EdInterface iface;
    public volatile boolean abort;
    public int maxSteps = 5_000_000;

    private static final Map<Integer, Integer> TRAP_BITS = new HashMap<>();
    static {
        TRAP_BITS.put(SgbdException.Err.bip(2), 2);
        TRAP_BITS.put(SgbdException.Err.bip(6), 6);
        TRAP_BITS.put(SgbdException.Err.bip(9), 9);
        TRAP_BITS.put(SgbdException.Err.bip(10), 10);
        for (int i = 1; i <= 16; i++) TRAP_BITS.put(SgbdException.Err.ifh(i), 10 + i);
        TRAP_BITS.put(SgbdException.Err.bip(11), 8);
        TRAP_BITS.put(SgbdException.Err.ifh(69), 28);
        TRAP_BITS.put(SgbdException.Err.ifh(74), 29);
    }

    public Sgbd(String fileName, byte[] data) throws SgbdException {
        this.fileName = fileName;
        this.file = data;
        registers = new Register[256];
        int[][] regDef = {
                // opcode, type(0=AB,1=I,2=L,3=F,4=S), index
        };
        for (int i = 0; i <= 0x0F; i++) registers[i] = new Register(i, 0, i);
        for (int i = 0x10; i <= 0x17; i++) registers[i] = new Register(i, 1, i - 0x10);
        for (int i = 0x18; i <= 0x1B; i++) registers[i] = new Register(i, 2, i - 0x18);
        for (int i = 0x1C; i <= 0x23; i++) registers[i] = new Register(i, 4, i - 0x1C);
        for (int i = 0x24; i <= 0x2B; i++) registers[i] = new Register(i, 3, i - 0x24);
        for (int i = 0x2C; i <= 0x33; i++) registers[i] = new Register(i, 4, i - 0x2C + 8);
        for (int i = 0x80; i <= 0x8F; i++) registers[i] = new Register(i, 0, i - 0x80 + 16);
        for (int i = 0x90; i <= 0x97; i++) registers[i] = new Register(i, 1, i - 0x90 + 8);
        for (int i = 0x98; i <= 0x9B; i++) registers[i] = new Register(i, 2, i - 0x98 + 4);
        for (int i = 0; i < strRegs.length; i++) strRegs[i] = new StringData(arrayMaxBufSize);
        config.put("EdiabasVersion", "7.7.3");
        config.put("CompatMode", "1");
        config.put("SystemResults", "1");
        config.put("RetryComm", "1");
        config.put("Simulation", "0");
        load();
    }

    public void setInterface(EdInterface i) {
        iface = i;
        if (i != null) i.setEdiabas(this);
    }

    public EdInterface getInterface() { return iface; }

    public void setConfig(String k, String v) { config.put(k, v); }

    // ===================== อ่านไฟล์ =====================
    private int rawInt(int off) {
        return (file[off] & 0xFF) | ((file[off + 1] & 0xFF) << 8) | ((file[off + 2] & 0xFF) << 16) | ((file[off + 3] & 0xFF) << 24);
    }

    private int decInt(int off) {
        return (dec(off) & 0xFF) | ((dec(off + 1) & 0xFF) << 8) | ((dec(off + 2) & 0xFF) << 16) | ((dec(off + 3) & 0xFF) << 24);
    }

    private byte dec(int off) {
        if (off < 0 || off >= file.length) throw new IndexOutOfBoundsException("SGBD read beyond end");
        return (byte) (file[off] ^ 0xF7);
    }

    private String decString(int off, int len) {
        byte[] b = new byte[len];
        int n = 0;
        for (int i = 0; i < len; i++) {
            b[i] = dec(off + i);
            if (b[i] == 0) break;
            n++;
        }
        return new String(b, 0, n, StandardCharsets.ISO_8859_1);
    }

    private void load() throws SgbdException {
        if (file.length < 0xA0) throw new SgbdException("ไฟล์ SGBD สั้นเกินไป");
        int usesOff = rawInt(0x7C);
        if (usesOff >= 0 && rawInt(usesOff) > 0)
            throw new SgbdException("SGBD นี้อ้างถึงไฟล์อื่น (uses) ยังไม่รองรับ");
        int verOff = rawInt(0x94);
        if (verOff >= 0) bipVersion = ((dec(verOff + 2) & 0xFF) << 16) | ((dec(verOff + 1) & 0xFF) << 8) | (dec(verOff) & 0xFF);
        int arraySize = rawInt(0x18);
        if (arraySize == 0) arraySize = 1024;
        int jobOff = rawInt(0x88);
        if (jobOff >= 0) {
            int n = rawInt(jobOff);
            int p = jobOff + 4;
            for (int i = 0; i < n; i++, p += 0x44) {
                String name = decString(p, 0x40);
                int addr = decInt(p + 0x40);
                String key = name.toUpperCase(Locale.ROOT);
                if (!jobs.containsKey(key)) jobs.put(key, new JobInfo(name, addr, arraySize));
            }
        }
        int tabOff = rawInt(0x84);
        if (tabOff >= 0) {
            int n = decInt(tabOff);
            tables = new TableInfo[n];
            int p = tabOff + 4;
            for (int i = 0; i < n; i++, p += 0x50) {
                TableInfo t = new TableInfo();
                t.name = decString(p, 0x40);
                t.columnOffset = decInt(p + 0x40);
                t.columns = decInt(p + 0x48);
                t.rows = decInt(p + 0x4C);
                tables[i] = t;
                tableNames.put(t.name.toUpperCase(Locale.ROOT), i);
            }
        }
    }

    public List<String> jobNames() {
        List<String> l = new ArrayList<>();
        for (JobInfo j : jobs.values()) l.add(j.name);
        return l;
    }

    public boolean hasJob(String name) { return jobs.containsKey(name.toUpperCase(Locale.ROOT)); }

    /** อ่านตารางทั้งตาราง (แถวแรก = ชื่อคอลัมน์) */
    public List<String[]> readTable(String name) {
        Integer idx = tableNames.get(name.toUpperCase(Locale.ROOT));
        List<String[]> out = new ArrayList<>();
        if (idx == null) return out;
        TableInfo t = tables[idx];
        indexTable(t);
        for (int[] row : t.entries) {
            String[] r = new String[t.columns];
            for (int c = 0; c < t.columns; c++) r[c] = decodeText(tableString(row[c]));
            out.add(r);
        }
        return out;
    }

    private void indexTable(TableInfo t) {
        if (t.entries != null) return;
        int p = t.columnOffset;
        Map<String, Integer> names = new HashMap<>();
        int[][] e = new int[t.rows + 1][t.columns];
        for (int j = 0; j < t.rows + 1; j++) {
            for (int k = 0; k < t.columns; k++) {
                e[j][k] = p;
                int start = p;
                int l = 0;
                while (l < 1024 && dec(p) != 0) { p++; l++; }
                if (l < 1024) p++; // ข้าม \0
                if (j == 0) names.put(new String(decBytes(start, l), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT), k);
            }
        }
        t.columnNames = names;
        t.entries = e;
    }

    private byte[] decBytes(int off, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = dec(off + i);
        return b;
    }

    private String tableString(int off) {
        int l = 0;
        while (l < 1024 && dec(off + l) != 0) l++;
        return new String(decBytes(off, l), StandardCharsets.ISO_8859_1);
    }

    // ===================== ข้อผิดพลาด =====================
    void setError(int error) throws SgbdException {
        if (error != 0) {
            Integer bit = TRAP_BITS.get(error);
            errorTrapBitNr = bit != null ? bit : 0;
            long active = (1L << errorTrapBitNr) & ~errorTrapMask & 0xFFFFFFFFL;
            if (active != 0) raiseError(error);
        } else {
            errorTrapBitNr = -1;
        }
    }

    void raiseError(int error) throws SgbdException {
        errorCodeLast = error;
        throw new SgbdException(error);
    }

    // ===================== ค่าช่วย =====================
    static long u32(long v) { return v & 0xFFFFFFFFL; }

    static long mask(long len) {
        if (len == 1) return 0xFFL;
        if (len == 2) return 0xFFFFL;
        if (len == 4) return 0xFFFFFFFFL;
        throw new IllegalArgumentException("invalid length " + len);
    }

    static byte[] latin1(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    static String str(byte[] b) {
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n, StandardCharsets.ISO_8859_1);
    }

    public static long stringToValue(String number) {
        String s = number.replaceAll("\\s+$", "");
        if (s.isEmpty()) return 0;
        String lower = s.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("0x")) {
                if (lower.length() > 2) {
                    char c = lower.charAt(2);
                    if (Character.isDigit(c) || (c >= 'a' && c <= 'f')) return Long.parseUnsignedLong(s.substring(2), 16);
                }
                return 0;
            } else if (lower.startsWith("0y")) {
                return Long.parseLong(s.substring(2), 2);
            } else {
                if (lower.equals("-") || lower.equals("--")) return 0;
                if (Character.isLetter(lower.charAt(0))) return 0;
                String conv = s.trim();
                int idx = -1;
                for (int i = 0; i < conv.length(); i++) if (conv.charAt(i) == '.' || conv.charAt(i) == ',') { idx = i; break; }
                if (idx >= 0) conv = conv.substring(0, idx);
                if (conv.startsWith("+")) conv = conv.substring(1);
                return Long.parseLong(conv);
            }
        } catch (Exception e) {
            return 0;
        }
    }

    static double stringToFloat(String number, boolean[] valid) {
        if (valid != null) valid[0] = false;
        try {
            for (char c : number.toCharArray()) if (c > 127) return 0;
            double d = Double.parseDouble(number.replace(",", ".").trim());
            if (valid != null) valid[0] = true;
            return d;
        } catch (Exception e) {
            return 0;
        }
    }

    static byte[] hexToBytes(String s) {
        try {
            int len = s.length() - s.length() % 2;
            byte[] r = new byte[len / 2];
            for (int i = 0; i < len; i += 2) r[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
            return r;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ===================== Register / StringData / Flags =====================
    private final class StringData {
        long length;
        byte[] data;
        StringData(long len) { data = new byte[(int) len]; }
        void newArrayLength(long len) { if (len > data.length) data = new byte[(int) len]; }
        byte[] get(boolean complete) {
            if (complete) return data;
            return Arrays.copyOf(data, (int) length);
        }
        void set(byte[] v, boolean keepLength) throws SgbdException {
            if (v.length > data.length) { setError(SgbdException.Err.bip(1)); return; }
            System.arraycopy(v, 0, data, 0, v.length);
            if (!keepLength) length = v.length;
        }
        void clear() { Arrays.fill(data, (byte) 0); length = 0; }
    }

    private final class Register {
        final int opcode, type, index; // type 0=AB 1=I 2=L 3=F 4=S
        Register(int opcode, int type, int index) { this.opcode = opcode; this.type = type; this.index = index; }

        boolean isString() { return type == 4; }
        boolean isFloat() { return type == 3; }

        long dataLen() {
            switch (type) {
                case 0: return 1;
                case 1: return 2;
                case 2: return 4;
                case 3: return 8;
                default: return arrayData(false).length;
            }
        }

        long value() {
            switch (type) {
                case 0: return byteRegs[index] & 0xFF;
                case 1: { int o = index << 1; return (byteRegs[o] & 0xFF) | ((byteRegs[o + 1] & 0xFF) << 8); }
                case 2: {
                    int o = index << 2;
                    return u32((byteRegs[o] & 0xFF) | ((byteRegs[o + 1] & 0xFF) << 8) | ((byteRegs[o + 2] & 0xFF) << 16) | ((long) (byteRegs[o + 3] & 0xFF) << 24));
                }
                default: throw new IllegalStateException("Register.value: invalid type");
            }
        }

        Object raw() {
            switch (type) {
                case 0: case 1: case 2: return value();
                case 3: return floatRegs[index];
                default: return arrayData(false);
            }
        }

        byte[] arrayData(boolean complete) {
            if (type != 4) throw new IllegalStateException("Register.arrayData: invalid type");
            return strRegs[index].get(complete);
        }

        void setRaw(Object v) throws SgbdException {
            if (type == 4) {
                if (!(v instanceof byte[])) throw new IllegalStateException("Register.setRaw: invalid type");
                setArray((byte[]) v, false);
            } else if (type == 3) {
                if (!(v instanceof Double)) throw new IllegalStateException("Register.setRaw float: invalid type");
                floatRegs[index] = (Double) v;
            } else {
                if (!(v instanceof Long)) throw new IllegalStateException("Register.setRaw value: invalid type");
                setValue((Long) v);
            }
        }

        void setValue(long v) {
            switch (type) {
                case 0: byteRegs[index] = (byte) v; break;
                case 1: { int o = index << 1; byteRegs[o] = (byte) v; byteRegs[o + 1] = (byte) (v >> 8); break; }
                case 2: {
                    int o = index << 2;
                    byteRegs[o] = (byte) v; byteRegs[o + 1] = (byte) (v >> 8);
                    byteRegs[o + 2] = (byte) (v >> 16); byteRegs[o + 3] = (byte) (v >> 24);
                    break;
                }
                default: throw new IllegalStateException("Register.setValue: invalid type");
            }
        }

        void setArray(byte[] v, boolean keepLength) throws SgbdException {
            if (type != 4) throw new IllegalStateException("Register.setArray: invalid type");
            strRegs[index].set(v, keepLength);
        }

        void clear() { strRegs[index].clear(); }
    }

    private static final class Flags {
        boolean carry, zero, sign, overflow;
        void init() { carry = zero = sign = overflow = false; }
        void update(long value, long len) {
            long m, s;
            if (len == 1) { m = 0xFFL; s = 0x80L; }
            else if (len == 2) { m = 0xFFFFL; s = 0x8000L; }
            else if (len == 4) { m = 0xFFFFFFFFL; s = 0x80000000L; }
            else throw new IllegalArgumentException("Flags.update length " + len);
            zero = (value & m) == 0;
            sign = (value & s) != 0;
        }
        void setOverflow(long v1, long v2, long res, long len) {
            long s = len == 1 ? 0x80L : len == 2 ? 0x8000L : 0x80000000L;
            if ((v1 & s) != (v2 & s)) overflow = false;
            else overflow = (v1 & s) != (res & s);
        }
        void setCarry(long value, long len) {
            long c = len == 1 ? 0x100L : len == 2 ? 0x10000L : 0x100000000L;
            carry = (value & c) != 0;
        }
        long toValue() { return (carry ? 1 : 0) | (zero ? 2 : 0) | (sign ? 4 : 0) | (overflow ? 8 : 0); }
        void fromValue(long v) { carry = (v & 1) != 0; zero = (v & 2) != 0; sign = (v & 4) != 0; overflow = (v & 8) != 0; }
    }

    // ===================== Operand =====================
    private static final int NONE = 0, REGS = 1, REGAB = 2, REGI = 3, REGL = 4, IMM8 = 5, IMM16 = 6, IMM32 = 7,
            IMMSTR = 8, IDXIMM = 9, IDXREG = 10, IDXREGIMM = 11, IDXIMMLENIMM = 12, IDXIMMLENREG = 13,
            IDXREGLENIMM = 14, IDXREGLENREG = 15;

    private final class Operand {
        int mode;
        Object d1, d2, d3;

        void init(int m, Object a, Object b, Object c) { mode = m; d1 = a; d2 = b; d3 = c; }

        boolean isArrayType() {
            switch (mode) {
                case REGS: case IMMSTR: case IDXIMM: case IDXREG: case IDXREGIMM:
                case IDXIMMLENIMM: case IDXIMMLENREG: case IDXREGLENIMM: case IDXREGLENREG:
                    return true;
            }
            return false;
        }

        boolean isRegister() { return d1 instanceof Register; }

        long dataLen(boolean write) throws SgbdException {
            switch (mode) {
                case REGS: case IMMSTR: return arrayData().length;
                case REGAB: case IMM8: return 1;
                case REGI: case IMM16: return 2;
                case REGL: case IMM32: return 4;
                case IDXIMM: case IDXREG: case IDXREGIMM:
                    if (write) return 1;
                    return arrayData().length;
                case IDXIMMLENIMM: case IDXIMMLENREG: case IDXREGLENIMM: case IDXREGLENREG:
                    return arrayData().length;
            }
            return 0;
        }

        long dataLen() throws SgbdException { return dataLen(false); }

        long valueMask() throws SgbdException { return mask(dataLen()); }

        Object raw() throws SgbdException {
            switch (mode) {
                case REGS: case REGAB: case REGI: case REGL:
                    return ((Register) d1).raw();
                case IMM8: case IMM16: case IMM32: case IMMSTR:
                    return d1;
                case IDXIMM: case IDXREG: case IDXREGIMM: {
                    byte[] arr = ((Register) d1).arrayData(true);
                    long index = mode == IDXIMM ? (Long) d2 : ((Register) d2).value();
                    if (mode == IDXREGIMM) index = u32(index + (Long) d3);
                    long req = index + 1;
                    if (req > arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return new byte[0]; }
                    if (arr.length < req) return new byte[0];
                    return Arrays.copyOfRange(arr, (int) index, arr.length);
                }
                case IDXIMMLENIMM: case IDXIMMLENREG: case IDXREGLENIMM: case IDXREGLENREG: {
                    byte[] arr = ((Register) d1).arrayData(true);
                    long index = (mode == IDXIMMLENIMM || mode == IDXIMMLENREG) ? (Long) d2 : ((Register) d2).value();
                    long len = (mode == IDXIMMLENIMM || mode == IDXREGLENIMM) ? (Long) d3 : ((Register) d3).value();
                    long req = index + len;
                    if (req > arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return new byte[0]; }
                    if (arr.length < req) {
                        if (arr.length <= index) return new byte[0];
                        len = arr.length - index;
                    }
                    return Arrays.copyOfRange(arr, (int) index, (int) (index + len));
                }
            }
            throw new IllegalStateException("Operand.raw: invalid mode " + mode);
        }

        long value() throws SgbdException { return value(0); }

        long value(long dataLen) throws SgbdException {
            Object r = raw();
            if (r instanceof Long) return (Long) r & valueMask();
            if (!(r instanceof byte[])) throw new IllegalStateException("Operand.value: invalid data type");
            if (dataLen == 0) throw new IllegalStateException("Operand.value: invalid length");
            byte[] a = (byte[]) r;
            long v = 0;
            for (int i = (int) dataLen - 1; i >= 0; i--) {
                v <<= 8;
                v |= i < a.length ? (a[i] & 0xFF) : 0;
            }
            return u32(v);
        }

        double floatData() throws SgbdException {
            Object r = raw();
            if (!(r instanceof Double)) throw new IllegalStateException("Operand.floatData: invalid type");
            return (Double) r;
        }

        byte[] arrayData() throws SgbdException {
            Object r = raw();
            if (!(r instanceof byte[])) throw new IllegalStateException("Operand.arrayData: invalid type");
            return (byte[]) r;
        }

        String stringData() throws SgbdException { return str(arrayData()); }

        void setRaw(Object data) throws SgbdException { setRaw(data, 1); }

        void setRaw(Object data, long dataLen) throws SgbdException {
            switch (mode) {
                case REGS: case REGAB: case REGI: case REGL:
                    ((Register) d1).setRaw(data);
                    return;
                case IDXIMM: case IDXREG: case IDXREGIMM: {
                    Register reg = (Register) d1;
                    byte[] arr = reg.arrayData(false);
                    long index = mode == IDXIMM ? (Long) d2 : ((Register) d2).value();
                    if (mode == IDXREGIMM) index = u32(index + (Long) d3);
                    byte[] src;
                    if (data instanceof Long) {
                        src = new byte[(int) dataLen];
                        long v = (Long) data;
                        for (int i = 0; i < dataLen; i++) src[i] = (byte) (v >> (i << 3));
                    } else src = (byte[]) data;
                    long req = index + src.length;
                    if (req > arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return; }
                    if (arr.length < req) arr = Arrays.copyOf(arr, (int) req);
                    System.arraycopy(src, 0, arr, (int) index, src.length);
                    reg.setRaw(arr);
                    return;
                }
            }
            throw new IllegalStateException("Operand.setRaw: invalid mode " + mode);
        }

        void setArray(byte[] data) throws SgbdException {
            if (!(d1 instanceof Register) || mode != REGS) throw new IllegalStateException("Operand.setArray: invalid mode");
            ((Register) d1).setRaw(data);
        }

        void setString(String s) throws SgbdException {
            byte[] b = latin1(s);
            if (b.length > 0 && b[b.length - 1] != 0) b = Arrays.copyOf(b, b.length + 1);
            setArray(b);
        }
    }

    private long arrayMaxSize() { return arrayMaxBufSize - 1; }

    private void setArrayMaxBufSize(long v) {
        arrayMaxBufSize = v;
        for (StringData s : strRegs) s.newArrayLength(v);
    }

    private Register reg(int opcode) {
        Register r = opcode < registers.length ? registers[opcode] : null;
        if (r == null) throw new IllegalStateException(String.format("invalid register 0x%02X", opcode));
        return r;
    }

    // ===================== decode operand =====================
    private int readPos;

    private int rd8() { return dec(readPos++) & 0xFF; }

    private int rd16() { int v = rd8(); v |= rd8() << 8; return v; }

    private void getArg(int mode, Operand o) {
        switch (mode) {
            case NONE: o.init(mode, null, null, null); return;
            case REGS: case REGAB: case REGI: case REGL: o.init(mode, reg(rd8()), null, null); return;
            case IMM8: o.init(mode, (long) rd8(), null, null); return;
            case IMM16: o.init(mode, (long) rd16(), null, null); return;
            case IMM32: { long v = rd16(); v |= (long) rd16() << 16; o.init(mode, u32(v), null, null); return; }
            case IMMSTR: {
                int len = (short) rd16();
                byte[] b = new byte[Math.max(0, len)];
                for (int i = 0; i < b.length; i++) b[i] = (byte) rd8();
                o.init(mode, b, null, null);
                return;
            }
            case IDXIMM: { Register r = reg(rd8()); long idx = rd16(); o.init(mode, r, idx, null); return; }
            case IDXREG: { Register r = reg(rd8()); Register r2 = reg(rd8()); o.init(mode, r, r2, null); return; }
            case IDXREGIMM: { Register r = reg(rd8()); Register r2 = reg(rd8()); long inc = rd16(); o.init(mode, r, r2, inc); return; }
            case IDXIMMLENIMM: { Register r = reg(rd8()); long idx = rd16(); long len = rd16(); o.init(mode, r, idx, len); return; }
            case IDXIMMLENREG: { Register r = reg(rd8()); long idx = rd16(); Register l = reg(rd8()); o.init(mode, r, idx, l); return; }
            case IDXREGLENIMM: { Register r = reg(rd8()); Register i = reg(rd8()); long len = rd16(); o.init(mode, r, i, len); return; }
            case IDXREGLENREG: { Register r = reg(rd8()); Register i = reg(rd8()); Register l = reg(rd8()); o.init(mode, r, i, l); return; }
            default: throw new IllegalStateException("unsupported address mode " + mode);
        }
    }

    private static boolean isNearJump(int op) {
        return op == 0x0B || op == 0x0C || (op >= 0x0E && op <= 0x15) || op == 0x41 || op == 0x47 || op == 0x48 || (op >= 0x5A && op <= 0x5F);
    }

    // ===================== รัน job =====================
    /**
     * รัน job คืนชุดผลลัพธ์: index 0 = ผลระบบ, 1.. = ผลของ job
     * @param args อาร์กิวเมนต์คั่นด้วย ;
     */
    public List<Map<String, Result>> executeJob(String jobName, String args) throws SgbdException {
        if (requestInit) executeInitJob();
        jobStd = false;
        setArgs(args);
        return executeJobPrivate(jobName);
    }

    public void forceInit() { requestInit = true; }

    private void setArgs(String args) {
        argStrings = new ArrayList<>();
        if (args != null && args.length() > 0) {
            argBin = latin1(args);
            argStrings.addAll(Arrays.asList(args.split(";", -1)));
        } else argBin = null;
    }

    private void executeInitJob() throws SgbdException {
        boolean old = jobStd;
        jobStd = true;
        List<String> savedArgs = argStrings;
        byte[] savedBin = argBin;
        argStrings = new ArrayList<>();
        argBin = null;
        try {
            List<Map<String, Result>> sets = executeJobPrivate("INITIALISIERUNG");
            if (sets.size() > 1) {
                Result r = sets.get(1).get("DONE");
                if (r != null && r.value instanceof Long && (Long) r.value == 1) {
                    requestInit = false;
                    return;
                }
            }
            setError(SgbdException.Err.sys(10));
        } finally {
            jobStd = old;
            argStrings = savedArgs;
            argBin = savedBin;
        }
    }

    private List<Map<String, Result>> executeJobPrivate(String jobName) throws SgbdException {
        long ediabasBip = ((EDIABAS_VERSION >> 8) & 0xF) << 16 | ((EDIABAS_VERSION >> 4) & 0xF) << 8 | (EDIABAS_VERSION & 0xF);
        if (bipVersion > ediabasBip) { setError(SgbdException.Err.bip(9)); return new ArrayList<>(); }
        JobInfo job = jobs.get(jobName.toUpperCase(Locale.ROOT));
        if (job == null) { setError(SgbdException.Err.sys(8)); return new ArrayList<>(); }

        resultSetsTemp = new ArrayList<>();
        resultDict.clear();
        sysDict.clear();
        stack.clear();
        flags.init();
        for (StringData s : strRegs) s.clear();
        errorTrapBitNr = -1;
        errorTrapMask = 0;
        errorCodeLast = 0;
        resultJobStatus = "";
        setArrayMaxBufSize(job.arraySize);
        pc = job.offset;
        tableIndex = -1;
        tableRowIndex = -1;
        jobEnd = false;
        Operand a0 = new Operand(), a1 = new Operand();
        int steps = 0;
        try {
            while (!jobEnd) {
                if (abort) throw new SgbdException("ยกเลิกโดยผู้ใช้");
                if (++steps > maxSteps) throw new SgbdException("job ทำงานนานผิดปกติ (loop?)");
                readPos = (int) pc;
                int op = rd8();
                int am = rd8();
                getArg((am >> 4) & 0xF, a0);
                getArg(am & 0xF, a1);
                pc = readPos;
                if (isNearJump(op) && a0.mode == IMM32) a0.d1 = u32(pc + (Long) a0.d1);
                exec(op, a0, a1);
            }
            if (!resultDict.isEmpty()) resultSetsTemp.add(new LinkedHashMap<>(resultDict));
            resultDict.clear();
        } catch (SgbdException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SgbdException("SGBD runtime: " + e.getMessage() + String.format(" @%X", pc));
        } finally {
            Map<String, Result> sys = new LinkedHashMap<>();
            String obj = fileName.replaceAll("(?i)\\.(prg|grp)$", "");
            sys.put("VARIANTE", new Result(TYPE_S, "VARIANTE", obj.toUpperCase(Locale.ROOT)));
            sys.put("OBJECT", new Result(TYPE_S, "OBJECT", obj));
            sys.put("JOBNAME", new Result(TYPE_S, "JOBNAME", job.name));
            sys.put("SAETZE", new Result(TYPE_W, "SAETZE", (long) resultSetsTemp.size()));
            sys.put("JOBSTATUS", new Result(TYPE_S, "JOBSTATUS", resultJobStatus));
            sys.putAll(sysDict);
            resultSetsTemp.add(0, sys);
        }
        return resultSetsTemp;
    }

    private List<String> activeArgs() { return jobStd ? new ArrayList<>() : argStrings; }

    private byte[] activeArgBin() { return jobStd || argBin == null ? new byte[0] : argBin; }

    private void setResult(Result r) { resultDict.put(r.name.toUpperCase(Locale.ROOT), r); }

    private void needIface() throws SgbdException {
        if (iface == null || !iface.isConnected()) setError(SgbdException.Err.ifh(56));
    }

    // ===================== opcodes =====================
    private void exec(int op, Operand a0, Operand a1) throws SgbdException {
        switch (op) {
            case 0x00: opMove(a0, a1); break;
            case 0x01: opClear(a0); break;
            case 0x02: { // comp
                long len = a0.dataLen(true);
                long v0 = a0.value(len), v1 = a1.value(len);
                long diff = v0 - v1;
                flags.update(u32(diff), len);
                flags.setOverflow(v0, u32(-v1), u32(diff), len);
                flags.setCarry(diff, len);
                break;
            }
            case 0x03: case 0x4A: { // subb / subc
                long len = a0.dataLen(true);
                long v0 = a0.value(len), v1 = a1.value(len);
                if (op == 0x4A && flags.carry) v1++;
                long diff = v0 - v1;
                a0.setRaw(u32(diff));
                flags.update(u32(diff), len);
                flags.setOverflow(v0, u32(-v1), u32(diff), len);
                flags.setCarry(diff, len);
                break;
            }
            case 0x04: case 0x49: { // adds / addc
                long len = a0.dataLen(true);
                long v0 = a0.value(len), v1 = a1.value(len);
                if (op == 0x49 && flags.carry) v1++;
                long sum = v0 + v1;
                a0.setRaw(u32(sum));
                flags.update(u32(sum), len);
                flags.setOverflow(v0, v1, u32(sum), len);
                flags.setCarry(sum, len);
                break;
            }
            case 0x05: { // mult
                long len = a0.dataLen(true);
                long v1 = a0.value(len), v2 = a1.value(len);
                long res;
                if (len == 1) res = u32((long) (byte) v1 * (byte) v2);
                else if (len == 2) res = u32((long) (short) v1 * (short) v2);
                else res = u32((long) (int) v1 * (int) v2);
                a0.setRaw(res);
                flags.overflow = false;
                flags.update(res, len);
                if (a1.isRegister()) a1.setRaw(len == 4 ? 0L : res >>> (len << 3), len);
                break;
            }
            case 0x06: { // divs
                long len = a0.dataLen(true);
                long v1 = a0.value(len), v2 = a1.value(len);
                long res = 0, rem = 0;
                boolean err = false;
                int i1 = (int) v1, i2 = (int) v2;
                if (i2 == 0) {
                    err = true;
                    raiseError(SgbdException.Err.bip(7));
                } else {
                    res = u32(i1 / i2);
                    rem = u32(i1 % i2);
                }
                flags.overflow = false;
                flags.update(res, len);
                if (err) res = a0.value(len);
                a0.setRaw(res);
                if (a1.isRegister()) a1.setRaw(rem, len);
                break;
            }
            case 0x07: case 0x08: case 0x09: { // and / or / xor
                long len = a0.dataLen(true);
                long v0 = a0.value(len), v1 = a1.value(len);
                long v = op == 0x07 ? v0 & v1 : op == 0x08 ? v0 | v1 : v0 ^ v1;
                a0.setRaw(v);
                flags.overflow = false;
                flags.update(v, len);
                break;
            }
            case 0x0A: { // not
                long v = u32(~a0.value());
                a0.setRaw(v);
                flags.overflow = false;
                flags.update(a0.value(), a0.dataLen());
                break;
            }
            case 0x0B: pc = a0.value(); break; // jump
            case 0x0E: if (flags.carry) pc = a0.value(); break; // jc
            case 0x0F: if (!flags.carry) pc = a0.value(); break; // jae
            case 0x10: if (flags.zero) pc = a0.value(); break; // jz
            case 0x11: if (!flags.zero) pc = a0.value(); break; // jnz
            case 0x12: if (flags.overflow) pc = a0.value(); break; // jv
            case 0x13: if (!flags.overflow) pc = a0.value(); break; // jnv
            case 0x14: if (flags.sign) pc = a0.value(); break; // jmi
            case 0x15: if (!flags.sign) pc = a0.value(); break; // jpl
            case 0x16: flags.carry = false; break;
            case 0x17: flags.carry = true; break;
            case 0x18: opAsr(a0, a1); break;
            case 0x19: case 0x1B: opShl(a0, a1); break; // lsl / asl
            case 0x1A: opLsr(a0, a1); break;
            case 0x1C: break; // nop
            case 0x1D: // eoj
                if (a0.mode != NONE) resultJobStatus = a0.stringData();
                jobEnd = true;
                break;
            case 0x1E: { // push
                long v = a0.value();
                long len = a0.dataLen();
                for (int i = 0; i < len; i++) { stack.add((byte) v); v >>>= 8; }
                break;
            }
            case 0x1F: { // pop
                long len = a0.dataLen();
                long v = 0;
                if (stack.size() < len) setError(SgbdException.Err.bip(5));
                else for (int i = 0; i < len; i++) { v <<= 8; v |= stack.remove(stack.size() - 1) & 0xFF; }
                a0.setRaw(u32(v));
                flags.overflow = false;
                flags.update(u32(v), len);
                break;
            }
            case 0x20: { // scmp
                byte[] x = a0.arrayData(), y = a1.arrayData();
                flags.zero = Arrays.equals(x, y);
                break;
            }
            case 0x21: { // scat
                byte[] x = a0.arrayData(), y = a1.arrayData();
                byte[] r = Arrays.copyOf(x, x.length + y.length);
                System.arraycopy(y, 0, r, x.length, y.length);
                if (r.length > arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return; }
                a0.setRaw(r);
                break;
            }
            case 0x22: { // scut
                byte[] x = a0.arrayData();
                long len = a1.value();
                if (len > x.length) a0.setArray(new byte[0]);
                else a0.setArray(Arrays.copyOf(x, (int) (x.length - len)));
                break;
            }
            case 0x23: { // slen
                a0.setRaw(a1.dataLen());
                flags.overflow = false;
                flags.update(a0.value(), a0.dataLen());
                break;
            }
            case 0x24: opSpaste(a0, a1); break;
            case 0x25: opSerase(a0, a1); break;
            case 0x26: // xconnect
                if (iface == null) throw new SgbdException("ไม่มีช่องทางสื่อสาร");
                iface.connect();
                break;
            case 0x27: if (iface == null) throw new SgbdException("ไม่มีช่องทางสื่อสาร"); iface.disconnect(); break;
            case 0x28: opXsetpar(a0); break;
            case 0x29: { // xawlen
                needIface();
                if (iface == null || !iface.isConnected()) break;
                byte[] d = a0.arrayData();
                if ((d.length & 1) != 0) throw new IllegalStateException("xawlen length");
                short[] ans = new short[d.length / 2];
                for (int i = 0; i < ans.length; i++) ans[i] = (short) ((d[i * 2] & 0xFF) | (d[i * 2 + 1] << 8));
                iface.setCommAnswerLen(ans);
                break;
            }
            case 0x2A: { // xsend
                needIface();
                if (iface == null || !iface.isConnected()) break;
                byte[] resp = iface.transmit(a1.arrayData());
                if (resp != null) a0.setRaw(resp);
                break;
            }
            case 0x2B: case 0x2C: case 0x2D: needIface(); break; // xsendf / xrequf / xstopf (ไม่รองรับ frequent)
            case 0x2E: { needIface(); byte[] kb = iface != null ? iface.keyBytes() : null; if (kb != null) a0.setRaw(kb); break; }
            case 0x2F: { if (iface == null) { setError(SgbdException.Err.ifh(56)); break; } a0.setRaw(iface.state()); break; }
            case 0x30: case 0x31: if (iface == null) setError(SgbdException.Err.ifh(56)); break; // xboot / xreset
            case 0x32: { needIface(); if (iface != null) a0.setString(iface.interfaceType()); break; }
            case 0x33: { needIface(); if (iface != null) a0.setRaw(iface.interfaceVersion()); break; }
            case 0x34: setResult(new Result(TYPE_B, a0.stringData(), a1.value(1) & 0xFF)); break;
            case 0x35: setResult(new Result(TYPE_W, a0.stringData(), a1.value(2) & 0xFFFF)); break;
            case 0x36: setResult(new Result(TYPE_D, a0.stringData(), u32(a1.value(4)))); break;
            case 0x37: setResult(new Result(TYPE_I, a0.stringData(), (long) (short) a1.value(2))); break;
            case 0x38: setResult(new Result(TYPE_R, a0.stringData(), a1.floatData())); break;
            case 0x39: setResult(new Result(TYPE_S, a0.stringData(), a1.stringData())); break;
            case 0x3A: { // a2flt
                boolean[] valid = new boolean[1];
                double r = stringToFloat(a1.stringData(), valid);
                a0.setRaw(r);
                break;
            }
            case 0x3B: case 0x3C: case 0x3D: case 0x3E: { // fadd fsub fmul fdiv
                double x = a0.floatData(), y = a1.floatData();
                double r = op == 0x3B ? x + y : op == 0x3C ? x - y : op == 0x3D ? x * y : x / y;
                if (Double.isInfinite(r) || Double.isNaN(r)) { setError(SgbdException.Err.bip(11)); r = 0; }
                a0.setRaw(r);
                break;
            }
            case 0x3F: setResult(new Result(TYPE_Y, a0.stringData(), a1.arrayData())); break;
            case 0x40: // enewset
                if (resultSetsTemp != null && !resultDict.isEmpty()) {
                    resultSetsTemp.add(new LinkedHashMap<>(resultDict));
                    resultDict.clear();
                }
                break;
            case 0x41: break; // etag: ไม่ได้กรองผลลัพธ์ = ทำทุก tag
            case 0x42: { needIface(); if (iface != null) iface.setCommRepeats(a0.value()); break; }
            case 0x43: a0.setRaw(errorTrapMask); flags.update(a0.value(), a0.dataLen()); break; // gettmr
            case 0x44: errorTrapMask = a0.value(); break; // settmr
            case 0x45: { long e = a0.value(); if (e == 0) e = 0x40000000L; errorTrapBitNr = (int) e; break; } // sett
            case 0x46: errorTrapBitNr = -1; break; // clrt
            case 0x47: if (trapTest(a1, true)) pc = a0.value(); break; // jt
            case 0x48: if (!trapTest(a1, false)) pc = a0.value(); break; // jnt
            case 0x4B: setError(SgbdException.Err.bip(8)); break; // break
            case 0x4C: flags.overflow = false; break;
            case 0x4D: // eerr
                if (errorTrapBitNr >= 0) {
                    for (Map.Entry<Integer, Integer> e : TRAP_BITS.entrySet())
                        if (e.getValue() == errorTrapBitNr) raiseError(e.getKey());
                    raiseError(SgbdException.Err.bip(0));
                }
                break;
            case 0x4E: { // popf
                long v = 0;
                if (stack.size() < 4) setError(SgbdException.Err.bip(5));
                else for (int i = 0; i < 4; i++) { v <<= 8; v |= stack.remove(stack.size() - 1) & 0xFF; }
                flags.fromValue(v);
                break;
            }
            case 0x4F: { long v = flags.toValue(); for (int i = 0; i < 4; i++) { stack.add((byte) v); v >>>= 8; } break; }
            case 0x50: { // atsp
                long len = a0.dataLen();
                long pos = a1.value();
                long v = 0;
                if (stack.size() < len) setError(SgbdException.Err.bip(5));
                else {
                    int n = stack.size();
                    long index = pos - len;
                    if (index < 0) throw new IllegalStateException("atsp index");
                    for (int i = 0; i < len; i++) {
                        // C# Stack.ToArray(): index 0 = top
                        int k = (int) index++;
                        v <<= 8;
                        v |= stack.get(n - 1 - k) & 0xFF;
                    }
                }
                a0.setRaw(u32(v));
                flags.update(u32(v), len);
                break;
            }
            case 0x51: { // swap
                Register r = (Register) a0.d1;
                byte[] d = r.arrayData(true);
                long start = (Long) a0.d2, len = (Long) a0.d3;
                if (start + len > arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return; }
                for (int i = 0; i < len / 2; i++) {
                    byte t = d[(int) (start + i)];
                    d[(int) (start + i)] = d[(int) (start + len - 1 - i)];
                    d[(int) (start + len - 1 - i)] = t;
                }
                r.setArray(d, true);
                break;
            }
            case 0x52: tokenSeparator = a0.stringData(); tokenIndex = a1.value(); break; // setspc
            case 0x53: { byte[] d = a0.arrayData(); for (int i = 0; i < d.length / 2; i++) { byte t = d[i]; d[i] = d[d.length - 1 - i]; d[d.length - 1 - i] = t; } a0.setArray(d); break; }
            case 0x54: { // stoken
                if (tokenSeparator.isEmpty()) flags.zero = true;
                else {
                    String[] w = splitAny(a1.stringData(), tokenSeparator);
                    if (tokenIndex < 1 || tokenIndex > w.length) flags.zero = true;
                    else { a0.setString(w[(int) tokenIndex - 1]); flags.zero = false; }
                }
                break;
            }
            case 0x55: case 0x56: case 0x57: { // parb parw parl
                long res = 0;
                flags.zero = true; flags.carry = false; flags.sign = false; flags.overflow = false;
                long pos = a1.value() - 1;
                List<String> args = activeArgs();
                if (pos >= 0 && pos < args.size() && !args.get((int) pos).isEmpty()) {
                    res = u32(stringToValue(args.get((int) pos)));
                    flags.zero = false;
                }
                a0.setRaw(res);
                break;
            }
            case 0x58: { // pars
                String res = "";
                flags.zero = true;
                long pos = a1.value() - 1;
                List<String> args = activeArgs();
                if (pos >= 0 && pos < args.size() && !args.get((int) pos).isEmpty()) { res = args.get((int) pos); flags.zero = false; }
                a0.setString(res);
                break;
            }
            case 0x59: case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66:
                setError(SgbdException.Err.bip(6)); // ไฟล์ภายนอก: ไม่รองรับ
                if (op == 0x62) { a0.setArray(new byte[0]); flags.carry = true; }
                break;
            case 0x5A: if (flags.sign == flags.overflow && !flags.zero) pc = a0.value(); break; // jg
            case 0x5B: if (flags.zero || flags.sign == flags.overflow) pc = a0.value(); break; // jge
            case 0x5C: if (!flags.zero && flags.sign != flags.overflow) pc = a0.value(); break; // jl
            case 0x5D: if (flags.sign != flags.overflow || flags.zero) pc = a0.value(); break; // jle
            case 0x5E: if (!flags.carry && !flags.zero) pc = a0.value(); break; // ja
            case 0x5F: if (flags.carry || flags.zero) pc = a0.value(); break; // jbe
            case 0x67: { // a2fix
                long v = stringToValue(a1.stringData());
                if (v < Integer.MIN_VALUE) v = Integer.MIN_VALUE;
                if (v > Integer.MAX_VALUE) v = 0xFFFFFFFFL;
                a0.setRaw(u32(v));
                flags.zero = false; flags.sign = false; flags.overflow = false;
                break;
            }
            case 0x68: { // fix2flt
                long v = a1.value();
                long len = a1.dataLen();
                double r = len == 1 ? (byte) v : len == 2 ? (short) v : (int) v;
                a0.setRaw(r);
                break;
            }
            case 0x69: { // parr
                double res = 0;
                flags.zero = true; flags.carry = false; flags.sign = false; flags.overflow = false;
                long pos = a1.value() - 1;
                List<String> args = activeArgs();
                if (pos >= 0 && pos < args.size() && !args.get((int) pos).isEmpty()) { res = stringToFloat(args.get((int) pos), null); flags.zero = false; }
                a0.setRaw(res);
                break;
            }
            case 0x6A: { // test
                long len = a0.dataLen(true);
                long v = a0.value(len) & a1.value(len);
                flags.overflow = false;
                flags.update(v, len);
                break;
            }
            case 0x6B: sleep(a0.value() * 1000); break; // wait
            case 0x6C: { // date
                Calendar c = Calendar.getInstance();
                c.setMinimalDaysInFirstWeek(4);
                c.setFirstDayOfWeek(Calendar.MONDAY);
                int dow = c.get(Calendar.DAY_OF_WEEK) - 1;
                if (dow == 0) dow = 7;
                a0.setArray(new byte[]{(byte) c.get(Calendar.DAY_OF_MONTH), (byte) (c.get(Calendar.MONTH) + 1),
                        (byte) (c.get(Calendar.YEAR) % 100), (byte) c.get(Calendar.WEEK_OF_YEAR), (byte) dow});
                break;
            }
            case 0x6D: { // time
                Calendar c = Calendar.getInstance();
                a0.setArray(new byte[]{(byte) c.get(Calendar.HOUR_OF_DAY), (byte) c.get(Calendar.MINUTE), (byte) c.get(Calendar.SECOND)});
                break;
            }
            case 0x6E: { if (iface == null) { setError(SgbdException.Err.ifh(56)); break; } a0.setRaw(u32(iface.batteryVoltage())); break; }
            case 0x72: { if (iface == null) { setError(SgbdException.Err.ifh(56)); break; } a0.setRaw(u32(iface.ignitionVoltage())); break; }
            case 0x71: case 0x73: { if (iface == null) { setError(SgbdException.Err.ifh(56)); break; } a0.setRaw(0L); break; } // xgetport / xloopt
            case 0x74: case 0x76: case 0x77: if (iface == null) setError(SgbdException.Err.ifh(56)); break; // xprog / xsetport / xsireset
            case 0x75: needIface(); break; // xraw
            case 0x79: { // fix2hex
                long len = !isValueOperand(a1) ? 1 : a1.dataLen();
                long v = a1.value(len);
                a0.setString(len == 1 ? String.format("0x%02X", v) : len == 2 ? String.format("0x%04X", v) : String.format("0x%08X", v));
                break;
            }
            case 0x7A: { // fix2dez
                long len = !isValueOperand(a1) ? 1 : a1.dataLen();
                long v = a1.value(len);
                a0.setString(String.valueOf(len == 1 ? (byte) v : len == 2 ? (short) v : (int) v));
                break;
            }
            case 0x7B: opTabset(a0.stringData()); break;
            case 0x7C: { // tabseek
                if (tableIndex < 0) { setError(SgbdException.Err.bip(10)); return; }
                boolean[] found = new boolean[1];
                int row = seekTable(tableIndex, a0.stringData(), a1.stringData(), found);
                if (row < 0) { setError(SgbdException.Err.bip(10)); return; }
                tableRowIndex = row;
                flags.zero = !found[0];
                break;
            }
            case 0x7D: { // tabget
                if (tableIndex < 0) { setError(SgbdException.Err.bip(10)); return; }
                boolean[] colInvalid = new boolean[1];
                String entry = tableEntry(tableIndex, tableRowIndex, a1.stringData(), colInvalid);
                if (entry == null) {
                    if (tableRowIndex < 0 && !colInvalid[0]) { a0.setString(""); return; }
                    setError(SgbdException.Err.bip(10));
                    return;
                }
                a0.setString(entry);
                break;
            }
            case 0x7E: { // strcat
                long len1 = a0.dataLen();
                String s1 = a0.stringData(), s2 = a1.stringData();
                if (len1 + s2.length() > arrayMaxSize()) s2 = s2.substring(0, (int) Math.max(0, arrayMaxSize() - len1));
                a0.setString(s1 + s2);
                break;
            }
            case 0x7F: { // pary
                byte[] r = new byte[0];
                flags.zero = true;
                byte[] b = activeArgBin();
                if (b.length > 0) { r = b; flags.zero = false; }
                a0.setArray(r);
                break;
            }
            case 0x80: { // parn
                a0.setRaw((long) activeArgs().size());
                flags.overflow = false;
                flags.update(a0.value(), a0.dataLen());
                break;
            }
            case 0x81: setResult(new Result(TYPE_C, a0.stringData(), (long) (byte) a1.value(1))); break;
            case 0x82: setResult(new Result(TYPE_L, a0.stringData(), (long) (int) a1.value(4))); break;
            case 0x83: { // tabline
                if (tableIndex < 0) { setError(SgbdException.Err.bip(10)); return; }
                TableInfo t = tables[tableIndex];
                long line = a0.value();
                int row;
                boolean found;
                if (line >= t.rows) { row = t.rows - 1; found = false; } else { row = (int) line; found = true; }
                tableRowIndex = row;
                flags.zero = !found;
                break;
            }
            case 0x87: { // flt2a
                double v = a1.floatData();
                a0.setString(flt2a(v));
                break;
            }
            case 0x88: floatPrecision = a0.value(); break; // setflt
            case 0x89: { String v = config.get(a1.stringData()); if (v != null) a0.setRaw(u32(stringToValue(v))); break; } // cfgig
            case 0x8A: { String v = config.get(a1.stringData()); if (v != null) a0.setArray(latin1(v)); break; } // cfgsg
            case 0x8B: config.put(a0.stringData(), String.valueOf(a1.value())); break; // cfgis
            case 0x8C: a0.setArray(a2y(a1.stringData())); break;
            case 0x8E: { // hex2y
                String s = a1.stringData();
                byte[] r = hexToBytes(s);
                flags.carry = r.length == 0 && s.length() >= 2;
                a0.setArray(r);
                break;
            }
            case 0x8F: flags.zero = !a0.stringData().equals(a1.stringData()); break; // strcmp
            case 0x90: { // strlen
                long r = a1.stringData().length();
                a0.setRaw(r);
                flags.overflow = false;
                flags.update(r, a0.dataLen());
                break;
            }
            case 0x91: { // y2bcd
                StringBuilder sb = new StringBuilder();
                for (byte b : a1.arrayData()) {
                    int h = (b >> 4) & 0xF, l = b & 0xF;
                    sb.append(h > 9 ? "*" : Integer.toHexString(h).toUpperCase(Locale.ROOT));
                    sb.append(l > 9 ? "*" : Integer.toHexString(l).toUpperCase(Locale.ROOT));
                }
                a0.setString(sb.toString());
                break;
            }
            case 0x92: { // y2hex
                StringBuilder sb = new StringBuilder();
                for (byte b : a1.arrayData()) sb.append(String.format("%02X", b & 0xFF));
                a0.setString(sb.toString());
                break;
            }
            case 0x93: SHARED.put(a0.stringData().toUpperCase(Locale.ROOT), a1.arrayData()); break; // shmset
            case 0x94: { // shmget
                byte[] d = SHARED.get(a1.stringData().toUpperCase(Locale.ROOT));
                flags.carry = d == null;
                a0.setArray(d == null ? new byte[0] : d);
                break;
            }
            case 0x95: { // ergsysi
                String n = a0.stringData();
                long v = a1.value(2);
                if (n.equals("!INITIALISIERUNG")) { if (v != 0) requestInit = true; }
                else sysDict.put(n.toUpperCase(Locale.ROOT), new Result(TYPE_I, n, v));
                break;
            }
            case 0x96: { // flt2fix
                long r = u32((long) a1.floatData());
                a0.setRaw(r);
                flags.overflow = false;
                flags.update(r, 4);
                break;
            }
            case 0x97: case 0x98: case 0x99: break; // iupdate / irange / iincpos
            case 0x9A: { // tabseeku
                if (tableIndex < 0) { setError(SgbdException.Err.bip(10)); return; }
                boolean[] found = new boolean[1];
                int row = seekTableValue(tableIndex, a0.stringData(), a1.value(), found);
                if (row < 0) { setError(SgbdException.Err.bip(10)); return; }
                tableRowIndex = row;
                flags.zero = !found[0];
                break;
            }
            case 0x9B: case 0x9C: { // flt2y4 / flt2y8
                Register r = (Register) a0.d1;
                byte[] d = r.arrayData(false);
                long start = (Long) a0.d2;
                int n = op == 0x9B ? 4 : 8;
                if (start + n > arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return; }
                if (d.length < start + n) d = Arrays.copyOf(d, (int) (start + n));
                ByteBuffer bb = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
                if (n == 4) bb.putFloat((float) a1.floatData()); else bb.putDouble(a1.floatData());
                System.arraycopy(bb.array(), 0, d, (int) start, n);
                r.setArray(d, false);
                break;
            }
            case 0x9D: { byte[] d = Arrays.copyOf(a1.arrayData(), 4); a0.setRaw((double) ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getFloat()); break; }
            case 0x9E: { byte[] d = Arrays.copyOf(a1.arrayData(), 8); a0.setRaw(ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getDouble()); break; }
            case 0xA1: { // fcomp
                double x = a0.floatData(), y = a1.floatData();
                double d = x - y;
                if (Double.isInfinite(d) || Double.isNaN(d)) flags.carry = true;
                flags.zero = x == y;
                flags.sign = x < y;
                flags.overflow = false;
                break;
            }
            case 0x9F: case 0xA2: case 0xA3: case 0xA5: case 0xA7: case 0xA9: break; // plugin: no-op
            case 0xA4: a0.setRaw(0L); flags.overflow = false; flags.update(0, a0.dataLen()); break; // ppop
            case 0xA6: a0.setRaw(0.0); flags.overflow = false; break; // ppopflt
            case 0xA8: a0.setArray(new byte[0]); break; // ppopy
            case 0xAA: { // tabsetex: ตารางจากไฟล์อื่น — ใช้ไฟล์ตัวเองแทน
                opTabset(a0.stringData());
                break;
            }
            case 0xAB: { // ufix2dez
                long len = !isValueOperand(a1) ? 1 : a1.dataLen();
                long v = a1.value(len);
                a0.setString(String.valueOf(v & mask(len)));
                break;
            }
            case 0xAC: { // generr
                int e = (int) a0.value();
                raiseError(e < 250 || e > 399 ? SgbdException.Err.bip(1) : e);
                break;
            }
            case 0xAD: a0.setRaw(u32(System.currentTimeMillis())); break; // ticks
            case 0xAE: sleep(a0.value()); break; // waitex
            case 0xB5: a0.setRaw(arrayMaxBufSize); break; // ssize
            case 0xB6: a0.setRaw(tableIndex < 0 ? 0L : (long) tables[tableIndex].columns); break; // tabcols
            case 0xB7: a0.setRaw(tableIndex < 0 ? 0L : (long) tables[tableIndex].rows + 1); break; // tabrows
            default:
                throw new SgbdException(String.format("opcode 0x%02X ยังไม่รองรับ", op));
        }
    }

    private boolean isValueOperand(Operand o) { return !o.isArrayType(); }

    private static String[] splitAny(String s, String seps) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (seps.indexOf(s.charAt(i)) >= 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out.toArray(new String[0]);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(Math.max(0, ms)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private boolean trapTest(Operand a1, boolean jt) throws SgbdException {
        if (a1.mode != NONE) {
            long bit = a1.value(1);
            if (bit > 0) return errorTrapBitNr == (int) bit || (errorTrapBitNr == 0 && bit == 32);
            return errorTrapBitNr >= 0x40000000;
        }
        return jt ? errorTrapBitNr >= 0 : errorTrapBitNr >= 0x40000000;
    }

    private String flt2a(double value) {
        if (value == 0) return "0";
        double scale = Math.pow(10, Math.floor(Math.log10(Math.abs(value))) + 1);
        double conv = scale * BigDecimal.valueOf(value / scale).setScale((int) floatPrecision, java.math.RoundingMode.HALF_EVEN).doubleValue();
        String r = BigDecimal.valueOf(conv).stripTrailingZeros().toPlainString();
        int digits = 0;
        for (int i = 0; i < r.length(); i++) {
            if (Character.isDigit(r.charAt(i))) {
                digits++;
                if (digits >= floatPrecision) { r = r.substring(0, i + 1); break; }
            }
        }
        return r;
    }

    private static byte[] a2y(String data) {
        List<Byte> out = new ArrayList<>();
        String s = data;
        String lower = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (!(Character.isDigit(c) || (c >= 'a' && c <= 'f') || c == ' ' || c == ';' || c == ',')) { s = s.substring(0, i); break; }
        }
        if (!s.isEmpty()) {
            outer:
            for (String v : s.split("[,;]", -1)) {
                if (v.trim().isEmpty()) {
                    for (int i = 0; i < v.length() + 1; i++) out.add((byte) 0);
                } else {
                    for (String sub : v.trim().split(" ")) {
                        if (sub.length() > 0) {
                            try { out.add((byte) Integer.parseInt(sub, 16)); if (Integer.parseInt(sub, 16) > 255) break outer; }
                            catch (Exception e) { break outer; }
                        }
                    }
                }
            }
        }
        byte[] r = new byte[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private void opMove(Operand a0, Operand a1) throws SgbdException {
        boolean destArr = a0.isArrayType();
        boolean srcArr = a1.isArrayType();
        if (!destArr) {
            long len = a0.dataLen(true);
            long v = a1.value(len);
            a0.setRaw(v);
            flags.carry = false;
            flags.overflow = false;
            flags.update(v, len);
        } else if (!srcArr) {
            long len = a0.dataLen(true);
            long v = a1.value(len);
            a0.setRaw(v);
            flags.carry = false;
            flags.overflow = false;
            flags.update(v, 1);
        } else {
            Object src = a1.raw();
            if (a0.mode == REGS && src instanceof byte[]) {
                byte[] dest = a0.arrayData();
                byte[] s = (byte[]) src;
                if (dest.length < s.length) dest = Arrays.copyOf(dest, s.length);
                System.arraycopy(s, 0, dest, 0, s.length);
                a0.setRaw(dest);
            } else {
                a0.setRaw(a1.raw());
            }
            flags.carry = false;
            flags.zero = false;
            flags.sign = false;
            flags.overflow = false;
        }
    }

    private void opClear(Operand a0) throws SgbdException {
        Register r = (Register) a0.d1;
        if (r.isString()) r.clear();
        else if (r.isFloat()) floatRegs[r.index] = 0;
        else r.setValue(0);
        flags.carry = false;
        flags.zero = true;
        flags.sign = false;
        flags.overflow = false;
    }

    private void opShl(Operand a0, Operand a1) throws SgbdException {
        long len = a0.dataLen(true);
        long v = a0.value(len);
        int shift = (int) a1.value(len);
        if (shift == 0) flags.carry = false;
        else if (shift > 0) {
            if (shift > len * 8) flags.carry = false;
            else {
                long cs = len * 8 - shift;
                long cm = u32(1 << (int) cs);
                flags.carry = (v & cm) != 0;
            }
            v = shift >= len * 8 ? 0 : u32(v << shift);
        }
        a0.setRaw(v);
        flags.overflow = false;
        flags.update(v, len);
    }

    private void opLsr(Operand a0, Operand a1) throws SgbdException {
        long len = a0.dataLen(true);
        long v = a0.value(len);
        int shift = (int) a1.value(len);
        if (shift == 0) flags.carry = false;
        else if (shift > 0) {
            if (shift > len * 8) flags.carry = false;
            else flags.carry = (v & u32(1 << (shift - 1))) != 0;
            v = shift >= len * 8 ? 0 : v >>> shift;
        }
        a0.setRaw(v);
        flags.overflow = false;
        flags.update(v, len);
    }

    private void opAsr(Operand a0, Operand a1) throws SgbdException {
        long len = a0.dataLen(true);
        long v = a0.value(len);
        int shift = (int) a1.value(len);
        if (shift == 0) flags.carry = false;
        else if (shift > 0) {
            long signMask = u32(1 << (int) (len * 8 - 1));
            if (shift > len * 8) flags.carry = (v & signMask) != 0;
            else flags.carry = (v & u32(1 << (shift - 1))) != 0;
            if (shift >= len * 8) v = (v & signMask) != 0 ? 0xFFFFFFFFL : 0;
            else if (len == 1) v = u32(((byte) v) >> shift);
            else if (len == 2) v = u32(((short) v) >> shift);
            else v = u32(((int) v) >> shift);
        }
        a0.setRaw(v);
        flags.overflow = false;
        flags.update(v, len);
    }

    private long idxStart(Operand a0) {
        if (a0.mode == IDXIMM) return (Long) a0.d2;
        if (a0.mode == IDXREG) return ((Register) a0.d2).value();
        throw new IllegalStateException("invalid mode for spaste/serase");
    }

    private void opSpaste(Operand a0, Operand a1) throws SgbdException {
        long start = idxStart(a0);
        Register r = (Register) a0.d1;
        byte[] dest = r.arrayData(false);
        byte[] src = a1.arrayData();
        if (start >= arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return; }
        if (start < dest.length) {
            byte[] res = new byte[dest.length + src.length];
            System.arraycopy(dest, 0, res, 0, (int) start);
            System.arraycopy(src, 0, res, (int) start, src.length);
            System.arraycopy(dest, (int) start, res, (int) start + src.length, dest.length - (int) start);
            if (res.length > arrayMaxSize()) { setError(SgbdException.Err.bip(1)); return; }
            r.setArray(res, false);
        }
    }

    private void opSerase(Operand a0, Operand a1) throws SgbdException {
        long start = idxStart(a0);
        Register r = (Register) a0.d1;
        byte[] d = r.arrayData(false);
        long len = a1.value();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < d.length; i++) if (i < start || i >= start + len) bo.write(d[i]);
        r.setArray(bo.toByteArray(), false);
    }

    private void opXsetpar(Operand a0) throws SgbdException {
        needIface();
        if (iface == null || !iface.isConnected()) return;
        byte[] d = a0.arrayData();
        int len = d.length;
        int tl = 0;
        if (len >= 2) {
            int t = d[1] & 0xFF;
            tl = t == 0x00 ? 2 : t == 0x01 ? 4 : t == 0xFF ? 1 : 0;
        }
        long[] pars = new long[0];
        if (tl > 0 && len % tl == 0) {
            pars = new long[len / tl];
            for (int i = 0; i < pars.length; i++) {
                int o = i * tl;
                long v = d[o] & 0xFF;
                if (tl >= 2) v |= (long) (d[o + 1] & 0xFF) << 8;
                if (tl >= 4) v |= ((long) (d[o + 2] & 0xFF) << 16) | ((long) (d[o + 3] & 0xFF) << 24);
                pars[i] = v;
            }
        }
        iface.setCommParameter(pars);
    }

    // ===================== ตาราง =====================
    private void opTabset(String name) throws SgbdException {
        int last = tableIndex, lastRow = tableRowIndex;
        tableIndex = -1;
        tableRowIndex = -1;
        Integer idx = tableNames.get(name.toUpperCase(Locale.ROOT));
        int addr;
        if (idx != null) {
            indexTable(tables[idx]);
            addr = idx;
        } else {
            addr = tables.length - 1;
            setError(SgbdException.Err.bip(10));
        }
        tableIndex = addr;
        tableRowIndex = -1;
        if (tableIndex == last) tableRowIndex = lastRow;
    }

    private int columnIdx(TableInfo t, String col) {
        indexTable(t);
        Integer c = t.columnNames.get(col.toUpperCase(Locale.ROOT));
        return c == null ? -1 : c;
    }

    @SuppressWarnings("unchecked")
    private int seekTable(int ti, String col, String value, boolean[] found) {
        found[0] = false;
        if (ti < 0 || ti >= tables.length) return -1;
        TableInfo t = tables[ti];
        int c = columnIdx(t, col);
        if (c < 0) return -1;
        if (t.seekStr == null) t.seekStr = new Map[t.columns];
        if (t.seekStr[c] == null) {
            Map<String, Integer> m = new HashMap<>();
            for (int i = 1; i < t.entries.length; i++) {
                String s = tableString(t.entries[i][c]).toUpperCase(Locale.ROOT);
                if (!m.containsKey(s)) m.put(s, i - 1);
            }
            t.seekStr[c] = m;
        }
        Integer row = t.seekStr[c].get(value.toUpperCase(Locale.ROOT));
        if (row != null) { found[0] = true; return row; }
        return t.rows - 1;
    }

    @SuppressWarnings("unchecked")
    private int seekTableValue(int ti, String col, long value, boolean[] found) {
        found[0] = false;
        if (ti < 0 || ti >= tables.length) return -1;
        TableInfo t = tables[ti];
        int c = columnIdx(t, col);
        if (c < 0) return -1;
        if (t.seekVal == null) t.seekVal = new Map[t.columns];
        if (t.seekVal[c] == null) {
            Map<Long, Integer> m = new HashMap<>();
            for (int i = 1; i < t.entries.length; i++) {
                long v = u32(stringToValue(tableString(t.entries[i][c])));
                if (!m.containsKey(v)) m.put(v, i - 1);
            }
            t.seekVal[c] = m;
        }
        Integer row = t.seekVal[c].get(value);
        if (row != null) { found[0] = true; return row; }
        return t.rows - 1;
    }

    private String tableEntry(int ti, int row, String col, boolean[] colInvalid) {
        colInvalid[0] = false;
        if (ti < 0 || ti >= tables.length) return null;
        TableInfo t = tables[ti];
        int c = columnIdx(t, col);
        if (c < 0) { colInvalid[0] = true; return null; }
        if (row < 0 || row >= t.rows) return null;
        return tableString(t.entries[row + 1][c]);
    }
}
