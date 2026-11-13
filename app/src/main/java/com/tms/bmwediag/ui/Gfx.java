package com.tms.bmwediag.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/** กราฟิกของธีม M Sport: แถบ 3 สี, โลโก้, ไอคอนเมนู */
public final class Gfx {
    private Gfx() {}

    // สีธีม
    public static final int BG = 0xFF07080A, CARD = 0xFF121418, CARD2 = 0xFF191C22, LINE = 0xFF262A31,
            TEXT = 0xFFF2F4F7, MUTED = 0xFF8B929E, DIM = 0xFF5B616B,
            M_LIGHT = 0xFF81C4FF, M_BLUE = 0xFF1C69D4, M_RED = 0xFFE7222E,
            OK = 0xFF2BD576, WARN = 0xFFFFB020, NAV = 0xFF0C0E11;

    /** แถบเอียง 3 สี (ฟ้า-น้ำเงิน-แดง) วาดชิดขวา */
    public static final class StripeDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float widthFrac, alpha, skew;

        /** widthFrac = สัดส่วนความกว้างของแถบทั้งหมด, skew = ความเอียง (0 = ตรง) */
        public StripeDrawable(float widthFrac, float alpha, float skew) {
            this.widthFrac = widthFrac; this.alpha = alpha; this.skew = skew;
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float h = b.height();
            float total = b.width() * widthFrac;
            float sw = total / 3f;
            float off = h * skew;
            float x0 = b.right - total - off * 0.5f;
            int[] cols = {M_LIGHT, M_BLUE, M_RED};
            for (int i = 0; i < 3; i++) {
                Path path = new Path();
                float l = x0 + i * sw;
                path.moveTo(l + off, b.top);
                path.lineTo(l + sw + off, b.top);
                path.lineTo(l + sw, b.bottom);
                path.lineTo(l, b.bottom);
                path.close();
                p.setColor(cols[i]);
                p.setAlpha((int) (255 * alpha));
                c.drawPath(path, p);
            }
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** เส้นแถบ 3 สีแนวนอนเต็มความกว้าง */
    public static final class TriBar extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width() / 3f;
            int[] cols = {M_LIGHT, M_BLUE, M_RED};
            for (int i = 0; i < 3; i++) {
                p.setColor(cols[i]);
                c.drawRect(b.left + i * w, b.top, b.left + (i + 1) * w, b.bottom, p);
            }
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** วาดสัญลักษณ์โลโก้ (เกจ 3 สี + เข็ม) ในกรอบสี่เหลี่ยม size×size ที่ (x,y) */
    public static void drawEmblem(Canvas c, float x, float y, float size, float needleFrac) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size * 0.22f;
        // พื้นหลังกรอบมน
        p.setColor(0xFF0E1116);
        c.drawRoundRect(new RectF(x, y, x + size, y + size), r, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.012f);
        p.setColor(0xFF2A2F38);
        c.drawRoundRect(new RectF(x, y, x + size, y + size), r, r, p);
        // วงเกจ 270°
        float cx = x + size / 2f, cy = y + size * 0.54f, gr = size * 0.30f;
        RectF arc = new RectF(cx - gr, cy - gr, cx + gr, cy + gr);
        p.setStrokeWidth(size * 0.075f);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(M_LIGHT);
        c.drawArc(arc, 135, 150, false, p);
        p.setColor(M_BLUE);
        c.drawArc(arc, 285, 70, false, p);
        p.setColor(M_RED);
        c.drawArc(arc, 355, 50, false, p);
        // ขีดสเกล
        p.setStrokeWidth(size * 0.012f);
        p.setColor(0x66FFFFFF);
        for (int i = 0; i <= 6; i++) {
            double a = Math.toRadians(135 + i * 45);
            float r1 = gr - size * 0.075f, r2 = r1 - size * 0.04f;
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
        }
        // เข็ม
        double na = Math.toRadians(135 + 270 * needleFrac);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        float nl = gr * 0.82f, nw = size * 0.022f;
        Path n = new Path();
        n.moveTo(cx + (float) Math.cos(na) * nl, cy + (float) Math.sin(na) * nl);
        n.lineTo(cx + (float) Math.cos(na + Math.PI / 2) * nw, cy + (float) Math.sin(na + Math.PI / 2) * nw);
        n.lineTo(cx + (float) Math.cos(na - Math.PI / 2) * nw, cy + (float) Math.sin(na - Math.PI / 2) * nw);
        n.close();
        c.drawPath(n, p);
        p.setColor(M_RED);
        c.drawCircle(cx, cy, size * 0.045f, p);
        p.setColor(0xFF0E1116);
        c.drawCircle(cx, cy, size * 0.018f, p);
        // ตัวอักษร TMS ใต้เกจ
        p.setColor(MUTED);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size * 0.10f);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setLetterSpacing(0.15f);
        c.drawText("TMS", cx, y + size * 0.90f, p);
    }

    /** หน้าจอเปิดแอป: โลโก้ + BMW DIAG + by TMS (เข็มกวาดขึ้น) */
    public static final class SplashView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long start = System.currentTimeMillis();
        private final String version;

        public SplashView(Context c, String version) {
            super(c);
            this.version = version;
            setBackgroundColor(BG);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float t = Math.min(1f, (System.currentTimeMillis() - start) / 1100f);
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            float size = Math.min(w, h) * 0.42f;
            float x = (w - size) / 2f, y = h * 0.40f - size * 0.75f;
            float needle = ease < 0.7f ? ease / 0.7f * 0.92f : 0.92f - (ease - 0.7f) / 0.3f * 0.30f;
            drawEmblem(c, x, y, size, needle);
            // ชื่อแอป
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(TEXT);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
            p.setTextSize(size * 0.30f);
            p.setLetterSpacing(0.04f);
            p.setAlpha((int) (255 * Math.min(1f, t * 1.6f)));
            float ty = y + size + size * 0.38f;
            c.drawText("BMW DIAG", w / 2f, ty, p);
            // แถบ 3 สี
            float bw = size * 0.75f, bh = size * 0.035f, bx = (w - bw) / 2f, by = ty + size * 0.10f;
            int[] cols = {M_LIGHT, M_BLUE, M_RED};
            for (int i = 0; i < 3; i++) {
                p.setColor(cols[i]);
                p.setAlpha((int) (255 * Math.min(1f, t * 1.6f)));
                c.drawRect(bx + i * bw / 3f, by, bx + (i + 1) * bw / 3f, by + bh, p);
            }
            p.setColor(MUTED);
            p.setAlpha((int) (255 * Math.min(1f, t * 1.6f)));
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            p.setTextSize(size * 0.11f);
            p.setLetterSpacing(0.25f);
            c.drawText("BY TMS", w / 2f, by + bh + size * 0.20f, p);
            p.setTextSize(size * 0.075f);
            p.setLetterSpacing(0.1f);
            p.setColor(DIM);
            c.drawText(version, w / 2f, h - size * 0.25f, p);
            if (t < 1f) postInvalidateOnAnimation();
        }
    }

    /** ไอคอนเมนูล่าง วาดด้วยเส้น */
    public static final class NavIcon extends View {
        public static final int CONNECT = 0, DATA = 1, FAULT = 2, INFO = 3, MORE = 4;
        private final int kind;
        private int color = MUTED;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public NavIcon(Context c, int kind) { super(c); this.kind = kind; }

        public void setColor(int col) { color = col; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.09f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float cx = ox + s / 2f, cy = oy + s / 2f;
            switch (kind) {
                case CONNECT: { // ปลั๊ก
                    c.drawLine(cx - s * 0.13f, oy + s * 0.12f, cx - s * 0.13f, oy + s * 0.30f, p);
                    c.drawLine(cx + s * 0.13f, oy + s * 0.12f, cx + s * 0.13f, oy + s * 0.30f, p);
                    RectF body = new RectF(cx - s * 0.27f, oy + s * 0.30f, cx + s * 0.27f, oy + s * 0.58f);
                    c.drawRoundRect(body, s * 0.08f, s * 0.08f, p);
                    c.drawLine(cx, oy + s * 0.58f, cx, oy + s * 0.88f, p);
                    break;
                }
                case DATA: { // เกจ
                    RectF a = new RectF(ox + s * 0.12f, oy + s * 0.18f, ox + s * 0.88f, oy + s * 0.94f);
                    c.drawArc(a, 150, 240, false, p);
                    c.drawLine(cx, oy + s * 0.56f, cx + s * 0.20f, oy + s * 0.36f, p);
                    break;
                }
                case FAULT: { // สามเหลี่ยม !
                    Path t = new Path();
                    t.moveTo(cx, oy + s * 0.12f);
                    t.lineTo(ox + s * 0.92f, oy + s * 0.86f);
                    t.lineTo(ox + s * 0.08f, oy + s * 0.86f);
                    t.close();
                    c.drawPath(t, p);
                    c.drawLine(cx, oy + s * 0.40f, cx, oy + s * 0.60f, p);
                    c.drawPoint(cx, oy + s * 0.73f, p);
                    break;
                }
                case INFO: { // วงกลม i
                    c.drawCircle(cx, cy, s * 0.40f, p);
                    c.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.20f, p);
                    c.drawPoint(cx, cy - s * 0.18f, p);
                    break;
                }
                default: { // จุด 3 จุด
                    p.setStyle(Paint.Style.FILL);
                    for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.28f, cy, s * 0.08f, p);
                }
            }
        }
    }

    /** ไอคอนกล่อง: DME = กล่อง ECU มีครีบ + ขั้วต่อ, GEAR = เฟือง (กล่องเกียร์) */
    public static final class EcuIcon extends View {
        public static final int DME = 0, GEAR = 1;
        private final int kind;
        private int color = MUTED;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public EcuIcon(Context c, int kind) { super(c); this.kind = kind; }

        public void setColor(int col) { color = col; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
            p.setColor(color);
            p.setStrokeWidth(s * 0.06f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            if (kind == DME) {
                // ตัวกล่อง
                p.setStyle(Paint.Style.STROKE);
                RectF box = new RectF(ox + s * 0.10f, oy + s * 0.22f, ox + s * 0.90f, oy + s * 0.80f);
                c.drawRoundRect(box, s * 0.08f, s * 0.08f, p);
                // ครีบระบายความร้อน
                for (int i = 0; i < 4; i++) {
                    float x = ox + s * (0.30f + i * 0.13f);
                    c.drawLine(x, oy + s * 0.34f, x, oy + s * 0.56f, p);
                }
                // ขั้วต่อด้านล่าง
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(ox + s * 0.22f, oy + s * 0.80f, ox + s * 0.78f, oy + s * 0.92f), s * 0.03f, s * 0.03f, p);
                // หูยึด
                c.drawCircle(ox + s * 0.18f, oy + s * 0.30f, s * 0.035f, p);
                c.drawCircle(ox + s * 0.82f, oy + s * 0.30f, s * 0.035f, p);
                // ไฟสถานะ 3 สี
                int[] cols = {M_LIGHT, M_BLUE, M_RED};
                for (int i = 0; i < 3; i++) {
                    p.setColor(cols[i]);
                    c.drawRect(ox + s * (0.30f + i * 0.13f), oy + s * 0.64f, ox + s * (0.40f + i * 0.13f), oy + s * 0.69f, p);
                }
            } else {
                // เฟือง
                float cx = ox + s / 2f, cy = oy + s / 2f, r1 = s * 0.30f, r2 = s * 0.42f;
                Path g = new Path();
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a0 = Math.PI * 2 * i / (teeth * 2), a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
                    float r = (i % 2 == 0) ? r2 : r1;
                    float x0 = cx + (float) Math.cos(a0) * r, y0 = cy + (float) Math.sin(a0) * r;
                    float x1 = cx + (float) Math.cos(a1) * r, y1 = cy + (float) Math.sin(a1) * r;
                    if (i == 0) g.moveTo(x0, y0); else g.lineTo(x0, y0);
                    g.lineTo(x1, y1);
                }
                g.close();
                p.setStyle(Paint.Style.STROKE);
                c.drawPath(g, p);
                c.drawCircle(cx, cy, s * 0.13f, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(M_RED);
                c.drawCircle(cx, cy, s * 0.05f, p);
            }
        }
    }
}
