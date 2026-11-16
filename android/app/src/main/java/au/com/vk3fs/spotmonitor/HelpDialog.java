package au.com.vk3fs.spotmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Offline Help & About guide. No network calls and no changes to monitoring state. */
final class HelpDialog {
    private final Activity activity;
    private final boolean night;
    private final int foreground, subdued, surface, outline, accent;
    private LinearLayout topics;
    private int openIndex=0;

    private static final String[] HEADINGS={
        "Getting started",
        "Filters & spot list",
        "Spot colours & frequency marks",
        "Wanted Stations",
        "Sound & master mute",
        "Theme & screen controls",
        "Spot details & maps",
        "QRZ Names",
        "HF Conditions",
        "Feed status & troubleshooting",
        "What's new in v1.0.21",
        "About"
    };

    private static final String[] CONTENT={
        "Parks n Peaks Spot Monitor displays incoming spots from the worldwide Parks n Peaks feed. The table refreshes automatically about once per minute. Tap Refresh Spots to request an update immediately. Returning to the app after waking the tablet also requests fresh spots when appropriate. An internet connection is required for live spots; the Help guide works offline.",
        "MODE selects the operating mode. COUNTRY filters by VK, ZL or all regions. PROGRAM selects POTA, WWFF, SOTA and other supported programs. MAX AGE limits how old a spot may be. Filters are remembered after the app closes.\n\nThe spot table shows callsign, QRZ first name (if enabled), frequency in MHz, mode, reference, age, Park(s) count and spot details. Tap a row for more information. If nothing matches your filters, a message such as 'No FT8 / VK spots currently available.' appears. This is different from a feed connection error.",
        "Recent spots under one minute old have a subtle green row background. Wanted Stations have an amber background and take priority over the green highlight. Mode names use their own colours.\n\nFor FT8, frequencies within 2 kHz of a standard FT8 dial frequency are displayed at the standard frequency with an asterisk (*). For example, a posted 14.075 MHz spot may display as 14.074*. The asterisk means the display was adjusted; it does not alter the original feed data.\n\nPark(s) shows the number of distinct parks counted for an activator for the current UTC day. Repeated spots from the same park do not add to the count. The daily count resets at 00:00 UTC; a visible spot with a valid park reference still shows at least one.",
        "Tap Wanted Stations to maintain your personal callsign list and enable monitoring. Enter one callsign per line, then Save. Portable suffixes such as /P and /M match the base callsign.\n\nMatching spots are highlighted amber. Wanted Station monitoring checks incoming spots even if your table filters hide them. The Wanted Stations dialog also has a separate 'Play sound' option and a sound test. The main Sound Off button overrides all audio alerts but not the amber highlighting.",
        "Sound On/Off is the MASTER mute for the whole app. Sound Off immediately stops and silences both ordinary new-spot gongs and Wanted Stations alerts. Sound On restores your previously chosen individual sound preferences; it does not force every alert type on.\n\nLong-press the Sound button to test the new-spot gong (only while Sound On). The ordinary new-spot alert uses the supplied Metal Hit Gong sound. A normal alert is for genuinely new, recent spots matching the active display filters; previously seen or initial-load spots should not sound. The Wanted Stations sound is configured separately in Wanted Stations.\n\nFor quiet overnight monitoring, select Sound Off. The selection is remembered.",
        "Tap the existing Theme button to change between Day and Night immediately. Long-press Theme to open Settings. In Settings, 'Automatic day/night theme' switches by the tablet's local time: Day from 07:00 to 18:00, Night otherwise. A manual tap disables automatic switching until you re-enable it in Settings.\n\nThe cyan Screen Auto / Screen On button controls the tablet's normal screen timeout while Spot Monitor is on screen. Screen On keeps the display awake; Screen Auto allows it to sleep. Landscape orientation is used for the tablet layout, with horizontal table scrolling on smaller displays.\n\nSettings also contains the individual normal new-spot sound preference.",
        "Tap a spot row to open its details. Copy Spot copies the spot information to the clipboard. Show on Map opens a supported reference link supplied with that spot in your tablet's web browser. If there is no usable location link, the map may be unavailable. Map links can open an external website and require internet access.",
        "QRZ Names optionally looks up operators' first names using QRZ's XML data service. Tap QRZ Names to configure your QRZ callsign and password, or disable name lookups. QRZ XML access may require an eligible subscription. Names may take a little time to populate. The app stores QRZ credentials using Android's secure storage rather than putting them in the source code.",
        "Tap HF Conditions to view space-weather and ionospheric information, including Bureau of Meteorology products. You can refresh those maps independently from the spot table. Map availability depends on the external service and the tablet's internet connection.",
        "The feed status reports Connecting, Downloading, Parsing, Connected or Feed Error. Tap the status text (even during Refreshing) for Feed Diagnostics, which shows the current stage, HTTP result and parser details. Copy Diagnostics lets you share the report when troubleshooting.\n\nIf a refresh fails, previous successfully received spots may remain visible; look at the status to tell whether they are current. Try Refresh Spots again. If the app stays busy, the refresh watchdog should report a timeout and permit recovery. Don't mistake 'No spots currently available' for a network failure.",
        "v1.0.21 refines the Help & About experience.\n\n• Help moved from the main control row to a cyan link in the lower-left footer.\n• Website link added to What's New and About.\n• Existing offline, expandable help topics remain available.\n\nThe worldwide feed, parser, QRZ, Wanted Stations, audio, filters and table behaviour are unchanged.",
        "Parks n Peaks Spot Monitor\nAndroid v1.0.21\nby VK3FS\n\nRead-only, landscape-first tablet application for monitoring Parks n Peaks spots. No radio CAT or HaLo connection is required. Designed primarily for the Lenovo TB336FU and other Android tablets.\n\nThis is an independent monitoring application. Spot data and HF-condition information originate from their respective external services."
    };

