package com.tms.bmwediag;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.tms.bmwediag.diag.CsvLogger;
import com.tms.bmwediag.diag.DefinitionLoader;
import com.tms.bmwediag.diag.DiagSession;
import com.tms.bmwediag.diag.Dtc;
import com.tms.bmwediag.diag.EcuDef;
import com.tms.bmwediag.io.FtdiSerial;
import com.tms.bmwediag.io.Hex;
import com.tms.bmwediag.proto.Elm327;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.tms.bmwediag.USB_PERMISSION";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_IMPORT = 41, REQ_BT_PERM = 42, REQ_SGBD = 43;

    // สี
    private static final int BG = 0xFF0F1318, CARD = 0xFF1A2029, ACCENT = 0xFF3B82F6, TEXT = 0xFFE6EAF0,
            MUTED = 0xFF8A94A6, OK = 0xFF22C55E, WARN = 0xFFF97316, ERR = 0xFFEF4444;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private SharedPreferences prefs;
    private SafFileSource sgbdFiles;
    private TextView sgbdLabel;
    private List<EcuDef> ecus = new ArrayList<>();
    private volatile DiagSession session;
    private BluetoothSocket btSocket;
    private volatile boolean polling;
    private CsvLogger csv;
    private String csvName;

    // views
    private TextView status, faultsText, infoText, termOut, logText, ecuNotes, dashInfo;
    private Spinner linkSpin, btSpin, ecuSpin, initSpin;
    private Button connectBtn, pollBtn, logBtn;
    private CheckBox cbEcho, cbDtr, cbRts, cbTrace;
    private EditText termIn, intervalIn, p3In;
    private GridLayout grid;
    private final List<TextView[]> tiles = new ArrayList<>();
    private FrameLayout content;
    private final List<View> pages = new ArrayList<>();
    private final List<Button> tabs = new ArrayList<>();
    private final List<BluetoothDevice> btDevices = new ArrayList<>();
    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        loadDefinitions();
        setContentView(buildUi());
        refreshBtDevices();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        disconnect();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ===================== UI =====================
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        bt.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(8));
        bt.setBackground(g);
        bt.setPadding(dp(14), dp(6), dp(14), dp(6));
        bt.setOnClickListener(l);
        return bt;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        c.setBackground(g);
        return c;
    }

    private Spinner spinner(List<?> items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<Object> a = new ArrayAdapter<Object>(this, android.R.layout.simple_spinner_item, new ArrayList<Object>(items)) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(TEXT);
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(8), dp(6), dp(8), dp(4));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), 0, dp(4), dp(2));
        TextView title = label("BMW DIAG", 17, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        head.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView sub = label("  Beta test · By TMS", 11, ACCENT);
        sub.setSingleLine(true);
        head.addView(sub, new LinearLayout.LayoutParams(-2, -2));
        status = label("ไม่ได้เชื่อมต่อ", 12, MUTED);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setGravity(Gravity.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
        sp.setMargins(dp(10), 0, 0, 0);
        head.addView(status, sp);
        root.addView(head);

        // ---- การเชื่อมต่อ ----
        LinearLayout conn = card();
        LinearLayout row1 = new LinearLayout(this);
        List<String> links = new ArrayList<>();
        links.add("USB สาย K+DCAN");
        links.add("Bluetooth ELM327");
        linkSpin = spinner(links);
        linkSpin.setSelection(prefs.getInt("link", 0));
        row1.addView(linkSpin, lp(0, -2, 1));
        btSpin = spinner(new ArrayList<String>());
        row1.addView(btSpin, lp(0, -2, 1));
        conn.addView(row1);
        linkSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                btSpin.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                prefs.edit().putInt("link", pos).apply();
                if (pos == 1) refreshBtDevices();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        LinearLayout row2 = new LinearLayout(this);
        ecuSpin = spinner(ecus);
        row2.addView(ecuSpin, lp(0, -2, 1));
        connectBtn = button("เชื่อมต่อ", v -> { if (session == null) connect(); else disconnect(); });
        row2.addView(connectBtn, lp(-2, -2, 0));
        conn.addView(row2);
        ecuNotes = label("", 12, MUTED);
        conn.addView(ecuNotes);
        ecuSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < ecus.size()) ecuNotes.setText(ecus.get(pos).notes);
                prefs.edit().putString("ecu", ecus.get(pos).id).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        selectSavedEcu();
        root.addView(conn, lp(-1, -2, 0));

        // ---- แท็บ ----
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        hs.addView(tabRow);
        String[] names = {"แดชบอร์ด", "Fault", "ข้อมูลกล่อง", "Terminal", "ตั้งค่า", "Log"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button t = button(names[i], v -> showPage(idx));
            tabs.add(t);
            tabRow.addView(t, lp(-2, -2, 0));
        }
        root.addView(hs);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.add(buildDashboard());
        pages.add(buildFaults());
        pages.add(buildInfo());
        pages.add(buildTerminal());
        pages.add(buildSettings());
        pages.add(buildLog());
        for (View p : pages) content.addView(p);
        showPage(0);
        return root;
    }

    private void showPage(int idx) {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == idx ? ACCENT : CARD);
            g.setCornerRadius(dp(8));
            tabs.get(i).setBackground(g);
        }
        if (idx == 5 && logText != null) synchronized (logBuf) { logText.setText(logBuf.toString()); }
    }

    private ScrollView scroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    private View buildDashboard() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        pollBtn = button("▶ เริ่มอ่านค่า", v -> { if (polling) stopPolling(); else startPolling(); });
        bar.addView(pollBtn, lp(0, -2, 1));
        logBtn = button("● บันทึก CSV", v -> toggleCsv());
        bar.addView(logBtn, lp(0, -2, 1));
        bar.addView(button("เลือกค่า / Job", v -> choosePids()), lp(0, -2, 1));
        l.addView(bar);
        dashInfo = label("เชื่อมต่อแล้วกด เริ่มอ่านค่า", 12, MUTED);
        l.addView(dashInfo);
        grid = new GridLayout(this);
        grid.setColumnCount(getResources().getConfiguration().screenWidthDp > 600 ? 4 : 2);
        l.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        return scroll(l);
    }

    private View buildFaults() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.addView(button("อ่านโค้ด Fault", v -> readFaults()), lp(0, -2, 1));
        Button clr = button("ลบโค้ด", v -> new AlertDialog.Builder(this)
                .setTitle("ลบโค้ด Fault?")
                .setMessage("จะลบความจำ fault ของกล่องที่เลือก (ควรอ่าน/จดโค้ดไว้ก่อน)")
                .setPositiveButton("ลบ", (d, w) -> clearFaults())
                .setNegativeButton("ยกเลิก", null).show());
        ((GradientDrawable) clr.getBackground()).setColor(ERR);
        bar.addView(clr, lp(0, -2, 1));
        l.addView(bar);
        LinearLayout c = card();
        faultsText = label("ยังไม่ได้อ่าน", 15, TEXT);
        faultsText.setTextIsSelectable(true);
        c.addView(faultsText);
        l.addView(c, lp(-1, -2, 0));
        return scroll(l);
    }

    private View buildInfo() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(button("อ่านข้อมูลกล่อง (Ident / VIN)", v -> readInfo()), lp(-1, -2, 0));

        LinearLayout c = card();
        infoText = label("—", 14, TEXT);
        infoText.setTypeface(Typeface.MONOSPACE);
        infoText.setTextIsSelectable(true);
        c.addView(infoText);
        l.addView(c, lp(-1, -2, 0));
        return scroll(l);
    }

    private View buildTerminal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(label("ส่งคำสั่งดิบ: DS2 = data (เช่น 00 / 0B 03), KWP/OBD = service (เช่น 1A 80 / 01 0C), ELM = คำสั่ง AT\nโหมด SGBD: พิมพ์ชื่อ job เช่น FS_LESEN หรือ STATUS_LESEN;arg", 12, MUTED));
        LinearLayout bar = new LinearLayout(this);
        termIn = new EditText(this);
        termIn.setTextColor(TEXT);
        termIn.setHint("เช่น 00");
        termIn.setHintTextColor(MUTED);
        termIn.setSingleLine(true);
        termIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        termIn.setTypeface(Typeface.MONOSPACE);
        bar.addView(termIn, lp(0, -2, 1));
        bar.addView(button("ส่ง", v -> sendRaw()), lp(-2, -2, 0));
        l.addView(bar);
        LinearLayout c = card();
        termOut = label("", 13, TEXT);
        termOut.setTypeface(Typeface.MONOSPACE);
        termOut.setTextIsSelectable(true);
        c.addView(termOut);
        l.addView(c, lp(-1, -2, 0));
        return scroll(l);
    }

    private View buildSettings() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout c = card();
        c.addView(label("สาย K+DCAN (USB)", 15, TEXT));
        cbEcho = check("ตัด echo ของ K-line (ปกติเปิด)", "echo", true);
        cbDtr = check("DTR = ON", "dtr", true);
        cbRts = check("RTS = ON", "rts", false);
        c.addView(cbEcho);
        c.addView(cbDtr);
        c.addView(cbRts);
        c.addView(label("สาย K+DCAN แบบมีสวิตช์: E46/E39/E83 ให้ตั้งเป็นโหมด K-line (ขา 7+8 เชื่อมกัน). "
                + "ถ้าสายสลับโหมดด้วย DTR/RTS ให้ลองสลับค่าด้านบน", 12, MUTED));
        c.addView(label("OBD2 init (USB)", 15, TEXT));
        List<String> inits = new ArrayList<>();
        inits.add("อัตโนมัติ (fast → 5-baud)");
        inits.add("Fast init (ISO14230)");
        inits.add("5-baud (ISO9141-2)");
        initSpin = spinner(inits);
        initSpin.setSelection(prefs.getInt("init", 0));
        initSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { prefs.edit().putInt("init", pos).apply(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        c.addView(initSpin);
        c.addView(label("รอบการอ่าน (ms)", 15, TEXT));
        intervalIn = new EditText(this);
        intervalIn.setTextColor(TEXT);
        intervalIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalIn.setText(String.valueOf(prefs.getInt("interval", 0)));
        c.addView(intervalIn);
        c.addView(label("0 = อ่านต่อเนื่องเร็วที่สุด", 12, MUTED));
        c.addView(label("หน่วงระหว่างคำสั่ง OBD/KWP — P3 (ms)", 15, TEXT));
        p3In = new EditText(this);
        p3In.setTextColor(TEXT);
        p3In.setInputType(InputType.TYPE_CLASS_NUMBER);
        p3In.setText(String.valueOf(prefs.getInt("p3", 25)));
        c.addView(p3In);
        c.addView(label("มาตรฐาน 55 ms, ลดเหลือ 20–25 จะเร็วขึ้น ถ้าค่าขึ้น n/a/ERR บ่อยให้เพิ่มกลับ (มีผลตอนเชื่อมต่อใหม่)", 12, MUTED));
        cbTrace = check("แสดง TX/RX ทุกเฟรมในแท็บ Log (ช้าลง ใช้ตอนหาปัญหา)", "trace", false);
        cbTrace.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("trace", on).apply();
            DiagSession s = session;
            if (s != null) worker.execute(() -> s.setTrace(on));
        });
        c.addView(cbTrace);
        l.addView(c, lp(-1, -2, 0));

        LinearLayout cs = card();
        cs.addView(label("SGBD (ไฟล์ .prg)", 15, TEXT));
        cs.addView(label("แอปมี SGBD ภาษาไทยฝังมาแล้ว ไม่ต้องตั้งค่า — ถ้าต้องการใช้เวอร์ชันใหม่กว่า เลือกโฟลเดอร์ Ecu ในมือถือ (ไฟล์ในโฟลเดอร์จะถูกใช้ก่อน)", 12, MUTED));
        sgbdLabel = label(sgbdStatus(), 13, OK);
        cs.addView(sgbdLabel);
        LinearLayout sb = new LinearLayout(this);
        sb.addView(button("เลือกโฟลเดอร์", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_SGBD);
        }), lp(0, -2, 1));
        sb.addView(button("ใช้ตัวที่ฝังในแอป", v -> {
            prefs.edit().remove("sgbdTree").apply();
            sgbdFiles = null;
            sgbdLabel.setText(sgbdStatus());
        }), lp(0, -2, 1));
        cs.addView(sb);
        l.addView(cs, lp(-1, -2, 0));

        LinearLayout c2 = card();
        c2.addView(label("นิยามกล่อง (ecus.json)", 15, TEXT));
        c2.addView(label("เพิ่ม/แก้คำสั่ง live data, offset, factor ได้เอง แล้ว import", 12, MUTED));
        LinearLayout bar = new LinearLayout(this);
        bar.addView(button("Import JSON", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        }), lp(0, -2, 1));
        bar.addView(button("Export ตัวปัจจุบัน", v -> exportDefinitions()), lp(0, -2, 1));
        bar.addView(button("คืนค่าเริ่มต้น", v -> {
            new File(getFilesDir(), "ecus.json").delete();
            loadDefinitions();
            resetEcuSpinner();
            toast("ใช้นิยามเริ่มต้นแล้ว");
        }), lp(0, -2, 1));
        c2.addView(bar);
        l.addView(c2, lp(-1, -2, 0));

        LinearLayout ca = card();
        ca.addView(label("BMW DIAG (Beta test) — By TMS", 15, ACCENT));
        ca.addView(label("เวอร์ชันทดสอบ 0.9 • รองรับ DME MS42/MS43/ME9.2 (E46)/MS45/MEV9.2 (E90) และเกียร์ GS20/GS8.60.4\n"
                + "ตัวรัน SGBD พอร์ตจาก EdiabasLib (GPLv3)", 12, MUTED));
        l.addView(ca, lp(-1, -2, 0));

        LinearLayout c3 = card();
        c3.addView(label("ข้อควรระวัง", 15, WARN));
        c3.addView(label("• ใช้ตอนรถจอดนิ่ง บิดกุญแจ ON หรือเครื่องติด\n• แอปนี้อ่านข้อมูล/ลบโค้ดเท่านั้น ไม่เขียนแฟลช\n"
                + "• ค่า DS2 live (0B 03) แสดงเป็นไบต์ดิบ ให้กำหนด offset/สูตรเองใน JSON", 13, MUTED));
        l.addView(c3, lp(-1, -2, 0));
        return scroll(l);
    }

    private CheckBox check(String s, String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(s);
        cb.setTextColor(TEXT);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean(key, c).apply());
        return cb;
    }

    private View buildLog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(button("ล้าง log", v -> { synchronized (logBuf) { logBuf.setLength(0); } logText.setText(""); }), lp(-2, -2, 0));
        logText = label("", 11, MUTED);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        l.addView(logText);
        return scroll(l);
    }

    // ===================== นิยามกล่อง =====================
    private void loadDefinitions() {
        String json = null;
        File f = new File(getFilesDir(), "ecus.json");
        try {
            if (f.exists()) json = readAll(new java.io.FileInputStream(f));
            if (json == null) json = readAll(getAssets().open("ecus.json"));
            ecus = DefinitionLoader.parse(json);
        } catch (Exception e) {
            toast("อ่าน ecus.json ไม่ได้: " + e.getMessage());
            try { ecus = DefinitionLoader.parse(readAll(getAssets().open("ecus.json"))); } catch (Exception ignored) {}
        }
    }

    private void resetEcuSpinner() {
        ArrayAdapter<Object> a = new ArrayAdapter<Object>(this, android.R.layout.simple_spinner_item, new ArrayList<Object>(ecus)) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(TEXT);
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ecuSpin.setAdapter(a);
        selectSavedEcu();
    }

    private void selectSavedEcu() {
        String id = prefs.getString("ecu", "obd");
        for (int i = 0; i < ecus.size(); i++) if (ecus.get(i).id.equals(id)) ecuSpin.setSelection(i);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    private void exportDefinitions() {
        try {
            File f = new File(getFilesDir(), "ecus.json");
            String json = f.exists() ? readAll(new java.io.FileInputStream(f)) : readAll(getAssets().open("ecus.json"));
            OutputStream os = openDownload("ecus.json", "application/json");
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.close();
            toast("บันทึก ecus.json ไว้ที่ Download/BmwEDiag");
        } catch (Exception e) {
            toast("Export ไม่สำเร็จ: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SGBD && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs.edit().putString("sgbdTree", tree.toString()).apply();
            sgbdFiles = null;
            final SafFileSource src = sgbdSource();
            worker.execute(() -> {
                String msg;
                try { msg = "พบ " + src.fileCount() + " ไฟล์"; } catch (Exception e) { msg = "อ่านโฟลเดอร์ไม่ได้: " + e.getMessage(); }
                final String m = msg;
                runOnUiThread(() -> { sgbdLabel.setText(sgbdStatus() + "  (" + m + ")"); toast(m); });
            });
            return;
        }
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            String json = readAll(getContentResolver().openInputStream(data.getData()));
            List<EcuDef> test = DefinitionLoader.parse(json);
            try (FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), "ecus.json"))) {
                fo.write(json.getBytes(StandardCharsets.UTF_8));
            }
            ecus = test;
            resetEcuSpinner();
            toast("Import แล้ว " + test.size() + " กล่อง");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("JSON ไม่ถูกต้อง").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    // ===================== เชื่อมต่อ =====================
    private DiagSession.Options options() {
        DiagSession.Options o = new DiagSession.Options();
        o.echo = cbEcho.isChecked();
        o.dtr = cbDtr.isChecked();
        o.rts = cbRts.isChecked();
        o.obdInit = DiagSession.ObdInit.values()[initSpin.getSelectedItemPosition()];
        o.trace = cbTrace.isChecked();
        try { o.p3Ms = Math.max(0, Math.min(200, Integer.parseInt(p3In.getText().toString()))); } catch (Exception e) { o.p3Ms = 25; }
        prefs.edit().putInt("p3", o.p3Ms).apply();
        o.obdPids = savedPids();
        final SafFileSource user = sgbdSource();
        final AssetFileSource builtIn = new AssetFileSource(getAssets());
        // โฟลเดอร์ที่ผู้ใช้เลือกก่อน ถ้าไม่มีไฟล์นั้นใช้ SGBD ภาษาไทยที่ฝังในแอป
        o.sgbdFiles = name -> {
            byte[] d = null;
            if (user != null) { try { d = user.read(name); } catch (Exception ignored) {} }
            return d != null ? d : builtIn.read(name);
        };
        return o;
    }

    /** PID ที่เลือกไว้ (ค่าเริ่มต้น = ชุดหลักที่ใช้จูน ไม่อ่านทุกตัวเพราะ K-line ช้า) */
    private int[] savedPids() {
        String s = prefs.getString("pids", "0C,0D,05,0F,04,11,0E,10,06,07");
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim(), 16);
        return out;
    }

    private SafFileSource sgbdSource() {
        if (sgbdFiles != null) return sgbdFiles;
        String t = prefs.getString("sgbdTree", null);
        if (t == null) return null;
        sgbdFiles = new SafFileSource(getContentResolver(), Uri.parse(t));
        return sgbdFiles;
    }

    private String sgbdStatus() {
        String t = prefs.getString("sgbdTree", null);
        if (t == null) return "✓ ใช้ SGBD ภาษาไทยที่ฝังในแอป (MS42/MS43/ME9.2/MS45/MEV9.2/GS20/GS8604)";
        String name = Uri.decode(t);
        int i = name.lastIndexOf(':');
        return "✓ ใช้ SGBD จาก: " + (i >= 0 ? name.substring(i + 1) : name);
    }

    private void chooseSgbdJob(DiagSession s) {
        List<String> jobs = s.sgbd().statusJobs();
        if (jobs.isEmpty()) jobs = s.sgbd().jobs();
        final String[] arr = jobs.toArray(new String[0]);
        final boolean[] checked = new boolean[arr.length];
        String cur = prefs.getString("sgbdJob_" + s.sgbd().variantName, "");
        for (int i = 0; i < arr.length; i++) for (String c : cur.split(",")) if (c.trim().equals(arr[i])) checked[i] = true;
        new AlertDialog.Builder(this)
                .setTitle("เลือกค่าที่จะแสดง (" + s.sgbd().variantName + ") — เลือกได้หลายอัน")
                .setMultiChoiceItems(arr, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("ตกลง", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length; i++) if (checked[i]) { if (sb.length() > 0) sb.append(','); sb.append(arr[i]); }
                    final String spec = sb.toString();
                    prefs.edit().putString("sgbdJob_" + s.sgbd().variantName, spec).apply();
                    worker.execute(() -> s.setSgbdLiveJob(spec));
                })
                .setNegativeButton("ยกเลิก", null).show();
    }

    private void choosePids() {
        DiagSession curSession = session;
        if (curSession != null && curSession.isSgbd()) { chooseSgbdJob(curSession); return; }
        List<com.tms.bmwediag.diag.ObdPids.Pid> all = com.tms.bmwediag.diag.ObdPids.ALL;
        CharSequence[] names = new CharSequence[all.size()];
        boolean[] checked = new boolean[all.size()];
        int[] cur = savedPids();
        for (int i = 0; i < all.size(); i++) {
            com.tms.bmwediag.diag.ObdPids.Pid p = all.get(i);
            names[i] = String.format("%02X  %s (%s)", p.pid, p.name, p.unit);
            for (int x : cur) if (x == p.pid) checked[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle("เลือกค่า OBD ที่จะอ่าน (ยิ่งน้อยยิ่งเร็ว)")
                .setMultiChoiceItems(names, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("ตกลง", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    List<Integer> sel = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) if (checked[i]) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(String.format("%02X", all.get(i).pid));
                        sel.add(all.get(i).pid);
                    }
                    prefs.edit().putString("pids", sb.toString()).apply();
                    int[] arr = new int[sel.size()];
                    for (int i = 0; i < arr.length; i++) arr[i] = sel.get(i);
                    DiagSession s = session;
                    if (s != null) worker.execute(() -> s.setObdPids(arr));
                })
                .setNegativeButton("ยกเลิก", null).show();
    }

    private void setStatus(String s, int color) {
        runOnUiThread(() -> { status.setText(s); status.setTextColor(color); });
    }

    private void connect() {
        if (ecus.isEmpty()) return;
        EcuDef ecu = ecus.get(ecuSpin.getSelectedItemPosition());
        DiagSession.Options o = options();
        boolean usb = linkSpin.getSelectedItemPosition() == 0;
        if (usb) {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice dev = null;
            for (UsbDevice d : um.getDeviceList().values()) if (d.getVendorId() == 0x0403) dev = d;
            if (dev == null) { toast("ไม่พบสาย FTDI K+DCAN (ต่อผ่าน OTG แล้วหรือยัง?)"); return; }
            if (!um.hasPermission(dev)) { requestUsbPermission(um, dev); return; }
            final UsbDevice device = dev;
            setStatus("กำลังเชื่อมต่อ...", WARN);
            worker.execute(() -> {
                DiagSession s = new DiagSession(ecu, o, this::log);
                try {
                    s.connectSerial(new FtdiSerial(um, device));
                    onConnected(s);
                } catch (Exception e) {
                    s.close();
                    onConnectFail(e);
                }
            });
        } else {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_PERM);
                return;
            }
            int i = btSpin.getSelectedItemPosition();
            if (i < 0 || i >= btDevices.size()) { toast("ยังไม่ได้จับคู่ ELM327 ในตั้งค่า Bluetooth ของมือถือ"); return; }
            BluetoothDevice dev = btDevices.get(i);
            prefs.edit().putString("bt", dev.getAddress()).apply();
            setStatus("กำลังเชื่อมต่อ Bluetooth...", WARN);
            worker.execute(() -> {
                DiagSession s = new DiagSession(ecu, o, this::log);
                try {
                    btSocket = openBt(dev);
                    s.connectElm(new Elm327(btSocket.getInputStream(), btSocket.getOutputStream()));
                    onConnected(s);
                } catch (Exception e) {
                    s.close();
                    closeBt();
                    onConnectFail(e);
                }
            });
        }
    }

    @SuppressWarnings("MissingPermission")
    private BluetoothSocket openBt(BluetoothDevice dev) throws IOException {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad != null) ad.cancelDiscovery();
        try {
            BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (IOException e) {
            log("SPP ปกติไม่สำเร็จ ลองช่อง 1: " + e.getMessage());
            try {
                Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                BluetoothSocket s = (BluetoothSocket) m.invoke(dev, 1);
                s.connect();
                return s;
            } catch (Exception e2) {
                throw new IOException("เชื่อม Bluetooth ไม่ได้: " + e.getMessage());
            }
        }
    }

    private void closeBt() {
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        btSocket = null;
    }

    private void onConnected(DiagSession s) {
        if (s.isSgbd()) {
            String j = prefs.getString("sgbdJob_" + s.sgbd().variantName, null);
            if (j != null) s.setSgbdLiveJob(j);
        }
        session = s;
        setStatus("● เชื่อมต่อแล้ว", OK);
        runOnUiThread(() -> {
            connectBtn.setText("ตัดการเชื่อมต่อ");
            ecuNotes.setVisibility(View.GONE);
            ((GradientDrawable) connectBtn.getBackground()).setColor(ERR);
            dashInfo.setText(s.describe());
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private void onConnectFail(Exception e) {
        log("เชื่อมต่อไม่สำเร็จ: " + e);
        setStatus("เชื่อมต่อไม่สำเร็จ", ERR);
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("เชื่อมต่อไม่สำเร็จ")
                .setMessage(e.getMessage() + "\n\nตรวจสอบ: กุญแจ ON, เลือกกล่อง/โปรโตคอลถูกรุ่น, โหมดสาย (K-line/D-CAN), ดูรายละเอียดในแท็บ Log")
                .setPositiveButton("OK", null).show());
    }

    private void disconnect() {
        stopPolling();
        final DiagSession s = session;
        session = null;
        worker.execute(() -> {
            if (s != null) s.close();
            closeBt();
        });
        setStatus("ไม่ได้เชื่อมต่อ", MUTED);
        runOnUiThread(() -> {
            connectBtn.setText("เชื่อมต่อ");
            ecuNotes.setVisibility(View.VISIBLE);
            ((GradientDrawable) connectBtn.getBackground()).setColor(ACCENT);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private void requestUsbPermission(UsbManager um, UsbDevice dev) {
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try { unregisterReceiver(this); } catch (Exception ignored) {}
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect();
                else toast("ไม่ได้รับอนุญาตใช้ USB");
            }
        };
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
        um.requestPermission(dev, pi);
    }

    @SuppressWarnings("MissingPermission")
    private void refreshBtDevices() {
        btDevices.clear();
        List<String> names = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                if (linkSpin != null && linkSpin.getSelectedItemPosition() == 1)
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_PERM);
            } else {
                BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                if (ad != null) {
                    Set<BluetoothDevice> bonded = ad.getBondedDevices();
                    String saved = prefs.getString("bt", "");
                    int sel = 0;
                    for (BluetoothDevice d : bonded) {
                        if (d.getAddress().equals(saved)) sel = btDevices.size();
                        btDevices.add(d);
                        names.add((d.getName() == null ? "?" : d.getName()) + " " + d.getAddress());
                    }
                    if (btSpin != null) {
                        setBtNames(names);
                        btSpin.setSelection(sel);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log("Bluetooth: " + e.getMessage());
        }
        if (btSpin != null) setBtNames(names);
    }

    private void setBtNames(List<String> names) {
        if (names.isEmpty()) names.add("(ไม่มีอุปกรณ์ที่จับคู่)");
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(TEXT);
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        btSpin.setAdapter(a);
        btSpin.setVisibility(linkSpin.getSelectedItemPosition() == 1 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_BT_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) refreshBtDevices();
    }

    // ===================== งานวินิจฉัย =====================
    private boolean need() {
        if (session == null) { toast("ยังไม่ได้เชื่อมต่อ"); return false; }
        return true;
    }

    private void readFaults() {
        if (!need()) return;
        faultsText.setText("กำลังอ่าน...");
        final DiagSession s = session;
        worker.execute(() -> {
            String out;
            try {
                List<Dtc.Code> codes = s.readFaults();
                int real = 0;
                for (Dtc.Code c : codes) if (!c.code.equals("✓") && !c.code.equals("—")) real++;
                StringBuilder sb = new StringBuilder();
                sb.append(real == 0 ? "ไม่พบโค้ด fault ✓" : "พบ " + real + " โค้ด").append("\n\n");
                for (Dtc.Code c : codes) {
                    if (c.code.equals("✓") || c.code.equals("—")) continue;
                    sb.append("● ").append(c.code).append('\n');
                    if (!c.text.isEmpty()) sb.append("   ").append(c.text).append('\n');
                    sb.append('\n');
                }
                out = sb.toString().trim();
            } catch (Exception e) {
                out = "อ่านโค้ดไม่สำเร็จ: " + e.getMessage();
            }
            final String o = out;
            runOnUiThread(() -> faultsText.setText(o));
        });
    }

    private void clearFaults() {
        if (!need()) return;
        final DiagSession s = session;
        worker.execute(() -> {
            String out;
            try { out = s.clearFaults(); } catch (Exception e) { out = "ลบไม่สำเร็จ: " + e.getMessage(); }
            final String o = out;
            runOnUiThread(() -> faultsText.setText(o));
        });
    }

    private void readInfo() {
        if (!need()) return;
        infoText.setText("กำลังอ่าน...");
        final DiagSession s = session;
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                for (String line : s.readIdent()) sb.append(line).append('\n');
            } catch (Exception e) {
                sb.append("ผิดพลาด: ").append(e.getMessage());
            }
            runOnUiThread(() -> infoText.setText(sb.toString()));
        });
    }

    private void scanModules() {
        if (!need()) return;
        infoText.setText("กำลังสแกน...");
        final DiagSession s = session;
        final List<EcuDef> defs = new ArrayList<>(ecus);
        worker.execute(() -> {
            String out;
            try {
                StringBuilder sb = new StringBuilder();
                for (String line : s.scanDs2(defs, m -> runOnUiThread(() -> infoText.setText(m)))) sb.append(line).append('\n');
                out = sb.toString();
            } catch (Exception e) {
                out = "สแกนไม่สำเร็จ: " + e.getMessage();
            }
            final String o = out;
            runOnUiThread(() -> infoText.setText(o));
        });
    }

    private void sendRaw() {
        if (!need()) return;
        final String in = termIn.getText().toString().trim();
        if (in.isEmpty()) return;
        final DiagSession s = session;
        worker.execute(() -> {
            String out;
            try {
                if (in.toUpperCase(Locale.US).startsWith("AT")) out = s.rawElmCommand(in);
                else if (s.isSgbd() && in.matches("(?s)[A-Za-z_][A-Za-z0-9_]*[G-Zg-z_][A-Za-z0-9_]*(;.*)?")) {
                    StringBuilder sb = new StringBuilder();
                    for (String line : s.runJob(in)) sb.append(line).append('\n');
                    out = sb.toString().trim();
                }
                else out = Hex.of(s.raw(Hex.parse(in)));
            } catch (Exception e) {
                out = "ERR " + e.getMessage();
            }
            final String o = "> " + in + "\n" + out + "\n\n";
            runOnUiThread(() -> termOut.setText(o + termOut.getText()));
        });
    }

    // ===================== Live =====================
    private void startPolling() {
        if (!need()) return;
        int iv;
        try { iv = Integer.parseInt(intervalIn.getText().toString()); } catch (Exception e) { iv = 0; }
        iv = Math.max(0, Math.min(iv, 10000));
        prefs.edit().putInt("interval", iv).apply();
        polling = true;
        pollBtn.setText("■ หยุด");
        final int interval = iv;
        final DiagSession s = session;
        worker.execute(new Runnable() {
            long last = System.currentTimeMillis();
            int n = 0;
            @Override public void run() {
                if (!polling || session != s) return;
                try {
                    List<DiagSession.Reading> r = s.pollLive();
                    if (csv != null) try { csv.write(r); } catch (IOException e) { log("CSV: " + e.getMessage()); }
                    n++;
                    long now = System.currentTimeMillis();
                    final String rate = String.format(Locale.US, "%.1f Hz", 1000.0 / Math.max(1, now - last));
                    last = now;
                    runOnUiThread(() -> {
                        updateTiles(r);
                        dashInfo.setText(s.describe() + "   " + rate + (csv != null ? "   CSV " + csv.rows + " แถว" : ""));
                    });
                } catch (Exception e) {
                    log("poll: " + e.getMessage());
                    runOnUiThread(() -> dashInfo.setText("ผิดพลาด: " + e.getMessage()));
                }
                if (polling) worker.schedule(this, interval, TimeUnit.MILLISECONDS);
            }
        });
    }

    private void stopPolling() {
        polling = false;
        if (pollBtn != null) runOnUiThread(() -> pollBtn.setText("▶ เริ่มอ่านค่า"));
        if (csv != null) toggleCsv();
    }

    private void updateTiles(List<DiagSession.Reading> r) {
        if (r.isEmpty()) {
            dashInfo.setText("กล่องนี้ยังไม่มีนิยาม live data — เลือก \"OBD2 มาตรฐาน\" หรือเพิ่มคำสั่งใน ecus.json");
            return;
        }
        if (tiles.size() != r.size() || !((String) tiles.get(0)[0].getTag()).equals(r.get(0).name)) {
            grid.removeAllViews();
            tiles.clear();
            int w = (getResources().getDisplayMetrics().widthPixels - dp(28)) / grid.getColumnCount();
            for (DiagSession.Reading x : r) {
                LinearLayout c = card();
                TextView n = label(x.name, 12, MUTED);
                n.setTag(x.name);
                TextView v = label("--", 26, TEXT);
                v.setTypeface(Typeface.DEFAULT_BOLD);
                TextView u = label(x.unit, 12, MUTED);
                c.addView(n);
                c.addView(v);
                c.addView(u);
                GridLayout.LayoutParams p = new GridLayout.LayoutParams();
                p.width = w - dp(8);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                grid.addView(c, p);
                tiles.add(new TextView[]{n, v, u});
            }
        }
        for (int i = 0; i < r.size(); i++) {
            DiagSession.Reading x = r.get(i);
            TextView v = tiles.get(i)[1];
            v.setText(x.text);
            v.setTextColor(x.value == null ? MUTED : TEXT);
        }
    }

    // ===================== CSV =====================
    private void toggleCsv() {
        if (csv != null) {
            final CsvLogger c = csv;
            csv = null;
            worker.execute(c::close);
            runOnUiThread(() -> {
                logBtn.setText("● บันทึก CSV");
                ((GradientDrawable) logBtn.getBackground()).setColor(ACCENT);
                toast("บันทึกแล้ว: Download/BmwEDiag/" + csvName + " (" + c.rows + " แถว)");
            });
            return;
        }
        if (session == null) { toast("ยังไม่ได้เชื่อมต่อ"); return; }
        try {
            String id = session.ecu().id;
            csvName = id + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
            csv = new CsvLogger(openDownload(csvName, "text/csv"));
            logBtn.setText("■ หยุดบันทึก");
            ((GradientDrawable) logBtn.getBackground()).setColor(ERR);
            if (!polling) startPolling();
        } catch (Exception e) {
            toast("สร้างไฟล์ CSV ไม่ได้: " + e.getMessage());
        }
    }

    /** สร้างไฟล์ในโฟลเดอร์ Download/BmwEDiag */
    private OutputStream openDownload(String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/BmwEDiag");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("MediaStore insert ล้มเหลว");
            return getContentResolver().openOutputStream(uri);
        }
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "BmwEDiag");
        if (!dir.exists() && !dir.mkdirs()) dir = getExternalFilesDir(null);
        return new FileOutputStream(new File(dir, name));
    }

    // ===================== log =====================
    private void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + s + "\n";
        synchronized (logBuf) {
            logBuf.append(line);
            if (logBuf.length() > 60000) logBuf.delete(0, logBuf.length() - 40000);
        }
        if (logText != null && logText.isShown()) {
            runOnUiThread(() -> { synchronized (logBuf) { logText.setText(logBuf.toString()); } });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (logText != null) synchronized (logBuf) { logText.setText(logBuf.toString()); }
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }
}
