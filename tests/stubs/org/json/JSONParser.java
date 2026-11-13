package org.json;
import java.util.*;
class JSONParser {
    final String s; int i;
    JSONParser(String s){this.s=s;}
    void ws(){ while(i<s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    Object value(){
        ws(); char c=s.charAt(i);
        if(c=='{'){ i++; JSONObject o=new JSONObject(); ws(); if(s.charAt(i)=='}'){i++;return o;}
            while(true){ ws(); String k=(String)value(); ws(); if(s.charAt(i++)!=':') throw new JSONException("':' expected at "+i);
                o.map.put(k,value()); ws(); char d=s.charAt(i++); if(d=='}') return o; if(d!=',') throw new JSONException("',' expected at "+i);} }
        if(c=='['){ i++; JSONArray a=new JSONArray(); ws(); if(s.charAt(i)==']'){i++;return a;}
            while(true){ a.list.add(value()); ws(); char d=s.charAt(i++); if(d==']') return a; if(d!=',') throw new JSONException("',' expected at "+i);} }
        if(c=='"'){ i++; StringBuilder b=new StringBuilder(); while(true){ char d=s.charAt(i++); if(d=='"') break;
            if(d=='\\'){ char e=s.charAt(i++); if(e=='u'){ b.append((char)Integer.parseInt(s.substring(i,i+4),16)); i+=4;} else b.append(e=='n'?'\n':e=='t'?'\t':e);} else b.append(d);} return b.toString(); }
        if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;} if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;} if(s.startsWith("null",i)){i+=4;return null;}
        int st=i; while(i<s.length() && "+-0123456789.eE".indexOf(s.charAt(i))>=0) i++;
        if(st==i) throw new JSONException("bad char at "+i); return Double.parseDouble(s.substring(st,i));
    }
}
