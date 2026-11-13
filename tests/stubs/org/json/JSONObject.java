package org.json;
import java.util.*;
public class JSONObject {
    final Map<String,Object> map=new LinkedHashMap<>();
    JSONObject(){}
    public JSONObject(String s){ JSONObject o=(JSONObject)new JSONParser(s).value(); map.putAll(o.map); }
    public boolean has(String k){return map.containsKey(k);}
    Object req(String k){ if(!map.containsKey(k)) throw new JSONException("No value for "+k); return map.get(k);}
    public String getString(String k){ Object o=req(k); if(!(o instanceof String)) throw new JSONException(k+" not string"); return (String)o;}
    public int getInt(String k){ Object o=req(k); if(!(o instanceof Double)) throw new JSONException(k+" not number"); return ((Double)o).intValue();}
    public JSONArray getJSONArray(String k){ return (JSONArray)req(k);}
    public JSONObject getJSONObject(String k){ return (JSONObject)req(k);}
    public JSONObject optJSONObject(String k){ Object o=map.get(k); return o instanceof JSONObject?(JSONObject)o:null;}
    public java.util.Iterator<String> keys(){ return map.keySet().iterator(); }
    public JSONArray optJSONArray(String k){ Object o=map.get(k); return o instanceof JSONArray?(JSONArray)o:null;}
    public String optString(String k,String d){ Object o=map.get(k); return o==null?d:(o instanceof Double && ((Double)o)%1==0? String.valueOf(((Double)o).intValue()):o.toString());}
    public int optInt(String k,int d){ Object o=map.get(k); return o instanceof Double?((Double)o).intValue():d;}
    public double optDouble(String k,double d){ Object o=map.get(k); return o instanceof Double?(Double)o:d;}
    public boolean optBoolean(String k,boolean d){ Object o=map.get(k); return o instanceof Boolean?(Boolean)o:d;}
}
