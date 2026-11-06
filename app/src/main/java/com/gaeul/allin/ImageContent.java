package com.gaeul.allin;

import java.net.URI;
import java.util.Iterator;
import org.json.JSONObject;

/** Cross-protocol image syntax. Validation scans encoded data without decoding it. */
final class ImageContent {
    static final class InvalidImage extends IllegalArgumentException {
        InvalidImage(String message) { super(message); }
    }
    private ImageContent() {}
    static void keys(JSONObject value, String... allowed) {
        outer: for (Iterator<String> it=value.keys(); it.hasNext();) {
            String key=it.next(); for(String a:allowed) if(a.equals(key)) continue outer;
            throw new InvalidImage("Unsupported image/content field");
        }
    }
    static void validateUrl(String value) {
        if(value==null || value.isEmpty()) throw new InvalidImage("Image URL is required");
        if(value.startsWith("data:")) {
            int comma=value.indexOf(',');
            if(comma<0) throw new InvalidImage("Invalid image data URI");
            String header=value.substring(5,comma);
            if(!header.endsWith(";base64")) throw new InvalidImage("Image data URI requires base64");
            String mime=header.substring(0,header.length()-7);
            // Schema-level MIME syntax, not a claim of provider format support.
            if(!mime.startsWith("image/") || mime.length()==6) throw new InvalidImage("Image MIME required");
            for(int i=6;i<mime.length();i++) {char c=mime.charAt(i); if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||"!#$&^_.+-".indexOf(c)>=0)) throw new InvalidImage("Invalid image MIME");}
            int n=value.length()-comma-1;
            if(n==0 || n%4!=0) throw new InvalidImage("Invalid image base64");
            int pads=0;
            for(int i=comma+1;i<value.length();i++) {
                char c=value.charAt(i);
                if(c=='=') {pads++; if(pads>2 || i<value.length()-2) throw new InvalidImage("Invalid image base64");}
                else if(pads>0 || !((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='+'||c=='/')) throw new InvalidImage("Invalid image base64");
            }
            // Reject non-canonical unused bits without decoding the image.
            if(pads>0) {char c=value.charAt(value.length()-pads-1); int x=c>='A'&&c<='Z'?c-'A':c>='a'&&c<='z'?c-'a'+26:c>='0'&&c<='9'?c-'0'+52:c=='+'?62:63; if((pads==2 && (x&15)!=0)||(pads==1 && (x&3)!=0)) throw new InvalidImage("Invalid image base64 padding");}
            return;
        }
        try {
            String lower=value.toLowerCase(java.util.Locale.ROOT);
            if(lower.contains("%0d") || lower.contains("%0a")) throw new InvalidImage("Invalid image HTTP URL");
            URI uri=new URI(value); String scheme=uri.getScheme();
            if(!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)) || uri.isOpaque() || uri.getHost()==null || uri.getHost().isEmpty() || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null || uri.getPort()>65535 || uri.getRawAuthority()==null || uri.getRawAuthority().endsWith(":")) throw new InvalidImage("Invalid image HTTP URL");
        } catch(java.net.URISyntaxException e) {throw new InvalidImage("Invalid image HTTP URL");}
    }
    static String chatUrl(JSONObject part, boolean anthropic) {
        keys(part,"type","image_url"); Object raw=part.opt("image_url"); String url;
        if(raw instanceof String) url=(String)raw;
        else if(raw instanceof JSONObject) {
            JSONObject image=(JSONObject)raw; keys(image,"url","detail");
            if(!(image.opt("url") instanceof String)) throw new InvalidImage("Image URL is required");
            url=(String)image.opt("url");
            if(image.has("detail")) {Object d=image.opt("detail"); if(!(d instanceof String)|| !java.util.Arrays.asList("auto","low","high").contains(d)) throw new InvalidImage("Invalid image detail"); if(anthropic && !"auto".equals(d)) throw new InvalidImage("Image detail cannot be represented by Anthropic");}
        } else throw new InvalidImage("Image URL is required");
        validateUrl(url); return url;
    }
    static JSONObject toAnthropic(JSONObject part) throws Exception {
        String url=chatUrl(part,true); JSONObject source=new JSONObject();
        if(url.startsWith("data:")) {int comma=url.indexOf(','); source.put("type","base64").put("media_type",url.substring(5,comma-7)).put("data",url.substring(comma+1));}
        else source.put("type","url").put("url",url);
        return new JSONObject().put("type","image").put("source",source);
    }
    // Projection may omit opaque native metadata only behind requiresNative's guard.
    static boolean needsNative(JSONObject block) {
        try { keys(block,"type","source"); JSONObject src=block.optJSONObject("source");
            if(src==null) return true;
            String type=src.optString("type");
            if("base64".equals(type)) keys(src,"type","media_type","data");
            else if("url".equals(type)) keys(src,"type","url");
            else return true;
            return false;
        } catch(IllegalArgumentException opaque) { return true; }
    }
    static JSONObject projectNative(JSONObject block) throws Exception {
        JSONObject src=block.optJSONObject("source");
        if(src==null) throw new InvalidImage("Image source required");
        String type=src.optString("type");
        if(!"base64".equals(type) && !"url".equals(type))
            return new JSONObject().put("type","text").put("text","[Native image omitted from projection]");
        JSONObject image=new JSONObject().put("type","image"); JSONObject clean=new JSONObject().put("type",type);
        if("base64".equals(type)) {if(!(src.opt("media_type") instanceof String)||!(src.opt("data") instanceof String)) throw new InvalidImage("Image media type and data required"); clean.put("media_type",src.get("media_type")).put("data",src.get("data"));}
        else {if(!(src.opt("url") instanceof String)) throw new InvalidImage("Image URL required"); clean.put("url",src.get("url"));}
        return fromAnthropic(image.put("source",clean));
    }
    static JSONObject fromAnthropic(JSONObject block) throws Exception {
        keys(block,"type","source"); JSONObject src=block.optJSONObject("source"); if(src==null) throw new InvalidImage("Image source required");
        String url; String kind=src.optString("type");
        if("base64".equals(kind)) {keys(src,"type","media_type","data"); if(!(src.opt("media_type") instanceof String)||!(src.opt("data") instanceof String)) throw new InvalidImage("Image media type and data required"); url="data:"+src.getString("media_type")+";base64,"+src.getString("data");}
        else if("url".equals(kind)) {keys(src,"type","url"); if(!(src.opt("url") instanceof String)) throw new InvalidImage("Image URL required"); url=src.getString("url"); if(url.startsWith("data:")) throw new InvalidImage("URL source requires HTTP URL");}
        else throw new InvalidImage("Unsupported image source");
        validateUrl(url); return new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",url));
    }
}
