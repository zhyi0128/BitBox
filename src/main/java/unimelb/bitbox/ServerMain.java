package unimelb.bitbox;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import unimelb.bitbox.util.Configuration;
import unimelb.bitbox.util.Document;
import unimelb.bitbox.util.FileSystemManager;
import unimelb.bitbox.util.FileSystemObserver;
import unimelb.bitbox.util.FileSystemManager.FileSystemEvent;

public class ServerMain implements FileSystemObserver {
	private static Logger log = Logger.getLogger(ServerMain.class.getName());
	protected FileSystemManager fileSystemManager;
	private static CopyOnWriteArrayList<Connections> peerlist = new CopyOnWriteArrayList<Connections>();
	private HashMap<String,String> rsa_keymap;
	
	public ServerMain() throws NumberFormatException, IOException, NoSuchAlgorithmException {
		fileSystemManager = new FileSystemManager(Configuration.getConfigurationValue("path"),this);
		Thread serve_client = new Thread(new ServeClient()); //open a new thread to serve the client
		serve_client.start();
		tcpInitialConnect();
		Thread sync_event = new TCPSyncEvent();
		sync_event.start();
		tcpListen();
	}

	@Override
	public synchronized void processFileSystemEvent(FileSystemEvent fileSystemEvent) {
		for(int i = 0; i < peerlist.size(); i++) {
			peerlist.get(i).pushQueue(fileSystemEvent); //push events into the queue of each connection
		}
	}
	
	///////////////
	//////TCP//////
	///////////////
	
	private void tcpListen() {
		try {
			ServerSocket listen_socket = new ServerSocket(Integer.parseInt(Configuration.getConfigurationValue("port")));
			while(true) {
				Socket client_socket = listen_socket.accept();
				new Connections(client_socket,fileSystemManager);
				log.info("Peer " + client_socket.getInetAddress().getHostAddress() + ":" + client_socket.getPort() + " is applying for connection!");
			}
		}
		catch(IOException e) {
			log.info("Listen : "+e.getMessage());
		}
	}
	
