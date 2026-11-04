package com.gaeul.allin;

import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public final class ForegroundServicePolicyTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Recording implements ForegroundServicePolicy.Promoter {
        int legacyCalls, typedCalls, type; boolean fail;
        public void legacy() { legacyCalls++; }
        public void typed(int value) throws Exception { typedCalls++; type=value; if (fail) throw new SecurityException("injected promotion failure"); }
    }
    static String attr(Element e, String name) { return e.getAttributeNS("http://schemas.android.com/apk/res/android", name); }
    static void manifest() throws Exception {
        Path file = Paths.get("src/main/AndroidManifest.xml");
        if (!Files.exists(file)) file=Paths.get("app/src/main/AndroidManifest.xml");
        check(Files.exists(file), "manifest fixture path");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        Document d=f.newDocumentBuilder().parse(file.toFile());
        Element gateway=null; NodeList services=d.getElementsByTagName("service");
        for(int i=0;i<services.getLength();i++) { Element e=(Element)services.item(i); if(attr(e,"name").equals("com.gaeul.allin.GatewayService")) gateway=e; }
        check(gateway!=null, "gateway service declaration");
        Set<String> types=new HashSet<>(Arrays.asList(attr(gateway,"foregroundServiceType").split("\\|")));
        check(types.equals(new HashSet<>(Arrays.asList("dataSync","specialUse"))), "only declared compatibility types");
        check("false".equals(attr(gateway,"exported")), "service private");
        NodeList props=gateway.getElementsByTagName("property"); boolean subtype=false;
        for(int i=0;i<props.getLength();i++) { Element e=(Element)props.item(i); if(attr(e,"name").equals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")) {
            String value=attr(e,"value"); check(value.contains("local HTTP AI gateway") && value.contains("user turns the gateway off"), "concrete user-controlled purpose"); subtype=true;
        }}
        check(subtype,"specialUse subtype required"); boolean special=false,legacy=false;
        NodeList permissions=d.getElementsByTagName("uses-permission");
        for(int i=0;i<permissions.getLength();i++) { Element e=(Element)permissions.item(i);
            if(attr(e,"name").equals("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")) special=true;
            if(attr(e,"name").equals("android.permission.FOREGROUND_SERVICE_DATA_SYNC")) { check(attr(e,"maxSdkVersion").equals("33"),"dataSync compatibility permission not granted on modern Android");legacy=true; }
        }
        check(special&&legacy,"type permissions");
    }
    public static void main(String[] args) throws Exception {
        for(int sdk : new int[]{24,25,26,28,29,30,31,32,33,34,35,36,37}) {
            Recording r=new Recording(); int mask=ForegroundServicePolicy.promote(sdk,r);
            int expected=sdk>=34?0x40000000:sdk>=29?1:0;
            check(mask==expected,"runtime type for SDK "+sdk);
            check(r.typedCalls==(sdk>=29?1:0) && r.legacyCalls==(sdk<29?1:0),"exact promotion path SDK "+sdk);
            if(sdk>=34)check(r.type==ForegroundServicePolicy.SPECIAL_USE && (r.type&ForegroundServicePolicy.DATA_SYNC)==0,"modern runtime never mixes dataSync");
            check(ForegroundServicePolicy.name(mask).equals(sdk>=34?"specialUse":sdk>=29?"dataSync":"legacy"),"actual name");
        }
        for(int sdk : new int[]{29,33,34,35,36}) {
            Recording r=new Recording(); r.fail=true;
            try { ForegroundServicePolicy.promote(sdk,r); throw new AssertionError("failed typed promotion must propagate"); }
            catch(SecurityException expected) { check(r.typedCalls==1 && r.legacyCalls==0,"no silent legacy/manifest fallback"); }
        }
        check(ForegroundServicePolicy.name(0x40000001).equals("unknown"),"union must not masquerade as specialUse");
        manifest();
        System.out.println("ForegroundServicePolicyTest OK (API24-37 dispatch, failure, manifest)");
    }
}
