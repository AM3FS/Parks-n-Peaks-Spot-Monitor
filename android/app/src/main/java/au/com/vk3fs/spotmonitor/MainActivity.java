package au.com.vk3fs.spotmonitor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.content.res.AssetFileDescriptor;
import android.widget.EditText;
import android.text.InputType;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.BitmapFactory;
import android.text.InputType;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import java.util.HashMap;
import java.util.Calendar;
import java.util.Map;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Tablet-first read-only client. No radio, HaLo, or Windows dependencies. */
public final class MainActivity extends Activity {
    private static final String[] MODES={"All","SSB","CW","FT8","FT4","AM","FM"};
    private static final String[] PROGRAMS={"All","AUSParks","POTA","WWFF","SOTA","SiOTA","ZLOTA","LLOTA"};
    private static final String[] COUNTRIES={"All","VK only","VK + ZL","ZL only"};
    private static final String[] AGES={"15","30","45","60","90","120"};
    private static final int REFRESH_INTERVAL_MS=60_000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    // The feed worker is replaceable: a stuck parser must never block all subsequent refreshes.
    private ExecutorService feedExecutor;
    private Future<?> activeFeed;
    private Runnable activeWatchdog;
    private int feedGeneration=0;
    private static final long FEED_DEADLINE_MS=30_000L;
    private String feedStage="Idle";
    private String feedDetail="No request started";
    private long feedStartedAt=0L;
    private final ArrayList<SpotFeed.Spot> spots=new ArrayList<>();
    private SharedPreferences prefs;
    private LinearLayout root, spotRows;
    private TextView status, count, localClock, utcClock, empty, legend;
    private Spinner modeSpinner, programSpinner, countrySpinner, ageSpinner;
    private Button refreshButton, themeButton, wantedButton, qrzButton, hfButton, awakeButton, newSoundButton;
    private final ParkCounter parkCounter=new ParkCounter();
    private HfConditionsDialog hfConditions;
    private QrzLookup qrz;
    private final Map<String,NameEntry> nameCache=new HashMap<>();
    private final ExecutorService qrzExecutor=Executors.newSingleThreadExecutor();
    private boolean qrzLoading=false;
    private String qrzMessage="";
    private static final class NameEntry {
        final String name; final long when;
        NameEntry(String name,long when){this.name=name;this.when=when;}
    }
    // The visible Sound button is a master mute. Individual alert preferences
    // are saved separately and never overwritten by the master switch.
    private boolean wantedEnabled=false, wantedSound=true, newSoundEnabled=true, masterSoundOn=true;
    private final Set<String> wantedCalls=new HashSet<>();
    private final Set<String> observedSpots=new HashSet<>();
    private boolean wantedBaselineReady=false;
    private MediaPlayer wantedPlayer, newSpotPlayer;
    private boolean loading=false, night=true, autoTheme=true, keepAwake=false;
    private boolean hasLoaded=false;
    private long lastLoaded=0;
    private long lastRefreshAttempt=0;
    private String error=null;
    private final Runnable ticker=new Runnable(){public void run(){
        if(autoTheme){ boolean shouldNight=isNight();if(shouldNight!=night){night=shouldNight;buildUi();} }
        updateClock();renderSpots();handler.postDelayed(this,30_000);
    }};
    private final Runnable refreshTimer=new Runnable(){public void run(){refresh();handler.postDelayed(this,REFRESH_INTERVAL_MS);}};
    private int bg, panel, text, muted, accent, border;

    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        prefs=getSharedPreferences("vk3fs_spot_settings",MODE_PRIVATE);
        qrz=new QrzLookup(this);
        autoTheme=prefs.getBoolean("auto_theme",true);
        night=autoTheme?isNight():prefs.getBoolean("night",true);
        keepAwake=prefs.getBoolean("awake",false);
        wantedEnabled=prefs.getBoolean("wanted_enabled",false);
        wantedSound=prefs.getBoolean("wanted_sound",true);
        // Migrate the old v1.0.19 Sound switch to a master mute exactly once.
        // Normal-spot and Wanted Station preferences remain independent.
        if(!prefs.contains("master_sound_on")){
            boolean oldSound=prefs.getBoolean("new_spot_sound",true);
            prefs.edit().putBoolean("master_sound_on",oldSound)
                .putBoolean("normal_spot_sound_enabled",true).apply();
        }
        masterSoundOn=prefs.getBoolean("master_sound_on",true);
        newSoundEnabled=prefs.getBoolean("normal_spot_sound_enabled",true);
        wantedCalls.addAll(prefs.getStringSet("wanted_calls",new HashSet<>()));
        buildUi();handler.post(ticker);handler.post(refreshTimer);
    }
    @Override protected void onDestroy(){
        ++feedGeneration;
        handler.removeCallbacksAndMessages(null);
        if(activeFeed!=null)activeFeed.cancel(true);
        if(feedExecutor!=null)feedExecutor.shutdownNow();
        qrzExecutor.shutdownNow();
        stopAllAlertAudio();
        if(hfConditions!=null)hfConditions.close();super.onDestroy();}
    @Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);buildUi();}
    private boolean isNight(){java.util.Calendar c=java.util.Calendar.getInstance();int hour=c.get(java.util.Calendar.HOUR_OF_DAY);return hour>=18||hour<7;}
    private int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+0.5f);}
    private GradientDrawable shape(int fill,int stroke,int rad){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(rad));d.setStroke(dp(1),stroke);return d;}
    private void colours(){bg=Color.parseColor(night?"#101925":"#E8EEF4");panel=Color.parseColor(night?"#1B2A3C":"#FFFFFF");text=Color.parseColor(night?"#F5FAFF":"#172B40");muted=Color.parseColor(night?"#A7BCD0":"#536A80");accent=Color.parseColor("#40D48A");border=Color.parseColor(night?"#395269":"#CBD7E1");}
    // Set an explicit dialog theme: Android's default dark dialog is illegible with
    // the day palette unless the whole dialog (including checkboxes) is themed.
    private AlertDialog.Builder themedDialog(){
        return new AlertDialog.Builder(this,night?
            android.R.style.Theme_Material_Dialog_Alert:
            android.R.style.Theme_Material_Light_Dialog_Alert);
    }
    private void whiteOverlayText(TextView v){
        v.setTextColor(Color.WHITE);
        v.setShadowLayer(dp(2.5f),0,dp(1),0xFF07111C);
    }
    private void styleWantedButton(){
        if(wantedEnabled){
            wantedButton.setTextColor(night?0xFFFFC96D:0xFF704000);
            if(!night)wantedButton.setBackground(shape(0xFFFFE3A1,0xFFB77912,7));
        } else {
            wantedButton.setTextColor(text);
            wantedButton.setBackground(shape(panel,border,7));
        }
    }
    private void styleQrzButton(){
        qrzButton.setTextColor(qrz.configured()?(night?accent:0xFF145B39):text);
        if(!night&&qrz.configured())qrzButton.setBackground(shape(0xFFDCF3E6,0xFF19764A,7));
    }
    private TextView label(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(text);t.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout.LayoutParams layout(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void addGap(LinearLayout l,int w){View v=new View(this);l.addView(v,layout(w,1));}
    private void setPadding(View v,int a,int b,int c,int d){v.setPadding(dp(a),dp(b),dp(c),dp(d));}
    private void buildUi(){
        colours();applyImmersive();
        if(keepAwake)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root=column();root.setBackgroundColor(bg);
        try {
            Bitmap backdrop=BitmapFactory.decodeStream(getAssets().open(night?"background_night.jpg":"background_day.jpg"));
            BitmapDrawable drawable=new BitmapDrawable(getResources(),backdrop);
            drawable.setGravity(Gravity.FILL);
            root.setBackground(drawable);
        } catch(IOException ignored) {}
        setPadding(root,12,8,12,8);setContentView(root);
        // Full-width landscape artwork with the readable title overlaid.
        FrameLayout header=new FrameLayout(this);
        header.setBackground(shape(night?0xD5111D2A:0xE9ECF3F9,border,8));
        root.addView(header,layout(-1,91));
        ImageView banner=new ImageView(this);
        try {banner.setImageBitmap(BitmapFactory.decodeStream(getAssets().open(night?"banner_night.png":"banner_day.png")));}catch(IOException ignored){}
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.setAlpha(0.80f); // Artwork at approximately 80% opacity.
        header.addView(banner,new FrameLayout.LayoutParams(-1,-1));
        // Gentle scrim protects white lettering while preserving the scenery.
        View bannerShade=new View(this);
        bannerShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0x3507111D,0x220B1525,0x420C1826}));
        header.addView(bannerShade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout headerContent=row();
        setPadding(headerContent,12,0,12,0);
        header.addView(headerContent,new FrameLayout.LayoutParams(-1,-1));
        // Original Windows branding, retained as a transparent image at the left.
        ImageView hunter=new ImageView(this);
        try {hunter.setImageBitmap(BitmapFactory.decodeStream(getAssets().open("hunter_text.png")));}catch(IOException ignored){}
        hunter.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int tabletWidthDp=(int)(getResources().getDisplayMetrics().widthPixels /
            getResources().getDisplayMetrics().density);
        headerContent.addView(hunter,layout(tabletWidthDp<1000?145:205,67));
        LinearLayout nameBlock=column();nameBlock.setGravity(Gravity.CENTER);
        headerContent.addView(nameBlock,new LinearLayout.LayoutParams(0,-1,1));
        TextView appTitle=label("Parks n Peaks Spot Monitor",21,true);
        appTitle.setGravity(Gravity.CENTER);appTitle.setSingleLine(true);
        whiteOverlayText(appTitle);
        appTitle.setShadowLayer(dp(3),0,dp(1),0xFF08111E);
        appTitle.setAutoSizeTextTypeUniformWithConfiguration(14,21,1,TypedValue.COMPLEX_UNIT_SP);
        nameBlock.addView(appTitle,layout(-1,37));
        TextView subtitle=label("Live amateur radio park spots",11,false);
        subtitle.setTextColor(0xFFE3EBF3);subtitle.setGravity(Gravity.CENTER);
        nameBlock.addView(subtitle,layout(-1,20));
        LinearLayout meta=column();meta.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        setPadding(meta,0,0,0,0);
        TextView title=label("ANDROID  v1.0.22",12,true);title.setTextColor(accent);title.setGravity(Gravity.RIGHT);meta.addView(title);
        localClock=label("",12,true);localClock.setGravity(Gravity.RIGHT);whiteOverlayText(localClock);meta.addView(localClock);
        utcClock=label("",12,true);utcClock.setGravity(Gravity.RIGHT);whiteOverlayText(utcClock);meta.addView(utcClock);
        headerContent.addView(meta,layout(tabletWidthDp<1000?155:190,-1));
        LinearLayout controls=column();controls.setPadding(dp(6),dp(5),dp(6),dp(5));
        controls.setBackground(shape(night?0xD7111D2A:0xF0ECF3F9,border,8));
        LinearLayout.LayoutParams cp=layout(-1,-2);cp.topMargin=dp(5);root.addView(controls,cp);
        // Compact, content-sized filter dropdowns at the left; watchlist and QRZ at the right.
        // On narrower tablets the full-sized controls scroll instead of shrinking labels.
        HorizontalScrollView filterScroller=new HorizontalScrollView(this);
        filterScroller.setFillViewport(true);
        filterScroller.setHorizontalScrollBarEnabled(true);
        controls.addView(filterScroller,layout(-1,48));
        LinearLayout first=row();first.setMinimumWidth(dp(1120));
        filterScroller.addView(first,new FrameLayout.LayoutParams(-2,dp(48)));
        modeSpinner=makeCompactSpinner(first,"MODE",MODES,"mode","All",88);
        countrySpinner=makeCompactSpinner(first,"COUNTRY",COUNTRIES,"country","All",108);
        programSpinner=makeCompactSpinner(first,"PROGRAM",PROGRAMS,"program","All",112);
        ageSpinner=makeCompactSpinner(first,"MAX AGE",AGES,"age","30",72);
        View filterSpacer=new View(this);first.addView(filterSpacer,new LinearLayout.LayoutParams(0,dp(1),1));
        wantedButton=action("★ Wanted Stations",this::showWantedStations);
        styleWantedButton();
        first.addView(wantedButton,layout(144,38));addGap(first,7);
        awakeButton=action("",this::toggleKeepAwake);
        first.addView(awakeButton,layout(118,38));styleAwakeButton();addGap(first,7);
        qrzButton=action("QRZ Names",this::showQrzSettings);
        styleQrzButton();
        first.addView(qrzButton,layout(105,38));
        // Keep all actions touch-sized, with sideways access on 8-inch tablets.
        HorizontalScrollView actionScroller=new HorizontalScrollView(this);
        actionScroller.setFillViewport(true);
        controls.addView(actionScroller,layout(-1,42));
        LinearLayout actions=row();actions.setMinimumWidth(dp(1035));
        actionScroller.addView(actions,new FrameLayout.LayoutParams(-2,dp(42)));
        refreshButton=action("Refresh Spots",()->refresh());actions.addView(refreshButton,layout(136,36));addGap(actions,8);
        themeButton=action(autoTheme?"Theme: Auto":(night?"Theme: Night":"Theme: Day"),this::toggleTheme);
        themeButton.setOnLongClickListener(v->{showSettings();return true;});
        actions.addView(themeButton,layout(150,36));addGap(actions,8);
        hfButton=action("HF Conditions",this::showHfConditions);
        actions.addView(hfButton,layout(130,36));addGap(actions,8);
        newSoundButton=action("",this::toggleNewSpotSound);
        actions.addView(newSoundButton,layout(116,36));styleNewSoundButton();
        newSoundButton.setOnLongClickListener(v->{testNewSpotSound();return true;});

        addGap(actions,12);
        count=label("",12,true);actions.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        status=label("Connecting to Parks n Peaks…",12,false);status.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        actions.addView(status,layout(275,-2));
        status.setOnClickListener(v->showFeedDiagnostics());
        status.setOnLongClickListener(v->{showFeedDiagnostics();return true;});

        // One horizontal scroller contains BOTH heading and spot rows, preserving
        // column alignment if the available width is less than the tablet table.
        FrameLayout tableViewport=new FrameLayout(this);
        LinearLayout.LayoutParams tableViewportParams=new LinearLayout.LayoutParams(-1,0,1);
        tableViewportParams.topMargin=dp(6);
        root.addView(tableViewport,tableViewportParams);
        HorizontalScrollView tableScroller=new HorizontalScrollView(this);
        tableScroller.setFillViewport(true);
        tableScroller.setHorizontalScrollBarEnabled(true);
        tableViewport.addView(tableScroller,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout tableSurface=column();
        tableSurface.setMinimumWidth(dp(1080));
        tableScroller.addView(tableSurface,new FrameLayout.LayoutParams(-2,-1));
        LinearLayout titles=row();titles.setBackground(shape(night?Color.parseColor("#223C54"):Color.parseColor("#D5E3EE"),border,7));
        tableSurface.addView(titles,layout(-1,38));
        tableCell(titles,"CALLSIGN",125,12,true,null);
        tableCell(titles,"NAME",105,12,true,null);
        tableCell(titles,"MHz",92,12,true,null);
        tableCell(titles,"MODE",80,12,true,null);
        tableCell(titles,"REFERENCE",215,12,true,null);
        tableCell(titles,"AGE",60,12,true,null);
        tableCell(titles,"PARK(S)",78,12,true,null);
        tableCell(titles,"SPOT DETAILS",0,12,true,null);
        FrameLayout frame=new FrameLayout(this);
        LinearLayout.LayoutParams frameParams=new LinearLayout.LayoutParams(-1,0,1);
        frameParams.topMargin=dp(5);
        tableSurface.addView(frame,frameParams);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        spotRows=column();scroll.addView(spotRows);
        // Empty state belongs to the visible viewport, not the minimum-width
        // horizontally scrolling table. Keep it compact and centred on all tablets.
        empty=label("",17,true);empty.setGravity(Gravity.CENTER);
        empty.setTextColor(night?Color.WHITE:0xFF183C2B);
        empty.setPadding(dp(20),dp(12),dp(20),dp(12));
        empty.setBackground(shape(night?0xF2223546:0xF5E4F0E8,night?0xFF557766:0xFF8AB39A,12));
        int viewportDp=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
        int emptyWidth=Math.max(240,Math.min(560,viewportDp-80));
        FrameLayout.LayoutParams emptyParams=new FrameLayout.LayoutParams(dp(emptyWidth),dp(100),Gravity.CENTER);
        tableViewport.addView(empty,emptyParams);
        empty.setClickable(false);empty.bringToFront();
        LinearLayout footer=row();root.addView(footer,layout(-1,24));
        String footerText="Tap a spot for details   •   Filters are remembered   •   Updates every 60 seconds   •   Help";
        SpannableString footerSpans=new SpannableString(footerText);
        int helpStart=footerText.lastIndexOf("Help");
        footerSpans.setSpan(new ClickableSpan(){
            @Override public void onClick(View widget){new HelpDialog(MainActivity.this,night).show();}
            @Override public void updateDrawState(TextPaint paint){
                paint.setColor(0xFF54E5E0);
                paint.setUnderlineText(false);
                paint.setFakeBoldText(true);
            }
        },helpStart,footerText.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        legend=label("",11,false);
        legend.setText(footerSpans);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setMovementMethod(LinkMovementMethod.getInstance());
        legend.setHighlightColor(Color.TRANSPARENT);
        whiteOverlayText(legend);
        setPadding(legend,3,0,0,0);
        footer.addView(legend,layout(-2,-1));
        TextView footerSpacer=new TextView(this);
        footer.addView(footerSpacer,new LinearLayout.LayoutParams(0,-1,1));
        TextView credit=label("by VK3FS",11,false);whiteOverlayText(credit);credit.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        footer.addView(credit,layout(94,-1));
        updateClock();renderSpots();
    }
    private void toggleKeepAwake(){
        keepAwake=!keepAwake;
        prefs.edit().putBoolean("awake",keepAwake).apply();
        applyKeepAwake();styleAwakeButton();
    }
    private void applyKeepAwake(){
        if(keepAwake)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void styleAwakeButton(){
        if(awakeButton==null)return;
        awakeButton.setText(keepAwake?"Screen On":"Screen Auto");
        awakeButton.setTextColor(night?0xFF87FCFA:0xFF065F68);
        awakeButton.setBackground(shape(keepAwake?(night?0xFF164B57:0xFFB9F2F0):(night?0xFF1D3E50:0xFFDCF7F7),
            night?0xFF54E5E0:0xFF148C97,7));
    }
    private void toggleNewSpotSound(){
        masterSoundOn=!masterSoundOn;
        prefs.edit().putBoolean("master_sound_on",masterSoundOn).apply();
        // Immediate master mute: stop both currently playing sounds as well.
        if(!masterSoundOn)stopAllAlertAudio();
        styleNewSoundButton();
    }
    private void stopAllAlertAudio(){
        if(newSpotPlayer!=null){
            try{newSpotPlayer.stop();}catch(IllegalStateException ignored){}
            newSpotPlayer.release();newSpotPlayer=null;
        }
        if(wantedPlayer!=null){
            try{wantedPlayer.stop();}catch(IllegalStateException ignored){}
            wantedPlayer.release();wantedPlayer=null;
        }
    }
    private void styleNewSoundButton(){
        if(newSoundButton==null)return;
        newSoundButton.setText(masterSoundOn?"Sound On":"Sound Off");
        newSoundButton.setTextColor(masterSoundOn?(night?0xFF9FEACA:0xFF186B4B):text);
        newSoundButton.setBackground(shape(panel,border,7));
        newSoundButton.setContentDescription(masterSoundOn?
            "Sound On. Tap to mute all alerts. Long press to test the new-spot sound.":
            "Sound Off. All alerts muted. Tap to restore your saved alert preferences.");
    }
    private void refreshOnReturn(){
        long now=System.currentTimeMillis();
        if(!loading&&(lastRefreshAttempt==0||now-lastRefreshAttempt>=20_000L)
            &&(lastLoaded==0||now-lastLoaded>=20_000L))refresh();
    }
    @Override protected void onResume(){super.onResume();
        applyKeepAwake();
        if(prefs!=null)refreshOnReturn();
    }
    private void applyImmersive(){
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus){applyImmersive();if(prefs!=null)refreshOnReturn();}
    }
    private Spinner makeSpinner(LinearLayout container,String heading,String[] options,String key,String def,int weight){
        LinearLayout wrapper=row();LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,weight);params.rightMargin=dp(8);container.addView(wrapper,params);
        TextView label=label(heading,11,true);label.setTextColor(muted);wrapper.addView(label,layout(-2,-1));addGap(wrapper,5);
        Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,options){
            private TextView itemView(int position, boolean popup){
                TextView item=new TextView(MainActivity.this);
                item.setText(options[position]);
                item.setTextColor(text);
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setSingleLine(true);
                item.setEllipsize(TextUtils.TruncateAt.END);
                item.setPadding(dp(popup?14:6),dp(popup?11:7),dp(12),dp(popup?11:7));
                if(popup)item.setBackgroundColor(panel);
                return item;
            }
            @Override public View getView(int position,View convertView,ViewGroup parent){
                return itemView(position,false);
            }
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){
                return itemView(position,true);
            }
        };
        spinner.setAdapter(adapter);spinner.setBackground(shape(panel,border,7));
        spinner.setPopupBackgroundDrawable(shape(panel,border,7));
        setPadding(spinner,7,0,5,0);
        wrapper.addView(spinner,new LinearLayout.LayoutParams(0,dp(39),1));
        String selected=prefs.getString(key,def);int index=0;
        for(int i=0;i<options.length;i++)if(options[i].equals(selected))index=i;
        spinner.setSelection(index);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){prefs.edit().putString(key,options[pos]).apply();renderSpots();}
        });
        return spinner;
    }
    /** Content-width filters: retains exactly the same saved selection and filtering behaviour. */
    private Spinner makeCompactSpinner(LinearLayout container,String heading,String[] options,String key,String def,int spinnerWidth){
        LinearLayout wrapper=row();
        LinearLayout.LayoutParams params=layout(-2,-1);params.rightMargin=dp(9);
        container.addView(wrapper,params);
        TextView headingView=label(heading,11,true);headingView.setTextColor(muted);
        wrapper.addView(headingView,layout(-2,-1));addGap(wrapper,5);
        Spinner spinner=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,options){
            private TextView itemView(int position, boolean popup){
                TextView item=new TextView(MainActivity.this);
                item.setText(options[position]);
                item.setTextColor(text);
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setSingleLine(true);
                item.setEllipsize(TextUtils.TruncateAt.END);
                item.setPadding(dp(popup?14:6),dp(popup?11:7),dp(12),dp(popup?11:7));
                if(popup)item.setBackgroundColor(panel);
                return item;
            }
            @Override public View getView(int position,View convertView,ViewGroup parent){
                return itemView(position,false);
            }
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){
                return itemView(position,true);
            }
        };
        spinner.setAdapter(adapter);spinner.setBackground(shape(panel,border,7));
        spinner.setPopupBackgroundDrawable(shape(panel,border,7));
        setPadding(spinner,7,0,5,0);
        wrapper.addView(spinner,layout(spinnerWidth,39));
        String selected=prefs.getString(key,def);int selectedIndex=0;
        for(int i=0;i<options.length;i++)if(options[i].equals(selected))selectedIndex=i;
        spinner.setSelection(selectedIndex);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                prefs.edit().putString(key,options[position]).apply();renderSpots();
            }
        });
        return spinner;
    }
    private Button action(String title,Runnable callback){Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextColor(text);b.setTextSize(12);b.setPadding(0,0,0,0);b.setBackground(shape(panel,border,7));b.setOnClickListener(v->callback.run());return b;}
    private void tableCell(LinearLayout parent,String value,int width,int sp,boolean bold,Integer colour){
        TextView v=label(value,sp,bold);v.setSingleLine(true);v.setEllipsize(TextUtils.TruncateAt.END);
        if(colour!=null)v.setTextColor(colour);
        setPadding(v,8,0,3,0);
        parent.addView(v,width==0?new LinearLayout.LayoutParams(0,-1,1):layout(width,-1));
    }
    private void updateClock(){
        if(localClock==null||utcClock==null)return;
        Date now=new Date();
        SimpleDateFormat localFormat=new SimpleDateFormat("EEE HH:mm:ss",Locale.getDefault());
        SimpleDateFormat utcFormat=new SimpleDateFormat("EEE HH:mm:ss",Locale.getDefault());
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        localClock.setText("Local  " + localFormat.format(now));
        utcClock.setText("UTC    " + utcFormat.format(now));
    }
    private int modeColour(String mode){switch(mode){case "SSB":case "USB":case "LSB":return Color.parseColor("#FF6969");case "CW":return Color.parseColor(night?"#61DFED":"#00788B");case "FT8":return Color.parseColor(night?"#B58EFF":"#7532AF");case "FT4":return Color.parseColor(night?"#58DF92":"#167D43");default:return text;}}
    private static final long[] FT8_DIAL_HZ={3573000,7074000,10136000,14074000,
        18100000,21074000,24915000,28074000};
    /** Matches desktop FT8 rule: integer Hz, nearest standard dial within ±2000 Hz. */
    private String ft8DisplayFrequency(String freq,String mode){
        String defaultDisplay=freq;
        try{defaultDisplay=String.format(Locale.US,"%.3f",Double.parseDouble(freq));}
        catch(Exception ignored){return freq;}
        if(!"FT8".equalsIgnoreCase(mode))return defaultDisplay;
        try{
            java.math.BigDecimal rawHz=new java.math.BigDecimal(freq.trim()).movePointRight(6);
            long hz=rawHz.longValueExact();
            long nearest=0;long distance=Long.MAX_VALUE;
            for(long dial:FT8_DIAL_HZ){long d=Math.abs(dial-hz);
                if(d<distance){distance=d;nearest=dial;}}
            if(distance>2000||distance==0)return defaultDisplay;
            return String.format(Locale.US,"%.3f*",nearest/1000000.0);
        }catch(Exception ignored){return defaultDisplay;}
    }
    private String noSpotsMessage(String mode,String program,String country){
        ArrayList<String> selected=new ArrayList<>();
        if(!"All".equals(program))selected.add(program);
        if(!"All".equals(mode))selected.add(mode);
        if("VK only".equals(country))selected.add("VK");
        else if("ZL only".equals(country))selected.add("ZL");
        else if("VK + ZL".equals(country))selected.add("VK/ZL");
        String prefix=TextUtils.join(" / ",selected);
        return prefix.isEmpty()?"No spots currently available.":"No "+prefix+" spots currently available.";
    }
    private void renderSpots(){
        if(spotRows==null||modeSpinner==null||countrySpinner==null||programSpinner==null||ageSpinner==null)return;
        String m=modeSpinner.getSelectedItem()==null?"All":modeSpinner.getSelectedItem().toString();
        String c=countrySpinner.getSelectedItem()==null?"All":countrySpinner.getSelectedItem().toString();
        String p=programSpinner.getSelectedItem()==null?"All":programSpinner.getSelectedItem().toString();
        int max=30;try{max=Integer.parseInt(ageSpinner.getSelectedItem().toString());}catch(Exception ignored){}
        parkCounter.resetIfNewDay();
        spotRows.removeAllViews();int visible=0;
        for(SpotFeed.Spot s:spots){if(!s.matches(m,p,c,max))continue;visible++;
            LinearLayout record=row();record.setMinimumHeight(dp(53));
            boolean wanted=isWanted(s.call);
            long spotAgeMs=System.currentTimeMillis()-s.utcMillis;
            boolean fresh=spotAgeMs>=0L&&spotAgeMs<60_000L;
            // Amber wanted highlighting always takes precedence over recent green.
            int rowFill=wanted?(night?0xF06A4D18:0xFFE4AD4F):
                fresh?(night?0xFF294336:0xFFE5F2DF):
                (visible%2==0?(night?0xEE1D2D40:0xF0F3F7FA):(night?0xEE1B2A3C:0xF0FFFFFF));
            int rowStroke=wanted?(night?0xFFFFC96D:0xFF996014):
                fresh?(night?0xFF54775F:0xFFB5D4AF):border;
            record.setBackground(shape(rowFill,rowStroke,4));
            LinearLayout.LayoutParams lp=layout(-1,54);lp.bottomMargin=dp(3);spotRows.addView(record,lp);
            tableCell(record,(wanted?"★ ":"")+s.call,125,15,true,wanted?(night?0xFFFFC96D:0xFF442500):null);
            tableCell(record,cachedName(s.call),105,13,false,null);
            String mhz=ft8DisplayFrequency(s.freq,s.mode);
            tableCell(record,mhz,92,14,true,null);
            tableCell(record,s.mode,80,14,true,modeColour(s.mode));
            tableCell(record,s.refs.isEmpty()?"—":s.refs,215,13,false,null);
            tableCell(record,s.ageMinutes()+"m",60,13,false,muted);
            // A visible park spot from just before UTC rollover still represents one park.
            int parksToday=parkCounter.count(s.call);
            if(parksToday==0&&!s.refs.trim().isEmpty())parksToday=1;
            tableCell(record,Integer.toString(parksToday),78,13,false,null);
            tableCell(record,s.detail,0,13,false,null);
            record.setOnClickListener(v->showSpot(s));
        }
        count.setText(visible+" matching  /  "+spots.size()+" received");
        refreshButton.setEnabled(!loading);
        if(loading){
            long elapsed=(System.currentTimeMillis()-feedStartedAt)/1000;
            status.setText("Feed: "+feedStage+" · "+elapsed+"s");
            status.setTextColor(night?accent:0xFF087948);
        }
        else if(error!=null){status.setText("⚠ Feed error — tap for details");status.setTextColor(0xFFFF9090);}
        else if(hasLoaded){SimpleDateFormat fmt=new SimpleDateFormat("HH:mm:ss",Locale.getDefault());status.setText("● Connected · Updated "+fmt.format(new Date(lastLoaded)));status.setTextColor(night?accent:0xFF087948);}
        else status.setText("Connecting to Parks n Peaks…");
        if(visible==0){
            if(error!=null)empty.setText("Feed unavailable\n"+error+"\nTap the red Feed error status for diagnostics, or Refresh Spots to retry");
            else if(loading&&!hasLoaded)empty.setText("Retrieving live spots…");
            else if(!hasLoaded)empty.setText("Waiting for live spots…");
            else empty.setText(noSpotsMessage(m,p,c));
            empty.setVisibility(View.VISIBLE);
            empty.bringToFront();
        }else empty.setVisibility(View.GONE);
    }
    private void showFeedDiagnostics(){
        String message="App stage: "+feedStage+"\nDetail: "+feedDetail
            +"\nElapsed: "+(feedStartedAt==0?0:(System.currentTimeMillis()-feedStartedAt)/1000)+" seconds"
            +"\nRequest active: "+loading+"\n\n"+SpotFeed.getLastDiagnostic();
        if(error!=null)message="Displayed error: "+error+"\n\n"+message;
        final String report=message;
        themedDialog().setTitle("Feed Diagnostics")
            .setMessage(report)
            .setPositiveButton("Close",null)
            .setNeutralButton("Copy Diagnostics",(dialog,which)->{
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spot Monitor diagnostics",report));
                Toast.makeText(this,"Feed diagnostics copied",Toast.LENGTH_SHORT).show();
            }).show();
    }
    private static ExecutorService newFeedWorker(){
        return Executors.newSingleThreadExecutor(new ThreadFactory(){
            @Override public Thread newThread(Runnable r){
                Thread t=new Thread(r,"ParksPeaks-Feed");
                t.setDaemon(true);return t;
            }
        });
    }
    private void feedProgress(int generation,String stage,String detail){
        handler.post(()->{
            if(generation!=feedGeneration||!loading||isFinishing()||isDestroyed())return;
            feedStage=stage;feedDetail=detail;renderSpots();
        });
    }
    private void stopFeedWatchdog(){
        if(activeWatchdog!=null){handler.removeCallbacks(activeWatchdog);activeWatchdog=null;}
    }
    private void refresh(){
        if(loading)return;
        final int generation=++feedGeneration;
        lastRefreshAttempt=System.currentTimeMillis();
        feedStartedAt=lastRefreshAttempt;
        loading=true;error=null;
        feedStage="Connecting";feedDetail="Starting feed request";
        renderSpots();
        // One worker per attempt. A timed-out worker is retired, never reused.
        feedExecutor=newFeedWorker();
        final ExecutorService thisWorker=feedExecutor;
        activeWatchdog=()->{
            if(generation!=feedGeneration||!loading)return;
            String stalled=feedStage;
            ++feedGeneration; // invalidate any late callback from this request
            loading=false;
            feedStage="Timed out";
            feedDetail="Request exceeded 30 seconds at "+stalled;
            error="Refresh timed out during "+stalled+" (30 seconds)";
            if(activeFeed!=null)activeFeed.cancel(true);
            thisWorker.shutdownNow();
            activeFeed=null;
            activeWatchdog=null;
            renderSpots();
        };
        handler.postDelayed(activeWatchdog,FEED_DEADLINE_MS);
        activeFeed=thisWorker.submit(()->{
            try {
                List<SpotFeed.Spot> received=SpotFeed.download((stage,detail)->feedProgress(generation,stage,detail));
                handler.post(()->{
                    if(generation!=feedGeneration||isFinishing()||isDestroyed())return;
                    stopFeedWatchdog();
                    loading=false;error=null;hasLoaded=true;lastLoaded=System.currentTimeMillis();
                    feedStage="Complete";feedDetail="Received "+received.size()+" spots";
                    processSpotAlerts(received);
                    parkCounter.ingest(received);
                    spots.clear();spots.addAll(received);
                    renderSpots();loadQrzNames();
                });
            }catch(Throwable exception){
                handler.post(()->{
                    if(generation!=feedGeneration||isFinishing()||isDestroyed())return;
                    stopFeedWatchdog();loading=false;
                    feedStage="Error";
                    feedDetail=exception.toString();
                    error=exception.getMessage()==null?exception.toString():exception.getMessage();
                    renderSpots();
                });
            }finally{thisWorker.shutdown();}
        });
    }
    private void showSpot(SpotFeed.Spot s){
        String when=new SimpleDateFormat("dd MMM yyyy HH:mm:ss",Locale.getDefault()).format(new Date(s.utcMillis));
        String message="QRZ first name: "+(cachedName(s.call).isEmpty()?"Not available":cachedName(s.call))+"\nFrequency: "+s.freq+" MHz\nMode: "+s.mode+"\nProgram: "+s.categories+"\nReference: "+s.refs+"\nAge: "+s.ageMinutes()+" minutes\nSpot time (local): "+when+"\nSpotter: "+s.spotter+"\n\n"+s.detail;
        AlertDialog dialog=themedDialog().setTitle(s.call).setMessage(message)
            .setPositiveButton("Close",null)
            .setNeutralButton("Copy spot",(d,w)->{
                android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText("Parks n Peaks",s.call+" "+s.freq+" MHz "+s.mode+" "+s.refs));
                Toast.makeText(this,"Spot copied",Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Show on Map",(d,w)->showSpotMap(s)).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!s.mapLinks.isEmpty()));
        dialog.show();
    }
    private void showSpotMap(SpotFeed.Spot spot){
        if(spot.mapLinks.isEmpty()){
            Toast.makeText(this,"No map link for this spot",Toast.LENGTH_SHORT).show();return;
        }
        if(spot.mapLinks.size()==1){openSpotMap(spot.mapLinks.get(0).url);return;}
        String[] choices=new String[spot.mapLinks.size()];
        for(int i=0;i<choices.length;i++)choices[i]=spot.mapLinks.get(i).reference;
        themedDialog().setTitle("Show on Map — choose reference")
            .setItems(choices,(dialog,which)->openSpotMap(spot.mapLinks.get(which).url))
            .setNegativeButton("Cancel",null).show();
    }
    private void openSpotMap(String url){
        if(!SpotFeed.isTrustedMapUrl(url))return;
        try {startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}
        catch(Exception e){Toast.makeText(this,"No web browser available",Toast.LENGTH_LONG).show();}
    }
    private void showHfConditions(){
        if(hfConditions==null)hfConditions=new HfConditionsDialog(this);
        hfConditions.show();
    }
    /** Tap the existing Theme button to change modes; long-press for Auto settings. */
    private void toggleTheme(){
        autoTheme=false;
        night=!night;
        prefs.edit().putBoolean("auto_theme",false).putBoolean("night",night).apply();
        buildUi();
    }
    /** Test the ordinary alert directly; long-press the Sound On/Off button. */
    private void testNewSpotSound(){
        if(!masterSoundOn){
            Toast.makeText(this,"Sound Off — all alerts are muted",Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this,"Testing new-spot alert",Toast.LENGTH_SHORT).show();
        playNewSpotSound();
    }
    private void showSettings(){
        LinearLayout layout=column();setPadding(layout,22,14,22,8);
        CheckBox auto=new CheckBox(this);auto.setText("Automatic day/night theme (07:00–18:00)");auto.setChecked(autoTheme);auto.setTextColor(text);layout.addView(auto);
        CheckBox dark=new CheckBox(this);dark.setText("Night theme when automatic is off");dark.setChecked(night);dark.setTextColor(text);layout.addView(dark);
        CheckBox awake=new CheckBox(this);awake.setText("Keep tablet screen awake");awake.setChecked(keepAwake);awake.setTextColor(text);layout.addView(awake);
        CheckBox normalAlert=new CheckBox(this);
        normalAlert.setText("Play sound for new spots (when Sound On)");
        normalAlert.setChecked(newSoundEnabled);
        normalAlert.setTextColor(text);
        layout.addView(normalAlert);
        themedDialog().setTitle("Spot Monitor settings (long-press Theme)").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
            autoTheme=auto.isChecked();night=autoTheme?isNight():dark.isChecked();keepAwake=awake.isChecked();
            newSoundEnabled=normalAlert.isChecked();
            prefs.edit().putBoolean("auto_theme",autoTheme).putBoolean("night",night)
                .putBoolean("awake",keepAwake)
                .putBoolean("normal_spot_sound_enabled",newSoundEnabled).apply();buildUi();
        }).show();
    }
    private String normaliseWanted(String raw){
        String value=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);
        return value.replaceFirst("/(P|M|MM|AM|QRP|PORTABLE|MOBILE)$","");
    }
    private boolean isWanted(String call){
        return wantedEnabled&&wantedCalls.contains(normaliseWanted(call));
    }
    private String spotId(SpotFeed.Spot spot){
        return spot.call+"|"+spot.freq+"|"+spot.mode+"|"+spot.refs+"|"+spot.utcMillis;
    }
    /** Sounds only for genuinely new, recent spots, never the initial/backfilled feed. */
    private void processSpotAlerts(List<SpotFeed.Spot> received){
        boolean wantedNew=false, ordinaryNew=false;
        // Match the displayed Mode, Country and Program, not just Mode.
        // The feed is worldwide, so otherwise unseen overseas spots cause
        // a normal gong even when the user is monitoring VK only.
        String selectedMode=prefs.getString("mode","All");
        String selectedCountry=prefs.getString("country","All");
        String selectedProgram=prefs.getString("program","All");
        for(SpotFeed.Spot spot:received){
            String key=spotId(spot);
            boolean firstSight=observedSpots.add(key);
            if(!wantedBaselineReady||!firstSight||spot.ageMinutes()>5)continue;
            if(isWanted(spot.call)){
                if(wantedSound)wantedNew=true;
            }else if(newSoundEnabled&&spot.matches(selectedMode,selectedProgram,selectedCountry,5)){
                ordinaryNew=true;
            }
        }
        wantedBaselineReady=true;
        if(observedSpots.size()>5000){
            observedSpots.clear();for(SpotFeed.Spot spot:received)observedSpots.add(spotId(spot));
        }
        if(!masterSoundOn)return;
        if(wantedNew)playWantedSound();
        else if(ordinaryNew)playNewSpotSound();
    }
    private void playNewSpotSound(){
        if(!masterSoundOn)return;
        try{
            if(newSpotPlayer!=null){newSpotPlayer.release();newSpotPlayer=null;}
            AssetFileDescriptor afd=getAssets().openFd("new_spot_alert.wav");
            MediaPlayer player=new MediaPlayer();
            player.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
            afd.close();
            player.setOnCompletionListener(mp->{mp.release();if(newSpotPlayer==mp)newSpotPlayer=null;});
            player.prepare();newSpotPlayer=player;player.start();
        }catch(Exception ex){
            Toast.makeText(this,"New-spot sound error: "+ex.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
        }
    }
    private void playWantedSound(){
        if(!masterSoundOn){
            Toast.makeText(this,"Sound Off — wanted alerts are muted",Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if(wantedPlayer!=null){wantedPlayer.release();wantedPlayer=null;}
            AssetFileDescriptor afd=getAssets().openFd("Wanted_Station_On_Air.wav");
            MediaPlayer player=new MediaPlayer();
            player.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
            afd.close();
            player.setOnCompletionListener(mp->{mp.release();if(wantedPlayer==mp)wantedPlayer=null;});
            player.prepare();wantedPlayer=player;player.start();
        }catch(Exception ex){Toast.makeText(this,"Wanted alert sound could not play",Toast.LENGTH_SHORT).show();}
    }
    private void showWantedStations(){
        LinearLayout body=column();setPadding(body,20,12,20,8);
        CheckBox enabled=new CheckBox(this);enabled.setText("Monitor wanted callsigns");enabled.setChecked(wantedEnabled);
        enabled.setTextColor(text);
        enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(night?0xFFFFC96D:0xFF704000));
        body.addView(enabled,layout(-1,46));
        CheckBox sound=new CheckBox(this);sound.setText("Play sound when a wanted station appears");sound.setChecked(wantedSound);
        sound.setTextColor(text);
        sound.setButtonTintList(android.content.res.ColorStateList.valueOf(night?0xFFFFC96D:0xFF704000));
        body.addView(sound,layout(-1,46));
        TextView instructions=label("One callsign per line. Portable /P, /M etc. match the base callsign.",13,false);
        instructions.setTextColor(muted);body.addView(instructions,layout(-1,53));
        EditText entries=new EditText(this);
        entries.setTextColor(text);entries.setHintTextColor(muted);entries.setHint("VK3ABC\nVK2XYZ");
        entries.setBackgroundTintList(android.content.res.ColorStateList.valueOf(night?0xFF9CB5D0:0xFF526C83));
        entries.setTextSize(16);entries.setMinLines(5);entries.setMaxLines(10);
        entries.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ArrayList<String> sorted=new ArrayList<>(wantedCalls);java.util.Collections.sort(sorted);
        entries.setText(TextUtils.join("\n",sorted));
        body.addView(entries,layout(-1,190));
        AlertDialog wantedDialog=themedDialog().setTitle("★ Wanted Stations")
            .setView(body).setNeutralButton("Test sound",(d,w)->playWantedSound())
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save",null).create();
        wantedDialog.setOnShowListener(dialog->{
                AlertDialog alert=(AlertDialog)dialog;
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                    Set<String> updated=new HashSet<>();
                    for(String line:entries.getText().toString().split("[\n,;]+")){
                        String call=line.trim().toUpperCase(Locale.ROOT);
                        if(call.isEmpty())continue;
                        if(!call.matches("[A-Z0-9/]{3,20}")){
                            entries.setError("Invalid callsign: "+call);
                            Toast.makeText(this,"Check callsign: "+call,Toast.LENGTH_LONG).show();return;
                        }
                        updated.add(normaliseWanted(call));
                    }
                    wantedCalls.clear();wantedCalls.addAll(updated);
                    wantedEnabled=enabled.isChecked();wantedSound=sound.isChecked();
                    prefs.edit().putStringSet("wanted_calls",new HashSet<>(wantedCalls))
                        .putBoolean("wanted_enabled",wantedEnabled)
                        .putBoolean("wanted_sound",wantedSound).apply();
                    styleWantedButton();
                    renderSpots();alert.dismiss();
                });
            });
        wantedDialog.show();
    }

    private String cachedName(String call){
        String key=QrzLookup.keyFor(call);
        NameEntry entry=nameCache.get(key);
        return entry==null?"":entry.name;
    }
    private void loadQrzNames(){
        if(!qrz.configured()||qrzLoading)return;
        ArrayList<String> todo=new ArrayList<>();
        long now=System.currentTimeMillis();
        for(SpotFeed.Spot spot:spots){
            String key=QrzLookup.keyFor(spot.call);
            if(key.isEmpty()||todo.contains(key))continue;
            NameEntry entry=nameCache.get(key);
            if(entry==null || now-entry.when>(entry.name.isEmpty()?86400000L:180L*86400000L))todo.add(key);
        }
        if(todo.isEmpty())return;
        qrzLoading=true;
        qrzExecutor.execute(()->{
            String errorText="";
            for(String call:todo){
                if(!qrz.configured())break;
                try{
                    String first=qrz.firstName(call);
                    runOnUiThread(()->{nameCache.put(call,new NameEntry(first,System.currentTimeMillis()));renderSpots();});
                }catch(Exception e){errorText=e.getMessage()==null?"QRZ lookup failed":e.getMessage();break;}
            }
            final String finishedError=errorText;
            runOnUiThread(()->{qrzLoading=false;qrzMessage=finishedError;if(!finishedError.isEmpty())Toast.makeText(this,finishedError,Toast.LENGTH_LONG).show();});
        });
    }
    private void showQrzSettings(){
        LinearLayout form=column();setPadding(form,22,14,22,8);
        TextView info=label("Optional QRZ XML subscription. The tablet retrieves operator first names directly from QRZ. Passwords are encrypted using Android Keystore.",13,false);
        form.addView(info,layout(-1,-2));
        EditText user=new EditText(this);user.setSingleLine(true);user.setHint("QRZ username (callsign)");user.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        user.setTextColor(text);user.setHintTextColor(muted);user.setText(qrz.username());form.addView(user,layout(-1,-2));
        EditText pass=new EditText(this);pass.setSingleLine(true);pass.setHint(qrz.configured()?"Password saved — leave blank to keep":"QRZ password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);pass.setTextColor(text);pass.setHintTextColor(muted);form.addView(pass,layout(-1,-2));
        if(!qrzMessage.isEmpty()){
            TextView errorText=label(qrzMessage,12,false);errorText.setTextColor(night?0xFFFF9999:0xFFAF2626);form.addView(errorText,layout(-1,-2));
        }
        AlertDialog dialog=themedDialog().setTitle("QRZ First Names")
            .setView(form).setNegativeButton("Cancel",null)
            .setNeutralButton("Disable QRZ",(d,w)->{
                qrz.clearCredentials();nameCache.clear();qrzMessage="";styleQrzButton();renderSpots();
            }).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String username=user.getText().toString().trim();String password=pass.getText().toString();
                if(username.isEmpty()){user.setError("Enter QRZ username");return;}
                if(password.isEmpty()&&qrz.configured()&&username.equalsIgnoreCase(qrz.username())){dialog.dismiss();loadQrzNames();return;}
                if(password.isEmpty()){pass.setError("Enter QRZ password");return;}
                try{
                    qrz.saveCredentials(username,password);nameCache.clear();qrzMessage="";
                    styleQrzButton();dialog.dismiss();loadQrzNames();
                }catch(Exception e){pass.setError("Unable to securely store credentials");}
            });
        });
        dialog.show();
    }

}
