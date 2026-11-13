package android.widget;
public abstract class CompoundButton extends Button { public CompoundButton(android.content.Context c){super(c);} public interface OnCheckedChangeListener { void onCheckedChanged(CompoundButton b, boolean c);} public void setChecked(boolean b){} public boolean isChecked(){return false;} public void setOnCheckedChangeListener(OnCheckedChangeListener l){} }
