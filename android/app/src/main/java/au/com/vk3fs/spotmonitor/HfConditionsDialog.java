package au.com.vk3fs.spotmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Two live BoM space-weather maps, refreshed every five minutes while open. */
final class HfConditionsDialog {
    private static final String[] TITLES={"Australasian T Index","Australasian Ionospheric Map"};
    private static final String[] URLS={
        "https://www.sws.bom.gov.au/Images/HF%20Systems/Australasia/T%20Index/ANZTIndexMap.gif",
        "https://www.sws.bom.gov.au/Images/HF%20Systems/Australasia/Ionospheric%20Map/AustralasiaIMap.gif"
    };
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ImageView[] maps=new ImageView[2];
    private TextView status;
    private AlertDialog dialog;
    private boolean loading=false;
    private final Runnable periodic=new Runnable(){@Override public void run(){
        if(dialog!=null&&dialog.isShowing()){refresh();handler.postDelayed(this,300_000);}
    }};
    HfConditionsDialog(Activity activity){this.activity=activity;}
    private int dp(int px){return (int)(activity.getResources().getDisplayMetrics().density*px+0.5f);}
    private TextView text(String str,int size){TextView v=new TextView(activity);v.setText(str);v.setTextSize(size);v.setTextColor(Color.WHITE);v.setGravity(Gravity.CENTER);return v;}
    void show(){
        if(dialog!=null&&dialog.isShowing())return;
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12),dp(10),dp(12),dp(8));body.setBackgroundColor(0xFF101925);
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);body.addView(heading);
        status=text("Loading BoM maps…",12);
        heading.addView(status,new LinearLayout.LayoutParams(0,dp(38),1));
        Button refresh=new Button(activity);refresh.setAllCaps(false);refresh.setText("Refresh Now");
        heading.addView(refresh,new LinearLayout.LayoutParams(dp(135),dp(45)));
        LinearLayout pictures=new LinearLayout(activity);pictures.setOrientation(LinearLayout.HORIZONTAL);
        // Approximately 30% larger maps, limited by available landscape screen height.
        int screenHeight=activity.getResources().getDisplayMetrics().heightPixels;
        int mapHeight=Math.min(dp(481),Math.max(dp(240),screenHeight-dp(170)));
        body.addView(pictures,new LinearLayout.LayoutParams(-1,mapHeight));
        for(int i=0;i<2;i++){
            LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(5),dp(4),dp(5),dp(4));
            LinearLayout.LayoutParams panelParams=new LinearLayout.LayoutParams(0,-1,1);pictures.addView(panel,panelParams);
            panel.addView(text(TITLES[i],14),new LinearLayout.LayoutParams(-1,dp(35)));
            ImageView picture=new ImageView(activity);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.setBackgroundColor(0xFF173148);panel.addView(picture,new LinearLayout.LayoutParams(-1,0,1));maps[i]=picture;
        }
        dialog=new AlertDialog.Builder(activity).setTitle("HF Conditions — BoM Space Weather")
            .setView(body).setPositiveButton("Close",(d,w)->{}).create();
        dialog.setOnDismissListener(d->{handler.removeCallbacks(periodic);dialog=null;});
        refresh.setOnClickListener(v->refresh());
        dialog.show();
        // Expand the popup horizontally without forcing it outside the display.
        if(dialog.getWindow()!=null){
            int width=activity.getResources().getDisplayMetrics().widthPixels;
            dialog.getWindow().setLayout((int)(width*0.94f),-2);
        }
        handler.post(periodic);
    }
    private static Bitmap retrieve(String url) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url+"?t="+System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(12000);connection.setReadTimeout(18000);
        connection.setRequestProperty("User-Agent","Mozilla/5.0 VK3FS-Android-SpotMonitor");
        try{
            if(connection.getResponseCode()!=200)throw new Exception("HTTP "+connection.getResponseCode());
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] chunk=new byte[8192];int n;
                while((n=input.read(chunk))!=-1){bytes.write(chunk,0,n);if(bytes.size()>8_000_000)throw new Exception("Map too large");}
                byte[] raw=bytes.toByteArray();Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length);
                if(bitmap==null)throw new Exception("Unable to decode image");
                return bitmap;
            }
        }finally{connection.disconnect();}
    }
    private void refresh(){
        if(loading)return;loading=true;status.setText("Refreshing BoM maps…");
        executor.execute(()->{
            Bitmap[] results=new Bitmap[2];StringBuilder errors=new StringBuilder();
            for(int i=0;i<2;i++){
                try{results[i]=retrieve(URLS[i]);}
                catch(Exception e){if(errors.length()>0)errors.append(", ");errors.append(TITLES[i]);}
            }
            activity.runOnUiThread(()->{
                loading=false;
                if(dialog==null||!dialog.isShowing())return;
                for(int i=0;i<2;i++)if(results[i]!=null)maps[i].setImageBitmap(results[i]);
                String stamp=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date());
                status.setText(errors.length()==0?"Last refreshed "+stamp:"Unavailable: "+errors+" — try Refresh Now");
            });
        });
    }
    void close(){handler.removeCallbacks(periodic);if(dialog!=null){dialog.dismiss();dialog=null;}executor.shutdownNow();}
}
