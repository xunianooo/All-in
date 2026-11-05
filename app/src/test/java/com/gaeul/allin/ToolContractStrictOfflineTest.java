package com.gaeul.allin;

import org.json.*;
import java.lang.reflect.*;
import java.math.BigDecimal;

public final class ToolContractStrictOfflineTest {
    static int failures;
    static void rejects(Method method, Object... args) throws Exception {
        try { method.invoke(null,args); failures++; }
        catch(InvocationTargetException expected) { if(!(expected.getCause() instanceof IllegalArgumentException)) throw expected; }
    }
    public static void main(String[] args) throws Exception {
        Method input=AnthropicBridge.class.getDeclaredMethod("toolInput",JSONObject.class); input.setAccessible(true);
        for(String value:new String[]{"{'x':1}","{\"x\":1,}","{\"x\":1;\"y\":2}","{\"x\":NaN}","{\"x\":01}","{\"x\":+1}","{\"x\":.1}","{\"x\":undefined}"})
            rejects(input,new JSONObject().put("arguments",value));
        JSONObject valid=(JSONObject)input.invoke(null,new JSONObject().put("arguments","{\"n\":1234567890123456789,\"a\":[true,null,\"中文\\n\\u2603\"],\"s\":{\"signature\":\"kept\"}}"));
        if(valid.getLong("n")!=1234567890123456789L)throw new AssertionError("64 bit arguments changed");
        StringBuilder deep=new StringBuilder("{\"a\":"); for(int i=0;i<140;i++)deep.append('[');deep.append('0');for(int i=0;i<140;i++)deep.append(']');deep.append('}');rejects(input,new JSONObject().put("arguments",deep.toString()));
        Method anth=AnthropicBridge.class.getDeclaredMethod("toolIndex",JSONObject.class);anth.setAccessible(true);
        Method resp=ResponsesBridge.class.getDeclaredMethod("toolIndex",JSONObject.class,String.class);resp.setAccessible(true);
        for(Object n:new Object[]{new BigDecimal("1.00000000000000000001"),new BigDecimal("1e-400"),-1,2147483648L,"1"}) {
            rejects(anth,new JSONObject().put("index",n));rejects(resp,new JSONObject().put("output_index",n),"output_index");
        }
        if(!Integer.valueOf(1).equals(anth.invoke(null,new JSONObject().put("index",new BigDecimal("1.0")))))throw new AssertionError("exact index rejected");
        System.out.println("ToolContractStrictOfflineTest accepted-invalid-count="+failures);
        if(failures!=0)throw new AssertionError("strict contract accepted "+failures+" malformed inputs");
    }
}
