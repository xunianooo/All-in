package com.gaeul.allin;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Extracts only provider-documented model multipliers; never token prices. */
public final class ModelBillingMetadata {
    private ModelBillingMetadata() {}
    private static final Pattern WORK = Pattern.compile("(?i)^\\s*[x×]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:credits)?\\s*$");
    private static final Pattern LOOMY = Pattern.compile("(?i)\\(\\s*x\\s*(\\d+(?:\\.\\d+)?)\\s*\\)\\s*$");
    private static final Pattern CAT = Pattern.compile("(?i)^\\s*(?:[x×]\\s*)?(\\d+(?:\\.\\d+)?)(?:\\s*[x×])?\\s*$");

    private static Double number(Object o, boolean strings) {
        if (!(o instanceof Number) && !(strings && o instanceof String && !((String)o).trim().isEmpty())) return null;
        try { double n = o instanceof Number ? ((Number)o).doubleValue() : Double.parseDouble(((String)o).trim());
            return Double.isFinite(n) && n >= 0 ? n : null;
        } catch (RuntimeException e) { return null; }
    }
    private static Object first(JSONObject o, String a, String b) {
        if (o == null) return null;
        Object v=o.opt(a); return v==null || v==JSONObject.NULL ? o.opt(b) : v;
    }
    private static JSONObject object(Object o) {
        if(o instanceof JSONObject) return (JSONObject)o;
        if(o instanceof String) try { return new JSONObject((String)o); } catch(Exception ignored) { }
        return null;
    }
    private static Double match(Object o, Pattern p) {
        if (!(o instanceof String)) return null;
        Matcher m=p.matcher((String)o); return m.find() ? number(m.group(1),true) : null;
    }
    /** Deep-copy raw and attach billing when an official model multiplier is present. */
    public static JSONObject normalize(String provider, JSONObject raw) throws Exception {
        JSONObject out=raw==null ? new JSONObject() : new JSONObject(raw.toString());
        if(raw==null || provider==null) return out;
        String p=provider.toLowerCase(Locale.ROOT); Double rate=null, original=null;
        String source=null; JSONObject schedule=null;
        switch(p) {
        case "qoder": {
            Object price=first(raw,"price_factor","priceFactor");
            rate=number(price,false); source=raw.has("price_factor")?"price_factor":"priceFactor";
            JSONObject promo=object(first(raw,"promotion","prommotion"));
            Double before=number(first(promo,"before_promotion_price_factor","beforePromotionPriceFactor"),false);
            Double discount=number(first(promo,"discount_factor","discountFactor"),false);
            if(rate!=null && promo!=null && Boolean.TRUE.equals(promo.opt("active")) && before!=null && before>0) {
                if(rate==0) {
                    if(discount==null) { rate=null; }
                    else { rate=before*discount; source="promotion.discount_factor"; }
                }
                if(rate!=null && before>rate) original=before;
            } else if(rate!=null) {
                Double was=number(first(raw,"original_price_factor","originalPriceFactor"),false);
                if(was!=null && was>rate) original=was;
            }
            // is_free alone is not a numeric multiplier. Invalid explicit prices do not imply free.
            break;
        }
        case "trae": case "trae-cn": case "trae-international": {
            JSONObject cfg=object(raw.opt("display_contact_config"));
            if(cfg==null) break;
            JSONObject consumption=cfg.optJSONObject("consumption_rate");
            JSONObject data=consumption==null?null:consumption.optJSONObject("data");
            if(consumption!=null && !Boolean.FALSE.equals(consumption.opt("enable")) && data!=null) rate=number(data.opt("rate"),true);
            source="display_contact_config.consumption_rate.data.rate";
            JSONObject disc=cfg.optJSONObject("discount"), dd=disc==null?null:disc.optJSONObject("data");
            if(disc!=null && Boolean.TRUE.equals(disc.opt("enable")) && dd!=null) {
                Double after=number(dd.opt("consumption_rate"),true), before=number(dd.opt("original_consumption_rate"),true);
                if(after!=null){rate=after;source="display_contact_config.discount.data.consumption_rate";}
                if(before!=null && rate!=null && before>rate)original=before;
            }
            JSONObject activity=cfg.optJSONObject("activity_discount"), ad=activity==null?null:activity.optJSONObject("data");
            JSONObject off=ad==null?null:ad.optJSONObject("off_peak");
            if(activity!=null && Boolean.TRUE.equals(activity.opt("enable")) && off!=null && off.optJSONArray("time_windows")!=null) {
                // Persist the official schedule, never freeze the rate observed inside a window.
                schedule=new JSONObject(activity.toString());
                Double base=number(off.opt("before_consumption_rate"),true);
                if(base!=null){rate=base;source="display_contact_config.activity_discount.data.off_peak.before_consumption_rate";original=null;}
                else {rate=null;original=null;}
            }
            break;
        }
        case "workbuddy": case "workbuddy-ai": rate=match(raw.opt("credits"),WORK);source="credits";break;
        case "raccoon": {
            Double base=number(raw.opt("billing_multiplier"),true), eff=number(raw.opt("billing_effective_multiplier"),true);
            rate=eff!=null?eff:base;source=eff!=null?"billing_effective_multiplier":"billing_multiplier";
            if(rate==null) { rate=number(raw.opt("multiplier"),false); source="saved_catalog.multiplier"; }
            if(eff!=null && base!=null && base>eff)original=base;break;
        }
        case "catpaw": {
            JSONObject ext=object(raw.opt("extendedInfo")); Object v=ext==null?null:ext.opt("rateMultiplier");
            rate=v instanceof String?match(v,CAT):number(v,false);
            if(v instanceof String && ((String)v).trim().matches("(?i)^[x×].*[x×]$"))rate=null;source="extendedInfo.rateMultiplier";break;
        }
        case "loomy": rate=match(raw.opt("name"),LOOMY);source="name";if(rate==null){rate=match(raw.opt("display_name"),LOOMY);source="display_name";}break;
        case "copilot": case "github-copilot": {
            JSONObject pricing=raw.optJSONObject("pricing"); JSONObject premium=pricing==null?null:pricing.optJSONObject("premium_requests");
            rate=premium==null?null:number(premium.opt("multiplier"),true);source="pricing.premium_requests.multiplier";break;
        }
        default: return out;
        }
        JSONObject previousBilling=raw.optJSONObject("billing");
        Object observed=previousBilling==null?null:previousBilling.opt("observedAt");
        if (!(observed instanceof Number) || !Double.isFinite(((Number)observed).doubleValue()) || ((Number)observed).doubleValue()<=0) observed=raw.opt("catalogObservedAt");
        if (!(observed instanceof Number) || !Double.isFinite(((Number)observed).doubleValue()) || ((Number)observed).doubleValue()<=0) observed=null;
        // Normalized fields are owned by this adapter, not inherited from an unrelated source.
        out.remove("billing"); out.remove("billingSchedule"); out.remove("multiplier"); out.remove("free");
        if(schedule!=null)out.put("billingSchedule",new JSONObject().put("source","display_contact_config.activity_discount").put("activity",schedule));
        if(rate!=null && Double.isFinite(rate)) {
            JSONObject billing=new JSONObject().put("kind","multiplier").put("value",rate).put("source",source).put("scope","model");
            if(observed!=null)billing.put("observedAt",observed);else billing.put("sourceObservation","saved_catalog");
            if(original!=null)billing.put("original",original);
            if(schedule!=null)billing.put("rateRole","base");
            out.put("billing",billing);
            if(schedule==null) out.put("multiplier",rate).put("free",rate==0);
        } else if("qoder".equals(p) && !raw.has("price_factor") && !raw.has("priceFactor") && Boolean.TRUE.equals(first(raw,"is_free","isFree"))) {
            out.put("free",true);
        }
        return out;
    }
}
