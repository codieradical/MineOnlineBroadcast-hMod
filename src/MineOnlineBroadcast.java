import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedList;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MineOnlineBroadcast extends Plugin {
    private static String NAME = "MineOnlineBroadcast";
    Thread broadcastThread;
    public static long lastPing;
    MineOnlineBroadcastListener listener;
    Logger log;
    String md5;

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

    public static String listServer(
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
            JSONObject jsonObject = new JSONObject();
            if (ip != null)
                jsonObject.put("ip", ip);
            jsonObject.put("port", port);
            if (users > -1)
                jsonObject.put("users", users);
            jsonObject.put("max", maxUsers);
            jsonObject.put("name", name);
            jsonObject.put("onlinemode", onlineMode);
            jsonObject.put("md5", md5);
            jsonObject.put("whitelisted", whitelisted);
            jsonObject.put("players", playerNames);

            String json = jsonObject.toString();

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

            JSONObject resObject = new JSONObject(response.toString());
            return resObject.has("uuid") ? resObject.getString("uuid") : null;
        } catch (Exception e) {

            e.printStackTrace();
            return null;
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
    }

    private void register(PluginLoader.Hook hook, PluginListener.Priority priority) {
        etc.getLoader().addListener(hook, this.listener, this, priority);
    }

    private void register(PluginLoader.Hook hook) {
        this.register(hook, PluginListener.Priority.MEDIUM);
    }

    public void disable() {
        broadcastThread.interrupt();
    }
}
