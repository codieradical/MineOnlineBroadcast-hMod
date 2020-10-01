import java.util.ArrayList;
import java.util.logging.Logger;

public class MineOnlineBroadcastListener extends PluginListener {
    private static ArrayList<String> PlayerList = new ArrayList();
    boolean _DEBUG = false;
    private Logger log;
    private MineOnlineBroadcast parent = null;

    public MineOnlineBroadcastListener(MineOnlineBroadcast parent) {
        this.parent = parent;
        this.log = Logger.getLogger("Minecraft");
    }

    public void onKick(Player mod, Player player, String reason) {
        MineOnlineBroadcast.lastPing = 0;
    }

    public void onDisconnect(Player player) {
        MineOnlineBroadcast.lastPing = 0;
    }

    public void onLogin(Player player) {
        MineOnlineBroadcast.lastPing = 0;
    }
}