	//connect to the default peers in the configuration file
	private void tcpInitialConnect() {
		String[] default_peers = Configuration.getConfigurationValue("peers").split(",");
		for(String peer : default_peers) {
			try {
				String[] hostport = peer.split(":");
				Socket socket = new Socket(hostport[0], Integer.parseInt(hostport[1]));
				new Connections(socket, fileSystemManager);
				Document command = new Document();
				Document self_hostport = new Document();
				self_hostport.append("host", Configuration.getConfigurationValue("advertisedName"));
				self_hostport.append("port", Integer.parseInt(Configuration.getConfigurationValue("port")));
	    		command.append("command", "HANDSHAKE_REQUEST");
	    		command.append("hostPort", self_hostport);
	    		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
	    		out.write(command.toJson() + "\n");
	    		out.flush();
			}
			catch(Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	private void tcpConnect(String host, int port) {
		try {
			Socket socket = new Socket(host, port);
			new Connections(socket, fileSystemManager);
			Document command = new Document();
			Document self_hostport = new Document();
			self_hostport.append("host", Configuration.getConfigurationValue("advertisedName"));
			self_hostport.append("port", Integer.parseInt(Configuration.getConfigurationValue("port")));
			command.append("command", "HANDSHAKE_REQUEST");
			command.append("hostPort", self_hostport);
			BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
			out.write(command.toJson() + "\n");
			out.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	protected static synchronized boolean addPeer(Connections con) {
		if(peerlist.size() < Integer.parseInt(Configuration.getConfigurationValue("maximumIncommingConnections"))) {
			peerlist.add(con);
			return true;
		}
		return false;
	}

	public static synchronized boolean duplicateHandShake(String ip, int port) {
		for(int i = 0; i < peerlist.size(); i++) {
			if(peerlist.get(i).client_info[0].equals(ip) && Integer.parseInt(peerlist.get(i).client_info[1]) == port) {
				return true;
			}
		}
		return false;
	}
	
	protected static synchronized void removePeer(Connections con) {
		peerlist.remove(con);
	}
	
	public static synchronized int getConNum() {
		return peerlist.size();
	}
	
	//return the peerlist as a list of json by using the client_info provided by the client (their advertised name and port)
	public static synchronized ArrayList<Document> getpeerlist() {
		ArrayList<Document> peer_array = new ArrayList<Document>();
		for(int i = 0; i < peerlist.size(); i++) {
			Document hostport = new Document();
			hostport.append("host", peerlist.get(i).client_info[2]);
			hostport.append("port", peerlist.get(i).client_info[3]);
			peer_array.add(hostport);
		}
		return peer_array;
	}
	
	public static synchronized boolean checkpeerlist(String host, int port) {
		for(Connections con : peerlist) {
			if(con.client_info[0].equals(host) && Integer.parseInt(con.client_info[1]) == port)
				return true;
		}
		return false;
	}
	
	private static synchronized boolean quitTCPCon(String host, int port) {
		for(Connections con : peerlist) {
			if(con.client_info[0].equals(host) && Integer.parseInt(con.client_info[3]) == port) {
				con.setquit();
				return true;
			}
		}
		return false;
	}
	
	public class TCPSyncEvent extends Thread{
		@Override
		public void run() {
			while(true) {
				try {
					while(ServerMain.getConNum() == 0) {
					//wait for connections to generateSyncEvents
					}
					ArrayList<FileSystemEvent> sync_list = fileSystemManager.generateSyncEvents();
					for(int i = 0; i < sync_list.size(); i++) {
						processFileSystemEvent(sync_list.get(i));
					}
					sleep(1000*Integer.parseInt(Configuration.getConfigurationValue("syncInterval")));
				} 
				catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	///////////////
	//ServeClient//
	///////////////
	
	public class ServeClient implements Runnable{
		private Logger log = Logger.getLogger(ServerMain.class.getName());
		private final String aeskey = "5v8y/B?D(G+KbPeS";
		public ServeClient() {
			//generate keymap
			rsa_keymap = new HashMap<String,String> ();
			String[] keys = Configuration.getConfigurationValue("authorized_keys").split("\n");
			for(String key : keys) {
				String[] split_key = key.split(" ");
				for(int i = 0; i < split_key.length; i++) {
					if(split_key[i].startsWith("AAAA") && i < split_key.length - 1) {
						rsa_keymap.put(split_key[i+1], split_key[i]);
						break;
					}
				}
			}
		}
		public void run() {
			ServerSocket listen_socket;
			try {
				listen_socket = new ServerSocket(Integer.parseInt(Configuration.getConfigurationValue("clientPort")));
				while(true) {
					try {
						Socket client_socket = listen_socket.accept();
						log.info("Client " + client_socket.getInetAddress().getHostAddress() + ":" + client_socket.getPort() + " is applying for connection!");
						boolean release = true;
						BufferedReader in = new BufferedReader(new InputStreamReader(client_socket.getInputStream(), "UTF-8"));
						BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client_socket.getOutputStream(), "UTF-8"));
						Document message = Document.parse(in.readLine());
			    		if(message.getString("command") != null && message.getString("command").equals("AUTH_REQUEST") && message.containsKey("identity")) {
			    			Document response = new Document();
	    					response.append("command", "AUTH_RESPONSE");
	    					String public_key = rsa_keymap.get(message.getString("identity"));
			    			if(public_key != null){
			    				//encrypt the aes key with the client's public key
			    				AuthorizedKeysDecoder decoder = new AuthorizedKeysDecoder();
			    		        PublicKey key = decoder.decodePublicKey(public_key);
			    		        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			    		        cipher.init(Cipher.ENCRYPT_MODE, key);
			    				response.append("AES128", Base64.getEncoder().encodeToString(cipher.doFinal(aeskey.getBytes("UTF-8"))));
				    			response.append("status", true);
				    			response.append("message", "public key found");
				    			release = false; //found public key, ready to receive payload
			    			}
			    			else {
				    			response.append("status", false);
				    			response.append("message", "public key not found");
			    			}
			    			out.write(response.toJson() + "\n");
			    			out.flush();
			    		}
			    		while(!release) {
			    			message = Document.parse(in.readLine());
				    		if(message.getString("payload") != null) {
				    			String payload = message.getString("payload");
				    			Key key = new SecretKeySpec(aeskey.getBytes("UTF-8"), "AES");
								Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
								cipher.init(Cipher.DECRYPT_MODE, key);
				    			Document decrypted_payload = Document.parse(new String(cipher.doFinal(Base64.getDecoder().decode(payload.getBytes("UTF-8")))).split("\n")[0]);
				    			if(decrypted_payload.containsKey("command")) {
				    				Document response = new Document();
					    			switch(decrypted_payload.getString("command")) {
					    				case "LIST_PEERS_REQUEST":
					    					ArrayList<Document> peers;
					    					peers = getpeerlist();
					    					response.append("command", "LIST_PEERS_RESPONSE");
					    					response.append("peers", peers);
					    					Document final_response = new Document();
					    					final_response.append("payload", encryptPayload(response.toJson()));
					    					out.write(final_response.toJson() + "\n");
					    					out.flush();
					    					release = true; //sending response and release the connection
					    					break;
					    					
					    				case "CONNECT_PEER_REQUEST":
					    					if(!decrypted_payload.containsKey("host") || !decrypted_payload.containsKey("port"))
					    						break;
					    					tcpConnect(decrypted_payload.getString("host"), (int) decrypted_payload.getLong("port"));
					    					response.append("command", "CONNECT_PEER_RESPONSE");
					    					response.append("host", decrypted_payload.getString("host"));
					    					response.append("port", decrypted_payload.getLong("port"));
					    					boolean success = false;
											try {
												String client_ip = InetAddress.getByName(decrypted_payload.getString("host")).getHostAddress();
												long starttime = System.currentTimeMillis();
												//if the connection is established within the timeout
												while(System.currentTimeMillis()-starttime < Integer.parseInt(Configuration.getConfigurationValue("udpTimeout"))) {
													if(checkpeerlist(client_ip, (int) decrypted_payload.getLong("port"))){
															success = true;
															break;
													}

													int random_time = ThreadLocalRandom.current().nextInt(100,1000);
													Thread.sleep(random_time);
												}
											} catch (InterruptedException | UnknownHostException e) {
												e.printStackTrace();
											}
											if(success) {
												response.append("status", true);
							    				response.append("message", "connect successful");
											}
											else {
							    				response.append("status", false);
							    				response.append("message", "connect failed");	
											}
					    					final_response = new Document();
					    					final_response.append("payload", encryptPayload(response.toJson()));
					    					out.write(final_response.toJson() + "\n");
						    				out.flush();
					    					release = true;
						    				break;
						    				
					    				case "DISCONNECT_PEER_REQUEST":
					    					if(!decrypted_payload.containsKey("host") || !decrypted_payload.containsKey("port"))
					    						break;
					    					response.append("command", "DISCONNECT_PEER_RESPONSE");
					    					response.append("host", decrypted_payload.getString("host"));
					    					response.append("port", decrypted_payload.getLong("port"));
					    					success = false;
					    					try {
						    					String client_ip = InetAddress.getByName(decrypted_payload.getString("host")).getHostAddress();
						    					if(quitTCPCon(client_ip, (int) decrypted_payload.getLong("port")))
						    							success = true;
						    				} catch(UnknownHostException e) {
						    					e.printStackTrace();
						    				}
					    					if(success) {
												response.append("status", true);
							    				response.append("message", "disconnected from peer");
					    					}
					    					else {
												response.append("status", false);
							    				response.append("message", "connection not active");
					    					}
					    					final_response = new Document();
					    					final_response.append("payload", encryptPayload(response.toJson()));
					    					out.write(final_response.toJson() + "\n");
						    				out.flush();
					    					release = true;
					    					break;
					    			}
				    			}
				    		}
			    		}
					}
					catch (Exception e) {
					e.printStackTrace();
					}
				}
			} catch (NumberFormatException | IOException e) {
				e.printStackTrace();
			}
		}
		
		private String encryptPayload(String msg){
			String message = null;
			try {
	    		Key key = new SecretKeySpec(aeskey.getBytes("UTF-8"), "AES");
				Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
				cipher.init(Cipher.ENCRYPT_MODE, key);
				msg = msg + "\n";
				byte[] content = msg.getBytes("UTF-8");
				int length = 16 - content.length % 16;
				byte[] random = new byte[length];
				byte[] padded = new byte[content.length+length];
				SecureRandom secran = new SecureRandom();
				secran.nextBytes(random);
				System.arraycopy(content, 0, padded, 0, content.length);  
		        System.arraycopy(random, 0, padded, content.length, random.length);      
				message = Base64.getEncoder().encodeToString(cipher.doFinal(padded));
			} catch (Exception e) {
				e.printStackTrace();
			}			
			return message;
		}
	}	
}