    HelpDialog(Activity activity, boolean night){
        this.activity=activity;
        this.night=night;
        foreground=Color.parseColor(night?"#F5FAFF":"#172B40");
        subdued=Color.parseColor(night?"#B8CBDA":"#51697D");
        surface=Color.parseColor(night?"#203247":"#EAF1F6");
        outline=Color.parseColor(night?"#405B72":"#B7C9D8");
        accent=Color.parseColor(night?"#7DE3AD":"#14774E");
    }

    private int dp(float value){return (int)(activity.getResources().getDisplayMetrics().density*value+0.5f);}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String content,float sp,boolean bold,int color){
        TextView v=new TextView(activity);
        v.setText(content);v.setTextColor(color);v.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
        if(bold)v.setTypeface(null,Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    private GradientDrawable box(){
        GradientDrawable d=new GradientDrawable();d.setColor(surface);d.setCornerRadius(dp(8));d.setStroke(dp(1),outline);return d;
    }
    void show(){
        LinearLayout body=vertical();body.setPadding(dp(14),dp(8),dp(14),dp(10));
        TextView intro=text("Tap a topic to expand its instructions. This guide works without internet.",13,false,subdued);
        intro.setPadding(dp(4),dp(4),dp(4),dp(12));body.addView(intro);
        ScrollView scroller=new ScrollView(activity);
        scroller.setFillViewport(false);
        topics=vertical();scroller.addView(topics,new ScrollView.LayoutParams(-1,-2));
        body.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        populate();
        AlertDialog dialog=new AlertDialog.Builder(activity,night?
            android.R.style.Theme_Material_Dialog_Alert:
            android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Help & About — v1.0.21")
            .setView(body)
            .setPositiveButton("Close",null)
            .create();
        dialog.setOnShowListener(ignored->{
            android.view.Window window=dialog.getWindow();
            if(window!=null){
                android.util.DisplayMetrics dm=activity.getResources().getDisplayMetrics();
                int width=Math.min(dm.widthPixels-dp(36),dp(760));
                int height=Math.min(dm.heightPixels-dp(46),dp(570));
                window.setLayout(width,height);
            }
        });
        dialog.show();
    }
    private void populate(){
        topics.removeAllViews();
        for(int i=0;i<HEADINGS.length;i++){
            final int index=i;
            LinearLayout panel=vertical();panel.setBackground(box());
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
            params.bottomMargin=dp(7);topics.addView(panel,params);
            TextView heading=text((openIndex==i?"▾  ":"▸  ")+HEADINGS[i],15,true,foreground);
            heading.setPadding(dp(14),dp(9),dp(14),dp(9));
            heading.setMinHeight(dp(44));
            heading.setOnClickListener(v->{openIndex=openIndex==index?-1:index;populate();});
            panel.addView(heading);
            if(openIndex==i){
                TextView detail=text(CONTENT[i],13,false,foreground);
                detail.setGravity(Gravity.TOP);
                detail.setLineSpacing(dp(3),1.0f);
                detail.setPadding(dp(16),dp(1),dp(16),dp(15));
                panel.addView(detail,new LinearLayout.LayoutParams(-1,-2));
                if(i==10 || i==11){
                    TextView website=text("3fs.net.au",14,true,night?0xFF54E5E0:0xFF006A78);
                    website.setPaintFlags(website.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                    website.setPadding(dp(16),0,dp(16),dp(16));
                    website.setContentDescription("Visit 3fs.net.au in web browser");
                    website.setOnClickListener(v->{
                        android.content.Intent browser=new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://3fs.net.au"));
                        try { activity.startActivity(browser); }
                        catch(android.content.ActivityNotFoundException ex){
                            android.widget.Toast.makeText(activity,"No web browser available",android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    panel.addView(website,new LinearLayout.LayoutParams(-1,-2));
                }
            }
        }
    }
}
