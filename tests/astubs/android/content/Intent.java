package android.content;
public class Intent { public static final String ACTION_OPEN_DOCUMENT="", CATEGORY_OPENABLE="", ACTION_OPEN_DOCUMENT_TREE=""; public static final int FLAG_GRANT_READ_URI_PERMISSION=1, FLAG_GRANT_PERSISTABLE_URI_PERMISSION=64; public Intent addFlags(int f){return this;}
 public Intent(String a){} public Intent setPackage(String p){return this;} public Intent addCategory(String c){return this;} public Intent setType(String t){return this;}
 public boolean getBooleanExtra(String n, boolean d){return d;} public android.net.Uri getData(){return null;} }
