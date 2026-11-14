package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real durable config commits, stale snapshot rejection, failures and imported refresh fences. */
public final class AccountTransferGatewayOfflineTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static JSONObject get(GatewayServer s)throws Exception{Field f=GatewayServer.class.getDeclaredField("configJson");f.setAccessible(true);return (JSONObject)f.get(s);}
    static Object invoke(GatewayServer s,String name,Class<?>[] types,Object...args)throws Exception{Method m=GatewayServer.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(s,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
    static GatewayServer server(JSONObject cfg,Path directory)throws Exception {
        GatewayServer s=RoutingSetupOfflineTest.server(cfg);ResponsesGatewayOfflineTest.set(s,"configFile",directory.resolve("config.json").toFile());
        AtomicConfigFile.write(directory.resolve("config.json").toFile(),get(s).toString(2));return s;
    }
    static void rejected(Throwing work)throws Exception{try{work.run();throw new AssertionError("unsafe operation accepted");}catch(java.io.IOException|AccountTransferCodec.TransferException expected){checks++;}}
    interface Throwing{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("account-transfer-gateway-");GatewayServer server=null,second=null,miniServer=null;
        try {
            JSONObject cfg=AccountTransferCodecOfflineTest.config();server=server(cfg,root);final GatewayServer own=server;
            byte[] before=Files.readAllBytes(root.resolve("config.json")),snapshot=server.accountTransferSnapshot();check(Arrays.equals(before,snapshot),"native snapshot reads complete durable config");
            AccountTransferCodec.Document doc=AccountTransferMergeOfflineTest.doc(AccountTransferMergeOfflineTest.a("one"));
            JSONObject receipt=server.commitAccountTransfer(AccountTransferCodec.digest(snapshot),doc,Collections.singleton(0),false,AccountTransferMergeOfflineTest.NOW);
            check(receipt.getBoolean("ok")&&receipt.getInt("added")==1,"native commit result accurate");
            byte[] after=Files.readAllBytes(root.resolve("config.json"));check(!Arrays.equals(before,after),"actual config bytes changed");check(new JSONObject(new String(after,StandardCharsets.UTF_8)).similar(get(server)),"disk and authoritative memory match");
            check(!receipt.toString().contains("fixture-access")&&!receipt.toString().contains("fixture-refresh"),"native completion metadata contains no credentials");
            rejected(()->own.commitAccountTransfer(AccountTransferCodec.digest(before),doc,Collections.singleton(0),false,0));check(Arrays.equals(after,Files.readAllBytes(root.resolve("config.json"))),"stale preview is zero-write");
            AccountTransferCodec.Document next=AccountTransferMergeOfflineTest.doc(AccountTransferMergeOfflineTest.a("two"));
            GatewayServer.setConfigWriteHookForTests(()->{throw new IllegalStateException("synthetic failure");});
            try{rejected(()->own.commitAccountTransfer(AccountTransferCodec.digest(after),next,Collections.singleton(0),false,AccountTransferMergeOfflineTest.NOW));}
            finally{GatewayServer.setConfigWriteHookForTests(null);}
            check(Arrays.equals(after,Files.readAllBytes(root.resolve("config.json"))),"failed atomic write keeps old bytes");check(get(server).similar(new JSONObject(new String(after,StandardCharsets.UTF_8))),"failed write keeps old live accounts");
            GatewayServer.setPostCommitHookForTests(()->{throw new IllegalStateException("synthetic post-commit failure");});
            try{receipt=server.commitAccountTransfer(AccountTransferCodec.digest(after),next,Collections.singleton(0),false,AccountTransferMergeOfflineTest.NOW);check(receipt.getBoolean("ok")&&receipt.getBoolean("runtimeWarning"),"committed file with degraded effect is not misreported as failed save");}
            finally{GatewayServer.setPostCommitHookForTests(null);}
            JSONObject current=get(server);check(AccountTransferCodecOfflineTest.provider(current,"raccoon").getJSONArray("accounts").length()==2,"whole second batch saved");
            byte[] hash=AccountTransferCodec.digest(server.accountTransferSnapshot());
            byte[] exported=server.exportAccountTransfer(hash,Collections.singleton(0),AccountTransferMergeOfflineTest.NOW);
            check(AccountTransferCodec.parse(exported,"").entries.size()==1,"native selective file output");
            check(!new String(exported,StandardCharsets.UTF_8).contains("PRIVATE_FIXTURE_GATEWAY_KEY"),"native export does not contain gateway key");
            rejected(()->own.exportAccountTransfer(new byte[32],Collections.singleton(0),0));
            // The file format must carry a relevant old fence, not its local HMAC/key files.
            String refresh="fixture-refresh-one",digest=AccountTransferProtection.digest("raccoon",refresh);
            check(digest.equals(invoke(server,"refreshFenceDigest",new Class[]{String.class,String.class},"raccoon",refresh)),"portable digest equals production fence digest");
            check(AccountTransferProtection.digest("raccoon"," Bearer "+refresh+" ").equals(digest),"Raccoon exact wire normalization retained");
            Files.writeString(root.resolve(AccountTransferProtection.FILE),"unknown raccoon "+digest+"\n",StandardCharsets.US_ASCII);
            Path destination=Files.createDirectory(root.resolve("destination"));
            second=server(new JSONObject(current.toString()),destination);
            Map<String,String> records=AccountTransferProtection.read(root.resolve(AccountTransferProtection.FILE).toFile());
            JSONObject decorated=AccountTransferProtection.decorate(current,records);
            byte[] fenced=AccountTransferCodec.export(decorated,Collections.singleton(0),AccountTransferMergeOfflineTest.NOW);
            AccountTransferCodec.Document fencedDoc=AccountTransferCodec.parse(fenced,"");check(fencedDoc.entries.get(0).value.has(AccountTransferProtection.FIELD),"relevant unknown fence carried in account record");
            JSONObject empty=AccountTransferCodecOfflineTest.config();AccountTransferPlan.Result restored=AccountTransferPlan.merge(empty,fencedDoc,Collections.singleton(0),false,AccountTransferMergeOfflineTest.NOW);
            second.stop();second=server(restored.config,destination);final GatewayServer target=second;
            check((Boolean)invoke(target,"refreshFenceHit",new Class[]{String.class,String.class},"raccoon",refresh),"new device actually blocks transferred one-use value");
            check(!(Boolean)invoke(target,"refreshValueRecordedAsSpent",new Class[]{String.class,String.class},"raccoon",refresh),"unknown is not falsely called spent");
            rejected(()->invoke(target,"reserveRefreshFence",new Class[]{String.class,String.class},"raccoon",refresh));
            check(!Files.exists(destination.resolve(AccountTransferProtection.FILE)),"blocked imported value not reserved or sent again");
            JSONObject originalAccount=fencedDoc.entries.get(0).value;
            JSONObject updated=new JSONObject(originalAccount.toString());updated.put(AccountTransferProtection.FIELD,new JSONObject());
            AccountTransferProtection.preserve(originalAccount,updated);check(updated.getJSONObject(AccountTransferProtection.FIELD).has(digest),"import cannot erase local retained fence");
            // MiniMax uses a separate HMAC journal. Classification must not create a key/lock.
            Path mini=Files.createDirectory(root.resolve("mini"));MiniMaxRefreshJournal journal=new MiniMaxRefreshJournal(mini.toFile()){@Override void syncDirectory(){}};
            check(journal.protectionTag("fixture-refresh-mini").isEmpty(),"no journal means no MiniMax fence");try(java.util.stream.Stream<Path> paths=Files.list(mini)){check(paths.count()==0,"read-only classification creates no journal/key/lock");}
            MiniMaxRefreshJournal.Ticket ticket=journal.begin("mini","fixture-refresh-mini");check(journal.protectionTag("fixture-refresh-mini").equals("unknown"),"pending MiniMax journal classified");journal.complete(ticket);check(journal.protectionTag("fixture-refresh-mini").equals("spent"),"spent MiniMax journal classified");
            JSONObject miniConfig=AccountTransferCodecOfflineTest.config();JSONObject miniAccount=AccountTransferMergeOfflineTest.a("mini").put("expires",1);
            AccountTransferCodecOfflineTest.provider(miniConfig,"minimax-code").getJSONArray("accounts").put(miniAccount);
            JSONObject portable=AccountTransferProtection.decorateMiniMax(miniConfig,Collections.singleton(0),journal);
            check(AccountTransferProtection.tag(portable,"minimax-code","fixture-refresh-mini").equals("spent"),"MiniMax HMAC protection converted without exporting secret HMAC key");
            Path miniTarget=Files.createDirectory(root.resolve("mini-target"));miniServer=server(portable,miniTarget);AtomicInteger refreshCalls=new AtomicInteger();miniServer.setMiniMaxRefreshForTests(token->{refreshCalls.incrementAndGet();throw new AssertionError("must not send");});
            GatewayServer finalMini=miniServer;
            rejected(()->invoke(finalMini,"miniLease",new Class[]{JSONObject.class,boolean.class,MiniMaxLifecycle.Lease.class},AccountTransferCodecOfflineTest.provider(get(finalMini),"minimax-code").getJSONArray("accounts").getJSONObject(0),true,null));
            check(refreshCalls.get()==0,"actual forced MiniMax lease refresh makes zero transport calls after migration");
            Files.writeString(root.resolve(AccountTransferProtection.FILE),"corrupt\n");rejected(()->AccountTransferProtection.read(root.resolve(AccountTransferProtection.FILE).toFile()));
            AccountTransferCodec.Document bad=AccountTransferCodec.parse("[{\"token\":\"fixture\",\"transferRefreshFences\":{\"invalid\":\"spent\"}}]".getBytes(StandardCharsets.UTF_8),"raccoon");check(!bad.entries.get(0).valid(),"malformed protection cannot be imported");
        } finally {
            GatewayServer.setConfigWriteHookForTests(null);GatewayServer.setPostCommitHookForTests(null);
            if(server!=null)server.stop();if(second!=null)second.stop();if(miniServer!=null)miniServer.stop();
            try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);}
        }
        System.out.println("AccountTransferGatewayOfflineTest PASS: "+checks+" checks");
    }
}
