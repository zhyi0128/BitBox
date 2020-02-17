package unimelb.bitbox;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import unimelb.bitbox.util.Configuration;
import unimelb.bitbox.util.Document;
import unimelb.bitbox.util.FileSystemManager;
import unimelb.bitbox.util.FileSystemManager.FileSystemEvent;

public class Connections extends Thread{
	
	private static Logger log = Logger.getLogger(ServerMain.class.getName());
	private FileSystemManager fileSystemManager;
	private Socket client_socket;
	protected String[] client_info;
	private BlockingQueue<FileSystemEvent> queue;
	private volatile boolean quit = false;
	private boolean connection_establish = false;
	private BufferedReader in;
	private BufferedWriter out;
	public Connections(Socket s, FileSystemManager fm) {
		client_socket = s;
		fileSystemManager = fm;
		queue = new ArrayBlockingQueue<FileSystemEvent>(1024);
		client_info = new String[4];
		client_info[0] = client_socket.getInetAddress().getHostAddress();
		client_info[1] = Integer.toString(client_socket.getPort());
		client_info[2] = null; //default client advertised name
		client_info[3] = null; //default client advertised port
		start();
	}
	
	public void run() {				
		while(true) {
			try {				
				if(quit) { //close the connection
					log.info("Peer " + client_info[0] + ":" + client_info[1] + " Quit");
					ServerMain.removePeer(this);
					client_socket.close();
					break;
				}
				in = new BufferedReader(new InputStreamReader(client_socket.getInputStream(), "UTF-8"));
				//if there are messages to be read
			    if(in.ready()) {
			    	while(in.ready()) {
			    		String msg = in.readLine();
			    		Document message = Document.parse(msg);
			    		log.info("COMMAND RECEIVED: " + message.toJson());
			    		parseCommandSend(message);
			    	}
			    }
			    //if there are events to be broadcast
				if(!queue.isEmpty()) {
					while(!queue.isEmpty()) {
						try {
							processEvent((FileSystemEvent) queue.take());
						}
						catch(Exception e) { // catch other exceptions when dealing with one event to make the program robust
							e.printStackTrace();
						}
					}
				}
				int random_time = ThreadLocalRandom.current().nextInt(100,1000);
				sleep(random_time);
			}
			catch(InterruptedException | IOException e) {
				e.printStackTrace();
			}
		}
	}	
	
