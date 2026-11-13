import com.tms.bmwediag.diag.*;
import com.tms.bmwediag.proto.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ElmSessionTest {
    static class FakeElm {
        final PipedInputStream appIn=new PipedInputStream(65536); final PipedOutputStream toApp;
        final PipedOutputStream appOut=new PipedOutputStream(); final PipedInputStream fromApp;
        final List<String> cmds=Collections.synchronizedList(new ArrayList<>());
        FakeElm() throws IOException { toApp=new PipedOutputStream(appIn); fromApp=new PipedInputStream(appOut,65536);
            Thread t=new Thread(()->{ try{ StringBuilder sb=new StringBuilder(); int c;
                while((c=fromApp.read())>=0){ if(c=='\r'){ String cmd=sb.toString().trim(); sb.setLength(0); cmds.add(cmd); String r=reply(cmd); toApp.write((r+"\r\r>").getBytes()); toApp.flush(); } else sb.append((char)c);} }catch(IOException e){} });
            t.setDaemon(true); t.start(); }
        String reply(String cmd){
            if(cmd.startsWith("AT")) return cmd.equals("ATI")?"ELM327 v1.5":"OK";
            if(cmd.startsWith("1A80")) return "01D\r0:F15A800000075579\r1:3535313034303135\r2:3136373131323135\r3:33303531320000";
            if(cmd.startsWith("1802")) return "F1580227102429CCE4";
            if(cmd.startsWith("14")) return "F154FFFF";
            String sid=cmd.substring(0,2); int s=Integer.parseInt(sid,16)+0x40;
            return String.format("F1%02X%s0000000000000000000000000000", s, cmd.length()>=4?cmd.substring(2,4):"00");
        }
    }
    public static void main(String[] a) throws Exception {
        List<EcuDef> defs=DefinitionLoader.parse(new String(Files.readAllBytes(Paths.get("app/src/main/assets/ecus.json")),"UTF-8"));
        EcuDef ecu=null; for(EcuDef e:defs) if(e.id.equals("mev92")) ecu=e;
        DiagSession.Options o=new DiagSession.Options();
        o.sgbdFiles = name -> { for(Path x: Files.newDirectoryStream(Paths.get("/mnt/user-data/uploads/Ecu"))) if(x.getFileName().toString().equalsIgnoreCase(name)) return Files.readAllBytes(x); return null; };
        FakeElm f=new FakeElm();
        DiagSession s=new DiagSession(ecu,o,m->{ if(!m.startsWith("TX")&&!m.startsWith("RX")) System.out.println("LOG "+m);});
        s.connectElm(new Elm327(f.appIn, f.appOut));
        System.out.println("SGBD="+s.isSgbd()+" "+s.describe());
        List<String> id=s.readIdent(); System.out.println("  ident lines "+id.size()+": "+id.subList(0,Math.min(4,id.size())));
        for(Dtc.Code c: s.readFaults()) System.out.println("  FAULT "+c.code+" "+c.text.replace("\n"," | "));
        List<DiagSession.Reading> r=s.pollLive(); System.out.println("  live "+r.size());
        System.out.println("  ELM cmds: "+f.cmds.subList(0,Math.min(22,f.cmds.size())));
    }
}
