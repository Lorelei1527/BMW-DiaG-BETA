package android.content;
public abstract class Context {
 public static final String USB_SERVICE="usb", BLUETOOTH_SERVICE="bt"; public static final int MODE_PRIVATE=0, RECEIVER_NOT_EXPORTED=4;
 public Object getSystemService(String n){return null;}
 public SharedPreferences getSharedPreferences(String n,int m){return null;}
 public android.content.res.Resources getResources(){return null;}
 public java.io.File getFilesDir(){return null;}
 public java.io.File getExternalFilesDir(String t){return null;}
 public android.content.res.AssetManager getAssets(){return null;}
 public ContentResolver getContentResolver(){return null;}
 public String getPackageName(){return null;}
 public Intent registerReceiver(BroadcastReceiver r, IntentFilter f){return null;}
 public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags){return null;}
 public void unregisterReceiver(BroadcastReceiver r){}
 public int checkSelfPermission(String p){return 0;}
}
