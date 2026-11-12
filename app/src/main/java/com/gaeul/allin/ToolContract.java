package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.math.BigDecimal;

/** Strict structural validation only; never rewrites tool IDs or argument fragments. */
final class ToolContract {
    static int index(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("tool index must be an integer");
        try {
            int index=new BigDecimal(value.toString()).intValueExact();
            if(index<0) throw new ArithmeticException();
            return index;
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("tool index must be a nonnegative integer"); }
    }
    static JSONObject input(String text) {
        try {
            new Syntax(text).run();
            if(text.trim().isEmpty() || text.trim().charAt(0)!='{') throw new IllegalArgumentException();
            return new JSONObject(text);
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("tool arguments must be a strict JSON object"); }
    }
    private static final class Syntax {
        final String s; int i;
        Syntax(String s) { if(s==null) throw new IllegalArgumentException(); this.s=s; }
        void fail() { throw new IllegalArgumentException(); }
        void ws() { while(i<s.length() && " \t\r\n".indexOf(s.charAt(i))>=0) i++; }
        boolean take(char c) { ws(); if(i<s.length() && s.charAt(i)==c) { i++; return true; } return false; }
        void run() { value(0); ws(); if(i!=s.length()) fail(); }
        void value(int depth) {
            if(depth>128) fail(); ws(); if(i>=s.length()) fail(); char c=s.charAt(i);
            if(c=='"') { str(); return; }
            if(c=='{') {
                i++; if(take('}')) return;
                do { ws(); str(); if(!take(':')) fail(); value(depth+1); if(take('}')) return; } while(take(','));
                fail();
            } else if(c=='[') {
                i++; if(take(']')) return;
                do { value(depth+1); if(take(']')) return; } while(take(',')); fail();
            } else {
                int start=i; while(i<s.length() && ",]} \t\r\n".indexOf(s.charAt(i))<0) i++;
                String token=s.substring(start,i);
                if(!token.equals("true") && !token.equals("false") && !token.equals("null")
                    && !token.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) fail();
            }
        }
        void str() {
            if(i>=s.length() || s.charAt(i++)!='"') fail();
            while(i<s.length()) {
                char c=s.charAt(i++); if(c=='"') return; if(c<32) fail();
                if(c=='\\') {
                    if(i>=s.length()) fail(); c=s.charAt(i++);
                    if(c=='u') { for(int j=0;j<4;j++) { if(i>=s.length() || "0123456789abcdefABCDEF".indexOf(s.charAt(i++))<0) fail(); } }
                    else if("\"\\/bfnrt".indexOf(c)<0) fail();
                }
            }
            fail();
        }
    }
}
