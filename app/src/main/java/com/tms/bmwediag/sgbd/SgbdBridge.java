package com.tms.bmwediag.sgbd;

/** ให้ interface ภายนอก package ตั้ง error ของตัวรันได้ */
public final class SgbdBridge {
    private SgbdBridge() {}
    public static void setError(Sgbd s, int code) throws SgbdException { s.setError(code); }
}
