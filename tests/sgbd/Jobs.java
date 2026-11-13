import com.tms.bmwediag.sgbd.*;
import java.nio.file.*;
public class Jobs { public static void main(String[] a) throws Exception { for(String f:a){ Sgbd s=new Sgbd(f,Files.readAllBytes(Paths.get("/mnt/user-data/uploads/Ecu/"+f))); StringBuilder sb=new StringBuilder(); for(String j:s.jobNames()) if(j.toUpperCase().contains("STATUS")||j.toUpperCase().contains("MESSW")||j.toUpperCase().startsWith("FS_")) sb.append(j).append(' '); System.out.println("== "+f+"\n"+sb);} } }
