package org.json;
import java.util.*;
public class JSONArray {
    final List<Object> list=new ArrayList<>();
    public int length(){return list.size();}
    public JSONObject getJSONObject(int i){return (JSONObject)list.get(i);}
    public String getString(int i){return (String)list.get(i);}
}
