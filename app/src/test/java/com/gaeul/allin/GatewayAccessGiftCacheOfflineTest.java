package com.gaeul.allin;
import org.json.*;
public final class GatewayAccessGiftCacheOfflineTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args) {
  final long[] now={1000}; final int[] calls={0};
  ZCodeGiftControl.Transport transport=new ZCodeGiftControl.Transport(){
   public JSONObject summary(String jwt,String device){calls[0]++;return new JSONObject().put("ok",true).put("claimable",new JSONArray()).put("buckets",new JSONArray());}
   public JSONObject claim(String jwt,String device,String id){throw new AssertionError("claim");}
  };
  ZCodeGiftControl.setTestHooks(()->now[0],transport);
  try {
   check(ZCodeGiftControl.peekSummary("jwt","dev")==null && calls[0]==0,"peek is read only");
   ZCodeGiftControl.summary("jwt","dev"); check(calls[0]==1 && ZCodeGiftControl.peekSummary("jwt","dev")!=null,"fresh");
   now[0]=600999; check(ZCodeGiftControl.peekSummary("jwt","dev")!=null,"inside ttl");
   now[0]=601000; check(ZCodeGiftControl.peekSummary("jwt","dev")==null && calls[0]==1,"expiry and no request");
   now[0]=999; check(ZCodeGiftControl.peekSummary("jwt","dev")==null,"clock rollback");
  } finally {ZCodeGiftControl.setTestHooks(null,null);}
  System.out.println("GatewayAccessGiftCacheOfflineTest PASS");
 }
}
