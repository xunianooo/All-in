package com.gaeul.allin;

import java.io.File;
import java.io.IOException;

/** Windows-only build/test support. JNA is a test runtime dependency, never shipped in the APK. */
final class DesktopDurableReplace {
    private DesktopDurableReplace() {}
    static void replace(File source, File target) throws IOException {
        if (File.separatorChar != '\\' || AtomicConfigFile.isAndroidRuntime(System.getProperty("java.vm.name", "")))
            throw new IOException("Windows write-through replacement is not available");
        try {
            // NIO directory force is not supported on Windows. Use the documented Win32 durable
            // rename instead: MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH, same volume only.
            // Missing native support fails closed; it never degrades to an unflushed rename.
            Class<?> library=Class.forName("com.sun.jna.NativeLibrary");
            Class<?> function=Class.forName("com.sun.jna.Function");
            Class<?> wide=Class.forName("com.sun.jna.WString");
            Object kernel=library.getMethod("getInstance",String.class).invoke(null,"kernel32");
            int convention=function.getField("ALT_CONVENTION").getInt(null);
            Object move=library.getMethod("getFunction",String.class,int.class).invoke(kernel,"MoveFileExW",convention);
            Object from=wide.getConstructor(String.class).newInstance(source.getAbsolutePath());
            Object to=wide.getConstructor(String.class).newInstance(target.getAbsolutePath());
            int result=(Integer)function.getMethod("invokeInt",Object[].class).invoke(move,(Object)new Object[]{from,to,0x1|0x8});
            if(result==0) {
                int code=(Integer)Class.forName("com.sun.jna.Native").getMethod("getLastError").invoke(null);
                throw new IOException("Windows write-through replacement failed ("+code+")");
            }
        } catch(IOException failure) { throw failure; }
        catch(Exception unavailable) { throw new IOException("Windows durable replacement unavailable",unavailable); }
    }
}
