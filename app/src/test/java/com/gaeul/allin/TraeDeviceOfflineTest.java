package com.gaeul.allin;
import org.json.JSONObject;
import java.security.Signature;
import java.nio.charset.StandardCharsets;
public class TraeDeviceOfflineTest {
 public static void main(String[] args)throws Exception {
  JSONObject a=TraeDevice.create(); TraeDevice.validate(a); JSONObject p=TraeDevice.proofOf("/path","client","secret",a.getString("devicePrivateKey"),123,"abcd");
  Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(TraeDevice.publicKey(a.getString("devicePublicKey")));s.update("POST\n/path\nclient\nsecret\n123\nabcd".getBytes(StandardCharsets.UTF_8)); if(!s.verify(TraeDevice.unb64(p.getString("Signature"))))throw new AssertionError("proof");
  JSONObject b=TraeDevice.create(); a.put("devicePublicKey",b.getString("devicePublicKey"));try{TraeDevice.validate(a);throw new AssertionError("mismatched keys");}catch(AssertionError e){throw e;}catch(Exception expected){} System.out.println("TraeDeviceOfflineTest OK");
 }
}
