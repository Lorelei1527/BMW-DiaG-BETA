package android.app;
public class Activity extends android.content.Context {
 public static final int RESULT_OK=-1;
 protected void onCreate(android.os.Bundle b){} protected void onDestroy(){} protected void onResume(){}
 public android.view.Window getWindow(){return null;}
 public void setContentView(android.view.View v){}
 public final void runOnUiThread(Runnable r){}
 public void startActivityForResult(android.content.Intent i,int r){}
 protected void onActivityResult(int a,int b,android.content.Intent d){}
 public final void requestPermissions(String[] p,int r){}
 public void onRequestPermissionsResult(int r,String[] p,int[] g){}
}
