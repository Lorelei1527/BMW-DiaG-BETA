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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.tms.bmwediag.diag.DefinitionLoader;
import com.tms.bmwediag.diag.DiagSession;
import com.tms.bmwediag.diag.Dtc;
import com.tms.bmwediag.diag.EcuDef;
import com.tms.bmwediag.io.FtdiSerial;
import com.tms.bmwediag.proto.Elm327;
import com.tms.bmwediag.ui.Gfx;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.tms.bmwediag.ui.Gfx.*;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.tms.bmwediag.USB_PERMISSION";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_IMPORT = 41, REQ_BT_PERM = 42, REQ_SGBD = 43;
    private static final String VERSION = "เวอร์ชันทดสอบ 0.9";
    private static final int P_CONNECT = 0, P_DATA = 1, P_FAULT = 2, P_INFO = 3, P_MORE = 4;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private SharedPreferences prefs;
    private SafFileSource sgbdFiles;
    private TextView sgbdLabel;
    private List<EcuDef> ecus = new ArrayList<>();
    private volatile DiagSession session;
    private BluetoothSocket btSocket;
    private volatile boolean polling;
    private int linkIdx, ecuIdx, page;

    // ส่วนหัว
    private TextView hTitle, hSub, chipText;
    private View chipDot;
    // หน้าเชื่อมต่อ
    private final List<LinearLayout> linkCards = new ArrayList<>(), ecuCards = new ArrayList<>();
    private Spinner btSpin, initSpin;
    private TextView ecuNotes, connectBtn;
    private LinearLayout ecuGrid;
    // หน้า data
    private TextView heroName, heroVal, heroUnit, pollBtn, chooseBtn, dashInfo;
    private LinearLayout heroBar, heroTicks, tileGrid;
    private View heroFill, heroEmpty;
    private LinearLayout heroCard;
    private final List<Tile> tiles = new ArrayList<>();
    private final Map<String, Double> maxSeen = new HashMap<>();
    private String rate = "";
    // หน้า fault / ข้อมูลกล่อง
    private TextView fCount, fCurrent, fMsg, vinText, infoMsg;
    private LinearLayout fList, infoBox;
    private boolean faultsLoaded, infoLoaded;
    // ตั้งค่า
    private CheckBox cbFast, cbDtr, cbRts;
    private EditText intervalIn, p3In;
    // โครง
    private FrameLayout content;
    private final List<View> pages = new ArrayList<>();
    private final List<View[]> navItems = new ArrayList<>();
    private final List<BluetoothDevice> btDevices = new ArrayList<>();
    private final StringBuilder logBuf = new StringBuilder();

    private static final class Tile {
        String name;
        TextView n, v, u;
        View accent, fill, empty;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(NAV);
        loadDefinitions();
        linkIdx = prefs.getInt("link", 0);
        final View ui = buildUi();
        if (b == null) {
            setContentView(new Gfx.SplashView(this, VERSION));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                ui.setAlpha(0f);
                setContentView(ui);
                ui.animate().alpha(1f).setDuration(300).start();
            }, 1800);
        } else setContentView(ui);
        refreshBtDevices();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        disconnect();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ===================== ตัวช่วย UI =====================
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView bold(String s, float size, int color) {
        TextView t = label(s, size, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(round(CARD, LINE, 14));
        return c;
    }

    /** ปุ่มแบบธีม: kind 0 = น้ำเงิน, 1 = แดง, 2 = เทา */
    private TextView btn(String s, int kind, View.OnClickListener l) {
        TextView t = bold(s, 14, kind == 2 ? TEXT : Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(13), dp(12), dp(13));
        setBtnKind(t, kind);
        t.setOnClickListener(l);
        t.setClickable(true);
        return t;
    }

    private void setBtnKind(TextView t, int kind) {
        t.setBackground(round(kind == 0 ? M_BLUE : kind == 1 ? M_RED : CARD2, kind == 2 ? LINE : 0, 12));
        t.setTextColor(kind == 2 ? TEXT : Color.WHITE);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private LinearLayout.LayoutParams lpm(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout col() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        return r;
    }

    private TextView section(String s) {
        TextView t = label(s, 13, MUTED);
        t.setPadding(dp(4), dp(16), dp(4), dp(8));
        return t;
    }

    private ScrollView scroll(View v) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(v);
        return s;
    }

    private View triLine(int wDp, int hDp) {
        View v = new View(this);
        v.setBackground(new Gfx.TriBar());
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return v;
    }

    /** แถบระดับ: fill / empty ใช้ weight */
    private LinearLayout meter(int hDp, View[] out) {
        LinearLayout m = row();
        m.setBackground(round(0xFF1E2229, 0, hDp / 2f));
        View f = new View(this), e = new View(this);
        m.addView(f, lp(0, -1, 0));
        m.addView(e, lp(0, -1, 1));
        out[0] = f;
        out[1] = e;
        return m;
    }

    private void setMeter(View fill, View empty, double frac, Drawable look) {
        frac = Math.max(0, Math.min(1, frac));
        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = (float) frac;
        ((LinearLayout.LayoutParams) empty.getLayoutParams()).weight = (float) (1 - frac);
        if (look != null) fill.setBackground(look);
        fill.requestLayout();
    }

    private Spinner spinner(List<?> items) {
        Spinner s = new Spinner(this);
        s.setBackground(round(CARD2, LINE, 10));
        s.setPadding(dp(10), dp(8), dp(10), dp(8));
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

    // ===================== โครงหลัก =====================
    private View buildUi() {
        LinearLayout root = col();
        root.setBackgroundColor(BG);

        // ---- ส่วนหัว ----
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(14), dp(6));
        LinearLayout tcol = col();
        hTitle = label("BMW DIAG", 21, TEXT);
        hTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
        hSub = label("BETA TEST · BY TMS", 10.5f, MUTED);
        hSub.setLetterSpacing(0.1f);
        tcol.addView(hTitle);
        tcol.addView(hSub);
        head.addView(tcol, lp(0, -2, 1));
        LinearLayout chip = row();
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(6), dp(12), dp(6));
        chip.setBackground(round(CARD, LINE, 20));
        chipDot = new View(this);
        chipDot.setBackground(round(0xFF555A63, 0, 5));
        chip.addView(chipDot, lpm(dp(0) + dp(8), dp(8), 0, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) chipDot.getLayoutParams()).width = dp(8);
        chipText = label("ไม่ได้เชื่อมต่อ", 12, MUTED);
        chip.addView(chipText, lpm(-2, -2, 0, 6, 0, 0, 0));
        head.addView(chip);
        root.addView(head);
        LinearLayout stripeRow = row();
        stripeRow.setPadding(dp(16), 0, 0, dp(4));
        stripeRow.addView(triLine(56, 3));
        root.addView(stripeRow);

        // ---- เนื้อหา ----
        content = new FrameLayout(this);
        root.addView(content, lp(-1, 0, 1));
        pages.add(buildConnect());
        pages.add(buildData());
        pages.add(buildFaults());
        pages.add(buildInfo());
        pages.add(buildMore());
        for (View p : pages) content.addView(p);

        // ---- เมนูล่าง ----
        LinearLayout nav = row();
        nav.setBackgroundColor(NAV);
        View navLine = new View(this);
        navLine.setBackgroundColor(LINE);
        root.addView(navLine, lp(-1, 1, 0));
        String[] names = {"เชื่อมต่อ", "Data สด", "Fault", "ข้อมูลกล่อง", "ตั้งค่า"};
        int[] icons = {NavIcon.CONNECT, NavIcon.DATA, NavIcon.FAULT, NavIcon.INFO, NavIcon.MORE};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout it = col();
            it.setGravity(Gravity.CENTER_HORIZONTAL);
            it.setPadding(0, 0, 0, dp(8));
            View ind = new View(this);
            ind.setBackground(new Gfx.TriBar());
            it.addView(ind, lpm(dp(26) / 1, dp(3), 0, 0, 0, 0, 0));
            ((LinearLayout.LayoutParams) ind.getLayoutParams()).width = dp(30);
            NavIcon ic = new NavIcon(this, icons[i]);
            it.addView(ic, lpm(dp(22), dp(22), 0, 0, 7, 0, 2));
            ((LinearLayout.LayoutParams) ic.getLayoutParams()).width = dp(22);
            ((LinearLayout.LayoutParams) ic.getLayoutParams()).height = dp(22);
            TextView tl = label(names[i], 10.5f, MUTED);
            tl.setSingleLine(true);
            it.addView(tl);
            it.setOnClickListener(v -> showPage(idx));
            nav.addView(it, lp(0, -2, 1));
            navItems.add(new View[]{ind, ic, tl});
        }
        root.addView(nav);
        showPage(P_CONNECT);
        return root;
    }

    private void showPage(int idx) {
        page = idx;
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
            View[] n = navItems.get(i);
            n[0].setVisibility(i == idx ? View.VISIBLE : View.INVISIBLE);
            ((NavIcon) n[1]).setColor(i == idx ? TEXT : MUTED);
            ((TextView) n[2]).setTextColor(i == idx ? TEXT : MUTED);
        }
        updateHeader();
        DiagSession s = session;
        if (s != null && idx == P_FAULT && !faultsLoaded) readFaults();
        if (s != null && idx == P_INFO && !infoLoaded) readInfo();
    }

    private void updateHeader() {
        DiagSession s = session;
        String[] titles = {"BMW DIAG", "BMW DIAG", "Fault", "ข้อมูลกล่อง", "ตั้งค่า"};
        hTitle.setText(titles[page]);
        if (s == null) hSub.setText("BETA TEST · BY TMS");
        else hSub.setText(shortName(s.ecu()).toUpperCase(Locale.ROOT) + (s.isSgbd() ? " · " + s.sgbd().variantName : ""));
        if (s == null) setChip("ไม่ได้เชื่อมต่อ", 0xFF555A63, MUTED);
        else if (page == P_DATA && polling && !rate.isEmpty()) setChip(rate, OK, TEXT);
        else setChip("เชื่อมต่อแล้ว", OK, TEXT);
    }

    private void setChip(String t, int dot, int color) {
        chipText.setText(t);
        chipText.setTextColor(color);
        chipDot.setBackground(round(dot, 0, 5));
    }

    private void setStatus(String s, int dot) {
        runOnUiThread(() -> setChip(s, dot, dot == M_RED ? M_RED : dot == WARN ? WARN : TEXT));
    }

    private static String shortName(EcuDef e) {
        String n = e.name;
        int i = n.indexOf(" — ");
        if (i > 0) n = n.substring(0, i);
        int k = n.indexOf(" (");
        if (k > 0 && n.startsWith("เกียร์")) n = n.substring(0, k);
        return n.replaceFirst("^DME ", "").replaceFirst("^เกียร์ ", "");
    }

    private static String subName(EcuDef e) {
        String type = e.name.startsWith("เกียร์") ? "เกียร์" : e.name.startsWith("DME") ? "DME" : "";
        String rest = "";
        int i = e.name.indexOf(" — ");
        if (i > 0) rest = e.name.substring(i + 3);
        else {
            int k = e.name.indexOf(" (");
            if (k > 0) rest = e.name.substring(k + 2).replace(")", "");
        }
        return type + (rest.isEmpty() ? "" : " · " + rest);
    }

    // ===================== หน้าเชื่อมต่อ =====================
    private View buildConnect() {
        LinearLayout l = col();
        l.setPadding(dp(14), 0, dp(14), dp(16));
        l.addView(section("ช่องทางเชื่อมต่อ"));
        String[][] links = {{"สาย K+DCAN (USB)", "FTDI · E46 / E39 / E83 (K-line)"}, {"ELM327 Bluetooth", "D-CAN · E90 / E87"}};
        for (int i = 0; i < links.length; i++) {
            final int idx = i;
            LinearLayout c = row();
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout tx = col();
            tx.addView(bold(links[i][0], 15, TEXT));
            tx.addView(label(links[i][1], 12, MUTED));
            c.addView(tx, lp(0, -2, 1));
            View rad = new View(this);
            c.addView(rad, lpm(dp(20), dp(20), 0, 0, 0, 0, 0));
            ((LinearLayout.LayoutParams) rad.getLayoutParams()).width = dp(20);
            c.setTag(rad);
            c.setOnClickListener(v -> selectLink(idx));
            l.addView(c, lpm(-1, -2, 0, 0, 0, 0, 8));
            linkCards.add(c);
        }
        btSpin = spinner(new ArrayList<String>());
        l.addView(btSpin, lpm(-1, -2, 0, 0, 0, 0, 4));

        l.addView(section("เลือกกล่อง"));
        ecuGrid = col();
        l.addView(ecuGrid);
        ecuNotes = label("", 12, MUTED);
        ecuNotes.setPadding(dp(4), dp(8), dp(4), 0);
        l.addView(ecuNotes);
        buildEcuCards();

        connectBtn = bold("เชื่อมต่อ", 16, Color.WHITE);
        connectBtn.setGravity(Gravity.CENTER);
        connectBtn.setPadding(0, dp(16), 0, dp(16));
        setConnectLook(false);
        connectBtn.setOnClickListener(v -> { if (session == null) connect(); else disconnect(); });
        l.addView(connectBtn, lpm(-1, -2, 0, 0, 16, 0, 0));
        selectLink(linkIdx);
        return scroll(l);
    }

    private void setConnectLook(boolean connected) {
        GradientDrawable base = round(connected ? M_RED : M_BLUE, 0, 14);
        Drawable d = connected ? base : new LayerDrawable(new Drawable[]{base, new Gfx.StripeDrawable(0.16f, 0.95f, 0.5f)});
        connectBtn.setBackground(d);
        connectBtn.setText(connected ? "ตัดการเชื่อมต่อ" : "เชื่อมต่อ");
    }

    private void selectLink(int idx) {
        linkIdx = idx;
        prefs.edit().putInt("link", idx).apply();
        for (int i = 0; i < linkCards.size(); i++) {
            boolean on = i == idx;
            LinearLayout c = linkCards.get(i);
            c.setBackground(round(on ? 0xFF0F1E33 : CARD, on ? M_BLUE : LINE, 14));
            View rad = (View) c.getTag();
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(on ? 0xFF0F1E33 : Color.TRANSPARENT);
            g.setStroke(dp(on ? 5 : 2), on ? M_LIGHT : 0xFF444A54);
            rad.setBackground(g);
        }
        if (btSpin != null) btSpin.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        if (idx == 1) refreshBtDevices();
    }

    private void buildEcuCards() {
        ecuGrid.removeAllViews();
        ecuCards.clear();
        String id = prefs.getString("ecu", ecus.isEmpty() ? "" : ecus.get(0).id);
        ecuIdx = 0;
        for (int i = 0; i < ecus.size(); i++) if (ecus.get(i).id.equals(id)) ecuIdx = i;
        LinearLayout r = null;
        for (int i = 0; i < ecus.size(); i++) {
            if (i % 2 == 0) {
                r = row();
                ecuGrid.addView(r, lpm(-1, -2, 0, 0, 0, 0, 8));
            }
            final int idx = i;
            LinearLayout c = col();
            c.setPadding(dp(12), dp(10), dp(12), dp(10));
            c.addView(bold(shortName(ecus.get(i)), 15.5f, TEXT));
            TextView sub = label(subName(ecus.get(i)), 11, MUTED);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.addView(sub);
            c.setOnClickListener(v -> selectEcu(idx));
            r.addView(c, lpm(0, -1, 1, 0, 0, i % 2 == 0 ? 4 : 0, 0));
            if (i % 2 == 1) ((LinearLayout.LayoutParams) c.getLayoutParams()).leftMargin = dp(4);
            ecuCards.add(c);
        }
        if (ecus.size() % 2 == 1 && r != null) r.addView(new View(this), lpm(0, 1, 1, 4, 0, 0, 0));
        selectEcu(ecuIdx);
    }

    private void selectEcu(int idx) {
        if (ecus.isEmpty()) return;
        if (session != null && idx != ecuIdx) { toast("ตัดการเชื่อมต่อก่อนเปลี่ยนกล่อง"); return; }
        ecuIdx = idx;
        prefs.edit().putString("ecu", ecus.get(idx).id).apply();
        for (int i = 0; i < ecuCards.size(); i++) {
            boolean on = i == idx;
            ecuCards.get(i).setBackground(round(on ? 0xFF0F1E33 : CARD, on ? M_LIGHT : LINE, 12));
        }
        ecuNotes.setText(ecus.get(idx).notes);
    }

    // ===================== หน้า Data สด =====================
    private View buildData() {
        LinearLayout l = col();
        l.setPadding(dp(12), dp(4), dp(12), dp(12));

        heroCard = col();
        heroCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF14181F, 0xFF0B0D10});
        hg.setCornerRadius(dp(16));
        hg.setStroke(dp(1), LINE);
        heroCard.setBackground(new LayerDrawable(new Drawable[]{hg, new Gfx.StripeDrawable(0.30f, 0.16f, 1.0f)}));
        heroName = label("รอบเครื่อง", 12.5f, MUTED);
        heroCard.addView(heroName);
        LinearLayout vr = row();
        vr.setGravity(Gravity.BOTTOM);
        heroVal = bold("--", 50, TEXT);
        heroVal.setIncludeFontPadding(false);
        heroUnit = label("", 16, MUTED);
        heroUnit.setPadding(dp(8), 0, 0, dp(6));
        vr.addView(heroVal);
        vr.addView(heroUnit);
        heroCard.addView(vr);
        View[] hm = new View[2];
        heroBar = meter(8, hm);
        heroFill = hm[0];
        heroEmpty = hm[1];
        heroCard.addView(heroBar, lpm(-1, dp(8), 0, 0, 10, 0, 0));
        ((LinearLayout.LayoutParams) heroBar.getLayoutParams()).height = dp(8);
        heroTicks = row();
        heroCard.addView(heroTicks, lpm(-1, -2, 0, 0, 4, 0, 0));
        l.addView(heroCard);

        tileGrid = col();
        l.addView(tileGrid, lpm(-1, -2, 0, 0, 8, 0, 0));

        dashInfo = label("เชื่อมต่อแล้วค่าจะขึ้นอัตโนมัติ", 11, DIM);
        dashInfo.setGravity(Gravity.CENTER);
        dashInfo.setPadding(0, dp(6), 0, 0);
        l.addView(dashInfo);

        LinearLayout ctl = row();
        pollBtn = btn("▶ เริ่มอ่านค่า", 0, v -> { if (polling) stopPolling(); else startPolling(); });
        chooseBtn = btn("☰ เลือกค่า", 2, v -> choosePids());
        ctl.addView(pollBtn, lpm(0, -2, 1, 0, 0, 4, 0));
        ctl.addView(chooseBtn, lpm(0, -2, 1, 4, 0, 0, 0));
        l.addView(ctl, lpm(-1, -2, 0, 0, 10, 0, 0));
        return scroll(l);
    }

    /** ช่วงค่าสำหรับแถบระดับ {min, max} ตามหน่วย/ชื่อ (null = ใช้ค่าสูงสุดที่เคยเห็น) */
    private static double[] range(DiagSession.Reading r) {
        String u = r.unit == null ? "" : r.unit.trim().toLowerCase(Locale.ROOT);
        String n = r.name;
        if (u.equals("rpm") || u.equals("1/min")) return new double[]{0, 7000};
        if (u.equals("°c")) return n.contains("น้ำมัน") ? new double[]{-20, 150} : new double[]{-20, 130};
        if (u.equals("v")) return n.contains("แบต") || n.contains("KL15") ? new double[]{9, 16} : new double[]{0, 5};
        if (u.equals("kg/h")) return new double[]{0, 600};
        if (u.equals("km/h")) return new double[]{0, 260};
        if (u.equals("ms")) return new double[]{0, 20};
        if (u.equals("%") || u.startsWith("%")) return new double[]{0, 100};
        if (u.equals("°")) return new double[]{-20, 60};
        return null;
    }

    /** สีแถบด้านข้างของการ์ด */
    private static int accentFor(DiagSession.Reading r) {
        String u = r.unit == null ? "" : r.unit.trim().toLowerCase(Locale.ROOT);
        if (r.value != null) {
            if (u.equals("°c") && r.name.contains("หล่อเย็น") && r.value > 110) return M_RED;
            if (u.equals("v") && (r.name.contains("แบต")) && r.value < 11.8) return M_RED;
        }
        if (r.name.contains("น็อค") || r.name.contains("Misfire")) return M_RED;
        if (u.equals("°c")) return WARN;
        if (u.equals("%") || u.equals("kg/h") || u.equals("°")) return M_BLUE;
        return M_LIGHT;
    }

    private double frac(DiagSession.Reading r) {
        if (r.value == null) return 0;
        double[] rg = range(r);
        if (rg == null) {
            double m = Math.max(Math.abs(r.value), maxSeen.containsKey(r.name) ? maxSeen.get(r.name) : 0);
            maxSeen.put(r.name, m);
            return m == 0 ? 0 : Math.abs(r.value) / m;
        }
        return (r.value - rg[0]) / (rg[1] - rg[0]);
    }

    private static String bigText(DiagSession.Reading r) {
        if (r.value != null && r.unit != null && r.unit.equalsIgnoreCase("rpm") && Math.abs(r.value) < 1e7)
            return String.format(Locale.US, "%,d", Math.round(r.value));
        return r.text;
    }

    private void updateTiles(List<DiagSession.Reading> r) {
        if (r.isEmpty()) {
            dashInfo.setText("ยังไม่ได้เลือกค่า หรือกล่องนี้ไม่มี live data — กด ☰ เลือกค่า");
            return;
        }
        // ค่าแรก = ตัวใหญ่ด้านบน
        DiagSession.Reading h = r.get(0);
        heroName.setText(h.name);
        heroVal.setText(bigText(h));
        heroVal.setTextColor(h.value == null ? MUTED : TEXT);
        heroUnit.setText(h.unit);
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{M_LIGHT, M_BLUE, M_RED});
        grad.setCornerRadius(dp(4));
        setMeter(heroFill, heroEmpty, frac(h), grad);
        double[] rg = range(h);
        String tickKey = rg == null ? "" : rg[0] + "_" + rg[1];
        if (!tickKey.equals(heroTicks.getTag())) {
            heroTicks.setTag(tickKey);
            heroTicks.removeAllViews();
            if (rg != null) {
                boolean rpm = h.unit.equalsIgnoreCase("rpm");
                for (int i = 0; i <= 4; i++) {
                    double v = rg[0] + (rg[1] - rg[0]) * i / 4.0;
                    String s = rpm ? String.format(Locale.US, "%.1f", v / 1000).replace(".0", "") : String.format(Locale.US, "%.0f", v);
                    TextView t = label(s, 10, i == 4 ? M_RED : DIM);
                    t.setGravity(i == 0 ? Gravity.START : i == 4 ? Gravity.END : Gravity.CENTER);
                    heroTicks.addView(t, lp(0, -2, 1));
                }
            }
        }

        // การ์ดที่เหลือ
        List<DiagSession.Reading> rest = r.subList(1, r.size());
        boolean same = tiles.size() == rest.size();
        for (int i = 0; same && i < rest.size(); i++) same = rest.get(i).name.equals(tiles.get(i).name);
        if (!same) {
            tileGrid.removeAllViews();
            tiles.clear();
            int cols = getResources().getConfiguration().screenWidthDp > 600 ? 4 : 2;
            LinearLayout rw = null;
            for (int i = 0; i < rest.size(); i++) {
                if (i % cols == 0) {
                    rw = row();
                    tileGrid.addView(rw, lpm(-1, -2, 0, 0, 0, 0, 8));
                }
                Tile t = new Tile();
                t.name = rest.get(i).name;
                LinearLayout c = row();
                c.setBackground(round(CARD, LINE, 14));
                t.accent = new View(this);
                c.addView(t.accent, lpm(dp(3), -1, 0, 0, 10, 0, 10));
                ((LinearLayout.LayoutParams) t.accent.getLayoutParams()).width = dp(3);
                LinearLayout body = col();
                body.setPadding(dp(10), dp(9), dp(12), dp(10));
                t.n = label(t.name, 11.5f, MUTED);
                t.n.setSingleLine(true);
                t.n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout vr = row();
                vr.setGravity(Gravity.BOTTOM);
                t.v = bold("--", 25, TEXT);
                t.v.setSingleLine(true);
                t.u = label("", 11.5f, MUTED);
                t.u.setPadding(dp(4), 0, 0, dp(4));
                vr.addView(t.v);
                vr.addView(t.u);
                View[] mm = new View[2];
                LinearLayout m = meter(3, mm);
                t.fill = mm[0];
                t.empty = mm[1];
                body.addView(t.n);
                body.addView(vr);
                body.addView(m, lpm(-1, dp(3), 0, 0, 6, 0, 0));
                ((LinearLayout.LayoutParams) m.getLayoutParams()).height = dp(3);
                c.addView(body, lp(0, -2, 1));
                rw.addView(c, lpm(0, -2, 1, i % cols == 0 ? 0 : 4, 0, i % cols == cols - 1 ? 0 : 4, 0));
                tiles.add(t);
            }
            if (rw != null) for (int k = rest.size() % cols; k > 0 && k < cols; k++) rw.addView(new View(this), lpm(0, 1, 1, 4, 0, 0, 0));
        }
        for (int i = 0; i < rest.size(); i++) {
            DiagSession.Reading x = rest.get(i);
            Tile t = tiles.get(i);
            t.v.setText(bigText(x));
            t.v.setTextSize(x.text.length() > 9 ? 16 : 25);
            t.v.setTextColor(x.value == null ? MUTED : TEXT);
            t.u.setText(x.unit);
            int ac = accentFor(x);
            t.accent.setBackground(round(ac, 0, 2));
            setMeter(t.fill, t.empty, frac(x), round(ac, 0, 2));
        }
    }

    // ===================== หน้า Fault =====================
    private View buildFaults() {
        LinearLayout l = col();
        l.setPadding(dp(12), dp(4), dp(12), dp(12));
        LinearLayout sum = row();
        LinearLayout a = card(), b = card();
        fCount = bold("–", 30, M_RED);
        a.addView(fCount);
        a.addView(label("โค้ดที่พบ", 12, MUTED));
        fCurrent = bold("–", 30, WARN);
        b.addView(fCurrent);
        b.addView(label("เสียอยู่ตอนนี้", 12, MUTED));
        sum.addView(a, lpm(0, -2, 1, 0, 0, 4, 0));
        sum.addView(b, lpm(0, -2, 1, 4, 0, 0, 0));
        l.addView(sum);
        fMsg = label("เชื่อมต่อแล้วกด อ่านโค้ด", 13, MUTED);
        fMsg.setPadding(dp(4), dp(10), dp(4), dp(2));
        l.addView(fMsg);
        fList = col();
        l.addView(fList);
        LinearLayout ctl = row();
        ctl.addView(btn("↻ อ่านโค้ด", 0, v -> readFaults()), lpm(0, -2, 1, 0, 0, 4, 0));
        ctl.addView(btn("ลบโค้ด", 1, v -> new AlertDialog.Builder(this)
                .setTitle("ลบโค้ด Fault?")
                .setMessage("จะลบความจำ fault ของกล่องที่เชื่อมต่ออยู่ (ควรอ่าน/จดโค้ดไว้ก่อน)")
                .setPositiveButton("ลบ", (d, w) -> clearFaults())
                .setNegativeButton("ยกเลิก", null).show()), lpm(0, -2, 1, 4, 0, 0, 0));
        l.addView(ctl, lpm(-1, -2, 0, 0, 12, 0, 0));
        return scroll(l);
    }

    /** 1 = เสียอยู่ตอนนี้, 0 = เคยเกิด, -1 = ไม่ทราบ */
    static int faultState(String detail) {
        String d = detail.toLowerCase(Locale.ROOT);
        if (d.contains("หายแล้ว") || d.contains("ไม่มีอยู่ขณะนี้") || d.contains("เป็นพักๆ") || d.contains("ครั้งคราว")
                || d.contains("ไม่มีผล") || d.contains("nicht vorhanden") || d.contains("sporadisch")) return 0;
        if (d.contains("อยู่ตอนนี้") || d.contains("มีอยู่ขณะนี้") || d.contains("กำลังมีผล") || d.contains("ถาวร")
                || d.contains("vorhanden") || d.contains("present")) return 1;
        return -1;
    }

    private void showFaults(List<Dtc.Code> codes, String err) {
        fList.removeAllViews();
        if (err != null) {
            fMsg.setText(err);
            fMsg.setTextColor(M_RED);
            return;
        }
        int real = 0, cur = 0;
        for (Dtc.Code c : codes) {
            if (c.code.equals("✓") || c.code.equals("—")) continue;
            real++;
            String title = c.text, detail = "";
            int nl = c.text.indexOf('\n');
            if (nl >= 0) { title = c.text.substring(0, nl).trim(); detail = c.text.substring(nl + 1).trim(); }
            int st = faultState(detail);
            if (st == 1) cur++;
            LinearLayout cd = card();
            LinearLayout top = row();
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView code = bold(c.code, 13, st == 0 ? WARN : 0xFFFF6B73);
            code.setPadding(dp(8), dp(3), dp(8), dp(3));
            code.setBackground(round(st == 0 ? 0xFF2A2110 : 0xFF2A1215, 0, 6));
            top.addView(code);
            TextView stt = label(st == 1 ? "● เสียอยู่ตอนนี้" : st == 0 ? "○ เคยเกิด" : "", 11, st == 1 ? 0xFFFF6B73 : MUTED);
            stt.setGravity(Gravity.END);
            top.addView(stt, lp(0, -2, 1));
            cd.addView(top);
            TextView tt = label(title.isEmpty() ? "(ไม่มีคำอธิบาย)" : title, 14.5f, TEXT);
            tt.setPadding(0, dp(8), 0, 0);
            tt.setTextIsSelectable(true);
            cd.addView(tt);
            if (!detail.isEmpty()) {
                TextView dt = label(detail.replaceAll("\n\\s+", "\n"), 12, MUTED);
                dt.setPadding(0, dp(6), 0, 0);
                dt.setLineSpacing(0, 1.15f);
                cd.addView(dt);
            }
            fList.addView(cd, lpm(-1, -2, 0, 0, 8, 0, 0));
        }
        fCount.setText(String.valueOf(real));
        fCount.setTextColor(real == 0 ? OK : M_RED);
        fCurrent.setText(String.valueOf(cur));
        fMsg.setText(real == 0 ? "✓ ไม่พบโค้ด fault" : "");
        fMsg.setTextColor(real == 0 ? OK : MUTED);
        fMsg.setVisibility(real == 0 ? View.VISIBLE : View.GONE);
    }

    // ===================== หน้าข้อมูลกล่อง =====================
    private View buildInfo() {
        LinearLayout l = col();
        l.setPadding(dp(12), dp(4), dp(12), dp(12));
        LinearLayout vin = col();
        vin.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable vg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0F2440, 0xFF0B0F16});
        vg.setCornerRadius(dp(16));
        vg.setStroke(dp(1), 0xFF1D3B63);
        vin.setBackground(vg);
        LinearLayout vt = row();
        vt.setGravity(Gravity.CENTER_VERTICAL);
        vt.addView(label("VIN (เลขตัวถัง)", 12, 0xFF8FB8FF), lp(0, -2, 1));
        vt.addView(triLine(48, 3));
        vin.addView(vt);
        vinText = bold("—", 22, TEXT);
        vinText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        vinText.setLetterSpacing(0.08f);
        vinText.setPadding(0, dp(6), 0, 0);
        vinText.setTextIsSelectable(true);
        vin.addView(vinText);
        l.addView(vin);
        infoMsg = label("เชื่อมต่อแล้วกด อ่านข้อมูลกล่อง", 13, MUTED);
        infoMsg.setPadding(dp(4), dp(10), dp(4), 0);
        l.addView(infoMsg);
        infoBox = col();
        infoBox.setBackground(round(CARD, LINE, 14));
        infoBox.setVisibility(View.GONE);
        l.addView(infoBox, lpm(-1, -2, 0, 0, 8, 0, 0));
        l.addView(btn("↻ อ่านข้อมูลกล่อง", 0, v -> readInfo()), lpm(-1, -2, 0, 0, 12, 0, 0));
        return scroll(l);
    }

    private void showInfo(List<String> lines, String err) {
        infoBox.removeAllViews();
        if (err != null) {
            infoMsg.setVisibility(View.VISIBLE);
            infoMsg.setText(err);
            infoMsg.setTextColor(M_RED);
            infoBox.setVisibility(View.GONE);
            return;
        }
        String vin = null;
        int n = 0;
        for (String line : lines) {
            int i = line.indexOf(": ");
            String k = i > 0 ? line.substring(0, i).trim() : "";
            String v = i > 0 ? line.substring(i + 2).trim() : line.trim();
            if (k.toUpperCase(Locale.ROOT).startsWith("VIN")) { if (vin == null) vin = v; continue; }
            if (k.isEmpty() && v.isEmpty()) continue;
            LinearLayout r = row();
            r.setPadding(dp(14), dp(11), dp(14), dp(11));
            if (n > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(LINE);
                infoBox.addView(sep, lp(-1, 1, 0));
            }
            if (!k.isEmpty()) r.addView(label(k, 13.5f, MUTED), lpm(0, -2, 1, 0, 0, 10, 0));
            TextView vv = label(v, 13.5f, TEXT);
            vv.setGravity(k.isEmpty() ? Gravity.START : Gravity.END);
            vv.setTextIsSelectable(true);
            r.addView(vv, lp(0, -2, k.isEmpty() ? 1 : 1.2f));
            infoBox.addView(r);
            n++;
        }
        vinText.setText(vin == null || vin.isEmpty() ? "อ่านไม่ได้" : vin);
        vinText.setTextColor(vin == null || vin.equals("อ่านไม่ได้") ? MUTED : TEXT);
        infoBox.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        infoMsg.setVisibility(View.GONE);
    }

    // ===================== หน้าตั้งค่า =====================
    private View buildMore() {
        LinearLayout l = col();
        l.setPadding(dp(12), dp(4), dp(12), dp(16));

        // เกี่ยวกับแอป + โลโก้
        LinearLayout about = card();
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        View emb = new View(this) {
            @Override protected void onDraw(Canvas c) {
                Gfx.drawEmblem(c, 0, 0, Math.min(getWidth(), getHeight()), 0.62f);
            }
        };
        about.addView(emb, lpm(dp(84), dp(84), 0, 0, 4, 0, 6));
        ((LinearLayout.LayoutParams) emb.getLayoutParams()).width = dp(84);
        ((LinearLayout.LayoutParams) emb.getLayoutParams()).height = dp(84);
        TextView an = label("BMW DIAG", 20, TEXT);
        an.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
        about.addView(an);
        about.addView(triLine(70, 3));
        TextView by = label("BY TMS", 11, MUTED);
        by.setLetterSpacing(0.25f);
        by.setPadding(0, dp(4), 0, dp(6));
        about.addView(by);
        TextView ad = label(VERSION + " • DME MS42 / MS43 / ME9.2 (E46) / MS45 / MEV9.2 (E90) • เกียร์ GS20 / GS8.60.4\n"
                + "ตัวรัน SGBD พอร์ตจาก EdiabasLib (GPLv3)", 11.5f, MUTED);
        ad.setGravity(Gravity.CENTER);
        about.addView(ad);
        l.addView(about, lpm(-1, -2, 0, 0, 4, 0, 8));

        LinearLayout c = card();
        c.addView(bold("สาย K+DCAN (USB)", 15, TEXT));
        c.addView(label("ใช้ได้ทุกกล่องในแอป — แอปตั้งความเร็ว/โปรโตคอลตาม SGBD ของแต่ละกล่องเอง และตรวจ echo อัตโนมัติ\n"
                + "• E46 / E39 / E83 (MS42, MS43, ME9.2, MS45, GS20, GS8.60.4): สวิตช์สายโหมด K-line (ขา 7+8)\n"
                + "• E90 / E87 (MEV9.2): สวิตช์สายโหมด D-CAN", 12, MUTED));
        cbFast = check("โหมดเร็ว (ลดเวลาพักระหว่างคำสั่ง — ถ้ากล่องไม่ตอบจะช้าลงเอง)", "fast", true);
        c.addView(cbFast);
        TextView adv = bold("ขั้นสูง: สายที่สลับโหมดด้วยขา DTR/RTS", 12.5f, MUTED);
        adv.setPadding(0, dp(10), 0, 0);
        c.addView(adv);
        cbDtr = check("DTR = ON", "dtr", true);
        cbRts = check("RTS = ON", "rts", false);
        c.addView(cbDtr);
        c.addView(cbRts);
        TextView it = bold("OBD2 init (USB)", 15, TEXT);
        it.setPadding(0, dp(12), 0, dp(4));
        c.addView(it);
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
        LinearLayout nums = row();
        LinearLayout n1 = col(), n2 = col();
        n1.addView(label("รอบการอ่าน (ms)", 12.5f, TEXT));
        intervalIn = number(prefs.getInt("interval", 0));
        n1.addView(intervalIn);
        n1.addView(label("0 = เร็วที่สุด", 11, MUTED));
        n2.addView(label("P3 OBD/KWP (ms)", 12.5f, TEXT));
        p3In = number(prefs.getInt("p3", 25));
        n2.addView(p3In);
        n2.addView(label("มาตรฐาน 55 • ถ้าค่าขึ้น ERR ให้เพิ่ม", 11, MUTED));
        nums.addView(n1, lpm(0, -2, 1, 0, 0, 6, 0));
        nums.addView(n2, lpm(0, -2, 1, 6, 0, 0, 0));
        nums.setPadding(0, dp(12), 0, 0);
        c.addView(nums);
        l.addView(c, lpm(-1, -2, 0, 0, 0, 0, 8));

        LinearLayout cs = card();
        cs.addView(bold("SGBD (ไฟล์ .prg)", 15, TEXT));
        cs.addView(label("มี SGBD ภาษาไทยฝังในแอปแล้ว — ถ้าต้องการเวอร์ชันใหม่กว่า เลือกโฟลเดอร์ Ecu ในมือถือ (ไฟล์ในโฟลเดอร์จะถูกใช้ก่อน)", 12, MUTED));
        sgbdLabel = label(sgbdStatus(), 12.5f, OK);
        sgbdLabel.setPadding(0, dp(6), 0, dp(8));
        cs.addView(sgbdLabel);
        LinearLayout sb = row();
        sb.addView(btn("เลือกโฟลเดอร์", 2, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_SGBD);
        }), lpm(0, -2, 1, 0, 0, 4, 0));
        sb.addView(btn("ใช้ตัวที่ฝังในแอป", 2, v -> {
            prefs.edit().remove("sgbdTree").apply();
            sgbdFiles = null;
            sgbdLabel.setText(sgbdStatus());
        }), lpm(0, -2, 1, 4, 0, 0, 0));
        cs.addView(sb);
        l.addView(cs, lpm(-1, -2, 0, 0, 0, 0, 8));

        LinearLayout c2 = card();
        c2.addView(bold("นิยามกล่อง (ecus.json)", 15, TEXT));
        c2.addView(label("สำหรับผู้ใช้ขั้นสูง: แก้รายชื่อกล่อง/คำสั่ง แล้ว import", 12, MUTED));
        LinearLayout bar = row();
        bar.setPadding(0, dp(8), 0, 0);
        bar.addView(btn("Import", 2, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        }), lpm(0, -2, 1, 0, 0, 4, 0));
        bar.addView(btn("Export", 2, v -> exportDefinitions()), lpm(0, -2, 1, 4, 0, 4, 0));
        bar.addView(btn("ค่าเริ่มต้น", 2, v -> {
            new File(getFilesDir(), "ecus.json").delete();
            loadDefinitions();
            buildEcuCards();
            toast("ใช้นิยามเริ่มต้นแล้ว");
        }), lpm(0, -2, 1, 4, 0, 0, 0));
        c2.addView(bar);
        l.addView(c2, lpm(-1, -2, 0, 0, 0, 0, 8));

        LinearLayout c3 = card();
        c3.addView(bold("ข้อควรระวัง", 15, WARN));
        c3.addView(label("• ใช้ตอนรถจอดนิ่ง บิดกุญแจ ON หรือเครื่องติด\n• แอปนี้อ่านข้อมูล / ลบโค้ดเท่านั้น ไม่เขียนแฟลช", 12.5f, MUTED));
        l.addView(c3);
        return scroll(l);
    }

    private EditText number(int v) {
        EditText e = new EditText(this);
        e.setTextColor(TEXT);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(v));
        return e;
    }

    private CheckBox check(String s, String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(s);
        cb.setTextColor(TEXT);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean(key, c).apply());
        return cb;
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
            buildEcuCards();
            toast("Import แล้ว " + test.size() + " กล่อง");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("JSON ไม่ถูกต้อง").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    // ===================== เชื่อมต่อ =====================
    private DiagSession.Options options() {
        DiagSession.Options o = new DiagSession.Options();
        o.echo = true; // ตรวจ echo อัตโนมัติใน KLine
        o.fast = cbFast.isChecked();
        o.dtr = cbDtr.isChecked();
        o.rts = cbRts.isChecked();
        o.obdInit = DiagSession.ObdInit.values()[initSpin.getSelectedItemPosition()];
        o.trace = false;
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

    /** PID ที่เลือกไว้ (โหมด OBD สำรอง) */
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
        if (t == null) return "✓ ใช้ SGBD ภาษาไทยที่ฝังในแอป";
        String name = Uri.decode(t);
        int i = name.lastIndexOf(':');
        return "✓ ใช้ SGBD จาก: " + (i >= 0 ? name.substring(i + 1) : name);
    }

    private static String joinKeys(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String k : l) { if (sb.length() > 0) sb.append(','); sb.append(k); }
        return sb.toString();
    }

    /** เลือกค่า live data ทีละตัว (ชื่อภาษาไทย) ในโหมด SGBD — ค่าแรกที่เลือกจะแสดงตัวใหญ่ */
    private void chooseSgbdItems(DiagSession s) {
        final List<com.tms.bmwediag.sgbd.SgbdRunner.LiveItem> cat = s.sgbd().liveCatalog();
        if (cat.isEmpty()) { toast("SGBD นี้ไม่มีค่า live data ให้เลือก"); return; }
        final String[] arr = new String[cat.size()];
        final boolean[] checked = new boolean[cat.size()];
        final List<String> cur = s.sgbdLiveItems();
        for (int i = 0; i < arr.length; i++) {
            com.tms.bmwediag.sgbd.SgbdRunner.LiveItem it = cat.get(i);
            String code = com.tms.bmwediag.sgbd.ThaiLabels.base(it.result);
            arr[i] = it.label.equals(code) ? it.label : it.label + "   (" + code + ")";
            for (String k : cur) if (k.equalsIgnoreCase(it.key())) checked[i] = true;
        }
        final String pref = "sgbdItems_" + s.sgbd().variantName;
        new AlertDialog.Builder(this)
                .setTitle("เลือกค่าที่จะอ่าน — ยิ่งน้อยยิ่งเร็ว")
                .setMultiChoiceItems(arr, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("ตกลง", (d, w) -> {
                    // คงลำดับเดิมของค่าที่เลือกไว้ก่อน (ค่าแรก = ตัวใหญ่) แล้วต่อด้วยค่าใหม่
                    List<String> sel = new ArrayList<>();
                    for (String k : cur)
                        for (int i = 0; i < arr.length; i++) if (checked[i] && cat.get(i).key().equalsIgnoreCase(k) && !sel.contains(cat.get(i).key())) sel.add(cat.get(i).key());
                    for (int i = 0; i < arr.length; i++) if (checked[i] && !sel.contains(cat.get(i).key())) sel.add(cat.get(i).key());
                    if (sel.isEmpty()) { toast("ยังไม่ได้เลือกค่า"); return; }
                    prefs.edit().putString(pref, joinKeys(sel)).apply();
                    worker.execute(() -> s.setSgbdLiveItems(sel));
                    chooseBtn.setText("☰ เลือกค่า (" + sel.size() + ")");
                })
                .setNeutralButton("ค่าแนะนำ", (d, w) -> {
                    prefs.edit().remove(pref).apply();
                    worker.execute(() -> s.setSgbdLiveItems(null));
                    toast("ใช้ค่าแนะนำ");
                })
                .setNegativeButton("ยกเลิก", null).show();
    }

    private void choosePids() {
        DiagSession curSession = session;
        if (curSession == null) { toast("ยังไม่ได้เชื่อมต่อ"); return; }
        if (curSession.isSgbd()) { chooseSgbdItems(curSession); return; }
        List<com.tms.bmwediag.diag.ObdPids.Pid> all = com.tms.bmwediag.diag.ObdPids.ALL;
        CharSequence[] names = new CharSequence[all.size()];
        boolean[] checked = new boolean[all.size()];
        int[] cur = savedPids();
        for (int i = 0; i < all.size(); i++) {
            com.tms.bmwediag.diag.ObdPids.Pid p = all.get(i);
            names[i] = p.name + " (" + p.unit + ")";
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

    private void connect() {
        if (ecus.isEmpty()) return;
        EcuDef ecu = ecus.get(ecuIdx);
        DiagSession.Options o = options();
        synchronized (logBuf) { logBuf.setLength(0); }
        if (linkIdx == 0) {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice dev = null;
            for (UsbDevice d : um.getDeviceList().values()) if (d.getVendorId() == 0x0403) dev = d;
            if (dev == null) { toast("ไม่พบสาย FTDI K+DCAN (ต่อผ่าน OTG แล้วหรือยัง?)"); return; }
            if (!um.hasPermission(dev)) { requestUsbPermission(um, dev); return; }
            final UsbDevice device = dev;
            setStatus("กำลังเชื่อมต่อ...", WARN);
            connectBtn.setText("กำลังเชื่อมต่อ...");
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
            setStatus("กำลังเชื่อมต่อ...", WARN);
            connectBtn.setText("กำลังเชื่อมต่อ...");
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
            String j = prefs.getString("sgbdItems_" + s.sgbd().variantName, null);
            if (j != null && !j.isEmpty()) s.setSgbdLiveItems(java.util.Arrays.asList(j.split(",")));
        }
        final int nItems = s.isSgbd() ? s.sgbdLiveItems().size() : savedPids().length;
        session = s;
        faultsLoaded = false;
        infoLoaded = false;
        runOnUiThread(() -> {
            setConnectLook(true);
            chooseBtn.setText("☰ เลือกค่า (" + nItems + ")");
            dashInfo.setText(s.describe());
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            maxSeen.clear();
            showPage(P_DATA);
            startPolling();
        });
    }

    private String lastLog(int n) {
        synchronized (logBuf) {
            String[] l = logBuf.toString().split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, l.length - n); i < l.length; i++) sb.append(l[i]).append('\n');
            return sb.toString().trim();
        }
    }

    private void onConnectFail(Exception e) {
        log("เชื่อมต่อไม่สำเร็จ: " + e);
        setStatus("เชื่อมต่อไม่สำเร็จ", M_RED);
        runOnUiThread(() -> {
            setConnectLook(false);
            new AlertDialog.Builder(this)
                    .setTitle("เชื่อมต่อไม่สำเร็จ")
                    .setMessage(e.getMessage() + "\n\nตรวจสอบ: กุญแจ ON, เลือกกล่องถูกรุ่น, โหมดสาย (K-line/D-CAN)\n\nรายละเอียด:\n" + lastLog(6))
                    .setPositiveButton("OK", null).show();
        });
    }

    private void disconnect() {
        stopPolling();
        final DiagSession s = session;
        session = null;
        worker.execute(() -> {
            if (s != null) s.close();
            closeBt();
        });
        runOnUiThread(() -> {
            if (connectBtn != null) setConnectLook(false);
            if (hTitle != null) updateHeader();
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
                if (linkIdx == 1) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_PERM);
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
        if (names.isEmpty()) names.add("(ไม่มี ELM327 ที่จับคู่ไว้)");
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(TEXT);
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        btSpin.setAdapter(a);
        btSpin.setVisibility(linkIdx == 1 ? View.VISIBLE : View.GONE);
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
        faultsLoaded = true;
        fMsg.setVisibility(View.VISIBLE);
        fMsg.setText("กำลังอ่านโค้ด...");
        fMsg.setTextColor(MUTED);
        final DiagSession s = session;
        worker.execute(() -> {
            List<Dtc.Code> codes = null;
            String err = null;
            try { codes = s.readFaults(); } catch (Exception e) { err = "อ่านโค้ดไม่สำเร็จ: " + e.getMessage(); }
            final List<Dtc.Code> c = codes;
            final String er = err;
            runOnUiThread(() -> showFaults(c, er));
        });
    }

    private void clearFaults() {
        if (!need()) return;
        fMsg.setVisibility(View.VISIBLE);
        fMsg.setText("กำลังลบโค้ด...");
        fMsg.setTextColor(MUTED);
        final DiagSession s = session;
        worker.execute(() -> {
            String out;
            boolean ok;
            try { out = s.clearFaults(); ok = true; } catch (Exception e) { out = "ลบไม่สำเร็จ: " + e.getMessage(); ok = false; }
            final String o = out;
            final boolean okf = ok;
            runOnUiThread(() -> {
                toast(o);
                if (okf) readFaults();
                else { fMsg.setText(o); fMsg.setTextColor(M_RED); }
            });
        });
    }

    private void readInfo() {
        if (!need()) return;
        infoLoaded = true;
        infoMsg.setVisibility(View.VISIBLE);
        infoMsg.setText("กำลังอ่าน...");
        infoMsg.setTextColor(MUTED);
        final DiagSession s = session;
        worker.execute(() -> {
            List<String> lines = null;
            String err = null;
            try { lines = s.readIdent(); } catch (Exception e) { err = "ผิดพลาด: " + e.getMessage(); }
            final List<String> l = lines;
            final String er = err;
            runOnUiThread(() -> showInfo(l, er));
        });
    }

    // ===================== Live =====================
    private void startPolling() {
        if (!need() || polling) return;
        int iv;
        try { iv = Integer.parseInt(intervalIn.getText().toString()); } catch (Exception e) { iv = 0; }
        iv = Math.max(0, Math.min(iv, 10000));
        prefs.edit().putInt("interval", iv).apply();
        polling = true;
        pollBtn.setText("■ หยุด");
        setBtnKind(pollBtn, 1);
        final int interval = iv;
        final DiagSession s = session;
        worker.execute(new Runnable() {
            long last = System.currentTimeMillis();
            @Override public void run() {
                if (!polling || session != s) return;
                try {
                    List<DiagSession.Reading> r = s.pollLive();
                    long now = System.currentTimeMillis();
                    final String rt = String.format(Locale.US, "%.1f Hz", 1000.0 / Math.max(1, now - last));
                    last = now;
                    runOnUiThread(() -> {
                        if (!polling) return;
                        updateTiles(r);
                        rate = rt;
                        dashInfo.setText(s.describe());
                        if (page == P_DATA) setChip(rate, OK, TEXT);
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
        rate = "";
        if (pollBtn != null) runOnUiThread(() -> {
            pollBtn.setText("▶ เริ่มอ่านค่า");
            setBtnKind(pollBtn, 0);
            if (hTitle != null) updateHeader();
        });
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

    // ===================== log (ภายใน ใช้แสดงตอนเชื่อมต่อไม่สำเร็จ) =====================
    private void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s + "\n";
        synchronized (logBuf) {
            logBuf.append(line);
            if (logBuf.length() > 20000) logBuf.delete(0, logBuf.length() - 12000);
        }
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }
}
