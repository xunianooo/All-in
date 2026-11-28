package com.gaeul.allin;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.Arrays;

final class AccountExportDocument {
    private AccountExportDocument() {}

    static void writeAndVerify(ContentResolver resolver,Uri uri,byte[] bytes)throws IOException {
        if(bytes==null||bytes.length==0)throw new IOException("no export data");
        try(OutputStream out=resolver.openOutputStream(uri,"wt")) {
            if(out==null)throw new IOException();out.write(bytes);out.flush();
        }
        // Completion means exact persisted bytes, not an opened stream or a creation result.
        try(InputStream in=resolver.openInputStream(uri)) {
            if(in==null)throw new IOException();
            byte[] back=AccountTransferCodec.readBounded(in);
            try {if(!Arrays.equals(bytes,back))throw new IOException();}
            finally {Arrays.fill(back,(byte)0);}
        }
    }

    static boolean removeOwnedEmpty(ContentResolver resolver,Uri uri,String proposedName) {
        // Do not delete user-named/reused files or a cloud provider's placeholders. The default
        // name contains a fresh random UUID, and only Android's local document providers qualify.
        String authority=uri.getAuthority();
        if(!("com.android.externalstorage.documents".equals(authority)||"com.android.providers.downloads.documents".equals(authority)))return false;
        if(proposedName==null||!proposedName.matches("AllIn-[a-z0-9_-]+-[0-9]{8}-[0-9]{6}-[0-9a-f-]{36}[.]json"))return false;
        try {
            try(Cursor row=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                if(row==null||!row.moveToFirst()||!proposedName.equals(row.getString(0))||row.isNull(1)||row.getLong(1)!=0)return false;
            }
            try(InputStream in=resolver.openInputStream(uri)){if(in==null||in.read()!=-1)return false;}
            return DocumentsContract.deleteDocument(resolver,uri);
        } catch(Exception denied){return false;}
    }
}
