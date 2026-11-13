package android.widget;
public class LinearLayout extends android.view.ViewGroup { public static final int VERTICAL=1; public LinearLayout(android.content.Context c){super(c);}
 public void setOrientation(int o){} public void setGravity(int g){}
 public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams { public LayoutParams(int w,int h){super(w,h);} public LayoutParams(int w,int h,float weight){super(w,h);} } }
