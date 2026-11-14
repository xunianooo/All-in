package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.text.ParsePosition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TimeZone;

/** Account operations; the caller supplies an authenticated, bounded transport. */
public final class RaccoonAccountService {
    public interface Transport {
        /** Return complete JSON; throw Failure on HTTP errors. Do not follow redirects with credentials. */
        JSONObject request(String method, String path, boolean desktopHeaders) throws IOException;
    }
    public static final class Failure extends IOException {
        public final boolean expired;
        public Failure(String message, boolean expired) { super(message); this.expired = expired; }
    }
    private final Transport transport;
    public RaccoonAccountService(Transport transport) { if (transport == null) throw new IllegalArgumentException(); this.transport = transport; }
    private JSONObject get(String method, String path, boolean desktop) throws IOException {
        JSONObject response = transport.request(method, path, desktop);
        if (response == null) throw new Failure("Raccoon response missing", false);
        if (response.has("code")) {
            Object raw=response.opt("code");int code;
            if(raw instanceof Number){double n=((Number)raw).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<0||n>Integer.MAX_VALUE)throw new Failure("Raccoon invalid business code",false);code=(int)n;}
            else if(raw instanceof String&&((String)raw).matches("[0-9]{1,9}"))code=Integer.parseInt((String)raw);
            else throw new Failure("Raccoon invalid business code",false);
            if (code != 0) throw new Failure("Raccoon API error " + code, code == 200001 || code == 200003);
        }
        JSONObject data = response.optJSONObject("data");
        return data == null ? response : data;
    }
    private static Long points(JSONObject body) {
        Object x = body.opt("available_points");
        if (x == null || x == JSONObject.NULL || x instanceof Boolean) return null;
        try { double n = Double.parseDouble(x.toString()); return !Double.isNaN(n) && !Double.isInfinite(n) && n >= 0 && n < Long.MAX_VALUE ? (long)Math.floor(n) : null; }
        catch (NumberFormatException e) { return null; }
    }
    /** setting_info may grant daily credits even when called as a balance fallback. */
    public long balance() throws IOException {
        IOException first;
        try { Long n = points(get("GET", "/api/web/points/v1/balance", false)); if (n != null) return n; first = new Failure("Raccoon balance missing", false); }
        catch (Failure e) { if (e.expired) throw e; first = e; }
        catch (IOException e) { first = e; }
        try { Long n = points(get("GET", "/api/web/office/v3/setting_info", false)); if (n != null) return n; }
        catch (Failure e) { if (e.expired) throw e; }
        catch (IOException ignored) { }
        throw first;
    }
    static final int BILL_PAGE_SIZE=20, BILL_MAX_PAGES=5;
    static String billsPath(int offset) {
        if(offset<0||offset>=BILL_PAGE_SIZE*BILL_MAX_PAGES||offset%BILL_PAGE_SIZE!=0)throw new IllegalArgumentException("Invalid bill page");
        return "/api/web/points/v1/bills?paging.limit=20&paging.offset="+offset;
    }
    static boolean billsPathAllowed(String path) {
        for(int i=0;i<BILL_MAX_PAGES;i++)if(billsPath(i*BILL_PAGE_SIZE).equals(path))return true;
        return false;
    }
    private static final Pattern BILL_DATE=Pattern.compile("^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:[0-9]{2})$");
    static Long billTime(String value) {
        Matcher m=BILL_DATE.matcher(value==null?"":value);if(!m.matches())return null;
        String zone=m.group(3);if(!"Z".equals(zone)){int h=Integer.parseInt(zone.substring(1,3)),min=Integer.parseInt(zone.substring(4));if(h>18||min>59||(h==18&&min!=0))return null;}
        String fraction=m.group(2)==null?"000":(m.group(2)+"000").substring(0,3),text=m.group(1)+"."+fraction+zone;
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US);f.setLenient(false);
        ParsePosition pos=new ParsePosition(0);Date date=f.parse(text,pos);return date!=null&&pos.getIndex()==text.length()?date.getTime():null;
    }
    private static Long integerPoints(Object value) {
        if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long){long n=((Number)value).longValue();return n>=0?n:null;}
        if(value instanceof Number){double n=((Number)value).doubleValue();return Double.isFinite(n)&&n==Math.rint(n)&&n>=0&&n<(double)Long.MAX_VALUE?(long)n:null;}
        try {if(value instanceof String&&((String)value).matches("[0-9]{1,19}"))return Long.parseLong((String)value);}catch(NumberFormatException ignored){}
        return null;
    }
    private static Long dailyPoints(JSONObject item,long now) {
        if(!"daily_grant".equals(item.optString("biz_type")))return null;
        Long when=billTime(item.optString("created_at"));if(when==null||!CheckinCenter.day(now).equals(CheckinCenter.day(when)))return null;
        Long n=integerPoints(item.opt("points"));return n!=null&&n>0?n:null;
    }
    private static final class Bills {
        final Map<String,JSONObject> items=new LinkedHashMap<>();int pages,rows;boolean complete;
        long today(long now,Set<String> previous)throws IOException {
            long total=0;for(Map.Entry<String,JSONObject> entry:items.entrySet()){
                if(previous!=null&&previous.contains(entry.getKey()))continue;
                Long n=dailyPoints(entry.getValue(),now);if(n==null)continue;
                if(Long.MAX_VALUE-total<n)throw new Failure("Raccoon grant overflow",false);total+=n;
            }return total;
        }
    }
    private Bills readBills()throws IOException {
        Bills result=new Bills();long deadline=System.nanoTime()+15000000000L;
        for(int page=0;page<BILL_MAX_PAGES;page++){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)break;
            CheckinEvidence.query();JSONArray entries=get("GET",billsPath(page*BILL_PAGE_SIZE),false).optJSONArray("items");
            if(entries==null||entries.length()>BILL_PAGE_SIZE)throw new Failure("Raccoon bills malformed",false);
            result.pages++;result.rows+=entries.length();int before=result.items.size();boolean malformed=false;
            for(int i=0;i<entries.length();i++){JSONObject item=entries.optJSONObject(i);if(item==null){malformed=true;continue;}if(billTime(item.optString("created_at"))==null||item.optString("biz_type").isEmpty())malformed=true;Long number=integerPoints(item.opt("points"));if("daily_grant".equals(item.optString("biz_type"))&&(number==null||number<=0))malformed=true;result.items.putIfAbsent(key(item),item);}
            if(entries.length()<BILL_PAGE_SIZE){result.complete=!malformed;break;}
            if(malformed||result.items.size()==before)break;
        }return result;
    }
    private static JSONObject billResult(Bills bills,long now)throws IOException {
        long points=bills.today(now,null);
        JSONObject out=new JSONObject().put("phase","status").put("claimPossible",false).put("pagesRead",bills.pages).put("billsRead",bills.items.size()).put("rowsRead",bills.rows).put("coverageComplete",bills.complete);
        if(points>0)return out.put("outcome","done").put("verified",true).put("evidenceType","official_daily_grant").put("credit",points).put("message","官方账单已确认今日签到奖励，无需重复领取");
        return out.put("outcome","unknown").put("reasonCode",bills.complete?"no_today_record":"bill_window_incomplete").put("message","已核对最近 "+bills.items.size()+" 条账单，但暂未找到今天的签到奖励记录。"+(bills.complete?"可稍后查询，或到官方客户端查看。":"当前查询范围仍有限，请到官方客户端核对；不会重复领取。"));
    }
    /** A successful HTTP call or unchanged balance is never reward evidence. */
    public JSONObject checkIn(long nowMillis) throws IOException {
        CheckinEvidence.prepare();CheckinEvidence.query();Bills before;
        try{before=readBills();}catch(IOException error){return CheckinEvidence.failure(error);}
        JSONObject known=billResult(before,nowMillis);
        if(known.optBoolean("verified")||!before.complete)return known;
        CheckinEvidence.claim();get("GET","/api/web/office/v3/setting_info",true);CheckinEvidence.confirm();
        Bills after;try{after=readBills();}catch(IOException error){return CheckinEvidence.failure(error);}
        long added=after.today(nowMillis,before.items.keySet());JSONObject result=billResult(after,nowMillis).put("claimPossible",true);
        if(added>0)return result.put("outcome","claimed").put("verified",true).put("credit",added).put("message","官方新增账单已确认签到奖励到账");
        if(result.optBoolean("verified"))return result;
        return result.put("reasonCode","claim_not_confirmed").put("message","请求已完成，但账单还没有可核验的今日奖励。可稍后查询状态；今天不会重复领取。");
    }
    public JSONObject checkinStatus(long nowMillis) throws IOException {
        CheckinEvidence.prepare();CheckinEvidence.query();return billResult(readBills(),nowMillis);
    }
    private static String key(JSONObject item) {
        String id=item.optString("bill_id", ""); if (id.isEmpty()) id=item.optString("biz_id", "");
        return id.isEmpty() ? item.optString("created_at", "") + ":" + item.optString("points", "") + ":" + item.optString("event_name", "") : id;
    }
    /** Explicit opt-in: the POST may claim a one-time reward. Persist settlement per account. */
    public JSONObject claimDesktopOnboarding() throws IOException {
        JSONObject data=get("POST", "/api/web/desktop/v1/login/points/grant", true);
        if (!(data.opt("granted") instanceof Boolean)) return new JSONObject().put("settled", false);
        boolean granted=data.optBoolean("granted"); JSONObject result=new JSONObject().put("settled", true).put("granted", granted);
        if (granted) { JSONObject popup=data.optJSONObject("popup"); if (popup != null && popup.optLong("points",0)>0) result.put("credit",popup.optLong("points")); }
        return result;
    }
}
