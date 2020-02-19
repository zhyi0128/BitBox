package unimelb.bitbox;

import org.kohsuke.args4j.Option;

/**
 * A class for converting args from cmd.
 *
 * @author Jason Liu
 * @version 1.0 initial version
 */

public class CmdLineArgs {
    @Option(required = true, name = "-c", usage = "Command")
    private String command;

    @Option(required = true, name = "-s", usage = "Server")
    private String server;

    @Option(required = false, name = "-p", usage = "Peer")
    private String peer;

    @Option(required = true, name = "-i", usage = "Identity")
    private String identity;

    public String getCommand() {
        return command;
    }

    public String getSever() {
        return server;
    }

    public String getPeer() {
        return peer;
    }

    public String getIdentity() {
        return identity;
    }
}