package au.com.vk3fs.spotmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import java.util.regex.Pattern;

/** Optional QRZ XML first-name lookup. Password stored under Android Keystore AES-GCM. */
public final class QrzLookup {
    private static final String ENDPOINT="https://xmldata.qrz.com/xml/current/";
    private static final String ALIAS="vk3fs_spotmonitor_qrz_v1";
    private static final Pattern CALL_PATTERN=Pattern.compile("[A-Z]{1,3}[0-9][A-Z0-9]{1,5}");
    private final SharedPreferences prefs;
    private String session="";
    public QrzLookup(Context context){prefs=context.getSharedPreferences("qrz_account",Context.MODE_PRIVATE);}
    public String username(){return prefs.getString("username","");}
    public boolean configured(){return !username().isEmpty() && prefs.contains("password_cipher");}
    public synchronized void saveCredentials(String username,String password) throws Exception {
        String enc=encrypt(password);
        prefs.edit().putString("username",username.trim().toUpperCase(Locale.US))
            .putString("password_cipher",enc).apply();session="";
    }
    public synchronized void clearCredentials(){prefs.edit().clear().apply();session="";}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        SecretKey existing=(SecretKey)ks.getKey(ALIAS,null);
        if(existing!=null)return existing;
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());return kg.generateKey();
    }
    private String encrypt(String plaintext) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=c.getIV();byte[] ct=c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(iv.length);out.write(iv);out.write(ct);
        return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    private String password() throws Exception {
        byte[] raw=Base64.decode(prefs.getString("password_cipher",""),Base64.NO_WRAP);
        if(raw.length<15)throw new Exception("QRZ credentials need to be entered again");
        int n=raw[0]&255;if(n<12||n>16||raw.length<=n+1)throw new Exception("Invalid QRZ password storage");
        byte[] iv=new byte[n];System.arraycopy(raw,1,iv,0,n);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
        return new String(c.doFinal(raw,1+n,raw.length-n-1),StandardCharsets.UTF_8);
    }
    private static String normalize(String call){
        String cleaned=call.toUpperCase(Locale.US).replace(" ","");
        for(String part:cleaned.split("/"))if(CALL_PATTERN.matcher(part).matches())return part;
        return CALL_PATTERN.matcher(cleaned).matches()?cleaned:"";
    }
    public static String keyFor(String call){return normalize(call);}
    private static String tag(Document d,String name){NodeList l=d.getElementsByTagNameNS("*",name);if(l.getLength()==0)l=d.getElementsByTagName(name);return l.getLength()==0?"":l.item(0).getTextContent().trim();}
    private static String enc(String s) throws Exception{return URLEncoder.encode(s,"UTF-8");}
    private Document request(String params) throws Exception {
        HttpsURLConnection con=(HttpsURLConnection)new URL(ENDPOINT).openConnection();
        try {
            con.setConnectTimeout(8000);con.setReadTimeout(8000);con.setDoOutput(true);con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
            con.setRequestProperty("User-Agent","VK3FS-Android-SpotMonitor/1.0.5");
            try(OutputStream stream=con.getOutputStream()){stream.write(params.getBytes(StandardCharsets.UTF_8));}
            if(con.getResponseCode()!=200)throw new Exception("QRZ HTTP "+con.getResponseCode());
            byte[] bytes;
            try(InputStream stream=con.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;while((n=stream.read(b))!=-1){out.write(b,0,n);if(out.size()>250000)throw new Exception("QRZ response too large");}
                bytes=out.toByteArray();
            }
            // Android's bundled XML parser may not support Xerces feature names.
            // Reject document type declarations before parsing; QRZ XML does not need them.
            String xmlText=new String(bytes,StandardCharsets.UTF_8);
            String upper=xmlText.toUpperCase(Locale.ROOT);
            if(upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
                throw new Exception("QRZ returned an unsupported XML document type");
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            try {f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);}
            catch(javax.xml.parsers.ParserConfigurationException ignored) { /* Not available on every Android parser */ }
            javax.xml.parsers.DocumentBuilder builder=f.newDocumentBuilder();
            builder.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(bytes));
        }finally{con.disconnect();}
    }
    private void login() throws Exception {
        Document d=request("username="+enc(username())+"&password="+enc(password())+"&agent=VK3FS-Android-SpotMonitor");
        session=tag(d,"Key");
        if(session.isEmpty())throw new Exception("QRZ: "+message(d,"Login failed"));
    }
    private static String message(Document d,String fallback){String error=tag(d,"Error");if(!error.isEmpty())return error;String info=tag(d,"Message");return info.isEmpty()?fallback:info;}
    public synchronized String firstName(String callsign) throws Exception {
        String call=normalize(callsign);if(call.isEmpty()||!configured())return "";
        if(session.isEmpty())login();
        Document d=request("s="+enc(session)+"&callsign="+enc(call));
        if(tag(d,"Key").isEmpty()){session="";login();d=request("s="+enc(session)+"&callsign="+enc(call));}
        String first=tag(d,"fname");
        if(first.isEmpty()){
            String err=tag(d,"Error");
            // Lookup misses aren't fatal; entitlement/auth failures are surfaced.
            if(!err.isEmpty() && !err.toLowerCase(Locale.US).contains("not found"))throw new Exception("QRZ: "+err);
            String msg=tag(d,"Message");
            if(msg.toLowerCase(Locale.US).contains("subscription"))throw new Exception("QRZ: "+msg);
            return "";
        }
        return first.split("\\s+")[0].trim();
    }
}