	//mainly used for the processEvent method, allow it push events to the queue
	public boolean pushQueue(FileSystemEvent event) {
		try {
			queue.put(event);
			return true;
			} 
		catch (InterruptedException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	private void processEvent(FileSystemEvent event) throws SocketException {
		if(event.event == FileSystemManager.EVENT.FILE_CREATE)
			fileCreateRequest(event.fileDescriptor.toDoc(), event.pathName);
		else if(event.event == FileSystemManager.EVENT.FILE_DELETE)
			fileDelRequest(event.fileDescriptor.toDoc(), event.pathName);
		else if(event.event == FileSystemManager.EVENT.FILE_MODIFY)
			fileModifyRequest(event.fileDescriptor.toDoc(), event.pathName);	
		else if(event.event == FileSystemManager.EVENT.DIRECTORY_CREATE)
			dirCreateRequest(event.pathName);
		else if(event.event == FileSystemManager.EVENT.DIRECTORY_DELETE)
			dirDelRequest(event.pathName);
	}
	
	//if a message is about to be sent, usually this method is called
	private boolean sendMessage(Document msg) {
		try {
			out = new BufferedWriter(new OutputStreamWriter(client_socket.getOutputStream(), "UTF-8"));
		    out.write(msg.toJson()+"\n");
			out.flush();
			log.info("COMMAND SENT: " + msg.toJson());
			return true;
		}
		catch (SocketException e) { //check the connection status when sending message
		    setquit();
		    log.info("SocketException catched, connection may be closed by the other peer");
		}
		catch (IOException e) {
		    e.printStackTrace();
		}
		return false;
	}
	
	//parse command from the peer and handle the request
	private boolean parseCommandSend(Document message) {
		Document respond = new Document();
		String client_command = message.getString("command");
		if(client_command == null) {
			return invalidProtocol("message must contain a command field as string");
		}
		else {
			//if the first message is not handshake request or connection related protocol, send invalid protocol
			if(!connection_establish && !client_command.equals("HANDSHAKE_REQUEST") && !client_command.equals("HANDSHAKE_RESPONSE") && !client_command.equals("INVALID_PROTOCOL") && !client_command.equals("CONNECTION_REFUSED"))
				return invalidProtocol("no handshake request, connection is not established");
			Document client_hostport = (Document) message.get("hostPort");
			Document client_filedescriptor = (Document) message.get("fileDescriptor");
			String client_pathname = message.getString("pathName");
			switch(client_command) {
				case "HANDSHAKE_REQUEST":
					if(client_hostport == null || client_hostport.getString("host") == null || !client_hostport.containsKey("port"))
						return invalidProtocol("message must contain a hostPort field as string");
					connection_establish = true;
					String ip = client_socket.getInetAddress().getHostAddress();
					int port = client_socket.getPort();
					if(ServerMain.duplicateHandShake(ip, port)) //check if the handshake is duplicated by providing ip and port information from the socket
						return invalidProtocol("duplicated handshake");		
					//if add peer to the peerlist successfully, send handshake_response
					if (ServerMain.addPeer(this)) {
						Document hostport = new Document();
						hostport.append("host", Configuration.getConfigurationValue("advertisedName"));
						hostport.append("port", Integer.parseInt(Configuration.getConfigurationValue("port")));	
						client_info[2] = client_hostport.getString("host");
						client_info[3] = Long.toString(client_hostport.getLong("port"));
						respond.append("command", "HANDSHAKE_RESPONSE");
						respond.append("hostPort", hostport);
					}
					else {
						respond.append("command", "CONNECTION_REFUSED");
						respond.append("message", "connection limit reached");
						respond.append("peers", ServerMain.getpeerlist());
						setquit();
					}
					return sendMessage(respond);
					
				case "HANDSHAKE_RESPONSE":
					if(client_hostport == null || client_hostport.getString("host") == null || !client_hostport.containsKey("port"))
						return invalidProtocol("message must contain a hostPort field as string");
					//if a peer receive a response but add peer to peerlist failed, then max limit reached, so this peer quit and the other peer will find out soon
					if(!ServerMain.addPeer(this))
						setquit();
					else {
						client_info[2] = client_hostport.getString("host");
						client_info[3] = Long.toString(client_hostport.getLong("port"));
					}
					connection_establish = true;
					break;
					
				case "FILE_CREATE_REQUEST":
					if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null)
						return invalidProtocol("message must contain related fields");
					return createFileRequestByte(client_filedescriptor, client_pathname);
					
				case "FILE_CREATE_RESPONSE":
					if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null || !message.containsKey("message") || !message.containsKey("status"))
						return invalidProtocol("message must contain related fields");
					break;
				
				case "FILE_BYTES_REQUEST":
					if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null || !message.containsKey("position") || !message.containsKey("length"))
						return invalidProtocol("message must contain related fields");
					return sendFileContent(message);
					
				case "FILE_BYTES_RESPONSE":
					try {
						if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null || !message.containsKey("position") || !message.containsKey("length") || !message.containsKey("content") || !message.containsKey("message") || !message.containsKey("status")) {
							fileSystemManager.cancelFileLoader(client_pathname); //if response failed, delete file loader.
							log.info("incorretct file bytes response, loader canceled");
							return invalidProtocol("message must contain related fields");
						}
						else if(message.getBoolean("status") == true) //only response status is correct, then write
							return writeFileRequestByte(message);
						else {
							log.info("incorretct file bytes response, loader canceled");
							fileSystemManager.cancelFileLoader(client_pathname); //if response failed, delete file loader.
						}
					}
					catch(IOException e) {
						e.printStackTrace();
					}
					break;
					
				case "FILE_DELETE_REQUEST":
					if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null)
						return invalidProtocol("message must contain related fields");
					return delFile(client_filedescriptor, client_pathname);
				
