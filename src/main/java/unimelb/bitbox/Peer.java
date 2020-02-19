package unimelb.bitbox;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

import unimelb.bitbox.util.Configuration;


/**
 * A peer class which contains the main method. The entrance of the program. Handles the starting process of the server
 * and its services.
 * To use it, choose main as the Main class and run directly.
 *
 * @author Zhe Wang
 * @version 1.1
 */

public class Peer {
    static Logger log = Logger.getLogger(Peer.class.getName());

    public static void main(String[] args) throws IOException, NumberFormatException, NoSuchAlgorithmException {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tc] %2$s %4$s: %5$s%n");
        log.info("BitBox Peer starting...");
        Configuration.getConfiguration();

        ServerMain server = ServerMain.getServerMain();
        Thread serve_client = new Thread(server.new ServeClient()); //open a new thread to serve the client
        serve_client.start();
        //server.tcpInitialConnect();
        Thread sync_event = server.new TCPSyncEvent();
        sync_event.start();
        server.tcpListen();

    }

}