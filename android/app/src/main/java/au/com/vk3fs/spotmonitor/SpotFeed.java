package au.com.vk3fs.spotmonitor;

import android.text.Html;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of the read-only parsing rules in Windows v1.3.28 spot_feed.py. */
public final class SpotFeed {
    public static final String URL = "https://new.parksnpeaks.org/spot_panel/?zone=all";
    static final Pattern SCRIPT = Pattern.compile("\\$\\(\\s*['\"]#spot_panel['\"]\\s*\\)\\.html\\(\\s*(\"(?:\\\\.|[^\"\\\\])*\")", Pattern.DOTALL);
    static final Pattern TABLE = Pattern.compile("(?is)<table\\b(?=[^>]*\\bid\\s*=\\s*['\"]?spot_table['\"]?)[^>]*>(.*?)</table>");
    static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*?\\bhref\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");
    static final Pattern TR = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    static final Pattern TD = Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>");
    static final Pattern DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\b");
    static final Pattern FREQ = Pattern.compile("\\s*(\\d{1,5}(?:\\.\\d{1,6})?)(?:\\s*-\\s*([A-Za-z0-9]*))?\\s*");
    static final Pattern REFS = Pattern.compile("\\b(?:[A-Z0-9]{1,5}/[A-Z0-9]{1,5}-\\d{1,5}|AUS-\\d{4}|AU-\\d+|VKFF-\\d+|NZ-\\d+|ZLFF-\\d+|VK-[A-Z0-9]{2,12}|[A-Z]{2}-\\d+)\\b",Pattern.CASE_INSENSITIVE);
    static final Pattern CATEGORY = Pattern.compile("POTA|WWFF|SOTA|SIOTA|ZLOTA|LLOTA|AUSPARKS",Pattern.CASE_INSENSITIVE);
    // Diagnostics are intentionally limited to public feed data. Do not collect QRZ secrets.
    private static volatile String lastDiagnostic="No feed request has started yet.";
    public interface ProgressListener { void stage(String stage, String detail); }
    private static void checkInterrupted() throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Feed request cancelled");
    }
    private static void report(ProgressListener listener,String stage,String detail){
        lastDiagnostic="Time: "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date())
            +"\nSource: "+URL+"\nStage: "+stage+"\nDetail: "+detail
            +"\nTable diagnostics: "+lastTableDebug;
        if(listener!=null)listener.stage(stage,detail);
    }
    public static String getLastDiagnostic(){return lastDiagnostic;}
    private static String sample(String value){
        if(value==null)return "(none)";
        String preview=value.substring(0,Math.min(320,value.length()));
        return preview.replace('\r',' ').replace('\n',' ').replace('\t',' ')
            .replaceAll("[\\p{Cntrl}]", " ");
    }
    private static String diagnostic(int status,String type,String content,String stage,String detail){
        String lower=content==null?"":content.toLowerCase(Locale.ROOT);
        return "Time: "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date())
            +"\nSource: "+URL
            +"\nHTTP: "+status
            +"\nContent-Type: "+(type==null?"unknown":type)
            +"\nResponse characters: "+(content==null?0:content.length())
            +"\nContains spot_table: "+lower.contains("spot_table")
            +"\nContains <table: "+lower.contains("<table")
            +"\nContains #spot_panel: "+lower.contains("#spot_panel")
            +"\nStage: "+stage
            +"\nDetail: "+detail
            +"\nTable diagnostics: "+lastTableDebug
            +"\nResponse preview: "+sample(content);
    }
    private SpotFeed() {}

    public static final class MapLink {
        public final String reference,url;
        MapLink(String reference,String url){this.reference=reference;this.url=url;}
    }
    public static boolean isTrustedMapUrl(String value){
        try{
            URI uri=new URI(value);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1)return false;
            String host=uri.getHost(),path=uri.getRawPath();
            if(host==null)return false;
            if(host.equalsIgnoreCase("new.parksnpeaks.org"))return path!=null&&path.startsWith("/assets/");
            if(host.equalsIgnoreCase("pota.app"))return (uri.getRawFragment()!=null&&uri.getRawFragment().startsWith("/park/"));
            return false;
        }catch(URISyntaxException e){return false;}
    }
    private static String trustedUrl(String href){
        try{
            String decoded=Html.fromHtml(href,Html.FROM_HTML_MODE_LEGACY).toString();
            String url=new URI("https://new.parksnpeaks.org/").resolve(decoded).toString();
            return isTrustedMapUrl(url)?url:null;
        }catch(Exception e){return null;}
    }
    public static class Spot {
        public final String call, freq, mode, refs, detail, categories, spotter;
        public final long utcMillis;
        public final List<MapLink> mapLinks;
        Spot(String call,String freq,String mode,String refs,String detail,String categories,String spotter,long utcMillis,List<MapLink> links) {
            this.call=call;this.freq=freq;this.mode=mode;this.refs=refs;this.detail=detail;this.categories=categories;this.spotter=spotter;this.utcMillis=utcMillis;
            this.mapLinks=links;
        }
        public long ageMinutes(){return Math.max(0L,(System.currentTimeMillis()-utcMillis)/60000L);}
        public boolean matches(String modeFilter,String program,String country,int ageMax) {
            if(ageMinutes()>ageMax) return false;
            if(!modeFilter.equals("All")&&!mode.equals(modeFilter)&&!(modeFilter.equals("SSB")&&(mode.equals("LSB")||mode.equals("USB")))) return false;
            if(country.equals("VK only")&&!call.startsWith("VK")) return false;
            if(country.equals("ZL only")&&!call.startsWith("ZL")) return false;
            if(country.equals("VK + ZL")&&!(call.startsWith("VK")||call.startsWith("ZL"))) return false;
            if(program.equals("All")) return true;
            if(program.equals("AUSParks")) return refs.matches("(?is).*\\bAUS-\\d{4}\\b.*") || categories.contains("AUSPARKS");
            return categories.contains(program.toUpperCase(Locale.ROOT));
        }
    }

    public static List<Spot> download() throws Exception {return download(null);}
    public static List<Spot> download(ProgressListener listener) throws Exception {
        HttpURLConnection conn=null;
        int status=-1;
        String contentType="unknown",response="";
        try {
            report(listener,"Connecting","Opening Parks n Peaks worldwide feed");
            checkInterrupted();
            conn=(HttpURLConnection)new URL(URL+"&_="+System.currentTimeMillis()).openConnection();
            conn.setRequestMethod("GET");conn.setConnectTimeout(12000);conn.setReadTimeout(15000);
            // Match the Windows desktop client headers; mobile browser imitation can
            // produce a different server response at some intermediaries.
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept","*/*");
            conn.setRequestProperty("Referer","https://new.parksnpeaks.org/spots");
            conn.setRequestProperty("X-Requested-With","XMLHttpRequest");
            status=conn.getResponseCode();
            contentType=conn.getContentType();
            report(listener,"Downloading","HTTP "+status+"; Content-Type: "+contentType);
            InputStream input=status>=200&&status<400?conn.getInputStream():conn.getErrorStream();
            if(input!=null){
                try(InputStream in=input;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;
                    while((n=in.read(buffer))!=-1){
                        checkInterrupted();
                        bytes.write(buffer,0,n);
                        if(bytes.size()>2_000_000)throw new Exception("Feed exceeded 2 MB safety limit");
                    }
                    response=bytes.toString(StandardCharsets.UTF_8.name());
                }
            }
            if(status!=200)throw new Exception("HTTP "+status+" from feed");
            if(response.isEmpty())throw new Exception("Feed returned an empty response");
            try {
                report(listener,"Parsing","Downloaded "+response.length()+" characters; finding spot table");
                checkInterrupted();
                lastTableDebug="Parsing response";
                List<Spot> parsed=parse(response,listener);
                lastDiagnostic=diagnostic(status,contentType,response,"Complete","Parsed "+parsed.size()+" spots");
                if(listener!=null)listener.stage("Complete","Parsed "+parsed.size()+" spots");
                return parsed;
            }catch(Exception parsing){
                lastDiagnostic=diagnostic(status,contentType,response,"Parsing error",parsing.toString());
                throw parsing;
            }
        }catch(Exception problem){
            if(!lastDiagnostic.contains("Stage: Parsing error")||!lastDiagnostic.contains("Detail: "+problem.toString())){
                lastDiagnostic=diagnostic(status,contentType,response,"Request",problem.toString());
            }
            throw problem;
        }finally{if(conn!=null)conn.disconnect();}
    }
    // Parks n Peaks sends a JavaScript response containing HTML snippets.
    // Use bounded linear scans, not nested regular expressions on the whole
    // 80KB+ feed: such regexes can stall on large/malformed replies.
    private static volatile String lastTableDebug="Not yet parsed";
    private static final int MAX_FRAGMENTS=64;
    private static final int MAX_TABLES=512;

    private static String normaliseEscapedMarkup(String value){
        // Some responses contain an additional escaping layer around the HTML.
        for(int i=0;i<2;i++){
            String updated=value.replace("\\\\\\\"", "\"")
                .replace("\\\"", "\"").replace("\\'", "'")
                .replace("\\/", "/").replace("\\u003c", "<")
                .replace("\\u003C", "<").replace("\\u003e", ">")
                .replace("\\u003E", ">");
            if(updated.equals(value))break;
            value=updated;
        }
        return value;
    }

    private static List<String> candidateFragments(String response) throws Exception {
        List<String> fragments=new ArrayList<>();
        String lower=response.toLowerCase(Locale.ROOT);
        int pos=0, calls=0, decoded=0;
        while(calls<MAX_FRAGMENTS){
            checkInterrupted();
            int found=lower.indexOf(".html",pos);
            if(found<0)break;
            pos=found+5;
            int at=pos;
            while(at<response.length()&&Character.isWhitespace(response.charAt(at)))at++;
            if(at>=response.length()||response.charAt(at)!='(')continue;
            at++;
            while(at<response.length()&&Character.isWhitespace(response.charAt(at)))at++;
            if(at>=response.length())break;
            char quote=response.charAt(at);
            if(quote!='\"'&&quote!='\'')continue;
            calls++;
            int begin=at, j=at+1;
            boolean escaped=false;
            while(j<response.length()){
                if((j&1023)==0)checkInterrupted();
                char ch=response.charAt(j++);
                if(escaped){escaped=false;continue;}
                if(ch=='\\'){escaped=true;continue;}
                if(ch==quote)break;
            }
            if(j>response.length()||response.charAt(j-1)!=quote)continue;
            pos=j;
            String literal=response.substring(begin,j);
            try{
                Object value=new JSONTokener(literal).nextValue();
                if(value instanceof String){fragments.add((String)value);decoded++;}
            }catch(Exception ignored){
                // JSON permits double quotes only. Keep single-quoted JavaScript
                // snippets and normalise their simple quote escapes as fallback.
                if(quote=='\''){
                    fragments.add(literal.substring(1,literal.length()-1)
                        .replace("\\'", "'").replace("\\\\", "\\"));
                    decoded++;
                }
            }
        }
        // Direct HTML is also supported. Never send the full JavaScript response
        // through Html.fromHtml(): the live worldwide feed can exceed 700k chars
        // and Android's HTML renderer can stall for many seconds on that input.
        fragments.add(response);
        lastTableDebug="JavaScript .html calls: "+calls+", decoded: "+decoded
            +", candidate fragments: "+fragments.size()+" (no whole-response HTML rendering)";
        return fragments;
    }

    private static String selectTable(String response) throws Exception {
        List<String> fragments=candidateFragments(response);
        int scanned=0;
        String firstTag="(none)";
        for(String original:fragments){
            checkInterrupted();
            String html=normaliseEscapedMarkup(original);
            // A fragment may contain entity-escaped tags; decode only that
            // fragment with linear replacements, never render the full feed.
            if(original != response && html.contains("&lt;table")) {
                html=html.replace("&lt;", "<").replace("&gt;", ">");
                html=html.replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&amp;", "&");
            }
            String lower=html.toLowerCase(Locale.ROOT);
            int pos=0;
            while(scanned<MAX_TABLES){
                checkInterrupted();
                int open=lower.indexOf("<table",pos);
                if(open<0)break;
                int boundary=open+6;
                if(boundary<lower.length()&&Character.isLetterOrDigit(lower.charAt(boundary))){
                    pos=boundary;continue;
                }
                int tagEnd=lower.indexOf('>',boundary);
                if(tagEnd<0)break;
                scanned++;
                String tag=html.substring(open,tagEnd+1);
                if(scanned==1)firstTag=sample(tag);
                pos=tagEnd+1;
                // Restrict the attribute inspection to the opening tag, never
                // search the entire page using an unbounded pattern.
                if(!tag.toLowerCase(Locale.ROOT).contains("spot_table"))continue;
                String noQuotes=tag.replace("\\\"", "\"").replace("\\'", "'");
                if(!noQuotes.matches("(?is).*\\bid\\s*=\\s*['\"]?spot_table['\"]?(?:\\s|/|>).*"))continue;
                int close=lower.indexOf("</table",tagEnd+1);
                if(close<0)throw new Exception("Spot table opening found but closing </table> missing");
                lastTableDebug+="; tables scanned: "+scanned+"; found: "+sample(tag);
                return html.substring(tagEnd+1,close);
            }
        }
        lastTableDebug+="; tables scanned: "+scanned+"; first tag: "+firstTag
            +"; marker: "+vicinity(response,"spot_table");
        throw new Exception("Spot table not found in spot feed (see Feed Diagnostics)");
    }
    private static String vicinity(String content,String marker){
        int at=content.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if(at<0)return "(not found)";
        return sample(content.substring(Math.max(0,at-100),Math.min(content.length(),at+200)));
    }
    public static List<Spot> parse(String response) throws Exception {return parse(response,null);}
    private static List<Spot> parse(String response,ProgressListener listener) throws Exception {
        checkInterrupted();
        String html=selectTable(response);
        report(listener,"Parsing rows","Table found; HTML length "+html.length());
        Matcher tr=TR.matcher(html);

        List<List<String>> rows=new ArrayList<>(); List<List<String>> rowLinks=new ArrayList<>();
        while(tr.find()){
            if((rows.size()&15)==0)checkInterrupted();
            Matcher td=TD.matcher(tr.group(1)); List<String> cells=new ArrayList<>();
            List<String> links=new ArrayList<>();Matcher anchors=LINK.matcher(tr.group(1));
            while(anchors.find())links.add(anchors.group(1));
            while(td.find()) {
                String text=td.group(1).replaceAll("(?i)<br\\s*/?>"," ");
                text=Html.fromHtml(text,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();
                cells.add(text);
            }
            if(!cells.isEmpty()){rows.add(cells);rowLinks.add(links);}
        }
        List<Spot> result=new ArrayList<>(); HashSet<String> seen=new HashSet<>();
        SimpleDateFormat dateFormat=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));dateFormat.setLenient(false);
        long now=System.currentTimeMillis();
        for(int i=0;i+1<rows.size();i+=2){
            if((i&31)==0)checkInterrupted();
            List<String> main=rows.get(i),detail=rows.get(i+1);
            if(main.size()<6||detail.isEmpty())continue;
            String category=main.get(0).toUpperCase(Locale.ROOT),rawTime=main.get(1),call=main.get(2).replaceAll("\\s+","").toUpperCase(Locale.ROOT),rawRefs=main.get(3),spotter=main.get(4),rawFm=main.get(5);
            if(!CATEGORY.matcher(category).find()&&!Pattern.compile("\\bAUS-\\d{4}\\b",Pattern.CASE_INSENSITIVE).matcher(rawRefs).find())continue;
            Matcher dt=DATE.matcher(rawTime), fm=FREQ.matcher(rawFm);
            if(!dt.find()||!fm.matches()||!call.matches("[A-Z0-9/]{3,15}"))continue;
            Date time=dateFormat.parse(dt.group()); if(time==null)continue;
            double age=(now-time.getTime())/60000.0;
            if(age < -5 || age > 120)continue; // parse safety cap; UI controls visible age
            double freq=Double.parseDouble(fm.group(1));if(freq<1.8||freq>10500)continue;
            String mode=fm.group(2)==null||fm.group(2).isEmpty()?"UNKNOWN":fm.group(2).toUpperCase(Locale.ROOT);
            Matcher refMatch=REFS.matcher(rawRefs);
            List<String> wwff=new ArrayList<>(),pota=new ArrayList<>(),other=new ArrayList<>();
            while(refMatch.find()) {
                String ref=refMatch.group().toUpperCase(Locale.ROOT);
                List<String> target=ref.startsWith("VKFF-")||ref.startsWith("ZLFF-")?wwff:ref.startsWith("AU-")||ref.startsWith("NZ-")?pota:other;
                if(!target.contains(ref))target.add(ref);
            }
            List<String> refs=new ArrayList<>();refs.addAll(wwff);refs.addAll(pota);refs.addAll(other);
            String reference=String.join(" / ",refs);
            String name=detail.get(0).split("\\s+-\\s+|\\s*→",2)[0].replaceAll("\\s*(?:\\[[^\\]]+\\]|\\{[^}]+\\})","").trim();
            if(name.isEmpty())name=detail.get(0);
            String dedupe=time.getTime()+"|"+call+"|"+freq+"|"+mode+"|"+reference;
            if(!seen.add(dedupe))continue;
            if(reference.matches("(?i).*\\bAUS-\\d{4}\\b.*"))category+=" AUSPARKS";
            List<MapLink> maps=new ArrayList<>();
            for(String ref:refs){
                for(String href:rowLinks.get(i)){
                    String mapUrl=trustedUrl(href);
                    if(mapUrl==null)continue;
                    String token=href.toUpperCase(Locale.ROOT).replace('_','/');
                    if(token.contains(ref)&&maps.stream().noneMatch(link->link.reference.equals(ref))){
                        maps.add(new MapLink(ref,mapUrl));break;
                    }
                }
            }
            result.add(new Spot(call,String.format(Locale.US,"%.6f",freq).replaceAll("0+$","").replaceAll("\\.$",""),mode,reference,name,category,spotter,time.getTime(),maps));
        }
        return result;
    }
}
