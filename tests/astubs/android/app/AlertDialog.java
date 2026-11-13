package android.app;
public class AlertDialog { public static class Builder { public Builder(android.content.Context c){}
 public Builder setMultiChoiceItems(CharSequence[] i, boolean[] c, android.content.DialogInterface.OnMultiChoiceClickListener l){return this;}
 public Builder setItems(CharSequence[] i, android.content.DialogInterface.OnClickListener l){return this;}
 public Builder setTitle(CharSequence s){return this;} public Builder setMessage(CharSequence s){return this;}
 public Builder setPositiveButton(CharSequence s, android.content.DialogInterface.OnClickListener l){return this;}
 public Builder setNegativeButton(CharSequence s, android.content.DialogInterface.OnClickListener l){return this;}
 public AlertDialog show(){return null;} } }
