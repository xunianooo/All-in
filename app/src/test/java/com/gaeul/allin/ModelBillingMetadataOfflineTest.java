package com.gaeul.allin;
import org.json.*;

public final class ModelBillingMetadataOfflineTest {
 private static JSONObject n(String p,String raw)throws Exception{return ModelBillingMetadata.normalize(p,new JSONObject(raw));}
 private static void rate(JSONObject o,double v,String source)throws Exception {
  JSONObject b=o.getJSONObject("billing");
  if(b.getDouble("value")!=v || !source.equals(b.getString("source")) || !"model".equals(b.getString("scope")) || !"multiplier".equals(b.getString("kind")) || o.getDouble("multiplier")!=v || o.getBoolean("free")!=(v==0))throw new AssertionError(o.toString());
 }
 private static void unknown(JSONObject o){if(o.has("billing")||o.has("multiplier"))throw new AssertionError(o.toString());}
 public static void main(String[] args)throws Exception {
  JSONObject raw=new JSONObject("{\"key\":\"model12\",\"price_factor\":0.5,\"is_free\":true,\"original_price_factor\":1,\"context_config\":{\"x\":{\"token_count\":12}}}");
  JSONObject q=ModelBillingMetadata.normalize("qoder",raw);rate(q,.5,"price_factor");
  if(q.getBoolean("free")||q.getJSONObject("billing").getDouble("original")!=1||!q.getString("key").equals("model12"))throw new AssertionError(q.toString());
  q.getJSONObject("context_config").getJSONObject("x").put("token_count",99);
  if(raw.getJSONObject("context_config").getJSONObject("x").getInt("token_count")!=12)throw new AssertionError("not copied");
  q=n("qoder","{\"price_factor\":0,\"promotion\":{\"active\":true,\"before_promotion_price_factor\":2,\"discount_factor\":0.4}}"); rate(q,.8,"promotion.discount_factor");if(q.getJSONObject("billing").getDouble("original")!=2)throw new AssertionError();
  rate(n("qoder","{\"price_factor\":0,\"original_price_factor\":0.1}"),0,"price_factor");
  unknown(n("qoder","{\"price_factor\":\"NaN\",\"is_free\":true}"));
  unknown(n("qoder","{}"));if(!n("qoder","{\"is_free\":true}").getBoolean("free"))throw new AssertionError();
  JSONObject trae=n("trae","{\"display_contact_config\":{\"consumption_rate\":{\"data\":{\"rate\":\"0.78\"}},\"discount\":{\"enable\":true,\"data\":{\"consumption_rate\":\"0.58\",\"original_consumption_rate\":\"0.78\"}}}}");rate(trae,.58,"display_contact_config.discount.data.consumption_rate");
  trae=n("trae","{\"display_contact_config\":\"{\\\"consumption_rate\\\":{\\\"data\\\":{\\\"rate\\\":0}}}\"}");rate(trae,0,"display_contact_config.consumption_rate.data.rate");
  trae=n("trae","{\"display_contact_config\":{\"consumption_rate\":{\"data\":{\"rate\":0}},\"activity_discount\":{\"enable\":true,\"data\":{\"off_peak\":{\"before_consumption_rate\":1,\"after_consumption_rate\":0,\"time_zone\":\"Asia/Shanghai\",\"time_windows\":[{\"weekdays\":[1],\"start_minute\":0,\"end_minute\":60}]}}}}}");
  if(!trae.has("billingSchedule") || trae.has("multiplier") || trae.has("free") || trae.getJSONObject("billing").getDouble("value")!=1 || !"base".equals(trae.getJSONObject("billing").getString("rateRole")))throw new AssertionError(trae.toString());
  unknown(n("trae","{\"display_contact_config\":{\"consumption_rate\":{\"enable\":false,\"data\":{\"rate\":1}}}}"));
  rate(n("workbuddy","{\"credits\":\"X0.03\"}"),.03,"credits");unknown(n("workbuddy","{\"credits\":1.5}"));
  rate(n("raccoon","{\"billing_multiplier\":2,\"billing_effective_multiplier\":0}"),0,"billing_effective_multiplier");
  rate(n("catpaw","{\"extendedInfo\":\"{\\\"rateMultiplier\\\":\\\"2x\\\"}\"}"),2,"extendedInfo.rateMultiplier");
  rate(n("loomy","{\"name\":\"Model (x12)\"}"),12,"name");
  rate(n("copilot","{\"pricing\":{\"premium_requests\":{\"multiplier\":0.5}}}"),.5,"pricing.premium_requests.multiplier");
  unknown(n("workbuddy","{\"price_factor\":12,\"credits\":\"xNaN\"}"));unknown(n("qoder","{\"pricing\":{\"input\":1}}"));unknown(n("catpaw","{\"extendedInfo\":{\"rateMultiplier\":\"Infinity\"}}"));
  rate(n("catpaw","{\"extendedInfo\":{\"rateMultiplier\":\"1.5×\"}}"),1.5,"extendedInfo.rateMultiplier");
  rate(n("catpaw","{\"extendedInfo\":{\"rateMultiplier\":\"×1.5\"}}"),1.5,"extendedInfo.rateMultiplier");
  unknown(n("catpaw","{\"extendedInfo\":{\"rateMultiplier\":\"x1.5x\"}}"));
  rate(n("workbuddy","{\"credits\":\"×1.5 credits\"}"),1.5,"credits");
  rate(n("workbuddy","{\"credits\":\"x2 credits\"}"),2,"credits");
  unknown(n("workbuddy","{\"credits\":\"cost x2 credits now\"}"));
  unknown(n("workbuddy","{\"credits\":\"2 credits\"}"));
  rate(n("loomy","{\"name\":\"No rate\",\"display_name\":\"Model (x2)\"}"),2,"display_name");
  JSONObject old=n("qoder","{\"price_factor\":1,\"catalogObservedAt\":1234,\"billing\":{\"observedAt\":123}} ");
  if(old.getJSONObject("billing").getLong("observedAt")!=123)throw new AssertionError(old.toString());
  JSONObject again=ModelBillingMetadata.normalize("qoder",old);
  if(again.getJSONObject("billing").getLong("observedAt")!=123)throw new AssertionError(again.toString());
  JSONObject observed=n("qoder","{\"price_factor\":1,\"catalogObservedAt\":1234}");
  if(observed.getJSONObject("billing").getLong("observedAt")!=1234)throw new AssertionError(observed.toString());
  JSONObject missing=n("qoder","{\"price_factor\":1}").getJSONObject("billing");
  if(missing.has("observedAt")||!"saved_catalog".equals(missing.getString("sourceObservation")))throw new AssertionError(missing.toString());
  System.out.println("ModelBillingMetadataOfflineTest OK");
 }
}
