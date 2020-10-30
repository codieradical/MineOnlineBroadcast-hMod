import codie.mineonline.ProxyThread;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedList;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MineOnlineBroadcast extends Plugin {
    private static String NAME = "MineOnlineBroadcast";
    Thread broadcastThread;
    public static long lastPing;
    MineOnlineBroadcastListener listener;
    Logger log;
    String md5;
    ProxyThread proxyThread;

    public void launchProxy() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        proxyThread = new ProxyThread(serverSocket);
        proxyThread.start();

        System.out.println("Enabling online-mode fix.");

        System.setProperty("http.proxyHost", serverSocket.getInetAddress().getHostAddress());
        System.setProperty("http.proxyPort", "" + serverSocket.getLocalPort());
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
    }

    public void stopProxy() {
        if (proxyThread != null) {
            proxyThread.stop();
            proxyThread = null;
        }
    }

    public static byte[] createChecksum(String filename) throws Exception {
        InputStream fis =  new FileInputStream(filename);

        byte[] buffer = new byte[1024];
        MessageDigest complete = MessageDigest.getInstance("MD5");
        int numRead;

        do {
            numRead = fis.read(buffer);
            if (numRead > 0) {
                complete.update(buffer, 0, numRead);
            }
        } while (numRead != -1);

        fis.close();
        return complete.digest();
    }

    public static String getMD5ChecksumForFile(String filename) throws Exception {
        byte[] b = createChecksum(filename);
        String result = "";

        for (int i=0; i < b.length; i++) {
            result += Integer.toString( ( b[i] & 0xff ) + 0x100, 16).substring( 1 );
        }
        return result.toUpperCase();
    }

    public static void listServer(
            String ip,
            String port,
            int users,
            int maxUsers,
            String name,
            boolean onlineMode,
            String md5,
            boolean whitelisted,
            String[] playerNames
    ) {
        HttpURLConnection connection = null;

        try {
            URLClassLoader classLoader = new URLClassLoader(new URL[] { MineOnlineBroadcast.class.getProtectionDomain().getCodeSource().getLocation() });

            Class jsonObjectClass = classLoader.loadClass("org.json.JSONObject");

            Constructor jsonObjectConstructor = jsonObjectClass.getConstructor();
            Method jsonObjectPut = jsonObjectClass.getMethod("put", String.class, Object.class);
            Method jsonObjectToString = jsonObjectClass.getMethod("toString");

            Object jsonObject = jsonObjectConstructor.newInstance();
            if (ip != null)
                jsonObjectPut.invoke(jsonObject, "ip", ip);
            jsonObjectPut.invoke(jsonObject, "port", port);
            if (users > -1)
                jsonObjectPut.invoke(jsonObject, "users", users);
            jsonObjectPut.invoke(jsonObject, "max", maxUsers);
            jsonObjectPut.invoke(jsonObject, "name", name);
            jsonObjectPut.invoke(jsonObject, "onlinemode", onlineMode);
            jsonObjectPut.invoke(jsonObject, "md5", md5);
            jsonObjectPut.invoke(jsonObject, "whitelisted", whitelisted);
            jsonObjectPut.invoke(jsonObject, "players", playerNames);

            String json = (String)jsonObjectToString.invoke(jsonObject);

            URL url = new URL("https://mineonline.codie.gg/api/servers");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);

            connection.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().flush();
            connection.getOutputStream().close();

            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            rd.close();
        } catch (Exception e) {

            e.printStackTrace();
        } finally {

            if (connection != null)
                connection.disconnect();
        }
    }

    public void enable() {
        initialize();

        this.setName(NAME);

        this.log = Logger.getLogger("Minecraft");

        this.log.info("Enabled MineOnlineBroadcast");

        try {
            md5 = getMD5ChecksumForFile("minecraft_server.jar");
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        broadcastThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    if (System.currentTimeMillis() - MineOnlineBroadcast.lastPing > 45000) {
                        lastPing = System.currentTimeMillis();
                        try {
                            PropertiesFile propertiesFile = new PropertiesFile("server.properties");
                            propertiesFile.load();

                            boolean isPublic = propertiesFile.getBoolean("public", true);
                            if(!isPublic)
                                return;

                            String ip = propertiesFile.getString("serverlist-ip", propertiesFile.getString("server-ip", propertiesFile.getString("ip", null)));
                            String port = propertiesFile.getString("serverlist-port", propertiesFile.getString("server-port", propertiesFile.getString("port", "25565")));
                            int users = etc.getServer().getPlayerList().size();
                            int maxUsers = propertiesFile.getInt("max-players", 20);
                            String name = propertiesFile.getString("server-name", "Minecraft Server");
                            boolean onlineMode = propertiesFile.getBoolean("online-mode", true);
                            //String md5; handled on enable
                            boolean whitelisted = propertiesFile.getBoolean("whitelist", false);

                            String[] playerNames = etc.getServer().getPlayerList().stream().map(player -> player.getName()).collect(Collectors.toList()).toArray(new String[users]);

                            listServer(
                                    ip,
                                    port,
                                    users,
                                    maxUsers,
                                    name,
                                    onlineMode,
                                    md5,
                                    whitelisted,
                                    playerNames
                            );
                        } catch (IOException ex) {
                            //ex.printStackTrace();
                            // ignore.
                        }
                    }
                }
            }
        });

        broadcastThread.start();
    }

    public void initialize() {
        this.log = Logger.getLogger("Minecraft");
        this.listener = new MineOnlineBroadcastListener(this);
        this.register(PluginLoader.Hook.DISCONNECT);
        this.register(PluginLoader.Hook.LOGIN);
        this.register(PluginLoader.Hook.KICK);
        try {
            PropertiesFile propertiesFile = new PropertiesFile("server.properties");
            propertiesFile.load();
            boolean onlineMode = propertiesFile.getBoolean("online-mode", true);

            if (onlineMode)
                launchProxy();
        } catch (Exception ex) {
            log.warning("Failed to enable online-mode fix. Authentication may fail.");
        }
    }

    private void register(PluginLoader.Hook hook, PluginListener.Priority priority) {
        etc.getLoader().addListener(hook, this.listener, this, priority);
    }

    private void register(PluginLoader.Hook hook) {
        this.register(hook, PluginListener.Priority.MEDIUM);
    }

    public void disable() {
        broadcastThread.interrupt();
        stopProxy();
    }
}
