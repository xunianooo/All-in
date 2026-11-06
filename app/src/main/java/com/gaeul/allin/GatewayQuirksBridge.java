package com.gaeul.allin;

import android.content.Context;
import android.os.Build;
import java.io.*;
import org.json.*;

/** API 26+ updater classes remain isolated behind the guarded nested factory. */
abstract class GatewayQuirksBridge {
    abstract ProviderQuirks.Snapshot snapshot();
    abstract JSONObject status();
    abstract JSONObject check();
    abstract void close();
    static GatewayQuirksBridge create(Context context, byte[] builtin) throws Exception {
        if(Build.VERSION.SDK_INT<26) return null;
        return Api26.create(context,builtin);
    }
    private static final class Api26 {
        static GatewayQuirksBridge create(Context context,byte[] builtin) throws Exception {
            byte[] descriptor=null;
            try(InputStream in=context.getAssets().open("quirks_trust.json"); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[1024]; int n,total=0;
                while((n=in.read(b))!=-1) { total+=n;if(total>8192)throw new IOException("descriptor too large");out.write(b,0,n); }
                descriptor=out.toByteArray();
            } catch(FileNotFoundException missing) { /* no distribution configured */ }
            QuirksUpdateController.TrustConfig trust=QuirksUpdateController.TrustConfig.fromApkAsset(descriptor);
            final QuirksUpdateController updater=new QuirksUpdateController(trust,new File(context.getFilesDir(),"quirks-lkg.bin"),builtin,Build.VERSION.SDK_INT);
            return new GatewayQuirksBridge() {
                ProviderQuirks.Snapshot snapshot(){return updater.snapshot();}
                JSONObject status(){return updater.status().toJson();}
                JSONObject check(){return updater.check().toJson();}
                void close(){updater.close();}
            };
        }
    }
}
