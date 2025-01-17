/**
 * A client class implemented in a simple way. All members are static.
 * For further implementation, can separate core functions into a new class
 * <p>
 * To use it, run it providing cmd args. Also, the server should include the public key of the client in the
 * configuration file. As for the private key for used by the client in this class, its name is hardcoded in
 * the getPrivateKey() method.
 * Example usage: -i zhewang@unimelb -c connect_peer -s localhost:3001 -p localhost:8111
 *
 * @author Zhe Wang & Jason Liu
 * @param -i identity -s server -c command -p peers which server will connect to
 * @version 1.0 initial version
 */


package unimelb.bitbox;

import java.io.*;
import java.net.Socket;
import java.security.*;
import java.util.Base64;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.kohsuke.args4j.CmdLineParser;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import unimelb.bitbox.util.Document;
import unimelb.bitbox.util.HostPort;

public class Client {

    static Logger log = Logger.getLogger(Client.class.getName());
    static Socket socket = null;
    static Key secretKey = null;
    static boolean quit = false;
    static Communication comm = null;

    public static void main(String[] args) {
        try {
            //parse command line arguments
            CmdLineArgs arguments = new CmdLineArgs();
            CmdLineParser parser = new CmdLineParser(arguments);
            parser.parseArgument(args);
            String user_command = arguments.getCommand();
            HostPort server = new HostPort(arguments.getSever());
            HostPort peer = null;
            if (arguments.getPeer() != null) {
                peer = new HostPort(arguments.getPeer());
            }
            String identity = arguments.getIdentity();

            //initial request
            socket = new Socket(server.host, server.port);
            comm = new Communication(socket);
            Document auth_request = new Document();
            auth_request.append("command", "AUTH_REQUEST");
            auth_request.append("identity", identity);
            sendMessage(auth_request.toJson());

            //wait for response
            while (true) {
                Document response = Document.parse(comm.readString());
                if (response.containsKey("command") && response.getString("command") != null && response.getString("command").equals("AUTH_RESPONSE")) {
                    System.out.println("AUTH RESPONSE RECEIVED: " + response.toJson());
                    if (response.getBoolean("status")) {
                        secretKey = decrypt(getPrivateKey(), response.getString("AES128"));
                        Document user_request = parseUserCommand(user_command, peer);
                        if (quit)
                            break;
                        if (secretKey != null)
                            sendEncrypted(user_request.toJson());
                        else {
                            System.out.println("NO SECRET KEY");
                            break;
                        }
                    } else
                        break;
                }
                //receive the payload and then output
                else if (response.containsKey("payload") && response.getString("payload") != null) {
                    String decrypted_response = decrypt(secretKey, response.getString("payload"));
                    Document decrypted_payload = Document.parse(decrypted_response);
                    String command = decrypted_payload.getString("command");
                    if (command != null && (command.equals("LIST_PEERS_RESPONSE") || command.equals("CONNECT_PEER_RESPONSE") || command.equals("DISCONNECT_PEER_RESPONSE")))
                        System.out.println("RESPONSE RECEIVED: " + decrypted_payload.toJson());
                    else
                        System.out.println("INVALID AUTH RESPONSE RECEIVED, CLIENT QUIT");
                    break;
                } else {
                    System.out.println("INVALID AUTH RESPONSE RECEIVED, CLIENT QUIT");
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static PrivateKey getPrivateKey() {
        try {
            //read the key file and transform to PKCS8
            PEMParser pem_parser = new PEMParser(new FileReader("bitboxclient_rsa"));
            Security.addProvider(new BouncyCastleProvider());
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            Object obj = pem_parser.readObject();
            KeyPair keypair = converter.getKeyPair((PEMKeyPair) obj);
            return keypair.getPrivate();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Decrypt the message to get the AES key
     *
     * @param privateKey, message
     * @return an AES key
     */
    static Key decrypt(PrivateKey privateKey, String message) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(message.getBytes("UTF-8")));
            return new SecretKeySpec(decryptedBytes, "AES");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Decrypt the encrypted message using the AES key to get a plaintext
     *
     * @param secretKey, message
     * @return a plaintext (message)
     */
    static String decrypt(Key secretKey, String message) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decrypted_bytes = cipher.doFinal(Base64.getDecoder().decode(message.getBytes("UTF-8")));
            message = new String(decrypted_bytes);
            message = message.split("\n")[0]; //get the message, ignore the padding
        } catch (Exception e) {
            e.printStackTrace();
        }
        return message;
    }

    static void sendEncrypted(String message) {
        try {
            message = message + "\n";
            byte[] content = message.getBytes("UTF-8");
            int length = 16 - content.length % 16;
            byte[] random = new byte[length]; //random bytes
            byte[] padded = new byte[content.length + length];
            SecureRandom secran = new SecureRandom();
            secran.nextBytes(random);
            System.arraycopy(content, 0, padded, 0, content.length);
            System.arraycopy(random, 0, padded, content.length, random.length); //combine the content and the padding
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(padded);
            Document request = new Document();
            request.append("payload", Base64.getEncoder().encodeToString(encrypted));
            sendMessage(request.toJson());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static Document parseUserCommand(String command, HostPort peer) {
        Document request = new Document();
        switch (command) {
            case "list_peers":
                request.append("command", "LIST_PEERS_REQUEST");
                break;
            case "connect_peer":
                request.append("command", "CONNECT_PEER_REQUEST");
                request.append("host", peer.host);
                request.append("port", peer.port);
                break;
            case "disconnect_peer":
                request.append("command", "DISCONNECT_PEER_REQUEST");
                request.append("host", peer.host);
                request.append("port", peer.port);
                break;
            default:
                System.out.println("INVALID USER COMMAND, CLIENT QUIT");
                quit = true;
        }
        return request;
    }

    static void sendMessage(String message) {
        try {
            comm.sendMessage(message);
        } catch (Exception e) {
            log.info("SENDING MESSAGE " + message + " FAILED");
            e.printStackTrace();
        }
    }
}