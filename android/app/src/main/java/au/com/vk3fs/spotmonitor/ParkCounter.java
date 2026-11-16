package au.com.vk3fs.spotmonitor;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Session-only count of distinct parks spotted per activator on the current UTC day. */
final class ParkCounter {
    private final Map<String,List<Set<String>>> parks=new HashMap<>();
    private String utcDay="";
    ParkCounter(){resetIfNewDay();}
    private static String utcDate(long millis){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US);
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR)+"-"+(c.get(Calendar.MONTH)+1)+"-"+c.get(Calendar.DAY_OF_MONTH);
    }
    void resetIfNewDay(){
        String today=utcDate(System.currentTimeMillis());
        if(!today.equals(utcDay)){utcDay=today;parks.clear();}
    }
    private static String baseCall(String call){
        if(call==null)return "";
        return call.toUpperCase(Locale.ROOT).replaceFirst("/(P|M|MM|AM|QRP|PORTABLE|MOBILE)$","");
    }
    void ingest(List<SpotFeed.Spot> spots){
        resetIfNewDay();
        for(SpotFeed.Spot spot:spots){
            if(!utcDay.equals(utcDate(spot.utcMillis)))continue;
            Set<String> identity=new HashSet<>();
            if(!spot.refs.isEmpty()){
                for(String ref:spot.refs.split("\\s*/\\s*")){
                    String value=ref.trim().toUpperCase(Locale.ROOT);
                    if(!value.isEmpty())identity.add("REF:"+value);
                }
            }
            String name=spot.detail==null?"":spot.detail.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+"," ");
            if(!name.isEmpty())identity.add("NAME:"+name);
            if(identity.isEmpty())continue;
            List<Set<String>> groups=parks.computeIfAbsent(baseCall(spot.call),k->new ArrayList<>());
            boolean merged;
            do{
                merged=false;
                for(int i=groups.size()-1;i>=0;i--){
                    Set<String> group=groups.get(i);
                    boolean overlap=false;
                    for(String token:identity)if(group.contains(token)){overlap=true;break;}
                    if(overlap){identity.addAll(group);groups.remove(i);merged=true;}
                }
            }while(merged);
            groups.add(identity);
        }
    }
    int count(String call){resetIfNewDay();List<Set<String>> groups=parks.get(baseCall(call));return groups==null?0:groups.size();}
}
