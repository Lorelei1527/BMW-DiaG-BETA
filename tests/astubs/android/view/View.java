package android.view;
public class View { public static final int VISIBLE=0, GONE=8; public View(android.content.Context c){}
 public interface OnClickListener { void onClick(View v); }
 public void setOnClickListener(OnClickListener l){} public void setVisibility(int v){} public void setBackground(android.graphics.drawable.Drawable d){} public android.graphics.drawable.Drawable getBackground(){return null;}
 public void setBackgroundColor(int c){} public void setPadding(int a,int b,int c,int d){} public void setTag(Object o){} public Object getTag(){return null;} public boolean isShown(){return true;}
 public void setHorizontalScrollBarEnabled(boolean b){} }
