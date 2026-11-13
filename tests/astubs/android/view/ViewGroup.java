package android.view;
public abstract class ViewGroup extends View { public ViewGroup(android.content.Context c){super(c);}
 public void addView(View v){} public void addView(View v, LayoutParams p){} public void removeAllViews(){}
 public static class LayoutParams { public int width, height; public LayoutParams(int w,int h){width=w;height=h;} public LayoutParams(){}}
 public static class MarginLayoutParams extends LayoutParams { public MarginLayoutParams(int w,int h){super(w,h);} public MarginLayoutParams(){} public void setMargins(int a,int b,int c,int d){} } }
