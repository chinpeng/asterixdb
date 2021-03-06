/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.control.nc.service;

import org.apache.commons.lang3.SystemUtils;
import org.apache.hyracks.control.common.controllers.IniUtils;
import org.ini4j.Ini;
import org.kohsuke.args4j.CmdLineParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stand-alone process which listens for configuration information from the
 * CC and starts an NC. Intended to be a constantly-running service.
 */
public class NCService {

    private static final Logger LOGGER = Logger.getLogger(NCService.class.getName());

    /**
     * The .ini read from the CC (*not* the ncservice.ini file)
     */
    private static Ini ini = new Ini();

    /**
     * ID of *this* NC
     */
    private static String ncId = "";

    /**
     * The Ini section representing *this* NC
     */
    private static String nodeSection = null;

    /**
     * The NCServiceConfig
     */
    private static NCServiceConfig config;

    /**
     * The child Process, if one is active
     */
    private static Process proc = null;

    private static final String MAGIC_COOKIE = "hyncmagic";

    private static List<String> buildCommand() throws IOException {
        List<String> cList = new ArrayList<String>();

        // Find the command to run. For now, we allow overriding the name, but
        // still assume it's located in the bin/ directory of the deployment.
        // Even this is likely more configurability than we need.
        String command = IniUtils.getString(ini, nodeSection, "command", "hyracksnc");
        // app.home is specified by the Maven appassembler plugin. If it isn't set,
        // fall back to user's home dir. Again this is likely more flexibility
        // than we need.
        String apphome = System.getProperty("app.home", System.getProperty("user.home"));
        String path = apphome + File.separator + "bin" + File.separator;
        if (SystemUtils.IS_OS_WINDOWS) {
            cList.add(path + command + ".bat");
        } else {
            cList.add(path + command);
        }

        cList.add("-config-file");
        // Store the Ini file from the CC locally so NCConfig can read it.
        // QQQ should arrange to delete this when done
        File tempIni = File.createTempFile("ncconf", ".conf");
        ini.store(tempIni);
        cList.add(tempIni.getCanonicalPath());
        return cList;
    }

    private static void configEnvironment(Map<String,String> env) {
        if (env.containsKey("JAVA_OPTS")) {
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Keeping JAVA_OPTS from environment");
            }
            return;
        }
        String jvmargs = IniUtils.getString(ini, nodeSection, "jvm.args", "-Xmx1536m");
        env.put("JAVA_OPTS", jvmargs);
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Setting JAVA_OPTS to " + jvmargs);
        }
    }

    /**
     * Attempts to launch the "real" NCDriver, based on the configuration
     * information gathered so far.
     * @return true if the process was successfully launched and has now
     * exited with a 0 (normal) exit code. false if some configuration error
     * prevented the process from being launched or the process returned
     * a non-0 (abnormal) exit code.
     */
    private static boolean launchNCProcess() {
        try {
            ProcessBuilder pb = new ProcessBuilder(buildCommand());
            configEnvironment(pb.environment());
            // QQQ inheriting probably isn't right
            pb.inheritIO();

            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Launching NCDriver process");
            }

            // Logfile
            if (! "-".equals(config.logdir)) {
                pb.redirectErrorStream(true);
                File log = new File(config.logdir);
                if (! log.mkdirs()) {
                    if (! log.isDirectory()) {
                        throw new IOException(config.logdir + ": cannot create");
                    }
                    // If the directory IS there, all is well
                }
                File logfile = new File(config.logdir, "nc-" + ncId + ".log");
                // Don't care if this succeeds or fails:
                logfile.delete();
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logfile));
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("Logging to " + logfile.getCanonicalPath());
                }
            }
            proc = pb.start();

            boolean waiting = true;
            int retval = 0;
            while (waiting) {
                try {
                    retval = proc.waitFor();
                    waiting = false;
                } catch (InterruptedException ignored) {
                }
            }
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("NCDriver exited with return value " + retval);
            }
            return (retval == 0);
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.SEVERE)) {
                LOGGER.log(Level.SEVERE, "Configuration from CC broken", e);
            }
            return false;
        }
    }

    private static boolean acceptConnection(InputStream is) {
        // Simple on-wire protocol: magic cookie (string), CC address (string),
        // port (string), as encoded on CC by ObjectOutputStream. If we see
        // anything else or have any error, crap out and await a different
        // connection.
        // QQQ This should probably be changed to directly accept the full
        // config file from the CC, rather than calling back to the CC's
        // "config" webservice to retrieve it. Revisit when the CC is fully
        // parsing and validating the master config file.
        try {
            ObjectInputStream ois = new ObjectInputStream(is);
            String magic = ois.readUTF();
            if (! MAGIC_COOKIE.equals(magic)) {
                LOGGER.severe("Connection used incorrect magic cookie");
                return false;
            }
            String iniString = ois.readUTF();
            ini = new Ini(new StringReader(iniString));
            ncId = IniUtils.getString(ini, "localnc", "id", "");
            nodeSection = "nc/" + ncId;
            return launchNCProcess();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error decoding connection from server", e);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        // Register a shutdown hook which will kill the NC if the NC Service is killed.
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (proc != null) {
                    proc.destroy();
                }
            }
        });
        config = new NCServiceConfig();
        CmdLineParser cp = new CmdLineParser(config);
        try {
            cp.parseArgument(args);
        } catch (Exception e) {
            e.printStackTrace();
            cp.printUsage(System.err);
            System.exit(1);
        }
        config.loadConfigAndApplyDefaults();

        // For now we implement a trivial listener which just
        // accepts an IP/port combination from the CC. This could
        // be made more advanced in several ways depending on whether
        // we want to expand the functionality of this service.
        // For now this gets the job done, without radically changing
        // the NC itself so that Managix can continue to function.
        InetAddress addr = config.address == null ? null : InetAddress.getByName(config.address);
        int port = config.port;

        // Loop forever - the NCService will always return to "waiting for CC" state
        // when the child NC terminates for any reason.
        while (true) {
            ServerSocket listener = new ServerSocket(port, 5, addr);
            try {
                boolean launched = false;
                while (!launched) {
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("Waiting for connection from CC on " + addr + ":" + port);
                    }
                    Socket socket = listener.accept();
                    try {
                        // QQQ Because acceptConnection() doesn't return if the
                        // service is started appropriately, the socket remains
                        // open but non-responsive.
                        launched = acceptConnection(socket.getInputStream());
                    } finally {
                        socket.close();
                    }
                }
            } finally {
                listener.close();
            }
        }
    }
}