				case "FILE_DELETE_RESPONSE":
					if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null || !message.containsKey("message") || !message.containsKey("status"))
						return invalidProtocol("message must contain related fields");
					break;
					
				case "FILE_MODIFY_REQUEST":
					if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null)
						return invalidProtocol("message must contain related fields");
					return modifyFileRequestByte(client_filedescriptor, client_pathname);
				
				case "FILE_MODIFY_RESPONSE":
					if(client_filedescriptor == null || client_filedescriptor.getString("md5") == null || !client_filedescriptor.containsKey("lastModified") || !client_filedescriptor.containsKey("fileSize") || client_pathname == null || !message.containsKey("message") || !message.containsKey("status"))
						return invalidProtocol("message must contain related fields");
					break;
					
				case "DIRECTORY_CREATE_REQUEST":
					if(client_pathname == null)
						return invalidProtocol("message must contain a pathName field as string");
					return createDirectory(client_pathname);
				
				case "DIRECTORY_CREATE_RESPONSE":
					if(client_pathname == null || !message.containsKey("message") || !message.containsKey("status"))
						return invalidProtocol("message must contain a pathName field as string");
					break;
				
				case "DIRECTORY_DELETE_REQUEST":
					if(client_pathname == null)
						return invalidProtocol("message must contain a pathName field as string");
					return delDirectory(client_pathname);
				
				case "DIRECTORY_DELETE_RESPONSE":
					if(client_pathname == null || !message.containsKey("message") || !message.containsKey("status"))
						return invalidProtocol("message must contain a pathName field as string");
					break;
				
				case "INVALID_PROTOCOL":
					log.info("invalid protocol received, quit");
					setquit();
					break;
					
				case "CONNECTION_REFUSED":
					log.info("connection refuse received, quit");
					setquit();
					break;
					
				default:
					return invalidProtocol("invalid protocol name");				
			}
			return false;
		}
	}
	
	private boolean invalidProtocol (String message) {
		log.info("invalid protocol, quit");
		Document respond = new Document();
		respond.append("command", "INVALID_PROTOCOL");
		respond.append("message", message);
		setquit();
		return sendMessage(respond);
	}
	
	private boolean fileCreateRequest(Document file_descriptor, String pathname) {
		Document request = new Document();
		request.append("command", "FILE_CREATE_REQUEST");
		request.append("fileDescriptor", file_descriptor);
		request.append("pathName", pathname);
		return sendMessage(request);
	}
	
	private boolean createFileRequestByte(Document file_descriptor, String pathname) {
		try {
			Document respond = new Document();
			if(!fileSystemManager.isSafePathName(pathname)) {
				respond.append("command", "FILE_CREATE_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("message", "unsafe pathName");
				respond.append("status", false);
				return sendMessage(respond);
			}
			
			else if(fileSystemManager.fileNameExists(pathname)) {
				respond.append("command", "FILE_CREATE_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				if(fileSystemManager.fileNameExists(pathname, file_descriptor.getString("md5"))) {
					respond.append("message", "same file content exists, no need to create");
					respond.append("status", false);
				}
				else if(modifyFileRequestByte(file_descriptor, pathname)) {
					respond.append("message", "file exists but has new timestamp and content, modifyloader created successfully to overwrite");
					respond.append("status", true);
				}
				else {
					respond.append("message", "file exists but creating modifyloader failed");
					respond.append("status", false);
				}					
				return sendMessage(respond);
			}	
			
			else if(fileSystemManager.createFileLoader(pathname, file_descriptor.getString("md5"), file_descriptor.getLong("fileSize"), file_descriptor.getLong("lastModified"))) {
				//firstly send file_create_response and check shortcut then send file_bytes_request
				respond.append("command", "FILE_CREATE_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("status", true);
				if(fileSystemManager.checkShortcut(pathname)) {
					respond.append("message", "shortcut found, no need to create");
					return sendMessage(respond);
				}
				else {
					respond.append("message", "successfully creating fileloader");
					Document request = new Document();
					request.append("command", "FILE_BYTES_REQUEST");
					request.append("fileDescriptor", file_descriptor);
					request.append("pathName", pathname);
					request.append("position", 0);
					int blocksize = Integer.parseInt(Configuration.getConfigurationValue("blockSize"));
					if(file_descriptor.getLong("fileSize") <= blocksize)
						request.append("length", file_descriptor.getLong("fileSize"));
					else
						request.append("length", blocksize);
					return(sendMessage(respond) && sendMessage(request));
				}
				
			}
			else {
				respond.append("command", "FILE_CREATE_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("message", "creating fileloader failed");
				respond.append("status",false);
				return sendMessage(respond);
			}
		}
		catch(NoSuchAlgorithmException | IOException e){
			e.printStackTrace();
		}
		return false;
	}
	
	private boolean sendFileContent(Document message) {
		try {
			Document request = new Document();
			Document file_descriptor = (Document) message.get("fileDescriptor");
			request.append("command", "FILE_BYTES_RESPONSE");
			request.append("fileDescriptor", file_descriptor);
			request.append("pathName", message.getString("pathName"));
			long position = message.getLong("position");
			long length = message.getLong("length");
			request.append("position", position);
			request.append("length", length);
			ByteBuffer buf = fileSystemManager.readFile(file_descriptor.getString("md5"), position, length);
			if(buf != null) {
				request.append("status", true);
				request.append("message", "successful read");
				request.append("content", Base64.getEncoder().encodeToString(buf.array()));
			}
			else {
				request.append("status", false);
				request.append("message", "read fail");
				request.append("content", "");
			}
			return sendMessage(request);
		}
		catch(NoSuchAlgorithmException | IOException e){
			e.printStackTrace();
		}
		return false;
	}
	
	private boolean writeFileRequestByte(Document message) {
		try {
			long position = message.getLong("position");
			String pathname = message.getString("pathName");
			ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode((String) message.get("content")));
			if(fileSystemManager.writeFile(pathname, buf, position)) {
				Document request = new Document();
				Document file_descriptor = (Document) message.get("fileDescriptor");
				request.append("command", "FILE_BYTES_REQUEST");
				request.append("fileDescriptor", file_descriptor);
				request.append("pathName", pathname);
				long newposition = position + message.getLong("length");
				request.append("position", newposition );
				long size = file_descriptor.getLong("fileSize");
				int blocksize = Integer.parseInt(Configuration.getConfigurationValue("blockSize"));
				if(newposition < size) {
					if(size - newposition >= blocksize) 
						request.append("length", blocksize);
					else
						request.append("length", size - newposition);
					return sendMessage(request);
				}
				else 
					fileSystemManager.checkWriteComplete(pathname);
			}
			else {
				log.info("writing file failed, file loader canceled");
				fileSystemManager.cancelFileLoader(pathname);
			}
		}
		catch(NoSuchAlgorithmException | IOException e){
			e.printStackTrace();
		}
		return false;
	}
	
	private boolean fileDelRequest(Document file_descriptor, String pathname) {
		Document request = new Document();
		request.append("command", "FILE_DELETE_REQUEST");
		request.append("fileDescriptor", file_descriptor);
		request.append("pathName", pathname);
		return sendMessage(request);
	}
	
	private boolean delFile(Document file_descriptor, String pathname) {
		Document respond = new Document();
		if(!fileSystemManager.isSafePathName(pathname)) {
			respond.append("command", "FILE_DELETE_RESPONSE");
			respond.append("fileDescriptor", file_descriptor);
			respond.append("pathName", pathname);
			respond.append("message", "unsafe pathName");
			respond.append("status", false);
		}
		else if(!fileSystemManager.fileNameExists(pathname)) {
			respond.append("command", "FILE_DELETE_RESPONSE");
			respond.append("fileDescriptor", file_descriptor);
			respond.append("pathName", pathname);
			respond.append("message", "file not exists");
			respond.append("status", false);
		}
		else if(fileSystemManager.deleteFile(pathname, file_descriptor.getLong("lastModified"), file_descriptor.getString("md5"))) {
			respond.append("command", "FILE_DELETE_RESPONSE");
			respond.append("fileDescriptor", file_descriptor);
			respond.append("pathName", pathname);
			respond.append("message", "successfully deleting file");
			respond.append("status", true);
		}
		else {
			respond.append("command", "FILE_DELETE_RESPONSE");
			respond.append("fileDescriptor", file_descriptor);
			respond.append("pathName", pathname);
			respond.append("message", "deleting failed");
			respond.append("status", false);
		}
		return sendMessage(respond);
	}

	private boolean fileModifyRequest(Document file_descriptor, String pathname) {
		Document request = new Document();
		request.append("command", "FILE_MODIFY_REQUEST");
		request.append("fileDescriptor", file_descriptor);
		request.append("pathName", pathname);
		return sendMessage(request);
	}

	private boolean modifyFileRequestByte(Document file_descriptor, String pathname) {
		try {
			Document respond = new Document();
			if(!fileSystemManager.isSafePathName(pathname)) {
				respond.append("command", "FILE_MODIFY_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("message", "unsafe pathName");
				respond.append("status", false);
				return sendMessage(respond);
			}			
			else if(!fileSystemManager.fileNameExists(pathname)) {
				respond.append("command", "FILE_MODIFY_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("message", "file does not exists");
				respond.append("status", false);
				return sendMessage(respond);
			}			
			
			else if(fileSystemManager.fileNameExists(pathname, file_descriptor.getString("md5"))) {
				respond.append("command", "FILE_MODIFY_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("message", "same file content exists, no need to modify");
				respond.append("status", false);
				return sendMessage(respond);
			}
			
			else if(fileSystemManager.modifyFileLoader(pathname, file_descriptor.getString("md5"), file_descriptor.getLong("lastModified"))) {
				//firstly send file_create_response, then send file_bytes_request
				respond.append("command", "FILE_MODIFY_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("message", "successfully creating modifyloader");
				respond.append("status", true);
				Document request = new Document();
				request.append("command", "FILE_BYTES_REQUEST");
				request.append("fileDescriptor", file_descriptor);
				request.append("pathName", pathname);
				request.append("position", 0);
				int blocksize = Integer.parseInt(Configuration.getConfigurationValue("blockSize"));
				if(file_descriptor.getLong("fileSize") <= blocksize)
					request.append("length", file_descriptor.getLong("fileSize"));
				else
					request.append("length", blocksize);
				return(sendMessage(respond) && sendMessage(request));
			}
			else {
				respond.append("command", "FILE_MODIFY_RESPONSE");
				respond.append("fileDescriptor", file_descriptor);
				respond.append("pathName", pathname);
				respond.append("message", "creating modifyloader failed");
				respond.append("status", false);
				return sendMessage(respond);
			}
		}
		catch(IOException e){
			e.printStackTrace();
		}
		return false;
	}
	
	private boolean dirCreateRequest(String pathname) {
		Document request = new Document();
		request.append("command", "DIRECTORY_CREATE_REQUEST");
		request.append("pathName", pathname);
		return sendMessage(request);
	}
	
	private boolean createDirectory(String pathname) {
		Document respond = new Document();
		if(!fileSystemManager.isSafePathName(pathname)) {
			respond.append("command", "DIRECTORY_CREATE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "unsafe pathName");
			respond.append("status", false);
		}
		else if(fileSystemManager.dirNameExists(pathname)) {
			respond.append("command", "DIRECTORY_CREATE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "dir already exists");
			respond.append("status", false);
		}
		else if(fileSystemManager.makeDirectory(pathname)) {
			respond.append("command", "DIRECTORY_CREATE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "successfully creating path");
			respond.append("status", true);
		}
		else {
			respond.append("command", "DIRECTORY_CREATE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "creating dir failed");
			respond.append("status", false);
		}
		return sendMessage(respond);
	}

	private boolean dirDelRequest(String pathname) {
		Document request = new Document();
		request.append("command", "DIRECTORY_DELETE_REQUEST");
		request.append("pathName", pathname);
		return sendMessage(request);
	}
	
	private boolean delDirectory(String pathname) {
		Document respond = new Document();
		if(!fileSystemManager.isSafePathName(pathname)) {
			respond.append("command", "DIRECTORY_DELETE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "unsafe pathName");
			respond.append("status", false);
		}
		else if(!fileSystemManager.dirNameExists(pathname)) {
			respond.append("command", "DIRECTORY_DELETE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "dir not exists");
			respond.append("status", false);
		}
		else if(fileSystemManager.deleteDirectory(pathname)) {
			respond.append("command", "DIRECTORY_DELETE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "successfully deleting path");
			respond.append("status", true);
		}
		else {
			respond.append("command", "DIRECTORY_DELETE_RESPONSE");
			respond.append("pathName", pathname);
			respond.append("message", "deleting failed");
			respond.append("status", false);
		}
		return sendMessage(respond);
	}
	
	protected void setquit() {
		quit = true;
	}
}
