package com.gaeul.allin;

/** Strict JSON grammar before org.json; protects recursive parser and rejects duplicate keys. */
final class BoundedStrictJson {
    private final String s; private int i, nodes;
    BoundedStrictJson(String s) { this.s = s; }
    void run() { value(0); ws(); if (i != s.length()) fail(); }
    private void fail() { throw new IllegalArgumentException("invalid strict JSON"); }
    private void ws() { while (i<s.length() && " \t\r\n".indexOf(s.charAt(i))>=0) i++; }
    private boolean take(char c) { ws(); if (i<s.length() && s.charAt(i)==c) { i++; return true; } return false; }
    private void value(int depth) {
        if (depth>EncryptedConfigBackup.MAX_DEPTH || ++nodes>EncryptedConfigBackup.MAX_NODES) fail();
        ws(); if (i>=s.length()) fail(); char c=s.charAt(i);
        if (c=='"') { str(); return; }
        if (c=='{') {
            i++; java.util.HashSet<String> keys = new java.util.HashSet<>();
            if (take('}')) return;
            do { ws(); String key=str(); if (!keys.add(key) || !take(':')) fail(); value(depth+1); if (take('}')) return; } while(take(','));
            fail();
        } else if (c=='[') {
            i++; if(take(']')) return;
            do { value(depth+1); if(take(']')) return; } while(take(',')); fail();
        } else {
            int start=i; while(i<s.length() && ",]} \t\r\n".indexOf(s.charAt(i))<0) i++;
            String token=s.substring(start,i);
            if(!token.equals("true") && !token.equals("false") && !token.equals("null")
                    && !token.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) fail();
        }
    }
    private String str() {
        if(i>=s.length() || s.charAt(i++)!='"') { fail(); }
        StringBuilder decoded = new StringBuilder();
        while(i<s.length()) {
            char c=s.charAt(i++); if(c=='"') return decoded.toString(); if(c<32) fail();
            if(c=='\\') {
                if(i>=s.length()) fail(); c=s.charAt(i++);
                if(c=='u') { if(i+4>s.length()) fail(); int v=0; for(int j=0;j<4;j++) { int n=Character.digit(s.charAt(i++),16); if(n<0) fail(); v=(v<<4)|n; } c=(char)v; }
                else { int at="\"\\/bfnrt".indexOf(c); if(at<0) fail(); c="\"\\/\b\f\n\r\t".charAt(at); }
            }
            decoded.append(c);
        }
        fail(); return null;
    }
}
