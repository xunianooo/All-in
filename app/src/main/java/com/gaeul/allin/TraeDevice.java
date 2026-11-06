package com.gaeul.allin;

import org.json.JSONObject;
import java.security.*;
import java.security.spec.*;
import java.nio.charset.StandardCharsets;

/** Persist this entire identity with the account; never log its private key. */
public final class TraeDevice {
    private static final SecureRandom RNG = new SecureRandom();
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private TraeDevice() {}
    static String b64(byte[] a) {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<a.length;i+=3) { int n=(a[i]&255)<<16; if(i+1<a.length)n|=(a[i+1]&255)<<8; if(i+2<a.length)n|=a[i+2]&255;
            b.append(B64.charAt(n>>>18)).append(B64.charAt((n>>>12)&63)).append(i+1<a.length?B64.charAt((n>>>6)&63):'=').append(i+2<a.length?B64.charAt(n&63):'='); }
        return b.toString();
    }
    static byte[] unb64(String s) throws Exception {
        s=s.replaceAll("\\s",""); if(s.length()%4!=0 || !s.matches("[A-Za-z0-9+/]*={0,2}")) throw new Exception("invalid base64");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        for(int i=0;i<s.length();i+=4) { int a=B64.indexOf(s.charAt(i)),b=B64.indexOf(s.charAt(i+1)); int c=s.charAt(i+2)=='='?-1:B64.indexOf(s.charAt(i+2)), d=s.charAt(i+3)=='='?-1:B64.indexOf(s.charAt(i+3));
            if(a<0||b<0||(c<0&&d>=0)||(i+4<s.length()&&(c<0||d<0))) throw new Exception("invalid base64");
            out.write((a<<2)|(b>>4)); if(c>=0)out.write((b<<4)|(c>>2)); if(d>=0)out.write((c<<6)|d);
        } return out.toByteArray();
    }
    static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255)); return s.toString(); }
    static String randomHex(int n) { byte[] b=new byte[n]; RNG.nextBytes(b); return hex(b); }
    private static String pem(String type,byte[] b) { String s=b64(b); StringBuilder o=new StringBuilder("-----BEGIN "+type+"-----\n"); for(int i=0;i<s.length();i+=64)o.append(s.substring(i,Math.min(i+64,s.length()))).append('\n'); return o.append("-----END "+type+"-----\n").toString(); }
    private static byte[] parsePem(String s,String type) throws Exception { String h="-----BEGIN "+type+"-----",t="-----END "+type+"-----"; s=s.trim(); if(!s.startsWith(h)||!s.endsWith(t))throw new Exception("invalid PEM"); return unb64(s.substring(h.length(),s.length()-t.length())); }
    public static JSONObject create() throws Exception {
        KeyPairGenerator gen=KeyPairGenerator.getInstance("EC"); gen.initialize(new ECGenParameterSpec("secp256r1"),RNG); KeyPair p=gen.generateKeyPair();
        StringBuilder id=new StringBuilder().append(1+RNG.nextInt(9)); for(int i=1;i<16;i++)id.append(RNG.nextInt(10));
        JSONObject a=new JSONObject(); a.put("deviceId",id.toString()); a.put("machineId",randomHex(16)); a.put("devicePublicKey",pem("PUBLIC KEY",p.getPublic().getEncoded())); a.put("devicePrivateKey",pem("PRIVATE KEY",p.getPrivate().getEncoded())); return a;
    }
    public static PrivateKey privateKey(String pem) throws Exception { return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(parsePem(pem,"PRIVATE KEY"))); }
    public static PublicKey publicKey(String pem) throws Exception { return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(parsePem(pem,"PUBLIC KEY"))); }
    public static void validate(JSONObject a) throws Exception {
        if(!a.optString("deviceId").matches("[1-9][0-9]{15}") || !a.optString("machineId").matches("[0-9a-f]{32}"))throw new Exception("invalid device identity");
        PrivateKey priv=privateKey(a.optString("devicePrivateKey")); PublicKey pub=publicKey(a.optString("devicePublicKey"));
        AlgorithmParameters params=AlgorithmParameters.getInstance("EC"); params.init(new ECGenParameterSpec("secp256r1")); ECParameterSpec expected=params.getParameterSpec(ECParameterSpec.class);
        if(!sameCurve(((java.security.interfaces.ECKey)priv).getParams(),expected) || !sameCurve(((java.security.interfaces.ECKey)pub).getParams(),expected))throw new Exception("invalid EC key");
        byte[] challenge="trae-device-key-pair".getBytes(StandardCharsets.UTF_8); Signature sig=Signature.getInstance("SHA256withECDSA"); sig.initSign(priv); sig.update(challenge); byte[] signed=sig.sign(); sig.initVerify(pub); sig.update(challenge); if(!sig.verify(signed))throw new Exception("device keys do not match");
    }
    private static boolean sameCurve(ECParameterSpec a, ECParameterSpec b) { return a!=null && b!=null && a.getCurve().equals(b.getCurve()) && a.getGenerator().equals(b.getGenerator()) && a.getOrder().equals(b.getOrder()) && a.getCofactor()==b.getCofactor(); }
    public static JSONObject proofOf(String path,String clientId,String refresh,String privatePem) throws Exception { return proofOf(path,clientId,refresh,privatePem,System.currentTimeMillis()/1000,randomHex(16)); }
    public static JSONObject proofOf(String path,String clientId,String refresh,String privatePem,long at,String nonce) throws Exception {
        Signature s=Signature.getInstance("SHA256withECDSA"); s.initSign(privateKey(privatePem),RNG); s.update(("POST\n"+path+"\n"+clientId+"\n"+refresh+"\n"+at+"\n"+nonce).getBytes(StandardCharsets.UTF_8)); JSONObject o=new JSONObject(); o.put("Signature",b64(s.sign())); o.put("Timestamp",at); o.put("Nonce",nonce); return o;
    }
}
