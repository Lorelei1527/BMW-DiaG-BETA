package android.widget;
public abstract class AdapterView<T extends Adapter> extends android.view.ViewGroup { public AdapterView(android.content.Context c){super(c);}
 public interface OnItemSelectedListener { void onItemSelected(AdapterView<?> p, android.view.View v, int pos, long id); void onNothingSelected(AdapterView<?> p); }
 public void setOnItemSelectedListener(OnItemSelectedListener l){} public int getSelectedItemPosition(){return 0;} public void setSelection(int p){} public void setAdapter(T a){} }
