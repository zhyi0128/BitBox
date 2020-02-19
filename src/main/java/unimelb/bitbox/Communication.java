/**
 * A tool class which encapsulates communication function. To use it, provide a socket as a param.
 *
 * @param socket
 * @author Zhe Wang
 */

package unimelb.bitbox;

import unimelb.bitbox.util.Document;

import java.io.*;
import java.net.Socket;
import java.util.logging.Logger;


public class Communication {

    private static Logger log = Logger.getLogger(Communication.class.getName());
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    public Communication(Socket s) throws IOException {
        socket = s;
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
    }

    public boolean checkInReady() throws IOException {
        return in.ready();
    }

    public String readString() throws IOException {
        return in.readLine();
    }

    public void sendMessage(Document msg) throws IOException {
        out.write(msg.toJson() + "\n");
        out.flush();
        log.info("COMMAND SENT: " + msg.toJson());
    }

    public void sendMessage(String msg) throws IOException {
        out.write(msg + "\n");
        out.flush();
        log.info("COMMAND SENT: " + msg);
    }

    public synchronized static void sendMessage(Socket s, Document msg) throws IOException {
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
        out.write(msg.toJson() + "\n");
        out.flush();
        log.info("COMMAND SENT: " + msg.toJson());
    }

}
