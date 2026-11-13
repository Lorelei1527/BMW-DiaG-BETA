package com.tms.bmwediag.diag;

import com.tms.bmwediag.io.Hex;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** โหลดนิยามกล่องจาก JSON (assets/ecus.json หรือไฟล์ที่ผู้ใช้ import) */
public final class DefinitionLoader {
    private DefinitionLoader() {}

    public static List<EcuDef> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("ecus");
        List<EcuDef> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            EcuDef e = new EcuDef();
            e.id = o.getString("id");
            e.name = o.getString("name");
            e.notes = o.optString("notes", "");
            e.protocol = EcuDef.Protocol.valueOf(o.getString("protocol").toUpperCase());
            e.address = Integer.parseInt(o.optString("address", "12"), 16);
            boolean ds2like = e.protocol == EcuDef.Protocol.DS2 || e.protocol == EcuDef.Protocol.AUTO;
            e.baud = o.optInt("baud", ds2like ? 9600 : 10400);
            String p = o.optString("parity", ds2like ? "E" : "N").toUpperCase();
            e.parity = p.startsWith("E") ? 2 : p.startsWith("O") ? 1 : 0;
            JSONArray id = o.optJSONArray("ident");
            if (id != null) for (int j = 0; j < id.length(); j++) e.ident.add(Hex.parse(id.getString(j)));
            JSONArray fra = o.optJSONArray("faultRead");
            if (fra != null) {
                for (int j = 0; j < fra.length(); j++) e.faultReads.add(fra.getString(j));
            } else if (o.has("faultRead")) {
                e.faultReads.add(o.getString("faultRead"));
            }
            if (!e.faultReads.isEmpty() && !e.faultReads.get(0).toUpperCase().contains("XX"))
                e.faultRead = Hex.parse(e.faultReads.get(0));
            e.sgbd = o.optString("sgbd", "");
            e.can = o.optBoolean("can", false);
            e.link = o.optString("link", "ANY");
            if (o.has("faultClear")) e.faultClear = Hex.parse(o.getString("faultClear"));
            e.ds2FaultEntrySize = o.optInt("faultEntrySize", 0);
            e.ds2FaultHeader = o.optInt("faultHeader", 1);
            JSONObject ft = o.optJSONObject("faultText");
            if (ft != null) for (java.util.Iterator<String> it = ft.keys(); it.hasNext(); ) {
                String key = it.next();
                e.faultText.put(Integer.parseInt(key.replace("0x", "").replace("0X", ""), 16), ft.getString(key));
            }
            JSONArray live = o.optJSONArray("live");
            if (live != null) for (int j = 0; j < live.length(); j++) {
                JSONObject l = live.getJSONObject(j);
                LiveRequest lr = new LiveRequest();
                lr.request = Hex.parse(l.getString("request"));
                lr.skip = l.optInt("skip", ds2like ? 1 : 2);
                lr.showRaw = l.optBoolean("showRaw", false);
                JSONArray ps = l.optJSONArray("params");
                if (ps != null) for (int k = 0; k < ps.length(); k++) {
                    JSONObject q = ps.getJSONObject(k);
                    LiveParam lp = new LiveParam();
                    lp.name = q.getString("name");
                    lp.unit = q.optString("unit", "");
                    lp.offset = q.getInt("offset");
                    lp.length = q.optInt("length", 1);
                    lp.signed = q.optBoolean("signed", false);
                    lp.littleEndian = q.optBoolean("littleEndian", false);
                    lp.factor = q.optDouble("factor", 1);
                    lp.add = q.optDouble("add", 0);
                    lp.decimals = q.optInt("decimals", 1);
                    String m = q.optString("bitMask", "");
                    if (!m.isEmpty()) lp.bitMask = Integer.parseInt(m.replace("0x", ""), 16);
                    lr.params.add(lp);
                }
                if (lr.params.isEmpty()) lr.showRaw = true;
                e.live.add(lr);
            }
            out.add(e);
        }
        return out;
    }
}
