package net.minecraft.client;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Minimal 26.3-shaped fake runtime; does not include any Mojang game code. */
public class Minecraft implements Executor {
    private static final Minecraft INSTANCE = new Minecraft();
    public Player player = new Player();
    public Server server = new Server(player);
    public Connection connection;
    public static Minecraft getInstance() { return INSTANCE; }
    public Server getSingleplayerServer() { return server; }
    public Connection getConnection() { return connection; }
    @Override public void execute(Runnable runnable) { runnable.run(); }

    public static class Connection {
        public boolean copyCommandAvailable;
        public String sentCommand;

        public CommandTree getCommands() {
            return new CommandTree(copyCommandAvailable);
        }

        public void sendCommand(String command) {
            sentCommand = command;
        }
    }

    public record CommandTree(boolean copyCommandAvailable) {
        public CommandTree getRoot() {
            return this;
        }

        public Object getChild(String name) {
            return copyCommandAvailable && name.equals("hoplitercopy") ? this : null;
        }
    }

    public static class Player {
        private final UUID uuid = UUID.randomUUID();
        private final Inventory inventory = new Inventory();
        public UUID getUUID() { return uuid; }
        public Inventory getInventory() { return inventory; }
    }
    public static class Server implements Executor {
        private final Player player;
        public Server(Player p) { player = p; }
        public PlayerList getPlayerList() { return new PlayerList(player); }
        @Override public void execute(Runnable runnable) { runnable.run(); }
    }
    public static class PlayerList {
        private final Player player;
        public PlayerList(Player p) { player = p; }
        public Player getPlayer(UUID uuid) { return player.getUUID().equals(uuid) ? player : null; }
    }
    public static class ItemStack {
        public final String uid;
        public final int count;
        public ItemStack(String uid, int count) { this.uid=uid; this.count=count; }
        public boolean isEmpty() { return count == 0; }
        public ItemStack copy() { return new ItemStack(uid, count); }
    }
    public static class Inventory {
        private final ItemStack[] items = new ItemStack[36];
        public boolean changed;
        public Inventory() { for (int i=0;i<36;i++) items[i]=new ItemStack("",0); }
        public int getFreeSlot() { for (int i=0;i<36;i++) if(items[i].isEmpty())return i; return -1; }
        public ItemStack getItem(int index) { return items[index]; }
        public void setItem(int index, ItemStack stack) { items[index]=stack; }
        public void setChanged() { changed=true; }
    }
}
