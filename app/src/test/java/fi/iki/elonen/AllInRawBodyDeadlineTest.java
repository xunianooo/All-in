package fi.iki.elonen;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Actual loopback socket body deadlines, including an indefinitely blocked read. */
public final class AllInRawBodyDeadlineTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void readHeader(AllInRawHeaderInputStream in) throws Exception {
        byte[] bytes=new byte[8192];int length=in.read(bytes);
        check(length>0 && new String(bytes,0,length,StandardCharsets.US_ASCII).endsWith("\r\n\r\n"),"Header complete before body timer test");
    }
    private static void generation() throws Exception {
        AllInRawHeaderDeadlineTest.BlockingStream source=new AllInRawHeaderDeadlineTest.BlockingStream();
        try(AllInRawHeaderInputStream in=new AllInRawHeaderInputStream(source,new ByteArrayOutputStream(),5000,5000)) {
            source.feed("POST /one HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\na");
            readHeader(in);long first=in.armedBodyGeneration();check(first!=0,"First body budget armed");
            check(in.read()=='a' && in.armedBodyGeneration()==0,"Complete body retires its budget");
            source.feed("POST /two HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\n");
            readHeader(in);long second=in.armedBodyGeneration();check(second!=first && second!=0,"Keep-alive body has a new generation");
            in.bodyDeadlineExpired(first);check(!source.wasClosed() && in.armedBodyGeneration()==second,"Old deadline cannot close the next request");
            in.bodyDeadlineExpired(second);check(source.wasClosed(),"Live deadline closes its own source");
            try{in.read();throw new AssertionError("Expired body returned data");}catch(SocketTimeoutException expected){checks++;}
        }
    }
    private static void socket(boolean drip) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        long start=System.nanoTime();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(3000);
            Thread handler=new Thread(()->{
                try(Socket accepted=listener.accept();AllInRawHeaderInputStream input=new AllInRawHeaderInputStream(accepted.getInputStream(),accepted.getOutputStream(),2000,180)) {
                    accepted.setSoTimeout(0); // Prove the absolute timer, not an idle socket timeout, ends the read.
                    readHeader(input);
                    try {while(input.read()!=-1){}throw new AssertionError("Missing body deadline");}
                    catch(SocketTimeoutException expected){check(input.armedBodyGeneration()==0,"Expired body generation retired");}
                }catch(Throwable error){failure.set(error);}
            },"raw-body-deadline-fixture");handler.start();
            try(Socket client=new Socket("127.0.0.1",listener.getLocalPort())) {
                client.setSoTimeout(3000);
                client.getOutputStream().write("POST /api/config HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
                Thread writer=null;
                if(drip) {
                    writer=new Thread(()->{try{for(int i=0;i<100;i++){client.getOutputStream().write('x');client.getOutputStream().flush();Thread.sleep(25);}}catch(Exception closed){}},"raw-body-drip-fixture");writer.start();
                }
                try{check(client.getInputStream().read()==-1,"Deadline releases actual socket");}catch(SocketException reset){checks++;}
                if(writer!=null){writer.join(3000);check(!writer.isAlive(),"Drip writer exits after abort");}
            }
            handler.join(3000);check(!handler.isAlive(),"Handler released after body timeout");
            if(failure.get()!=null)throw new AssertionError(failure.get());
            check((System.nanoTime()-start)/1000000<2000,"Body has an absolute bound even with no socket idle timeout");
        }
    }
    private static void responseOutlivesBody() throws Exception {
        AllInRawHeaderDeadlineTest.BlockingStream source=new AllInRawHeaderDeadlineTest.BlockingStream();
        try(AllInRawHeaderInputStream in=new AllInRawHeaderInputStream(source,new ByteArrayOutputStream(),2000,80)) {
            source.feed("POST /done HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\na");
            readHeader(in);check(in.read()=='a',"Body completed within budget");
            Thread.sleep(180);
            check(!source.wasClosed() && in.armedBodyGeneration()==0,"Completed upload never times out the outgoing stream");
        }
    }
    public static void main(String[] args)throws Exception {
        generation();socket(false);socket(true);responseOutlivesBody();
        check(AllInRawHeaderInputStream.pendingBodyDeadlines()==0,"Body deadline queue drained");
        check(AllInRawHeaderInputStream.pendingHeaderDeadlines()==0,"Header deadline queue drained");
        System.out.println("AllInRawBodyDeadlineTest PASS checks="+checks);
    }
}
