package com.tms.bmwediag.proto;

import java.io.IOException;

public class NegativeResponseException extends IOException {
    public final int service, code;

    public NegativeResponseException(int service, int code) {
        super(String.format("ECU ปฏิเสธ service 0x%02X: %s (0x%02X)", service, text(code), code));
        this.service = service;
        this.code = code;
    }

    public static String text(int c) {
        switch (c) {
            case 0x10: return "generalReject";
            case 0x11: return "serviceNotSupported";
            case 0x12: return "subFunctionNotSupported/invalidFormat";
            case 0x21: return "busyRepeatRequest";
            case 0x22: return "conditionsNotCorrect";
            case 0x31: return "requestOutOfRange";
            case 0x33: return "securityAccessDenied";
            case 0x78: return "responsePending";
            case 0x80: return "serviceNotSupportedInActiveSession";
            default: return "unknown";
        }
    }
}
