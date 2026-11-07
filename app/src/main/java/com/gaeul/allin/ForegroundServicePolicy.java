package com.gaeul.allin;

/** Select one runtime type, never the union declared for backward compatibility. */
final class ForegroundServicePolicy {
    static final int DATA_SYNC = 0x00000001;
    static final int MEDIA_PLAYBACK = 0x00000002;
    static final int SPECIAL_USE = 0x40000000;
    interface Promoter {
        void legacy() throws Exception;
        void typed(int type) throws Exception;
    }
    static int typeForSdk(int sdk) {
        return typeForSdk(sdk, false);
    }
    static int typeForSdk(int sdk, boolean audio) {
        int base = sdk >= 34 ? SPECIAL_USE : sdk >= 29 ? DATA_SYNC : 0;
        return sdk >= 29 && audio ? base | MEDIA_PLAYBACK : base;
    }
    static String name(int type) {
        if (type == (SPECIAL_USE | MEDIA_PLAYBACK)) return "specialUse|mediaPlayback";
        if (type == (DATA_SYNC | MEDIA_PLAYBACK)) return "dataSync|mediaPlayback";
        if (type == SPECIAL_USE) return "specialUse";
        if (type == DATA_SYNC) return "dataSync";
        if (type == 0) return "legacy";
        return "unknown";
    }
    static int promote(int sdk, Promoter promoter) throws Exception {
        return promote(sdk, false, promoter);
    }
    static int promote(int sdk, boolean audio, Promoter promoter) throws Exception {
        int type = typeForSdk(sdk, audio);
        if (sdk >= 29) promoter.typed(type);
        else promoter.legacy();
        // A typed failure propagates; no manifest-default or dataSync fallback.
        return type;
    }
}
